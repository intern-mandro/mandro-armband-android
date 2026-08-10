# mandro 프로젝트 인수인계 문서

> 작성일 2026-07-20. `mandro-dynamic-gesture` 레포 기준. 프로젝트를 처음 보는
> 사람이 "펌웨어-앱 사이에 데이터가 어떻게 오가는지", "모델이 실제로 어떻게
> 동작하는지", "지금까지 뭘 겪었는지"를 코드 근거와 함께 파악할 수 있도록 작성함.

---

## 1. 프로젝트 개요

EMG(근전도) 암밴드(ESP32-S3)로 전완 근육 신호를 읽어 손목 동작(rest/flexion/
extension/close/supination/pronation 6종)을 인식하는 시스템. 사용자가 자기
동작을 직접 녹화해서 개인화 모델을 만들고, 그 모델을 암밴드에 넣어 실시간
인식하게 하는 게 목표.

**아키텍처 대전환 — 서버 제거, 완전 로컬화**: 원래 FastAPI 서버가 학습을 담당하는
구조였는데, 서버를 완전히 없애고 **안드로이드 폰 안에서 직접 학습**(Chaquopy +
scikit-learn)하도록 바꿈. 학습된 가중치(52,248 bytes)만 BLE로 암밴드에 보내고,
암밴드가 그 가중치로 **온보드 추론**을 수행. 배경은
[`docs/archive/LOCAL_MIGRATION.md`](app/src/main/java/com/mandro/docs/archive/LOCAL_MIGRATION.md)(사전 설계안, §10 참고).

---

## 2. 레포 3개, 서로 다른 역할

| 레포 | 역할 | 상태 |
|---|---|---|
| `mandro-backend` | **펌웨어 소스 원본**(`firmware/exo_armband_hybrid/`)이 있는 곳. 예전 서버(FastAPI)도 여기 있었지만 앱은 더 이상 안 씀. | 펌웨어 소스 보관/수정용 |
| `mandro-local-app` | **정적 제스처**(자세를 몇 초간 유지) 인식 앱. | 안정 버전 |
| `mandro-dynamic-gesture` (이 레포) | 2026-07-13에 `mandro-local-app`을 clone해서 시작. 동적 모션 인식을 목표로 했으나 시도 후 실측 성능이 정적보다 나빠서 **정적 유지 방식으로 되돌린 상태**(2026-07-20). | 정적+버그 수정 여러 건 얹힌 상태 |

---

## 2-1. 프로젝트 진화 과정 (Python 데스크탑 → Android v1 → 완전 로컬)

문서/코드에 남은 흔적을 근거로 재구성한 히스토리. 구버전 문서는 삭제하지 않고
[`docs/archive/`](app/src/main/java/com/mandro/docs/archive/)에 보존했고, 각
파일 맨 위에 "지금 뭐가 유효하고 뭐가 아닌지" 안내를 붙여둠.

### 0단계 — Python 데스크탑 앱 (원본 프로토타입)

이 프로젝트의 시초. 밴드패스 필터(35~300Hz)→envelope→132차원 피처(classic 88+
TSD 44)→분류기라는 **지금까지도 그대로 쓰이는 신호 처리 알고리즘**이 여기서
검증됐을 것으로 추정(`lib/features.py`, `lib/preprocessing.py`의 원본).
"연구자가 옆에서 직접 EMG 개인화 학습 과정을 안내해야 했다"는 게 이 단계의
한계였고, 그걸 앱이 대체하자는 게 프로젝트의 출발점
([`docs/archive/FEATURES.md`](app/src/main/java/com/mandro/docs/archive/FEATURES.md) §1).

### 1단계 — Android v1 (2026-06-29~06-30 UI 프로토타입)

`docs/archive/FEATURES.md`, `docs/archive/HARNESS.md`, `docs/archive/
README_v1_stale.md` 기준. 이 시점 설계:

- **하드웨어 타깃이 지금과 다름 — `Teensy`**(현재는 ESP32-S3). 코드베이스
  전체에서 "Teensy"는 이제 이 archive 문서 3개에만 남아있고, 실제 소스 코드엔
  없음 — 하드웨어 자체가 나중에 ESP32-S3로 교체된 것으로 보임(정확한 전환
  시점은 문서에 명시 안 돼 있음)
- 아키텍처: **BLE**(신호 수신) + **USB**(펌웨어 전체를 Teensy에 플래시) +
  **HTTP/Retrofit**(FastAPI 서버로 학습 요청) 세 갈래
- 피처 추출을 **Kotlin으로 수동 포팅**(`EmgFeatureExtractor.kt` 등) — Python
  원본과 수치가 정확히 일치해야 해서 "DO NOT TOUCH, 오차 1e-4 이내로 검증
  후에만 수정" 규칙이 있었음
- 온보드 추론은 **TensorFlow Lite**(`.keras` → `.tflite` 변환)
- **데이터 수집 설계가 이 시점에 이미 확정되고, 지금까지 그대로 유지되고
  있음**: "1랩 = 6동작×10초, 최소 5랩 이상 수집해야 학습 가능" — 지금
  `CollectViewModel.kt`의 `MIN_LAPS_TO_TRAIN=5`, `RECORD_DURATION_MS=10000L`이
  이 설계 그대로임. 참고로 **"동작당 10회 반복" 초기안 → "랩 기반"으로
  변경했다는 기록이 있는데**, 이번 세션에서 시도했다가 되돌린 "온셋/오프셋
  큐 5회 반복"(§7.7)과 방향은 다르지만 "반복 횟수 vs 다른 수집 구조" 고민
  자체는 프로젝트 초기부터 반복되고 있는 주제임

### 2단계 — 서버 학습 연동 + 하드웨어 ESP32-S3 전환

FastAPI 서버(`mandro-backend`)가 실제로 붙어 학습을 전담하게 됨. 이 무렵
하드웨어도 Teensy에서 지금의 **ESP32-S3**로 바뀐 것으로 보임(BLE 프로토콜,
GPIO 채널 매핑 등이 전부 ESP32-S3 기준으로 문서화돼 있음,
[`docs/FIRMWARE_PROTOCOL.md`](app/src/main/java/com/mandro/docs/FIRMWARE_PROTOCOL.md)).
이 단계가 이번 로컬 전환 작업을 시작하기 직전의 "구조"였음.

### 3단계 — 완전 로컬 전환 (2026-07-03~07-10)

