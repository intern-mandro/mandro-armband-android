package com.mandro.domain.model

// 로봇 의수의 BLE MAC을 암밴드의 NVS 페어링 저장소에 등록하는 동안의 진행 상태.
// mandro-pc-app의 lib/ble_hand_pairing.py + client_app/pair_hand_screen.py 플로우를
// 안드로이드로 옮긴 것 — 연결 상태(BleState)와는 별개 관심사라 분리함.
sealed class HandPairingState {
    object Idle : HandPairingState()
    data class InProgress(val message: String) : HandPairingState()
    data class Success(val handMac: String) : HandPairingState()
    data class Error(val message: String) : HandPairingState()
}

// 로봇 의수 BLE 모듈이 현재 이 이름 접두사(대소문자 무관)로 광고함 —
// 모듈의 자체 이름(CHIPSEN)이고, "MARK7" 제품명이 아님. pc-app의
// lib.ble_hand_pairing.HAND_NAME_PREFIX와 동일.
const val DEFAULT_HAND_NAME_PREFIX = "chipsen"
