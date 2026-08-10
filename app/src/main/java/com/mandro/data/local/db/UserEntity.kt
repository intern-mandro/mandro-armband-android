package com.mandro.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.mandro.domain.model.GestureSet
import com.mandro.domain.model.User

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val gestureSet: String,           // GestureSet.label ("6cl" | "4cl")
    val researchConsent: Boolean,
) {
    fun toDomain(): User = User(
        id = id,
        name = name,
        createdAt = createdAt,
        hasModel = false,
        lastModelPath = null,
        lastAccuracy = null,
        gestureSet = GestureSet.entries.first { it.label == gestureSet },
        researchConsent = researchConsent,
    )

    companion object {
        fun fromDomain(user: User): UserEntity = UserEntity(
            id = user.id,
            name = user.name,
            createdAt = user.createdAt,
            gestureSet = user.gestureSet.label,
            researchConsent = user.researchConsent,
        )
    }
}
