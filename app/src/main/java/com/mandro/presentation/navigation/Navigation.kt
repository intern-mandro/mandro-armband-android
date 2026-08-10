package com.mandro.presentation.navigation

sealed class Screen(val route: String) {
    object Splash      : Screen("splash")
    object Home        : Screen("home")
    object UserCreate  : Screen("user/create")
    object BleScan     : Screen("ble/scan")

    // 메인 탭
    object Waveform    : Screen("main/waveform")
    object Training    : Screen("main/training")
    object Classify    : Screen("main/classify")
    object Settings    : Screen("main/settings")

    // 학습 플로우 (Training 탭 내 스텝)
    object Guide       : Screen("training/guide/{gestureIndex}") {
        fun createRoute(index: Int) = "training/guide/$index"
    }
    object Collect     : Screen("training/collect")
    object TrainingProgress : Screen("training/progress")
    object Firmware    : Screen("training/firmware")
}

// 바텀 내비게이션 탭
val BOTTOM_NAV_ITEMS = listOf(
    BottomNavItem(
        screen = Screen.Waveform,
        label  = "신호",
        icon   = "waveform",
    ),
    BottomNavItem(
        screen = Screen.Training,
        label  = "등록",
        icon   = "learning",
    ),
    BottomNavItem(
        screen = Screen.Classify,
        label  = "인식",
        icon   = "classify",
    ),
    BottomNavItem(
        screen = Screen.Settings,
        label  = "설정",
        icon   = "settings",
    ),
)

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: String,
)
