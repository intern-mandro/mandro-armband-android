package com.mandro.presentation.ui.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.domain.model.BleDevice
import com.mandro.domain.model.BleState
import com.mandro.domain.repository.BleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BleUiState(
    val bleState: BleState = BleState.Scanning,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class BleViewModel @Inject constructor(
    private val bleRepository: BleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState = _uiState.asStateFlow()

    init {
        observeBleState()
        viewModelScope.launch { bleRepository.startScan() }
    }

    private fun observeBleState() {
        viewModelScope.launch {
            bleRepository.bleState.collect { state ->
                _uiState.update { it.copy(bleState = state, error = null) }
            }
        }
    }

    fun onConnectClick(device: BleDevice) {
        viewModelScope.launch { bleRepository.connect(device) }
    }

    fun onRescan() {
        viewModelScope.launch { bleRepository.startScan() }
    }
}