[`docs/archive/LOCAL_MIGRATION.md`](app/src/main/java/com/mandro/docs/archive/LOCAL_MIGRATION.md)(사전 설계안, §10 참고)
가 이 단계 설계 문서. 핵심 계기: **arduino-cli를 Android 안에서 실행할 수
없어서**, "재학습 때마다 펌웨어 전체를 재컴파일→USB 재플래시"가 불가능함을
깨달음 → "신경망 구조(토폴로지)는 고정, 가중치만 파일로 교체" 구조로 설계 전환.

- **서버(FastAPI) 완전 제거**, Chaquopy로 안드로이드 안에서 직접 학습
  (scikit-learn `MLPClassifier`가 TensorFlow Keras를 대체 — 구조 동일, 정확도
  차이 거의 없음, 학습 속도는 5~12배 빠름)
- **Kotlin 피처 추출기가 이 시점부터 불필요해짐** — Chaquopy가 Python 원본
  코드를 그대로 재사용하니 수동 포팅이 필요 없어짐. `EmgFeatureExtractor.kt`가
  이때부터 죽은 코드가 된 것으로 추정(오늘 문서 정리하며 발견 — 코드 어디서도
  안 쓰이는데 파일만 남아있음, `core/emg/`로 위치는 한 번 옮겨졌음)
- **USB 펌웨어 전체 재플래시 → BLE로 가중치(52,248B)만 전송**하는 구조로
  전환. 펌웨어는 LittleFS에 저장, 재부팅 후 로드(HANDOVER.md §3.4)
- Phase 5(2026-07-10)에서 펌웨어 쪽(`MODEL.h`, `nn.cpp::loadFromLittleFS()`,
  가중치 수신 프로토콜)을 실제 구현·검증. 이 과정에서 §7.3의 BLE 콜백 블로킹
  버그를 발견/수정

### 4단계 — 동적 모션 인식 시도 (2026-07-13~07-20, 이 레포)

`mandro-local-app`에서 clone해서 시작. 정적 유지 → 반복 동작 인식으로 목표를
바꿔 시도했으나(§7.7), 정량 비교 결과 정적 방식이 더 나아서 최종적으로
되돌림. 지금은 **정적 유지 방식 + 이번 세션에서 발견/수정한 여러 버그**가
얹힌 상태 (§7 전체).

**주의**: 두 앱 레포 모두 `applicationId = com.mandro.app`으로 동일 — 같은 기기에
동시 설치 불가(서로 덮어씀). 실기기 테스트 시 지금 어느 레포를 빌드해서 설치했는지
항상 확인할 것. 펌웨어 소스는 `mandro-backend`에만 있고 앱 레포엔 프로토콜 문서만
복사돼 있음.

---

## 3. ESP32(펌웨어)가 데이터를 어떻게 주고받는가

파일: `mandro-backend/firmware/exo_armband_hybrid/exo_armband_hybrid.ino`,
`nn.cpp`/`nn.h`, `preprocessor.cpp`/`preprocessor.h`, `MODEL.h`

### 3.1 샘플링

- ADC를 8채널 순차로 읽음, 값은 `ADC_read - 250`을 `[0,255]`로 clip한 uint8
  (그래서 "rest" 상태가 대략 중간값 근처에 오도록 오프셋을 이미 하드웨어/펌웨어
  단에서 맞춰둠)
- 실측 주기 **781µs ≈ 1281Hz**

### 3.2 raw EMG를 어떻게 내보내는가

`loop()` 안에서 20샘플이 쌓일 때마다(`pos == 0`) 무조건 notify:

```cpp
// exo_armband_hybrid.ino, loop() 안
if (pos == 0) {
    if (deviceConnected) {
        pCharacteristic->setValue(txBuffer, PACKET_SIZE);  // 160 bytes = 20샘플×8채널
        pCharacteristic->notify();
    }
}
```
→ 발행 주기 20×781µs ≈ **15.6ms, 약 64Hz**.

### 3.3 추론을 언제, 어떻게 하는가

`loop()`가 매번 `getEMG()`로 새 샘플 하나를 원형 버퍼(`infer_buffer`, 크기
WINDOW_SIZE=128)에 채우고, **64샘플(INFER_HOP)마다** 추론을 실행:

```cpp
if (nn.isLoaded() &&
    infer_total_samples >= WINDOW_SIZE &&
    infer_samples_since_last >= (uint32_t)INFER_HOP) {
    infer_samples_since_last = 0;
    runInference();
}
```

`runInference()`(exo_armband_hybrid.ino:483) 내부 순서:
1. 원형 버퍼를 시간순으로 정렬해 128×8 스냅샷 생성
2. `preproc.process(snapshot, features)` — 밴드패스 필터(35~300Hz, causal,
   Direct Form II Transposed) → 정류 → envelope(moving average, kernel=12) →
   132차원 피처 추출 → StandardScaler(온보드 mean/std) 정규화. **Python 학습
   파이프라인과 동일한 전처리를 C++로 재구현한 것** — 둘이 어긋나면 추론이 틀어짐
3. `nn.predict(features, logits)` — 132→64→64→6 순전파(§4.2 참고)
4. `argmax`로 순간 예측 → **3-vote cascade**(최근 3번 추론 다수결, threshold=0.34,
   미달 시 직전 예측 유지)로 노이즈성 순간 오분류 완화
5. 결과를 `"classname|l0|l1|l2|l3|l4|l5"` 텍스트로 만들어 notify

발행 주기 64×781µs ≈ **50ms, 약 20Hz**.

**중요**: raw EMG notify와 추론 notify는 **펌웨어 코드 안에서는 서로 완전히 독립적**
(각자 `deviceConnected` 하나로만 게이팅, 펌웨어 자체엔 "raw만/추론만 보낸다" 같은
모드 스위치가 없음) — 안드로이드 코드(`BleManager.kt`)와 대표님 전언 사이에 불일치가
있었던 부분인데, 펌웨어 소스로 직접 확인한 결과임. 단, 2026-07-20에 안드로이드
쪽에서 raw EMG만 골라 CCCD(구독) 자체를 껐다 켜는 기능이 추가되어, 실질적으로는
"raw는 사용자 설정에 따라 켜짐/꺼짐, 추론은 항상 켜짐"이 됨 — **펌웨어 코드는 이
기능을 위해 전혀 수정되지 않았고**, 표준 BLE 구독 메커니즘을 안드로이드 쪽에서
재사용한 것뿐임. 자세한 원리와 근거는 §5.6 참고.

### 3.4 가중치를 어떻게 받는가 (Characteristic `...58`)

