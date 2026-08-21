package com.mandro.core.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.mandro.domain.model.HandPairingState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "HandPairingController"

/**
 * 로봇 의수의 BLE MAC을 스캔으로 찾아 암밴드의 PAIR characteristic(NVS 저장용,
 * PairCharCallbacks::onWrite()/onRead() — mandro-firmware의 exo_armband_hybrid.ino)에
 * 기록한다. mandro-pc-app의 lib/ble_hand_pairing.py + client_app/pair_hand_screen.py와
 * 동일한 와이어 프로토콜/순서를 안드로이드로 옮긴 것:
 *   [1B header 0xE0][6B 로봇 의수 MAC, 사람이 읽는 순서][1B XOR checksum(MAC 6바이트만)]
 *   CLEAR: [1B 0xE1] 단독 write — 저장된 MAC 삭제.
 *
 * 순서가 중요함:
 *   1. 폰이 이미 암밴드에 연결되어 있어야 함 — 그 자체로 암밴드가 SLAVE 모드로 전환되어
 *      로봇 의수를 놓아준 상태(exo_armband_hybrid.ino modeTick()/forceDisconnectChipsen()).
 *      이 컨트롤러는 Settings 탭에서 암밴드 연결 후에만 호출되므로 여기서 암밴드에 새로
 *      연결하는 단계는 없음 — 이미 연결된 [BluetoothGatt]를 인자로 받는다.
 *   2. 로봇 의수를 이름 접두사로 스캔해서 MAC을 찾음.
 *   3. 로봇 의수에 별도 GATT로 연결해 연결 슬롯을 잡아둔 채로(PC 앱과 동일하게 "양쪽
 *      링크를 모두 열어놓고") 암밴드 링크로 페어링 패킷을 쓰고 검증한다.
 *   4. 로봇 의수 연결을 끊음 — 폰이 암밴드 연결을 유지하는 한 암밴드는 계속 SLAVE
 *      모드이므로, 로봇 의수는 이후 앱이 암밴드에서 손을 뗄 때 암밴드가 자동으로
 *      재연결한다.
 *
 * 암밴드의 [BluetoothGattCallback]은 연결 시점에 한 번만 바인딩되어 [BleManager]가
 * 계속 들고 있어야 하므로, PAIR characteristic 관련 GATT 콜백 이벤트(쓰기 완료, notify
 * 활성화 완료, 응답 notify 수신, 읽기 완료)는 [BleManager]가 받아 onXxx()로 이 클래스에
 * 전달한다. 로봇 의수 스캔/연결은 암밴드 GATT와 무관한 별도 BLE 중앙 역할이라 이 클래스가
 * 직접 수행한다.
 */
