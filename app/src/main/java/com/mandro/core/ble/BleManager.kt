package com.mandro.core.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.mandro.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "BleManager"

private const val EMG_SERVICE_UUID        = "12345678-1234-1234-1234-1234567890ab"
private const val EMG_CHARACTERISTIC_UUID = "abcd1234-5678-1234-5678-abcdef123456"  // raw EMG
private const val INFER_CHARACTERISTIC_UUID = "abcd1234-5678-1234-5678-abcdef123457" // 추론 결과
// 가중치 수신 (WRITE + NOTIFY) — 신규, 펌웨어에 아직 없음 (Phase 5에서 추가 필요)
private const val WEIGHT_CHARACTERISTIC_UUID = "abcd1234-5678-1234-5678-abcdef123458"
private const val ARMBAND_NAME            = "ESP32S3_FAST_BLE"
private const val MTU_SIZE                = 247
private const val SAMPLES_PER_PACKET      = 20
private const val PACKET_SIZE             = SAMPLES_PER_PACKET * EMG_CHANNELS  // 160 bytes

// ── 가중치 전송 프로토콜 (USB와 동일 페이로드, BLE MTU에 맞게 청크만 다름) ──
// [4B MAGIC 0xDEADBEEF][4B LENGTH][N B PAYLOAD][4B CRC32]
private const val WEIGHT_MAGIC     = 0xDEADBEEF.toInt()
private const val BLE_CHUNK_SIZE   = MTU_SIZE - 3  // ATT 오버헤드(opcode 1B + handle 2B) 제외 = 244
private const val ACK_TIMEOUT_MS   = 10_000L

