package com.mandro.presentation.ui.classify

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.presentation.components.ConnectionBadge
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// 8채널 축 각도: 좌우 각 4개씩 대칭 배치 (단위: 라디안)
// 물리적 각도 슬롯 8개(-120,-150,150,120,60,30,-30,-60)는 그대로 두고,
// 어느 채널이 어느 슬롯에 앉을지만 재배치함 — 암밴드를 실제로 착용했을 때
// 2사분면에서 시계 반대방향으로 도는 순서가 0,1,2,...,7이 아니라
// 3,2,1,0,7,6,5,4라서, 그 순서에 맞춰 채널 인덱스를 슬롯에 재할당.
private val CHANNEL_ANGLES_DEG = floatArrayOf(120f, 150f, -150f, -120f, -60f, -30f, 30f, 60f)
private val CHANNEL_ANGLES_RAD = CHANNEL_ANGLES_DEG.map { Math.toRadians(it.toDouble()).toFloat() }.toFloatArray()

// 채널 간 최소 각도 간격은 30° — 떨림으로 인한 각도 변화폭을 그 절반보다
// 확실히 작게 제한해서, 아무리 흔들려도 옆 채널 부채꼴을 침범하지 않게 함.
private val MAX_ANGLE_DEVIATION_RAD = Math.toRadians(10.0).toFloat()
// ClassifyViewModel.JITTER_CLAMP와 맞춰야 함 (거기서 이 범위로 클램프해서 넘어옴)
private const val JITTER_CLAMP = 0.3f

// intensity(0~1)를 화면에 그대로 그리면 실제 신호가 낮은 대역(대략 0.04~0.2)에
// 몰려 있어서 세게 힘줘도 작게 보임. 그렇다고 선형 배율(×GAIN)만 쓰면 낮은
// 대역 전체를 고르게 늘릴 뿐이라 그 안에서의 크기 차이가 잘 안 보이고, 반대로
// 거듭제곱 커브(감마<1)만 쓰면 실측 대역(0.04 이상)에서는 오히려 선형 배율보다
// 작게 나옴 — 그래서 먼저 ×GAIN으로 키워서 1.0 이하로 clamp한 다음, 그 위에
// 제곱근(감마<1)을 씌워서 낮은 쪽을 한 번 더 밀어올림. 두 방식을 합쳐야
// "낮은 신호도 조금만 커지면 눈에 띄게 커지는" 반응이 나옴.
// (실제 값 자체는 안 바꾸고 그리는 길이만 이 커브를 거침)
private const val INTENSITY_GAIN = 15f
private const val INTENSITY_GAMMA = 0.5f

// 신호 유무 판정 임계값 — ClassifyViewModel의 rest 강제 판정과 동일한 값을 씀
// (RECOGNITION_IMPROVEMENT.md 참고). 임계값 미만이어도 선을 완전히 숨기지 않고
// WaveformScreen.kt의 "옅게 그리기" 패턴을 재사용 — 신호가 약하게라도 들어오고
// 있다는 걸 항상 확인할 수 있어야 함.
// ClassifyViewModel의 ACTIVE_CHANNEL_THRESHOLD와 동일한 값 유지 (RECOGNITION_IMPROVEMENT.md 3차)
private const val LOW_SIGNAL_THRESHOLD = 0.01f
private const val LOW_SIGNAL_ALPHA = 0.25f
// rest에서도 "센서가 실제로 미세하게 흔들리고 있다"를 보여주기 위해, jitter(순간
// 편차) 크기에 비례해서 그리는 최소 반지름의 배율 — 고정값이 아니라
// abs(jitter)/JITTER_CLAMP(0~1)에 곱해지므로 조용하면 0에 가깝고 실제로 흔들리는
// 만큼만 보임(2026-07-21, 이전의 고정 MIN_ALIVE_LENGTH_RATIO 방식은 진짜 미세
// 움직임을 그 고정값에 파묻어버리는 문제가 있어서 폐기).
private const val JITTER_VISIBLE_RATIO = 0.12f
// 길이가 짧을 때(rest 근처)는 각도 흔들림도 비례해서 줄임 — 안 그러면 짧은 길이
// 대비 각도 흔들림 폭이 상대적으로 훨씬 커 보여서 산만해짐.
private const val STUB_ANGLE_DEVIATION_SCALE = 0.4f

