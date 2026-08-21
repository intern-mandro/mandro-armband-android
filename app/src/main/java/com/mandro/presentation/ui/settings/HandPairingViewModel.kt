package com.mandro.presentation.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.domain.model.HandPairingState
import com.mandro.domain.repository.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HandPairingViewModel @Inject constructor(
    private val bleRepository: BleRepository,
) : ViewModel() {

    val pairingState: StateFlow<HandPairingState> = bleRepository.handPairingState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HandPairingState.Idle)

    private val _storedMac = MutableStateFlow<String?>(null)
    val storedMac: StateFlow<String?> = _storedMac.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        checkStoredMac()
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

    fun onPairClick() {
        viewModelScope.launch {
            _errorMessage.value = null
            bleRepository.pairHand()
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
}