```
[4 bytes] MAGIC   : 0xDEADBEEF (little-endian)
[4 bytes] LENGTH  : 53,304 (uint32 LE)
[N bytes] PAYLOAD : W0 b0 W1 b1 W2 b2 means stds (float32)
[4 bytes] CRC32   : PAYLOAD 체크섬 (LE)
```
- BLE는 MTU(247)-ATT오버헤드(3) = **244바이트씩 청크 분할**, 총 219회 순차 Write
  Request(응답 대기 후 다음 전송) — 링크 레이어가 이미 재전송을 보장해서 청크를
  더 잘게 쪼갤 필요 없음
- USB Serial 경로도 있지만(`receiveWeightsUsb()`) **현재 사용 안 함**(§7 트러블
  슈팅 참고, HWCDC 코어 버그로 대용량 스트림 수신이 막힘)
- 안전장치: 수신 데이터는 항상 `/weights.tmp`에 먼저 쓰고, CRC32 통과 시에만
  `/weights.bin`으로 rename. 전송 도중 끊겨도 기존 가중치는 안전
- 저장 완료 → `pendingRestart` 플래그 세팅 → `loop()` 맨 앞에서 안전하게 재부팅
  (§7의 콜백 블로킹 버그 참고)

---

## 4. 모델이 지금 어떻게 동작하는가

### 4.1 학습 시점 (Python, 안드로이드 안에서 Chaquopy로 실행)

파일: `app/src/main/python/lib/` 전체, 진입점 `training_local.py`

```
raw EMG(N,8) + 샘플별 라벨
  → 앞부분 0.5초(DELAY_SAMPLES) 트림 — BLE 연결 직후/반응 지연 노이즈 제거
  → Butterworth 밴드패스(35~300Hz, 4차, causal lfilter)
    ※ filtfilt(양방향, 미래 샘플 필요)가 아니라 lfilter(단방향)를 쓰는 이유:
      펌웨어 실시간 추론도 causal 방식이라 학습/추론 조건을 맞추기 위함
  → abs() 정류 + moving-average envelope(kernel=12, ~10ms)
  → 128샘플(~100ms, 2의 거듭제곱=FFT 효율) 단위 **비중첩** 윈도우 분할
  → 윈도우 라벨 = 다수결(지금은 take 전체가 한 라벨이라 사실상 항상 만장일치)
  → 132차원 피처 추출:
      classic 88차원 = 시간 6개(MAV,MaxAV,STD,RMS,WL,SSC)×8채널
                      + 주파수 5개(MeanPow,TotalPow,MeanFreq,MedianFreq,PeakFreq)×8채널
      TSD 44차원 = 공분산 상삼각 36개 + 채널별 에너지 8개 (Khushaba et al. 2017)
  → StandardScaler 정규화 (mean/std, 132차원 각각 독립)
  → MLPClassifier(hidden_layer_sizes=(64,64), relu, adam, early_stopping) 학습
  → W0 b0 W1 b1 W2 b2 means stds 순서로 float32 직렬화 → 53,304 bytes
```

**모델 구조**: 132(입력) → 64(ReLU) → 64(ReLU) → 6(Softmax). 원래는 서버에서
TensorFlow Keras로 학습했는데, 로컬 전환하면서 sklearn `MLPClassifier`로 교체
(구조 동일, 정확도 차이 거의 없음, 학습 속도는 5~12배 빠름).

**알아둘 기존 특이사항**:
- 학습 파이프라인은 윈도우를 **비중첩**으로 자르는데, 펌웨어 실시간 추론은 위 §3.3
  대로 **64샘플씩 겹치며(50% overlap)** 훨씬 자주 재평가함 — 두 레이어가 다른
  정책을 씀. 온셋 검출 등을 다시 설계할 때 고려해야 할 지점.
- TSD 서브윈도우링(`TSD_WIN_MS=80, TSD_INC_MS=40` @ 1200Hz)이 현재 파라미터로는
  128샘플 부모 윈도우 안에 서브윈도우가 1개만 들어가서, "여러 서브윈도우 평균"
  이라는 설계 의도가 무력화돼 있음 (버그는 아니지만 사실상 죽어있는 로직).
- `training.py::fit_local_model`이 20% 검증셋(`split_train_val`)을 계산은 하지만
  실제로 `model.fit()`엔 안 씀 — 죽은 코드. 학습 정확도를 확인하려면 이 함수
  안에서 안 쓰이는 로직을 살리거나, 별도 오프라인 평가 스크립트가 필요함
  (§6 정량 근거 부분 참고).

### 4.2 추론 시점 (C++, ESP32 온보드)

§3.3에서 이미 설명한 대로 `preproc.process()` → `nn.predict()` → argmax → 3-vote.
`nn.cpp::denseLayer()`가 실제 순전파 연산:

```cpp
// out[j] = activation( sum_i(in[i] * W[i,j]) + b[j] )
for (int j = 0; j < out_dim; j++) {
    float acc = biases[j];
    for (int i = 0; i < in_dim; i++) acc += in[i] * weights[i * out_dim + j];
    out[j] = acc;
}
applyActivation(out, out_dim, activation_code);
```
가중치는 row-major (in_dim, out_dim) — Keras `flatten()`과 동일한 레이아웃.
`loadFromLittleFS()`가 파일의 "레이어별 interleaved"(W0 b0 W1 b1 ...) 순서를
`predict()`가 기대하는 "가중치 전부 이어붙이고 그다음 bias 전부 이어붙이는" 레이아웃
으로 재배치해서 로드함 — 파일 포맷과 메모리 내부 표현이 다르다는 점 주의.

### 4.3 지금까지 확보한 정확도

같은 유저, 1랩 기준 held-out 정확도(오프라인 평가, 앱 자체 평가 로직은 죽은
코드라 별도 스크립트로 확인):

| 방식 | 정확도 |
|---|---|
| 정적 유지(10초 자세 유지) | **90.4%** |
| 반복 동작(짧게 여러 번 반복, take 전체 단일 라벨 — 라벨 오염 있음) | 66.4% |

정적 유지의 유일한 약점은 **supination↔pronation 혼동**(다른 4클래스는 거의
100%). 원인 분석: `weights.bin`을 직접 파싱해서 W0 가중치 크기로 채널 중요도를
역산하고, 별도로 raw 데이터의 채널별 std/p2p를 실측 비교한 결과 — **이 두 클래스를
구분하는 정보가 8채널 중 CH6·CH7 딱 두 채널에만 집중**돼 있고 나머지 6채널은
sup/pro를 수행해도 rest와 값이 거의 동일함. 해부학적으로 회외근(supinator)이
팔꿈치 바로 아래 깊은 곳에 있어, 전완 중간에 두르는 밴드 위치에서 원천적으로
약하게 잡힐 가능성이 있음 (§8 개선점 참고).

