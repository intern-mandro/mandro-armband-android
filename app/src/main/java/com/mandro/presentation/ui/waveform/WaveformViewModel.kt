package com.mandro.presentation.ui.waveform

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.core.calibration.RestCalibration
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.repository.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "WaveformViewModel"
private const val CALIBRATION_SAMPLES = 3840  // ~3초분 샘플

// 3초 * ~1281Hz ≈ 3840샘플
const val DISPLAY_SAMPLES = 3840

sealed class CalibrationState {
    object Idle : CalibrationState()           // 연결됨, 사용자 준비 대기
    object Calibrating : CalibrationState()   // 기준선 수집 중
    object ReadyToConfirm : CalibrationState() // 수집 완료, 사용자 확인 대기
    object Done : CalibrationState()          // 확인 후 파형 표시
}

data class WaveformUiState(
    val bleState: BleState = BleState.Disconnected,
    val visibleChannels: BooleanArray = BooleanArray(EMG_CHANNELS) { true },
    val calibration: CalibrationState = CalibrationState.Idle,
    val calibrationProgress: Float = 0f,  // 0~1
)

@HiltViewModel
class WaveformViewModel @Inject constructor(
    private val bleRepository: BleRepository,
    private val restCalibration: RestCalibration,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WaveformUiState())
    val uiState = _uiState.asStateFlow()

    private val _navigateToBleScan = Channel<Unit>(Channel.BUFFERED)
    val navigateToBleScan = _navigateToBleScan.receiveAsFlow()

    private var sampleCount = 0
    private val calibrationBuf = mutableListOf<FloatArray>()

    // 채널별 링버퍼 — 리컴포지션 없이 Canvas가 직접 읽음
    val buffers: Array<FloatArray> = Array(EMG_CHANNELS) { FloatArray(DISPLAY_SAMPLES) }
    // 모든 채널이 동시에 같은 위치에 쓰므로 단일 포인터로 관리
    @Volatile var writePtr: Int = 0
        private set

    // 이동평균용 최근 N샘플 누적 버퍼 (채널별)
    private val smoothWindow = 5
    private val smoothBuf = Array(EMG_CHANNELS) { FloatArray(smoothWindow) }
    private val smoothSum = FloatArray(EMG_CHANNELS)
    private var smoothIdx = 0

    init {
        // Collect(녹화)와 달리 이 화면은 raw를 강제로 켜지 않음 — Settings의
        // "원본 신호 수신" 설정이 Off면 이 화면도 그대로 Off로 둔다(파형/캐리브레이션
        // 모두 안 됨). 사용자가 Settings에서 직접 켜야 함(RAW_STREAM_TOGGLE.md 참고).
        observeBleState()
        collectEmgStream()
    }

    private fun observeBleState() {
        viewModelScope.launch {
            bleRepository.bleState.collect { state ->
                Log.d(TAG, "BLE 상태 변경: $state")
                _uiState.value = _uiState.value.copy(bleState = state)
                when (state) {
                    is BleState.Connected -> {
                        // 세션(앱 프로세스) 내에서는 한 번 캘리브레이션한 기준선을
                        // 재연결돼도 유지함 — 예전엔 연결될 때마다 무조건 리셋해서
                        // 매번 다시 캘리브레이션해야 했음. 재측정은 사용자가
                        // startCalibration()을 직접 눌렀을 때만.
                        calibrationBuf.clear()
                        _uiState.value = _uiState.value.copy(
                            calibration = if (restCalibration.isCalibrated) {
                                CalibrationState.Done
                            } else {
                                CalibrationState.Idle
                            },
                            calibrationProgress = if (restCalibration.isCalibrated) 1f else 0f,
                        )
                    }
                    is BleState.Disconnected -> _navigateToBleScan.send(Unit)
                    else -> {}
                }
            }
        }
    }

    private fun collectEmgStream() {
        viewModelScope.launch {
            bleRepository.emgStream.collect { sample ->
                sampleCount++
                if (sampleCount == 1) Log.i(TAG, "EMG 스트림 첫 샘플 수신 — CH0=${sample.channels[0]}")

                if (!restCalibration.isCalibrated && _uiState.value.calibration is CalibrationState.Calibrating) {
                    // 캘리브레이션 중: raw 샘플 수집
                    calibrationBuf.add(sample.channels.copyOf())
                    val progress = calibrationBuf.size.toFloat() / CALIBRATION_SAMPLES
                    _uiState.value = _uiState.value.copy(calibrationProgress = progress.coerceAtMost(1f))

                    if (calibrationBuf.size >= CALIBRATION_SAMPLES) {
                        restCalibration.setBaseline(calibrationBuf)
                        calibrationBuf.clear()
                        _uiState.value = _uiState.value.copy(calibration = CalibrationState.ReadyToConfirm)
                        Log.i(TAG, "캘리브레이션 완료 — 기준선: ${restCalibration.baseline.toList()}")
                    }
                }

                // 캘리브레이션 완료 후에만 파형 버퍼에 기록
                if (restCalibration.isCalibrated) {
                    pushSample(restCalibration.apply(sample.channels))
                }
            }
        }
    }

    fun pushSample(channels: FloatArray) {
        val bufIdx = writePtr
        for (ch in 0 until EMG_CHANNELS) {
            // 이동평균: 오래된 값 빼고 새 값 더함
            smoothSum[ch] -= smoothBuf[ch][smoothIdx]
            smoothBuf[ch][smoothIdx] = channels[ch]
            smoothSum[ch] += channels[ch]
            buffers[ch][bufIdx] = smoothSum[ch] / smoothWindow
        }
        smoothIdx = (smoothIdx + 1) % smoothWindow
        writePtr = (bufIdx + 1) % DISPLAY_SAMPLES
    }

    fun startCalibration() {
        // 재측정 시에도 이전 기준선(isCalibrated=true)을 지워야 collectEmgStream이
        // 다시 샘플을 받아들인다 — 안 지우면 재측정이 영원히 progress=0에서 멈춘다.
        restCalibration.reset()
        calibrationBuf.clear()
        _uiState.value = _uiState.value.copy(
            calibration = CalibrationState.Calibrating,
            calibrationProgress = 0f,
        )
    }

    fun confirmCalibration() {
        _uiState.value = _uiState.value.copy(calibration = CalibrationState.Done)
    }

    fun toggleChannel(ch: Int) {
        val current = _uiState.value.visibleChannels.copyOf()
        current[ch] = !current[ch]
        _uiState.value = _uiState.value.copy(visibleChannels = current)
    }
}
