"""
사전학습된 HAR(Human Activity Recognition) 백본을 불러와서,
NFC Guard 자체 라벨 데이터(의도적 태깅 vs 비정상 필드응답)로 분류 헤드만 파인튜닝한다.

중요 — 반드시 읽을 것:
"NFC 태깅 의도 감지"에 특화된 사전학습 모델은 세상에 존재하지 않는다.
여기서 말하는 "사전학습 모델"은 가속도계/자이로 기반 일반 행동 인식(HAR)
공개 모델(UCI HAR, HHAR 데이터셋 등으로 학습된 것)을 뜻하며,
이 모델이 배운 "모션 신호의 일반적 특징 표현"을 백본으로 재사용하는 것이다.

이 스크립트는 PRETRAINED_BACKBONE_PATH에 실제 체크포인트 파일이 있다고 가정한다.
체크포인트 자체는 아래 중 하나로 준비해야 함 (이 스크립트가 대신 받아오지 않음 -
공개 체크포인트 URL은 시점에 따라 사라지거나 라이선스 조건이 바뀔 수 있어
자동 다운로드 대신 직접 받아 로컬에 두는 방식을 권장):
  1) 공개 HAR 프로젝트(예: on-device-activity-recognition, HHAR 사전학습)의
     릴리즈 체크포인트를 받아 PRETRAINED_BACKBONE_PATH에 배치
  2) 위 데이터가 없다면 pretrain_backbone_from_scratch.py 로 UCI HAR 등
     공개 데이터셋을 이용해 자체적으로 백본을 먼저 만들어도 됨
     (이 경우 백본 첫 층에 입력 정규화가 포함되어 있어 앱은 원시 센서값을 그대로 넣으면 됨.
      외부 체크포인트를 쓸 땐 그 모델이 기대하는 단위/정규화를 반드시 확인할 것)

실행:
  NFCGUARD_ADMIN_API_KEY=... python finetune.py [--signing-key ./keys/model_signing_private.pem]
산출물은 기본적으로 ../server-java/models 에 쓰여 서버가 바로 앱에 배포한다 (NFCGUARD_MODEL_DIR로 변경).
"""
import argparse
import os

import numpy as np
import tensorflow as tf
from tensorflow import keras

from data_loader import load_from_server, build_dataset, stratified_split, WINDOW_LEN, FEATURES, RESAMPLE_METHOD
from model_export import build_metadata, choose_threshold, roc_auc, write_artifacts

PRETRAINED_BACKBONE_PATH = "./pretrained/har_backbone.keras"
# server-java의 nfcguard.model.dir(기본 ./models, server-java 기준)과 같은 위치
MODEL_OUTPUT_DIR = os.environ.get("NFCGUARD_MODEL_DIR", "../server-java/models")
SERVER_URL = os.environ.get("NFCGUARD_SERVER_URL", "http://localhost:8080")

FINE_TUNE_EPOCHS = 15
BATCH_SIZE = 16
VALIDATION_SPLIT = 0.2


def load_backbone(path: str) -> keras.Model:
    try:
        backbone = keras.models.load_model(path)
    except (IOError, OSError, ValueError) as e:  # Keras 3는 파일 없음을 ValueError로 냄
        raise FileNotFoundError(
            f"사전학습 백본을 {path} 에서 못 찾음. 파일 상단 주석의 준비 방법을 먼저 진행할 것."
        ) from e

    # 마지막 분류층(원래 HAR의 6개 활동 클래스 등)을 제거하고 특징 추출기로만 사용
    feature_extractor = keras.Model(
        inputs=backbone.input,
        outputs=backbone.layers[-2].output,
        name="har_feature_extractor",
    )
    # 백본 가중치는 고정 (catastrophic forgetting 방지) - 데이터가 충분히 쌓이면
    # 이후 단계에서 일부 상위 레이어만 unfreeze해서 추가 파인튜닝 고려
    feature_extractor.trainable = False
    return feature_extractor


