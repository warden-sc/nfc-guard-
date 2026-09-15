"""
서버(Java)의 GET /api/v1/events/dataset/export 에서 내려주는 JSONL을 읽어
학습용 (X, y) 배열로 변환한다.

각 줄 형식:
{"risk_score": 0.82, "reason": "...", "repeat_count": 2,
 "confirmed_intentional": false,
 "raw_sensor_window": [{"ax":..,"ay":..,"az":..,"gx":..,"gy":..,"gz":..,"t":..}, ...]}
"""
import json
import numpy as np
import requests

WINDOW_LEN = 96  # 리샘플링 후 고정 길이 (2.5s @ ~38Hz 근사 - 실측 후 조정 필요)
FEATURES = ["ax", "ay", "az", "gx", "gy", "gz"]


def load_from_server(server_url: str) -> list[dict]:
    resp = requests.get(f"{server_url}/api/v1/events/dataset/export", timeout=30)
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
