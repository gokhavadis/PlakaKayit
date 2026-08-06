package com.ogul.plakakayit.data

data class PlateRecord(
    val id: Long,
    val plate: String,
    val brand: String,
    val model: String,
    val color: String,
    val vehicleType: String,
    val aiConfidence: Float,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int
)