---

## 5. 안드로이드가 데이터를 어떻게 받고 처리하는가

### 5.1 BLE 수신 → Flow로 분배

파일: `app/src/main/java/com/mandro/core/ble/BleManager.kt`

```kotlin
override fun onCharacteristicChanged(gatt, characteristic) {
    val bytes = characteristic.value ?: return
    when (characteristic.uuid.toString().lowercase()) {
        EMG_CHARACTERISTIC_UUID.lowercase()   -> parseEmgPacket(bytes)
        INFER_CHARACTERISTIC_UUID.lowercase() -> parseInferencePacket(bytes)
        WEIGHT_CHARACTERISTIC_UUID.lowercase()-> { /* 가중치 전송 ACK 처리 */ }
    }
}
```

- `parseEmgPacket()`: 160바이트 패킷을 20개 샘플로 나눠 **샘플 하나당 한 번씩**
  `_emgStream.tryEmit(EmgSample(...))` — 즉 `emgStream`은 개별 샘플 단위
  (~1281Hz)로 흐름. 이 함수는 raw EMG의 BLE 구독(CCCD)이 켜져 있을 때만 애초에
  호출됨 — 꺼져 있으면 펌웨어가 notify 자체를 안 보내므로 `onCharacteristicChanged`가
  호출 안 됨(§5.6, 전력 절약을 위해 실제 라디오 송신을 막는 방식). 예전엔
  `emgEnabled`라는 앱 내부 로컬 필터가 있었는데(펌웨어는 계속 보내고 앱만 버리는
  방식이라 전력 절약 효과가 없었음), 이 기능으로 대체되면서 제거됨.
- `parseInferencePacket()`: 텍스트 파싱해서 `_inferenceStream.tryEmit(result)`
- 둘 다 `MutableSharedFlow` → 화면(ViewModel)이 필요한 것만 골라 구독

### 5.2 화면별 소비 방식

| 화면 | 구독하는 Flow | 용도 |
|---|---|---|
| Waveform | `emgStream` | 원본 파형 그리기, `hasSignal`(p2~p98 스프레드 > 5) 기반 신호 유무 alpha 처리 |
| Collect | `emgStream` | 녹화 버퍼 적재(`recordingBuffer`) + 신호 세기 바 표시 |
| Classify | `emgStream` + `inferenceStream` | 레이더 차트(세기=`channelIntensity`, 살아있음=`channelHasSignal`) + rest 강제 후처리(§8) + 인식 결과 텍스트 |

### 5.3 녹화 데이터 저장 (Collect → Room DB)

`CollectViewModel`이 `recordingBuffer`(raw 샘플 리스트)를 10초간 쌓았다가,
128샘플씩 잘라 `EmgWindow` 리스트로 변환 → `RecordingTake`(take 하나 = gesture
문자열 하나 + windows) → `EmgRepositoryImpl.saveTake()` → Room
`RecordingTakeEntity`(userId, gesture, takeIndex, samplesBlob(uint8 flat array))로
저장. **지금은(동적 모션 되돌린 뒤) take 전체가 단일 라벨** — 온셋/오프셋
윈도우별 라벨링 컬럼(`windowLabels`)은 코드상 남아있지만(하위 호환용, 빈 문자열이면
take의 gesture로 폴백) 실제로는 안 채워짐.

### 5.4 학습 요청 (Kotlin → Chaquopy → Python)

`RecordingBatch`(유저의 모든 take, gesture별로 그룹) →
`EmgSerialization.kt::serializeBatch()`가 (emg_data bytes, labels bytes) 페어로
직렬화 → `LocalTrainingRepositoryImpl`이 Chaquopy로 `training_local.py::
run_training_local()` 호출 → 학습된 가중치(53,304 bytes) 반환 → Kotlin이 이걸
그대로 BLE Characteristic `...58`로 전송(§3.4).

### 5.5 Classify 화면 후처리 (rest 오분류 대응)

펌웨어가 보낸 추론 결과를 그냥 안 믿고, **활성 채널 개수 기반으로 강제 rest
판정**하는 레이어를 앱 쪽에 추가함(`ClassifyViewModel`):
```
활성 채널 = channelIntensity[ch] >= 0.01 인 채널 개수  (threshold 실측 데이터로 재조정, §7)
활성 채널 <= 1개 상태가 100ms 이상 지속되면 → NN 예측 무시하고 rest로 강제 표시
```
레이더 차트는 "근육 활성 상태"(`channelIntensity`, baseline 대비 편차, rest에서
0에 가까운 게 정상)와 "센서가 데이터를 보내는가"(`channelHasSignal`, raw 값
자체의 최근 스프레드, baseline 보정과 무관)를 분리해서, 길이는 전자로 색상/alpha는
후자로 결정함(§7).

### 5.6 Raw EMG 스트리밍 On/Off (전력 절약, 2026-07-20)

**배경**: 암밴드가 raw EMG(`...56`)를 화면에서 실제로 쓰든 안 쓰든 항상 64Hz로
쏘고 있어서, Waveform/Collect처럼 파형을 그리거나 녹화할 때가 아니면 불필요한
BLE 라디오 송신(=전력 소모)이 계속 발생하고 있었음. 목표는 사용자가 Settings에서
이걸 껐다 켰다 할 수 있게 하는 것.

**핵심 아이디어 — 펌웨어 코드는 한 줄도 안 바꿈**: BLE Notify는 스펙상 원래
"클라이언트가 CCCD(0x2902 디스크립터)에 `0x0001`을 써야 구독 상태가 되고, 그래야
서버가 실제로 notify를 보낼 수 있다"가 표준 동작임. 그런데 기존 연결 절차(§6)는
연결 시 딱 한 번 `0x0001`을 쓰고 그 뒤로는 다시 안 건드렸음 — 그래서 "구독은
이미 계속 켜진 상태"였던 것. 이번에 추가한 건 **안드로이드가 사용자 설정에 따라
이 CCCD 값을 `0x0000`(구독 해제)/`0x0001`(재구독)으로 다시 써주는 것**뿐.

