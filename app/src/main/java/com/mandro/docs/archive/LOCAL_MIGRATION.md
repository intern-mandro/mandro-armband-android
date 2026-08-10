> ⚠️ **구버전 문서 (2026-07-22 archive됨)** — 서버 기반 구조를 완전 로컬로
> 바꾸기 **전에** 작성된 설계안. 전환이 이미 끝났고 지금은 이 문서가 제안했던
> 것과 실제 구현 결과가 다른 부분도 있음(예: `means.h`/`stds.h` 별도 헤더
> 파일 없이 `weights.bin`에 포함, SPIFFS 대신 LittleFS 사용). **실제로 구현된
> 최종 아키텍처**는 [`HANDOVER.md`](../../../../../../../../HANDOVER.md)
> §2-1(3단계), §3~5 참고. 배경 설계 의도를 알고 싶을 때만 이 문서를 볼 것.

# 완전 로컬 전환 설계 문서 (방식 A)

서버 기반 학습/빌드 구조를 Android 완전 로컬로 전환하기 위한 변경 사항 정리.

---

## 0. 배경: 가중치란?

가중치(weights)는 **학습이 완료된 결과물**이다. 신경망이 수천 번의 반복을 통해
EMG 패턴을 학습하고 확정된 숫자 배열이며, 이 값이 있어야 추론(분류)이 가능하다.

ESP32는 추론만 할 수 있고 학습 능력이 없다. 그래서 학습은 항상 외부에서 하고,
결과인 가중치만 암밴드에 올려준다.

| | 기존 방식 | A 방식 |
|---|---|---|
| 가중치 전달 | 펌웨어에 하드코딩 후 전체 `.bin` 플래시 | 별도 파일로 전송 → LittleFS 저장 |
| 재학습 시 | 매번 컴파일 + 전체 플래시 필요 | 가중치 파일만 전송 |
| 서버 필요 여부 | 필요 (arduino-cli 컴파일) | 불필요 |

---

## 1. 아키텍처 비교

### 현재 (서버 기반)
```
[수집]   Android ──BLE──→ EMG 데이터 수신
[학습]   Android ──HTTP──→ 서버 (FastAPI)
                            └─ TensorFlow 학습
                            └─ arduino-cli 컴파일 → firmware.bin
[플래시] Android ←─HTTP── firmware.bin 다운로드
         Android ──USB──→ ESP32 전체 플래시
[추론]   ESP32 ──BLE──→ 추론 결과 전송
```

### 목표 (방식 A — 완전 로컬)
```
[수집]   Android ──BLE──→ EMG 데이터 수신
[학습]   Android 내부 (Chaquopy + sklearn) 로컬 학습
[전송]   Android ──BLE 또는 USB Serial──→ 가중치 파일만 전송
[저장]   ESP32 → LittleFS에 weights.bin 저장 → 재부팅
[추론]   ESP32 부팅 시 LittleFS에서 가중치 로드 → BLE로 추론 결과 전송

※ 펌웨어 자체는 최초 1회만 PC에서 USB로 플래시
※ 재학습 시 펌웨어 재플래시 없이 가중치만 교체
```

---

## 2. 변경 구성 요소

### 2-1. 펌웨어 (`firmware/exo_armband_hybrid/`)

기존 펌웨어는 가중치가 `MODEL.h`에 C 배열로 하드코딩되어 있다.
A 방식에서는 LittleFS 파일에서 동적으로 읽도록 변경한다.

#### 동작 모드

| 모드 | 트리거 | 동작 |
|---|---|---|
| **Raw 모드** | 기본 | EMG 원시 데이터를 BLE로 전송 (현재와 동일) |
| **가중치 수신 모드** | 매직 넘버 수신 (BLE Write 또는 USB Serial) | 가중치 바이너리 수신 → LittleFS 저장 → 재부팅 |
| **추론 모드** | LittleFS에 가중치 존재 시 자동 활성 | BLE로 추론 결과 전송 (현재와 동일) |

#### 변경 파일

