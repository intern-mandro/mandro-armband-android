package com.mandro.presentation.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.domain.model.BleState
import com.mandro.domain.model.HandPairingState
import com.mandro.presentation.theme.MandroPalette
import kotlinx.coroutines.delay

// 등록 완료 배너를 이만큼 보여준 뒤 자동으로 닫음.
private const val SUCCESS_BANNER_VISIBLE_MS = 3_000L

// 등록 완료 배너 전용 색 — MandroPalette.Success100/600(앱 전역에서 공유하는 토큰,
// Collect/Home/Firmware 등에서도 씀)은 건드리지 않고, 이 화면에서만 쓰는 차분한
// 톤으로 따로 둠. 흔한 "AI 툴" 느낌의 채도 높은 민트그린 대신 톤 낮춘 세이지그린.
private val PairedBannerBg = Color(0xFFEAF2EC)
private val PairedBannerFg = Color(0xFF3D6B52)

private val HAND_PAIRING_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

@Composable
fun HandPairingScreen(
    viewModel: HandPairingViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onConnectArmband: () -> Unit = {},
) {
    val armbandState by viewModel.armbandState.collectAsStateWithLifecycle()
    val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()
    val storedMac by viewModel.storedMac.collectAsStateWithLifecycle()
    val storedMacDisplay by viewModel.storedMacDisplay.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val isArmbandConnected = armbandState is BleState.Connected
    val armbandDeviceName = (armbandState as? BleState.Connected)?.device?.name

    var handNameInput by remember { mutableStateOf("") }
    var permissionDenied by remember { mutableStateOf(false) }
    var pendingPair by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            permissionDenied = false
            if (pendingPair) {
                pendingPair = false
                viewModel.onPairClick(handNameInput)
            }
        } else {
            permissionDenied = true
            pendingPair = false
        }
    }

    val isPairing = pairingState is HandPairingState.InProgress

    // 등록 완료 배너는 잠깐 보여주고 자동으로 닫음 — 계속 떠있으면 아래 삭제
    // 버튼 위치가 계속 밀려있는 상태로 남아서.
    var showSuccessBanner by remember { mutableStateOf(false) }
    LaunchedEffect(pairingState) {
        if (pairingState is HandPairingState.Success) {
            showSuccessBanner = true
            delay(SUCCESS_BANNER_VISIBLE_MS)
            showSuccessBanner = false
        } else {
            showSuccessBanner = false
        }
    }

    Scaffold(
        containerColor = MandroPalette.Neutral50,
        topBar = {
            IconButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "뒤로",
                    tint = MandroPalette.Neutral700,
                )
            }
        },
        bottomBar = {
            // 위쪽 스크롤 영역 내용(성공 배너, 에러 메시지 등)이 늘었다 줄었다 해도
            // 이 버튼은 화면 하단에 고정 — bottomBar라 스크롤 컨텐츠와 별개로 위치가
            // 안 움직임.
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                TextButton(
                    onClick = viewModel::onClearClick,
                    enabled = storedMac != null && !isPairing && isArmbandConnected,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "등록된 로봇의수 삭제",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MandroPalette.Neutral500,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "로봇의수 페어링",
                style = MaterialTheme.typography.headlineLarge,
                color = MandroPalette.Neutral900,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "로봇의수의 BLE 주소를 암밴드에 등록해두면, 폰이 연결을 끊어도 " +
                    "암밴드가 로봇의수에 자동으로 재연결해요. 암밴드와 로봇의수 모두 " +
                    "전원이 켜져 있고 가까이 있어야 해요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MandroPalette.Neutral500,
            )
            Spacer(Modifier.height(28.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MandroPalette.White,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "암밴드",
                            style = MaterialTheme.typography.labelMedium,
                            color = MandroPalette.Neutral500,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (isArmbandConnected) (armbandDeviceName ?: "연결됨") else "연결 안 됨",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MandroPalette.Neutral900,
                        )
                    }
                    if (isArmbandConnected) {
                        OutlinedButton(
                            onClick = viewModel::onDisconnectArmband,
                            enabled = !isPairing,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("연결 해제")
                        }
                    } else {
                        Button(
                            onClick = onConnectArmband,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MandroPalette.Primary600),
                        ) {
                            Text("연결하기")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MandroPalette.White,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "등록된 로봇의수",
                        style = MaterialTheme.typography.labelMedium,
                        color = MandroPalette.Neutral500,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when {
                            !isArmbandConnected -> "암밴드에 연결하면 확인할 수 있어요"
                            checking -> "확인하는 중..."
                            storedMacDisplay != null -> storedMacDisplay!!
                            else -> "등록된 로봇의수가 없어요"
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MandroPalette.Neutral900,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 로봇의수 BLE 모듈 이름(접두사) — pc-app의 "Hand device name" 입력창과 동일.
            // 기본값(chipsen)이 아니라 다른 모듈로 바뀐 경우를 대비해 직접 지정 가능.
            OutlinedTextField(
                value = handNameInput,
                onValueChange = { handNameInput = it },
                label = { Text("기기 이름") },
                singleLine = true,
                enabled = !isPairing && isArmbandConnected,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    pendingPair = true
                    permissionLauncher.launch(HAND_PAIRING_PERMISSIONS)
                },
                enabled = !isPairing && isArmbandConnected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MandroPalette.Primary600,
                    contentColor = MandroPalette.White,
                    disabledContainerColor = MandroPalette.Neutral300,
                ),
            ) {
                if (isPairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MandroPalette.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = if (storedMac != null) "다시 검색하고 등록" else "로봇의수 검색 및 등록",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            // 스캔·연결이 여러 초 걸리는 동안 "멈춘 것처럼" 보이지 않도록, 단계가
            // 바뀔 때마다 이 한 줄이 갱신됨.
            if (isPairing) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = (pairingState as HandPairingState.InProgress).message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MandroPalette.Neutral500,
                )
            }

            if (showSuccessBanner && pairingState is HandPairingState.Success) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PairedBannerBg,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = PairedBannerFg,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "로봇의수가 암밴드에 등록됐어요",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = PairedBannerFg,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = (pairingState as HandPairingState.Success).let {
                                    "${it.handName}(${it.handMac})"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = PairedBannerFg,
                            )
                        }
                    }
                }
            }

            if (permissionDenied) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "블루투스 권한이 필요해요. 설정에서 권한을 허용한 뒤 다시 시도해 주세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MandroPalette.Danger600,
                )
            }

            errorMessage?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MandroPalette.Danger600,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
