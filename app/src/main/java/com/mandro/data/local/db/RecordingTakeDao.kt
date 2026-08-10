package com.mandro.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RecordingTakeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(take: RecordingTakeEntity): Long

    @Query("SELECT * FROM recording_takes WHERE userId = :userId ORDER BY recordedAt ASC")
    suspend fun getByUserId(userId: String): List<RecordingTakeEntity>

    @Query("SELECT COUNT(*) FROM recording_takes WHERE userId = :userId")
    suspend fun countByUserId(userId: String): Int

    @Query("DELETE FROM recording_takes WHERE userId = :userId")
    suspend fun deleteByUserId(userId: String)

    @Query("DELETE FROM recording_takes WHERE userId = :userId AND takeIndex = :takeIndex")
    suspend fun deleteByUserIdAndTakeIndex(userId: String, takeIndex: Int)
}