**"진짜로 무선 송신이 멈추는가?" — 라이브러리 소스로 확인함**: 펌웨어의
`pCharacteristic->notify()` 호출 자체는 무조건 실행되지만(펌웨어 코드는 CCCD 상태를
직접 체크 안 함), 그 호출이 실제로 진입하는 ESP32 Arduino BLE 라이브러리
(`C:\Arduino15\packages\esp32\hardware\esp32\3.3.10\libraries\BLE\src\
BLECharacteristic.cpp`, 이 프로젝트가 쓰는 Bluedroid 경로, `CONFIG_BLUEDROID_ENABLED`
블록)의 `BLECharacteristic::notify()` 안에 이미 이런 코드가 있었음:
```cpp
// Test to see if we have a 0x2902 descriptor.  If we do, then check to see if
// notification is enabled and, if not, prevent the notification.
BLE2902 *p2902 = (BLE2902 *)getDescriptorByUUID((uint16_t)0x2902);
if (is_notification) {
    if (p2902 != nullptr && !p2902->getNotifications()) {
        log_v("<< notifications disabled; ignoring");
        m_pCallbacks->onStatus(this, ...ERROR_NOTIFY_DISABLED, 0);
        return;   // ← 실제 무선 전송 함수(esp_ble_gatts_send_indicate)를 호출 안 함
    }
}
```
즉 CCCD가 꺼져 있으면 실제 전송 함수(`esp_ble_gatts_send_indicate`) 호출 전에
`return`해버림 — **앱단 필터가 아니라 ESP32 BLE 스택 내부에서 무선 송신 자체가
스킵됨**이 라이브러리 소스로 확인됨. 이 라이브러리는 프로젝트 시작 때부터 있던
서드파티 코드라 우리가 수정한 게 아니고, 그냥 원래 있던 표준 동작을 이번에 처음
활용한 것.

**`BLE2902`는 뭐냐 — CCCD를 표현하는 라이브러리 제공 클래스**: `BLEDescriptor`를
상속받은 특화 클래스로, UUID가 `0x2902`로 고정돼있고 내부적으로 2바이트만
들고 있음(`byte[0]`의 bit 0 = notification on/off, bit 1 = indication on/off).
`setup()`에서 Characteristic마다(`...56`/`...57`/`...58`) `addDescriptor(new
BLE2902())`로 각각 독립된 인스턴스가 붙어서, 구독 상태를 Characteristic별로
따로 관리함 — 그래서 raw EMG만 구독 해제하고 추론 결과는 계속 구독 유지하는
게 가능함.

**안드로이드가 쓴 CCCD 값이 실제로 어떻게 저장되는가** (구독 설정 방향,
안드로이드→ESP32): `BleManager.kt::setRawEmgSubscribed()`가
`gatt.writeDescriptor(desc)`를 호출하면 BLE 무선으로 ATT Write Request가
ESP32에 도착 → 라이브러리의 `BLEDescriptor.cpp::handleGATTServerEvent()`가
`ESP_GATTS_WRITE_EVT` 이벤트를 받아서 `setValue(param->write.value,
param->write.len)`을 호출 → 이게 `BLE2902` 객체의 그 2바이트 메모리에 값을
그대로 저장함. 이후 `notify()`가 호출될 때마다 `getNotifications()`가 읽는
값이 바로 이것. **이 저장 경로도 전부 라이브러리 안에서 일어나고, 우리
펌웨어/안드로이드 코드는 "쓰기 요청을 보내고 결과를 확인"만 함.**

**구현 파일**
- `app/src/main/java/com/mandro/data/local/RawStreamPreferences.kt`: DataStore
  기반 On/Off 설정, 기본값 `false`(Off)
- `BleManager.kt::setRawEmgSubscribed(enabled)`: raw EMG characteristic의 CCCD를
  `0x0000`/`0x0001`로 재기록
- `data/ble/BleRepositoryImpl.kt`: `@Singleton` 스코프에서 `bleState`와
  `rawStreamPreferences.enabled`를 `combine()`해서, **연결될 때마다(재연결
  포함)** 저장된 설정을 재적용 — CCCD는 연결이 끊기면 초기화되므로 재연결마다
  다시 적용해줘야 함
- 화면별 처리(Settings의 "원본 신호 수신" 토글이 유일한 조작점):
  - **Collect(녹화)**: raw가 없으면 녹화 데이터가 통째로 비어버리므로, 진입 시
    설정과 무관하게 항상 강제로 켬(끌 때 자동으로 되돌리지 않음 — 사용자가
    Settings에서 직접 꺼야 함)
  - **Waveform / Classify**: 설정을 그대로 따름, 강제로 켜지 않음. Off인 채로
    Waveform에 들어가면 파형은 기존 "신호 없음" 표시가 뜨고 캘리브레이션 진행률은
    0%에서 멈춤(raw 샘플이 안 들어오니까) — 의도된 트레이드오프. Classify는 Off면
    레이더 차트만 조용히 숨기고, 인식 결과 텍스트는 추론 notify(§3.3)가 raw와
    무관하게 항상 오므로 계속 정상 동작
- 문서: `app/src/main/java/com/mandro/docs/archive/RAW_STREAM_TOGGLE.md`(설계 논의+결정
  사항 전체 기록)

**검증 방법 (adb logcat, 태그 `BleManager`)**: `setRawEmgSubscribed()` 호출 시
"raw EMG 구독 상태 변경 요청" 로그 → `onDescriptorWrite()`에서 "Descriptor 쓰기
완료 (status=0)"으로 CCCD 쓰기 성공 확인 → `parseEmgPacket()`의 "패킷 수신 #N"
로그(64패킷≈1초마다)가 Off 직후 완전히 끊기고 On 하면 재개되는지로 실제 수신
중단을 확인. **아직 안 한 것**: 실제 전류 소모 측정(USB 전류계)으로 정량 확인 —
지금까지는 라이브러리 소스 검증 + 로그 기반 정성적 확인까지만 함.

---

## 6. 블루투스 프로토콜 요약

| Characteristic | UUID 끝자리 | 속성 | 페이로드 | 주기 |
|---|---|---|---|---|
| 1 (raw EMG) | `...56` | NOTIFY | 160B (20샘플×8채널 uint8) | ~64Hz |
| 2 (추론 결과) | `...57` | READ+NOTIFY | 텍스트 `"class\|l0..l5"` | ~20Hz |
| 3 (가중치 수신) | `...58` | WRITE+NOTIFY | MAGIC+LEN+53,304B+CRC32, 244B 청크 | 전송 시에만 |

