# 동작 인식 성능 개선 (전처리/후처리)

> 2026-07-15. 동적 모션 인식(온셋/오프셋 라벨링 재설계)은 2순위로 미루고,
> 그 전에 지금 있는 정적 인식 파이프라인의 실사용 품질을 먼저 개선한다.
> 여긴 설계 논의 문서 — 구현 전 결정할 것들을 정리한다.

## 배경

Classify 화면(레이더 차트)을 실사용해보니 두 가지 문제가 눈에 띔.

1. 아무 동작도 안 하고 가만히 있어도(=rest여야 정상) 추론 결과가 계속 다른 클래스로 흔들림
2. 신호가 약할 때 화면에 아무것도 안 그려져서, "암밴드가 신호를 안 보내는 건지" "약하게
   보내는 건지" 구분이 안 됨

---

## 문제 1 — rest 판정이 불안정함

### 현재 상태 (코드 확인 완료)

- 펌웨어(`exo_armband_hybrid.ino`)는 추론마다 3-vote cascade만 적용함
  (`vote_threshold=0.34`, 최근 3번 추론 중 다수결, 미달 시 직전 예측 유지) — 이건
  "순간적인 오분류 1번"은 걸러주지만, **신호 자체가 약하거나 노이즈성일 때 "무조건
  rest로 본다"는 판단은 하지 않음**
- 앱(`ClassifyViewModel.kt::observeInference()`)도 펌웨어가 보낸 `className`을
  그대로 화면에 반영함 — 신호 세기 기반 override 없음

즉 rest는 "다른 클래스들과 동등한 하나의 클래스"로만 취급되고 있고, "가장 확실하게
구분 가능한 상태"라는 특성을 전혀 활용하지 않고 있음.

### 제안하는 방향

신호 크기 기반으로 **NN 예측과 무관하게 rest로 강제 판정**하는 후처리 레이어 추가.

**판단 기준 (안)**
1. **전체적으로 신호가 안 들어오는 경우**: 8채널 종합 activity(예: envelope 합/최대값)가
   `RestCalibration.baseline` 대비 일정 threshold 이하 → rest
2. **한 채널만 튀는 경우(노이즈 의심)**: 8채널 중 1개만 threshold를 넘고 나머지 7개는
   baseline 근처 → 실제 근수축이 아니라 아티팩트(전극 접촉 불량, 케이블 흔들림 등)로
   보고 rest 처리

**아직 결정 안 된 것들**
- **어디서 판단할지**: 펌웨어(재플래시 필요, 실시간 예산 안에 들어가야 함) vs 앱
  (`emgStream`+`inferenceStream`을 같이 보고 있는 `ClassifyViewModel`에서 후처리,
  재플래시 없이 반복 실험 가능) — **앱 쪽에서 먼저 프로토타입 하는 걸 추천**: 이미
  `RestCalibration.baseline`과 raw `emgStream`을 다 갖고 있고, 반복 튜닝이 훨씬 빠름.
  검증되면 펌웨어로 옮길지는 나중에 결정.
- **threshold 구체적인 값**: baseline + 몇 배(std? 고정값?), "몇 ms/몇 프레임 연속"으로
  판단할지(순간적 튐 방지용 hysteresis 필요 — 이건 지난번 논의한 온셋 검출 threshold+
  hysteresis 아이디어와 같은 계열)
- **"한 채널만 튐" 판단 기준**: 몇 개 채널까지를 "소수"로 볼지(1개? 2개?), threshold
  자체는 문제 1의 전체 threshold와 같은 값을 쓸지 별도로 둘지

---

## 문제 2 — 신호 약할 때 화면이 완전히 빔

### 현재 상태 (코드 확인 완료)

[`ClassifyScreen.kt:179`](../presentation/ui/classify/ClassifyScreen.kt) —
```kotlin
val intensity = channelIntensity[ch].coerceIn(0f, 1f)
if (intensity < 0.02f) return@forEachIndexed   // 그냥 안 그림
```
채널 세기가 0.02 미만이면 그 채널의 선을 아예 그리지 않음. 그래서 신호가 약할 때
"신호 자체가 안 들어옴(연결 문제)"과 "신호가 약하게 들어옴(정상, 그냥 힘을 안 줌)"이
화면상 똑같이 "아무것도 안 보임"으로 나타남.

### 제안하는 방향

**신호가 들어오고 있다는 사실 자체는 항상 시각적으로 확인 가능해야 함.**

