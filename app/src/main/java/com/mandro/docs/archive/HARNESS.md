> ⚠️ **부분 구버전 문서 (2026-07-20 archive 처리)**. 섹션 2("절대 수정 금지:
> `core/util/EmgFeatureExtractor.kt` 등")는 더 이상 유효하지 않음 — 그
> 전제(Python과 수치를 맞춰야 하는 Kotlin 포팅본이 실제 쓰인다)가 Chaquopy
> 전환 이후 거짓이 됐고, 해당 파일은 현재 코드 어디서도 안 쓰이는 죽은
> 코드로 남아있음(`core/emg/EmgFeatureExtractor.kt`로 위치만 이동). 나머지
> 섹션(Compose/MVVM 아키텍처 규칙)은 여전히 유효함. 최신 아키텍처는 루트
> `HANDOVER.md` 참고.

---

# mandro — AI 코딩 하네스 (Harness Engineering)

> AI 코딩 어시스턴트(Cursor, Copilot 등)가 프론트엔드 개발 시  
> 의도하지 않은 수정을 방지하기 위한 가이드라인  
> 작성일: 2026-06-29

---

## 1. 프로젝트 개요 (AI가 반드시 숙지할 것)

**mandro**는 8채널 EMG 센서 기반 손 제스처 분류 Android 앱이다.

- 언어: Kotlin
- UI: Jetpack Compose
- 아키텍처: MVVM + Clean Architecture
- 최소 SDK: API 24 (Android 7.0)
- 타겟 SDK: API 35

**주요 사용자:** 절단 장애인, 재활 환자, 연구 참여자  
→ 비전문가도 혼자 사용할 수 있어야 함. 기술 용어 노출 금지.

---

## 2. 절대 수정 금지 파일 (DO NOT TOUCH)

아래 파일들은 Python 데스크탑 앱과 수치가 정확히 일치해야 하므로  
AI가 임의로 수정해서는 안 된다.

```
core/util/EmgFeatureExtractor.kt   ← Python features.py 포팅본
core/util/EmgPreprocessor.kt       ← Python preprocessing.py 포팅본
core/util/StandardScaler.kt        ← Python StandardScaler 포팅본
```

**수정이 필요한 경우:**
1. Python 원본 코드와 함께 검토
2. 수치 검증 테스트 통과 확인 후 수정
3. 오차 허용 범위: 1e-4 이내

---

## 3. 아키텍처 규칙

### 3-1. 레이어 의존성 방향
```
presentation → domain ← data
```
- `presentation`은 `domain`의 UseCase만 호출한다
- `presentation`이 `data`를 직접 참조하면 안 된다
- `domain`은 Android 의존성이 없는 순수 Kotlin이어야 한다

### 3-2. 패키지별 역할 — 벗어나면 안 됨

| 패키지 | 역할 | 금지 사항 |
|--------|------|-----------|
| `domain/model` | 데이터 클래스, Enum | Android import 금지 |
| `domain/usecase` | 비즈니스 로직 1개 | Repository 구현체 직접 참조 금지 |
| `domain/repository` | 인터페이스만 | 구현 코드 금지 |
| `core/ble` | Android BLE API | 비즈니스 로직 금지 |
| `core/usb` | Android USB API | 비즈니스 로직 금지 |
| `presentation/ui` | Composable 화면 | 직접 BLE/DB 호출 금지 |
| `presentation/components` | 재사용 컴포넌트 | 특정 화면 로직 금지 |

### 3-3. ViewModel 규칙
- 1개 화면 = 1개 ViewModel
- ViewModel은 UseCase만 호출한다
- UI State는 `StateFlow<UiState>`로 관리
- 이벤트는 `Channel<UiEvent>`로 처리

```kotlin
// 올바른 패턴
class HomeViewModel @Inject constructor(
    private val getUsersUseCase: GetUsersUseCase,   // UseCase만
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()
}

// 금지 패턴
class HomeViewModel @Inject constructor(
    private val userRepository: UserRepositoryImpl, // 구현체 직접 주입 금지
    private val bleManager: BleManager,             // core 직접 주입 금지
) : ViewModel()
```

---

## 4. UI 규칙

### 4-1. 테마 — 임의 색상 사용 금지

모든 색상은 반드시 `MandroPalette`에서 가져온다.

```kotlin
// 올바른 사용
color = MandroPalette.Primary600
color = MandroPalette.Neutral900

// 금지
color = Color(0xFF1234AB)   // 하드코딩 금지
color = Color.Blue          // Material 기본 색상 직접 사용 금지
```

**화면별 배경색 규칙:**
- 라이트 배경: `MandroPalette.Neutral50` — S01~S09, S11
- 다크 배경: `MandroPalette.DarkBg` — S05(파형), S10(동작 인식)

### 4-2. 타이포그래피 — 임의 폰트 사이즈 금지

```kotlin
// 올바른 사용
style = MaterialTheme.typography.headlineLarge  // 28sp
style = MaterialTheme.typography.bodyMedium     // 14sp

// 금지
fontSize = 15.sp    // 임의 사이즈 금지
```

**타이포 스케일:**
| 스타일 | 크기 | 용도 |
|--------|------|------|
| headlineLarge | 28sp Bold | 화면 메인 제목 |
| headlineMedium | 22sp SemiBold | 섹션 제목 |
| headlineSmall | 18sp SemiBold | 카드 제목 |
| bodyLarge | 16sp | 본문 |
| bodyMedium | 14sp | 보조 텍스트 |
| labelLarge | 14sp SemiBold | 버튼 |
| labelMedium | 12sp Medium | 뱃지, 태그 |
| labelSmall | 11sp | 캡션 |

### 4-3. 컴포넌트 — 재사용 컴포넌트 우선 사용

아래 컴포넌트가 있으면 반드시 사용하고 새로 만들지 않는다.

```kotlin
MandroPrimaryButton()    // 파란 버튼
MandroSecondaryButton()  // 아웃라인 버튼
ConnectionBadge()        // 연결 상태 뱃지
MandroProgressBar()      // 진행률 바
TrainingStepRow()        // 학습 단계 행
ConsentCheckboxRow()     // 동의 체크박스
```

### 4-4. 화면 구조 템플릿

모든 화면은 아래 구조를 따른다.

```kotlin
@Composable
fun XxxScreen(
    viewModel: XxxViewModel = hiltViewModel(),
    onNavigate: (Screen) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { /* TopBar */ },
        bottomBar = { /* BottomNav (해당하는 경우만) */ },
    ) { padding ->
        // 화면 콘텐츠
    }
}
```

---

## 5. 용어 규칙 — 앱 내 텍스트

AI가 UI 텍스트를 작성할 때 반드시 아래 용어를 사용한다.

### 5-1. 금지 용어 → 대체 용어

| ❌ 사용 금지 | ✅ 사용 |
|------------|--------|
| Teensy | 암밴드 |
| 펌웨어 업로드 | 암밴드 업데이트 |
| 디바이스 | 암밴드 |
| BLE 연결 | 암밴드 연결 |
| 모델 | 나만의 설정 |
| 데이터 수집 | 동작 녹화 |
| 서버에 전송 중 | 설정을 만들고 있어요 |
| 서버 업로드 | (표시 안 함) |
| 학습 완료: N% | 완성됐어요! 인식 정확도 N% |
| 신뢰도 | 정확도 |
| Take N/10 | N번째 녹화 중 (총 10번) |
| Feature extraction | (표시 안 함) |

### 5-2. 동작 이름 표기

동작명은 영어 그대로 사용하되, 항상 한 줄 설명을 병기한다.

```kotlin
// 올바른 패턴
Text("Flexion")
Text("손목을 아래로 구부리기", style = labelSmall, color = Neutral500)

// 금지
Text("손목 굽힘")   // 한글 단독 사용 금지
Text("Flexion")    // 설명 없이 단독 사용 금지 (가이드/수집 화면에서)
```

### 5-3. 학습 진행 단계 문구 (S08)

```
① 녹화 데이터 확인 중
② 설정을 만들고 있어요
③ 내 동작 패턴을 분석하고 있어요
④ 거의 다 됐어요!
완료: 완성됐어요! 인식 정확도 N%
```

서버, 전송, 업로드 등의 단어는 이 화면에서 절대 노출하지 않는다.

### 5-4. 오류 메시지 형식

오류는 반드시 **무슨 일 + 어떻게** 두 가지를 포함한다.

```kotlin
// 올바른 패턴
"암밴드를 찾지 못했어요. 전원을 확인하고 다시 시도해 주세요."

// 금지
"BLE connection failed"     // 영어 에러 메시지 노출 금지
"연결 실패"                  // 해결 방법 없이 에러만 표시 금지
"Error: GATT 133"           // 기술적 에러코드 노출 금지
```

---

## 6. 화면별 구현 가이드

### S01 · Splash
- 배경: `Primary600` 단색
- 앱 이름: `mandro` (소문자 고정)
- 부제: `내 동작을 기억하는 암밴드`
- 2초 후 자동으로 S02로 이동

### S02 · 홈
- 상단: 암밴드 연결 상태 카드 (`ConnectionBadge` 사용)
- 유저 목록: `UserCard` 컴포넌트
- 유저 없을 때: 빈 상태 + "새 유저 추가" 유도

### S03 · 유저 생성
- 필수 동의 미체크 시 "시작하기" 버튼 비활성화
- 선택 동의 기본값: **미체크**
- `ConsentCheckboxRow` 컴포넌트 사용

### S04 · 암밴드 연결
- 스캔 중 애니메이션: 펄스 원형
- 디바이스명 그대로 표시 (EMG-Sensor-XXXX)
- 신호 세기: `BleDevice.signalLabel` 사용 ("연결 상태 좋음/보통/약함")

### S05 · 파형 모니터 (다크)
- 배경: `DarkBg`
- 8채널 색상: `MandroPalette.waveColors` 리스트 순서대로
- Canvas로 실시간 스크롤 파형 구현
- 바텀 컨트롤: 일시정지 / 스케일 / 채널 on/off

### S06 · 동작 가이드
- 6개 동작 페이지 형태 (ViewPager 또는 스텝)
- 진행 바: 현재 동작 / 6
- 동작명 + 한 줄 설명 항상 병기
- 주의사항: `Warning100` 배경 카드

### S07 · 동작 녹화
- 카운트다운: 3 → 2 → 1 → 지금!
- 녹화 중 실시간 파형 표시
- 완료된 동작: 체크 표시 + `Success600` 색상
- 개별 take 재녹화 가능

### S08 · 학습 진행
- `TrainingStepRow` 컴포넌트 사용
- 서버 관련 용어 절대 노출 금지
- 예상 시간 표시 ("예상 시간: 약 30초")
- 앱 종료 방지 안내

### S09 · 암밴드 업데이트
- 체크리스트 3개 모두 완료 시 버튼 활성화
- USB 연결 감지 자동 처리
- "나중에 할게요" 버튼 항상 제공

### S10 · 동작 인식 (다크)
- 배경: `DarkBg`
- **방사형 채널 시각화**: Canvas, 8채널을 원형 배치, 신호 세기 = 벡터 길이
- 최근 인식 기록: 가로 스크롤 pill 형태
- 인식 정확도 낮을 때 "다시 학습하기" 유도
- v2 예정: 팔 측면 실루엣 + 근육 활성화 맵 (현재 구현 금지)

### S11 · 설정
- 연구 참여 동의 상태 변경 가능
- "내 데이터 삭제" — 확인 다이얼로그 필수
- 모델 정보: 정확도만 표시 ("인식 정확도 N%")

---

## 7. 상태 관리 규칙

### 7-1. UiState 패턴

```kotlin
// 각 화면의 UiState는 이 구조를 따른다
data class XxxUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    // 화면별 데이터
)

// 이벤트는 Channel로
sealed class XxxUiEvent {
    data class NavigateTo(val screen: Screen) : XxxUiEvent()
    data class ShowError(val message: String) : XxxUiEvent()
}
```

### 7-2. BLE 상태는 전역 관리

BLE 연결 상태는 앱 전역에서 동일해야 한다.

```kotlin
// BleViewModel은 싱글톤처럼 ActivityRetainedScoped 또는
// MainViewModel에서 공유해서 사용
// 각 화면 ViewModel이 별도로 BLE 상태를 관리하면 안 됨
```

---

## 8. 금지 패턴 목록

```kotlin
// ❌ 1. domain 레이어에 Android import
import android.content.Context  // domain/model, domain/usecase에서 금지

// ❌ 2. Composable에서 직접 Repository 호출
val repo: UserRepository by inject()  // Composable에서 직접 DI 금지

// ❌ 3. 하드코딩된 색상
Box(modifier = Modifier.background(Color(0xFF1478F5)))

// ❌ 4. 임의 Padding/Size 값
Modifier.padding(13.dp)   // 디자인 시스템에 없는 값 금지
// 허용: 4, 8, 12, 16, 20, 24, 32, 40, 48dp

// ❌ 5. 기술 용어 UI 노출
Text("서버에 업로드 중...")
Text("BLE GATT 연결 실패")
Text("모델 학습 완료")

// ❌ 6. EmgFeatureExtractor 임의 수정
// 수치 검증 없이 알고리즘 변경 금지

// ❌ 7. v2 기능 미리 구현
// 팔 실루엣, 근육 활성화 맵은 v2 예정 — 지금 구현 금지
```

---

## 9. 커밋 전 체크리스트

- [ ] `MandroPalette` 외 하드코딩 색상 없음
- [ ] 기술 용어 UI 노출 없음 (서버, BLE, 모델, 펌웨어 등)
- [ ] `EmgFeatureExtractor` 미수정 또는 수치 검증 완료
- [ ] domain 레이어에 Android import 없음
- [ ] ViewModel이 UseCase만 호출함
- [ ] 오류 메시지에 해결 방법 포함됨
- [ ] 동작명에 한 줄 설명 병기됨 (가이드/수집 화면)
- [ ] v2 기능 구현 없음