package com.mandro.presentation.ui.waveform

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.presentation.components.ConnectionBadge
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.sin

@Composable
fun WaveformScreen(
    viewModel: WaveformViewModel = hiltViewModel(),
    onDisconnected: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.navigateToBleScan.collect { onDisconnected() }
    }

    when (uiState.calibration) {
        is CalibrationState.Idle -> CalibrationReadyScreen(
            onStart = viewModel::startCalibration,
        )
        is CalibrationState.Calibrating -> CalibrationOverlay(
            progress = uiState.calibrationProgress,
            isDone = false,
            onConfirm = {},
        )
        is CalibrationState.ReadyToConfirm -> CalibrationOverlay(
            progress = 1f,
            isDone = true,
            onConfirm = viewModel::confirmCalibration,
        )
        is CalibrationState.Done -> WaveformContent(
            uiState = uiState,
            buffers = viewModel.buffers,
            getWritePtr = { viewModel.writePtr },
            onToggleChannel = viewModel::toggleChannel,
            onRecalibrate = viewModel::startCalibration,
        )
    }
}

@Composable
private fun WaveformContent(
    uiState: WaveformUiState,
    buffers: Array<FloatArray>,
    getWritePtr: () -> Int = { 0 },
    onToggleChannel: (Int) -> Unit,
    onRecalibrate: () -> Unit = {},
) {
    // 매 프레임마다 Canvas를 강제 갱신 — 리컴포지션 없이 드로잉만 반복
    // 이 방식이 StateFlow collect보다 레이턴시가 낮음
    var frameCount by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            awaitFrame()
            frameCount++
        }
    }

    val textMeasurer = rememberTextMeasurer()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MandroPalette.DarkBg),
    ) {
        // 상단 헤더
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "신호 모니터",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MandroPalette.White,
                )
                Text(
                    text = "신호가 들어오지 않는 채널이 있는지 확인해주세요",
                    style = MaterialTheme.typography.labelSmall,
                    color = MandroPalette.Neutral500,
                )
            }
            TextButton(onClick = onRecalibrate) {
                Text(
                    text = "기준선 다시 측정",
                    style = MaterialTheme.typography.labelSmall,
                    color = MandroPalette.Primary600,
                )
            }
            ConnectionBadge(bleState = uiState.bleState)
        }

        // 채널 파형 목록
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            // TODO: 채널 수 4/6/8 전환 시 EMG_CHANNELS → uiState.activeChannels로 교체
            repeat(EMG_CHANNELS) { ch ->
                ChannelRow(
                    ch = ch,
                    buffer = buffers[ch],
                    color = MandroPalette.waveColors[ch],
                    isVisible = uiState.visibleChannels[ch],
                    frameCount = frameCount,
                    getWritePtr = getWritePtr,
                    textMeasurer = textMeasurer,
                    onToggle = { onToggleChannel(ch) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }

    }
}

@Composable
private fun ChannelRow(
    ch: Int,
    buffer: FloatArray,
    color: Color,
    isVisible: Boolean,
    frameCount: Long,
    getWritePtr: () -> Int,
    textMeasurer: TextMeasurer,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 채널 라벨 (탭하면 on/off)
        Box(
            modifier = Modifier
                .width(36.dp)
                .fillMaxHeight()
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CH$ch",
                style = TextStyle(
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isVisible) color else MandroPalette.DarkBorder,
                ),
            )
        }

        // 파형 Canvas — frameCount 읽어서 매 프레임 갱신 트리거
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MandroPalette.DarkSurf, RoundedCornerShape(4.dp)),
        ) {
            @Suppress("UNUSED_EXPRESSION")
            frameCount // 이 값을 읽어야 프레임마다 리드로우 발생

            if (isVisible) {
                drawWaveform(
                    buffer = buffer,
                    writePtr = getWritePtr(),
                    color = color,
                    strokeWidth = 1.5.dp.toPx(),
                    textMeasurer = textMeasurer,
                )
            }
        }
    }
}

@Composable
private fun CalibrationReadyScreen(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MandroPalette.DarkBg)
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "신호 기준선 측정",
            style = MaterialTheme.typography.headlineSmall,
            color = MandroPalette.White,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "팔에 힘을 완전히 빼고\n편안하게 내려놓은 상태에서\n측정을 시작해 주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MandroPalette.Neutral500,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "약 3초간 쉬는 상태의 신호를 측정합니다.",
            style = MaterialTheme.typography.labelMedium,
            color = MandroPalette.Neutral700,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MandroPalette.Primary600),
        ) {
            Text("측정 시작하기", style = MaterialTheme.typography.labelLarge, color = MandroPalette.White)
        }
    }
}

