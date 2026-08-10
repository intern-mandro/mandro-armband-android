package com.mandro.domain.model

// BLE로 학습된 가중치(weights.bin)를 암밴드에 전송하는 진행 상태.
// 연결 상태(BleState)와는 별개 관심사라 분리함.
sealed class WeightTransferState {
    object Idle : WeightTransferState()
    data class Sending(val percent: Int) : WeightTransferState()
    object Done : WeightTransferState()
    data class Error(val message: String) : WeightTransferState()
}