- threshold 미만이어도 완전히 숨기지 말고, **아주 옅은(낮은 alpha) 짧은 선**을 항상
  그려서 "신호는 수신 중"임을 표시
- 세기가 커질수록 자연스럽게 진해지고/길어지는 방식이면, 지금의 "threshold 넘으면
  뿅 나타남" 방식보다 부드러운 전환도 덤으로 얻을 수 있음
- 참고: `WaveformScreen.kt`에는 이미 `NO_SIGNAL_THRESHOLD` 같은 유사 개념이 있어서
  (별도 확인 필요) 그쪽 처리 방식과 통일할지도 검토

**참고할 기존 패턴 (확인 완료)** — `WaveformScreen.kt:318-359`에 이미 똑같은 문제를
겪고 해결해둔 코드가 있음:
```kotlin
private const val NO_SIGNAL_THRESHOLD = 5   // 신호 없음 판정 임계값 (0~255 기준)
...
val hasSignal = (p98 - p2) > NO_SIGNAL_THRESHOLD
val lineColor = if (hasSignal) color else color.copy(alpha = 0.25f)  // 숨기지 않고 옅게
```
Waveform 화면은 신호가 threshold 밑이어도 선을 아예 안 그리는 게 아니라 **alpha를
0.25로 낮춰서 옅게 그림** — Classify 레이더 차트의 `return@forEachIndexed`(완전히
스킵)를 이 패턴으로 바꾸면 됨. 새로 설계할 필요 없이 **기존 코드 그대로 재사용
가능**.

**아직 결정 안 된 것들**
- 레이더 차트는 선 "길이"가 의미(세기)를 담고 있어서, Waveform처럼 색 alpha만 낮추는
  걸로 충분할지, 아니면 길이도 최소 스텁 길이를 둘지 — Waveform은 파형 전체를 옅게
  칠하는 거라 상황이 살짝 다름 (레이더는 threshold 밑에서 아예 길이 0이라 "옅은 색"만으론
  안 보일 수 있음 → 최소 길이 + 옅은 alpha 조합이 필요할 가능성)
- 문제 1의 "강제 rest 판정"과 이 화면 표시 로직이 상호작용하는지 — 강제로 rest
  처리된 상태에서도 레이더 차트에 옅은 선은 그대로 보여줄지, 아니면 rest 판정 자체를
  화면에도 반영(예: "rest"라고 크게 안내)할지

---

## 진행 상황 (2026-07-15)

**threshold 확정 및 1차 구현 완료**:
- 활성 채널 판단: `channelIntensity >= 0.02`
- rest 강제 판정: 활성 채널 ≤ 1개 상태가 100ms 이상 지속 → `ClassifyViewModel`에서
  NN 예측 무시하고 rest로 덮어씀
- 레이더 차트: threshold 미만이면 스킵 대신 최소 길이(6%) + alpha 0.25로 그리기
  (`ClassifyScreen.kt`)

**실기기 테스트 결과**:
- ✅ 문제 1(rest 흔들림)은 잘 해결됨
- ❌ 문제 2(신호 살아있음 표시)는 기대한 효과가 안 남 — **원인 파악됨**: 레이더
  차트의 stub도 `channelIntensity`(baseline 대비 편차)를 그대로 재사용했는데,
  캘리브레이션이 잘 됐다는 것 자체가 "rest일 때 이 값이 0에 가깝게 나오도록
  맞춰졌다"는 뜻이라 rest 상태에서는 stub도 거의 안 움직임. **"근육이 활성 상태인가"
  (baseline 대비, 문제 1에 필요)와 "센서가 데이터를 보내고 있는가"(raw 자체의 흔들림,
  문제 2에 필요)는 다른 질문인데 같은 값(`channelIntensity`)으로 판단한 게 원인.**
  Waveform 화면의 `hasSignal`은 baseline 보정 없는 raw p2-p98 스프레드로 판단해서
  이 문제가 없음 — Classify도 그 방식을 따로 가져와야 할 듯.

## 문제 2 재설계 및 구현 (2026-07-15, 2차)

**핵심 아이디어**: "근육이 활성 상태인가"(`channelIntensity`, baseline 대비 편차 —
rest에서 0에 가까운 게 정상)와 "센서가 실제로 데이터를 보내고 있는가"(raw 값 자체의
흔들림)를 완전히 분리된 두 값으로 나눔.

