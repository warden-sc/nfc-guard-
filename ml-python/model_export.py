"""
파인튜닝 산출물(TFLite + 메타데이터 + 서명)을 서버 모델 디렉터리에 안전하게 내보낸다.

산출물 (server-java ModelDistributionController가 그대로 서빙):
  gesture_classifier.tflite      - 모델
  gesture_classifier.json        - 메타데이터 (버전, sha256, 입력 스펙, 판정 임계값, 검증 지표)
  gesture_classifier.json.sig    - 메타데이터 원문 바이트에 대한 ECDSA P-256(SHA256withECDSA) 서명, base64 DER

왜 서명까지 하나: 모델이 변조되면(서버 침해 등) "항상 정상"을 내는 모델로 탐지 자체를 꺼버릴 수 있음.
앱에는 공개키만 내장(BuildConfig.MODEL_SIGNING_PUBLIC_KEY)하고, 개인키는 학습 머신에만 둔다.
메타데이터에 tflite의 sha256이 들어 있으므로 메타데이터 서명 하나로 모델 파일까지 검증된다.

키 생성:  python model_export.py --generate-key ./keys
"""
import argparse
import base64
import hashlib
import json
import os
import time

import numpy as np

MODEL_FILENAME = "gesture_classifier.tflite"
METADATA_FILENAME = "gesture_classifier.json"
SIGNATURE_FILENAME = METADATA_FILENAME + ".sig"


def roc_auc(y_true: np.ndarray, score: np.ndarray) -> float:
    """sklearn 없이 계산하는 AUC (Mann-Whitney U, 동점은 평균 순위)."""
    y_true = np.asarray(y_true).astype(bool)
    n_pos, n_neg = int(y_true.sum()), int((~y_true).sum())
    if n_pos == 0 or n_neg == 0:
        return float("nan")
    order = np.argsort(score, kind="mergesort")
    ranks = np.empty(len(score), dtype=np.float64)
    sorted_scores = np.asarray(score)[order]
    i = 0
    while i < len(sorted_scores):
        j = i
        while j + 1 < len(sorted_scores) and sorted_scores[j + 1] == sorted_scores[i]:
            j += 1
        ranks[order[i:j + 1]] = (i + j) / 2.0 + 1
        i = j + 1
    return float((ranks[y_true].sum() - n_pos * (n_pos + 1) / 2) / (n_pos * n_neg))


def choose_threshold(y_intentional: np.ndarray, p_intentional: np.ndarray) -> dict:
    """
    p(의도적 태그) < threshold 이면 "의심"으로 판정할 threshold를 Youden's J(TPR-FPR 최대)로 고른다.
    여기서 양성(탐지 대상) = 의심 = 라벨 0.
    """
    suspicious = np.asarray(y_intentional) == 0
    p = np.asarray(p_intentional, dtype=np.float64)
    candidates = np.unique(np.concatenate([p, [0.0, 1.0]]))
    best = {"threshold": 0.5, "tpr": float("nan"), "fpr": float("nan"), "youden_j": -1.0}
    if suspicious.all() or (~suspicious).all():
        return best  # 한 클래스뿐이면 고를 수 없음 -> 기본값
    for thr in candidates:
        flagged = p < thr
        tpr = float((flagged & suspicious).sum() / suspicious.sum())
        fpr = float((flagged & ~suspicious).sum() / (~suspicious).sum())
        if tpr - fpr > best["youden_j"]:
            best = {"threshold": float(thr), "tpr": tpr, "fpr": fpr, "youden_j": tpr - fpr}
    return best


def build_metadata(tflite_bytes: bytes, window_len: int, features: list[str], resample_method: str,
                   threshold: float, metrics: dict, blend_weight: float = 0.5) -> dict:
    return {
        "format": 1,
        "version": int(time.time() * 1000),
        "file": MODEL_FILENAME,
        "sha256": hashlib.sha256(tflite_bytes).hexdigest(),
        "input": {"window_len": window_len, "features": features, "resample": resample_method},
        "output": "intentional_tap_prob",
        # 앱: p < threshold 이면 의심. blend_weight = 휴리스틱(GestureMatcher) 대비 모델 점수 비중
        "threshold": threshold,
        "blend_weight": blend_weight,
        "metrics": metrics,
    }


def sign(data: bytes, private_key_pem_path: str) -> str:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    with open(private_key_pem_path, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), password=None)
    return base64.b64encode(key.sign(data, ec.ECDSA(hashes.SHA256()))).decode("ascii")


def write_artifacts(out_dir: str, tflite_bytes: bytes, metadata: dict, signing_key_path: str | None) -> None:
    """
    tmp 파일에 쓴 뒤 os.replace로 교체(원자적) - 서버가 반쯤 쓰인 모델을 내려주는 일이 없도록.
    순서: 모델 -> 서명 -> 메타데이터. 앱은 메타데이터의 sha256으로 모델을 검증하므로
    교체 중간에 받아가도 불일치로 거절될 뿐 잘못된 모델이 적용되지는 않는다.
    """
    os.makedirs(out_dir, exist_ok=True)
    metadata_bytes = json.dumps(metadata, ensure_ascii=False, indent=2).encode("utf-8")

    files = [(MODEL_FILENAME, tflite_bytes)]
    if signing_key_path:
        files.append((SIGNATURE_FILENAME, sign(metadata_bytes, signing_key_path).encode("ascii")))
    else:
        print("[model_export] 경고: 서명 키 없음 - 앱이 공개키를 내장한 경우 이 모델은 거절됨")
        stale_sig = os.path.join(out_dir, SIGNATURE_FILENAME)
        if os.path.exists(stale_sig):
            os.remove(stale_sig)  # 이전 모델의 서명이 남아 있으면 안 됨
    files.append((METADATA_FILENAME, metadata_bytes))

    for name, data in files:
        final_path = os.path.join(out_dir, name)
        tmp_path = final_path + ".tmp"
        with open(tmp_path, "wb") as f:
            f.write(data)
        os.replace(tmp_path, final_path)
    print(f"[model_export] {out_dir} 에 모델 v{metadata['version']} 기록 완료")


def generate_key(out_dir: str) -> None:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    os.makedirs(out_dir, exist_ok=True)
    key = ec.generate_private_key(ec.SECP256R1())
    priv_path = os.path.join(out_dir, "model_signing_private.pem")
    with open(priv_path, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
    os.chmod(priv_path, 0o600)
    spki = key.public_key().public_bytes(serialization.Encoding.DER,
                                         serialization.PublicFormat.SubjectPublicKeyInfo)
    pub_b64 = base64.b64encode(spki).decode("ascii")
    with open(os.path.join(out_dir, "model_signing_public.b64"), "w") as f:
        f.write(pub_b64)
    print(f"개인키: {priv_path} (학습 머신에만 보관, 커밋 금지)")
    print(f"android/app/build.gradle.kts 의 MODEL_SIGNING_PUBLIC_KEY 에 넣을 공개키:\n{pub_b64}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--generate-key", metavar="OUT_DIR", required=True)
    generate_key(parser.parse_args().generate_key)
