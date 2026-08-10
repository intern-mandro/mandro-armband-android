package com.mandro.presentation.ui.ble

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.domain.model.BleDevice
import com.mandro.domain.model.BleState
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme

private val BLE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

@Composable
fun BleScreen(
    viewModel: BleViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onConnected: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var permissionDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            permissionDenied = false
            viewModel.onRescan()
        } else {
            permissionDenied = true
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(BLE_PERMISSIONS)
    }

    LaunchedEffect(uiState.bleState) {
        if (uiState.bleState is BleState.Connected) onConnected()
    }

    if (permissionDenied) {
        PermissionDeniedContent(
            onBack = onBack,
            onRetry = { permissionLauncher.launch(BLE_PERMISSIONS) },
        )
    } else {
        BleContent(
            uiState = uiState,
            onBack = onBack,
            onConnectClick = viewModel::onConnectClick,
            onRescan = viewModel::onRescan,
        )
    }
}

@Composable
private fun PermissionDeniedContent(
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        containerColor = MandroPalette.Neutral50,
        topBar = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로",
                    tint = MandroPalette.Neutral700,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "암밴드 연결 권한이 필요해요",
                style = MaterialTheme.typography.headlineSmall,
                color = MandroPalette.Neutral900,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "블루투스 권한을 허용해야 암밴드와 연결할 수 있어요.\n설정에서 권한을 허용한 뒤 다시 시도해 주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MandroPalette.Neutral500,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MandroPalette.Primary600),
            ) {
                Text("권한 다시 요청", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun BleContent(
    uiState: BleUiState,
    onBack: () -> Unit,
    onConnectClick: (BleDevice) -> Unit,
    onRescan: () -> Unit,
) {
    val isScanning = uiState.bleState is BleState.Scanning
    val devices = (uiState.bleState as? BleState.DevicesFound)?.devices ?: emptyList()
    val connectingDevice = (uiState.bleState as? BleState.Connecting)?.device

    Scaffold(
        containerColor = MandroPalette.Neutral50,
        topBar = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로",
                    tint = MandroPalette.Neutral700,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 타이틀
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "암밴드 연결",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MandroPalette.Neutral900,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (uiState.bleState) {
                        is BleState.Scanning      -> "근처의 암밴드를 찾고 있어요"
                        is BleState.DevicesFound  -> "연결할 암밴드를 선택해 주세요"
                        is BleState.Connecting    -> "암밴드에 연결하고 있어요"
                        is BleState.Connected     -> "연결됐어요!"
                        else                      -> "근처의 암밴드를 찾고 있어요"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MandroPalette.Neutral500,
                )
                Spacer(Modifier.height(50.dp))
            }

            // 펄스 애니메이션
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    PulsingCircle(isScanning = isScanning, isConnecting = connectingDevice != null)
                }
                Spacer(Modifier.height(32.dp))
            }

            // 기기 목록 섹션
            if (devices.isNotEmpty()) {
                item {
                    Text(
                        text = "근처에서 찾은 암밴드",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MandroPalette.Neutral900,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                items(devices) { device ->
                    DeviceCard(
                        device = device,
                        isConnecting = connectingDevice?.address == device.address,
                        onConnectClick = { onConnectClick(device) },
                    )
                }
            }

            // 다시 찾기
            item {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onRescan,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "다시 찾기 ↺",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MandroPalette.Primary600,
                    )
                }
            }
        }
    }
}

@Composable
private fun PulsingCircle(isScanning: Boolean, isConnecting: Boolean) {
    val isActive = isScanning || isConnecting
    // 3개의 파동이 순차적으로 퍼져나감
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val delays = listOf(0, 400, 800)
    val scales = delays.map { delay ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 2000,
                    easing = EaseOut,
                    delayMillis = delay,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "scale_$delay",
        )
    }
    val alphas = delays.map { delay ->
        infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 2000,
                    easing = EaseOut,
                    delayMillis = delay,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "alpha_$delay",
        )
    }

    Box(contentAlignment = Alignment.Center) {
        // 파동 원들
        if (isActive) {
            scales.forEachIndexed { i, scale ->
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .scale(scale.value)
                        .background(
                            color = MandroPalette.Primary500.copy(alpha = alphas[i].value),
                            shape = CircleShape,
                        ),
                )
            }
        }

        // 중앙 원
        Box(
            modifier = Modifier
                .size(110.dp)
                .background(
                    color = MandroPalette.Primary100,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    isConnecting -> "연결 중..."
                    isScanning -> "찾는 중..."
                    else -> "대기 중"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MandroPalette.Primary600,
            )
        }
    }
}

@Composable
private fun DeviceCard(
    device: BleDevice,
    isConnecting: Boolean,
    onConnectClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MandroPalette.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MandroPalette.Neutral900,
                )
            }
            Button(
                onClick = onConnectClick,
                enabled = !isConnecting,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MandroPalette.Primary600,
                    contentColor = MandroPalette.White,
                    disabledContainerColor = MandroPalette.Neutral300,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MandroPalette.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("연결", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ── 프리뷰 ────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun BlePreview_Scanning() {
    MandroTheme {
        BleContent(
            uiState = BleUiState(bleState = BleState.Scanning),
            onBack = {},
            onConnectClick = {},
            onRescan = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun BlePreview_DevicesFound() {
    MandroTheme {
        BleContent(
            uiState = BleUiState(
                bleState = BleState.DevicesFound(
                    listOf(
                        BleDevice("EMG-Sensor-A4F2", "00:11:22:33:44:55", -55),
                        BleDevice("EMG-Sensor-B3C1", "00:11:22:33:44:66", -82),
                    )
                )
            ),
            onBack = {},
            onConnectClick = {},
            onRescan = {},
        )
    }
}
