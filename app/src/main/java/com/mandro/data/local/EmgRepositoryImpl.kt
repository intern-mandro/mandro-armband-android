package com.mandro.data.local

import android.util.Log
import com.mandro.data.local.db.RecordingTakeDao
import com.mandro.data.local.db.RecordingTakeEntity
import com.mandro.domain.model.GestureSet
import com.mandro.domain.model.RecordingBatch
import com.mandro.domain.model.RecordingTake
import com.mandro.domain.repository.EmgRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EmgRepositoryImpl"

@Singleton
class EmgRepositoryImpl @Inject constructor(
    private val takeDao: RecordingTakeDao,
) : EmgRepository {

    override suspend fun saveTake(userId: String, take: RecordingTake) {
        takeDao.insert(RecordingTakeEntity.fromDomain(userId, take))
        Log.d(TAG, "take 저장: userId=$userId gesture=${take.gesture} windows=${take.windows.size}")
    }

    override suspend fun getBatch(userId: String): RecordingBatch? {
        val entities = takeDao.getByUserId(userId)
        if (entities.isEmpty()) return null
        val takes = entities.map { it.toDomain() }
        return RecordingBatch(
            userId = userId,
            gestureSet = GestureSet.SIX_CLASS,
            takes = takes.groupBy { it.gesture },
        )
    }

    override suspend fun clearBatch(userId: String) {
        takeDao.deleteByUserId(userId)
        Log.d(TAG, "배치 초기화: userId=$userId")
    }

    override suspend fun deleteTakesForLap(userId: String, takeIndex: Int) {
        takeDao.deleteByUserIdAndTakeIndex(userId, takeIndex)
        Log.d(TAG, "랩 재녹화 - 기존 take 삭제: userId=$userId takeIndex=$takeIndex")
    }
}
