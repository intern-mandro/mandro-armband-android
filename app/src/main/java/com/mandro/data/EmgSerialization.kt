package com.mandro.data

import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.model.EmgWindow
import com.mandro.domain.model.RecordingBatch
import com.mandro.domain.model.WINDOW_SIZE
import java.nio.ByteBuffer
import java.nio.ByteOrder

// 서버 spec 기준 동작 인덱스 (로컬 Chaquopy 학습에서도 동일하게 사용)
val GESTURE_INDEX = mapOf(
    "Rest"       to 0,
    "Flexion"    to 1,
    "Extension"  to 2,
    "Close"      to 3,
    "Supination" to 4,
    "Pronation"  to 5,
)

/**
 * RecordingBatch → (emg_data bytes, labels bytes)
 * emg_data: uint8, shape (N, 8) row-major
 * labels:   int32 LE, shape (N,)
 *
 * 서버 업로드(EmgRepositoryImpl)와 로컬 Chaquopy 학습(LocalTrainingRepositoryImpl)이
 * 동일한 바이너리 포맷을 요구하므로 공용 함수로 둔다.
 */
fun serializeBatch(batch: RecordingBatch): Pair<ByteArray, ByteArray> {
    val allWindows = mutableListOf<Pair<EmgWindow, Int>>() // (window, gestureIdx)
    for ((gesture, takes) in batch.takes) {
        val gestureIdx = GESTURE_INDEX[gesture] ?: continue
        for (take in takes) {
            for (window in take.windows) {
                allWindows.add(window to gestureIdx)
            }
        }
    }

    val totalSamples = allWindows.size * WINDOW_SIZE
    val emgBuf = ByteBuffer.allocate(totalSamples * EMG_CHANNELS)
    val labelBuf = ByteBuffer.allocate(totalSamples * Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)

    for ((window, gestureIdx) in allWindows) {
        for (sample in window.data) {          // WINDOW_SIZE 행
            for (ch in 0 until EMG_CHANNELS) {
                emgBuf.put(sample[ch].toInt().coerceIn(0, 255).toByte())
            }
            labelBuf.putInt(gestureIdx)
        }
    }

    return emgBuf.array() to labelBuf.array()
}
