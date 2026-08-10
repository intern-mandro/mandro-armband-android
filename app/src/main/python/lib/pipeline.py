"""
pipeline.py
===========
mandro-backend/app/ml/lib/pipeline.py 에서 필요한 부분만 복사해오면 됨.
서버 원본은 LDA 관련 함수도 있는데(fit_lda 등) 로컬 파이프라인엔 필요 없음 —
extract_features_pipeline / fit_scaler / apply_scaler 만 이식.
"""

import numpy as np
from sklearn.preprocessing import StandardScaler

from lib.features.features import extract_features, extract_features_tsd


# 특징 추출 제어
# 옵션 종류: classic, tsd, concat (기본 모드)
def extract_features_pipeline(
        X: np.ndarray,
        sampling_frequency_EMG: int = 900,
        mode: str = "concat",
        tsd_win_ms: int = 115,
        tsd_inc_ms: int = 57,
        tsd_lam: float = 0.1,
) -> np.ndarray:
    """
    Extract features according to the selected mode.

    Parameters
    ----------
    X : np.ndarray shape (n_windows, N_samples, C_channels)
    sampling_frequency_EMG : int
    mode : str 'classic' | 'tsd' | 'concat'
    tsd_win_ms, tsd_inc_ms, tsd_lam : TSD parameters

    Returns
    -------
    np.ndarray shape (n_windows, n_features)
    """
    if mode == "classic":

        return extract_features(
            X,
            sampling_frequency_EMG
        )

    elif mode == "tsd":

        return extract_features_tsd(
            X,
            sampling_frequency_EMG,
            win_ms=tsd_win_ms,
            inc_ms=tsd_inc_ms,
            lam=tsd_lam
        )

    elif mode == "concat":

        classic = extract_features(
            X,
            sampling_frequency_EMG
        )

        tsd = extract_features_tsd(
            X,
            sampling_frequency_EMG,
            win_ms=tsd_win_ms,
            inc_ms=tsd_inc_ms,
            lam=tsd_lam
        )

        return np.concatenate(
            [classic, tsd],
            axis=1
        )

    else:
        raise ValueError(
            f"Unknown mode: '{mode}' "
            f"(use 'classic', 'tsd' or 'concat')"
        )


# 추출된 특징들의 단위 맞춰주기
def fit_scaler(
        X_train: np.ndarray
) -> StandardScaler:
    """
    Train a StandardScaler on X_train.
    """
    scaler = StandardScaler()

    scaler.fit(X_train)

    return scaler


def apply_scaler(
        scaler: StandardScaler,
        X: np.ndarray
) -> np.ndarray:
    """
    Apply a pre-trained scaler on X.
    """
    return scaler.transform(X)
