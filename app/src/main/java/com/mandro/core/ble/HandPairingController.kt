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
 * 로봇의수의 BLE MAC을 스캔으로 찾아 암밴드의 PAIR characteristic(NVS 저장용,
 * PairCharCallbacks::onWrite()/onRead() — mandro-firmware의 exo_armband_hybrid.ino)에
 * 기록한다. mandro-pc-app의 lib/ble_hand_pairing.py + client_app/pair_hand_screen.py와
 * 동일한 와이어 프로토콜/순서를 안드로이드로 옮긴 것:
 *   [1B header 0xE0][6B 로봇의수 MAC, 사람이 읽는 순서][1B XOR checksum(MAC 6바이트만)]
 *   CLEAR: [1B 0xE1] 단독 write — 저장된 MAC 삭제.
 *
 * 순서가 중요함:
 *   1. 폰이 이미 암밴드에 연결되어 있어야 함 — 그 자체로 암밴드가 SLAVE 모드로 전환되어
 *      로봇의수를 놓아준 상태(exo_armband_hybrid.ino modeTick()/forceDisconnectChipsen()).
 *      이 컨트롤러는 Settings 탭에서 암밴드 연결 후에만 호출되므로 여기서 암밴드에 새로
 *      연결하는 단계는 없음 — 이미 연결된 [BluetoothGatt]를 인자로 받는다.
 *   2. 로봇의수를 이름 접두사로 스캔해서 MAC을 찾음.
 *   3. 로봇의수에 별도 GATT로 연결해 연결 슬롯을 잡아둔 채로(PC 앱과 동일하게 "양쪽
 *      링크를 모두 열어놓고") 암밴드 링크로 페어링 패킷을 쓰고 검증한다.
 *   4. 로봇의수 연결을 끊음 — 폰이 암밴드 연결을 유지하는 한 암밴드는 계속 SLAVE
 *      모드이므로, 로봇의수는 이후 앱이 암밴드에서 손을 뗄 때 암밴드가 자동으로
 *      재연결한다.
 *
 * 암밴드의 [BluetoothGattCallback]은 연결 시점에 한 번만 바인딩되어 [BleManager]가
 * 계속 들고 있어야 하므로, PAIR characteristic 관련 GATT 콜백 이벤트(쓰기 완료, notify
 * 활성화 완료, 응답 notify 수신, 읽기 완료)는 [BleManager]가 받아 onXxx()로 이 클래스에
 * 전달한다. 로봇의수 스캔/연결은 암밴드 GATT와 무관한 별도 BLE 중앙 역할이라 이 클래스가
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
        // pc-app(lib/ble_hand_pairing.py)은 10초를 쓰는데, 거긴 매 페어링마다 새로
        // 붙는 단독 연결이라 여유로움. 안드로이드는 같은 암밴드 GATT 연결로 EMG/추론
        // 결과 notify가 계속 흐르는 중에 페어링 ack를 기다리는 거라 그만큼 밀릴 수
        // 있어서(2026-08-21) 더 넉넉하게 잡음.
        private const val ACK_TIMEOUT_MS = 20_000L
        private const val HAND_CONNECT_TIMEOUT_MS = 25_000L
        private const val SCAN_ATTEMPTS = 6
        private const val SCAN_WINDOW_MS = 6_000L
        private const val BUSY_RETRY_ATTEMPTS = 3
        private const val BUSY_RETRY_DELAY_MS = 200L

        // pc-app lib/ble_hand_pairing.py의 ACK_OK_PREFIX와 동일 — 성공 ack는
        // "OK:PAIR"/"OK:CLEAR"처럼 항상 이 접두사로 시작함. 실패는 "ERR:*".
        private const val ACK_OK_PREFIX = "OK:"
        private const val ACK_ERR_PREFIX = "ERR:"
    }

    private val _state = MutableStateFlow<HandPairingState>(HandPairingState.Idle)
    val state: StateFlow<HandPairingState> = _state.asStateFlow()

    private var handGatt: BluetoothGatt? = null
    private var pendingHandConnect: CancellableContinuation<Unit>? = null

    // 암밴드는 본딩을 안 써서(NOT BONDED), 연결이 끊기면 암밴드 쪽 notify 구독
    // 상태(CCCD)가 리셋됨. 이 컨트롤러는 BleManager의 싱글턴이라 앱이 사는 동안
    // 계속 살아있는데, notifyEnabled를 리셋 안 하면 재연결 후에도 "이미 구독함"으로
    // 착각해서 다시 구독을 안 하고, 그러면 ack notify를 영영 못 받아 "암밴드 응답이
    // 없어요" 타임아웃이 남 — 반드시 onArmbandDisconnected()로 리셋해야 함
    // (BleManager.disconnect()/gattCallback의 STATE_DISCONNECTED에서 호출).
    private var notifyEnabled = false
    private var pendingNotifyEnable: CancellableContinuation<Unit>? = null
    private var pendingWrite: CancellableContinuation<Unit>? = null
    private var pendingAck: CancellableContinuation<String>? = null
    private var earlyAck: String? = null
    private var pendingRead: CancellableContinuation<ByteArray>? = null

    // ── 암밴드 GATT 콜백에서 전달받는 이벤트 (BleManager.gattCallback 참고) ──

    // 암밴드 연결이 끊길 때마다 BleManager가 호출 — notifyEnabled 등 "이번 연결에서만
    // 유효한" 상태를 리셋해서, 재연결 후 다음 페어링/삭제 요청이 notify 구독 없이
    // 진행되는 걸 막음 (클래스 상단 notifyEnabled 주석 참고).
    fun onArmbandDisconnected() {
        notifyEnabled = false
    }

    fun onDescriptorWriteResult(status: Int) {
        val cont = pendingNotifyEnable
        pendingNotifyEnable = null
        if (status == BluetoothGatt.GATT_SUCCESS) {
            cont?.resume(Unit)
        } else {
            Log.w(TAG, "Notify 등록 실패 (status=$status)")
            cont?.resumeWithException(IllegalStateException("등록에 실패했어요."))
        }
    }

    fun onCharacteristicChangedResult(value: ByteArray) {
        val msg = String(value, Charsets.UTF_8)
        Log.d(TAG, "로봇의수 페어링 응답: $msg")
        // pc-app의 on_notify()와 동일한 방어 로직 — OK:/ERR: 형태가 아닌 notify는
        // 예상 밖의 응답(잡음, 중복 등)이니 로그만 남기고 ack로 취급하지 않는다.
        if (!msg.startsWith(ACK_OK_PREFIX) && !msg.startsWith(ACK_ERR_PREFIX)) {
            Log.w(TAG, "OK:/ERR: 형태가 아닌 notify — ack로 취급하지 않음: $msg")
            return
        }
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
            Log.w(TAG, "BLE 쓰기 실패 (status=$status)")
            cont.resumeWithException(IllegalStateException("등록에 실패했어요."))
        }
    }

    fun onCharacteristicReadResult(value: ByteArray, status: Int) {
        val cont = pendingRead ?: return
        pendingRead = null
        if (status == BluetoothGatt.GATT_SUCCESS) {
            cont.resume(value)
        } else {
            Log.w(TAG, "BLE 읽기 실패 (status=$status)")
            cont.resumeWithException(IllegalStateException("등록에 실패했어요."))
        }
    }

    // ── 공개 API ──────────────────────────────────────────────

    suspend fun pairHand(armbandGatt: BluetoothGatt, handNamePrefix: String): Result<String> {
        // scanForHand()는 runCatching 블록 안에서만 쓰이지만, 찾은 기기 이름은
        // 성공 시 아래 onSuccess에서 HandPairingState.Success에 실어 보내야 해서
        // 블록 밖에 따로 들고 있음.
        var handDeviceName = ""
        return runCatching {
            _state.value = HandPairingState.InProgress("로봇의수를 찾는 중...")
            val handDevice = scanForHand(handNamePrefix)
            handDeviceName = handDevice.name
            val handAddress = handDevice.address

            _state.value = HandPairingState.InProgress("로봇의수에 연결하는 중...")
            val hGatt = connectHandGatt(handAddress)
            try {
                val pairChar = requireCharacteristic(armbandGatt)
                ensureNotify(armbandGatt, pairChar)

                val macBytes = macStrToBytes(handAddress)
                val packet = buildPairPacket(macBytes)
                _state.value = HandPairingState.InProgress("암밴드에 등록하는 중...")
                val ack = writeAndAwaitAck(armbandGatt, pairChar, packet)
                if (!ack.startsWith(ACK_OK_PREFIX)) {
                    Log.w(TAG, "암밴드가 페어링을 거부함: $ack")
                    error("등록에 실패했어요.")
                }

                _state.value = HandPairingState.InProgress("등록 확인하는 중...")
                var stored = readCharacteristic(armbandGatt, pairChar)
                if (!stored.contentEquals(macBytes)) {
                    // NVS 커밋이 아직 안 됐을 수 있음 — 1초 뒤 재확인해서 타이밍
                    // 문제인지 진짜 불일치인지 구분 (pair_hand_screen.py와 동일 대응).
                    _state.value = HandPairingState.InProgress("다시 확인하는 중...")
                    delay(1000)
                    stored = readCharacteristic(armbandGatt, pairChar)
                }
                // pair_hand_screen.py의 3단계 진단(empty/byte-reversed/generic)은 로그로만
                // 남기고, 사용자에게는 원인 구분 없이 간단히 실패로만 알림.
                if (!stored.contentEquals(macBytes)) {
                    Log.w(
                        TAG,
                        "등록 확인 실패 — 보낸 값: ${macBytesToStr(macBytes)}, " +
                            "저장된 값: ${if (stored.isEmpty()) "(없음)" else macBytesToStr(stored)}"
                    )
                    error("등록에 실패했어요.")
                }

                macBytesToStr(macBytes)
            } finally {
                disconnectHandGatt(hGatt)
            }
        }.onSuccess { macStr ->
            _state.value = HandPairingState.Success(macStr, handDeviceName)
        }.onFailure { e ->
            Log.e(TAG, "로봇의수 페어링 실패", e)
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
        if (!ack.startsWith(ACK_OK_PREFIX)) {
            Log.w(TAG, "암밴드가 삭제 요청을 거부함: $ack")
            error("삭제에 실패했어요.")
        }

        // ack("OK:CLEAR")는 받았지만, 펌웨어의 NVS 삭제(pairPrefs.remove())가 그
        // 자리에서 곧바로 커밋 안 됐을 수 있음(BLE onWrite() 콜백 안에서 동기 처리라
        // pairHand()의 등록 확인(위 delay(1000) 재확인)과 같은 타이밍 문제). ack만
        // 믿지 않고 실제로 비워졌는지 읽어서 확인하고, 아직 안 지워졌으면 1초 뒤
        // 한 번 더 확인.
        var stored = readCharacteristic(armbandGatt, pairChar)
        if (!(stored.isEmpty() || stored.all { it == 0.toByte() })) {
            delay(1000)
            stored = readCharacteristic(armbandGatt, pairChar)
        }
        if (!(stored.isEmpty() || stored.all { it == 0.toByte() })) {
            Log.w(TAG, "삭제 확인 실패 — 암밴드에 아직 MAC(${macBytesToStr(stored)})이 남아있음")
            error("삭제에 실패했어요.")
        }
    }.onFailure { e ->
        Log.e(TAG, "로봇의수 페어링 삭제 실패", e)
    }

    // ── 암밴드 PAIR characteristic 입출력 ─────────────────────

    private fun requireCharacteristic(g: BluetoothGatt): BluetoothGattCharacteristic {
        val service = g.getService(UUID.fromString(EMG_SERVICE_UUID))
            ?: error("암밴드 서비스를 찾을 수 없어요.")
        return service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID))
            ?: error("이 암밴드는 로봇의수 페어링을 지원하지 않아요 (펌웨어 업데이트가 필요해요).")
    }

    // g.writeCharacteristic()/writeDescriptor()/readCharacteristic()이 false를 반환하는
    // 건 "다른 GATT 작업이 아직 진행 중"인 경우가 대부분(안드로이드 BLE 스택은 한 번에
    // 하나의 작업만 허용) — 특히 암밴드에 막 연결된 직후엔 BleManager가 EMG/추론 결과
    // characteristic 구독을 마무리하기 전에 Connected 상태를 먼저 알려서(BleManager.kt
    // onDescriptorWrite), 이 화면이 그 순간 바로 읽기/쓰기를 시도하면 실제로 경합이
    // 남. 짧게 재시도하면 대부분 풀림(BleManager.writeChunkWithRetry와 동일한 대응) —
    // "...시작 실패"로 끝나는 예외만 재시도 대상으로 삼고, 그 외(타임아웃 등)는 그대로
    // 전파.
    private suspend fun <T> retryIfBusy(block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(BUSY_RETRY_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (e: IllegalStateException) {
                if (e.message?.endsWith("시작 실패") != true) throw e
                lastError = e
                Log.w(TAG, "GATT 작업 시작 거부됨 — 재시도 (${attempt + 1}/$BUSY_RETRY_ATTEMPTS)")
                delay(BUSY_RETRY_DELAY_MS)
            }
        }
        Log.e(TAG, "GATT 작업이 계속 거부됨 (${BUSY_RETRY_ATTEMPTS}회 재시도 실패): ${lastError?.message}")
        error("암밴드와 통신하지 못했어요.")
    }

    private suspend fun ensureNotify(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
        if (notifyEnabled) return
        retryIfBusy { ensureNotifyOnce(g, char) }
        notifyEnabled = true
    }

    private suspend fun ensureNotifyOnce(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
    ): Unit = suspendCancellableCoroutine { cont ->
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

    private suspend fun writeAndAwaitAck(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        packet: ByteArray,
    ): String {
        earlyAck = null
        retryIfBusy { writeOnce(g, char, packet) }
        return try {
            withTimeout(ACK_TIMEOUT_MS) { awaitAck() }
        } catch (e: TimeoutCancellationException) {
            error("암밴드 응답이 없어요.")
        }
    }

    private suspend fun writeOnce(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        packet: ByteArray,
    ): Unit = suspendCancellableCoroutine { cont ->
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
    ): ByteArray = retryIfBusy { readCharacteristicOnce(g, char) }

    private suspend fun readCharacteristicOnce(
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

    // ── 로봇의수 스캔 + 연결 (암밴드 GATT와 무관) ────────────

    // 스캔에서 실제로 광고받은 이름(예: "CHIPSEN")까지 같이 들고 있음 — 암밴드
    // NVS엔 MAC만 저장되니, 페어링 성공 직후에만 정확한 이름을 알 수 있어서
    // HandPairingState.Success에 실어 화면에 보여주기 위함.
    private data class ScannedHand(val name: String, val address: String)

    // 이름 접두사(대소문자 무관)로 반복 스캔 — 로봇의수의 RSSI가 암밴드보다 약한
    // 경우가 흔해서(-80dBm대) 여러 번 시도.
    private suspend fun scanForHand(namePrefix: String): ScannedHand {
        val scanner = adapter?.bluetoothLeScanner ?: error("블루투스를 사용할 수 없어요.")
        repeat(SCAN_ATTEMPTS) { attempt ->
            _state.value = HandPairingState.InProgress(
                "로봇의수를 찾는 중... (${attempt + 1}/$SCAN_ATTEMPTS)"
            )
            scanOnce(scanner, namePrefix, SCAN_WINDOW_MS)?.let { return it }
        }
        error("로봇의수를 찾지 못했어요. 전원과 거리를 확인해 주세요.")
    }

    private suspend fun scanOnce(
        scanner: BluetoothLeScanner,
        namePrefix: String,
        windowMs: Long,
    ): ScannedHand? {
        val found = AtomicReference<ScannedHand?>(null)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                    if (name.lowercase().startsWith(namePrefix.lowercase())) {
                        found.compareAndSet(null, ScannedHand(name, result.device.address))
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
            error("로봇의수 연결 시간이 초과됐어요.")
        }
        return handGatt ?: error("로봇의수 연결에 실패했어요.")
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
                    Log.d(TAG, "로봇의수 GATT 연결됨")
                    val cont = pendingHandConnect
                    pendingHandConnect = null
                    cont?.resume(Unit)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "로봇의수 GATT 연결 끊김 (status=$status)")
                    val cont = pendingHandConnect
                    if (cont != null) {
                        pendingHandConnect = null
                        cont.resumeWithException(IllegalStateException("로봇의수 연결이 끊겼어요."))
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
