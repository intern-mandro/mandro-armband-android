package com.mandro.presentation.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.presentation.theme.MandroPalette

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onGoHome: () -> Unit = {},
) {
    val rawStreamEnabled by viewModel.rawStreamEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MandroPalette.Neutral50)
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "설정",
            style = MaterialTheme.typography.headlineLarge,
            color = MandroPalette.Neutral900,
        )
        Spacer(Modifier.height(32.dp))

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
                        text = "원본 신호 수신",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MandroPalette.Neutral900,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "꺼두면 암밴드 전력 소모가 줄어요. 녹화 화면은 자동으로 켜져요.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MandroPalette.Neutral500,
                    )
                }
                Switch(
                    checked = rawStreamEnabled,
                    onCheckedChange = viewModel::onRawStreamEnabledChanged,
                    colors = SwitchDefaults.colors(checkedTrackColor = MandroPalette.Primary600),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Surface(
            onClick = { viewModel.onGoHome(onGoHome) },
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
                Text(
                    text = "다른 사용자로 전환",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MandroPalette.Neutral900,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
