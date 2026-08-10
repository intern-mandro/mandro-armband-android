> ⚠️ **구버전 문서 (2026-07-22 archive됨)** — Teensy 시절(F01/F02 스프린트,
> 2026-06-30) AI 작업 가이드라인. 하드웨어가 ESP32-S3로 바뀌고 BLE 연동이
> 완료된 지금은 이 문서가 전제하는 상황 자체가 지났음. 현재 BLE 구현/프로토콜은
> [`HANDOVER.md`](../../../../../../../../HANDOVER.md) §3, §5,
> [`FIRMWARE_PROTOCOL.md`](../FIRMWARE_PROTOCOL.md) 참고. 히스토리 보존 목적으로만 남김.

# mandro — BLE 연동 하네스 (Harness Engineering)

> AI 코딩 어시스턴트가 BLE 실제 연동 작업 시  
> 의도하지 않은 수정/설계 이탈을 방지하기 위한 가이드라인  
> 작성일: 2026-06-30  
> 작업 범위: F01(BLE 연결), F02(파형 시각화) 실제 연동

---

## 1. 작업 목표 (이번 스프린트 한정)

현재 상태: UI는 fake 데이터로 완성됨 (✅), 실제 BLE 연동만 빠짐 (🔌)

이번 작업의 목표는 **딱 두 가지만**이다:
1. 암밴드와 실제 BLE 페어링/연결이 되게 한다
2. 수신한 EMG 신호가 S05 파형 화면에 실시간으로 그려지게 한다

**이번 작업에서 하지 않는 것 (건드리면 안 됨):**
- 서버 학습 연동 (F06) — 다음 스프린트
- USB 펌웨어 업로드 (F07) — 다음 스프린트
- TFLite 추론 (F08) — 다음 스프린트
- 피처 추출기(`EmgFeatureExtractor.kt`) — 별도 git 관리, 이번 작업과 무관

---

## 2. 절대 수정 금지

```
core/util/EmgFeatureExtractor.kt   ← 이번 작업과 무관, 건드리지 않음
presentation/ui/**/*.kt (이미 완성된 UI 컴포넌트 구조) ← 레이아웃/스타일 변경 금지
```

UI는 이미 fake 데이터로 완성되어 디자인 검토까지 끝난 상태다.  
**BLE 연동 작업은 ViewModel과 Repository 레이어에서만 일어나야 하며, Composable의 UI 구조/스타일을 변경하지 않는다.**

ViewModel이 받는 데이터 타입(State)만 fake → real로 바뀌는 것이지,  
화면에 보이는 레이아웃, 색상, 텍스트는 손대지 않는다.

---

## 3. 확정된 설계 결정 (변경 금지, 재논의 없이 그대로 구현)

### 3-1. 연결 끊김 시 동작
```
❌ 자동 재연결 3회 시도 (이전 설계, 폐기됨)
✅ 연결 끊김 즉시 BLE 스캔 화면(S04)으로 강제 이동
```

### 3-2. 파형 화면 기능 범위
```
❌ 일시정지/재개 버튼 — 제거됨, 구현하지 않음
❌ 스크롤 속도 조절 — 제거됨, 구현하지 않음
✅ 채널 on/off 토글 — 유지
✅ 신호 없는 채널 안내 문구 — 유지
```

### 3-3. 렌더링 방식
```
✅ awaitFrame() + 링 버퍼(채널당 200 samples) 방식 고정
❌ Handler/Timer 기반 폴링 금지
❌ LaunchedEffect(Unit) { while(true) { delay(16) } } 같은 패턴 금지
```

`awaitFrame()`은 `androidx.compose.runtime.withFrameNanos` 또는  
`androidx.compose.foundation.gestures` 패키지 활용. Compose 프레임 콜백에  
동기화해서 그려야 레이턴시가 최소화된다.

---

## 4. BLE 패킷 사양 (✅ 확정 — 펌웨어 .ino 직접 확인 완료)

```kotlin
// core/ble/BleManager.kt — 실제 펌웨어 값으로 교체 완료
private const val EMG_SERVICE_UUID        = "12345678-1234-1234-1234-1234567890ab"
private const val EMG_CHARACTERISTIC_UUID = "abcd1234-5678-1234-5678-abcdef123456"
```

**이 값들은 확정됐다. AI가 임의로 변경하면 안 된다.**

패킷 포맷 (✅ 확정):

| 항목 | 값 | 비고 |
|------|----|------|
| 패킷 크기 | 160 bytes | 20샘플 × 8채널 × 1byte |
| 데이터 타입 | **uint8** | int16 아님! 펌웨어 `uint8_t txBuffer` 확인됨 |
| 값 범위 | 0~255 | 펌웨어에서 ADC값 - 250 후 클리핑 |
| Notify 주기 | ~64Hz | |
| 채널 순서 | CH0~CH7 순서대로 | byte[0]=CH0, byte[7]=CH7 |

**핵심 파싱 주의사항:**
```kotlin
// ✅ 올바른 파싱 — uint8, 부호 확장 방지
(bytes[offset].toInt() and 0xFF).toFloat()

// ❌ 틀린 파싱 — int16 가정 (이전 코드, 이미 수정됨)
val raw = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
raw.toShort().toFloat()
```

패킷 크기가 160bytes가 아닌 경우 `parseEmgPacket()`에서 무시(drop)한다.  
파싱 로직 수정이 필요한 경우 `parseEmgPacket()` 함수만 수정하고 다른 레이어는 건드리지 않는다.

