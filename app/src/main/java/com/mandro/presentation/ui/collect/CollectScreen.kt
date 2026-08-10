package com.mandro.presentation.ui.collect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.BuildConfig
import com.mandro.domain.model.BleDevice
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.model.GestureSet
import com.mandro.presentation.components.ConnectionBadge
import com.mandro.presentation.components.MandroPrimaryButton
import com.mandro.presentation.components.MandroSecondaryButton
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme
import kotlinx.coroutines.android.awaitFrame

@Composable
fun CollectScreen(
    viewModel: CollectViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.isDone) {
        onDone()
        return
    }

    CollectContent(
        uiState = uiState,
        getAmplitudes = { viewModel.channelAmplitudes },
        onStartTrainingEarly = viewModel::onStartTrainingEarly,
        onDebugSkip = viewModel::onDebugSkip,
        onRedoLap = viewModel::onRedoLap,
        onContinueToNextLap = viewModel::onContinueToNextLap,
    )
}

@Composable
private fun CollectContent(
    uiState: CollectUiState,
    getAmplitudes: () -> FloatArray = { FloatArray(EMG_CHANNELS) },
    onStartTrainingEarly: () -> Unit = {},
    onDebugSkip: () -> Unit = {},
    onRedoLap: () -> Unit = {},
    onContinueToNextLap: () -> Unit = {},
) {
    if (uiState.lapReviewPending) {
        LapReviewDialog(
            lapNumber = uiState.currentLap,
            onRedo = onRedoLap,
            onContinue = onContinueToNextLap,
        )
    }

    val lapProgressAnim by animateFloatAsState(
        targetValue = uiState.lapProgress,
        animationSpec = tween(600),
        label = "lap_progress",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MandroPalette.Neutral50)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        // 헤더
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "동작 녹화",
                style = MaterialTheme.typography.headlineLarge,
                color = MandroPalette.Neutral900,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${uiState.currentLap - 1} / $TOTAL_LAPS 랩",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MandroPalette.Neutral500,
                )
                Spacer(Modifier.width(8.dp))
                ConnectionBadge(bleState = uiState.bleState)
            }
        }

        Spacer(Modifier.height(8.dp))

        AnimatedVisibility(
            visible = uiState.isDisconnected,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MandroPalette.Danger600.copy(alpha = 0.1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = "블루투스 연결이 끊겼어요. 다시 연결되면 자동으로 이어서 녹화합니다.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MandroPalette.Danger600,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 랩 진행 바
        LinearProgressIndicator(
            progress = { lapProgressAnim },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MandroPalette.Primary600,
            trackColor = MandroPalette.Neutral100,
            drawStopIndicator = {},
        )

        Spacer(Modifier.height(16.dp))

        // 현재 동작 카드
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MandroPalette.Primary50,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "지금 동작: ${uiState.currentGestureName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MandroPalette.Primary600,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = gestureNameKo(uiState.currentGestureName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MandroPalette.Neutral700,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${uiState.currentGestureIndex + 1} / ${uiState.gestures.size}번째 동작 · ${uiState.currentLap}랩",
                    style = MaterialTheme.typography.labelMedium,
                    color = MandroPalette.Neutral500,
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // 카운트다운 or 녹화 애니메이션
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = uiState.phase,
                transitionSpec = {
                    (scaleIn(tween(250), initialScale = 0.7f) + fadeIn(tween(200))) togetherWith
                        (scaleOut(tween(200), targetScale = 1.2f) + fadeOut(tween(150)))
                },
                label = "phase_anim",
            ) { phase ->
                when (phase) {
                    is CollectPhase.Countdown -> CountdownCircle(count = phase.count)
                    is CollectPhase.Recording -> SignalStrengthBars(getAmplitudes = getAmplitudes)
                }
            }
        }

        AnimatedContent(
            targetState = uiState.phase is CollectPhase.Recording,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "hint_anim",
        ) { isRecording ->
            if (isRecording) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "동작 중...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MandroPalette.Neutral500,
                    )
                    Spacer(Modifier.width(8.dp))
                    AnimatedContent(
                        targetState = uiState.recordingSecondsLeft,
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                        },
                        label = "seconds_anim",
                    ) { seconds ->
                        Text(
                            text = "${seconds}초",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            ),
                            color = MandroPalette.Primary600,
                        )
                    }
                }
            } else {
                Text(
                    text = "준비되면 동작해 주세요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MandroPalette.Neutral500,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // 5랩 달성 전 안내 / 달성 후 학습 버튼
        AnimatedVisibility(
            visible = !uiState.canStartTraining,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MandroPalette.Neutral100,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "최소 ${MIN_LAPS_TO_TRAIN}랩 이상 수집해야 학습할 수 있어요",
                    style = MaterialTheme.typography.labelMedium,
                    color = MandroPalette.Neutral500,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        AnimatedVisibility(
            visible = uiState.canStartTraining,
            enter = expandVertically() + fadeIn(),
            exit = fadeOut(),
        ) {
            MandroSecondaryButton(
                text = "지금 학습 시작하기 (${uiState.currentLap - 1}랩 수집 완료)",
                onClick = onStartTrainingEarly,
            )
        }

        Spacer(Modifier.height(24.dp))

        // 개발 전용 스킵 버튼
        // if (BuildConfig.DEBUG) {
        //     TextButton(
        //         onClick = onDebugSkip,
        //         modifier = Modifier.fillMaxWidth(),
        //     ) {
        //         Text(
        //             text = "[DEV] 다음 화면으로 →",
        //             style = MaterialTheme.typography.labelMedium,
        //             color = MandroPalette.Danger600,
        //         )
        //     }
        //     Spacer(Modifier.height(8.dp))
        // }

        // 랩 내 동작 현황
        Text(
            text = "이번 랩 진행",
            style = MaterialTheme.typography.headlineSmall,
            color = MandroPalette.Neutral900,
        )
        Spacer(Modifier.height(8.dp))

        uiState.gestures.forEachIndexed { index, name ->
            GestureRow(
                name = name,
                isDone = index < uiState.currentGestureIndex,
                isCurrent = index == uiState.currentGestureIndex,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LapReviewDialog(
    lapNumber: Int,
    onRedo: () -> Unit,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},  // 바깥 탭으로 닫히지 않도록 — 반드시 선택해야 함
        title = {
            Text(
                text = "${lapNumber}랩 녹화 완료",
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Text(
                text = "방금 녹화한 ${lapNumber}랩을 그대로 사용할까요,\n다시 녹화할까요?",
                style = MaterialTheme.typography.bodyMedium,
                color = MandroPalette.Neutral700,
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text("다음 랩 진행", color = MandroPalette.Primary600)
            }
        },
        dismissButton = {
            TextButton(onClick = onRedo) {
                Text("다시 녹화", color = MandroPalette.Danger600)
            }
        },
    )
}

@Composable
private fun CountdownCircle(count: Int) {
    Box(
        modifier = Modifier
            .size(160.dp)
            .border(width = 3.dp, color = MandroPalette.Primary600, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = count,
            transitionSpec = {
                scaleIn(tween(300), initialScale = 0.5f) + fadeIn(tween(250)) togetherWith
                    fadeOut(tween(150))
            },
            label = "count_num",
        ) { n ->
            Text(
                text = n.toString(),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MandroPalette.Primary600,
            )
        }
    }
}

@Composable
private fun SignalStrengthBars(
    getAmplitudes: () -> FloatArray,
) {
    var frameCount by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            awaitFrame()
            frameCount++
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val colors = MandroPalette.waveColors

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MandroPalette.DarkBg, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            frameCount

            val amplitudes = getAmplitudes()
            val totalWidth = size.width
            val totalHeight = size.height
            val labelHeightPx = 28f
            val barAreaHeight = totalHeight - labelHeightPx
            val barWidth = (totalWidth / EMG_CHANNELS) * 0.55f
            val barSpacing = totalWidth / EMG_CHANNELS

            for (ch in 0 until EMG_CHANNELS) {
                val amp = amplitudes.getOrElse(ch) { 0f }.coerceIn(0f, 1f)
                val barHeight = (barAreaHeight * amp).coerceAtLeast(2f)
                val left = ch * barSpacing + (barSpacing - barWidth) / 2f
                val top = barAreaHeight - barHeight
                val color = colors.getOrElse(ch) { Color.White }

                // 바 본체
                drawRoundRect(
                    color = color.copy(alpha = 0.85f),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(4f, 4f),
                )

                // 바닥 기준선
                drawLine(
                    color = color.copy(alpha = 0.25f),
                    start = Offset(left, barAreaHeight),
                    end = Offset(left + barWidth, barAreaHeight),
                    strokeWidth = 1f,
                )

                // 채널 라벨
                val label = textMeasurer.measure(
                    text = "CH$ch",
                    style = TextStyle(fontSize = 8.sp, color = color.copy(alpha = 0.7f)),
                )
                drawText(
                    textLayoutResult = label,
                    topLeft = Offset(
                        x = left + barWidth / 2f - label.size.width / 2f,
                        y = barAreaHeight + 6f,
                    ),
                )
            }
        }
    }
}

@Composable
private fun GestureRow(
    name: String,
    isDone: Boolean,
    isCurrent: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isDone) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MandroPalette.Success600,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .border(
                        width = 1.5.dp,
                        color = if (isCurrent) MandroPalette.Primary600 else MandroPalette.Neutral300,
                        shape = CircleShape,
                    ),
            )
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = when {
                isDone    -> MandroPalette.Neutral500
                isCurrent -> MandroPalette.Neutral900
                else      -> MandroPalette.Neutral700
            },
            modifier = Modifier.weight(1f),
        )

        if (isCurrent) {
            Text(
                text = "진행 중",
                style = MaterialTheme.typography.labelSmall,
                color = MandroPalette.Primary600,
            )
        }
    }

    HorizontalDivider(color = MandroPalette.Neutral100, thickness = 1.dp)
}

