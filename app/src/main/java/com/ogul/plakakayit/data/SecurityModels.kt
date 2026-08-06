package com.ogul.plakakayit.data

data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class PersonObservation(
    val trackId: Int,
    val box: NormalizedBox,
    val upperColor: String,
    val lowerColor: String,
    val accessory: String,
    val movement: String,
    val direction: String,
    val dwellSeconds: Int,
    val faceVisibility: String,
    val faceQuality: Int,
    val confidence: Float,
    val inRestrictedZone: Boolean
)

enum class SecurityEventType(val value: String, val displayName: String) {
    RESTRICTED_ZONE("restricted_zone", "Kısıtlı bölge"),
    FAST_MOVEMENT("fast_movement", "Hızlı hareket"),
    LONG_DWELL("long_dwell", "Uzun süre bekleme");

    companion object {
        fun fromValue(value: String): SecurityEventType =
            entries.firstOrNull { it.value == value } ?: RESTRICTED_ZONE
    }
}

data class SecurityEventDraft(
    val trackId: Int,
    val type: SecurityEventType,
    val summary: String,
    val confidence: Float,
    val upperColor: String,
    val lowerColor: String,
    val accessory: String,
    val movement: String,
    val direction: String,
    val dwellSeconds: Int,
    val faceVisibility: String,
    val faceQuality: Int,
    val occurredAt: Long
)

data class SecurityFrameResult(
    val persons: List<PersonObservation>,
    val events: List<SecurityEventDraft>,
    val plates: List<String> = emptyList()
)

data class SecurityEvent(
    val id: Long,
    val trackId: Int,
    val type: SecurityEventType,
    val summary: String,
    val confidence: Float,
    val upperColor: String,
    val lowerColor: String,
    val accessory: String,
    val movement: String,
    val direction: String,
    val dwellSeconds: Int,
    val faceVisibility: String,
    val faceQuality: Int,
    val linkedPlate: String,
    val occurredAt: Long
)
