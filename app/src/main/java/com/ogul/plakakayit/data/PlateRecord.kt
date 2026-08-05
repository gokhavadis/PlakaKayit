package com.ogul.plakakayit.data

data class PlateRecord(
    val id: Long,
    val plate: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val seenCount: Int
)
