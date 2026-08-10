package com.mandro.presentation.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mandro.presentation.navigation.Screen
import com.mandro.presentation.theme.MandroPalette
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(navController: NavController) {

    // 애니메이션 투명도 관리
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 600),
        )
        delay(2000L)
        // 홈으로 이동
        navController.navigate(Screen.Home.route) {
            popUpTo(Screen.Splash.route) { inclusive = true }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MandroPalette.Primary600),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(alpha.value),
        ) {
            Text(
                text = "mandro",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MandroPalette.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "내 동작을 기억하는 암밴드",
                fontSize = 14.sp,
                color = MandroPalette.White.copy(alpha = 0.7f)
            )
        }
    }
}