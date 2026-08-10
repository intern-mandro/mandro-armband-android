package com.mandro.domain.model

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,                      // 신호 세기 (dBm)
) {
    val signalStrength: SignalStrength
        get() = when {
            rssi >= -60 -> SignalStrength.STRONG
            rssi >= -80 -> SignalStrength.MODERATE
            else        -> SignalStrength.WEAK
        }

    val signalLabel: String
        get() = when (signalStrength) {
            SignalStrength.STRONG   -> "연결 상태 좋음"
            SignalStrength.MODERATE -> "연결 상태 보통"
            SignalStrength.WEAK     -> "연결 상태 약함"
        }
}

enum class SignalStrength { STRONG, MODERATE, WEAK }

sealed class BleState {
    object Disconnected : BleState()
    object Scanning : BleState()
    data class DevicesFound(val devices: List<BleDevice>) : BleState()
    data class Connecting(val device: BleDevice) : BleState()
    data class Connected(val device: BleDevice) : BleState()
    data class Error(val message: String) : BleState()
}