---

## 5. 레이어 책임 분리 (반드시 지킬 것)

```
core/ble/BleManager.kt
  → Android BLE API 직접 호출
  → 패킷 파싱 (byte → EmgSample)
  → BleState, EmgSample Flow로 노출

data/ (BleRepositoryImpl.kt — 신규 작성 필요)
  → BleManager를 감싸서 domain/repository/BleRepository 인터페이스 구현
  → 여기서 비즈니스 로직 추가하지 않음 (그냥 위임)

presentation/ui/ble/BleViewModel.kt (신규 작성 필요)
presentation/ui/waveform/WaveformViewModel.kt (신규 작성 필요)
  → BleRepository만 호출
  → core/ble 패키지를 직접 import하면 안 됨
```

**금지 패턴:**
```kotlin
// ❌ ViewModel에서 BleManager 직접 주입
class WaveformViewModel @Inject constructor(
    private val bleManager: BleManager,  // 금지 — Repository를 거쳐야 함
) : ViewModel()

// ✅ 올바른 패턴
class WaveformViewModel @Inject constructor(
    private val bleRepository: BleRepository,  // 인터페이스만
) : ViewModel()
```

---

## 6. Mock 모드 유지 (중요)

실제 하드웨어 없이도 개발/테스트 가능해야 한다.  
`BleRepositoryImpl`은 빌드 설정에 따라 Mock과 Real을 전환할 수 있어야 한다.

```kotlin
// di/BleModule.kt (✅ 구현 완료)
@Module
@InstallIn(SingletonComponent::class)
object BleModule {
    @Provides
    @Singleton
    fun provideBleRepository(
        bleManager: BleManager,
        mockBleRepository: MockBleRepository,
    ): BleRepository {
        return if (BuildConfig.USE_MOCK_BLE) mockBleRepository
               else BleRepositoryImpl(bleManager)
    }
}
```

`MockBleRepository` (✅ 구현 완료):
- 64Hz Notify 주기 시뮬레이션 (실제 펌웨어 동일)
- 패킷당 20샘플 (실제 펌웨어 동일)
- uint8 범위 0~255로 신호 생성 (실제 펌웨어 동일)
- 채널별 다른 주파수/위상의 sine + noise 조합

**AI가 임의로 Mock 모드를 삭제하거나 우회하면 안 된다.** 실기 연동 검증 전까지 유지.

---

## 7. 권한 처리

Android 12+ (API 31+)부터 BLE 권한 체계가 바뀌었다. minSdk가 24이므로 두 체계 모두 지원해야 한다.

```xml
<!-- AndroidManifest.xml -->
<!-- API 30 이하 -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />

    <!-- API 31+ -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

권한 요청 UX는 S04 화면 진입 시 처리하며, **권한 거부 시 별도 안내 화면이나 다이얼로그가 필요하면**  
새 화면을 만들지 말고 S04 내 상태(분기)로 처리한다 (기존 화면 구조 유지 원칙).

---

## 8. 용어 규칙 (기존 하네스와 동일하게 적용)

BLE 관련 작업이지만 유저에게 노출되는 모든 텍스트는 기존 용어집을 따른다.

| 기술 용어 | 화면 표시 |
|-----------|----------|
| BLE 연결 실패 | "암밴드를 찾지 못했어요. 전원을 확인하고 다시 시도해 주세요." |
| GATT 에러 코드 | 절대 노출 금지 |
| 연결 끊김 → 재연결 유도 | "암밴드와 연결이 끊어졌어요. 가까이 두고 다시 연결해 주세요." |
| RSSI 값 | 숫자 노출 금지, `signalLabel`("연결 상태 좋음/보통/약함")만 사용 |

---

## 9. 완료 기준 (Definition of Done)

이번 BLE 연동 작업은 아래를 모두 만족해야 완료로 본다.

- [ ] 실제 암밴드와 페어링 → 연결 성공
- [ ] S04 화면에서 실제 스캔된 디바이스 목록 표시 (fake 제거)
- [ ] 연결 성공 시 S05로 정상 진입, fake 파형 → 실제 파형으로 교체
- [ ] 8채널 모두 올바른 채널에 매핑되어 표시됨 (CH0~7 순서 검증)
- [ ] 연결 끊김 시 S04로 자동 이동 확인
- [ ] Mock 모드 빌드 플래그로 정상 전환 확인 (하드웨어 없이도 빌드/테스트 가능)
- [ ] 신호 없는 채널에 안내 문구 정상 표시
- [ ] 권한 미허용 시 적절한 안내 (앱 크래시 없음)
- [ ] `EmgFeatureExtractor.kt` 변경 없음
- [ ] 기존 Composable UI 구조/스타일 변경 없음

---

## 10. 다음 스프린트 예고 (지금은 손대지 말 것)

```
F06 서버 학습 — subprocess 방식으로 우선 구현 예정
F07 USB 펌웨어 업로드
F08 TFLite 추론 (EmgFeatureExtractor 연동 시점)
S07-B 녹화 품질 리포트
S11 설정 화면
```

이번 BLE 작업 중 위 항목들의 인터페이스(Repository, UseCase 시그니처)를  
미리 손대고 싶어질 수 있으나, **확정되지 않은 범위는 건드리지 않는다.**  
필요하면 TODO 주석만 남긴다.