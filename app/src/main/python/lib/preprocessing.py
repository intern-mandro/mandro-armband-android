"""
preprocessing.py
================
mandro-backend/app/ml/lib/preprocessing.py 에서 그대로 복사해오면 됨
(TensorFlow 의존성 없음 — numpy/scipy/pandas만 사용).

필요한 함수:
    BP_filter(df, lowcut, highcut, sampling_frequency_EMG, order, labels)
    compute_envelope(values, size)

이 파일에 추가로 구현할 것:
    preprocess_and_extract(raw_emg, labels) -> (X: np.ndarray, y: np.ndarray)
        mandro-backend/app/services/training.py 의 _preprocess_and_extract()를
        참고해서 이식. lib.pipeline.extract_features_pipeline, lib.windowing 사용.
"""

import numpy as np
import pandas as pd
from scipy.signal import butter, lfilter, lfilter_zi

from lib.config import (
    DELAY_SAMPLES, N_CHANNELS,
    LOWCUT, HIGHCUT, SAMPLING_FREQ, FILTER_ORDER,
    WINDOW_SIZE, KERNEL_RATIO,
    FEATURE_MODE, TSD_WIN_MS, TSD_INC_MS, TSD_LAM,
    GESTURES,
)


def BP_filter(
        df: pd.DataFrame,
        lowcut: float = 35.0, # 저주파 노이즈 (몸의 움직임으로 인한 흔들림)
        highcut: float = 300.0, # 고주파 노이즈 (주변 전자제품의 전자기적 간섭 등)
        sampling_frequency_EMG: int = 900,
        order: int = 4,
        labels: list | None = None,
) -> np.ndarray:
    """
    Causal Butterworth band-pass filter (matches Teensy real-time behavior).
    Single forward pass via lfilter; lfilter_zi initialization reduces
    startup transient.
    """
    if labels is None:
        labels = [c for c in df.columns
                  if c.startswith('Raw_CH') or c.startswith('Ch')]

    nyq = sampling_frequency_EMG / 2.0
    b, a = butter(order, [lowcut / nyq, highcut / nyq], btype='band')

    signal = df[labels].values.astype(float)
    filtered = np.zeros_like(signal)
    zi_template = lfilter_zi(b, a) # 필터가 처음 켜질 때 값이 튀는 현상을 방지

    for ch in range(signal.shape[1]):
        zi = zi_template * signal[0, ch]
        filtered[:, ch], _ = lfilter(b, a, signal[:, ch], zi=zi)

    return filtered


# 근전도 신호의 전체적인 테두리를 부드럽게 그려줌
def compute_envelope(
        signal: np.ndarray,
        size: int = 10,
) -> np.ndarray:
    """
    Causal moving-average envelope: y[t] uses only signal[t-size+1 : t+1].
    Matches a sliding-window mean implemented with a circular buffer in C++.
    """
    envelope = np.zeros_like(signal, dtype=float)
    kernel = np.ones(size) / size

    for ch in range(signal.shape[1]):
        full = np.convolve(signal[:, ch], kernel, mode='full')
        envelope[:, ch] = full[:signal.shape[0]]

    return envelope

# raw EMG + 레이블 → 132차원 피처 배열로 변환 (mandro-backend의 _preprocess_and_extract 이식)
def preprocess_and_extract(
        raw_emg: np.ndarray,
        labels: np.ndarray,
) -> tuple[np.ndarray, np.ndarray]:
    """
    raw EMG (uint8 N×8) → 피처 배열 (W×132) + 레이블 배열 (W,)

    raw_emg 값 범위: 0~255 (펌웨어에서 ADC - 250, clip [0,255])
    → float32로 변환 후 필터 적용

    gestures는 로컬 앱에서 항상 6클래스로 고정이라 lib.config.GESTURES를 그대로 씀.
    """
    from lib.pipeline import extract_features_pipeline
    from lib.windowing import get_windows, get_action_windows

    gestures = GESTURES

    signal = raw_emg.astype(np.float32)  # (N, 8)

    # 초반 아티팩트 제거
    signal = signal[DELAY_SAMPLES:]
    labels = labels[DELAY_SAMPLES:]

    # DataFrame으로 감싸서 BP_filter에 전달
    ch_cols = [f"Raw_CH{i}" for i in range(N_CHANNELS)]
    df = pd.DataFrame(signal, columns=ch_cols)
    df["Label"] = labels

    filtered = BP_filter(
        df,
        lowcut=LOWCUT,
        highcut=HIGHCUT,
        sampling_frequency_EMG=SAMPLING_FREQ,
        order=FILTER_ORDER,
        labels=ch_cols,
    )
    df[ch_cols] = filtered
    df[ch_cols] = np.abs(df[ch_cols])

    kernel_size = max(1, int(WINDOW_SIZE * KERNEL_RATIO))
    envelope = compute_envelope(df[ch_cols].values, size=kernel_size)
    df[ch_cols] = envelope

    # labels는 앱에서 int32 (0=rest … 5=pronation)로 전송됨
    # 유효 범위(0 ~ n_classes-1)만 사용
    n_classes = len(gestures)
    df["Action"] = df["Label"].astype(int)
    df = df[(df["Action"] >= 0) & (df["Action"] < n_classes)].reset_index(drop=True)

    # 비중첩 윈도우 분할
    windows = get_windows(df, WINDOW_SIZE)

    X_list, y_list = [], []
    for w in windows:
        action = get_action_windows(w)
        if action < 0:
            continue
        raw_win = w[ch_cols].values  # (WINDOW_SIZE, 8)
        X_list.append(raw_win)
        y_list.append(action)

    if not X_list:
        raise ValueError("유효한 윈도우가 없습니다. 데이터를 확인하세요.")

    X_3d = np.stack(X_list)  # (W, WINDOW_SIZE, 8)
    y    = np.array(y_list, dtype=np.int32)

    X_feat = extract_features_pipeline(
        X_3d,
        sampling_frequency_EMG=SAMPLING_FREQ,
        mode=FEATURE_MODE,
        tsd_win_ms=TSD_WIN_MS,
        tsd_inc_ms=TSD_INC_MS,
        tsd_lam=TSD_LAM,
    )  # (W, 132)

    return X_feat, y
