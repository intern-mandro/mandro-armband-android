package com.mandro.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mandro.domain.model.BleState
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme

// ── 버튼 ─────────────────────────────────────────────────────

@Composable
fun MandroPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MandroPalette.Primary600,
            contentColor   = MandroPalette.White,
            disabledContainerColor = MandroPalette.Neutral300,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun MandroSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MandroPalette.Neutral700,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

// ── 연결 상태 뱃지 ────────────────────────────────────────────

@Composable
fun ConnectionBadge(bleState: BleState) {
    val (label, color) = when (bleState) {
        is BleState.Connected    -> "● 연결됨"   to MandroPalette.Success600
        is BleState.Connecting   -> "● 연결 중..." to MandroPalette.Warning600
        is BleState.Scanning     -> "● 찾는 중..." to MandroPalette.Warning600
        else                     -> "● 연결 끊김"  to MandroPalette.Danger600
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

// ── 진행률 바 ─────────────────────────────────────────────────

@Composable
fun MandroProgressBar(
    progress: Float,        // 0.0 ~ 1.0
    modifier: Modifier = Modifier,
    color: Color = MandroPalette.Primary600,
) {
    LinearProgressIndicator(
        progress = progress,
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp),
        color = color,
        trackColor = MandroPalette.Neutral100,
    )
}

// ── 학습 단계 행 ──────────────────────────────────────────────

enum class StepState { DONE, ACTIVE, PENDING }

@Composable
fun TrainingStepRow(
    index: Int,
    label: String,
    subLabel: String,
    state: StepState,
    progress: Float? = null,    // ACTIVE일 때만 사용
) {
    val circleColor = when (state) {
        StepState.DONE    -> MandroPalette.Success600
        StepState.ACTIVE  -> MandroPalette.Primary600
        StepState.PENDING -> MandroPalette.Neutral100
    }
    val textColor = when (state) {
        StepState.DONE    -> MandroPalette.Neutral500
        StepState.ACTIVE  -> MandroPalette.Neutral900
        StepState.PENDING -> MandroPalette.Neutral300
    }

    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 원형 인덱스
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(circleColor, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = if (state == StepState.DONE) "✓" else index.toString(),
                color = if (state == StepState.PENDING) MandroPalette.Neutral500 else MandroPalette.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (state == StepState.ACTIVE)
                    androidx.compose.ui.text.font.FontWeight.SemiBold else
                    androidx.compose.ui.text.font.FontWeight.Normal,
                color = textColor,
            ))
            Text(subLabel, style = MaterialTheme.typography.labelSmall.copy(
                color = when (state) {
                    StepState.DONE   -> MandroPalette.Success600
                    StepState.ACTIVE -> MandroPalette.Primary600
                    StepState.PENDING -> MandroPalette.Neutral300
                }
            ))
            if (state == StepState.ACTIVE && progress != null) {
                Spacer(Modifier.height(8.dp))
                MandroProgressBar(progress)
            }
        }
    }
}

// ── 체크박스 동의 행 ──────────────────────────────────────────

@Composable
fun ConsentCheckboxRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    required: Boolean = false,
    onViewFullText: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MandroPalette.White,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = MandroPalette.Primary600,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "[${if (required) "필수" else "선택"}] $label",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = description,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MandroPalette.Neutral500,
                    ),
                )
                if (onViewFullText != null) {
                    TextButton(
                        onClick = onViewFullText,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            text  = "전문 보기 ›",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MandroPalette.Primary600,
                            ),
                        )
                    }
                }
            }
        }
    }
}

// ── 텍스트필드 ────────────────────────────────────────────────

@Composable
fun NameTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    errorMessage: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MandroPalette.Neutral700,
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MandroPalette.Neutral300,
                )
            },
            isError = errorMessage != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onAny = {
                    onImeAction()
                    defaultKeyboardAction(imeAction)
                },
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = MandroPalette.Primary600,
                unfocusedBorderColor = MandroPalette.Neutral300,
                errorBorderColor     = MandroPalette.Danger600,
                focusedTextColor     = MandroPalette.Neutral900,
                unfocusedTextColor   = MandroPalette.Neutral900,
                cursorColor          = MandroPalette.Primary600,
                focusedContainerColor   = MandroPalette.White,
                unfocusedContainerColor = MandroPalette.White,
            ),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (errorMessage != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MandroPalette.Danger600,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun NameTextFieldPreview() {
    MandroTheme {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            var name by remember { mutableStateOf("") }
            NameTextField(
                value = name,
                onValueChange = { name = it },
                label = "이름",
                placeholder = "이름을 입력해 주세요",
            )
            NameTextField(
                value = "HAERIM",
                onValueChange = {},
                label = "이름",
                placeholder = "이름을 입력해 주세요",
            )
            NameTextField(
                value = "",
                onValueChange = {},
                label = "이름",
                placeholder = "이름을 입력해 주세요",
                errorMessage = "이름을 입력해 주세요.",
            )
        }
    }
}
