package com.mandro.presentation.ui.classify

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.core.calibration.RestCalibration
import com.mandro.data.local.RawStreamPreferences
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.model.InferenceResult
import com.mandro.domain.repository.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ClassifyUiState(
    val gesture: String = "—",
    val gestureKo: String = "암밴드 연결을 기다리는 중...",
    val bleState: BleState = BleState.Disconnected,
    // 이 화면에서 한 번이라도 Connected를 본 적 있는지 — 화면 진입 직후의
    // "아직 연결 확인 전" 상태와 "연결됐다가 끊긴" 상태를 구분하기 위함.
    // 가중치 전송 성공 후 암밴드가 재부팅하면서 연결이 끊기는 경우를 감지해서
    // BleScan 화면으로 돌려보내는 데 씀 (ClassifyScreen의 onDisconnected).
    val hasConnectedOnce: Boolean = false,
    val probabilities: Map<String, Float> = emptyMap(),
    val error: String? = null,
    // raw EMG 스트리밍이 꺼져있으면 레이더 차트(채널 세기/살아있음 표시)를 그릴 데이터
    // 자체가 안 들어오므로 화면에서 그냥 숨김 (RAW_STREAM_TOGGLE.md 참고). 이 화면은
    // Collect(녹화 필수)와 달리 raw를 강제로 켜지 않고, 지금 설정값을 그대로 따름 —
    // 추론 결과 텍스트는 raw 없이도 정상 동작하므로 인식 자체는 계속됨.
    val rawStreamEnabled: Boolean = false,
)

