package com.mandro.presentation.ui.guide

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.presentation.components.MandroPrimaryButton
import com.mandro.presentation.components.MandroSecondaryButton
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun GuideScreen(
    viewModel: GuideViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onStartRecord: () -> Unit = {},
    onStartTrainingDirectly: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GuideContent(
        uiState = uiState,
        onBack = onBack,
        onPrev = viewModel::onPrev,
        onNext = viewModel::onNext,
        onStartRecord = onStartRecord,
        onStartTrainingDirectly = onStartTrainingDirectly,
    )
}

@Composable
private fun GuideContent(
    uiState: GuideUiState,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onStartRecord: () -> Unit,
    onStartTrainingDirectly: () -> Unit = {},
) {
    Scaffold(
        containerColor = MandroPalette.Neutral50,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 140.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 상단 헤더
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로",
                            tint = MandroPalette.Neutral700,
                        )
                    }
                    Text(
                        text = "${uiState.currentIndex + 1} / ${uiState.total}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MandroPalette.Neutral500,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    // 헤더 대칭을 위한 빈 공간
                    Spacer(Modifier.width(48.dp))
                }

                // 동작명 + 진행 바
                val progressAnim by animateFloatAsState(
                    targetValue = (uiState.currentIndex + 1).toFloat() / uiState.total,
                    animationSpec = tween(durationMillis = 400),
                    label = "progress",
                )

                AnimatedContent(
                    targetState = uiState.currentIndex,
                    transitionSpec = {
                        fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                    },
                    label = "gesture_anim",
                ) { index ->
                    val guide = GESTURE_GUIDES[uiState.gestures[index]]!!
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp),
                    ) {
                        Text(
                            text = guide.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MandroPalette.Neutral900,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = guide.nameKo,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MandroPalette.Neutral500,
                        )
                        Spacer(Modifier.height(12.dp))

                        // 진행 바
                        LinearProgressIndicator(
                            progress = { progressAnim },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = MandroPalette.Primary600,
                            trackColor = MandroPalette.Neutral100,
                            drawStopIndicator = {},
                        )

                        Spacer(Modifier.height(24.dp))

                        // 일러스트 영역 — assets/gesture_guides/<동작>/f01~f12.jpg 프레임을 순환 재생
                        GestureFrameAnimation(gestureKey = guide.name)

                        Spacer(Modifier.height(24.dp))

                        // 이렇게 해보세요
                        Text(
                            text = "이렇게 해보세요",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MandroPalette.Neutral900,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = guide.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MandroPalette.Neutral700,
                        )

                        // 주의사항 카드
                        if (guide.caution != null) {
                            Spacer(Modifier.height(16.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MandroPalette.Warning100,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "▲",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MandroPalette.Warning600,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = guide.caution,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                        color = MandroPalette.Warning600,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 하단 고정 버튼
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MandroPalette.Neutral50)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (uiState.isLast) {
                    MandroPrimaryButton(
                        text = "동작 녹화 시작하기",
                        onClick = onStartRecord,
                    )
                } else {
                    MandroPrimaryButton(
                        text = "다음",
                        onClick = onNext,
                    )
                }
                if (uiState.hasExistingData) {
                    MandroSecondaryButton(
                        text = "기존 데이터(${uiState.existingLapCount}랩)로 바로 학습",
                        onClick = onStartTrainingDirectly,
                    )
                } else if (uiState.currentIndex > 0) {
                    MandroSecondaryButton(
                        text = "이전 동작 다시 보기",
                        onClick = onPrev,
                    )
                }
            }
        }
    }
}

@Composable
private fun GestureFrameAnimation(gestureKey: String) {
    val context = LocalContext.current
    val folder = "gesture_guides/${gestureKey.lowercase()}"

    var frames by remember(folder) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var frameIndex by remember(folder) { mutableIntStateOf(0) }

    LaunchedEffect(folder) {
        frameIndex = 0
        frames = withContext(Dispatchers.IO) {
            val names = context.assets.list(folder)?.sorted() ?: emptyList()
            names.map { name ->
                context.assets.open("$folder/$name").use { BitmapFactory.decodeStream(it) }.asImageBitmap()
            }
        }
    }

    LaunchedEffect(frames) {
        if (frames.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(120)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MandroPalette.Neutral100, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        frames.getOrNull(frameIndex)?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

// ── 프리뷰 ────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun GuidePreview_Flexion() {
    MandroTheme {
        GuideContent(
            uiState = GuideUiState(currentIndex = 1),
            onBack = {},
            onPrev = {},
            onNext = {},
            onStartRecord = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun GuidePreview_Last() {
    MandroTheme {
        GuideContent(
            uiState = GuideUiState(currentIndex = 5),
            onBack = {},
            onPrev = {},
            onNext = {},
            onStartRecord = {},
        )
    }
}