**구현**:
- `ClassifyViewModel`에 raw(보정 전) 값의 최근 64샘플(~50ms) 윈도우 min-max
  스프레드로 판단하는 `channelHasSignal: BooleanArray` 추가 (`RAW_NO_SIGNAL_THRESHOLD
  = 5`, Waveform의 `NO_SIGNAL_THRESHOLD`와 동일 값 재사용)
- `ClassifyScreen.kt` 레이더 차트: **길이는 `channelIntensity` 그대로**(가짜 최소
  길이 없음), **색상/alpha만 `channelHasSignal`로 결정** — rest에서 길이는 짧아도
  색은 정상으로 유지돼서 "신호는 살아있다"가 전달됨

**가짜 최소 길이(stub) 도입 → 제거 히스토리**: 처음엔 `MIN_STUB_RADIUS_RATIO`로
짧은 채널에 고정 최소 길이를 줬는데, 사용자 피드백으로 **"이게 진짜 신호인지
디폴트값인지 헷갈린다"**는 문제가 있어서 제거함 — 길이는 항상 실제 `channelIntensity`
값을 정직하게 반영하고, "살아있음" 표시는 색상(`channelHasSignal`)에만 위임하는
쪽으로 정리.

**추가로 발견/수정한 버그**: `drawCircle()`(중심 흰 점, 반지름 4dp)이 채널 선보다
**나중에** 그려지고 있어서, stub 제거 후 짧아진 채널 선들이 이 점 밑에 가려지는
문제 발견 → 중심점을 채널 선보다 **먼저** 그리도록 순서 변경 (`ClassifyScreen.kt`).

**미해결**: 각도 흔들림 완화용 `STUB_ANGLE_DEVIATION_SCALE`가 `isActive`
(`channelIntensity >= LOW_SIGNAL_THRESHOLD`) 기준으로 남아있음 — 이름이
더는 안 맞을 수 있어서 다음에 정리 필요.

## rest 판정 재조정 필요성 발견 (2026-07-15, 3차)

**회귀 발견**: `MAX_QUIET_ACTIVE_CHANNELS = 1`(활성 채널 ≤1개면 rest 강제) 룰이
supination/pronation 자체도 rest로 잡아먹는 문제 발생 — sup/pro는 원래 활성화되는
채널 수 자체가 적어서(회외근/원회내근이 팔꿈치 쪽 깊은 곳에 있어 이 밴드 위치에서
약하게 잡힘, weights.bin 분석에서도 CH7 의존도가 높고 다른 채널은 상대적으로 약함),
"노이즈 1채널 튐"과 "진짜 약한 sup/pro"가 지금 룰(채널 개수만 봄) 기준으로 구분이
안 됨.

**초기 제안(미적용, 폐기)**: `MAX_QUIET_ACTIVE_CHANNELS`를 1→0으로 낮추는 안을
검토했으나, 실측 데이터로 시뮬레이션해보니 이걸로도 부족했음(아래 참고) — 폐기.

### 실측 데이터 기반 재조정 (2026-07-15, 4차)

`0715static` 유저의 기존 녹화 데이터(`mandro4.db`, adb로 이미 pull해둔 것)를 갖고
`ClassifyViewModel`의 `channelIntensity` EMA 공식을 Python으로 그대로 재현해서
검증함 (baseline은 Rest take 자체 평균으로 근사).

**채널별 분석 결과**: **CH6, CH7 딱 두 채널만** supination/pronation에서 rest 대비
뚜렷하게 구분됨(intensity median 0.015~0.022 근처). 나머지 6개 채널(CH0~5)은
sup/pro를 해도 rest와 거의 값이 같음 — 즉 이 밴드 배치에서 sup/pro의 판별 정보가
CH6/CH7 두 채널에 집중돼 있음.

**threshold=0.02(기존)로 시뮬레이션한 "rest 강제 발동 비율"**:
| | Rest | Supination | Pronation |
|---|---|---|---|
| 활성 채널 ≤1 비율 | 100% (정상) | **58.4% 오분류** | **87.7% 오분류** |

CH6/CH7조차 0.02를 자주 못 넘어서(pronation 때 CH6가 0.02 넘는 비율 38.7%뿐)
"활성 채널 2개(CH6+CH7)" 조건을 거의 못 채웠던 것 — `MAX_QUIET_ACTIVE_CHANNELS`를
0으로 낮추는 것만으론 해결 안 됐을 문제(Pronation은 활성==0도 58.4%).

