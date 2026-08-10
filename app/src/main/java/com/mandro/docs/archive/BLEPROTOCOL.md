> ⚠️ **중복 문서 (2026-07-20 archive 처리)**. 내용 자체는 틀리지 않지만
> `docs/FIRMWARE_PROTOCOL.md`가 가중치 수신 프로토콜(Characteristic 3) 등을
> 포함해 더 최신/상세하게 같은 내용을 다룸. 최신 BLE 스펙은
> `docs/FIRMWARE_PROTOCOL.md`를 볼 것.

---

# BLE 프로토콜 명세 — Android 구현용

> 소스: `firmware/exo_armband_hybrid/exo_armband_hybrid.ino`  
> 상세 스펙은 `FIRMWARE_PROTOCOL.md` 참고. 이 문서는 Android BLE 연동 핵심만 정리한다.

---

## 1. 기기 식별

| 항목 | 값 |
|------|----|
| BLE 광고 이름 | `ESP32S3_FAST_BLE` |
| 제조사 VID (USB 감지용) | `0x303A` (Espressif) |
| 칩 | ESP32-S3 |

---

## 2. GATT 프로파일

| 항목 | 값 |
|------|----|
| Service UUID | `12345678-1234-1234-1234-1234567890ab` |
| Characteristic UUID (raw EMG) | `abcd1234-5678-1234-5678-abcdef123456` |
| Characteristic UUID (추론 결과) | `abcd1234-5678-1234-5678-abcdef123457` |

> UUID 마지막 두 자리만 다름: `...56` = EMG, `...57` = 추론 결과

| Characteristic | 속성 | CCCD |
|---|---|---|
| raw EMG (`...56`) | NOTIFY only | BLE2902 포함, `0x0001` 쓰기로 활성화 |
| 추론 결과 (`...57`) | READ + NOTIFY | BLE2902 포함, `0x0001` 쓰기로 활성화 |

---

## 3. Characteristic 1 — raw EMG 스트림 (`...56`)

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
- 실시간 파형 시각화
- 데이터 수집 화면에서 raw 데이터 버퍼링 후 서버 업로드

---

## 4. Characteristic 2 — 추론 결과 (`...57`)

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
64샘플마다 추론 1회 → **약 20Hz** (50ms 간격)

---

## 5. 타이밍

| 항목 | 값 |
|------|----|
| 샘플링 주기 | 781µs → **약 1281 Hz** |
| raw EMG Notify 주기 | 20샘플 × 781µs = 15.6ms → **약 64 Hz** |
| 추론 Notify 주기 | 64샘플 × 781µs = 50ms → **약 20 Hz** |
| MTU 요청 | 247 bytes |

---

## 6. 연결 파라미터

| 항목 | 값 |
|------|----|
| Connection interval min | `0x06` → 7.5ms |
| Connection interval max | `0x0C` → 15ms |

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

---

## 8. 연결 끊김 동작

- 펌웨어가 연결 끊김 시 `BLEDevice::startAdvertising()` 자동 재호출
- Android 앱은 재연결 시도 없이 BLE 스캔 화면으로 이동

---

## 9. BLE 연결 절차 (Android)

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

---

## 10. 백엔드 API 연동 흐름

```
[수집] BLE Characteristic ...56 수신 → raw EMG 버퍼 누적
[업로드] 1랩 완료 시 → POST /sessions/{user_id}/data
           body: multipart
             - emg_data   : uint8 binary, shape (N, 8), row-major
             - labels     : int32 binary, shape (N,)  0~5
             - lap_count  : int
             - gesture_set: "6cl"
[학습] 5랩 이상 누적 시 → POST /sessions/{user_id}/train
[폴링] → GET /sessions/{user_id}/status (2~3초 간격)
         {"status": "training", "progress": 70}
[완료] → GET /sessions/{user_id}/firmware  → firmware.bin 다운로드
[플래시] USB OTG로 암밴드에 firmware.bin 플래시
[추론] BLE Characteristic ...57 수신 → 제스처 분류 결과 표시
```

> 피처 추출 및 모델 학습은 **서버에서 수행**. 앱은 raw EMG 바이너리만 업로드하면 됨.