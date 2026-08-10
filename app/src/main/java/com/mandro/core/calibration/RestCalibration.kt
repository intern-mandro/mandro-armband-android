package com.mandro.core.calibration

import com.mandro.domain.model.EMG_CHANNELS
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RestCalibration @Inject constructor() {

    @Volatile var baseline: FloatArray = FloatArray(EMG_CHANNELS)
        private set

    @Volatile var isCalibrated: Boolean = false
        private set

    fun setBaseline(samples: List<FloatArray>) {
        if (samples.isEmpty()) return
        val mean = FloatArray(EMG_CHANNELS) { ch ->
            samples.map { it[ch] }.average().toFloat()
        }
        baseline = mean
        isCalibrated = true
    }

    // 기준선에서 MARGIN만큼 덜 빼서 쉬는 상태에도 약간의 신호가 남도록 함
    // 너무 빡빡하게 빼면 rest≈0이 되어 "신호 없음" 판정 발생
    private val MARGIN = 20f

    fun apply(channels: FloatArray): FloatArray {
        if (!isCalibrated) return channels
        // 기준선 아래로 내려가는 값도 그대로 음수로 남겨서, 그래프가 중심선 기준
        // 양방향으로 그려지도록 함 (0에서 클램프하면 한쪽으로만 튀는 모양이 됨)
        return FloatArray(EMG_CHANNELS) { ch ->
            channels[ch] - (baseline[ch] - MARGIN).coerceAtLeast(0f)
        }
    }

    fun reset() {
        baseline = FloatArray(EMG_CHANNELS)
        isCalibrated = false
    }
}
