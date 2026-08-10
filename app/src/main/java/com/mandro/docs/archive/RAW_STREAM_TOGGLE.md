> ⚠️ **구버전 문서 (2026-07-22 archive됨)** — raw EMG 스트리밍 On/Off 기능
> **구현 전** 설계 논의 문서. 기능 자체는 구현·검증 완료됐고, 최종 동작
> 방식(BLE2902 CCCD 내부 동작까지)은
> [`HANDOVER.md`](../../../../../../../../HANDOVER.md) §5.6과
> [`FIRMWARE_PROTOCOL.md`](../FIRMWARE_PROTOCOL.md) §3에 더 정확하고
> 최신 상태로 정리되어 있음. 설계 당시 고민 과정(옵션 A/B 비교 등)을 보고
> 싶을 때만 이 문서를 볼 것.

# Raw EMG 스트리밍 On/Off 설정 (전력 절약)

> 2026-07-20. 설계 논의 문서 — 구현 전 결정 필요한 것들을 정리.

## 배경

암밴드가 raw EMG(Characteristic `...56`)를 **항상, 무조건 20샘플마다(~64Hz)**
BLE notify로 쏘고 있음(`exo_armband_hybrid.ino::loop()`, HANDOVER.md §3.2).
추론 결과(`...57`, ~20Hz)는 인식 기능에 필수지만, raw EMG는 Waveform 화면(파형
시각화)이나 Classify 화면의 레이더 차트처럼 **화면에 그릴 때만** 필요함. 그런데
지금은 화면과 무관하게 펌웨어가 계속 쏘고 있어서, 안 쓰이는 상황에서도 BLE 라디오
송신(=전력 소모)이 계속 발생함.

목표: 사용자가 (또는 앱이 자동으로) raw EMG 스트리밍을 껐다 켰다 할 수 있게 해서
불필요한 라디오 송신을 줄임.

## 현재 구조 재확인 (중요)

`BleManager.kt`에 이미 `emgEnabled`라는 플래그가 있고 화면들이 진입/이탈 시
`setEmgEnabled(true/false)`를 호출하고 있지만, **이건 안드로이드 앱 내부에서
수신한 패킷을 파싱할지 말지 결정하는 로컬 필터일 뿐**임:
```kotlin
private fun parseEmgPacket(bytes: ByteArray) {
    if (!emgEnabled) return   // 이미 도착한 패킷을 버릴지 말지만 결정
    ...
}
```
**펌웨어는 이 값을 전혀 모르고, 상관없이 계속 보냄.** 즉 지금은 "라디오는 계속
켜져있고 앱만 안 듣는 척"하는 상태 — 전력 절약 효과가 없음. 이번에 하려는 건
**펌웨어가 실제로 안 쏘게** 만드는 것.

## 설계 옵션 (결정 필요)

### 옵션 A — BLE 표준 구독(CCCD) 메커니즘 재사용 (추천)

BLE 스펙상 Notify는 원래 "클라이언트가 CCCD(0x2902 디스크립터)에 `0x0001`을
써야 구독 상태가 되고, 그래야 서버가 실제로 notify를 보낼 수 있다"는 게 표준
동작. 지금 연결 절차에서 raw EMG Characteristic의 CCCD에 `0x0001`을 쓰고
있는데(연결 시 항상), 이걸 **`0x0000`으로 다시 쓰면(구독 해제)** BLE 스택
자체(Bluedroid, 이 프로젝트가 쓰는 라이브러리는 `BLEDevice.h`/`BLE2902.h` —
Kolban 계열, NimBLE 아님)가 실제로 무선 송신을 안 하게 되는 게 표준 동작임.

- 장점: **펌웨어 쪽 코드 변경이 거의 필요 없을 수 있음**(표준 BLE 스택이 이미
  이렇게 동작해야 함) — 안드로이드에서 CCCD를 껐다 켰다 하는 것만으로 끝날 가능성
- 리스크: "이론상 이렇게 동작해야 한다"이지, **이 프로젝트가 실제로 쓰는 라이브러리
  버전에서 그렇게 동작하는지 실측 확인이 안 된 상태**. 확인 안 하고 진행하면
  "껐다고 표시는 되는데 실제로는 계속 나가고 있다" 같은 상황이 재발할 수 있음
  (이번 세션에서 `emgEnabled`가 딱 이런 함정이었음 — 이름은 "껐다"인데 실제
  라디오는 안 꺼짐)

### 옵션 B — 명시적 제어 바이트 추가

작은 WRITE 가능한 값(기존 characteristic에 얹거나 새 characteristic 신설)을
안드로이드가 써서, 펌웨어가 `rawStreamingEnabled` 플래그를 명시적으로 관리.
`loop()`의 raw notify 블록을 `if (pos==0 && deviceConnected &&
rawStreamingEnabled)`로 감싸면 됨.

- 장점: 라이브러리 내부 동작에 의존 안 함, 확실히 검증 가능(펌웨어 로그로 직접 확인)
- 단점: 새 프로토콜(또는 기존 Characteristic 3처럼 새 UUID) 필요, 펌웨어 변경량 더 큼

**제안**: 옵션 A를 먼저 시도해서 실측(펌웨어 로그 또는 전류 소모로) 확인 —
CCCD 구독 해제만으로 정말 라디오 송신이 멈추면 그걸로 끝. 안 되면(라이브러리가
구독 여부와 무관하게 무조건 notify를 시도하는 구현이면) 옵션 B로 보강.