- 서비스 UUID / 광고 이름: `ESP32S3_FAST_BLE`
- 연결 절차: `connectGatt()` → `requestMtu(247)` → `discoverServices()` → 두
  Characteristic(1,2) CCCD `0x0001` 쓰기 → `requestConnectionPriority(HIGH)` →
  이후 저장된 raw 스트리밍 설정에 따라 Characteristic 1의 CCCD만 다시
  `0x0000`/`0x0001`로 재기록될 수 있음(§5.6, 전력 절약 기능 — Characteristic
  2/추론은 항상 구독 유지)
- Connection interval 7.5~15ms, slave latency 0
- 상세: [`docs/FIRMWARE_PROTOCOL.md`](app/src/main/java/com/mandro/docs/FIRMWARE_PROTOCOL.md)

---

## 7. 트러블슈팅 히스토리 (시간순, 실제로 겪은 것들)

### 7.1 LittleFS 하드웨어 검증 (2026-07-08~10, 별도 테스트 프로젝트)
- 53KB(가중치+스케일러 크기)를 LittleFS에 쓰고 재부팅 후 읽는 게 실제로 되는지
  독립 스케치로 먼저 검증 → **성공**
- arduino-cli가 PowerShell에서 기본 설정 자동탐색 실패(한글 사용자명 경로 문제
  추정) → `--config-file`로 ASCII 경로 설정 파일 항상 명시하는 걸로 해결
- 시리얼 모니터가 `dtr=on,rts=on` 기본값이면 보드가 리셋 상태에 눌려서 부팅 못 함
  → `-c dtr=on -c rts=off`로 해결
