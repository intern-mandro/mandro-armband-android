package com.mandro.presentation.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.domain.model.HandPairingState
import com.mandro.presentation.theme.MandroPalette

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
) {
    val pairingState by viewModel.pairingState.collectAsStateWithLifecycle()
    val storedMac by viewModel.storedMac.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var permissionDenied by remember { mutableStateOf(false) }
    var pendingPair by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            permissionDenied = false
            if (pendingPair) {
                pendingPair = false
                viewModel.onPairClick()
            }
        } else {
            permissionDenied = true
            pendingPair = false
        }
    }

    val isPairing = pairingState is HandPairingState.InProgress

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
                text = "로봇 의수 페어링",
                style = MaterialTheme.typography.headlineLarge,
                color = MandroPalette.Neutral900,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "로봇 의수의 BLE 주소를 암밴드에 등록해두면, 폰이 연결을 끊어도 " +
                    "암밴드가 로봇 의수에 자동으로 재연결해요. 암밴드와 로봇 의수 모두 " +
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
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "등록된 로봇 의수",
                        style = MaterialTheme.typography.labelMedium,
                        color = MandroPalette.Neutral500,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when {
                            checking -> "확인하는 중..."
                            storedMac != null -> storedMac!!
                            else -> "등록된 로봇 의수가 없어요"
                        },
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MandroPalette.Neutral900,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    pendingPair = true
                    permissionLauncher.launch(HAND_PAIRING_PERMISSIONS)
                },
                enabled = !isPairing,
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
                        text = if (storedMac != null) "다시 검색하고 등록" else "로봇 의수 검색 및 등록",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (isPairing) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = (pairingState as HandPairingState.InProgress).message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MandroPalette.Neutral500,
                )
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

            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = viewModel::onClearClick,
                enabled = storedMac != null && !isPairing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "등록된 로봇 의수 삭제",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MandroPalette.Neutral500,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
