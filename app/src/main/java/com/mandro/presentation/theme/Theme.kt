package com.mandro.presentation.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── 컬러 팔레트 ───────────────────────────────────────────────

object MandroPalette {
    // Primary (블루)
    val Primary600 = Color(0xFF1478F5)
    val Primary500 = Color(0xFF3D94FA)
    val Primary100 = Color(0xFFE1EFFF)
    val Primary50  = Color(0xFFF0F7FF)

    // Success
    val Success600 = Color(0xFF10B874)
    val Success100 = Color(0xFFDBF7EC)

    // Warning
    val Warning600 = Color(0xFFF29E12)
    val Warning100 = Color(0xFFFFF3D9)

    // Danger
    val Danger600  = Color(0xFFED4545)
    val Danger100  = Color(0xFFFFE5E5)

    // Neutral
    val Neutral900 = Color(0xFF141519)
    val Neutral700 = Color(0xFF383F4F)
    val Neutral500 = Color(0xFF707A8F)
    val Neutral300 = Color(0xFFC2C9D6)
    val Neutral100 = Color(0xFFEDF0F5)
    val Neutral50  = Color(0xFFF7F8FA)
    val White      = Color(0xFFFFFFFF)

    // Dark (파형/인식 화면 전용)
    val DarkBg     = Color(0xFF0F1420)
    val DarkSurf   = Color(0xFF1A2131)
    val DarkBorder = Color(0xFF2E384D)

    // 8채널 파형 색상 — 무지개 순서 (빨→주황→노랑→초록→시안→파랑→보라→
    // 핑크/자주 → 다시 빨강으로 자연스럽게 이어짐)
    val WaveCH0 = Color(0xFFE44444)  // 빨강
    val WaveCH1 = Color(0xFFED8C1D)  // 주황 (진하게 — 노랑과 헷갈리지 않도록 채도↑, 명도↓)
    val WaveCH2 = Color(0xFFE4DC44)  // 노랑
    val WaveCH3 = Color(0xFF44E46C)  // 초록
    val WaveCH4 = Color(0xFF44E4E4)  // 시안
    val WaveCH5 = Color(0xFF446CE4)  // 파랑
    val WaveCH6 = Color(0xFF9444E4)  // 보라
    val WaveCH7 = Color(0xFFE444BC)  // 핑크/자주

    val waveColors = listOf(WaveCH0, WaveCH1, WaveCH2, WaveCH3, WaveCH4, WaveCH5, WaveCH6, WaveCH7)
}

// ── Material3 컬러 스킴 ───────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary          = MandroPalette.Primary600,
    onPrimary        = MandroPalette.White,
    primaryContainer = MandroPalette.Primary100,
    onPrimaryContainer = MandroPalette.Primary600,
    background       = MandroPalette.Neutral50,
    onBackground     = MandroPalette.Neutral900,
    surface          = MandroPalette.White,
    onSurface        = MandroPalette.Neutral900,
    surfaceVariant   = MandroPalette.Neutral100,
    onSurfaceVariant = MandroPalette.Neutral700,
    error            = MandroPalette.Danger600,
    outline          = MandroPalette.Neutral300,
)

// ── 타이포그래피 ──────────────────────────────────────────────

val MandroTypography = Typography(
    headlineLarge = TextStyle(
        fontSize   = 28.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontSize   = 22.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontSize   = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontSize   = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontSize   = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
    ),
    labelLarge = TextStyle(
        fontSize   = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontSize   = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontSize   = 11.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
    ),
)

// ── 테마 컴포저블 ─────────────────────────────────────────────

@Composable
fun MandroTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography  = MandroTypography,
        content     = content,
    )
}