- ESP32-S3 네이티브 USB(HWCDC)로 `Serial.print()`할 때 텍스트 줄이 유실되는 게
  arduino-esp32 코어의 알려진 버그(#9378, #11959) — "로그 안 뜸"이 "코드가 멈췄다"는
  뜻이 아님. `Serial.flush()` 피하기, `while(!Serial)` 대기, 체크섬 한 줄로 압축
  출력 등으로 완화
- FQBN에 `CDCOnBoot=cdc` 필수 — 기본값(Disabled)이면 USB로 로그가 아예 안 보임

### 7.2 USB Serial 가중치 수신 실패 → BLE로 전환 (2026-07-10)
- 53KB 더미 페이로드를 USB Serial로 보내는 테스트가 매번 PAYLOAD 수신 도중
  타임아웃 (청크+타임아웃 늘려도 실패)
- 원인 추정: ESP32-S3 네이티브 USB CDC로 대용량 연속 스트림 수신 시 멈추는 코어
  버그(#10836) + USB 자체엔 청크 단위 ACK 절차가 없음. 대조군(펌웨어 전체
  업로드는 esptool SLIP 프로토콜이라 별개, BLE는 청크마다 콜백 대기라 자연 페이싱)
  이 이 추정을 뒷받침
- **결정**: USB 경로는 보류(코드는 남겨둠), **BLE를 주 경로로 확정**

### 7.3 BLE 가중치 전송 실기기 검증 — 콜백 블로킹 버그 (2026-07-13, 가장 중요한 발견)
- 증상: 펌웨어 로그엔 `OK:WEIGHTS`(성공)가 찍히는데, 안드로이드는 매번 마지막
  청크에서 `status=133`/`status=8(CONN_TIMEOUT)`으로 실패 처리
- 처음엔 "안드로이드 BLE 콜백 지연"으로 오진, `delay(200)→2000)`로 늘렸다가
  역효과(콜백을 더 오래 막음)
- 청크별 정밀 로그로 타임라인 재구성 후 진짜 원인 발견: **펌웨어
  `WeightsCharCallbacks::onWrite()` 콜백이 `notifyWeightsResult()` 직후 콜백
  안에서 바로 `delay(...); ESP.restart();`를 실행** — BLE ATT Write Response는
  `onWrite()`가 return해야 스택이 실제로 내보낼 수 있는데, 콜백이 블로킹된 채
  재부팅까지 해버려서 안드로이드가 그 write의 확인 응답을 영원히 못 받음
- **교훈**: BLE `onWrite()`/`onWriteRequest()` 콜백 안에서는 절대 블로킹 호출
  (delay, 재부팅, 무거운 동기 I/O) 금지 — 콜백은 최대한 빨리 return해야 함
- **수정**: `pendingRestart`/`restartAtMs` 플래그로 콜백 밖(`loop()`)에서 처리.
  안드로이드 쪽도 안전장치 추가(`earlyAck` 보관, `writeChunkWithRetry` 3회 재시도,
  마지막 청크 ack 놓쳐도 이미 받았으면 성공 처리)

### 7.4 유저 데이터 꼬임 (2026-07-13 발견, 2026-07-20 근본 수정)
- 증상: 활성 유저에 `recording_takes`가 0건인데, 예전에 학습 성공했던
  `weights.bin`은 DB에도 없는 고아 유저 폴더에 남아있음
- 근본 원인: Room `fallbackToDestructiveMigration()`이 스키마 버전 바뀔 때 DB만
  초기화하고 `files/models/{userId}/` 폴더는 안 지움 + `deleteUser()`도 DB row만
  지우고 파일은 안 지움(이건 애초에 UI 어디서도 호출 안 되는 죽은 코드였음)
- **수정**: `deleteUser()`가 파일도 삭제, 앱 시작 시 `cleanupOrphanedModels()`로
  고아 폴더 자동 정리, 홈 화면에 유저 삭제 UI 신설 + "현재 활성 유저" 표시 추가
  (유저 카드를 안 눌러도 지난 세션 activeUserId가 재사용되는 게 안 보였던 UX 허점)

### 7.5 Classify 화면 rest 오분류 → threshold 실측 튜닝 (2026-07-15)
- 처음엔 임의로 `ACTIVE_CHANNEL_THRESHOLD=0.02`로 설정 → 실기기 테스트에서
  supination/pronation 자체가 rest로 오분류되는 회귀 발생
- `0715static` 유저의 기존 녹화 데이터를 adb로 pull, Python으로
  `channelIntensity` EMA 계산을 그대로 재현해서 시뮬레이션 → threshold=0.02에서
  sup/pro가 rest로 오분류되는 비율이 58~88%에 달함을 확인. CH6·CH7이 threshold를
  자주 못 넘긴 게 원인
- threshold를 0.01로 낮춰 재시뮬레이션 → 오분류율 0.4~5.9%로 개선(rest 정확도는
  100%→93.8%로 소폭 하락, hysteresis로 보완). **실기기 반복 테스트 대신 이미
  pull해둔 DB로 오프라인 시뮬레이션하는 게 훨씬 빠르고 정확했음** — 이 패턴은
  앞으로도 재사용 가치 있음

### 7.6 레이더 차트 "신호 살아있음" 표시 (2026-07-15)
- 신호 약할 때 화면에 아예 안 그려지던 문제 → 처음엔 가짜 최소 길이(stub)를
  넣었다가 "이게 진짜 신호인지 디폴트인지 헷갈린다"는 피드백으로 제거
- "근육 활성 상태"(baseline 대비, rest에서 의도적으로 0에 가까움)와 "센서가
  데이터를 보내는가"(raw 값 자체의 흔들림, baseline 무관)가 다른 질문인데 같은
  값(`channelIntensity`)으로 판단한 게 원인 — `channelHasSignal`(raw 기반)을
  새로 만들어 분리
- 부수 버그: 레이더 차트 중심 흰 점(`drawCircle`)이 채널 선보다 **나중에**
  그려지고 있어서, 짧은 선이 그 점 밑에 가려지던 문제 — 그리는 순서만 바꿔서 수정

### 7.7 온셋/오프셋 큐 라벨링 시도 → 되돌림 (2026-07-20)
동적 모션(반복 동작) 녹화 시 take 전체가 단일 라벨로 오염되는 문제(§4.3의
66.4% 원인)를 풀기 위해 "지금 동작하세요" 큐 기반 라벨링을 구현했다가 최종적으로
**되돌림**. 순서:
1. 구현: take 길이(10초) 유지, 5회 반복 신호(원 3개 카운트다운+삐 소리), 활성
   구간만 해당 동작 라벨/나머지 Rest로 윈도우 단위 기록
2. 버그 1 — 소리 안 남: `ToneGenerator.TONE_PROP_BEEP`이 삼성 기기에서 OEM
   자체 정의 톤이라 무음 처리됨 → 표준 DTMF 톤(`TONE_CDMA_PIP`) + `STREAM_ALARM`
   (무음/방해금지 모드 영향 최소)으로 교체
3. 버그 2 — 준비 시간 부족: "지금!" 플래시가 예고 없이 바로 떠서 사용자가
   준비 못 함 → 원 3개가 순서대로 채워지는 카운트다운(동동삡 리듬)으로 재설계
4. 버그 3 — **가장 치명적**: 사용자 요청 "첫 반복 데이터는 쓰지 말자"를
   "Rest로 라벨링"으로 잘못 구현(제 임의 해석, 사용자 확인 안 받음) → 오히려
   "동작이 섞인 신호도 Rest다"라고 모델을 오염시켜 성능이 더 나빠짐. `GESTURE_
   INDEX`에 없는 `DISCARD_LABEL`로 바꿔서 "Rest로 오염" 대신 "학습에서 완전
   제외"로 수정
5. **최종 결과**: 버그를 다 고친 뒤에도 실기기 재테스트 결과가 정적 유지보다
   낮음 → 3개 커밋(`ee9ed46`, `e6c787e`, `e2390e6`) 전부 `git revert`
   (`ae65d2a`, `af543fe`, `4e2f1bb`)로 원복
- **교훈**: 사용자 지시가 여러 해석이 가능할 때("쓰지 말자" = 제외? 재라벨링?)
  임의로 해석해서 구현하지 말고 먼저 확인받을 것 — 이번엔 그걸 안 해서 잘못된
  구현으로 한 번 더 테스트 사이클을 낭비함

### 7.8 개발 환경 관련 반복 함정
- **adb + 한글 Windows 사용자명**: `adb pull` 등에서 로컬 경로에 한글이 섞이면
  실패. 온디바이스 경로만 다루는 명령(`adb shell ... cp`)엔
  `MSYS_NO_PATHCONV=1`, 로컬+원격 경로가 섞이는 명령(`adb pull`)엔 그걸 `unset`
  하고 `MSYS2_ARG_CONV_EXCL="/sdcard"`만 쓸 것. **두 환경변수를 동시에 켜두면
  로컬 경로 변환까지 막혀서 실패** — 여러 번 반복해서 겪은 함정.
- Room DB를 직접 열려면 `.db`+`.db-wal`+`.db-shm` 세 파일을 다 같이 pull해야
  손상 없이 열림(WAL 모드).
- 새 레포를 로컬 clone으로 시작하면 `local.properties`(gitignore됨)가 없어서
  첫 빌드가 "SDK location not found"로 실패 — 기존 레포에서 복사해올 것.

---

## 8. 앞으로의 개선점 (구체적으로)

1. **정확도 개선 — 데이터량**: 지금까지 정량 비교는 전부 1랩짜리 데이터로만
   했음. 앱이 원래 권장하는 5랩까지 채워서 재검증 필요.
2. **정확도 개선 — supination 전극 위치**: 회외근이 팔꿈치 쪽 깊은 곳에 있어
   전완 밴드 위치에서 원천적으로 약하게 잡힐 가능성. 밴드를 근위부(팔꿈치 쪽)로
   옮겨서 CH6/CH7 신호가 더 강해지는지 실측 테스트 필요. 참고로 회외에는
   상완이두근도 관여하는데 이건 위팔에 있어서 전완 밴드로는 원천적으로 캡처 불가.
3. **동적 모션 인식 재도전 여부**: §7.7에서 한 번 시도했다가 되돌림. 재시도한다면
   같은 큐 기반 접근을 반복하지 말고, (a) 신호 기반 자동 온셋 검출(threshold+
   hysteresis, EMG 온셋 검출 문헌의 Teager-Kaiser Energy Operator 등 참고할 만함)
   이나 (b) 사용자가 큐 리듬에 충분히 익숙해진 뒤 녹화 등 근본적으로 다른 접근이
   필요.
4. **TSD 서브윈도우 버그**: §4.1에서 언급한, 현재 파라미터로는 서브윈도우가
   1개만 생성돼 "평균"이 무의미해진 문제. `TSD_WIN_MS`/`TSD_INC_MS`를 128샘플
   윈도우에 맞게 재조정하거나, 애초에 이 방식이 필요한지 재검토.
5. **`fit_local_model`의 죽은 검증셋 코드 정리**: 20% val split을 계산만 하고
   안 쓰는 부분 — 실제로 held-out 정확도를 학습 직후 확인할 수 있게 살리거나
   제거.
6. **유저 데이터 관리 근본 정리**: 학습 성공 시 원본 `recording_takes`가 자동
   삭제되는 게 정상 동작인데, 세션 중 활성 유저가 바뀌면 "학습된 모델을 가진
   유저"와 "지금 활성 유저"가 어긋날 수 있음(우선순위 낮음으로 보류 중).
7. **Classify 화면 rest 후처리 코드 정리**: `STUB_ANGLE_DEVIATION_SCALE` 등
   "stub" 개념이 제거된 뒤에도 이름이 그대로 남아있는 부분 정리.
8. **GitHub push 여부 확인**: 이 세션에서 만든 로컬 커밋들이 `origin/main`에
   반영 안 됐을 수 있음 — `git status`로 `ahead N` 확인하고 필요하면 push.
9. **Raw 스트리밍 On/Off 전류 소모 실측 (§5.6)**: CCCD 구독 해제가 실제로 무선
   송신을 막는다는 건 라이브러리 소스 확인+로그로 정성적으로 확인됨. USB
   전류계로 Off 전/후 소비 전류를 정량 비교하는 건 아직 안 함.

---

## 9. 개발 환경 / 빌드 방법

**빌드 & 설치 (PowerShell)**
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat installDebug          # 컴파일+APK 생성+연결된 기기에 설치
.\gradlew.bat compileDebugKotlin    # 컴파일 에러만 빠르게 확인 (설치 없음)
```
`JAVA_HOME`은 새 터미널마다 다시 잡아야 함. `installDebug`는 덮어쓰기 설치라 앱
데이터(DB, 모델 파일)는 유지됨.

**펌웨어 빌드(arduino-cli)**: `--config-file`로 ASCII 경로 설정 파일 항상 명시,
FQBN에 `CDCOnBoot=cdc` 필수, 모니터는 `-c dtr=on -c rts=off`. §7.1 참고.

---

## 10. 문서 지도

**현재 유효한 문서**

| 문서 | 내용 |
|---|---|
| `HANDOVER.md`(루트, 이 문서) | 전체 개요/아키텍처/진화 과정/트러블슈팅 — 처음 볼 때 여기부터 |
| [`docs/FIRMWARE_PROTOCOL.md`](app/src/main/java/com/mandro/docs/FIRMWARE_PROTOCOL.md) | BLE 프로토콜, 펌웨어 내부 파이프라인 상세 스펙 — **BLE 관련 최신/정확한 유일한 소스** |
| [`docs/RECOGNITION_IMPROVEMENT.md`](app/src/main/java/com/mandro/docs/RECOGNITION_IMPROVEMENT.md) | Classify 화면 개선 전체 이력, 동적 모션 시도/되돌림 상세 로그 |
| [`docs/TERMINOLOGY.md`](app/src/main/java/com/mandro/docs/TERMINOLOGY.md) | UI 카피/용어 가이드 — 백엔드 구조와 무관해서 지금도 유효 |

**구버전 문서 (히스토리 보존, `docs/archive/`로 이동함)**

| 문서 | 왜 archive됐는지 |
|---|---|
| [`docs/archive/README_v1_stale.md`](app/src/main/java/com/mandro/docs/archive/README_v1_stale.md) | 원래 루트 `README.md`. TFLite/Retrofit/서버 학습 등 로컬 전환 이전 아키텍처 설명 (2026-07-20 archive) |
| [`docs/archive/FEATURES.md`](app/src/main/java/com/mandro/docs/archive/FEATURES.md) | Android v1(2026-06-30) 기능 명세서. 아키텍처/기술스택 부분 stale, 화면 흐름/랩 수집 설계는 지금도 유효(§2-1 참고) (2026-07-20 archive) |
| [`docs/archive/HARNESS.md`](app/src/main/java/com/mandro/docs/archive/HARNESS.md) | "DO NOT TOUCH: EmgFeatureExtractor.kt" 섹션만 stale(그 파일 자체가 죽은 코드가 됨), 나머지 Compose/MVVM 규칙은 유효 (2026-07-20 archive) |
| [`docs/archive/BLEPROTOCOL.md`](app/src/main/java/com/mandro/docs/archive/BLEPROTOCOL.md) | 틀린 내용은 아니지만 `FIRMWARE_PROTOCOL.md`에 더 최신/상세하게 흡수됨(중복) (2026-07-20 archive) |
| [`docs/archive/BLEHARNESS.md`](app/src/main/java/com/mandro/docs/archive/BLEHARNESS.md) | Teensy 시절(2026-06-30) BLE 연동 AI 작업 가이드라인 — 하드웨어/연동 방식이 그때와 다 바뀜 (2026-07-22 archive) |
| [`docs/archive/CHAQUOPY_TODO.md`](app/src/main/java/com/mandro/docs/archive/CHAQUOPY_TODO.md) | Chaquopy 전환 체크리스트 — 전환 자체가 완료돼서 목록이 전부 지난 일이 됨 (2026-07-22 archive) |
| [`docs/archive/LOCAL_MIGRATION.md`](app/src/main/java/com/mandro/docs/archive/LOCAL_MIGRATION.md) | 로컬 전환 **사전** 설계안 — 실제 구현은 이 안과 세부적으로 다른 부분 있음(§2-1 참고), 지금은 HANDOVER.md가 실제 결과를 담음 (2026-07-22 archive) |
| [`docs/archive/RAW_STREAM_TOGGLE.md`](app/src/main/java/com/mandro/docs/archive/RAW_STREAM_TOGGLE.md) | raw EMG 스트리밍 On/Off 기능 **구현 전** 설계 논의 — 완성된 기능은 §5.6에 더 정확하게 정리됨 (2026-07-22 archive) |

각 archive 파일 맨 위에 "왜 구버전인지, 뭐가 지금도 유효한지" 안내를 붙여뒀음.
`app/src/main/java/com/mandro/docs/`에는 이제 위 "현재 유효한 문서" 4개(`HANDOVER.md`
제외 3개)만 남아있음 — 나머지는 전부 `archive/` 하위.

---

## 11. 지금 당장 아는 게 필요하면

- **"펌웨어가 지금 뭘 하고 있는지 궁금하다"** → §3, §4.2, 코드는
  `mandro-backend/firmware/exo_armband_hybrid/exo_armband_hybrid.ino`의
  `loop()`(664줄)와 `runInference()`(483줄)부터
- **"안드로이드가 데이터를 어떻게 받는지 궁금하다"** → §5, 코드는
  `BleManager.kt::onCharacteristicChanged()`부터
- **"왜 sup/pro 인식이 안 좋은지 궁금하다"** → §4.3, §8-2
- **"동적 모션을 다시 시도해도 되는지 궁금하다"** → §7.7, §8-3부터 읽을 것
  (같은 접근 반복 금지)
