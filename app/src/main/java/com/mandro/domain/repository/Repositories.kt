package com.mandro.domain.repository

import com.mandro.domain.model.*
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun getUsers(): Flow<List<User>>
    suspend fun getUserById(id: String): User?
    suspend fun createUser(name: String, researchConsent: Boolean): User
    suspend fun updateUser(user: User)
    suspend fun deleteUser(id: String)

    // users 테이블에 없는 userId의 models/ 폴더(고아 폴더)를 정리.
    // Room fallbackToDestructiveMigration()이 DB만 초기화하고 파일은 안 지워서
    // 스키마 버전이 바뀔 때마다 고아 폴더가 남을 수 있음 — 앱 시작 시 호출해서 청소.
    suspend fun cleanupOrphanedModels()
}

interface EmgRepository {
    // 녹화 데이터 로컬 저장
    suspend fun saveTake(userId: String, take: RecordingTake)
    suspend fun getBatch(userId: String): RecordingBatch?
    suspend fun clearBatch(userId: String)

    // 랩 재녹화 시 이전에 저장된 해당 랩(takeIndex)의 take들을 로컬에서 제거
    suspend fun deleteTakesForLap(userId: String, takeIndex: Int)
}

interface LocalTrainingRepository {
    // Chaquopy로 폰 안에서 학습 → 가중치 바이너리(52,248 bytes) 반환
    suspend fun trainLocally(batch: RecordingBatch): Result<ByteArray>
}

interface BleRepository {
    val bleState: Flow<BleState>
    val emgStream: Flow<EmgSample>
    val inferenceStream: Flow<InferenceResult>  // BLE Characteristic ...57
    val weightTransferState: Flow<WeightTransferState>  // BLE Characteristic ...58
    val handPairingState: Flow<HandPairingState>  // BLE Characteristic ...59

    suspend fun startScan()
    suspend fun stopScan()
    suspend fun connect(device: BleDevice)
    suspend fun disconnect()

    // weightsBytes: NN 가중치 + StandardScaler(mean/std) 페이로드 (53,304 bytes)
    suspend fun sendWeights(weightsBytes: ByteArray): Result<Unit>

    // 로봇의수를 스캔 → 연결 → MAC을 암밴드 NVS에 기록 → 검증까지 한 번에 수행.
    // 암밴드에 이미 연결돼 있어야 함(Settings 탭 진입 조건).
    suspend fun pairHand(handNamePrefix: String = DEFAULT_HAND_NAME_PREFIX): Result<String>

    // 현재 암밴드 NVS에 저장된 로봇의수 MAC을 읽음. null이면 아직 페어링 안 됨.
    suspend fun checkPairedHandMac(): Result<String?>

    // 암밴드 NVS에 저장된 로봇의수 MAC을 삭제.
    suspend fun clearPairedHand(): Result<Unit>
}

interface UsbRepository {
    val usbState: Flow<UsbState>
    // weightsBytes: NN 가중치 + StandardScaler(mean/std)를 합친 페이로드 (53,304 bytes)
    suspend fun flash(weightsBytes: ByteArray): Result<Unit>
}

