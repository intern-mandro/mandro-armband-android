package com.mandro.data.local

import android.util.Log
import com.chaquo.python.Python
import com.mandro.data.serializeBatch
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.model.RecordingBatch
import com.mandro.domain.repository.LocalTrainingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalTrainingRepositoryImpl"
private const val WEIGHT_TOTAL_BYTES = 53_304 // NN 가중치(52,248) + StandardScaler mean/std(1,056)

@Singleton
class LocalTrainingRepositoryImpl @Inject constructor() : LocalTrainingRepository {

    override suspend fun trainLocally(batch: RecordingBatch): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val (emgBytes, labelBytes) = serializeBatch(batch)
                Log.i(TAG, "로컬 학습 시작: samples=${emgBytes.size / EMG_CHANNELS}")

                val weights = Python.getInstance()
                    .getModule("training_local")
                    .callAttr("run_training_local", emgBytes, labelBytes)
                    .toJava(ByteArray::class.java)

                // training_local.py는 실패 시 빈 바이트를 반환함 (예외는 파이썬 쪽에서 삼킴)
                check(weights.size == WEIGHT_TOTAL_BYTES) {
                    "가중치 크기 이상: ${weights.size} bytes (expected $WEIGHT_TOTAL_BYTES)"
                }

                Log.i(TAG, "로컬 학습 완료: ${weights.size} bytes")
                weights
            }.onFailure {
                Log.e(TAG, "로컬 학습 실패", it)
            }
        }
}
