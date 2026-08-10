package com.mandro.presentation.ui.guide

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mandro.data.local.UserPreferences
import com.mandro.domain.model.GestureSet
import com.mandro.domain.repository.EmgRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GestureGuide(
    val name: String,           // 영문 동작명
    val nameKo: String,         // 한 줄 설명 (한글)
    val description: String,    // 이렇게 해보세요 본문
    val caution: String?,       // 주의사항 (없으면 null)
)

val GESTURE_GUIDES = mapOf(
    "Rest" to GestureGuide(
        name = "Rest",
        nameKo = "손을 자연스럽게 펴서 쉬기",
        description = "손과 팔의 긴장을 완전히 풀어 주세요.\n손가락이 자연스럽게 살짝 구부러진 상태가 기준 자세예요.",
        caution = null,
    ),
    "Flexion" to GestureGuide(
        name = "Flexion",
        nameKo = "손목을 아래로 구부리기",
        description = "손목을 천천히 아래로 구부려 주세요.\n끝까지 구부린 상태를 3초간 유지합니다.",
        caution = "팔꿈치는 고정하고 손목만 움직여 주세요",
    ),
    "Extension" to GestureGuide(
        name = "Extension",
        nameKo = "손목을 위로 젖히기",
        description = "손목을 천천히 위로 젖혀 주세요.\n끝까지 젖힌 상태를 3초간 유지합니다.",
        caution = "팔꿈치는 고정하고 손목만 움직여 주세요",
    ),
    "Close" to GestureGuide(
        name = "Close",
        nameKo = "주먹 꽉 쥐기",
        description = "손가락을 전부 안으로 굽혀 주먹을 꽉 쥐어 주세요.\n엄지손가락도 함께 굽혀 주세요.",
        caution = null,
    ),
    "Supination" to GestureGuide(
        name = "Supination",
        nameKo = "손바닥이 위를 향하게 돌리기",
        description = "손바닥이 하늘을 향하도록 팔을 돌려 주세요.\n끝까지 돌린 상태를 3초간 유지합니다.",
        caution = "팔꿈치를 고정하고 팔목만 회전해 주세요",
    ),
    "Pronation" to GestureGuide(
        name = "Pronation",
        nameKo = "손바닥이 아래를 향하게 돌리기",
        description = "손바닥이 바닥을 향하도록 팔을 돌려 주세요.\n끝까지 돌린 상태를 3초간 유지합니다.",
        caution = "팔꿈치를 고정하고 팔목만 회전해 주세요",
    ),
)

data class GuideUiState(
    val gestures: List<String> = GestureSet.SIX_CLASS.classes,
    val currentIndex: Int = 0,
    val existingLapCount: Int = 0,  // Room DB에 이미 쌓인 랩 수
) {
    val current: GestureGuide get() = GESTURE_GUIDES[gestures[currentIndex]]!!
    val total: Int get() = gestures.size
    val isLast: Boolean get() = currentIndex == gestures.size - 1
    val hasExistingData: Boolean get() = existingLapCount > 0
}

@HiltViewModel
class GuideViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val emgRepository: EmgRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val startIndex = savedStateHandle.get<Int>("gestureIndex") ?: 0

    private val _uiState = MutableStateFlow(GuideUiState(currentIndex = startIndex))
    val uiState = _uiState.asStateFlow()

    init {
        loadExistingLapCount()
    }

    private fun loadExistingLapCount() {
        viewModelScope.launch {
            val userId = userPreferences.getUserId() ?: return@launch
            val batch = emgRepository.getBatch(userId)
            val lapCount = batch?.takes?.values?.maxOfOrNull { it.size } ?: 0
            _uiState.update { it.copy(existingLapCount = lapCount) }
        }
    }

    fun onNext() {
        _uiState.update { state ->
            if (!state.isLast) state.copy(currentIndex = state.currentIndex + 1)
            else state
        }
    }

    fun onPrev() {
        _uiState.update { state ->
            if (state.currentIndex > 0) state.copy(currentIndex = state.currentIndex - 1)
            else state
        }
    }
}
