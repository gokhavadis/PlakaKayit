package com.ogul.plakakayit.data

data class PlateRecord(
    val id: Long,
    val plate: String,
    val brand: String,
    val model: String,
    val color: String,
    val vehicleType: String,
    val aiConfidence: Float,
    val category: String,
    val note: String,
    val isInside: Boolean,
    val lastEntryAt: Long,
    val lastExitAt: Long,
    val totalEntries: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int
)
