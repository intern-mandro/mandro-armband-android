package com.mandro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mandro.presentation.navigation.BOTTOM_NAV_ITEMS
import com.mandro.presentation.navigation.Screen
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme
import com.mandro.presentation.ui.ble.BleScreen
import com.mandro.presentation.ui.guide.GuideScreen
import com.mandro.presentation.ui.home.HomeScreen
import com.mandro.presentation.ui.splash.SplashScreen
import com.mandro.presentation.ui.user.UserScreen
import com.mandro.presentation.ui.classify.ClassifyScreen
import com.mandro.presentation.ui.collect.CollectScreen
import com.mandro.presentation.ui.firmware.FirmwareScreen
import com.mandro.presentation.ui.settings.SettingsScreen
import com.mandro.presentation.ui.training.TrainingProgressScreen
import com.mandro.presentation.ui.waveform.WaveformScreen
import dagger.hilt.android.AndroidEntryPoint

// 바텀 네비가 표시될 화면 목록
private val BOTTOM_NAV_ROUTES = setOf(
    Screen.Waveform.route,
    Screen.Training.route,
    Screen.Classify.route,
    Screen.Settings.route,
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MandroTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MandroPalette.Neutral50,
                    bottomBar = {
                        if (currentRoute in BOTTOM_NAV_ROUTES) {
                            NavigationBar(
                                containerColor = MandroPalette.White,
                                tonalElevation = 0.dp,
                            ) {
                                BOTTOM_NAV_ITEMS.forEachIndexed { index, item ->
                                    val selected = currentRoute == item.screen.route
                                    val icon = when (index) {
                                        0 -> Icons.Filled.Timeline
                                        1 -> Icons.Filled.Home
                                        2 -> Icons.Filled.Search
                                        else -> Icons.Filled.Settings
                                    }
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            navController.navigate(item.screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = item.label,
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = item.label,
                                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MandroPalette.Primary600,
                                            selectedTextColor = MandroPalette.Primary600,
                                            unselectedIconColor = MandroPalette.Neutral500,
                                            unselectedTextColor = MandroPalette.Neutral500,
                                            indicatorColor = MandroPalette.Primary50,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Splash.route,
                        modifier = Modifier.padding(padding),
                    ) {
                        composable(Screen.Splash.route) {
                            SplashScreen(navController)
                        }
                        composable(Screen.Home.route) {
                            HomeScreen(
                                onUserSelected = {
                                    navController.navigate(Screen.BleScan.route)
                                },
                                onAddUser = {
                                    navController.navigate(Screen.UserCreate.route)
                                },
                                onConnectBand = {
                                    navController.navigate(Screen.BleScan.route)
                                },
                                onResendWeights = {
                                    navController.navigate(Screen.Firmware.route)
                                },
                            )
                        }
                        composable(Screen.UserCreate.route) {
                            UserScreen(
                                onBack = { navController.popBackStack() },
                                onStart = { navController.navigate(Screen.BleScan.route) },
                            )
                        }
                        composable(Screen.BleScan.route) {
                            BleScreen(
                                onBack = { navController.popBackStack() },
                                onConnected = {
                                    // Firmware(가중치 재전송 지름길)에서 연결하러 온 거면
                                    // 다시 Firmware로 돌아감. 그 외(홈/유저생성 등에서 온
                                    // 최초 연결)에는 기존처럼 파형 화면으로 진행.
                                    if (navController.previousBackStackEntry?.destination?.route == Screen.Firmware.route) {
                                        navController.popBackStack()
                                    } else {
                                        navController.navigate(Screen.Waveform.route)
                                    }
                                },
                            )
                        }
                        composable(Screen.Waveform.route) {
                            WaveformScreen(
                                onDisconnected = {
                                    navController.navigate(Screen.BleScan.route) {
                                        popUpTo(Screen.BleScan.route) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Screen.Training.route) {
                            GuideScreen(
                                onBack = { navController.popBackStack() },
                                onStartRecord = {
                                    navController.navigate(Screen.Collect.route)
                                },
                                onStartTrainingDirectly = {
                                    navController.navigate(Screen.TrainingProgress.route) {
                                        popUpTo(Screen.Training.route)
                                    }
                                },
                            )
                        }
                        composable(Screen.Classify.route) {
                            ClassifyScreen(
                                onRelearn = {
                                    navController.navigate(Screen.Training.route)
                                },
                                onDisconnected = {
                                    navController.navigate(Screen.BleScan.route) {
                                        popUpTo(Screen.BleScan.route) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(Screen.Settings.route) {
                            SettingsScreen(
                                onGoHome = {
                                    navController.navigate(Screen.Home.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            inclusive = true
                                        }
                                    }
                                },
                            )
                        }
                        composable(Screen.Guide.route) {
                            GuideScreen(
                                onBack = { navController.popBackStack() },
                                onStartRecord = {
                                    navController.navigate(Screen.Collect.route)
                                },
                                onStartTrainingDirectly = {
                                    navController.navigate(Screen.TrainingProgress.route) {
                                        popUpTo(Screen.Training.route)
                                    }
                                },
                            )
                        }
                        composable(Screen.Collect.route) {
                            CollectScreen(
                                onDone = {
                                    navController.navigate(Screen.TrainingProgress.route) {
                                        popUpTo(Screen.Training.route)
                                    }
                                },
                            )
                        }
                        composable(Screen.TrainingProgress.route) {
                            TrainingProgressScreen(
                                onDone = {
                                    navController.navigate(Screen.Firmware.route) {
                                        popUpTo(Screen.Training.route)
                                    }
                                },
                            )
                        }
                        composable(Screen.Firmware.route) {
                            FirmwareScreen(
                                onDone = {
                                    // Firmware는 정상 학습 플로우(Training 경유)뿐 아니라 홈의
                                    // "가중치 재전송" 지름길로도 올 수 있음. Screen.Training도
                                    // 그래프 시작점(Splash, 진입 즉시 자기 자신을 백스택에서
                                    // 지움)도 지름길 경로에선 백스택에 없을 수 있어서 둘 다
                                    // popUpTo 타깃으로 못 씀 — 실제 백스택을 직접 확인해서
                                    // Training이 있으면 거기까지 정리, 없으면 Firmware
                                    // 자기 자신만 지움 (Firmware는 지금 이 화면이라 항상
                                    // 백스택에 존재가 보장됨).
                                    val hasTrainingInStack = navController.currentBackStack.value.any {
                                        it.destination.route == Screen.Training.route
                                    }
                                    navController.navigate(Screen.Classify.route) {
                                        if (hasTrainingInStack) {
                                            popUpTo(Screen.Training.route)
                                        } else {
                                            popUpTo(Screen.Firmware.route) { inclusive = true }
                                        }
                                    }
                                },
                                onConnectBand = {
                                    navController.navigate(Screen.BleScan.route)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