**`MODEL.h`** — 가중치 배열 제거, 토폴로지 상수만 유지
```cpp
#ifndef MODEL_H
#define MODEL_H

// 가중치는 LittleFS /weights.bin 에서 런타임에 로드
// (기존의 MODEL_WEIGHTS[], MODEL_BIASES[] 배열 제거)

const int MODEL_N_LAYERS = 4;
const int MODEL_TOPOLOGY[4]    = {132, 64, 64, 6};
const int MODEL_ACTIVATIONS[3] = {0, 0, 1};  // relu, relu, softmax

#endif
```

**`nn.h / nn.cpp`** — 생성자에서 LittleFS 파일 로드
```cpp
// nn.h 변경
class NeuralNet {
public:
    NeuralNet();
    bool loadFromLittleFS(const char* path = "/weights.bin");
    bool isLoaded() const { return _loaded; }
    void predict(const float* input, float* output);
    int  argmax(const float* output, int n);

private:
    bool   _loaded  = false;
    float* _weights = nullptr;  // 동적 할당 (총 13,062 floats)
    float* _biases  = nullptr;
    float* _means   = nullptr;  // StandardScaler mean (132 floats)
    float* _stds    = nullptr;  // StandardScaler std  (132 floats)
    float  buf_a[NN_MAX_WIDTH];
    float  buf_b[NN_MAX_WIDTH];
    // ...
};
```

```cpp
// nn.cpp — loadFromLittleFS 구현
#include <LittleFS.h>

bool NeuralNet::loadFromLittleFS(const char* path) {
    File f = LittleFS.open(path, "r");
    if (!f) return false;

    // 13,062 floats(NN) + 132(mean) + 132(std) = 13,326 floats
    const size_t EXPECTED = 13326 * sizeof(float);
    if (f.size() != EXPECTED) { f.close(); return false; }

    // 레이어 순서: W0 b0 W1 b1 W2 b2 means stds
    // W0(132×64) b0(64) W1(64×64) b1(64) W2(64×6) b2(6) means(132) stds(132)
    _weights = new float[8448 + 4096 + 384];  // W0 W1 W2
    _biases  = new float[64 + 64 + 6];        // b0 b1 b2
    _means   = new float[132];
    _stds    = new float[132];

    f.read((uint8_t*)/* W0 */, 8448 * 4);
    f.read((uint8_t*)/* b0 */, 64   * 4);
    f.read((uint8_t*)/* W1 */, 4096 * 4);
    f.read((uint8_t*)/* b1 */, 64   * 4);
    f.read((uint8_t*)/* W2 */, 384  * 4);
    f.read((uint8_t*)/* b2 */, 6    * 4);
    f.read((uint8_t*)/* means */, 132 * 4);
    f.read((uint8_t*)/* stds  */, 132 * 4);

    f.close();
    _loaded = true;
    return true;
}
```

> mean/std 없이는 입력 피처를 정규화할 수 없어 추론이 틀어진다 — 반드시 함께 로드해야 함.

**`exo_armband_hybrid.ino`** — LittleFS 초기화 + 가중치 수신 모드 추가
```cpp
#include <LittleFS.h>

#define WEIGHT_MAGIC  0xDEADBEEF
#define WEIGHTS_PATH  "/weights.bin"
#define WEIGHT_TOTAL  53304  // 13326 floats × 4 bytes (NN 13,062 + mean/std 264)

void setup() {
    // ... 기존 setup 코드 유지 ...

    LittleFS.begin(true);  // true = 마운트 실패 시 포맷

    if (nn.loadFromLittleFS(WEIGHTS_PATH)) {
        Serial.println("# weights loaded — inference enabled");
    } else {
        Serial.println("# no weights — inference disabled");
    }
}

void loop() {
    // 가중치 수신 모드 감지 (USB Serial 또는 BLE Write 핸들러에서 호출)
    if (Serial.available() >= 4) {
        uint32_t magic;
        Serial.readBytes((char*)&magic, 4);
        if (magic == WEIGHT_MAGIC) {
            receiveWeightsSerial();
            return;
        }
    }

    // ... 기존 루프 코드 유지 ...

    // 추론은 가중치가 로드된 경우에만 실행
    if (nn.isLoaded() && infer_total_samples >= WINDOW_SIZE &&
        infer_samples_since_last >= (uint32_t)INFER_HOP) {
        infer_samples_since_last = 0;
        runInference();
    }
}

void receiveWeightsSerial() {
    uint32_t total_bytes;
    Serial.readBytes((char*)&total_bytes, 4);
    if (total_bytes != WEIGHT_TOTAL) {
        Serial.println("ERR:SIZE");
        return;
    }

    File f = LittleFS.open(WEIGHTS_PATH, "w");
    uint8_t buf[256];
    uint32_t received = 0;

    while (received < total_bytes) {
        uint32_t chunk = min((uint32_t)256, total_bytes - received);
        Serial.readBytes((char*)buf, chunk);
        f.write(buf, chunk);
        received += chunk;
    }
    f.close();

    uint32_t crc_recv, crc_calc = 0; // TODO: CRC32 누적 계산
    Serial.readBytes((char*)&crc_recv, 4);
    if (crc_calc != crc_recv) {
        LittleFS.remove(WEIGHTS_PATH);
        Serial.println("ERR:CRC");
        return;
    }

    Serial.println("OK:WEIGHTS");
    delay(100);
    ESP.restart();  // 재부팅 후 새 가중치 자동 로드
}
```

