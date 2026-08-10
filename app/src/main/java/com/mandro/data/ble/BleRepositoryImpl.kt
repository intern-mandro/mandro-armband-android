package com.mandro.data.ble

import com.mandro.core.ble.BleManager
import com.mandro.data.local.RawStreamPreferences
import com.mandro.domain.model.BleDevice
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EmgSample
import com.mandro.domain.model.InferenceResult
import com.mandro.domain.model.WeightTransferState
import com.mandro.domain.repository.BleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

class BleRepositoryImpl @Inject constructor(
    private val bleManager: BleManager,
    private val rawStreamPreferences: RawStreamPreferences,
) : BleRepository {

    // BleRepositoryImpl은 Hilt에서 @Singleton으로 제공되므로(BleModule), 이 스코프는
    // 앱 프로세스가 살아있는 동안 계속 유지됨 — ViewModel 생명주기와 무관하게 항상
    // "지금 raw 스트리밍 설정이 뭔지"를 감시해서 실제 BLE 구독에 반영해야 하기 때문.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val bleState: Flow<BleState> = bleManager.bleState
    override val emgStream: Flow<EmgSample> = bleManager.emgStream
    override val inferenceStream: Flow<InferenceResult> = bleManager.inferenceStream
    override val weightTransferState: Flow<WeightTransferState> = bleManager.weightTransferState

    init {
        // CCCD는 연결마다(재연결 포함) 초기화되므로, "연결됨" 상태가 될 때마다
        // 그리고 사용자가 설정을 바꿀 때마다 실제 raw EMG 구독 상태를 다시 맞춰줌
        // (RAW_STREAM_TOGGLE.md 옵션 A).
        scope.launch {
            combine(bleManager.bleState, rawStreamPreferences.enabled) { state, enabled -> state to enabled }
                .collect { (state, enabled) ->
                    if (state is BleState.Connected) {
                        bleManager.setRawEmgSubscribed(enabled)
                    }
                }
        }
    }

    override suspend fun startScan() = bleManager.startScan()
    override suspend fun stopScan() = bleManager.stopScan()
    override suspend fun connect(device: BleDevice) = bleManager.connect(device)
    override suspend fun disconnect() = bleManager.disconnect()
    override suspend fun sendWeights(weightsBytes: ByteArray): Result<Unit> =
        bleManager.sendWeights(weightsBytes)
}
