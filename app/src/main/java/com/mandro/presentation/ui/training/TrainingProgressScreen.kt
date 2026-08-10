package com.mandro.presentation.ui.training

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme

@Composable
fun TrainingProgressScreen(
    viewModel: TrainingProgressViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.isDone) {
        onDone()
        return
    }

    TrainingProgressContent(uiState = uiState)
}

@Composable
private fun TrainingProgressContent(uiState: TrainingProgressUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MandroPalette.Neutral50)
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            text = "내 설정을 만들고 있어요",
            style = MaterialTheme.typography.headlineLarge,
            color = MandroPalette.Neutral900,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "잠깐만요, 곧 완료돼요 🔥",
            style = MaterialTheme.typography.bodyMedium,
            color = MandroPalette.Neutral500,
        )

        Spacer(Modifier.height(48.dp))

        // 스텝 목록
        uiState.steps.forEachIndexed { index, step ->
            StepRow(
                number = index + 1,
                step = step,
                isLast = index == uiState.steps.lastIndex,
            )
        }

        Spacer(Modifier.weight(1f))

        // 하단 안내
        Text(
            text = "예상 시간: 약 ${uiState.estimatedSeconds}초",
            style = MaterialTheme.typography.bodyMedium,
            color = MandroPalette.Neutral700,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "앱을 닫지 말고 기다려 주세요",
            style = MaterialTheme.typography.bodyMedium,
            color = MandroPalette.Neutral500,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun StepRow(
    number: Int,
    step: TrainingStep,
    isLast: Boolean,
) {
    val isDone = step.state == StepState.DONE
    val isActive = step.state == StepState.IN_PROGRESS
    val isPending = step.state == StepState.PENDING

    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 왼쪽: 인디케이터 + 연결선
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(40.dp),
        ) {
            // 원형 인디케이터
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isDone    -> MandroPalette.Success600
                            isActive  -> MandroPalette.Primary600
                            else      -> MandroPalette.Neutral100
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isDone) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MandroPalette.White,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isActive) MandroPalette.White else MandroPalette.Neutral500,
                    )
                }
            }

            // 연결선
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(48.dp)
                        .background(
                            if (isDone) MandroPalette.Success600.copy(alpha = 0.3f)
                            else MandroPalette.Neutral100
                        ),
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // 오른쪽: 텍스트 + 진행 바
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 6.dp, bottom = if (isLast) 0.dp else 24.dp),
        ) {
            Text(
                text = step.label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = when {
                    isPending -> MandroPalette.Neutral300
                    isDone    -> MandroPalette.Neutral700
                    else      -> MandroPalette.Neutral900
                },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = step.statusText,
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    isDone   -> MandroPalette.Success600
                    isActive -> MandroPalette.Primary600
                    else     -> MandroPalette.Neutral300
                },
            )

            // 진행 바 (업로드 단계)
            if (step.progress != null) {
                Spacer(Modifier.height(8.dp))
                val progressAnim by animateFloatAsState(
                    targetValue = step.progress,
                    animationSpec = tween(200),
                    label = "upload_progress",
                )
                LinearProgressIndicator(
                    progress = { progressAnim },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MandroPalette.Primary600,
                    trackColor = MandroPalette.Neutral100,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

// ── 프리뷰 ────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun TrainingProgressPreview() {
    MandroTheme {
        TrainingProgressContent(
            uiState = TrainingProgressUiState(
                steps = listOf(
                    TrainingStep("녹화 데이터 확인 중", StepState.DONE, statusText = "완료"),
                    TrainingStep("설정을 만들고 있어요", StepState.IN_PROGRESS, progress = 0.62f, statusText = "전송 중... 62%"),
                    TrainingStep("내 동작 패턴을 분석하고 있어요", StepState.PENDING),
                    TrainingStep("거의 다 됐어요!", StepState.PENDING),
                )
            )
        )
    }
}
