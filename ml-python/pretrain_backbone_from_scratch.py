"""
공개 HAR 사전학습 체크포인트를 못 구했을 때의 대안.
UCI HAR 같은 공개 가속도계/자이로 데이터셋으로 "일반 행동 인식" 백본을
직접 먼저 학습시켜서 finetune.py가 쓸 PRETRAINED_BACKBONE_PATH를 만들어낸다.

이 스크립트는 뼈대만 제공한다 - 실제로는:
1) UCI HAR Dataset (https://archive.ics.uci.edu/dataset/240/human+activity+recognition+using+smartphones)
   같은 공개 데이터셋을 직접 다운로드해서 로컬에 준비
2) 아래 build_backbone()의 입력 shape을 그 데이터셋 포맷에 맞게 조정
3) 원 논문/구현체들이 쓰는 전처리(중력성분 분리, 128샘플 슬라이딩 윈도우 등)를
   최대한 맞춰줘야 전이학습 효과가 제대로 남 - 여기서는 생략된 상태
"""
import tensorflow as tf
from tensorflow import keras

from data_loader import WINDOW_LEN, FEATURES

NUM_GENERIC_ACTIVITY_CLASSES = 6  # UCI HAR 기준: walking, upstairs, downstairs, sitting, standing, laying


def build_backbone(input_len: int = WINDOW_LEN, num_features: int = len(FEATURES)) -> keras.Model:
    """1D CNN 기반 경량 시계열 인코더. 온디바이스 TFLite 변환을 고려해 단순한 구조로 유지."""
    inputs = keras.Input(shape=(input_len, num_features))
    x = keras.layers.Conv1D(32, kernel_size=5, activation="relu", padding="same")(inputs)
    x = keras.layers.MaxPooling1D(2)(x)
    x = keras.layers.Conv1D(64, kernel_size=5, activation="relu", padding="same")(x)
    x = keras.layers.GlobalAveragePooling1D()(x)  # 이 출력이 finetune.py의 특징 벡터가 됨
    x = keras.layers.Dense(64, activation="relu")(x)
    outputs = keras.layers.Dense(NUM_GENERIC_ACTIVITY_CLASSES, activation="softmax")(x)
    return keras.Model(inputs, outputs, name="har_backbone")


def main():
    # TODO: UCI HAR 데이터를 (X_train, y_train) 형태로 로드하는 코드로 교체
    raise NotImplementedError(
        "공개 데이터셋 로더는 라이선스/포맷이 자주 바뀌므로 직접 준비 필요. "
        "build_backbone()과 아래 학습 루프 구조만 참고할 것."
    )

    # model = build_backbone()
    # model.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    # model.fit(X_train, y_train, epochs=30, validation_split=0.1)
    # model.save("./pretrained/har_backbone.keras")


if __name__ == "__main__":
    main()
