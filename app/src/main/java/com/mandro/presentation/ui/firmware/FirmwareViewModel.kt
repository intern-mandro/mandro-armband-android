package com.mandro.presentation.ui.firmware

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.UserPreferences
import com.mandro.domain.model.BleState
import com.mandro.domain.model.WeightTransferState
import com.mandro.domain.repository.BleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "FirmwareViewModel"

enum class CheckState { PENDING, CHECKING, DONE }

data class FirmwareCheck(
    val label: String,
    val state: CheckState = CheckState.PENDING,
)

data class FirmwareUiState(
    val checks: List<FirmwareCheck> = listOf(
        FirmwareCheck("암밴드 연결됨"),
        FirmwareCheck("내 설정 준비됨"),
    ),
    val isUpdateEnabled: Boolean = false,
    val isUpdating: Boolean = false,
    val isDone: Boolean = false,
    val isBleConnected: Boolean = false,
    // 업데이트 완료 후 암밴드가 스스로 재부팅하면서 "한 번 실제로 끊겼다가" 다시
    // 연결된 게 확인된 경우에만 true. isDone && isBleConnected만 보면, Done
    // 알림과 실제 연결 끊김 사이의 시차 때문에 "아직 안 끊긴 옛 연결"을 재연결로
    // 착각해서 곧장 다음 화면으로 넘어갔다가 뒤늦게 끊김이 감지되는 문제가 있었음
    // (FirmwareViewModel.observePostUpdateReconnect 참고).
    val isReconnectedAfterUpdate: Boolean = false,
    val error: String? = null,
) {
    val allChecked: Boolean get() = checks.all { it.state == CheckState.DONE }
}

private enum class PostUpdatePhase { NONE, AWAITING_DISCONNECT, AWAITING_RECONNECT, DONE }

@HiltViewModel
class FirmwareViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleRepository: BleRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FirmwareUiState())
    val uiState = _uiState.asStateFlow()

    private var weightsFile: File? = null

    // weightTransferState는 BleManager 싱글턴에 남아있는 "마지막 전송 결과"를
    // 그대로 들고 있음 — 이 화면을 새로 열었을 때 이전 세션의 Done/Error가
    // 남아있으면 바로 그걸 보고 반응해버리는 걸 막기 위한 가드.
    // onStartUpdate()를 실제로 호출한 뒤부터만 상태 변화에 반응한다.
    private var transferStarted = false

    // 업데이트 완료 후 "실제로 한 번 끊겼다가 다시 연결됨"을 추적하는 상태 —
    // observeBleState()/observeWeightTransferState() 양쪽에서 갱신.
    private var postUpdatePhase = PostUpdatePhase.NONE

    init {
        observeBleState()
        observeWeightTransferState()
        checkModelReady()
    }

    // 암밴드 연결은 다른 화면(수집 화면 등)에서 이미 맺어져 있을 수 있음 —
    // BleManager가 싱글턴이라 여기서는 그 연결 상태를 관찰만 함. 연결이 끊기면
    // 체크도 다시 PENDING으로 되돌려서(연결됐다가 끊긴 경우 화면이 계속 "연결됨"으로
    // 잘못 표시되지 않도록), 안 연결된 상태면 UI에서 연결 버튼을 보여줄 수 있게 함.
    private fun observeBleState() {
        viewModelScope.launch {
            bleRepository.bleState.collect { state ->
                val connected = state is BleState.Connected
                setCheckState(0, connected)
                _uiState.update { it.copy(isBleConnected = connected) }
                updateEnabled()

                when (postUpdatePhase) {
                    PostUpdatePhase.AWAITING_DISCONNECT -> if (!connected) {
                        postUpdatePhase = PostUpdatePhase.AWAITING_RECONNECT
                    }
                    PostUpdatePhase.AWAITING_RECONNECT -> if (connected) {
                        postUpdatePhase = PostUpdatePhase.DONE
                        _uiState.update { it.copy(isReconnectedAfterUpdate = true) }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeWeightTransferState() {
        viewModelScope.launch {
            bleRepository.weightTransferState.collect { state ->
                if (!transferStarted) return@collect
                when (state) {
                    is WeightTransferState.Sending -> {
                        _uiState.update { it.copy(isUpdating = true) }
                    }
                    is WeightTransferState.Done -> {
                        _uiState.update { it.copy(isUpdating = false, isDone = true) }
                        // 암밴드가 곧 스스로 재부팅하며 연결이 끊길 예정 — 그 끊김을
                        // 실제로 관측한 뒤에만 재연결로 인정하도록 대기 상태로 전환.
                        postUpdatePhase = PostUpdatePhase.AWAITING_DISCONNECT
                    }
                    is WeightTransferState.Error -> {
                        _uiState.update { it.copy(isUpdating = false, error = state.message) }
                        Log.e(TAG, "BLE 가중치 전송 오류: ${state.message}")
                    }
                    is WeightTransferState.Idle -> {}
                }
            }
        }
    }

    private fun checkModelReady() {
        viewModelScope.launch {
            val userId = userPreferences.getUserId() ?: return@launch
            val file = File(context.filesDir, "models/$userId/weights.bin")
            if (file.exists()) {
                weightsFile = file
                setCheckState(1, true)
                updateEnabled()
                Log.i(TAG, "weights.bin 준비 완료: ${file.absolutePath}")
            } else {
                _uiState.update { it.copy(error = "학습된 모델이 없어요. 학습을 먼저 진행해 주세요.") }
            }
        }
    }

    fun onStartUpdate() {
        val file = weightsFile ?: return
        transferStarted = true
        _uiState.update { it.copy(isUpdating = true, error = null) }
        viewModelScope.launch {
            bleRepository.sendWeights(file.readBytes())
                .onFailure { e ->
                    _uiState.update { it.copy(isUpdating = false, error = e.message) }
                }
        }
    }

    private fun setCheckState(index: Int, done: Boolean) {
        _uiState.update { state ->
            state.copy(checks = state.checks.mapIndexed { i, check ->
                if (i == index) check.copy(state = if (done) CheckState.DONE else CheckState.PENDING) else check
            })
        }
    }

    private fun updateEnabled() {
        _uiState.update { it.copy(isUpdateEnabled = it.allChecked) }
    }
}
