package com.mandro.di

import android.content.Context
import androidx.room.Room
import com.mandro.data.local.db.MandroDatabase
import com.mandro.data.local.db.RecordingTakeDao
import com.mandro.data.local.db.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MandroDatabase =
        Room.databaseBuilder(context, MandroDatabase::class.java, "mandro.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideRecordingTakeDao(db: MandroDatabase): RecordingTakeDao =
        db.recordingTakeDao()

    @Provides
    fun provideUserDao(db: MandroDatabase): UserDao =
        db.userDao()
}
