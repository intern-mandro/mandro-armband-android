package com.mandro.domain.model

sealed class UsbState {
    object Disconnected : UsbState()
    object DeviceDetected : UsbState()
    data class Flashing(val percent: Int) : UsbState()
    object Done : UsbState()
    data class Error(val message: String) : UsbState()
}
