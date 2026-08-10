"""
features.py
===========
mandro-backend/app/ml/lib/features/features.py 에서 그대로 복사해오면 됨
(TensorFlow 의존성 없음).

필요한 함수:
    extract_features(X, sampling_frequency_EMG)      -> classic 88차원
    extract_features_tsd(X, sampling_frequency_EMG, win_ms, inc_ms, lam) -> TSD 44차원
"""

import numpy as np


# =============================================================================
# Classic time-domain features (전통적 시간 영역 특징)
# =============================================================================

def MAV(window: np.ndarray) -> np.ndarray:
    """Mean Absolute Value - shape (C,)"""
    return np.mean(np.abs(window), axis=0)


def RMS(window: np.ndarray) -> np.ndarray:
    """Root Mean Square - shape (C,)"""
    return np.sqrt(np.mean(window ** 2, axis=0))


def WL(window: np.ndarray) -> np.ndarray:
    """Waveform Length - shape (C,)"""
    return np.sum(np.abs(np.diff(window, axis=0)), axis=0)


def SSC(window: np.ndarray) -> np.ndarray:
    """Slope Sign Change count - shape (C,)"""
    diff1 = np.diff(window, axis=0)
    signs = np.sign(diff1)
    changes = np.diff(signs, axis=0)
    return np.sum(changes != 0, axis=0).astype(float)


def MaxAV(window: np.ndarray) -> np.ndarray:
    """Maximum Absolute Value - shape (C,)"""
    return np.max(np.abs(window), axis=0)


def STD(window: np.ndarray) -> np.ndarray:
    """Standard Deviation - shape (C,)"""
    return np.std(window, axis=0)


# =============================================================================
# Frequency-domain features
# =============================================================================

def _freq_features(window: np.ndarray, fs: int) -> np.ndarray:
    """
    Compute 5 frequency-domain features for each channel.

    Returns
    -------
    np.ndarray  shape (5 * C,)
        [MeanPower, TotalPower, MeanFreq, MedianFreq, PeakFreq] x channels
    """
    N, C = window.shape
    freq = np.fft.rfftfreq(N, 1.0 / fs)
    fft_power = np.abs(np.fft.rfft(window, axis=0)) ** 2  # (N//2+1, C)

    features = []
    for ch in range(C):
        p = fft_power[:, ch]
        total = p.sum() + 1e-12
        mean_pow = p.mean()
        total_pow = total
        mean_freq = np.sum(freq * p) / total
        cumsum = np.cumsum(p)
        med_idx = min(np.searchsorted(cumsum, p.sum() / 2), len(freq) - 1)
        med_freq = freq[med_idx]
        peak_freq = freq[np.argmax(p)]
        features.extend([mean_pow, total_pow, mean_freq, med_freq, peak_freq])

    return np.array(features)


# =============================================================================
# TSD features - Temporal-Spatial Descriptors (Khushaba et al., 2017)
# =============================================================================

def compute_tsd(
        window: np.ndarray,
        lam: float = 0.1,
) -> np.ndarray:
    """
    Temporal-Spatial Descriptor (TSD) for one window.

    Parameters
    ----------
    window : np.ndarray  shape (N, C)
    lam    : float       regularization parameter lambda

    Returns
    -------
    np.ndarray  shape (C*(C+1)//2 + C,)
    """
    N, C = window.shape
    cov = np.cov(window.T) + lam * np.eye(C)
    idx = np.triu_indices(C)
    cov_feats = cov[idx]
    energy = np.mean(window ** 2, axis=0)

    return np.concatenate([cov_feats, energy])


# 모든 데이터 창을 돌면서 위에서 만든 6개의 시간 특징과 5개의 주파수 특징을 한 줄로 길게 이어붙여 거대한 데이터 세트를 만들어 줌
def extract_features(
        X: np.ndarray,
        sampling_frequency_EMG: int = 900,
) -> np.ndarray:
    """
    Extract MAV, MaxAV, STD, RMS, WL, SSC + 5 frequency-domain features
    for each window.

    Parameters
    ----------
    X : np.ndarray  shape (n_windows, N_samples, C_channels)
    sampling_frequency_EMG : int

    Returns
    -------
    np.ndarray  shape (n_windows, n_features)
        n_features = (6 time + 5 freq) x C_channels = 11 x C
    """
    all_features = []
    for window in X:
        time_feats = np.concatenate([
            MAV(window),
            MaxAV(window),
            STD(window),
            RMS(window),
            WL(window),
            SSC(window),
        ])
        freq_feats = _freq_features(window, sampling_frequency_EMG)
        all_features.append(np.concatenate([time_feats, freq_feats]))
    return np.array(all_features)


# 하나의 큰 데이터 창을 더 작은 sub window로 쪼개서
# TSD 특징들을 촘촘하게 계산한 뒤 평균을 내어 최종 고급 특징 벡터를 완성함
# [질문] tsd가 뭐더라 뭐 뽑아내는 함수?
def extract_features_tsd(
        X: np.ndarray,
        sampling_frequency_EMG: int = 900,
        win_ms: int = 115,
        inc_ms: int = 57,
        lam: float = 0.1,
) -> np.ndarray:
    """
    Extract TSD features for each window of X.

    Parameters
    ----------
    X                       : np.ndarray  shape (n_windows, N_samples, C_channels)
    sampling_frequency_EMG  : int
    win_ms                  : int  internal sub-window size (ms)
    inc_ms                  : int  sub-window increment (ms)
    lam                     : float

    Returns
    -------
    np.ndarray  shape (n_windows, n_tsd_features)
    """
    win_samples = int(sampling_frequency_EMG * win_ms / 1000)
    inc_samples = int(sampling_frequency_EMG * inc_ms / 1000)

    all_features = []
    for window in X:
        N, C = window.shape
        sub_features = []

        start = 0
        while start + win_samples <= N:
            sub = window[start: start + win_samples]
            sub_features.append(compute_tsd(sub, lam=lam))
            start += inc_samples

        if sub_features:
            feat = np.mean(sub_features, axis=0)
        else:
            feat = compute_tsd(window, lam=lam)

        all_features.append(feat)

    return np.array(all_features)
