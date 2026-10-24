"""TensorFlow 없이 도는 파이프라인 단위 테스트:  python -m pytest tests"""
import base64
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from data_loader import _resample_window, build_dataset, stratified_split  # noqa: E402
from model_export import (METADATA_FILENAME, MODEL_FILENAME, SIGNATURE_FILENAME, build_metadata,  # noqa: E402
                          choose_threshold, generate_key, roc_auc, write_artifacts)


def test_resample_matches_android_fixture():
    # android GestureModelTest.resampleMatchesPythonDataLoader 와 같은 입력/기대값
    win = [{"ax": float(k), "ay": float(k * k), "az": -1.0, "gx": 0.5 * k, "gy": 0, "gz": 2.0} for k in range(5)]
    out = _resample_window(win, 4)
    np.testing.assert_allclose(out[1], [1.333333, 2.0, -1.0, 0.666667, 0.0, 2.0], atol=1e-5)
    np.testing.assert_allclose(out[3], [4.0, 16.0, -1.0, 2.0, 0.0, 2.0], atol=1e-6)


def test_build_dataset_skips_unlabeled():
    recs = [{"raw_sensor_window": [{"ax": 1}, {"ax": 2}], "confirmed_intentional": True},
            {"raw_sensor_window": None, "confirmed_intentional": False},
            {"raw_sensor_window": [{"ax": 1}, {"ax": 2}], "confirmed_intentional": None}]
    X, y = build_dataset(recs)
    assert X.shape == (1, 96, 6) and y.tolist() == [1]


def test_stratified_split_keeps_rare_class_in_both_sides():
    y = np.array([1] * 95 + [0] * 5)
    X = np.arange(100).reshape(100, 1, 1).astype(np.float32)
    X_tr, y_tr, X_val, y_val = stratified_split(X, y, 0.2)
    assert (y_val == 0).sum() >= 1 and (y_tr == 0).sum() >= 1
    assert len(X_tr) + len(X_val) == 100
    assert set(X_tr.ravel()).isdisjoint(set(X_val.ravel()))


def test_roc_auc_and_threshold():
    y_int = np.array([1, 1, 1, 0, 0])
    p = np.array([0.9, 0.8, 0.7, 0.3, 0.2])
    assert roc_auc(y_int == 0, 1 - p) == 1.0
    thr = choose_threshold(y_int, p)
    assert 0.3 < thr["threshold"] <= 0.7 and thr["tpr"] == 1.0 and thr["fpr"] == 0.0


def test_write_artifacts_signs_metadata(tmp_path):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    generate_key(str(tmp_path / "keys"))
    tflite = b"fake-model"
    meta = build_metadata(tflite, 96, ["ax"], "linear_by_index", 0.6, {"n_val": 1})
    out = tmp_path / "models"
    write_artifacts(str(out), tflite, meta, str(tmp_path / "keys" / "model_signing_private.pem"))

    assert (out / MODEL_FILENAME).read_bytes() == tflite
    meta_bytes = (out / METADATA_FILENAME).read_bytes()
    assert json.loads(meta_bytes)["sha256"] == meta["sha256"]
    pub = serialization.load_der_public_key(base64.b64decode((tmp_path / "keys" / "model_signing_public.b64").read_text()))
    sig = base64.b64decode((out / SIGNATURE_FILENAME).read_text())
    pub.verify(sig, meta_bytes, ec.ECDSA(hashes.SHA256()))  # 실패 시 InvalidSignature

    # 서명 키 없이 다시 쓰면 이전 서명 파일은 지워져야 함 (새 메타데이터와 안 맞는 서명이 남으면 안 됨)
    write_artifacts(str(out), tflite, meta, None)
    assert not (out / SIGNATURE_FILENAME).exists()
    assert not list(out.glob("*.tmp"))
