package com.mandro.presentation.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.RawStreamPreferences
import com.mandro.domain.repository.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val bleRepository: BleRepository,
    private val rawStreamPreferences: RawStreamPreferences,
) : ViewModel() {

    // raw EMG 스트리밍(전력 절약) 설정 — RAW_STREAM_TOGGLE.md 참고. 기본 Off.
    val rawStreamEnabled = rawStreamPreferences.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun onRawStreamEnabledChanged(enabled: Boolean) {
        viewModelScope.launch { rawStreamPreferences.setEnabled(enabled) }
    }

    // 홈으로 나가면서 이번 유저의 BLE 연결을 끊어, 다음 유저가 스캔할 때
    // 이전 연결이 살아있는 채로 남는 걸 방지한다.
    fun onGoHome(onDone: () -> Unit) {
        viewModelScope.launch {
            bleRepository.disconnect()
            onDone()
        }
    }
}
