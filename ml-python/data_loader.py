"""
서버(Java)의 GET /api/v1/events/dataset/export 에서 내려주는 JSONL을 읽어
학습용 (X, y) 배열로 변환한다.

각 줄 형식:
{"risk_score": 0.82, "reason": "...", "repeat_count": 2,
 "confirmed_intentional": false,
 "raw_sensor_window": [{"ax":..,"ay":..,"az":..,"gx":..,"gy":..,"gz":..,"t":..}, ...]}
"""
import json
import os

import numpy as np
import requests

WINDOW_LEN = 96  # 리샘플링 후 고정 길이 (2.5s @ ~38Hz 근사 - 실측 후 조정 필요)
FEATURES = ["ax", "ay", "az", "gx", "gy", "gz"]
# 안드로이드 TfliteGestureClassifier.resample()이 이 방식(인덱스 기준 선형보간)을 그대로 재현함.
# 여기를 바꾸면 반드시 안드로이드 쪽도 같이 바꿀 것 (학습/추론 전처리 불일치 = 조용한 성능 붕괴).
RESAMPLE_METHOD = "linear_by_index"


def load_from_server(server_url: str, admin_key: str | None = None, after_id: int = 0) -> list[dict]:
    """서버 export는 원본 센서 시계열 전체가 나가므로 관리자 키(X-Admin-Key) 필요."""
    admin_key = admin_key or os.environ.get("NFCGUARD_ADMIN_API_KEY")
    if not admin_key:
        raise ValueError("NFCGUARD_ADMIN_API_KEY 환경변수(서버 nfcguard.admin.api-key와 같은 값)가 필요함")
    resp = requests.get(
        f"{server_url}/api/v1/events/dataset/export",
        params={"afterId": after_id},
        headers={"X-Admin-Key": admin_key},
        timeout=30,
    )
    resp.raise_for_status()
    return [json.loads(line) for line in resp.text.strip().split("\n") if line.strip()]


def load_from_file(path: str) -> list[dict]:
    with open(path, "r", encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def _resample_window(raw_window: list[dict], target_len: int = WINDOW_LEN) -> np.ndarray:
    """가변 길이 센서 시퀀스를 고정 길이로 선형 보간. HAR 백본 입력 shape을 맞추기 위함."""
    if len(raw_window) < 2:
        return np.zeros((target_len, len(FEATURES)), dtype=np.float32)

    arr = np.array([[s.get(f, 0.0) for f in FEATURES] for s in raw_window], dtype=np.float32)
    orig_idx = np.linspace(0, 1, num=len(arr))
    target_idx = np.linspace(0, 1, num=target_len)

    resampled = np.zeros((target_len, len(FEATURES)), dtype=np.float32)
    for col in range(len(FEATURES)):
        resampled[:, col] = np.interp(target_idx, orig_idx, arr[:, col])
    return resampled


def build_dataset(records: list[dict]) -> tuple[np.ndarray, np.ndarray]:
    """
    반환:
      X: shape (N, WINDOW_LEN, len(FEATURES))
      y: shape (N,)  - 1 = 정상(의도적 태깅), 0 = 의심(비정상)
    """
    xs, ys = [], []
    skipped = 0
    for r in records:
        raw = r.get("raw_sensor_window")
        label = r.get("confirmed_intentional")
        if raw is None or label is None:
            skipped += 1
            continue
        xs.append(_resample_window(raw))
        ys.append(1 if label else 0)

    if skipped:
        print(f"[data_loader] raw_sensor_window 또는 라벨 없는 레코드 {skipped}건 스킵")

    if not xs:
        raise ValueError("파인튜닝할 라벨링된 데이터가 없음 - 셀프리포트 확정 이벤트가 더 쌓여야 함")

    return np.stack(xs), np.array(ys, dtype=np.int32)


def stratified_split(X: np.ndarray, y: np.ndarray, val_fraction: float = 0.2, seed: int = 42):
    """
    클래스 비율을 유지한 채 셔플 분할.
    (keras의 validation_split은 셔플 없이 "마지막 N%"를 쓰므로, id(=시간) 순서 데이터에서는
     검증셋이 최근 데이터로만 채워지고 희소한 의심 클래스가 아예 빠질 수 있음)
    """
    rng = np.random.default_rng(seed)
    train_idx, val_idx = [], []
    for cls in np.unique(y):
        idx = np.flatnonzero(y == cls)
        rng.shuffle(idx)
        n_val = int(round(len(idx) * val_fraction))
        if len(idx) >= 2:
            n_val = min(max(n_val, 1), len(idx) - 1)  # 클래스마다 학습/검증 양쪽에 최소 1개
        else:
            n_val = 0
        val_idx.extend(idx[:n_val])
        train_idx.extend(idx[n_val:])
    train_idx, val_idx = np.array(train_idx), np.array(val_idx)
    rng.shuffle(train_idx)
    rng.shuffle(val_idx)
    return X[train_idx], y[train_idx], X[val_idx], y[val_idx]
