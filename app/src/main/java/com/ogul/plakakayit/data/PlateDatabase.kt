package com.ogul.plakakayit.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.max

class PlateDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val crypto = CryptoManager()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_RECORDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                plate_cipher TEXT NOT NULL,
                plate_iv TEXT NOT NULL,
                plate_hash TEXT NOT NULL UNIQUE,
                vehicle_info_cipher TEXT NOT NULL DEFAULT '',
                vehicle_info_iv TEXT NOT NULL DEFAULT '',
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_records_last_seen ON $TABLE_RECORDS(last_seen_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE $TABLE_RECORDS ADD COLUMN vehicle_info_cipher TEXT NOT NULL DEFAULT ''"
            )
            db.execSQL(
                "ALTER TABLE $TABLE_RECORDS ADD COLUMN vehicle_info_iv TEXT NOT NULL DEFAULT ''"
            )
        }
        // V3 uses the existing encrypted vehicle-info columns, so no new SQL column is needed.
    }

    fun upsertPlate(
        plate: String,
        observation: VehicleObservation? = null,
        now: Long = System.currentTimeMillis()
    ) {
        val hash = crypto.hash(plate)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = findExistingByHash(db, hash)
            if (existing != null) {
                db.execSQL(
                    "UPDATE $TABLE_RECORDS SET last_seen_at = ?, seen_count = seen_count + 1 WHERE plate_hash = ?",
                    arrayOf<Any?>(now, hash)
                )

                if (observation != null) {
                    val merged = mergeObservation(existing.vehicleInfo, observation)
                    updateEncryptedVehicleInfo(db, existing.id, merged)
                }
            } else {
                val encryptedPlate = crypto.encrypt(plate)
                val values = ContentValues().apply {
                    put("plate_cipher", encryptedPlate.ciphertext)
                    put("plate_iv", encryptedPlate.iv)
                    put("plate_hash", hash)
                    put("first_seen_at", now)
                    put("last_seen_at", now)
                    put("seen_count", 1)
                }
                if (observation != null) {
                    putVehicleInfo(values, mergeObservation(VehicleInfo(), observation))
                }
                db.insertOrThrow(TABLE_RECORDS, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getRecent(limit: Int = 500): List<PlateRecord> {
        val records = mutableListOf<PlateRecord>()
        readableDatabase.query(
            TABLE_RECORDS,
            arrayOf(
                "id",
                "plate_cipher",
                "plate_iv",
                "vehicle_info_cipher",
                "vehicle_info_iv",
                "first_seen_at",
                "last_seen_at",
                "seen_count"
            ),
            null,
            null,
            null,
            null,
            "last_seen_at DESC",
            limit.coerceIn(1, 1000).toString()
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val cipherIndex = cursor.getColumnIndexOrThrow("plate_cipher")
            val ivIndex = cursor.getColumnIndexOrThrow("plate_iv")
            val infoCipherIndex = cursor.getColumnIndexOrThrow("vehicle_info_cipher")
            val infoIvIndex = cursor.getColumnIndexOrThrow("vehicle_info_iv")
            val firstIndex = cursor.getColumnIndexOrThrow("first_seen_at")
            val lastIndex = cursor.getColumnIndexOrThrow("last_seen_at")
            val countIndex = cursor.getColumnIndexOrThrow("seen_count")

            while (cursor.moveToNext()) {
                val plate = runCatching {
                    crypto.decrypt(cursor.getString(cipherIndex), cursor.getString(ivIndex))
                }.getOrElse { "Şifreli kayıt" }
                val info = decryptVehicleInfo(
                    cursor.getString(infoCipherIndex),
                    cursor.getString(infoIvIndex)
                )

                records += PlateRecord(
                    id = cursor.getLong(idIndex),
                    plate = plate,
                    brand = info.brand,
                    model = info.model,
                    color = info.color,
                    vehicleType = info.vehicleType,
                    aiConfidence = info.aiConfidence,
                    firstSeenAt = cursor.getLong(firstIndex),
                    lastSeenAt = cursor.getLong(lastIndex),
                    seenCount = cursor.getInt(countIndex)
                )
            }
        }
        return records
    }

    fun updateVehicleInfo(id: Long, brand: String, model: String, color: String) {
        val db = writableDatabase
        val existing = findVehicleInfoById(db, id)
        val updated = existing.copy(
            brand = brand.trim(),
            model = model.trim(),
            color = color.trim()
        )
        updateEncryptedVehicleInfo(db, id, updated)
    }

    fun deleteRecord(id: Long) {
        writableDatabase.delete(TABLE_RECORDS, "id = ?", arrayOf(id.toString()))
    }

    fun clearAll() {
        writableDatabase.delete(TABLE_RECORDS, null, null)
    }

    private fun findExistingByHash(db: SQLiteDatabase, hash: String): ExistingRecord? {
        return db.query(
            TABLE_RECORDS,
            arrayOf("id", "vehicle_info_cipher", "vehicle_info_iv"),
            "plate_hash = ?",
            arrayOf(hash),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ExistingRecord(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                vehicleInfo = decryptVehicleInfo(
                    cursor.getString(cursor.getColumnIndexOrThrow("vehicle_info_cipher")),
                    cursor.getString(cursor.getColumnIndexOrThrow("vehicle_info_iv"))
                )
            )
        }
    }

    private fun findVehicleInfoById(db: SQLiteDatabase, id: Long): VehicleInfo {
        return db.query(
            TABLE_RECORDS,
            arrayOf("vehicle_info_cipher", "vehicle_info_iv"),
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use VehicleInfo()
            decryptVehicleInfo(
                cursor.getString(cursor.getColumnIndexOrThrow("vehicle_info_cipher")),
                cursor.getString(cursor.getColumnIndexOrThrow("vehicle_info_iv"))
            )
        }
    }

    private fun mergeObservation(current: VehicleInfo, observation: VehicleObservation): VehicleInfo {
        val shouldUseObservation =
            current.aiConfidence <= 0f || observation.confidence >= current.aiConfidence

        return current.copy(
            color = if (current.color.isBlank()) observation.color else current.color,
            vehicleType = if (shouldUseObservation) observation.type else current.vehicleType,
            aiConfidence = max(current.aiConfidence, observation.confidence)
        )
    }

    private fun updateEncryptedVehicleInfo(db: SQLiteDatabase, id: Long, info: VehicleInfo) {
        val values = ContentValues()
        putVehicleInfo(values, info)
        db.update(TABLE_RECORDS, values, "id = ?", arrayOf(id.toString()))
    }

    private fun putVehicleInfo(values: ContentValues, info: VehicleInfo) {
        if (info.isEmpty()) {
            values.put("vehicle_info_cipher", "")
            values.put("vehicle_info_iv", "")
            return
        }

        val plainText = listOf(
            info.brand,
            info.model,
            info.color,
            info.vehicleType,
            info.aiConfidence.toString()
        ).joinToString(INFO_SEPARATOR)
        val encrypted = crypto.encrypt(plainText)
        values.put("vehicle_info_cipher", encrypted.ciphertext)
        values.put("vehicle_info_iv", encrypted.iv)
    }

    private fun decryptVehicleInfo(ciphertext: String, iv: String): VehicleInfo {
        if (ciphertext.isBlank() || iv.isBlank()) return VehicleInfo()

        return runCatching {
            val parts = crypto.decrypt(ciphertext, iv).split(INFO_SEPARATOR)
            VehicleInfo(
                brand = parts.getOrElse(0) { "" },
                model = parts.getOrElse(1) { "" },
                color = parts.getOrElse(2) { "" },
                vehicleType = parts.getOrElse(3) { "" },
                aiConfidence = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
            )
        }.getOrDefault(VehicleInfo())
    }

    private data class ExistingRecord(
        val id: Long,
        val vehicleInfo: VehicleInfo
    )

    private data class VehicleInfo(
        val brand: String = "",
        val model: String = "",
        val color: String = "",
        val vehicleType: String = "",
        val aiConfidence: Float = 0f
    ) {
        fun isEmpty(): Boolean =
            brand.isBlank() && model.isBlank() && color.isBlank() &&
                vehicleType.isBlank() && aiConfidence <= 0f
    }

    companion object {
        private const val DATABASE_NAME = "plate_records.db"
        private const val DATABASE_VERSION = 3
        private const val TABLE_RECORDS = "plate_records"
        private const val INFO_SEPARATOR = "\u001F"
    }
}
