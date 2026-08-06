package com.ogul.plakakayit.capture

enum class CaptureMode(val value: String) {
    PARK("park"),
    DRIVE("drive");

    companion object {
        fun fromValue(value: String?): CaptureMode =
            entries.firstOrNull { it.value == value } ?: PARK
    }
}

enum class CaptureType(val value: String) {
    MANUAL("manual"),
    AUTO("auto")
}

data class CaptureResult(
    val fullImagePath: String,
    val plateImagePath: String?,
    val logPath: String
)
