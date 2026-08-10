# 펌웨어 프로토콜 명세 — Android 앱 개발용

> 소스 펌웨어: `firmware/esp32/exo_armband_hybrid_6clf/`  
> 이 문서는 Android BLE 연동 및 모델 플래시에 필요한 모든 스펙을 정리한다.

---

## 목차

1. [기기 식별](#1-기기-식별)
2. [BLE GATT 프로파일](#2-ble-gatt-프로파일)
3. [Characteristic 1 — raw EMG 스트림](#3-characteristic-1--raw-emg-스트림)
4. [Characteristic 2 — 추론 결과](#4-characteristic-2--추론-결과)
4-1. [Characteristic 3 — 가중치 수신 (신규, 로컬 학습용)](#4-1-characteristic-3--가중치-수신-신규-로컬-학습용)
5. [타이밍](#5-타이밍)
6. [연결 파라미터](#6-연결-파라미터)
7. [채널-핀 매핑](#7-채널-핀-매핑)
8. [연결 끊김 동작](#8-연결-끊김-동작)
9. [모델 플래시 방식](#9-모델-플래시-방식)
10. [펌웨어 내부 추론 파이프라인](#10-펌웨어-내부-추론-파이프라인)
11. [백엔드 → 앱 → 암밴드 전체 흐름](#11-백엔드--앱--암밴드-전체-흐름)
12. [Android 연동 순서](#12-android-연동-순서)

---

## 1. 기기 식별

| 항목 | 값 |
|------|----|
| BLE 광고 이름 | `ESP32S3_FAST_BLE` |
| 제조사 VID (USB 감지용) | `0x303A` (Espressif) |
| 칩 | ESP32-S3 |

---

## 2. BLE GATT 프로파일

| 항목 | 값 |
|------|----|
| Service UUID | `12345678-1234-1234-1234-1234567890ab` |
| Characteristic UUID (raw EMG) | `abcd1234-5678-1234-5678-abcdef123456` |
| Characteristic UUID (추론 결과) | `abcd1234-5678-1234-5678-abcdef123457` |
| Characteristic UUID (가중치 수신) | `abcd1234-5678-1234-5678-abcdef123458` |

> UUID 마지막 두 자리만 다름: `...56` = EMG, `...57` = 예측, `...58` = 가중치 수신(§4-1)

**Characteristic 속성:**

| Characteristic | 속성 | CCCD |
|---|---|---|
| raw EMG (`...56`) | NOTIFY only | BLE2902 포함, `0x0001`/`0x0000`로 구독 켜고 끌 수 있음(전력 절약, §3 참고) |
| 추론 결과 (`...57`) | READ + NOTIFY | BLE2902 포함, `0x0001` 쓰기로 활성화, 항상 구독 유지 |
| 가중치 수신 (`...58`) | WRITE + NOTIFY | BLE2902 포함, `0x0001` 쓰기로 응답 NOTIFY 활성화 (§4-1) |

---

## 3. Characteristic 1 — raw EMG 스트림

### 패킷 크기

```
SAMPLES_PER_PACKET = 20
N_CHANNELS         = 8
PACKET_SIZE        = 20 × 8 = 160 bytes
```

### 레이아웃 (row-major)

```
byte[0..7]    → sample 0, CH0~CH7
byte[8..15]   → sample 1, CH0~CH7
...
byte[152..159]→ sample 19, CH0~CH7

인덱스 공식: byte[샘플번호 × 8 + 채널번호]
```

### 값 타입 및 범위

| 항목 | 내용 |
|------|------|
| 타입 | `uint8` (부호 없는 1바이트) |
| 원본 ADC | 10-bit (0~1023) |
| 펌웨어 변환 | `val = ADC_read - 250`, 클리핑 `[0, 255]` |
| 유효 범위 | 0~255 |
| 엔디안 | 해당 없음 (1바이트) |

### Android 파싱 예시 (Kotlin)

```kotlin
fun parseEmgPacket(bytes: ByteArray): Array<IntArray> {
    // returns [20][8] — [sample_index][channel_index]
    val result = Array(20) { IntArray(8) }
    for (s in 0 until 20) {
        for (ch in 0 until 8) {
            result[s][ch] = bytes[s * 8 + ch].toInt() and 0xFF
        }
    }
    return result
}
```

> `and 0xFF` 필수 — Kotlin `Byte`는 signed (-128~127)이므로 uint8로 재해석해야 함.

### 용도

- 실시간 파형 시각화 (신호 탭 8채널 파형)
- 데이터 수집 화면에서 raw 데이터 버퍼링 후 로컬 학습(§9 참고 — 서버 업로드 아님)

### 전력 절약 — 이 Characteristic만 구독 껐다 켤 수 있음 (2026-07-20)

`RawStreamPreferences`/`BleManager.kt::setRawEmgSubscribed()`(HANDOVER.md §5.6
참고)가 이 Characteristic의 CCCD를 사용자 설정에 따라 `0x0000`/`0x0001`로
다시 쓴다. **펌웨어 코드는 전혀 안 바뀜** — `loop()`의 `notify()` 호출은
여전히 무조건 실행되고, 실제로 무선이 나가는지는 라이브러리가 CCCD를 보고
알아서 판단한다. 추론 결과(`...57`)는 이 전력 절약 대상이 아니라 항상 구독
유지가 기본 — 인식 기능 자체가 이 스트림에 의존하기 때문.

**동작 원리 — CCCD 값이 저장되고 확인되는 경로**:

1. **구독 설정 방향 (Android → ESP32)**: `gatt.writeDescriptor(desc)`로 BLE
   무선 ATT Write Request가 ESP32에 도착 → 라이브러리
   (`BLEDescriptor.cpp::handleGATTServerEvent()`)가 `ESP_GATTS_WRITE_EVT`를
   받아서 `setValue(param->write.value, param->write.len)` 호출 → 이게
   `BLE2902` 객체(`addDescriptor(new BLE2902())`로 붙여둔 것)의 2바이트
   메모리에 값을 저장함. `BLE2902`는 `BLEDescriptor`를 상속한 CCCD 전용
   클래스로, `byte[0]`의 bit 0이 notification on/off를 뜻함
   (`BLE2902::getNotifications()`/`setNotifications()`가 이 비트를
   읽고/씀). Characteristic마다(raw EMG/추론/가중치) 독립된 `BLE2902`
   인스턴스가 있어서 구독 상태가 서로 안 섞임.
2. **데이터 알림 방향 (ESP32 → Android)**: `notify()`가 호출될 때마다
   라이브러리(`BLECharacteristic.cpp::notify()`)가
   `p2902->getNotifications()`로 위에서 저장된 값을 읽고, 꺼져있으면 실제
   무선 송신 함수(`esp_ble_gatts_send_indicate`) 호출 전에 `return`해버림 —
   앱단 필터가 아니라 **ESP32 BLE 스택 내부에서 무선 송신 자체가 스킵됨**.

이 저장/확인 로직 전부 ESP32 BLE 라이브러리(`C:\Arduino15\packages\esp32\
hardware\esp32\<버전>\libraries\BLE\src\`, 프로젝트 시작 때부터 있던
서드파티 코드, 우리가 수정한 적 없음) 안에서 일어난다 — 펌웨어/안드로이드
코드는 각각 "쓰기 요청을 보내고 결과 확인" / "notify를 무조건 부르고 파싱"만
한다.

---

## 4. Characteristic 2 — 추론 결과

### 포맷

```
"classname|l0|l1|l2|l3|l4|l5"
```

| 필드 | 설명 | 예시 |
|------|------|------|
| classname | 최종 예측 제스처명 (3-vote cascade 적용) | `flexion` |
| l0~l5 | 각 클래스 softmax 확률 (소수점 3자리) | `0.020` |

### 실제 패킷 예시

```
"flexion|0.020|0.910|0.030|0.015|0.015|0.010"
```

### 클래스 인덱스 매핑

| 인덱스 | 클래스명 | 동작 |
|--------|----------|------|
| 0 | `rest` | 휴식 |
| 1 | `flexion` | 손목 아래로 구부리기 |
| 2 | `extension` | 손목 위로 젖히기 |
| 3 | `close` | 주먹 쥐기 |
| 4 | `supination` | 손바닥 위로 돌리기 |
| 5 | `pronation` | 손바닥 아래로 돌리기 |

### Android 파싱 예시 (Kotlin)

```kotlin
fun parsePrediction(bytes: ByteArray): Pair<String, FloatArray> {
    val msg = String(bytes, Charsets.UTF_8)
    val parts = msg.split("|")
    val className = parts[0]
    val logits = FloatArray(6) { i -> parts[i + 1].toFloat() }
    return Pair(className, logits)
}
```

### 발행 주기

64샘플마다 추론 1회 → **약 20Hz** (64 / 1281 Hz ≈ 50ms)

### 3-vote cascade

연속 3번의 추론 중 다수결로 최종 예측 결정. 노이즈성 순간 오분류 방지.

```
vote_threshold = 0.34 (= 1/3보다 조금 높음)
threshold 미달 시 직전 예측 유지
```

---

## 4-1. Characteristic 3 — 가중치 수신 (신규, 로컬 학습용)

> **⚠️ 펌웨어 미구현 — Phase 5에서 추가 필요.** Chaquopy로 폰 안에서 학습한 가중치를
> BLE로 암밴드에 보내기 위한 Characteristic. 기존 `...56`/`...57`과 별개로 신설.

### 배경 및 설계 판단

기존에는 재학습할 때마다 서버가 가중치를 헤더 파일(`MODEL.h` 등)로 만들어서 펌웨어
전체를 재컴파일 → USB로 통째로 재플래시하는 방식이었다(9절 참고, 지금은 구방식).
이 방식은 재학습 한 번 할 때마다 펌웨어 빌드 환경(arduino-cli 등)이 필요하고, 매번
전체 바이너리를 플래시해야 해서 느리고 번거롭다.

**개발 방향**: 신경망의 구조(토폴로지 — 몇 층, 층마다 뉴런 몇 개)는 재학습해도 바뀌지
않고, 학습으로 얻은 **숫자 값(가중치)만** 매번 바뀐다는 점에 착안해서, 구조는 펌웨어에
고정으로 컴파일해두고(`MODEL.h`의 `MODEL_TOPOLOGY` 등) 가중치만 별도 파일(`/weights.bin`)로
분리해 전송하는 방식으로 간다. 이렇게 하면 펌웨어는 최초 1회만 플래시하면 되고, 이후
재학습 결과는 파일 교체만으로 반영된다.

이 판단에 따라 아래 세부 사항들이 정해졌다:

- **저장소로 LittleFS 선택**: SPIFFS는 Espressif가 deprecated 처리했고, 전원이 갑자기
  꺼져도 데이터가 덜 깨지는 wear-leveling 구조라 공식적으로 LittleFS를 권장함. 53KB급
  바이너리 쓰기/읽기가 실제 하드웨어에서 문제없이 동작하는지는 별도 프로젝트
  (`esp32-littlefs-test`)로 먼저 검증 완료.
- **전송 채널을 USB와 BLE 둘 다 지원**: USB는 케이블 연결 상태에서 스트리밍으로 빠르고
  확실하게 보낼 수 있어 개발/디버깅 및 폴백용으로 두고, BLE는 케이블 없이 "재학습 →
  바로 반영"이 가능해야 하는 실사용 흐름에 필요해서 같이 지원한다. 두 채널 모두 동일한
  페이로드 포맷(매직넘버+길이+데이터+CRC32)을 쓰고, 검증(매직넘버/CRC 확인) 로직도
  공유 가능 — 다만 **BLE는 MTU 제한 때문에 여러 조각으로 나눠 오므로, 펌웨어가 조각을
  순서대로 이어붙이는 재조립 로직이 USB 수신 로직과 별도로 필요**하다 (USB는 연속
  스트림이라 이 부분이 필요 없음).
- **BLE 청크 크기 244바이트**: MTU(247)에서 ATT 헤더(3바이트)를 뺀 최대값을 그대로 씀.
  Write Request(응답 대기 방식)라 링크 레이어에서 이미 재전송을 보장해주기 때문에,
  청크를 더 작게 쪼갠다고 신뢰성이 올라가지 않고 전송 시간(패킷 수)만 늘어난다고
  판단해서 최대치로 정함. 이 값은 Android(`BleManager.kt::sendWeights()`)에 이미
  고정 구현되어 있으므로, **펌웨어는 청크 크기를 따로 정할 필요 없이 이 크기의 Write를
  받아들이는 재조립 버퍼만 준비하면 된다.**
- **임시 파일 → CRC 검증 → rename 순서로 저장**: BLE 연결이 전송 도중 끊기거나 USB가
  중간에 뽑히는 경우, 기존에 잘 쓰던 가중치 파일이 반쯤 덮어써진 채로 남으면 다음 부팅
  때 손상된 가중치로 추론하게 된다. 이를 피하기 위해 수신 데이터는 항상 임시 파일
  (`/weights.tmp`)에 먼저 쓰고, CRC32 검증까지 통과했을 때만 실제 `/weights.bin`으로
  교체(rename)한다. 검증 실패 시 임시 파일만 지우고 기존 `/weights.bin`은 그대로 둔다.

이런 판단들을 바탕으로 확정된 스펙은 아래와 같다.

### 식별

| 항목 | 값 |
|------|----|
| Characteristic UUID | `abcd1234-5678-1234-5678-abcdef123458` |
| 속성 | **WRITE** (Android → 암밴드) + **NOTIFY** (암밴드 → Android, 응답용) |
| CCCD | 포함, `0x0001` 쓰기로 NOTIFY 활성화 (기존 두 Characteristic과 동일 패턴) |

### 패킷 포맷 (USB와 동일 페이로드, MTU 단위로만 분할)

```
[4 bytes] MAGIC   : 0xDEADBEEF (little-endian)
[4 bytes] LENGTH  : payload 바이트 수 = 53,304 (uint32 little-endian)
[N bytes] PAYLOAD : W0 b0 W1 b1 W2 b2 means stds (float32, LOCAL_MIGRATION.md 직렬화 순서와 동일)
[4 bytes] CRC32   : PAYLOAD 체크섬 (little-endian)
```

- 총 패킷 크기: 53,316 bytes
- BLE Write 청크 크기: **244 bytes** (MTU 247 − ATT 오버헤드 3)
- 필요한 Write 횟수: `⌈53316 / 244⌉ = 219`회, 순차 Write Request(응답 대기 후 다음 전송)

### 암밴드 응답 (Characteristic 3의 NOTIFY로 전송)

| 응답 (UTF-8 텍스트) | 의미 |
|---|---|
| `OK:WEIGHTS` | 수신 완료, CRC 일치, `/weights.tmp` → `/weights.bin`으로 교체(rename) 성공 → 재부팅 |
| `ERR:SIZE` | LENGTH 필드와 실제 수신 바이트 수 불일치 (`/weights.tmp` 삭제, `/weights.bin`은 유지) |
| `ERR:CRC` | CRC32 불일치 (`/weights.tmp` 삭제, `/weights.bin`은 유지) |

Android는 마지막 청크 전송 후 이 NOTIFY를 최대 10초 대기하며, 타임아웃/오류 시 실패 처리.

**펌웨어 저장 순서 (안전장치)**: 수신되는 바이트는 곧바로 `/weights.bin`에 덮어쓰지
않고, 항상 `/weights.tmp`에 먼저 쓴다. CRC32 검증까지 통과한 뒤에만 `/weights.tmp`를
`/weights.bin`으로 교체한다. 이렇게 하면 전송 도중 연결이 끊기거나 검증에 실패해도
기존에 정상 동작하던 가중치 파일이 훼손되지 않는다.

### Android 구현 위치

`core/ble/BleManager.kt::sendWeights()` — 이미 구현됨 (Kotlin 쪽은 완료, 펌웨어 쪽만 남음).

---

## 5. 타이밍

| 항목 | 값 |
|------|----|
| 샘플링 주기 | 781µs → **약 1281 Hz** |
| raw EMG Notify 주기 | 20샘플 × 781µs = 15.6ms → **약 64 Hz** |
| 추론 Notify 주기 | 64샘플 × 781µs = 50ms → **약 20 Hz** |
| MTU 요청 | 247 bytes (160바이트 패킷 여유 있음) |

---

## 6. 연결 파라미터

| 항목 | 값 |
|------|----|
| Connection interval min | `0x06` → 7.5ms |
| Connection interval max | `0x0C` → 15ms |
| Slave latency | 0 |

Android에서 `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` 호출 권장.

---

## 7. 채널-핀 매핑

| 채널 | ADC 핀 |
|------|--------|
| CH0 | GPIO 9 |
| CH1 | GPIO 10 |
| CH2 | GPIO 7 |
| CH3 | GPIO 8 |
| CH4 | GPIO 11 |
| CH5 | GPIO 12 |
| CH6 | GPIO 17 |
| CH7 | GPIO 18 |

패킷 내 CH0~CH7 순서는 위 핀 순서와 동일.

---

## 8. 연결 끊김 동작

- 펌웨어가 연결 끊김 시 `BLEDevice::startAdvertising()` 자동 재호출
- Android 앱은 재연결 시도 없이 BLE 스캔 화면(S04)으로 이동 (기능명세 F01 결정사항)

---

## 9. 모델 플래시 방식

> 2026-07-20 갱신 — 아래 §4-1(BLE 가중치 수신)이 실제로 구현·검증된 현재 방식임.
> 이전엔 이 섹션이 "학습마다 펌웨어 전체를 재컴파일해서 USB로 재플래시"하던
> 구시대 흐름을 그대로 기록하고 있었는데, 실제로는 그 방식으로 간 적 없이
> `LOCAL_MIGRATION.md`가 설계한 대로 처음부터 BLE 가중치 전송으로 구현됨 — 내용을
> 현재 코드(`MODEL.h`, `nn.cpp::loadFromLittleFS()`) 기준으로 다시 씀.

### 개요

TFLite도, "모델을 통째로 C 헤더 파일로 변환해서 매번 재플래시"하는 방식도 안 씀.
**신경망 구조(토폴로지)는 컴파일 시점에 고정**되고, **재학습마다 바뀌는 가중치만**
BLE로 전송해서 LittleFS에 저장 → 재부팅 시 로드하는 방식.

### MODEL.h — 이제 고정 파일, 재생성 안 함

`firmware/exo_armband_hybrid/MODEL.h`는 토폴로지/활성화 함수 상수만 담고
있고, **재학습해도 이 파일은 안 바뀜**:
```c
const int MODEL_N_LAYERS = 4;
const int MODEL_TOPOLOGY[4] = { 132, 64, 64, 6 };
const int MODEL_ACTIVATIONS[3] = { 0, 0, 1 };  // RELU, RELU, SOFTMAX
```
가중치 배열은 여기 없음 — 부팅 시 `NeuralNet::loadFromLittleFS()`가
`/weights.bin`에서 읽어옴(§4-1). **`means.h`/`stds.h` 같은 별도 헤더 파일도
더 이상 없음** — StandardScaler의 mean/std는 `weights.bin` 페이로드 안에
가중치와 함께 이어붙어서 옴(`W0 b0 W1 b1 W2 b2 means stds` 순서, §4-1 참고).

### 실제 펌웨어 플래시(USB, arduino-cli)가 필요한 시점 — 최초 1회뿐

```
[최초 셋업, 새 암밴드마다 딱 1회]
arduino-cli로 exo_armband_hybrid 컴파일(FQBN에 CDCOnBoot=cdc 필수)
    ↓ USB
ESP32-S3에 업로드
    ↓
[암밴드] 부팅 → LittleFS.begin(true)로 빈 파일시스템 자동 포맷
    ↓ /weights.bin 아직 없음
[암밴드] "no weights — inference disabled" — 부팅/raw EMG 송신은 정상,
         추론 결과만 비활성
    ↓
[이후, 재학습할 때마다] Android가 §4-1 프로토콜로 가중치(53,304B)만 BLE 전송
    ↓
[암밴드] /weights.tmp에 받고 CRC 통과 시 /weights.bin으로 교체 → 재부팅
    ↓
[암밴드] 새 가중치로 추론 시작 (재플래시 없음)
```

**재플래시가 다시 필요한 경우는 딱 3가지뿐**: (1) 새 암밴드 최초 셋업, (2)
`MODEL_TOPOLOGY` 자체를 바꿀 때(예: 132→64→64→6이 아닌 다른 신경망 구조로
설계 변경), (3) 펌웨어 로직(전처리, BLE 프로토콜 등) 자체를 수정할 때. 그냥
"유저가 재학습했다"는 여기 해당 안 됨 — §4-1의 BLE 가중치 전송만으로 끝남.

---

## 10. 펌웨어 내부 추론 파이프라인

암밴드가 BLE로 추론 결과를 쏘기 위해 내부적으로 수행하는 과정.

### 상수

```c
WINDOW_SIZE     = 128       // 추론 윈도우 샘플 수
INFER_HOP       = 64        // 추론 간격 (50% overlap)
N_CHANNEL       = 8
N_FEATURES      = 132       // 피처 벡터 차원
SAMPLING_FREQ   = 1200 Hz
ENVELOPE_KERNEL = 12        // moving average 커널 크기
```

### 처리 순서

```
getEMG() → circular buffer (128×8) 누적
    64샘플마다 runInference() 호출
        ↓
1. applyBandpass()
   - Butterworth 4차 BP 필터 (35~300 Hz)
   - Direct Form II Transposed (causal, Python lfilter와 동일)
   - 필터 계수: preprocessor.cpp의 bp_b[], bp_a[]
   - 초기 상태: lfilter_zi(b, a) * x[0]

2. applyRectify()
   - abs() 취하기

3. applyEnvelope()
   - 이동평균 (kernel=12), circular buffer 방식
   - 윈도우 시작마다 상태 리셋 (Python np.convolve와 동일)

4. extractClassicFeatures()  → 88차원
   [시간영역, 피처 기준 묶음, 인덱스 0..47]
     MAV   × 8ch (idx 0..7)
     MaxAV × 8ch (idx 8..15)
     STD   × 8ch (idx 16..23)
     RMS   × 8ch (idx 24..31)
     WL    × 8ch (idx 32..39)
     SSC   × 8ch (idx 40..47)
   [주파수영역, 채널 기준 묶음, 인덱스 48..87]
     ch0: MeanPow, TotalPow, MeanFreq, MedianFreq, PeakFreq (idx 48..52)
     ch1: 위와 동일 (idx 53..57)
     ...
     ch7: 위와 동일 (idx 83..87)

5. extractTSDFeatures()  → 44차원
   [공분산 상삼각, 인덱스 88..123]
     (0,0),(0,1),(0,2),...,(0,7),
     (1,1),(1,2),...,(1,7),
     ...
     (7,7) → 총 36개
   [채널별 에너지, 인덱스 124..131]
     ch0~ch7 → 8개
   TSD 서브윈도우: win=96샘플, inc=48샘플, lam=0.1
   np.cov 기준 ddof=1 (n-1로 나눔)

6. standardize()
   - (x / std) - (mean / std) 형태로 계산 (수치 안정성)
   - means.h의 STANDARDIZER_MEANS[132]
   - stds.h의 STANDARDIZER_STDS[132]

7. nn.predict()
   - Dense(132→64, ReLU) → Dense(64→64, ReLU) → Dense(64→6, Softmax)
   - 가중치: MODEL.h의 MODEL_WEIGHTS[], MODEL_BIASES[]

8. 3-vote cascade → BLE Notify (Characteristic ...57)
```

---

## 11. 백엔드 → 앱 → 암밴드 전체 흐름

```
──────────────── 최초 사용 (학습 + 플래시) ────────────────

[Android 앱]
  유저 생성 → POST /users
  BLE 연결 → raw EMG 수집 (Characteristic ...56)
  랩 완료 → POST /sessions/{user_id}/data (raw EMG binary 업로드)
  5랩 이상 → POST /sessions/{user_id}/train
  폴링    → GET  /sessions/{user_id}/status (2~3초 간격)
  학습 완료→ GET  /sessions/{user_id}/model?file=MODEL.h
           → GET  /sessions/{user_id}/model?file=means.h
           → GET  /sessions/{user_id}/model?file=stds.h
  USB OTG  → 암밴드에 헤더 포함 펌웨어 바이너리 플래시

──────────────── 일반 사용 (추론) ────────────────

[암밴드]
  BLE Notify → Characteristic ...56: raw EMG (160바이트, 64Hz)
  BLE Notify → Characteristic ...57: 추론 결과 (텍스트, 20Hz)

[Android 앱]
  ...56 수신 → 파형 시각화
  ...57 수신 → 제스처 분류 결과 표시
```

---

## 12. Android 연동 순서

### BLE 연결 절차

```
1. BLE 스캔 → "ESP32S3_FAST_BLE" 기기 찾기
2. connectGatt()
3. onConnectionStateChange() → requestMtu(247)
4. onMtuChanged() → discoverServices()
5. onServicesDiscovered():
   - getService("12345678-1234-1234-1234-1234567890ab")
   - getCharacteristic("...123456") → setCharacteristicNotification(true)
                                    → CCCD에 0x0001 쓰기
   - getCharacteristic("...123457") → setCharacteristicNotification(true)
                                    → CCCD에 0x0001 쓰기
6. requestConnectionPriority(CONNECTION_PRIORITY_HIGH)
7. onCharacteristicChanged() 콜백으로 데이터 수신 시작
```

### 데이터 수집 절차 (학습용)

```
1. Characteristic ...56 수신 → 160바이트 파싱 → 샘플 버퍼에 누적
2. 동작별 레이블(0~5) 타임스탬프 기준으로 병렬 기록
3. 1랩(6동작 × 10초) 완료 시 → POST /sessions/{user_id}/data
   body: multipart
     - emg_data  : uint8 binary, shape (N, 8), row-major
     - labels    : int32 binary, shape (N,)
     - lap_count : int (이번 업로드의 랩 수)
     - gesture_set: "6cl"
4. 5랩 이상 누적 시 학습 가능
```

### 모델 플래시 절차 (USB OTG)

```
1. GET /sessions/{user_id}/model?file=MODEL.h   → 저장
2. GET /sessions/{user_id}/model?file=means.h   → 저장
3. GET /sessions/{user_id}/model?file=stds.h    → 저장
4. 다운받은 헤더 파일을 펌웨어 소스에 포함하여 빌드
5. USB OTG로 ESP32에 플래시
6. 암밴드 재부팅 → 새 모델로 추론 시작
```

> **⚠️ 현재 백엔드 미구현 사항:**  
> 백엔드가 현재 `model.tflite`와 `scaler.json`을 생성하고 있으나,  
> 실제 필요한 파일은 `MODEL.h` / `means.h` / `stds.h`다.  
> 백엔드의 `services/training.py`에 export 기능 추가가 필요하다.

---

## 부록 — 피처 벡터 132개 상세 인덱스

| 인덱스 | 피처 | 채널 |
|--------|------|------|
| 0~7 | MAV | ch0~ch7 |
| 8~15 | MaxAV | ch0~ch7 |
| 16~23 | STD | ch0~ch7 |
| 24~31 | RMS | ch0~ch7 |
| 32~39 | WL | ch0~ch7 |
| 40~47 | SSC | ch0~ch7 |
| 48~52 | MeanPow, TotalPow, MeanFreq, MedianFreq, PeakFreq | ch0 |
| 53~57 | 위와 동일 | ch1 |
| 58~62 | 위와 동일 | ch2 |
| 63~67 | 위와 동일 | ch3 |
| 68~72 | 위와 동일 | ch4 |
| 73~77 | 위와 동일 | ch5 |
| 78~82 | 위와 동일 | ch6 |
| 83~87 | 위와 동일 | ch7 |
| 88~123 | 공분산 상삼각 (i,j), i≤j | ch0~ch7 |
| 124~131 | 채널별 에너지 | ch0~ch7 |
