package com.mandro.domain.model

// 로봇의수의 BLE MAC을 암밴드의 NVS 페어링 저장소에 등록하는 동안의 진행 상태.
// mandro-pc-app의 lib/ble_hand_pairing.py + client_app/pair_hand_screen.py 플로우를
// 안드로이드로 옮긴 것 — 연결 상태(BleState)와는 별개 관심사라 분리함.
sealed class HandPairingState {
    object Idle : HandPairingState()
    data class InProgress(val message: String) : HandPairingState()
    // handName: 스캔 중 실제로 광고받은 기기 이름(예: "CHIPSEN"). 암밴드 NVS엔 MAC만
    // 저장되므로, 화면을 나중에 다시 열었을 때 이 이름을 보여주려면 로컬에 따로
    // 기억해둬야 함 — HandDevicePreferences 참고.
    data class Success(val handMac: String, val handName: String) : HandPairingState()
    data class Error(val message: String) : HandPairingState()
}

// 로봇의수 BLE 모듈이 현재 이 이름 접두사(대소문자 무관)로 광고함 —
// 모듈의 자체 이름(CHIPSEN)이고, "MARK7" 제품명이 아님. pc-app의
// lib.ble_hand_pairing.HAND_NAME_PREFIX와 동일.
const val DEFAULT_HAND_NAME_PREFIX = "chipsen"
