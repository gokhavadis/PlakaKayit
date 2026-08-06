package com.ogul.plakakayit.data

enum class MovementType(val value: String) {
    OBSERVATION("observation"),
    ENTRY("entry"),
    EXIT("exit");

    companion object {
        fun fromValue(value: String?): MovementType =
            entries.firstOrNull { it.value == value } ?: OBSERVATION
    }
}

data class MovementEvent(
    val id: Long,
    val type: MovementType,
    val time: Long
)

data class DetectionOutcome(
    val movementType: MovementType,
    val movementChanged: Boolean,
    val isInside: Boolean
)