@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _bleState = MutableStateFlow<BleState>(BleState.Disconnected)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _emgStream = MutableSharedFlow<EmgSample>(
        replay = 0,
        extraBufferCapacity = 512,
    )
    val emgStream: SharedFlow<EmgSample> = _emgStream.asSharedFlow()
    private val _inferenceStream = MutableSharedFlow<com.mandro.domain.model.InferenceResult>(
        replay = 1,
        extraBufferCapacity = 64,
    )
    val inferenceStream: SharedFlow<com.mandro.domain.model.InferenceResult> = _inferenceStream.asSharedFlow()

    private val _weightTransferState = MutableStateFlow<WeightTransferState>(WeightTransferState.Idle)
    val weightTransferState: StateFlow<WeightTransferState> = _weightTransferState.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var packetCount = 0
    private var emgNotifyDone = false
    private var weightNotifyEnabled = false

    private var pendingWrite: kotlinx.coroutines.CancellableContinuation<Unit>? = null
    private var pendingAck: kotlinx.coroutines.CancellableContinuation<String>? = null

    // 암밴드가 마지막 청크를 받자마자 OK:WEIGHTS를 보내고 곧바로 재부팅하는데,
    // 그 NOTIFY가 waitForAck()이 리스닝을 시작하기도 전에 도착할 수 있음
    // (pendingAck이 아직 null이라 그냥 버려질 뻔했음) — 그 경우를 대비해 보관.
    private var earlyAck: String? = null
    private var pendingNotifyEnable: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    // ── 스캔 ──────────────────────────────────────────────────

    // 이 앱이 블루투스 스캔을 할 수 있는 권한이 있는지를 시스템에게 확인받는 함수
    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 연결 권한 있나 확인
    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (!hasScanPermission()) {
            _bleState.value = BleState.Error("블루투스 스캔 권한이 필요합니다.")
            return
        }

        foundDevices.clear()
        _bleState.value = BleState.Scanning
        Log.d(TAG, "스캔 시작")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "스캔 시작 실패: ${e.message}")
            _bleState.value = BleState.Error("블루투스 스캔을 시작할 수 없습니다: ${e.message}")
        }
    }

    fun stopScan() {
        if (!hasScanPermission()) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            // 무시
        }
    }

    private val foundDevices = mutableListOf<BleDevice>()
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val name = result.device.name ?: return
                if (name != ARMBAND_NAME) return
                val device = BleDevice(
                    name = name,
                    address = result.device.address,
                    rssi = result.rssi,
                )
                if (foundDevices.none { it.address == device.address }) {
                    foundDevices.add(device)
                    Log.d(TAG, "기기 발견: ${device.name} (${device.address}) RSSI=${device.rssi}")
                    _bleState.value = BleState.DevicesFound(foundDevices.toList())
                }
            } catch (e: SecurityException) {
                // 권한이 없으면 스캔 결과 처리 불가
            }
        }
    }

    // ── 연결 ──────────────────────────────────────────────────

    fun connect(device: BleDevice) {
        if (!hasConnectPermission()) {
            _bleState.value = BleState.Error("블루투스 연결 권한이 필요합니다.")
            return
        }
        stopScan()
        _bleState.value = BleState.Connecting(device)

        val btDevice = adapter?.getRemoteDevice(device.address) ?: return
        try {
            gatt = btDevice.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            _bleState.value = BleState.Error("블루투스 연결을 시작할 수 없습니다: ${e.message}")
        }
    }

    fun disconnect() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {

        }
        gatt = null
        _bleState.value = BleState.Disconnected
    }

    // ── 가중치 전송 (BLE) ─────────────────────────────────────
    //
    // [4B MAGIC 0xDEADBEEF][4B LENGTH][N B PAYLOAD][4B CRC32] 를
    // 244바이트(MTU-3)씩 잘라 순차 Write Request로 전송한 뒤,
    // 같은 Characteristic의 NOTIFY로 "OK:WEIGHTS"/"ERR:*" 응답을 기다린다.

    suspend fun sendWeights(weightsBytes: ByteArray): Result<Unit> {
        val g = gatt ?: return Result.failure(IllegalStateException("암밴드에 연결되어 있지 않아요."))
        if (!hasConnectPermission()) {
            return Result.failure(SecurityException("블루투스 연결 권한이 필요해요."))
        }

        val service = try {
            g.getService(java.util.UUID.fromString(EMG_SERVICE_UUID))
        } catch (e: SecurityException) {
            null
        } ?: return Result.failure(IllegalStateException("서비스를 찾을 수 없어요."))

        val char = service.getCharacteristic(java.util.UUID.fromString(WEIGHT_CHARACTERISTIC_UUID))
            ?: return Result.failure(
                IllegalStateException("이 암밴드는 가중치 수신을 지원하지 않아요 (펌웨어 업데이트 필요).")
            )

        return runCatching {
            _weightTransferState.value = WeightTransferState.Sending(0)
            earlyAck = null  // 이전 전송에서 남아있을 수 있는 값 정리

            if (!weightNotifyEnabled) {
                // GATT는 한 번에 하나의 작업만 진행 가능함 — 이 descriptor 쓰기가
                // 끝났다는 콜백(onDescriptorWrite)을 기다린 뒤에야 다음 write(청크
                // 전송)를 시작할 수 있음. 기다리지 않고 바로 writeChunk()를 부르면
                // "이미 다른 작업 진행 중"으로 거부당함 (writeCharacteristic()이
                // false 리턴 → BLE 쓰기 시작 실패).
                enableNotifyAndAwait(g, char)
                weightNotifyEnabled = true
            }

            val crc = CRC32().apply { update(weightsBytes) }
            val packet = ByteBuffer.allocate(4 + 4 + weightsBytes.size + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(WEIGHT_MAGIC)
                .putInt(weightsBytes.size)
                .put(weightsBytes)
                .putInt(crc.value.toInt())
                .array()

            var offset = 0
            var chunkIndex = 0
            val totalChunks = (packet.size + BLE_CHUNK_SIZE - 1) / BLE_CHUNK_SIZE
            while (offset < packet.size) {
                val end = minOf(offset + BLE_CHUNK_SIZE, packet.size)
                val chunkStartMs = System.currentTimeMillis()
                try {
                    writeChunkWithRetry(g, char, packet.copyOfRange(offset, end))
                    Log.d(TAG, "청크 #$chunkIndex/$totalChunks 전송 성공 (offset=$offset~$end, ${System.currentTimeMillis() - chunkStartMs}ms)")
                } catch (e: Exception) {
                    Log.e(TAG, "청크 #$chunkIndex/$totalChunks 전송 실패 (offset=$offset~$end, ${System.currentTimeMillis() - chunkStartMs}ms 경과): ${e.message}")
                    if (earlyAck != null) {
                        // 마지막 청크가 도착하자마자 암밴드가 처리를 끝내고 바로
                        // 재부팅하면서 연결이 끊겨, 그 write의 확인 응답만 놓친 것 —
                        // 이미 응답(OK:WEIGHTS 등)을 받았으니 실제로는 성공.
                        Log.w(TAG, "청크 확인 응답 받기 전 연결 끊김 — 이미 암밴드 응답을 받아서 무시: ${e.message}")
                        offset = packet.size
                        break
                    }
                    throw e
                }
                offset = end
                chunkIndex++
                _weightTransferState.value = WeightTransferState.Sending(offset * 100 / packet.size)
                delay(15)  // 안드로이드 BLE 스택이 다음 write를 처리할 여유를 줌 (status 133 완화)
            }

            val ack = try {
                withTimeout(ACK_TIMEOUT_MS) { waitForAck() }
            } catch (e: TimeoutCancellationException) {
                error("암밴드 응답이 없어요 (타임아웃)")
            }
            if (ack != "OK:WEIGHTS") error("암밴드가 전송을 거부했어요: $ack")

            _weightTransferState.value = WeightTransferState.Done
        }.onFailure { e ->
            Log.e(TAG, "가중치 전송 실패", e)
            _weightTransferState.value = WeightTransferState.Error(e.message ?: "알 수 없는 오류")
        }
    }

    // status=133(GATT_ERROR) 같은 안드로이드 BLE 스택의 원인 불명 일시적 오류는
    // 219개 청크를 연속으로 쓰는 동안 종종 발생함 — 널리 알려진 안드로이드 BLE
    // 이슈라 재시도로 완화하는 게 표준적인 대응.
    private suspend fun writeChunkWithRetry(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        chunk: ByteArray,
        maxAttempts: Int = 3,
    ) {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                writeChunk(gatt, char, chunk)
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "청크 write 실패 (시도 ${attempt + 1}/$maxAttempts): ${e.message}")
                delay(100L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("청크 전송 실패 (원인 불명)")
    }

    private suspend fun writeChunk(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        chunk: ByteArray,
    ): Unit = suspendCancellableCoroutine { cont ->
        pendingWrite = cont
        try {
            @Suppress("DEPRECATION")
            char.value = chunk
            @Suppress("DEPRECATION")
            val started = g.writeCharacteristic(char)
            if (!started) {
                pendingWrite = null
                cont.resumeWithException(IllegalStateException("BLE 쓰기 시작 실패"))
            }
        } catch (e: SecurityException) {
            pendingWrite = null
            cont.resumeWithException(e)
        }
    }

    private suspend fun waitForAck(): String {
        earlyAck?.let {
            earlyAck = null
            return it
        }
        return suspendCancellableCoroutine { cont ->
            pendingAck = cont
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            try {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "GATT 연결됨 — MTU 요청 ($MTU_SIZE bytes)")
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        gatt.requestMtu(MTU_SIZE)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        // status 코드 뜻을 바로 알 수 있게 사람이 읽는 설명을 같이 남김
                        // (8=GATT_CONN_TIMEOUT: 슈퍼비전 타임아웃 동안 피어로부터
                        // 응답 없음 — 상대(암밴드) 쪽이 그 시간만큼 통신에 응답을
                        // 못 했다는 뜻. 19=GATT_CONN_TERMINATE_PEER_USER: 상대가
                        // 스스로 연결 종료. 34=GATT_CONN_LMP_TIMEOUT.)
                        val reason = when (status) {
                            8 -> "CONN_TIMEOUT — 슈퍼비전 타임아웃 동안 암밴드 응답 없음"
                            19 -> "TERMINATE_PEER_USER — 암밴드가 스스로 연결 종료"
                            34 -> "LMP_TIMEOUT"
                            0 -> "정상 종료(status=0)"
                            else -> "알 수 없음"
                        }
                        Log.d(TAG, "GATT 연결 끊김 (status=$status: $reason)")
                        this@BleManager.gatt?.close()
                        this@BleManager.gatt = null
                        _bleState.value = BleState.Disconnected
                    }
                }
            } catch (e: SecurityException) {
                _bleState.value = BleState.Error("블루투스 통신 중 권한 오류가 발생했습니다.")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU 협상 완료: ${mtu} bytes (status=$status) — 서비스 탐색 시작")
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                _bleState.value = BleState.Error("서비스 검색 중 권한 오류가 발생했습니다.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            try {
                Log.d(TAG, "서비스 탐색 완료 (status=$status)")
                val service = gatt.getService(java.util.UUID.fromString(EMG_SERVICE_UUID))
                    ?: run {
                        Log.e(TAG, "EMG 서비스 없음 — UUID 불일치 가능성")
                        _bleState.value = BleState.Error("암밴드를 찾지 못했어요. 전원을 확인하고 다시 시도해 주세요.")
                        return
                    }

                // raw EMG Characteristic (...56) — 먼저 구독
                emgNotifyDone = false
                val emgChar = service.getCharacteristic(java.util.UUID.fromString(EMG_CHARACTERISTIC_UUID))
                if (emgChar != null) {
                    enableNotify(gatt, emgChar)
                } else {
                    Log.w(TAG, "raw EMG Characteristic 없음")
                    subscribeInferChar(gatt, service)  // EMG 없어도 추론 결과는 구독 시도
                }
                // 추론 결과 Characteristic (...57) 구독은 onDescriptorWrite 콜백에서 순차 처리
            } catch (e: SecurityException) {
                _bleState.value = BleState.Error("서비스 설정 중 권한 오류가 발생했습니다.")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val charUuid = descriptor.characteristic.uuid.toString()
            Log.d(TAG, "Descriptor 쓰기 완료: $charUuid (status=$status)")

            if (charUuid.equals(WEIGHT_CHARACTERISTIC_UUID, ignoreCase = true)) {
                val cont = pendingNotifyEnable
                pendingNotifyEnable = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    cont?.resume(Unit)
                } else {
                    cont?.resumeWithException(IllegalStateException("Notify 등록 실패 (status=$status)"))
                }
                return
            }

            if (charUuid.equals(EMG_CHARACTERISTIC_UUID, ignoreCase = true) && !emgNotifyDone) {
                emgNotifyDone = true
                // raw EMG 구독 완료 → 추론 결과 Characteristic 구독
                val service = gatt.getService(java.util.UUID.fromString(EMG_SERVICE_UUID))
                if (service != null) subscribeInferChar(gatt, service)

                // 연결 완료 상태 업데이트
                val deviceName = try { gatt.device.name } catch (e: SecurityException) { null }
                val connectedDevice = BleDevice(
                    name = deviceName ?: ARMBAND_NAME,
                    address = gatt.device.address,
                    rssi = 0,
                )
                Log.i(TAG, "연결 완료: ${connectedDevice.name}")
                _bleState.value = BleState.Connected(connectedDevice)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val bytes = characteristic.value ?: return
            when (characteristic.uuid.toString().lowercase()) {
                EMG_CHARACTERISTIC_UUID.lowercase()    -> parseEmgPacket(bytes)
                INFER_CHARACTERISTIC_UUID.lowercase()  -> parseInferencePacket(bytes)
                WEIGHT_CHARACTERISTIC_UUID.lowercase() -> {
                    val msg = String(bytes, Charsets.UTF_8)
                    Log.d(TAG, "가중치 전송 응답: $msg")
                    val cont = pendingAck
                    if (cont != null) {
                        pendingAck = null
                        cont.resume(msg)
                    } else {
                        // waitForAck()이 아직 시작 안 한 시점에 응답이 먼저 옴 —
                        // 버리지 않고 보관했다가 waitForAck()이 나중에 즉시 반환하도록.
                        earlyAck = msg
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!characteristic.uuid.toString().equals(WEIGHT_CHARACTERISTIC_UUID, ignoreCase = true)) return
            val cont = pendingWrite ?: return
            pendingWrite = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                cont.resume(Unit)
            } else {
                cont.resumeWithException(IllegalStateException("BLE 쓰기 실패 (status=$status)"))
            }
        }
    }

    // enableNotify()와 달리 onDescriptorWrite 콜백이 올 때까지 기다림 —
    // 가중치 전송처럼 바로 이어서 다른 GATT 작업(청크 write)을 해야 하는
    // 경우에만 사용. 콜백 처리는 onDescriptorWrite의 WEIGHT_CHARACTERISTIC_UUID
    // 분기 참고.
    private suspend fun enableNotifyAndAwait(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
    ): Unit = suspendCancellableCoroutine { cont ->
        pendingNotifyEnable = cont
        try {
            gatt.setCharacteristicNotification(char, true)
            val desc = char.descriptors.firstOrNull()
            if (desc == null) {
                pendingNotifyEnable = null
                cont.resumeWithException(IllegalStateException("CCCD descriptor를 찾을 수 없어요"))
                return@suspendCancellableCoroutine
            }
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = gatt.writeDescriptor(desc)
            if (!started) {
                pendingNotifyEnable = null
                cont.resumeWithException(IllegalStateException("Notify 등록 시작 실패"))
            }
        } catch (e: SecurityException) {
            pendingNotifyEnable = null
            cont.resumeWithException(e)
        }
    }

    private fun enableNotify(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        try {
            gatt.setCharacteristicNotification(char, true)
            char.descriptors.firstOrNull()?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Notify 등록 실패: ${char.uuid}", e)
        }
    }

    /**
     * raw EMG(...56) Characteristic의 CCCD를 명시적으로 켰다 껐다 함 — 연결 시
     * 최초 구독(enableNotify)과는 별개로, 사용자가 RawStreamPreferences를 바꿀 때마다
     * BleRepositoryImpl이 이 함수를 호출해서 실제 라디오 송신 여부를 제어함
     * (RAW_STREAM_TOGGLE.md 옵션 A). CCCD를 0x0000으로 쓰면 펌웨어가 실제로 notify를
     * 안 보내게 되는 표준 BLE 동작을 그대로 활용 — 앱 쪽 로컬 필터가 아님.
     */
    fun setRawEmgSubscribed(enabled: Boolean) {
        val g = gatt ?: return
        try {
            val service = g.getService(java.util.UUID.fromString(EMG_SERVICE_UUID)) ?: return
            val char = service.getCharacteristic(java.util.UUID.fromString(EMG_CHARACTERISTIC_UUID)) ?: return
            g.setCharacteristicNotification(char, enabled)
            val desc = char.descriptors.firstOrNull() ?: return
            desc.value = if (enabled) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            g.writeDescriptor(desc)
            Log.d(TAG, "raw EMG 구독 상태 변경 요청: enabled=$enabled")
        } catch (e: SecurityException) {
            Log.e(TAG, "raw EMG 구독 상태 변경 실패", e)
        }
    }

    private fun subscribeInferChar(gatt: BluetoothGatt, service: android.bluetooth.BluetoothGattService) {
        val inferChar = service.getCharacteristic(java.util.UUID.fromString(INFER_CHARACTERISTIC_UUID))
        if (inferChar != null) {
            enableNotify(gatt, inferChar)
            Log.d(TAG, "추론 결과 Characteristic 구독 요청")
        } else {
            Log.w(TAG, "추론 결과 Characteristic 없음 (펌웨어 미지원 가능성)")
        }
    }

    // ── 패킷 파싱 ─────────────────────────────────────────────

    /** 포맷: "classname|l0|l1|l2|l3|l4|l5" */
    private fun parseInferencePacket(bytes: ByteArray) {
        val result = com.mandro.domain.model.InferenceResult.parse(bytes) ?: run {
            Log.w(TAG, "추론 결과 파싱 실패: ${String(bytes, Charsets.UTF_8)}")
            return
        }
        _inferenceStream.tryEmit(result)
    }

    /**
     * 패킷 포맷: uint8 × 8채널 × 20샘플 = 160 bytes (row-major)
     * byte[s*8 + ch] → 채널 ch의 s번째 샘플값 (0~255)
     */
    private fun parseEmgPacket(bytes: ByteArray) {
        // raw EMG 구독 여부(CCCD)는 setRawEmgSubscribed()로 관리 — 구독 안 돼있으면
        // 펌웨어가 애초에 notify를 안 보내므로 이 함수 자체가 호출 안 됨. 예전엔
        // 여기서 로컬 플래그(emgEnabled)로 한 번 더 걸렀는데, 그건 "펌웨어는 계속
        // 보내는데 앱만 무시"하는 거라 전력 절약 효과가 없어서 제거함(RAW_STREAM_TOGGLE.md).
        if (bytes.size != PACKET_SIZE) {
            Log.w(TAG, "예상치 못한 패킷 크기: ${bytes.size} bytes (기대값: $PACKET_SIZE) — 드롭")
            return
        }
        packetCount++
        if (packetCount % 64 == 0) {
            val ch0 = bytes[0].toInt() and 0xFF
            Log.d(TAG, "패킷 수신 #$packetCount — CH0[0]=$ch0 (0~255)")
        }
        for (s in 0 until SAMPLES_PER_PACKET) {
            val channels = FloatArray(EMG_CHANNELS) { ch ->
                (bytes[s * EMG_CHANNELS + ch].toInt() and 0xFF).toFloat()
            }
            _emgStream.tryEmit(EmgSample(channels = channels))
        }
    }
}