#### LittleFS 파티션
`partitions.csv`에 LittleFS 파티션 최소 256KB 확보 필요 (가중치+스케일러 52KB + 여유분).

#### 최초 플래시 절차 (1회만)
```bash
# PC에서 arduino-cli로 LittleFS 지원 펌웨어 빌드 및 플래시
arduino-cli compile --fqbn esp32:esp32:esp32s3 firmware/exo_armband_hybrid
arduino-cli upload  --fqbn esp32:esp32:esp32s3 --port /dev/ttyUSB0 firmware/exo_armband_hybrid
```
이후 재학습 시에는 플래시 불필요.

---

### 2-2. Android 앱

#### Chaquopy 도입
Android에서 CPython을 실행하는 라이브러리. 기존 Python 전처리/학습 코드를 거의 그대로 재사용할 수 있다.

```gradle
// app/build.gradle
plugins {
    id("com.chaquo.python") version "15.0.1"
}

chaquopy {
    defaultConfig {
        pip {
            install("numpy")
            install("scipy")
            install("scikit-learn")
        }
    }
}
```

APK 용량 증가: numpy + scipy + sklearn 합산 약 50~80MB 추가.

#### TensorFlow → sklearn MLP 교체
모바일에서 TensorFlow는 200MB+ 로 너무 무거움. `MLPClassifier`로 대체해도 정확도 차이 없음.
같은 구조 (Dense 64→64→6, ReLU, Softmax, Adam) 이고 가중치 추출 방식도 동일하다.

```python
# 기존 (TensorFlow, 서버용)
model = tf.keras.Sequential([
    tf.keras.layers.Dense(64, activation="relu"),
    tf.keras.layers.Dense(64, activation="relu"),
    tf.keras.layers.Dense(n_classes, activation="softmax"),
])
model.fit(X_train, y_train_onehot, ...)

# 변경 (sklearn, Android 로컬용)
from sklearn.neural_network import MLPClassifier
model = MLPClassifier(
    hidden_layer_sizes=(64, 64),
    activation="relu",
    solver="adam",
    max_iter=200,
    early_stopping=True,
    n_iter_no_change=10,   # EarlyStopping patience=10 대체
    validation_fraction=0.15,
)
model.fit(X_train, y_train)  # 정수 레이블 그대로 사용 (원핫 불필요)
```

#### 가중치 추출 및 직렬화
학습 완료 후 가중치를 float32 바이너리로 직렬화해 전송한다.

