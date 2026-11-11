"""
공개 HAR 사전학습 체크포인트를 못 구했을 때의 대안.
UCI HAR 공개 데이터셋으로 "일반 행동 인식" 백본을 직접 먼저 학습시켜서
finetune.py가 쓸 PRETRAINED_BACKBONE_PATH를 만들어낸다.

데이터 준비 (자동 다운로드 안 함 - 라이선스/링크 수명 문제로 직접 받을 것):
  https://archive.ics.uci.edu/dataset/240/human+activity+recognition+using+smartphones
  압축을 풀면 "UCI HAR Dataset/" 폴더가 생김 -> --uci-root 로 그 경로 지정

UCI HAR가 이 프로젝트와 잘 맞는 이유:
  - 50Hz, 2.56초(128샘플) 창 = MotionBuffer의 ~50Hz, 2.5초 창과 거의 동일
  - Inertial Signals의 body_acc(중력 제거 가속도) = 안드로이드 TYPE_LINEAR_ACCELERATION,
    body_gyro = TYPE_GYROSCOPE (rad/s) 와 같은 물리량 -> FEATURES 6채널에 그대로 대응
  - 단, body_acc 단위는 g -> m/s^2 로 변환 필요 (아래 G_TO_MS2)

실행:  python pretrain_backbone_from_scratch.py --uci-root "./data/UCI HAR Dataset"
"""
import argparse
import os

import numpy as np
from tensorflow import keras

from data_loader import WINDOW_LEN, FEATURES

NUM_GENERIC_ACTIVITY_CLASSES = 6  # UCI HAR 기준: walking, upstairs, downstairs, sitting, standing, laying
G_TO_MS2 = 9.80665
OUTPUT_PATH = "./pretrained/har_backbone.keras"

# FEATURES(ax, ay, az, gx, gy, gz) 순서와 일치해야 함
_UCI_SIGNALS = [("body_acc_x", G_TO_MS2), ("body_acc_y", G_TO_MS2), ("body_acc_z", G_TO_MS2),
                ("body_gyro_x", 1.0), ("body_gyro_y", 1.0), ("body_gyro_z", 1.0)]


def resample_batch(x: np.ndarray, target_len: int = WINDOW_LEN) -> np.ndarray:
    """(N, T, C) -> (N, target_len, C). data_loader._resample_window와 같은 인덱스 기준 선형보간."""
    n, t, c = x.shape
    orig_idx = np.linspace(0, 1, num=t)
    target_idx = np.linspace(0, 1, num=target_len)
    out = np.empty((n, target_len, c), dtype=np.float32)
    for ch in range(c):
        out[:, :, ch] = np.stack([np.interp(target_idx, orig_idx, row) for row in x[:, :, ch]])
    return out


def load_uci_har(root: str, split: str) -> tuple[np.ndarray, np.ndarray]:
    """split = "train" | "test". 반환 X: (N, WINDOW_LEN, 6) float32 (m/s^2, rad/s), y: (N,) 0..5"""
    signals = []
    for name, scale in _UCI_SIGNALS:
        path = os.path.join(root, split, "Inertial Signals", f"{name}_{split}.txt")
        signals.append(np.loadtxt(path, dtype=np.float32) * scale)  # (N, 128)
    x = np.stack(signals, axis=-1)  # (N, 128, 6)
    y = np.loadtxt(os.path.join(root, split, f"y_{split}.txt"), dtype=np.int32) - 1
    return resample_batch(x), y


def build_backbone(input_len: int = WINDOW_LEN, num_features: int = len(FEATURES),
                   normalization: keras.layers.Normalization | None = None) -> keras.Model:
    """1D CNN 기반 경량 시계열 인코더. 온디바이스 TFLite 변환을 고려해 단순한 구조로 유지."""
    inputs = keras.Input(shape=(input_len, num_features))
    # 채널별 표준화를 모델 안에 포함 -> 앱/파인튜닝이 별도 정규화 통계를 관리할 필요 없음
    x = normalization(inputs) if normalization is not None else inputs
    x = keras.layers.Conv1D(32, kernel_size=5, activation="relu", padding="same")(x)
    x = keras.layers.MaxPooling1D(2)(x)
    x = keras.layers.Conv1D(64, kernel_size=5, activation="relu", padding="same")(x)
    x = keras.layers.GlobalAveragePooling1D()(x)
    x = keras.layers.Dense(64, activation="relu")(x)  # 이 출력이 finetune.py의 특징 벡터가 됨 (layers[-2])
    outputs = keras.layers.Dense(NUM_GENERIC_ACTIVITY_CLASSES, activation="softmax")(x)
    return keras.Model(inputs, outputs, name="har_backbone")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--uci-root", required=True, help='압축 해제한 "UCI HAR Dataset" 폴더 경로')
    parser.add_argument("--epochs", type=int, default=30)
    args = parser.parse_args()

    X_train, y_train = load_uci_har(args.uci_root, "train")
    X_test, y_test = load_uci_har(args.uci_root, "test")
    print(f"[pretrain] train {X_train.shape}, test {X_test.shape}")

    norm = keras.layers.Normalization(axis=-1)
    norm.adapt(X_train)

    model = build_backbone(normalization=norm)
    model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    model.fit(X_train, y_train, epochs=args.epochs, batch_size=64, validation_split=0.1)
    _, test_acc = model.evaluate(X_test, y_test, verbose=0)
    print(f"[pretrain] UCI HAR test accuracy = {test_acc:.3f}")

    os.makedirs(os.path.dirname(OUTPUT_PATH), exist_ok=True)
    model.save(OUTPUT_PATH)
    print(f"[pretrain] 백본 저장: {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
