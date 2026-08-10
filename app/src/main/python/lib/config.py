"""
config.py
=========
mandro-backend/app/config.py 와 반드시 값이 일치해야 함 (펌웨어/서버/앱 3곳 동기화).
"""

SAMPLING_FREQ = 1200
WINDOW_SIZE = 128
N_CHANNELS = 8

LOWCUT = 35.0
HIGHCUT = 300.0
FILTER_ORDER = 4
KERNEL_RATIO = 0.10

DELAY_SAMPLES = int(SAMPLING_FREQ * 0.5)

FEATURE_MODE = "concat"
TSD_WIN_MS = 80
TSD_INC_MS = 40
TSD_LAM = 0.1

GESTURES = ["rest", "flexion", "extension", "close", "supination", "pronation"]
N_CLASSES = len(GESTURES)

VAL_RATIO = 0.20
RANDOM_STATE = 42

WEIGHT_MAGIC = 0xDEADBEEF

# 페이로드 구성: NN 가중치(W0 b0 W1 b1 W2 b2) + StandardScaler(mean, std)
# 13,062 floats(NN) + 132(mean) + 132(std) = 13,326 floats = 53,304 bytes
N_FEATURES = 132
NN_WEIGHT_BYTES = 52_248
SCALER_BYTES = N_FEATURES * 4 * 2  # mean 132 + std 132, float32
WEIGHT_TOTAL_BYTES = NN_WEIGHT_BYTES + SCALER_BYTES
