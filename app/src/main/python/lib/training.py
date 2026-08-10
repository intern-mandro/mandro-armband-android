"""
training.py
============
서버(TensorFlow) 대신 sklearn MLPClassifier로 로컬 학습.
mandro-backend/app/services/training.py::_fit_model 의 로컬 버전.

TF vs sklearn 벤치마크 결과(실제 수집 데이터 2세션 기준):
    - 정확도: sklearn이 세션별로 -1~-3.4%p (근소하게 낮음)
    - 학습 시간: sklearn이 훨씬 빠름 (PC 기준 약 5~12배)
"""

import numpy as np

from lib.config import N_CLASSES, VAL_RATIO, RANDOM_STATE
from lib.windowing import split_train_val
from lib.pipeline import fit_scaler, apply_scaler

# [질문] 학습시키는 함수?
def fit_local_model(X: np.ndarray, y: np.ndarray):
    from sklearn.neural_network import MLPClassifier

    scaler = fit_scaler(X)
    X_scaled = apply_scaler(scaler, X)
    X_train, y_train, X_val, y_val = split_train_val(
        X_scaled, y, val_size=VAL_RATIO, random_state=RANDOM_STATE
    )

    model = MLPClassifier(
        hidden_layer_sizes=(64, 64),
        activation="relu",
        solver="adam",
        max_iter=200,
        early_stopping=True,
        n_iter_no_change=10,
        validation_fraction=0.15,
        random_state=RANDOM_STATE,
    )
    model.fit(X_train, y_train)

    return model, scaler
