package com.mandro.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [RecordingTakeEntity::class, UserEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MandroDatabase : RoomDatabase() {
    abstract fun recordingTakeDao(): RecordingTakeDao
    abstract fun userDao(): UserDao
}
