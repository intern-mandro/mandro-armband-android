package com.mandro.presentation.ui.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.UserPreferences
import com.mandro.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserCreateUiState(
    val name: String = "",
    val consentPrivacy: Boolean = false,
    val consentResearch: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val isStartEnabled: Boolean
        get() = name.isNotBlank() && consentPrivacy && !isLoading
}

@HiltViewModel
class UserCreateViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(UserCreateUiState())
    val uiState = _uiState.asStateFlow()

    private val _navigateNext = Channel<Unit>(Channel.BUFFERED)
    val navigateNext = _navigateNext.receiveAsFlow()

    fun onNameChange(value: String) {
        _uiState.update { it.copy(name = value, error = null) }
    }

    fun onConsentPrivacyChange(checked: Boolean) {
        _uiState.update { it.copy(consentPrivacy = checked) }
    }

    fun onConsentResearchChange(checked: Boolean) {
        _uiState.update { it.copy(consentResearch = checked) }
    }

    fun onStartClick() {
        val state = _uiState.value
        if (!state.isStartEnabled) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                userRepository.createUser(
                    name = state.name.trim(),
                    researchConsent = state.consentResearch,
                )
            }.onSuccess { user ->
                // 이 화면 다음(onStart)이 Home의 유저 선택(HomeViewModel.onUserSelected,
                // saveUserId 호출)을 안 거치고 바로 BleScan으로 가므로, 여기서 직접
                // 활성 유저로 지정 안 하면 예전 활성 유저가 그대로 남아서 새로 만든
                // 유저가 아니라 그쪽에 녹화가 기록되는 문제가 있었음(2026-07-21).
                userPreferences.saveUserId(user.id)
                _navigateNext.send(Unit)
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "오류가 발생했어요") }
            }
        }
    }
}
