package com.mandro.presentation.ui.firmware

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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

    if (uiState.isDone) {
        onDone()
        return
    }

    FirmwareContent(
        uiState = uiState,
        onStartUpdate = viewModel::onStartUpdate,
        onConnectBand = onConnectBand,
    )
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
