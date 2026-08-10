package com.mandro.presentation.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.UserPreferences
import com.mandro.domain.model.BleState
import com.mandro.domain.model.User
import com.mandro.domain.repository.BleRepository
import com.mandro.domain.repository.EmgRepository
import com.mandro.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val users: List<User> = emptyList(),
    val bleState: BleState = BleState.Disconnected,
    val error: String? = null,
    val deleteTarget: User? = null,
    val activeUserId: String? = null,
) {
    // "암밴드 연결하기"를 누르면 실제로 어느 유저로 동작할지 — 유저 카드를 안 눌러도
    // 지난 세션에 저장된 activeUserId가 그대로 재사용되므로, 화면에 명시해줘야 함.
    val activeUser: User? get() = users.find { it.id == activeUserId }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val emgRepository: EmgRepository,
    private val userPreferences: UserPreferences,
    private val bleRepository: BleRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()

    private val _navigateToMain = Channel<Unit>(Channel.BUFFERED)
    val navigateToMain = _navigateToMain.receiveAsFlow()

    private val _navigateToBleScan = Channel<Unit>(Channel.BUFFERED)
    val navigateToBleScan = _navigateToBleScan.receiveAsFlow()

    private val _navigateToFirmware = Channel<Unit>(Channel.BUFFERED)
    val navigateToFirmware = _navigateToFirmware.receiveAsFlow()

    init {
        viewModelScope.launch { userRepository.cleanupOrphanedModels() }
        loadUsers()
        observeBleState()
        observeActiveUser()
    }

    private fun observeBleState() {
        viewModelScope.launch {
            bleRepository.bleState.collect { state ->
                _uiState.update { it.copy(bleState = state) }
            }
        }
    }

    private fun observeActiveUser() {
        viewModelScope.launch {
            userPreferences.userId.collect { id ->
                _uiState.update { it.copy(activeUserId = id) }
            }
        }
    }

    private fun loadUsers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            userRepository.getUsers().collect { users ->
                _uiState.update { it.copy(users = users, isLoading = false) }
            }
        }
    }

    fun onUserSelected(user: User) {
        viewModelScope.launch {
            userPreferences.saveUserId(user.id)
            _navigateToMain.send(Unit)
        }
    }

    fun onConnectBand() {
        viewModelScope.launch {
            val userId = userPreferences.getUserId()
            if (userId == null) {
                _uiState.update { it.copy(error = "암밴드를 연결하려면 먼저 유저를 선택해 주세요.") }
            } else {
                _navigateToBleScan.send(Unit)
            }
        }
    }

    // 이미 학습된 모델(weights.bin)이 있는 유저를 선택하고 곧바로 가중치 업데이트
    // 화면으로 이동 — 매번 재녹화/재학습 없이 저장된 모델을 다시 보낼 때 씀.
    fun onResendWeights(user: User) {
        viewModelScope.launch {
            userPreferences.saveUserId(user.id)
            _navigateToFirmware.send(Unit)
        }
    }

    fun onErrorDismissed() {
        _uiState.update { it.copy(error = null) }
    }

    fun onDeleteUserRequested(user: User) {
        _uiState.update { it.copy(deleteTarget = user) }
    }

    fun onDeleteUserCancelled() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun onDeleteUserConfirmed() {
        val target = _uiState.value.deleteTarget ?: return
        viewModelScope.launch {
            emgRepository.clearBatch(target.id)
            userRepository.deleteUser(target.id)
            if (userPreferences.getUserId() == target.id) {
                userPreferences.clear()
            }
            _uiState.update { it.copy(deleteTarget = null) }
        }
    }
}
