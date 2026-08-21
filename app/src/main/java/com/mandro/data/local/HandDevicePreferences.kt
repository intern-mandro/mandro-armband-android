package com.mandro.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.handDeviceDataStore by preferencesDataStore(name = "hand_device_prefs")

/**
 * 마지막으로 페어링에 성공한 로봇의수의 MAC↔이름을 로컬에 기억해둠. 암밴드의 NVS
 * 페어링 저장소는 MAC 6바이트만 들고 있어서(PairCharCallbacks, exo_armband_hybrid.ino),
 * 화면을 다시 열어 저장된 MAC을 조회할 때는 이름을 알 방법이 없음 — 페어링 직후
 * 스캔으로 얻은 이름을 여기 남겨뒀다가, MAC이 일치할 때만 같이 보여주는 용도.
 * NVS와 마찬가지로 한 번에 하나만 기억함(기록/목록 아님).
 */
@Singleton
class HandDevicePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY_MAC = stringPreferencesKey("last_hand_mac")
    private val KEY_NAME = stringPreferencesKey("last_hand_name")

    val lastKnown: Flow<Pair<String, String>?> = context.handDeviceDataStore.data.map { prefs ->
        val mac = prefs[KEY_MAC]
        val name = prefs[KEY_NAME]
        if (mac != null && name != null) mac to name else null
    }

    suspend fun remember(mac: String, name: String) {
        context.handDeviceDataStore.edit {
            it[KEY_MAC] = mac
            it[KEY_NAME] = name
        }
    }
}
