package com.mandro.data.ble

import com.mandro.domain.model.BleDevice
import com.mandro.domain.model.BleState
import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.model.EmgSample
import com.mandro.domain.model.InferenceResult
import com.mandro.domain.model.WeightTransferState
import com.mandro.domain.repository.BleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sin
import kotlin.random.Random

@Singleton
class MockBleRepository @Inject constructor() : BleRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _bleState = MutableStateFlow<BleState>(BleState.Disconnected)
    override val bleState: Flow<BleState> = _bleState.asStateFlow()

    private val _emgStream = MutableSharedFlow<EmgSample>(
        replay = 0,
        extraBufferCapacity = 512,
    )
    override val emgStream: Flow<EmgSample> = _emgStream.asSharedFlow()

    private val _inferenceStream = MutableSharedFlow<InferenceResult>(replay = 1)
    override val inferenceStream: Flow<InferenceResult> = _inferenceStream.asSharedFlow()

    private val _weightTransferState = MutableStateFlow<WeightTransferState>(WeightTransferState.Idle)
    override val weightTransferState: Flow<WeightTransferState> = _weightTransferState.asStateFlow()

    private val mockDevices = listOf(
        BleDevice("ESP32S3_FAST_BLE", "00:11:22:33:44:55", -55),
        BleDevice("ESP32S3_FAST_BLE", "00:11:22:33:44:66", -72),
    )

    override suspend fun startScan() {
        _bleState.value = BleState.Scanning
        delay(1500L)
        _bleState.value = BleState.DevicesFound(mockDevices)
    }

    override suspend fun stopScan() {
        if (_bleState.value is BleState.Scanning) {
            _bleState.value = BleState.Disconnected
        }
    }

    override suspend fun connect(device: BleDevice) {
        _bleState.value = BleState.Connecting(device)
        delay(800L)
        _bleState.value = BleState.Connected(device)
        startMockEmgStream()
    }

    override suspend fun disconnect() {
        _bleState.value = BleState.Disconnected
    }

    override suspend fun sendWeights(weightsBytes: ByteArray): Result<Unit> {
        _weightTransferState.value = WeightTransferState.Sending(0)
        for (percent in 20..100 step 20) {
            delay(150L)
            _weightTransferState.value = WeightTransferState.Sending(percent)
        }
        _weightTransferState.value = WeightTransferState.Done
        return Result.success(Unit)
    }

    // 64Hz Notify 시뮬레이션 — 패킷당 20샘플, uint8 범위(0~255)
    private fun startMockEmgStream() {
        scope.launch {
            var t = 0.0
            while (_bleState.value is BleState.Connected) {
                repeat(20) { s ->
                    val channels = FloatArray(EMG_CHANNELS) { ch ->
                        val freq = 0.05 + ch * 0.015
                        val sine = sin((t + s) * freq + ch) * 100.0 + 127.5
                        val noise = (Random.nextFloat() - 0.5f) * 20f
                        (sine + noise).coerceIn(0.0, 255.0).toFloat()
                    }
                    _emgStream.tryEmit(EmgSample(channels = channels))
                }
                t += 20.0
                delay(15L) // ~64Hz
            }
        }

        // 20Hz 추론 결과 시뮬레이션
        scope.launch {
            val gestures = InferenceResult.GESTURE_NAMES
            var i = 0
            while (_bleState.value is BleState.Connected) {
                val probs = FloatArray(6) { if (it == i % 6) 0.85f else 0.03f }
                _inferenceStream.tryEmit(InferenceResult(gestures[i % 6], probs))
                i++
                delay(50L) // ~20Hz
            }
        }
    }
}