@HiltViewModel
class ClassifyViewModel @Inject constructor(
    private val bleRepository: BleRepository,
    private val restCalibration: RestCalibration,
    private val rawStreamPreferences: RawStreamPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClassifyUiState())
    val uiState = _uiState.asStateFlow()

    // 레이더 차트용 — "세기"(느리게 스무딩된 값, 선 길이를 결정)와
    // "떨림"(세기 대비 순간 편차 히스토리, 선의 흔들림/파형을 결정)을 분리.
    // 하나의 값으로 합쳐서 쓰면 노이즈 때문에 길이가 계속 요동쳐서 세기를
    // 읽기 어려웠음 — Canvas가 둘 다 직접 읽음.
    val channelIntensity = FloatArray(EMG_CHANNELS)
    val channelJitter = Array(EMG_CHANNELS) { FloatArray(JITTER_HISTORY_SIZE) }
    private var jitterWriteIdx = 0

    // "센서가 실제로 데이터를 보내고 있는가"를 channelIntensity(baseline 대비 편차,
    // 캘리브레이션되면 rest에서 의도적으로 0에 가까워짐)와 분리해서 판단하기 위한
    // raw(보정 전) 값 기록. WaveformScreen.kt의 hasSignal(p2~p98 스프레드) 판단
    // 방식과 동일한 아이디어 — baseline 보정과 무관하게 "raw 값 자체가 흔들리고
    // 있는가"만 봄. 캘리브레이션 여부와 상관없이 항상 유효함.
    private val rawHistory = Array(EMG_CHANNELS) { FloatArray(RAW_HISTORY_SIZE) }
    private var rawHistoryWriteIdx = 0
    val channelHasSignal = BooleanArray(EMG_CHANNELS)

    // rest 강제 판정 (RECOGNITION_IMPROVEMENT.md 참고) — 활성 채널(세기가
    // ACTIVE_CHANNEL_THRESHOLD 이상)이 하나도 없는 상태가 REST_HYSTERESIS_MS 이상
    // 지속되면 NN 예측과 무관하게 rest로 덮어씀. 채널 하나라도 뛰면 rest 아님
    // (2026-07-21) — 이전엔 MAX_QUIET_ACTIVE_CHANNELS=1로 단일 채널 노이즈 튐을
    // 봐줬는데, "값 하나라도 뛰면 rest로 바꾸지 말 것"이라는 명시적 요청으로 0으로
    // 바꿈. 트레이드오프: 순수 센서 노이즈만으로 한 채널이 threshold를 넘어도
    // rest가 깨질 수 있음(감수하기로 함) — 실기기에서 노이즈로 인한 rest 깜빡임이
    // 심하면 ACTIVE_CHANNEL_THRESHOLD를 올리는 쪽으로 재검토할 것.
    private var quietSinceElapsedMs: Long? = null

    // rest 판정 threshold 튜닝용 raw 데이터 로그 (RECOGNITION_IMPROVEMENT.md 3차 참고)
    // — adb logcat -s ClassifyRaw 로 필터링해서 rest/sup/pro 시 실제 값 비교
    private var lastLogElapsedMs = 0L

    init {
        // 이 화면은 Waveform/Collect와 달리 raw를 강제로 켜지 않음 — 레이더 차트는
        // 있으면 좋지만 필수는 아니고(추론 결과 텍스트는 raw 없이도 동작), 사용자가
        // 전력 절약을 위해 꺼둔 걸 이 화면 진입만으로 다시 켜버리면 설정이 무의미해짐.
        observeRawStreamPreference()
        observeBleState()
        observeInference()
        observeEmg()
    }

    private fun observeRawStreamPreference() {
        viewModelScope.launch {
            rawStreamPreferences.enabled.collect { enabled ->
                _uiState.update { it.copy(rawStreamEnabled = enabled) }
            }
        }
    }

    private fun observeBleState() {
        viewModelScope.launch {
            bleRepository.bleState.collect { state ->
                _uiState.update {
                    it.copy(
                        bleState = state,
                        hasConnectedOnce = it.hasConnectedOnce || state is BleState.Connected,
                        gestureKo = when {
                            state is BleState.Connected -> "동작을 인식할 준비가 됐어요"
                            state is BleState.Disconnected && it.hasConnectedOnce ->
                                "암밴드 연결이 끊겼어요. 다시 연결해 주세요."
                            else -> it.gestureKo
                        },
                    )
                }
            }
        }
    }

    /** 암밴드 BLE Characteristic ...57 에서 추론 결과 수신 */
    private fun observeInference() {
        viewModelScope.launch {
            bleRepository.inferenceStream.collect { result ->
                // "채널 하나라도 강하게 활성이면 rest일 수 없다" — ACTIVE_CHANNEL_THRESHOLD
                // (노이즈와 섞이는 낮은 값)보다 훨씬 높은 STRONG_SIGNAL_THRESHOLD를 써서,
                // 노이즈가 아니라 실제 근수축으로 볼 수 있는 경우만 잡음. 아래 forceRest
                // (quiet-hysteresis 기반)보다 반드시 먼저 체크해야 함 — 안 그러면 "활성
                // 채널이 정확히 1개, 근데 그게 강하게 활성"인 경우 두 규칙이 동시에
                // 조건을 만족해서 forceRest가 이겨버리는 모순이 생김.
                val hasStrongSignal = channelIntensity.any { it >= STRONG_SIGNAL_THRESHOLD }

                val forceRest = !hasStrongSignal && restCalibration.isCalibrated &&
                    quietSinceElapsedMs?.let {
                        SystemClock.elapsedRealtime() - it >= REST_HYSTERESIS_MS
                    } == true

                // 강한 신호가 있는데 NN이 그래도 rest라고 했으면, rest를 뺀 나머지
                // 중 확률이 가장 높은 클래스로 대체 — sup/pro끼리는 구분이 어려워도
                // rest와의 구분은 명확하다는 전제(사용자 관찰) 하에, "무엇인지는
                // 몰라도 rest는 아니다"를 확률 2등으로 근사함.
                val className = when {
                    forceRest -> "rest"
                    hasStrongSignal && result.className == "rest" -> bestNonRestClassName(result)
                    else -> result.className
                }

                _uiState.update { it.copy(
                    gesture    = className,
                    gestureKo  = gestureNameKo(className),
                    probabilities = InferenceResult.GESTURE_NAMES
                        .zip(result.probabilities.toList())
                        .toMap(),
                ) }
            }
        }
    }

    /** probabilities[0]=rest를 제외하고 확률이 가장 높은 클래스 이름 반환 */
    private fun bestNonRestClassName(result: InferenceResult): String {
        var bestIdx = 1
        for (i in 2 until result.probabilities.size) {
            if (result.probabilities[i] > result.probabilities[bestIdx]) bestIdx = i
        }
        return InferenceResult.GESTURE_NAMES[bestIdx]
    }

    /** raw EMG stream → 레이더 차트 채널 값(세기+떨림) 업데이트 */
    private fun observeEmg() {
        viewModelScope.launch {
            bleRepository.emgStream.collect { sample ->
                var activeChannels = 0
                for (ch in 0 until EMG_CHANNELS) {
                    val raw = sample.channels[ch]
                    // 0~1로 clamp하기 전의 편차 — 음수도 그대로 유지해야 떨림이
                    // 한쪽(양수) 방향으로만 쏠리지 않음 (clamp된 값으로 떨림을
                    // 계산하면 음수 쪽이 -channelIntensity에서 인위적으로 막혀버림).
                    val rawDeviation = if (restCalibration.isCalibrated) {
                        val baseline = restCalibration.baseline[ch]
                        (raw - baseline) / (255f - baseline).coerceAtLeast(1f)
                    } else {
                        raw / 255f
                    }
                    val normalized = rawDeviation.coerceIn(0f, 1f)
                    // 세기: 느리게 스무딩 — 이게 선 길이. 노이즈에 덜 흔들리게
                    // 기존(0.15)보다 훨씬 느린 비율을 씀. clamp된 값 기준.
                    channelIntensity[ch] += (normalized - channelIntensity[ch]) * INTENSITY_SMOOTHING
                    // 떨림: 세기(안정된 평균)에서 순간값이 얼마나 벗어났는지 —
                    // clamp 전 값 기준이라 평균보다 낮은 쪽도 정직하게 음수로 나옴.
                    channelJitter[ch][jitterWriteIdx % JITTER_HISTORY_SIZE] =
                        (rawDeviation - channelIntensity[ch]).coerceIn(-JITTER_CLAMP, JITTER_CLAMP)

                    if (channelIntensity[ch] >= ACTIVE_CHANNEL_THRESHOLD) activeChannels++

                    // raw(보정 전) 값 자체의 최근 스프레드로 "센서가 살아있는가" 판단
                    rawHistory[ch][rawHistoryWriteIdx % RAW_HISTORY_SIZE] = raw
                    var rawMin = Float.MAX_VALUE
                    var rawMax = -Float.MAX_VALUE
                    for (v in rawHistory[ch]) {
                        if (v < rawMin) rawMin = v
                        if (v > rawMax) rawMax = v
                    }
                    channelHasSignal[ch] = (rawMax - rawMin) > RAW_NO_SIGNAL_THRESHOLD
                }
                jitterWriteIdx++
                rawHistoryWriteIdx++

                // rest 강제 판정용 "조용한 상태" 지속 시간 추적
                val now = SystemClock.elapsedRealtime()
                if (activeChannels <= MAX_QUIET_ACTIVE_CHANNELS) {
                    if (quietSinceElapsedMs == null) {
                        quietSinceElapsedMs = now
                    }
                } else {
                    quietSinceElapsedMs = null
                }

                if (now - lastLogElapsedMs >= LOG_INTERVAL_MS) {
                    lastLogElapsedMs = now
                    Log.d(
                        "ClassifyRaw",
                        "raw=[${sample.channels.joinToString(",") { "%3.0f".format(it) }}] " +
                            "intensity=[${channelIntensity.joinToString(",") { "%.3f".format(it) }}] " +
                            "active=$activeChannels",
                    )
                }
            }
        }
    }

    companion object {
        private const val INTENSITY_SMOOTHING = 0.03f
        private const val JITTER_HISTORY_SIZE = 40
        private const val JITTER_CLAMP = 0.3f

        // rest 강제 판정 (RECOGNITION_IMPROVEMENT.md 3차) — ClassifyScreen.kt의
        // LOW_SIGNAL_THRESHOLD와 동일한 값을 씀. 0715static 실측 데이터 시뮬레이션
        // 결과 0.02는 supination/pronation의 핵심 채널(CH6,7)조차 자주 못 넘겨서
        // sup/pro가 rest로 오분류되는 비율이 58~88%에 달했음 — 0.01로 낮춰서
        // sup/pro 오분류 0.4~5.9%로 개선 (rest 정확도는 100%→93.8%, hysteresis로 보완).
        private const val ACTIVE_CHANNEL_THRESHOLD = 0.01f
        private const val MAX_QUIET_ACTIVE_CHANNELS = 0
        private const val REST_HYSTERESIS_MS = 100L

        // "이건 노이즈가 아니라 확실히 근수축이다"로 볼 수 있는 임계값. 채널 하나라도
        // ACTIVE_CHANNEL_THRESHOLD를 넘으면 이제 곧바로 forceRest 조건에서 빠지므로
        // (MAX_QUIET_ACTIVE_CHANNELS=0), 이 값은 "NN이 rest라고 오분류해도 강제로
        // 뒤집을지"를 결정하는 별도 기준으로만 쓰임(§observeInference). 값 자체는
        // sup/pro 튜닝 이전(threshold=0.02)에 "일반적인 신호 있음" 기준으로 실측 검증됐던
        // 값을 재사용한 초기값 — flexion/extension/close처럼 강한 제스처엔 충분히 낮지만,
        // sup/pro의 약한 CH6/7 신호까지 확실히 잡는지는 아직 실기기로 재검증 안 함
        // (RECOGNITION_IMPROVEMENT.md 참고, 필요하면 이전처럼 실측 데이터 시뮬레이션으로
        // 튜닝할 것).
        private const val STRONG_SIGNAL_THRESHOLD = 0.02f

        // "센서 살아있음" 판정 — WaveformScreen.kt의 NO_SIGNAL_THRESHOLD(raw 0~255
        // 기준 5)와 동일한 값. 64샘플(~50ms @1281Hz) 윈도우 안에서의 최대-최소 스프레드.
        private const val RAW_HISTORY_SIZE = 64
        private const val RAW_NO_SIGNAL_THRESHOLD = 5f

        private const val LOG_INTERVAL_MS = 150L
    }
}

private fun gestureNameKo(name: String) = when (name.lowercase()) {
    "rest"       -> "손을 자연스럽게 펴서 쉬기"
    "flexion"    -> "손목을 아래로 구부리기"
    "extension"  -> "손목을 위로 젖히기"
    "close"      -> "주먹 꽉 쥐기"
    "supination" -> "손바닥이 위를 향하게 돌리기"
    "pronation"  -> "손바닥이 아래를 향하게 돌리기"
    else         -> name
}