// ── 프리뷰 ────────────────────────────────────────────────────

private val previewConnectedState =
    BleState.Connected(BleDevice("ESP32S3_FAST_BLE", "00:11:22:33:44:55", -55))

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun CollectPreview_Countdown() {
    MandroTheme {
        CollectContent(
            uiState = CollectUiState(
                currentLap = 3,
                currentGestureIndex = 2,
                phase = CollectPhase.Countdown(3),
                bleState = previewConnectedState,
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun CollectPreview_CanTrain() {
    MandroTheme {
        CollectContent(
            uiState = CollectUiState(
                currentLap = 6,
                currentGestureIndex = 0,
                phase = CollectPhase.Recording,
                gestures = GestureSet.SIX_CLASS.classes,
                bleState = previewConnectedState,
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun CollectPreview_Disconnected() {
    MandroTheme {
        CollectContent(
            uiState = CollectUiState(
                currentLap = 3,
                currentGestureIndex = 2,
                phase = CollectPhase.Recording,
                bleState = BleState.Disconnected,
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun CollectPreview_LapReview() {
    MandroTheme {
        CollectContent(
            uiState = CollectUiState(
                currentLap = 3,
                currentGestureIndex = 5,
                phase = CollectPhase.Recording,
                bleState = previewConnectedState,
                lapReviewPending = true,
            )
        )
    }
}
