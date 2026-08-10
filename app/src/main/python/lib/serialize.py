"""
serialize.py
============
학습된 sklearn MLPClassifier + StandardScaler → 펌웨어 전송용 float32 바이너리.

레이어 순서: W0 b0 W1 b1 W2 b2 means stds
    W0: (132, 64) float32 → 33,792 bytes
    b0: (64,)     float32 →    256 bytes
    W1: (64, 64)  float32 → 16,384 bytes
    b1: (64,)     float32 →    256 bytes
    W2: (64, 6)   float32 →  1,536 bytes
    b2: (6,)      float32 →     24 bytes
    means: (132,) float32 →    528 bytes  (StandardScaler.mean_)
    stds:  (132,) float32 →    528 bytes  (StandardScaler.scale_)
    합계:                    53,304 bytes  (lib.config.WEIGHT_TOTAL_BYTES)

펌웨어가 LittleFS에서 이 순서 그대로 읽어 NN 가중치와 정규화 파라미터를 로드하므로
순서를 바꾸면 안 됨. mean/std 없이는 펌웨어가 입력 피처를 정규화할 수 없어 추론이 틀어진다.
"""

import numpy as np

from lib.config import WEIGHT_TOTAL_BYTES


def serialize_weights(model, scaler) -> bytes:
    """
    Parameters
    ----------
    model : sklearn.neural_network.MLPClassifier (학습 완료 상태)
    scaler : sklearn.preprocessing.StandardScaler (학습 완료 상태)

    Returns
    -------
    bytes, 길이 WEIGHT_TOTAL_BYTES (53,304)
    """
    buffers = []
    for W, b in zip(model.coefs_, model.intercepts_):
        buffers.append(W.astype(np.float32).tobytes())
        buffers.append(b.astype(np.float32).tobytes())
    buffers.append(scaler.mean_.astype(np.float32).tobytes())
    buffers.append(scaler.scale_.astype(np.float32).tobytes())

    result = b"".join(buffers)
    assert len(result) == WEIGHT_TOTAL_BYTES, (
        f"직렬화 크기 불일치: {len(result)} bytes (expected {WEIGHT_TOTAL_BYTES})"
    )
    return result
