package com.mandro.data.local

import android.content.Context
import com.mandro.data.local.db.UserDao
import com.mandro.data.local.db.UserEntity
import com.mandro.domain.model.User
import com.mandro.domain.repository.UserRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userDao: UserDao,
) : UserRepository {

    // UserEntity.toDomain()은 hasModel=false로 고정돼 있음 (DB에 그 정보가 없어서) —
    // 실제로는 파일 시스템에 weights.bin이 있는지로 판단해야 함.
    private fun hasWeightsFile(userId: String): Boolean =
        File(context.filesDir, "models/$userId/weights.bin").exists()

    private fun UserEntity.toDomainWithModelCheck(): User =
        toDomain().copy(hasModel = hasWeightsFile(id))

    override fun getUsers(): Flow<List<User>> =
        userDao.getAll().map { entities -> entities.map { it.toDomainWithModelCheck() } }

    override suspend fun getUserById(id: String): User? =
        userDao.getById(id)?.toDomainWithModelCheck()

    override suspend fun createUser(name: String, researchConsent: Boolean): User {
        val user = User(name = name, researchConsent = researchConsent) // id는 UUID로 자동 생성
        userDao.insert(UserEntity.fromDomain(user))
        return user
    }

    override suspend fun updateUser(user: User) {
        userDao.insert(UserEntity.fromDomain(user)) // REPLACE 전략이라 upsert로 동작
    }

    override suspend fun deleteUser(id: String) {
        userDao.deleteById(id)
        File(context.filesDir, "models/$id").deleteRecursively()
    }

    override suspend fun cleanupOrphanedModels() {
        val modelsDir = File(context.filesDir, "models")
        val knownIds = userDao.getAllIds().toSet()
        modelsDir.listFiles { file -> file.isDirectory }
            ?.filter { it.name !in knownIds }
            ?.forEach { it.deleteRecursively() }
    }
}