```python
import numpy as np

def serialize_weights(model, scaler) -> bytes:
    """sklearn MLPClassifier + StandardScaler를 바이너리로 직렬화.
    (실제 구현: app/src/main/python/lib/serialize.py)

    레이어 순서: W0 b0 W1 b1 W2 b2 means stds
      W0: (132, 64) float32 → 33,792 bytes
      b0: (64,)     float32 →    256 bytes
      W1: (64, 64)  float32 → 16,384 bytes
      b1: (64,)     float32 →    256 bytes
      W2: (64, 6)   float32 →  1,536 bytes
      b2: (6,)      float32 →     24 bytes
      means: (132,) float32 →    528 bytes  (StandardScaler.mean_)
      stds:  (132,) float32 →    528 bytes  (StandardScaler.scale_)
      합계:                    53,304 bytes
    """
    buffers = []
    for W, b in zip(model.coefs_, model.intercepts_):
        buffers.append(W.astype(np.float32).tobytes())
        buffers.append(b.astype(np.float32).tobytes())
    buffers.append(scaler.mean_.astype(np.float32).tobytes())
    buffers.append(scaler.scale_.astype(np.float32).tobytes())
    return b"".join(buffers)
```

#### 전송 프로토콜
USB(현재 구현: `UsbRepositoryImpl.kt`) 및 BLE 모두 동일한 패킷 포맷 사용.

```
[4 bytes] 매직 넘버: 0xDEADBEEF (little-endian)
[4 bytes] 총 바이트 수: 53304 (uint32 little-endian)
[N bytes] 가중치+스케일러 바이너리 (float32, 레이어 순서대로)
[4 bytes] CRC32 체크섬
```

BLE 전송 시에는 MTU(244바이트) 단위로 청크 분할.
```
패킷 수: 53,304 / 200 ≈ 267개
예상 시간: 연결 간격 15ms 기준 약 4~5초
```

#### Kotlin에서 Python 호출

```kotlin
val py = Python.getInstance()
val module = py.getModule("training_local")

// 학습 실행 (백그라운드 스레드)
val weightsBinary = withContext(Dispatchers.IO) {
    module.callAttr("run_training_local", emgByteArray, labelsIntArray)
           .toJava(ByteArray::class.java)
}

// 가중치 전송 (BLE 또는 USB Serial 선택)
sendWeightsOverBle(weightsBinary)
// 또는
sendWeightsOverUsb(weightsBinary)
```

#### 서버 통신 코드 제거 대상
- `POST /sessions/{user_id}/data`
- `POST /sessions/{user_id}/train`
- `GET /sessions/{user_id}/status`
- `GET /sessions/{user_id}/firmware`
- Retrofit / OkHttp 서버 관련 코드 전체

---

### 2-3. 백엔드 (`mandro-backend`)

A 방식 완전 로컬 전환 시 서버는 불필요하다.
단, 아래 용도로 유지할 수 있음:
- 모바일 CPU가 느릴 경우 클라우드 학습 fallback
- 다중 디바이스 모델 공유 / 백업

당장 제거하지 않아도 됨.

---

## 3. 작업 순서

```
1단계  펌웨어 — LittleFS 가중치 수신/로드 구현
         └─ MODEL.h에서 가중치 배열 제거
         └─ nn.cpp에 loadFromLittleFS() 구현
         └─ .ino에 receiveWeights() 추가
         └─ 더미 가중치로 수신 → 저장 → 로드 동작 확인
         └─ PC에서 최초 1회 플래시

2단계  Android — 가중치 전송 구현
         └─ BLE Write characteristic 또는 USB Serial 전송 코드
         └─ 하드코딩 더미 가중치로 1단계 펌웨어에 전송 테스트

3단계  Android — Chaquopy + sklearn 학습 파이프라인
         └─ 기존 Python 전처리/학습 코드를 assets에 배치
         └─ TensorFlow → sklearn MLP 교체
         └─ serialize_weights() 구현
         └─ Kotlin에서 Chaquopy 호출 연결

4단계  통합 테스트
         └─ BLE 수집 → 로컬 학습 → 가중치 전송 → 재부팅 → 추론 전체 플로우
```

---

## 4. 주요 의존성

| 라이브러리 | 버전 | 용도 |
|---|---|---|
| Chaquopy | 15.0.1+ | Android Python 런타임 |
| numpy | 1.24+ | 배열 연산 |
| scipy | 1.10+ | Butterworth 필터 |
| scikit-learn | 1.3+ | MLPClassifier 학습 |
| LittleFS (ESP32) | arduino-esp32 내장 | 가중치 파일 저장 |
