package com.mandro.presentation.ui.user

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.presentation.components.ConsentCheckboxRow
import com.mandro.presentation.components.MandroPrimaryButton
import com.mandro.presentation.components.NameTextField
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme

@Composable
fun UserScreen(
    viewModel: UserCreateViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onStart: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.navigateNext.collect { onStart() }
    }

    UserContent(
        uiState = uiState,
        onBack = onBack,
        onNameChange = viewModel::onNameChange,
        onConsentPrivacyChange = viewModel::onConsentPrivacyChange,
        onConsentResearchChange = viewModel::onConsentResearchChange,
        onStart = viewModel::onStartClick,
    )
}

@Composable
private fun UserContent(
    uiState: UserCreateUiState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onConsentPrivacyChange: (Boolean) -> Unit,
    onConsentResearchChange: (Boolean) -> Unit,
    onStart: () -> Unit,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 타이틀
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "처음 오셨나요?",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MandroPalette.Neutral900,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "이름을 입력하면 나만의 설정을 만드로 드려요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MandroPalette.Neutral500,
                    )
                    Spacer(Modifier.height(24.dp))
                }

                // 이름 입력
                item {
                    NameTextField(
                        value = uiState.name,
                        onValueChange = onNameChange,
                        placeholder = "이름을 입력해주세요",
                        errorMessage = uiState.error,
                    )
                    Spacer(Modifier.height(32.dp))
                }

                // 약관 동의 섹션
                item {
                    Text(
                        text = "약관 동의",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MandroPalette.Neutral900,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                item {
                    ConsentCheckboxRow(
                        label = "개인정보 처리방침 동의",
                        description = "내 동작 데이터로 개인 설정을 만들어요",
                        checked = uiState.consentPrivacy,
                        onCheckedChange = onConsentPrivacyChange,
                        required = true,
                        onViewFullText = {},
                    )
                }

                item {
                    ConsentCheckboxRow(
                        label = "연구 참여 동의",
                        description = "더 나은 인식을 위한 연구에 참여할게요",
                        checked = uiState.consentResearch,
                        onCheckedChange = onConsentResearchChange,
                        required = false,
                        onViewFullText = {},
                    )
                }

                // 선택 동의 안내 카드
                item {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MandroPalette.Warning100,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "선택 동의를 거부해도 모든 기능을 사용할 수 있어요",
                            style = MaterialTheme.typography.labelSmall,
                            color = MandroPalette.Warning600,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            // 하단 고정 버튼
            MandroPrimaryButton(
                text = "시작하기",
                onClick = onStart,
                enabled = uiState.isStartEnabled,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

// ── 프리뷰 ────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun UserScreenPreview_Empty() {
    MandroTheme {
        UserContent(
            uiState = UserCreateUiState(),
            onBack = {},
            onNameChange = {},
            onConsentPrivacyChange = {},
            onConsentResearchChange = {},
            onStart = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun UserScreenPreview_Filled() {
    MandroTheme {
        UserContent(
            uiState = UserCreateUiState(
                name = "HAERIM",
                consentPrivacy = true,
                consentResearch = false,
            ),
            onBack = {},
            onNameChange = {},
            onConsentPrivacyChange = {},
            onConsentResearchChange = {},
            onStart = {},
        )
    }
}
