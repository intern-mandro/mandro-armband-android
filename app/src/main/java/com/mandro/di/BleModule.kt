package com.mandro.di

import com.mandro.BuildConfig
import com.mandro.core.ble.BleManager
import com.mandro.data.ble.BleRepositoryImpl
import com.mandro.data.ble.MockBleRepository
import com.mandro.data.local.RawStreamPreferences
import com.mandro.domain.repository.BleRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BleModule {

    @Provides
    @Singleton
    fun provideBleRepository(
        bleManager: BleManager,
        mockBleRepository: MockBleRepository,
        rawStreamPreferences: RawStreamPreferences,
    ): BleRepository {
        return if (BuildConfig.USE_MOCK_BLE) mockBleRepository
               else BleRepositoryImpl(bleManager, rawStreamPreferences)
    }
}
