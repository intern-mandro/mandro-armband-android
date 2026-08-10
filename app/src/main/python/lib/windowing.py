"""
windowing.py
============
mandro-backend/app/ml/lib/windowing.py 에서 필요한 부분만 복사해오면 됨.

필요한 함수:
    get_windows(df, window_size)
    get_action_windows(window, action_col='Action')
    split_train_val(emg, labels, val_size, random_state)
"""

import random

import numpy as np
import pandas as pd


def get_n_windows(
        df: pd.DataFrame,
        window_size: int
) -> int:
    return len(df) // window_size


def get_windows(
        df: pd.DataFrame,
        window_size: int
) -> list[pd.DataFrame]:
    n = get_n_windows(
        df,
        window_size
    )

    return [
        df.iloc[
            i * window_size:
            (i + 1) * window_size
        ].reset_index(drop=True)

        for i in range(n)
    ]


def get_action_windows(
        window: pd.DataFrame,
        action_col: str = 'Action'
) -> int:
    return int(
        window[action_col].mode()[0]
    )


def split_train_test_set(
        takes: list,
        test_ratio: float = 0.2,
        random_state: int | None = None,
) -> tuple[list, list]:

    if random_state is not None:
        random.seed(random_state)

    shuffled = list(takes)

    random.shuffle(shuffled)

    n_test = max(
        1,
        int(len(shuffled) * test_ratio)
    )

    return (
        shuffled[n_test:],
        shuffled[:n_test]
    )


def split_train_val(
        emg: np.ndarray,
        labels: np.ndarray,
        val_size: float = 0.2,
        random_state: int | None = None,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """
    Split feature arrays into training and validation sets
    using random shuffling.

    Returns
    -------
    (emg_train, labels_train, emg_val, labels_val)
    """
    if random_state is not None:
        np.random.seed(random_state)

    N = emg.shape[0]
    N_val = int(N * val_size)
    idx = np.random.permutation(N)

    return (
        emg[idx[N_val:]],
        labels[idx[N_val:]],

        emg[idx[:N_val]],
        labels[idx[:N_val]],
    )