## 결정 필요한 것들 (2026-07-20 확정)

1. **기본값**: **Off**. 앱 처음 실행/연결 시 raw 스트리밍은 꺼져 있음
   (`RawStreamPreferences`의 DataStore 기본값 `false`).
2. **설정 위치**: Settings 화면("원본 신호 수신" 토글). Home/연결 화면에는
   노출하지 않음.
3. **Waveform/Collect 화면 처리** (2026-07-20 1차 결정 → 같은 날 수정):
   처음엔 두 화면 다 **(a)** 자동 On으로 결정했었으나, "Waveform과 인식(Classify)
   부분만 Off를 그대로 따르고, Collect는 제외"로 재조정함. 이유: Collect(녹화)는
   raw 샘플이 없으면 녹화 버퍼가 비어서 **빈 데이터가 저장되는 실질적 손상**이
   생기지만, Waveform은 "신호 없음" 표시(및 캘리브레이션 진행 정지)로 그친다.
   - **Collect**: 여전히 진입 시 자동 On (raw 필수, 변경 없음)
   - **Waveform**: 자동 On 제거 — Settings 설정을 그대로 따름. Off인 채로
     들어가면 파형 Canvas는 flat buffer → 기존 "신호 없음" 표시가 자연히 뜨고,
     캘리브레이션 시작 시 진행률이 0%에서 멈춘다(raw 샘플이 안 들어오므로).
     이 상태는 의도된 트레이드오프 — 별도 안내 문구/자동 유도 없음. 사용자가
     Settings에서 직접 켜야 함.
4. **Classify 화면**: **그냥 숨김** 채택 — raw가 꺼져 있으면 레이더 차트의
   채널 선(및 관련 그리기)을 안내 문구 없이 그냥 안 그림(기준/중심점만 표시).
   "지금 동작" 추론 텍스트는 raw와 무관하게 계속 동작. Waveform과 마찬가지로
   강제 On 없음(원래부터 이렇게 구현됨).

## 구현 상태 (2026-07-20)

옵션 A(CCCD 구독 해제)로 구현 완료:
- `RawStreamPreferences.kt` (DataStore, 기본 `false`)
- `BleManager.setRawEmgSubscribed(enabled)` — CCCD에 `0x0000`/`0x0001` 재기록
- `BleRepositoryImpl`가 `@Singleton` 스코프에서 `bleState`와
  `rawStreamPreferences.enabled`를 `combine`해서, 연결될 때마다(재연결 포함)
  저장된 설정값을 다시 적용
- **Collect ViewModel**: 진입 시 자동 On (raw 필수라 예외)
- **Waveform/Classify ViewModel**: 설정값을 그대로 따름, 강제 On 없음.
  Off면 Waveform은 "신호 없음"+캘리브레이션 정지, Classify는 레이더 차트 숨김
- Settings 화면에 토글 UI 추가

**아직 미검증**: CCCD 구독 해제가 실제로 펌웨어의 라디오 송신을 멈추는지
(옵션 A의 핵심 가정)는 실기기에서 확인 전. 아래 "검증 방법" 참고.

## 예상 작업 범위

**펌웨어 (`mandro-backend/firmware/exo_armband_hybrid/`)**
- (옵션 A만으로 될 경우) 변경 없거나 최소— CCCD 상태에 따라 실제로 notify가
  억제되는지 로그로 검증만
- (옵션 B 필요시) `rawStreamingEnabled` 플래그 추가, `loop()` 조건 수정, 제어
  경로(새 characteristic 또는 기존 것 재사용) 프로토콜 설계+구현

**안드로이드 (`mandro-dynamic-gesture`)**
- `BleManager.kt`: raw EMG characteristic의 CCCD를 `0x0000`/`0x0001`로 다시
  쓸 수 있는 함수 추가(현재는 연결 시 한 번 `0x0001`만 씀)
- 설정 영속화: `DataStore` 기반 preference 신설(패턴은 기존 `UserPreferences.kt`
  참고)
- Settings 화면에 토글 UI 추가
- 위 "결정 필요한 것들" 3, 4번에 따라 Waveform/Collect/Classify ViewModel에
  화면 진입/이탈 시 재연동 로직 추가
- 재연결 시(펌웨어가 재부팅되거나 연결이 끊겼다 다시 붙을 때) CCCD 상태가
  초기화되므로, 저장된 사용자 설정을 다시 적용하는 로직 필요

## 검증 방법

- 펌웨어에 임시 디버그 로그(`Serial.println`) 추가해서 raw notify가 실제로
  스킵되는지 확인(간단, 빠름)
- 가능하면 실제 전류 소모 측정(USB 전류계 등)으로 체감 아닌 숫자로 확인 —
  없으면 정성적 확인(펌웨어 로그)으로 대체

## 다음 단계

1. 위 "결정 필요한 것들" 1~4번 답변 받기
2. 옵션 A(CCCD 구독 해제)부터 펌웨어에 임시 로그 붙여서 실측 검증
3. 검증 결과에 따라 옵션 A로 충분한지 옵션 B가 필요한지 확정
4. 안드로이드 쪽 설정 UI/영속화/화면별 처리 구현