class HandPairingController(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
) {
    companion object {
        const val CHARACTERISTIC_UUID = "abcd1234-5678-1234-5678-abcdef123459"
        private val HDR: Byte = 0xE0.toByte()
        private val HDR_CLEAR: Byte = 0xE1.toByte()
        private const val PACKET_LEN = 8
        private const val MAC_BYTE_LEN = 6
        private const val ACK_TIMEOUT_MS = 10_000L
        private const val HAND_CONNECT_TIMEOUT_MS = 25_000L
        private const val SCAN_ATTEMPTS = 6
        private const val SCAN_WINDOW_MS = 6_000L
    }

    private val _state = MutableStateFlow<HandPairingState>(HandPairingState.Idle)
    val state: StateFlow<HandPairingState> = _state.asStateFlow()

    private var handGatt: BluetoothGatt? = null
    private var pendingHandConnect: CancellableContinuation<Unit>? = null

    private var notifyEnabled = false
    private var pendingNotifyEnable: CancellableContinuation<Unit>? = null
    private var pendingWrite: CancellableContinuation<Unit>? = null
    private var pendingAck: CancellableContinuation<String>? = null
    private var earlyAck: String? = null
    private var pendingRead: CancellableContinuation<ByteArray>? = null

    // ── 암밴드 GATT 콜백에서 전달받는 이벤트 (BleManager.gattCallback 참고) ──

    fun onDescriptorWriteResult(status: Int) {
        val cont = pendingNotifyEnable
        pendingNotifyEnable = null
        if (status == BluetoothGatt.GATT_SUCCESS) {
            cont?.resume(Unit)
        } else {
            cont?.resumeWithException(IllegalStateException("Notify 등록 실패 (status=$status)"))
        }
    }

    fun onCharacteristicChangedResult(value: ByteArray) {
        val msg = String(value, Charsets.UTF_8)
        Log.d(TAG, "로봇 의수 페어링 응답: $msg")
        val cont = pendingAck
        if (cont != null) {
            pendingAck = null
            cont.resume(msg)
        } else {
            // waitForAck() 시작 전에 응답이 먼저 온 경우 — 버리지 않고 보관.
            earlyAck = msg
        }
    }

    fun onCharacteristicWriteResult(status: Int) {
        val cont = pendingWrite ?: return
        pendingWrite = null
        if (status == BluetoothGatt.GATT_SUCCESS) {
            cont.resume(Unit)
        } else {
            cont.resumeWithException(IllegalStateException("BLE 쓰기 실패 (status=$status)"))
        }
    }

    fun onCharacteristicReadResult(value: ByteArray, status: Int) {
        val cont = pendingRead ?: return
        pendingRead = null
        if (status == BluetoothGatt.GATT_SUCCESS) {
            cont.resume(value)
        } else {
            cont.resumeWithException(IllegalStateException("BLE 읽기 실패 (status=$status)"))
        }
    }

    // ── 공개 API ──────────────────────────────────────────────

    suspend fun pairHand(armbandGatt: BluetoothGatt, handNamePrefix: String): Result<String> {
        return runCatching {
            _state.value = HandPairingState.InProgress("로봇 의수를 찾는 중...")
            val handAddress = scanForHand(handNamePrefix)

            _state.value = HandPairingState.InProgress("로봇 의수에 연결하는 중...")
            val hGatt = connectHandGatt(handAddress)
            try {
                _state.value = HandPairingState.InProgress("암밴드에 MAC 등록 중...")
                val pairChar = requireCharacteristic(armbandGatt)
                ensureNotify(armbandGatt, pairChar)

                val macBytes = macStrToBytes(handAddress)
                val packet = buildPairPacket(macBytes)
                val ack = writeAndAwaitAck(armbandGatt, pairChar, packet)
                if (ack != "OK:PAIR") error("암밴드가 페어링을 거부했어요: $ack")

                _state.value = HandPairingState.InProgress("등록 확인 중...")
                var stored = readCharacteristic(armbandGatt, pairChar)
                if (!stored.contentEquals(macBytes)) {
                    // NVS 커밋이 아직 안 됐을 수 있음 — 1초 뒤 재확인해서 타이밍
                    // 문제인지 진짜 불일치인지 구분 (pair_hand_screen.py와 동일 대응).
                    delay(1000)
                    stored = readCharacteristic(armbandGatt, pairChar)
                }
                if (!stored.contentEquals(macBytes)) {
                    error("암밴드에 저장된 MAC이 보낸 값과 달라요.")
                }

                macBytesToStr(macBytes)
            } finally {
                disconnectHandGatt(hGatt)
            }
        }.onSuccess { macStr ->
            _state.value = HandPairingState.Success(macStr)
        }.onFailure { e ->
            Log.e(TAG, "로봇 의수 페어링 실패", e)
            _state.value = HandPairingState.Error(e.message ?: "알 수 없는 오류")
        }
    }

    suspend fun checkPairedHandMac(armbandGatt: BluetoothGatt): Result<String?> = runCatching {
        val pairChar = requireCharacteristic(armbandGatt)
        val stored = readCharacteristic(armbandGatt, pairChar)
        if (stored.isEmpty() || stored.all { it == 0.toByte() }) null else macBytesToStr(stored)
    }

    suspend fun clearPairedHand(armbandGatt: BluetoothGatt): Result<Unit> = runCatching {
        val pairChar = requireCharacteristic(armbandGatt)
        ensureNotify(armbandGatt, pairChar)
        val ack = writeAndAwaitAck(armbandGatt, pairChar, byteArrayOf(HDR_CLEAR))
        if (ack != "OK:CLEAR") error("암밴드가 삭제 요청을 거부했어요: $ack")
    }.onFailure { e ->
        Log.e(TAG, "로봇 의수 페어링 삭제 실패", e)
    }

    // ── 암밴드 PAIR characteristic 입출력 ─────────────────────

    private fun requireCharacteristic(g: BluetoothGatt): BluetoothGattCharacteristic {
        val service = g.getService(UUID.fromString(EMG_SERVICE_UUID))
            ?: error("암밴드 서비스를 찾을 수 없어요.")
        return service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID))
            ?: error("이 암밴드는 로봇 의수 페어링을 지원하지 않아요 (펌웨어 업데이트가 필요해요).")
    }

    private suspend fun ensureNotify(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
        if (notifyEnabled) return
        suspendCancellableCoroutine<Unit> { cont ->
            pendingNotifyEnable = cont
            try {
                g.setCharacteristicNotification(char, true)
                val desc = char.descriptors.firstOrNull()
                if (desc == null) {
                    pendingNotifyEnable = null
                    cont.resumeWithException(IllegalStateException("CCCD descriptor를 찾을 수 없어요"))
                    return@suspendCancellableCoroutine
                }
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val started = g.writeDescriptor(desc)
                if (!started) {
                    pendingNotifyEnable = null
                    cont.resumeWithException(IllegalStateException("Notify 등록 시작 실패"))
                }
            } catch (e: SecurityException) {
                pendingNotifyEnable = null
                cont.resumeWithException(e)
            }
        }
        notifyEnabled = true
    }

    private suspend fun writeAndAwaitAck(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        packet: ByteArray,
    ): String {
        earlyAck = null
        suspendCancellableCoroutine<Unit> { cont ->
            pendingWrite = cont
            try {
                @Suppress("DEPRECATION")
                char.value = packet
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
        return try {
            withTimeout(ACK_TIMEOUT_MS) { awaitAck() }
        } catch (e: TimeoutCancellationException) {
            error("암밴드 응답이 없어요 (타임아웃)")
        }
    }

    private suspend fun awaitAck(): String {
        earlyAck?.let {
            earlyAck = null
            return it
        }
        return suspendCancellableCoroutine { cont -> pendingAck = cont }
    }

    private suspend fun readCharacteristic(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
    ): ByteArray = suspendCancellableCoroutine { cont ->
        pendingRead = cont
        try {
            @Suppress("DEPRECATION")
            val started = g.readCharacteristic(char)
            if (!started) {
                pendingRead = null
                cont.resumeWithException(IllegalStateException("BLE 읽기 시작 실패"))
            }
        } catch (e: SecurityException) {
            pendingRead = null
            cont.resumeWithException(e)
        }
    }

    // ── 로봇 의수 스캔 + 연결 (암밴드 GATT와 무관) ────────────

    // 이름 접두사(대소문자 무관)로 반복 스캔 — 로봇 의수의 RSSI가 암밴드보다 약한
    // 경우가 흔해서(-80dBm대) 여러 번 시도.
    private suspend fun scanForHand(namePrefix: String): String {
        val scanner = adapter?.bluetoothLeScanner ?: error("블루투스를 사용할 수 없어요.")
        repeat(SCAN_ATTEMPTS) { attempt ->
            _state.value = HandPairingState.InProgress(
                "로봇 의수를 찾는 중... (${attempt + 1}/$SCAN_ATTEMPTS)"
            )
            scanOnce(scanner, namePrefix, SCAN_WINDOW_MS)?.let { return it }
        }
        error("로봇 의수를 찾지 못했어요. 전원과 거리를 확인해 주세요.")
    }

    private suspend fun scanOnce(
        scanner: BluetoothLeScanner,
        namePrefix: String,
        windowMs: Long,
    ): String? {
        val found = AtomicReference<String?>(null)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                    if (name.lowercase().startsWith(namePrefix.lowercase())) {
                        found.compareAndSet(null, result.device.address)
                    }
                } catch (e: SecurityException) {
                    // 권한 없으면 결과 무시
                }
            }
        }
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            withTimeoutOrNull(windowMs) {
                while (found.get() == null) delay(200)
            }
        } catch (e: SecurityException) {
            error("블루투스 스캔 권한이 없어요.")
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                // 무시
            }
        }
        return found.get()
    }

    private suspend fun connectHandGatt(address: String): BluetoothGatt {
        val device = adapter?.getRemoteDevice(address) ?: error("블루투스 어댑터를 사용할 수 없어요.")
        try {
            withTimeout(HAND_CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    pendingHandConnect = cont
                    cont.invokeOnCancellation { pendingHandConnect = null }
                    try {
                        handGatt = device.connectGatt(context, false, handGattCallback)
                    } catch (e: SecurityException) {
                        pendingHandConnect = null
                        cont.resumeWithException(e)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            error("로봇 의수 연결 시간이 초과됐어요.")
        }
        return handGatt ?: error("로봇 의수 연결에 실패했어요.")
    }

    private fun disconnectHandGatt(g: BluetoothGatt) {
        try {
            g.disconnect()
            g.close()
        } catch (e: SecurityException) {
            // 무시
        }
        if (handGatt === g) handGatt = null
    }

    private val handGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "로봇 의수 GATT 연결됨")
                    val cont = pendingHandConnect
                    pendingHandConnect = null
                    cont?.resume(Unit)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "로봇 의수 GATT 연결 끊김 (status=$status)")
                    val cont = pendingHandConnect
                    if (cont != null) {
                        pendingHandConnect = null
                        cont.resumeWithException(
                            IllegalStateException("로봇 의수 연결이 끊겼어요 (status=$status)")
                        )
                    }
                }
            }
        }
    }

    // ── MAC/패킷 헬퍼 ─────────────────────────────────────────

    private fun macStrToBytes(address: String): ByteArray {
        val parts = address.split(":")
        if (parts.size != MAC_BYTE_LEN) error("잘못된 MAC 주소 형식: $address")
        return ByteArray(MAC_BYTE_LEN) { i -> parts[i].toInt(16).toByte() }
    }

    private fun macBytesToStr(bytes: ByteArray): String =
        bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    private fun buildPairPacket(macBytes: ByteArray): ByteArray {
        val packet = ByteArray(PACKET_LEN)
        packet[0] = HDR
        macBytes.copyInto(packet, destinationOffset = 1)
        var chk = 0
        for (b in macBytes) chk = chk xor (b.toInt() and 0xFF)
        packet[7] = chk.toByte()
        return packet
    }
}
