package com.mandro.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.rawStreamDataStore by preferencesDataStore(name = "raw_stream_prefs")

/**
 * raw EMG BLE 스트리밍(Characteristic ...56) On/Off 사용자 설정 — RAW_STREAM_TOGGLE.md 참고.
 * 기본값 Off(전력 절약). Waveform/Collect처럼 raw가 반드시 필요한 화면은 진입 시
 * 이 값을 true로 바꿔두고, 다시 끄고 싶으면 Settings에서 직접 끔(자동으로 안 꺼짐).
 * 실제 BLE 구독 반영은 BleRepositoryImpl이 이 값을 관찰해서 처리함.
 */
@Singleton
class RawStreamPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY_ENABLED = booleanPreferencesKey("raw_stream_enabled")

    val enabled: Flow<Boolean> = context.rawStreamDataStore.data.map { it[KEY_ENABLED] ?: false }

    suspend fun setEnabled(value: Boolean) {
        context.rawStreamDataStore.edit { it[KEY_ENABLED] = value }
    }
}
