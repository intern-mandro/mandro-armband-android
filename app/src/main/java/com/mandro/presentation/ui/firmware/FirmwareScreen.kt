package com.mandro.presentation.ui.firmware

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.presentation.components.MandroPrimaryButton
import com.mandro.presentation.components.MandroSecondaryButton
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme

@Composable
fun FirmwareScreen(
    viewModel: FirmwareViewModel = hiltViewModel(),
    onDone: () -> Unit = {},
    onConnectBand: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 업데이트가 끝나면 암밴드가 스스로 재부팅해서 연결이 끊김(BleManager 참고).
    // isBleConnected를 곧바로 보면 "아직 안 끊긴 옛 연결"을 재연결로 착각해 인식
    // 탭으로 갔다가 뒤늦게 끊김이 감지돼 BLE 탐색 탭으로 다시 튕기는 문제가 있어서,
    // "실제로 한 번 끊겼다가 재연결됨"이 확인된 isReconnectedAfterUpdate만 본다.
    LaunchedEffect(uiState.isReconnectedAfterUpdate) {
        if (uiState.isReconnectedAfterUpdate) {
            onDone()
        }
    }

    if (uiState.isDone) {
        FirmwareDoneContent(
            isBleConnected = uiState.isBleConnected,
            onConnectBand = onConnectBand,
        )
        return
    }

    FirmwareContent(
        uiState = uiState,
        onStartUpdate = viewModel::onStartUpdate,
        onConnectBand = onConnectBand,
    )
}

// 업데이트 완료 화면 — 예전엔 isDone이 되자마자 아무것도 안 그리고 바로
// onDone()을 호출해서 넘어가버려 "완료됐다"는 문구를 볼 새가 없었음. 이제는 완료
// 상태를 실제로 렌더링하고, 암밴드 재부팅으로 끊긴 연결을 다시 잡도록 안내함 —
// 재연결되면 위 LaunchedEffect가 자동으로 다음 화면으로 넘김(버튼 없음).
@Composable
private fun FirmwareDoneContent(
    isBleConnected: Boolean,
    onConnectBand: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MandroPalette.Neutral50)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MandroPalette.Success600,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "업데이트가 완료됐어요",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MandroPalette.Neutral900,
        )
        Spacer(Modifier.height(8.dp))
        if (isBleConnected) {
            // 큰 체크 아이콘(완료)과 큰 스피너가 한 화면에 같이 있으면 "다 된 건지
            // 아직 기다리는 건지" 헷갈려서, 로딩은 이 텍스트 옆에 작게만 붙임 —
            // "업데이트는 이미 끝났고, 재연결 확인만 남았다"는 걸 명확히 구분.
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = MandroPalette.Primary600,
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "암밴드를 찾는 중...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MandroPalette.Neutral500,
                )
            }
        } else {
            Text(
                text = "암밴드가 재시작됐어요. 다시 연결해 주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MandroPalette.Neutral500,
            )
        }
        Spacer(Modifier.height(32.dp))
        if (!isBleConnected) {
            MandroPrimaryButton(
                text = "암밴드 연결하기",
                onClick = onConnectBand,
            )
        }
    }
}

@Composable
private fun FirmwareContent(
    uiState: FirmwareUiState,
    onStartUpdate: () -> Unit = {},
    onConnectBand: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MandroPalette.Neutral50)
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = "암밴드 업데이트",
            style = MaterialTheme.typography.headlineLarge,
            color = MandroPalette.Neutral900,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "내 설정을 암밴드에 저장할게요",
            style = MaterialTheme.typography.bodyMedium,
            color = MandroPalette.Neutral500,
        )

        Spacer(Modifier.height(24.dp))

        // USB 연결 일러스트 영역
        // TODO: 실제 일러스트/이미지로 교체
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(MandroPalette.Neutral100, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.isUpdating) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = MandroPalette.Primary600,
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "업데이트 중...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MandroPalette.Neutral500,
                    )
                }
            } else {
                Text(
                    text = "블루투스로 암밴드에 연결해 주세요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MandroPalette.Neutral300,
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        // 연결 확인 섹션
        Text(
            text = "연결 확인",
            style = MaterialTheme.typography.headlineSmall,
            color = MandroPalette.Neutral900,
        )
        Spacer(Modifier.height(8.dp))

        uiState.checks.forEach { check ->
            CheckRow(check = check)
            HorizontalDivider(color = MandroPalette.Neutral100, thickness = 1.dp)
        }

        // 모두 확인 완료 메시지
        AnimatedVisibility(
            visible = uiState.allChecked && !uiState.isUpdating,
            enter = fadeIn() + slideInVertically { it / 2 },
        ) {
            Column {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "모두 확인했어요! 업데이트를 시작할게요",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MandroPalette.Primary600,
                )
            }
        }

        // 에러가 나면 isUpdating은 false로 돌아가고 isDone은 안 되는데(FirmwareViewModel
        // 참고), 예전엔 이 상태를 화면에 그리는 코드가 아예 없어서 "아무 반응 없이
        // 조용히 멈춘 것"처럼 보였음.
        uiState.error?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MandroPalette.Danger600,
            )
        }

        Spacer(Modifier.weight(1f))

        // 버튼 — 암밴드 연결이 안 돼있으면 업데이트 대신 연결부터 하도록 안내
        // (녹화/학습 없이 저장된 모델을 바로 재전송할 때는 BLE 연결이 안 맺어져
        // 있는 상태로 이 화면에 곧바로 올 수 있어서 필요함)
        if (!uiState.isBleConnected) {
            MandroSecondaryButton(
                text = "암밴드 연결하기",
                onClick = onConnectBand,
            )
            Spacer(Modifier.height(12.dp))
        }
        MandroPrimaryButton(
            text = "업데이트 시작",
            onClick = onStartUpdate,
            enabled = uiState.isUpdateEnabled && !uiState.isUpdating,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CheckRow(check: FirmwareCheck) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = check.state == CheckState.DONE,
            enter = fadeIn(),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MandroPalette.Success600,
                modifier = Modifier.size(18.dp),
            )
        }
        if (check.state != CheckState.DONE) {
            Spacer(Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = check.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (check.state == CheckState.DONE) MandroPalette.Neutral900 else MandroPalette.Neutral300,
        )
    }
}

// ── 프리뷰 ────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun FirmwarePreview_AllChecked() {
    MandroTheme {
        FirmwareContent(
            uiState = FirmwareUiState(
                checks = listOf(
                    FirmwareCheck("암밴드 연결됨", CheckState.DONE),
                    FirmwareCheck("내 설정 준비됨", CheckState.DONE),
                ),
                isUpdateEnabled = true,
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun FirmwarePreview_Updating() {
    MandroTheme {
        FirmwareContent(
            uiState = FirmwareUiState(
                checks = listOf(
                    FirmwareCheck("암밴드 연결됨", CheckState.DONE),
                    FirmwareCheck("내 설정 준비됨", CheckState.DONE),
                ),
                isUpdateEnabled = true,
                isUpdating = true,
            )
        )
    }
}
