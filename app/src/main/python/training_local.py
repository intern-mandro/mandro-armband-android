"""
training_local.py
==================
Chaquopy를 통해 Kotlin에서 호출되는 로컬 학습 엔트리 포인트.
전처리/피처추출/윈도잉 로직은 lib/ 이하 모듈에서 가져다 쓴다 (mandro-backend와 동일 구조).

가중치 직렬화 순서는 펌웨어(LittleFS 로더)와 반드시 일치해야 함:
    W0(132x64) b0(64) W1(64x64) b1(64) W2(64x6) b2(6) means(132) stds(132) = 53,304 bytes
"""

import traceback

import numpy as np

from lib.config import RANDOM_STATE
from lib.preprocessing import preprocess_and_extract
from lib.training import fit_local_model
from lib.serialize import serialize_weights


def run_training_local(emg_bytes: bytes, labels_bytes: bytes) -> bytes:
    """
    Kotlin에서 호출하는 최상위 진입점.

    Parameters
    ----------
    emg_bytes : bytes
        raw EMG, uint8 flat array (N * N_CHANNELS,) row-major
    labels_bytes : bytes
        int32 flat array (N,)

    Returns
    -------
    bytes
        직렬화된 가중치+스케일러 바이너리 (53,304 bytes). BLE/USB 전송 프로토콜에
        얹기 전 단계의 payload (매직넘버/CRC는 Kotlin 쪽 전송 코드에서 붙임).
    """
    try:
        raw_emg = np.frombuffer(emg_bytes, dtype=np.uint8).reshape(-1, 8)
        labels = np.frombuffer(labels_bytes, dtype=np.int32)

        X, y = preprocess_and_extract(raw_emg, labels)
        model, scaler = fit_local_model(X, y)

        return serialize_weights(model, scaler)
    except Exception as e:
        print(f"Error during local training: {str(e)}")
        traceback.print_exc()
        return b""
