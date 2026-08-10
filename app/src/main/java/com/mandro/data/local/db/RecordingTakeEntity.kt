package com.mandro.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.model.EmgWindow
import com.mandro.domain.model.RecordingTake
import com.mandro.domain.model.WINDOW_SIZE

/**
 * BLE로 수집된 EMG 녹화 데이터 1 take.
 *
 * samplesBlob: uint8 flat array, shape (sampleCount × EMG_CHANNELS)
 *   - FloatArray 값이 0~255 범위이므로 byte로 손실 없이 저장 가능
 *   - 100 windows × 128 samples × 8ch = 102,400 bytes ≈ 100 KB per take
 */
@Entity(tableName = "recording_takes")
data class RecordingTakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val gesture: String,
    val takeIndex: Int,
    val recordedAt: Long,
    val sampleCount: Int,               // 총 샘플 수 (= windows * WINDOW_SIZE)
    val samplesBlob: ByteArray,         // sampleCount * EMG_CHANNELS bytes
) {
    fun toDomain(): RecordingTake {
        val windows = mutableListOf<EmgWindow>()
        var offset = 0
        while (offset + WINDOW_SIZE * EMG_CHANNELS <= samplesBlob.size) {
            val data = Array(WINDOW_SIZE) { row ->
                FloatArray(EMG_CHANNELS) { ch ->
                    (samplesBlob[offset + row * EMG_CHANNELS + ch].toInt() and 0xFF).toFloat()
                }
            }
            windows.add(EmgWindow(data = data))
            offset += WINDOW_SIZE * EMG_CHANNELS
        }
        return RecordingTake(
            gesture = gesture,
            takeIndex = takeIndex,
            windows = windows,
            recordedAt = recordedAt,
        )
    }

    companion object {
        fun fromDomain(userId: String, take: RecordingTake): RecordingTakeEntity {
            val allSamples = take.windows.flatMap { window -> window.data.toList() }
            val blob = ByteArray(allSamples.size * EMG_CHANNELS) { i ->
                val sample = allSamples[i / EMG_CHANNELS]
                val ch     = i % EMG_CHANNELS
                sample[ch].toInt().coerceIn(0, 255).toByte()
            }
            return RecordingTakeEntity(
                userId      = userId,
                gesture     = take.gesture,
                takeIndex   = take.takeIndex,
                recordedAt  = take.recordedAt,
                sampleCount = allSamples.size,
                samplesBlob = blob,
            )
        }
    }
}