@Composable
private fun CalibrationOverlay(
    progress: Float,
    isDone: Boolean,
    onConfirm: () -> Unit,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(300),
        label = "calib_progress",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MandroPalette.DarkBg)
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (isDone) "신호 안정화 완료" else "신호 안정화 중...",
            style = MaterialTheme.typography.headlineSmall,
            color = MandroPalette.White,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "암밴드를 착용하고 팔에 힘을 빼주세요",
            style = MaterialTheme.typography.bodyMedium,
            color = MandroPalette.Neutral500,
        )
        Spacer(Modifier.height(32.dp))
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MandroPalette.Primary600,
            trackColor = MandroPalette.Neutral700,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(12.dp))
        if (!isDone) {
            Text(
                text = "${(animatedProgress * 3).toInt() + 1}초 / 3초",
                style = MaterialTheme.typography.labelMedium,
                color = MandroPalette.Neutral500,
            )
        }
        Spacer(Modifier.height(40.dp))
        AnimatedVisibility(
            visible = isDone,
            enter = fadeIn(tween(400)) + expandVertically(),
        ) {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MandroPalette.Primary600),
            ) {
                Text("확인했어요", style = MaterialTheme.typography.labelLarge, color = MandroPalette.White)
            }
        }
    }
}

// 커서 주변 빈 구간 (samples)
private const val CURSOR_GAP = 5
// 신호 없음 판정 임계값 (uint8 range 0~255 기준)
private const val NO_SIGNAL_THRESHOLD = 5
// 자동 스케일 최소 표시 범위 — 이보다 작으면 노이즈로 보고 확대 안 함
private const val MIN_DISPLAY_RANGE = 60f

private fun DrawScope.drawWaveform(
    buffer: FloatArray,
    writePtr: Int,
    color: Color,
    strokeWidth: Float,
    textMeasurer: TextMeasurer,
) {
    val w = size.width
    val h = size.height
    val midY = h / 2f
    val n = buffer.size

    fun bx(idx: Int) = (idx.toFloat() / (n - 1)) * w

    // ── 기준선 ────────────────────────────────────────────────
    drawLine(
        color = MandroPalette.DarkBorder,
        start = Offset(0f, midY),
        end = Offset(w, midY),
        strokeWidth = 0.5.dp.toPx(),
    )

    // ── 스케일 계산 (2~98 백분위수 기반 — 스파이크 한두 개에 안 흔들림) ──
    val sorted = buffer.copyOf().also { it.sort() }
    val p2  = sorted[(n * 0.02f).toInt().coerceIn(0, n - 1)]
    val p98 = sorted[(n * 0.98f).toInt().coerceIn(0, n - 1)]
    val hasSignal = (p98 - p2) > NO_SIGNAL_THRESHOLD

    val displayRange = maxOf(p98 - p2, MIN_DISPLAY_RANGE)
    val midOffset = (p98 + p2) / 2f
    val scale = (h * 0.45f) / (displayRange / 2f)

    fun by(v: Float) = midY - (v - midOffset) * scale

    val lineColor = if (hasSignal) color else color.copy(alpha = 0.25f)

    // ── 세그먼트 드로우 헬퍼 ─────────────────────────────────
    fun drawSegment(from: Int, to: Int) {
        if (to - from < 2) return
        val path = Path()
        path.moveTo(bx(from), by(buffer[from]))
        for (i in from + 1 until to) {
            path.lineTo(bx(i), by(buffer[i]))
        }
        drawPath(path, lineColor, style = Stroke(strokeWidth))
    }

    // ── 링버퍼 분할 드로우 ────────────────────────────────────
    // Current (왼쪽, 최신): 0 ~ writePtr-1
    // Past    (오른쪽, 과거): writePtr+GAP ~ n-1
    val pastStart = (writePtr + CURSOR_GAP).coerceAtMost(n)

    drawSegment(0, writePtr)
    drawSegment(pastStart, n)

    // ── 커서 수직선 ───────────────────────────────────────────
    drawLine(
        color = MandroPalette.White.copy(alpha = 0.35f),
        start = Offset(bx(writePtr), 0f),
        end = Offset(bx(writePtr), h),
        strokeWidth = 1.dp.toPx(),
    )

    // ── 신호 없음 안내 문구 ───────────────────────────────────
    if (!hasSignal) {
        val measured = textMeasurer.measure(
            text = "신호 없음",
            style = TextStyle(fontSize = 8.sp, color = MandroPalette.Neutral500),
        )
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(
                x = w / 2f - measured.size.width / 2f,
                y = midY - measured.size.height / 2f,
            ), 
        )
    }
}


// ── 프리뷰 ────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun WaveformPreview() {
    MandroTheme {
        val dummyBuffers = Array(EMG_CHANNELS) { ch ->
            FloatArray(DISPLAY_SAMPLES) { i ->
                // uint8 범위(0~255), 중앙값 127.5 기준 sine
                (sin(i * 0.05 + ch) * 80f + 127.5f).toFloat()
            }
        }
        WaveformContent(
            uiState = WaveformUiState(bleState = BleState.Connected(
                com.mandro.domain.model.BleDevice("ESP32S3_FAST_BLE", "00:11:22:33:44:55", -55)
            )),
            buffers = dummyBuffers,
            getWritePtr = { DISPLAY_SAMPLES / 2 },
            onToggleChannel = {},
        )
    }
}
