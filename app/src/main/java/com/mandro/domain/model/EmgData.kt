package com.mandro.domain.model

const val EMG_CHANNELS = 8
const val WINDOW_SIZE = 128
const val SAMPLING_FREQ = 1200

// BLE로 수신한 원시 EMG 샘플 (1개 시점, 8채널)
data class EmgSample(
    val channels: FloatArray,           // 크기 8
    val timestampMs: Long = System.currentTimeMillis(),
) {
    init {
        require(channels.size == EMG_CHANNELS) {
            "EMG 채널 수는 $EMG_CHANNELS 이어야 합니다"
        }
    }
}

// 윈도우 단위 EMG 데이터 (128 샘플 × 8채널)
data class EmgWindow(
    val data: Array<FloatArray>,        // [WINDOW_SIZE][EMG_CHANNELS]
    val label: String? = null,          // 녹화 시 레이블 (ex: "Flexion")
)

// 동작 녹화 세션 (take 1개)
data class RecordingTake(
    val gesture: String,
    val takeIndex: Int,
    val windows: List<EmgWindow>,
    val recordedAt: Long = System.currentTimeMillis(),
)

// 전체 녹화 배치 (동작별 10 takes)
data class RecordingBatch(
    val userId: String,
    val gestureSet: GestureSet,
    val takes: Map<String, List<RecordingTake>>,  // gesture → takes
) {
    fun isComplete(): Boolean =
        gestureSet.classes.all { gesture ->
            (takes[gesture]?.size ?: 0) >= REQUIRED_TAKES_PER_GESTURE
        }

    fun completedCount(): Int =
        takes.values.count { it.size >= REQUIRED_TAKES_PER_GESTURE }

    companion object {
        const val REQUIRED_TAKES_PER_GESTURE = 10
    }
}

// 실시간 분류 결과
data class ClassificationResult(
    val gesture: String,
    val confidence: Float,              // 0.0 ~ 1.0
    val probabilities: Map<String, Float>,  // 각 클래스별 확률
    val channelSignals: FloatArray,     // 현재 8채널 신호 (방사형 시각화용)
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * BLE Characteristic ...57 에서 수신한 추론 결과.
 * 포맷: "classname|l0|l1|l2|l3|l4|l5"
 */
data class InferenceResult(
    val className: String,
    val probabilities: FloatArray,      // size = 6, softmax 확률
) {
    val confidence: Float get() = probabilities.maxOrNull() ?: 0f

    companion object {
        val GESTURE_NAMES = listOf("rest", "flexion", "extension", "close", "supination", "pronation")

        fun parse(bytes: ByteArray): InferenceResult? = runCatching {
            val parts = String(bytes, Charsets.UTF_8).split("|")
            if (parts.size != 7) return null
            InferenceResult(
                className = parts[0],
                probabilities = FloatArray(6) { i -> parts[i + 1].toFloat() },
            )
        }.getOrNull()
    }
}
