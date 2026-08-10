> ⚠️ **구버전 문서 (2026-07-20 archive 처리)**. TFLite 온보드 추론, Retrofit
> 서버 통신, Kotlin으로 수동 포팅한 피처 추출기(`EmgFeatureExtractor.kt` 등)를
> 전제로 설명하고 있음 — 이후 서버 완전 제거 + Chaquopy(Python 온디바이스 학습)
> 전환으로 이 아키텍처는 더 이상 유효하지 않음. 최신 문서는 루트
> `HANDOVER.md` 참고. 히스토리 보존 목적으로만 남겨둠.

---

# mandro Android 프로젝트 구조

## 데모 영상

https://github.com/user-attachments/assets/70a54704-5914-45a6-ac54-dcddce8aab29

## 패키지 구조

```
com.mandro
├── core/                           # 플랫폼 레이어 (Android API 직접 사용)
│   ├── ble/
│   │   └── BleManager.kt          # BLE 스캔, 연결, EMG 데이터 수신
│   ├── usb/
│   │   └── UsbManager.kt          # USB Host API, 펌웨어 업데이트
│   ├── network/
│   │   └── NetworkModule.kt       # Hilt 네트워크 모듈 (Retrofit, OkHttp)
│   └── util/
│       ├── EmgFeatureExtractor.kt # ⭐ 피처 추출 (Python 포팅, 132차원)
│       ├── EmgPreprocessor.kt     # 밴드패스 필터, Envelope
│       ├── EmgRingBuffer.kt       # 실시간 윈도잉 (128 samples)
│       └── StandardScaler.kt     # mean/std 정규화
│
├── data/                           # 데이터 레이어
│   ├── local/
│   │   ├── db/
│   │   │   ├── MandroDatabase.kt  # Room DB
│   │   │   ├── UserDao.kt
│   │   │   └── SessionDao.kt
│   │   └── datastore/
│   │       └── UserPreferences.kt # DataStore (현재 유저 ID 등)
│   └── remote/
│       ├── api/
│       │   └── MandrApiService.kt # Retrofit 인터페이스
│       └── dto/
│           └── Dtos.kt            # 요청/응답 DTO
│
├── domain/                         # 비즈니스 로직 레이어 (순수 Kotlin)
│   ├── model/
│   │   ├── User.kt                # User, GestureSet
│   │   ├── EmgData.kt             # EmgSample, EmgWindow, RecordingBatch 등
│   │   └── BleDevice.kt           # BleDevice, BleState
│   ├── repository/
│   │   └── Repositories.kt        # Repository 인터페이스 + TrainingProgress
│   └── usecase/
│       ├── CreateUserUseCase.kt
│       ├── StartRecordingUseCase.kt
│       ├── TrainModelUseCase.kt    # 서버 학습 요청 + 폴링
│       └── ClassifyGestureUseCase.kt # TFLite 실시간 추론
│
└── presentation/                   # UI 레이어
    ├── theme/
    │   └── Theme.kt               # MandroTheme, 컬러, 타이포
    ├── components/
    │   └── Components.kt          # 공통 컴포넌트
    ├── navigation/
    │   └── Navigation.kt          # Screen, BottomNavItem
    └── ui/
        ├── home/                  # S02 홈
        ├── user/                  # S03 유저 생성
        ├── ble/                   # S04 암밴드 연결
        ├── waveform/              # S05 파형 모니터
        ├── guide/                 # S06 동작 가이드
        ├── collect/               # S07 동작 녹화
        ├── training/              # S08 학습 진행
        ├── firmware/              # S09 암밴드 업데이트
        ├── classify/              # S10 동작 인식
        └── settings/              # S11 설정
```

## 아키텍처 원칙

**Clean Architecture 3레이어**
```
Presentation → Domain ← Data
```
- `domain`은 Android 의존성 없는 순수 Kotlin
- `presentation`은 `domain` UseCase만 호출
- `data`는 `domain` Repository 인터페이스 구현

**단방향 데이터 플로우**
```
UI Event → ViewModel → UseCase → Repository → Data Source
                ↓
           UI State (StateFlow)
```

## 핵심 구현 주의사항

### 피처 추출 수치 검증 (최우선)
`EmgFeatureExtractor.kt`는 Python `lib/features/features.py`와
**동일한 입력에 동일한 출력**이 나와야 합니다.

검증 방법:
```python
# Python에서 테스트 벡터 생성
import numpy as np
window = np.random.randn(128, 8).astype(np.float32)
features = extract_features_pipeline(window[np.newaxis])[0]
print(features.tolist())  # → Kotlin 테스트 케이스에 입력
```

```kotlin
// Kotlin에서 동일 입력 → 동일 출력 확인
@Test fun testFeatureExtraction() {
    val window = // 위 Python 출력과 동일한 128×8 배열
    val features = EmgFeatureExtractor.extract(window)
    // features와 Python 출력 수치 비교 (오차 1e-4 이내)
}
```

### BLE UUID 교체
`BleManager.kt`의 `EMG_SERVICE_UUID`, `EMG_CHARACTERISTIC_UUID`를
실제 펌웨어 UUID로 교체 필요 (TODO 주석 확인)

### 패킷 파싱 포맷 확인
`parseEmgPacket()` 내 패킷 구조가 실제 펌웨어와 일치하는지 확인 필요

## 의존성 주요 버전

| 라이브러리 | 버전 |
|-----------|------|
| Kotlin | 2.0+ |
| Compose BOM | 2024.x |
| Hilt | 2.51+ |
| Room | 2.6+ |
| Retrofit | 2.11+ |
| TFLite | 2.14+ |
| minSdk | 24 (API 24, Android 7.0) |
| targetSdk | 35 |
