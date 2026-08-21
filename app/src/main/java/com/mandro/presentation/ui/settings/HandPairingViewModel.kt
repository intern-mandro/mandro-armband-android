package com.mandro.presentation.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.HandDevicePreferences
import com.mandro.domain.model.BleState
import com.mandro.domain.model.DEFAULT_HAND_NAME_PREFIX
import com.mandro.domain.model.HandPairingState
import com.mandro.domain.repository.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HandPairingViewModel @Inject constructor(
    private val bleRepository: BleRepository,
    private val handDevicePreferences: HandDevicePreferences,
) : ViewModel() {

    val armbandState: StateFlow<BleState> = bleRepository.bleState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BleState.Disconnected)

    // 스캔·연결이 몇 초씩 걸리는 동안 "화면이 멈춘 것처럼 보인다"는 피드백 때문에,
    // 단계가 바뀔 때마다 메시지가 갱신되는 이 상태를 화면에서 작은 텍스트로 보여줌.
    val pairingState: StateFlow<HandPairingState> = bleRepository.handPairingState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HandPairingState.Idle)

    private val _storedMac = MutableStateFlow<String?>(null)
    val storedMac: StateFlow<String?> = _storedMac.asStateFlow()

    // "등록된 로봇의수" 카드에 보여줄 표시용 문자열 — 암밴드 NVS엔 MAC만 저장돼 있어서
    // (이름 정보 없음), HandDevicePreferences에 기억해둔 마지막 페어링 이름과 MAC이
    // 일치할 때만 "이름(MAC)"으로, 아니면(이름을 모르면) MAC만 보여줌.
    val storedMacDisplay: StateFlow<String?> =
        combine(_storedMac, handDevicePreferences.lastKnown) { mac, lastKnown ->
            when {
                mac == null -> null
                lastKnown != null && lastKnown.first.equals(mac, ignoreCase = true) ->
                    "${lastKnown.second}($mac)"
                else -> mac
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        // 암밴드에 연결돼 있을 때만 등록 상태를 조회 — 연결 안 된 상태에서는 상태
        // 카드가 이미 "연결 안 됨"을 보여주므로 별도 에러 문구를 띄우지 않음.
        viewModelScope.launch {
            armbandState.collect { state ->
                if (state is BleState.Connected) checkStoredMac() else _storedMac.value = null
            }
        }
        // 페어링 성공 시 이름을 로컬에 기억해둬서, 다음에 저장된 MAC만 다시 읽어도
        // 이름을 같이 보여줄 수 있게 함.
        viewModelScope.launch {
            pairingState.collect { state ->
                if (state is HandPairingState.Success) {
                    handDevicePreferences.remember(state.handMac, state.handName)
                }
            }
        }
    }

    fun checkStoredMac() {
        viewModelScope.launch {
            _checking.value = true
            bleRepository.checkPairedHandMac()
                .onSuccess { _storedMac.value = it }
                .onFailure { _errorMessage.value = it.message }
            _checking.value = false
        }
    }

    fun onPairClick(handNamePrefix: String) {
        viewModelScope.launch {
            _errorMessage.value = null
            val prefix = handNamePrefix.trim().ifEmpty { DEFAULT_HAND_NAME_PREFIX }
            bleRepository.pairHand(prefix)
                .onSuccess { _storedMac.value = it }
                .onFailure { _errorMessage.value = it.message }
        }
    }

    fun onClearClick() {
        viewModelScope.launch {
            _errorMessage.value = null
            bleRepository.clearPairedHand()
                .onSuccess { _storedMac.value = null }
                .onFailure { _errorMessage.value = it.message }
        }
    }

    fun onDisconnectArmband() {
        viewModelScope.launch { bleRepository.disconnect() }
    }
}
