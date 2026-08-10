package com.mandro.domain.model

import java.util.UUID

data class User(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val hasModel: Boolean = false,
    val lastModelPath: String? = null,
    val lastAccuracy: Float? = null,
    val gestureSet: GestureSet = GestureSet.SIX_CLASS,
    val researchConsent: Boolean = false,
)

enum class GestureSet(val label: String, val classes: List<String>) {
    SIX_CLASS(
        label = "6cl",
        classes = listOf("Rest", "Flexion", "Extension", "Close", "Supination", "Pronation")
    ),
    FOUR_CLASS(
        label = "4cl",
        classes = listOf("Rest", "Flexion", "Extension", "Close")
    ),
}