@Composable
fun ClassifyScreen(
    viewModel: ClassifyViewModel = hiltViewModel(),
    onRelearn: () -> Unit = {},
    onDisconnected: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 가중치 전송 성공 후 암밴드가 재부팅하며 연결이 끊기는 경우를 감지해서
    // BleScan으로 돌려보냄 (WaveformScreen과 동일한 패턴). hasConnectedOnce로
    // "화면 진입 직후 아직 연결 확인 전"인 상태와 구분함.
    LaunchedEffect(uiState.bleState, uiState.hasConnectedOnce) {
        if (uiState.hasConnectedOnce && uiState.bleState is BleState.Disconnected) {
            onDisconnected()
        }
    }

    ClassifyContent(
        uiState = uiState,
        channelIntensity = viewModel.channelIntensity,
        channelJitter = viewModel.channelJitter,
        channelHasSignal = viewModel.channelHasSignal,
        onRelearn = onRelearn,
    )
}

@Composable
private fun ClassifyContent(
    uiState: ClassifyUiState,
    channelIntensity: FloatArray,
    channelJitter: Array<FloatArray>,
    channelHasSignal: BooleanArray = BooleanArray(EMG_CHANNELS) { true },
    onRelearn: () -> Unit,
) {
    var frameCount by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            awaitFrame()
            frameCount++
        }
    }

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
            Text(
                text = "동작 인식",
                style = MaterialTheme.typography.headlineSmall,
                color = MandroPalette.White,
                modifier = Modifier.weight(1f),
            )
            ConnectionBadge(bleState = uiState.bleState)
        }

        // 인식된 동작 카드
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = MandroPalette.DarkSurf,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "지금 동작",
                    style = MaterialTheme.typography.labelMedium,
                    color = MandroPalette.Neutral500,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = uiState.gesture,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MandroPalette.White,
                )
                Text(
                    text = uiState.gestureKo,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MandroPalette.Neutral500,
                )
            }
        }

        // 레이더 차트
        // weight(1f)+fillMaxWidth()로 남는 공간을 채우게 해놓고 마지막에
        // wrapContentSize()를 붙이면 서로 모순돼서(Canvas는 그릴 자식이
        // 없어 "내용물 크기"가 사실상 0) 캔버스 크기가 0에 가깝게 찌그러짐
        // — 값 계산은 맞는데 그려지는 선 길이가 0이 되는 원인이었음.
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            @Suppress("UNUSED_EXPRESSION")
            frameCount

            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxRadius = minOf(size.width, size.height) * 0.42f

            // 기준 동심원 (3단계)
            for (i in 1..3) {
                drawCircle(
                    color = MandroPalette.DarkBorder,
                    radius = maxRadius * (i / 3f),
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // 중심점을 채널 선보다 먼저 그림 — 나중에 그리면 길이가 짧은(rest 근처)
            // 채널 선이 이 점(반지름 4dp) 밑에 완전히 가려지는 문제가 있었음.
            drawCircle(
                color = MandroPalette.White,
                radius = 4.dp.toPx(),
                center = Offset(cx, cy),
            )

            // 채널별 선 — raw EMG 스트리밍이 꺼져있으면(RAW_STREAM_TOGGLE.md) 이
            // 데이터 자체가 안 들어오므로 그냥 숨김(동심원/중심점만 남음). 길이는
            // 세기(channelIntensity, 느리게 스무딩됨), 선의 흔들림은 떨림(channelJitter,
            // 순간 편차 히스토리)으로 그려서 "세기"와 "노이즈"를 시각적으로 분리함.
            // 떨림은 "수직 거리"가 아니라 "각도" 변화로 표현해서, 중심에서 멀어져도
            // 옆 채널 부채꼴을 절대 침범하지 않도록 함(거리로 표현하면 멀어질수록
            // 옆으로 크게 새어나감).
            if (!uiState.rawStreamEnabled) return@Canvas
            CHANNEL_ANGLES_RAD.forEachIndexed { ch, angle ->
                val intensity = channelIntensity[ch].coerceIn(0f, 1f)
                val isActive = intensity >= LOW_SIGNAL_THRESHOLD

                // 길이 = "근육이 활성 상태인가"(baseline 대비 편차, channelIntensity).
                // rest에서 0에 가까운 게 정상 — 고정 최소값으로 채우지 않음(그러면
                // 진짜 미세한 떨림이 그 고정값에 묻혀서 안 보임, 2026-07-21 문제).
                // "신호가 살아있다"는 아래 path 그리기에서 jitter(실제 순간 편차) 크기에
                // 비례하는 반지름으로 표현 — 조용하면 거의 0, 진짜로 흔들리면 그만큼만.
                val length = maxRadius * (intensity * INTENSITY_GAIN).coerceIn(0f, 1f).pow(INTENSITY_GAMMA)
                // 색상/alpha = "센서가 실제로 데이터를 보내고 있는가"(raw 값 자체의
                // 흔들림, channelHasSignal — baseline 보정과 무관). 이걸 intensity와
                // 분리해야, rest처럼 intensity가 의도적으로 0에 가까운 상태에서도
                // "신호는 살아있다"를 정상 색으로 계속 보여줄 수 있음.
                val lineColor = if (channelHasSignal[ch]) {
                    MandroPalette.waveColors[ch]
                } else {
                    MandroPalette.waveColors[ch].copy(alpha = LOW_SIGNAL_ALPHA)
                }

                val angleDeviationRad = if (isActive) {
                    MAX_ANGLE_DEVIATION_RAD
                } else {
                    MAX_ANGLE_DEVIATION_RAD * STUB_ANGLE_DEVIATION_SCALE
                }
                val jitter = channelJitter[ch]
                val path = Path().apply {
                    moveTo(cx, cy)
                    for (i in jitter.indices) {
                        val t = (i + 1f) / jitter.size
                        val angleOffset = (jitter[i] / JITTER_CLAMP) * angleDeviationRad
                        val pointAngle = angle + angleOffset
                        // rest처럼 length가 0에 가까우면 각도 흔들림이 반지름 0에 곱해져
                        // 안 보이게 되므로, 그 지점의 실제 jitter 크기에 비례하는 반지름을
                        // 최소값으로 씀 — 고정 상수가 아니라 실측값 기반이라 조용하면
                        // 자동으로 0에 가까워짐(가짜 floor 아님).
                        val jitterRadius = if (channelHasSignal[ch]) {
                            maxRadius * JITTER_VISIBLE_RATIO * (kotlin.math.abs(jitter[i]) / JITTER_CLAMP).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val r = maxOf(length * t, jitterRadius)
                        lineTo(cx + cos(pointAngle) * r, cy + sin(pointAngle) * r)
                    }
                }

                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        // 하단 재학습 버튼
        TextButton(
            onClick = onRelearn,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                text = "인식이 잘 안 되나요?  →  다시 학습하기",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MandroPalette.Neutral500,
            )
        }
    }
}

// ── 프리뷰 ────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF0F1420)
@Composable
private fun ClassifyPreview() {
    MandroTheme {
        ClassifyContent(
            uiState = ClassifyUiState(gesture = "Flexion", gestureKo = "손목을 아래로 구부리기"),
            channelIntensity = floatArrayOf(0.15f, 0.20f, 0.75f, 0.80f, 0.70f, 0.18f, 0.12f, 0.14f),
            channelJitter = Array(8) { floatArrayOf(0.02f, -0.03f, 0.01f, -0.02f, 0.03f, -0.01f) },
            onRelearn = {},
        )
    }
}