**`ACTIVE_CHANNEL_THRESHOLD`를 0.02→0.01로 낮춰서 재시뮬레이션**:
| | Rest | Supination | Pronation |
|---|---|---|---|
| 활성 채널 ≤1 비율 | 93.8% (약간 감소, hysteresis로 보완 가능) | **0.4%** | **5.9%** |

**적용 완료**: `ClassifyViewModel.ACTIVE_CHANNEL_THRESHOLD`, `ClassifyScreen
.LOW_SIGNAL_THRESHOLD` 둘 다 0.02 → 0.01로 변경. 분석 스크립트:
`C:\tmp_adb_pull\simulate_rest_threshold.py`, `simulate_per_channel.py`
(프로젝트 밖 임시 위치, 영구 보존 아님).

## 다음 단계

1. ~~문제 1, 2 각각 threshold 값/방식에 대한 의사결정~~ 완료
2. ~~`ClassifyViewModel`에 프로토타입 구현~~ 완료
3. ~~문제 2 재설계 (raw 기반 channelHasSignal)~~ 완료
4. ~~중심점 가림 버그 수정~~ 완료
5. **rest 판정 조건 재설계** — raw 데이터(rest vs sup/pro 시 실제 채널값)를 실측
   로그로 같이 보고, "활성 채널 개수" 이진 판단 대신 더 세밀한 기준(예: 채널별
   절대값 크기, 지속 시간 등) 검토
6. 필요하면 펌웨어 이관 여부 결정

---

## 온셋/오프셋 큐 라벨링 시도 → 되돌림 (2026-07-20)

동적 모션(반복 동작) 녹화 시 take 전체가 단일 라벨로 오염되는 문제(정량 비교:
정적 90.4% vs 반복 동작 66.4%, 위쪽 세션 로그 참고)를 풀기 위해 "지금 동작하세요"
큐 기반 라벨링을 구현했었음(`ee9ed46`, `e6c787e`, `e2390e6` 3개 커밋).

**구현했던 것**: take 길이(10초) 유지, 그 안에서 5회 반복 신호(원 3개 채워지는
카운트다운 + 삐 소리), 활성 구간만 해당 동작 라벨/나머지 Rest로 윈도우 단위 기록.
가는 길에 소리 안 남(OEM 톤/스트림 문제), 박자 문제, "첫 반복을 Rest로 잘못
라벨링"(오히려 성능 저하 유발) 등 여러 버그를 순차적으로 발견/수정함.

**결과**: 버그를 다 고친 뒤 실기기로 재녹화→재학습→재전송까지 다시 해서 테스트했지만
**여전히 정적 유지 방식보다 인식 성능이 떨어짐**. 사용자 판단으로 `git revert
ae65d2a af543fe 4e2f1bb`(3개 커밋 모두)로 **정적 유지(take 전체 단일 라벨) 방식으로
완전히 되돌림**. `CollectViewModel.kt`, `CollectScreen.kt`, `MandroDatabase.kt`
(version 3→2), `RecordingTakeEntity.kt`, `EmgSerialization.kt` 전부 이 3개 커밋
이전 상태로 복귀.

**Why 되돌렸는지**: 이 세션에서 유일하게 확보한 정량적 근거(정적 90.4% vs 반복
66.4%)가 계속 정적 우세를 가리켰고, 큐 기반 라벨링을 버그 없이 구현한 뒤에도
실측 결과가 개선되지 않음. 온셋/오프셋 자동 검출이라는 아이디어 자체보다,
실제로 이 하드웨어/모델 조합에서는 "동작을 정확한 타이밍에 맞춰 짧게 수행"하는
것 자체가 "자세를 몇 초간 유지"하는 것보다 신호 품질이 안정적이지 않을 가능성
(반응 지연, 개인별 타이밍 편차 등)이 있어 보임.

**How to apply**: 앞으로 이 세션에서 다시 "동적 모션 인식"을 시도한다면, 이번
시행착오(커밋 `ee9ed46`~`e2390e6`, revert `ae65d2a`~`4e2f1bb`)를 먼저 확인할 것 —
큐 기반 라벨링을 다시 만드는 건 이미 한 번 시도했다가 실측으로 기각된 접근임.
재시도한다면 이전과 다른 근본적인 차이(예: 큐 리듬을 사용자가 충분히 연습한 뒤
녹화, 활성 윈도우 길이/반응지연 재조정, 또는 신호 기반 자동 온셋 검출로 완전히
다르게 접근)가 있어야 함. **당분간은 정적 유지(택 전체 단일 라벨) 방식이 확정**.
