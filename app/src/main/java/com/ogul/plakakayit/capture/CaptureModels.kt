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
    AUTO("auto");

    companion object {
        fun fromValue(value: String?): CaptureType =
            entries.firstOrNull { it.value == value } ?: AUTO
    }
}

data class CaptureResult(
    val fullImagePath: String,
    val plateImagePath: String?,
    val logPath: String
)

data class CaptureEntry(
    val id: String,
    val timestamp: Long,
    val plate: String?,
    val captureMode: CaptureMode,
    val captureType: CaptureType,
    val fullImagePath: String,
    val plateImagePath: String?,
    val vehicleType: String?,
    val vehicleColor: String?,
    val vehicleConfidence: Float?,
    val correctedPlate: String?,
    val note: String,
    val favorite: Boolean,
    val manualBrand: String,
    val manualModel: String,
    val sha256: String?
) {
    val displayPlate: String
        get() = correctedPlate?.takeIf { it.isNotBlank() }
            ?: plate?.takeIf { it.isNotBlank() }
            ?: "Plaka yok"
}