def build_finetune_model(feature_extractor: keras.Model) -> keras.Model:
    inputs = keras.Input(shape=(WINDOW_LEN, len(FEATURES)))
    x = feature_extractor(inputs, training=False)
    x = keras.layers.Dense(32, activation="relu")(x)
    x = keras.layers.Dropout(0.3)(x)
    outputs = keras.layers.Dense(1, activation="sigmoid", name="intentional_tap_prob")(x)

    model = keras.Model(inputs, outputs)
    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=1e-3),
        loss="binary_crossentropy",
        metrics=["accuracy", keras.metrics.AUC(name="auc")],
    )
    return model


def convert_tflite(model: keras.Model) -> bytes:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]  # 온디바이스용 경량화
    return converter.convert()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--signing-key", default=os.environ.get("NFCGUARD_MODEL_SIGNING_KEY"),
                        help="model_export.py --generate-key 로 만든 개인키 PEM")
    parser.add_argument("--dataset-file", help="서버 대신 JSONL 파일에서 읽기 (오프라인 실험용)")
    args = parser.parse_args()

    if args.dataset_file:
        from data_loader import load_from_file
        records = load_from_file(args.dataset_file)
    else:
        print("[finetune] 서버에서 라벨링 데이터 가져오는 중...")
        records = load_from_server(SERVER_URL)
    X, y = build_dataset(records)
    print(f"[finetune] 데이터 {len(X)}건 (정상 {int(y.sum())} / 의심 {len(y) - int(y.sum())})")

    if len(X) < 50:
        print(
            "[finetune] 경고: 데이터가 50건 미만. 이 정도 규모로는 파인튜닝해도 "
            "일반화 성능을 신뢰하기 어려움 - 배포 전 반드시 홀드아웃 검증 필요."
        )

    X_train, y_train, X_val, y_val = stratified_split(X, y, VALIDATION_SPLIT)

    feature_extractor = load_backbone(PRETRAINED_BACKBONE_PATH)
    model = build_finetune_model(feature_extractor)

    model.fit(
        X_train, y_train,
        epochs=FINE_TUNE_EPOCHS,
        batch_size=BATCH_SIZE,
        validation_data=(X_val, y_val) if len(X_val) else None,
        class_weight=_compute_class_weight(y_train),
    )

    # 검증셋으로 앱이 쓸 판정 임계값과 지표 산출 (임계값 0.5 고정은 클래스 불균형에서 부적절)
    p_val = model.predict(X_val, verbose=0).ravel() if len(X_val) else np.array([])
    thr = choose_threshold(y_val, p_val) if len(X_val) else {"threshold": 0.5}
    metrics = {
        "n_train": int(len(X_train)),
        "n_val": int(len(X_val)),
        "n_val_suspicious": int((y_val == 0).sum()),
        # 탐지 대상(의심) 기준 AUC: 의심일수록 점수가 높도록 1-p 사용
        "val_auc_suspicious": roc_auc(y_val == 0, 1.0 - p_val) if len(X_val) else None,
        **{k: v for k, v in thr.items() if k != "threshold"},
    }
    print(f"[finetune] 임계값={thr['threshold']:.3f} 지표={metrics}")

    tflite_bytes = convert_tflite(model)
    metadata = build_metadata(tflite_bytes, WINDOW_LEN, FEATURES, RESAMPLE_METHOD, thr["threshold"], metrics)
    write_artifacts(MODEL_OUTPUT_DIR, tflite_bytes, metadata, args.signing_key)


def _compute_class_weight(y: np.ndarray) -> dict:
    # 정상 태깅 사례가 압도적으로 많고 의심 사례는 희소할 가능성이 높음 -> 클래스 불균형 보정
    n_pos = int(y.sum())
    n_neg = len(y) - n_pos
    if n_pos == 0 or n_neg == 0:
        return {0: 1.0, 1: 1.0}
    total = len(y)
    return {0: total / (2 * n_neg), 1: total / (2 * n_pos)}


if __name__ == "__main__":
    main()
