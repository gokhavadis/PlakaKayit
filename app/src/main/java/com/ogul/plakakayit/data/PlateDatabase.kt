package com.ogul.plakakayit.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
    }

    fun upsertPlate(plate: String, now: Long = System.currentTimeMillis()) {
        val hash = crypto.hash(plate)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val exists = db.rawQuery(
                "SELECT 1 FROM $TABLE_RECORDS WHERE plate_hash = ? LIMIT 1",
                arrayOf(hash)
            ).use { it.moveToFirst() }

            if (exists) {
                db.execSQL(
                    "UPDATE $TABLE_RECORDS SET last_seen_at = ?, seen_count = seen_count + 1 WHERE plate_hash = ?",
                    arrayOf<Any?>(now, hash)
                )
            } else {
                val encrypted = crypto.encrypt(plate)
                db.insertOrThrow(
                    TABLE_RECORDS,
                    null,
                    ContentValues().apply {
                        put("plate_cipher", encrypted.ciphertext)
                        put("plate_iv", encrypted.iv)
                        put("plate_hash", hash)
                        put("first_seen_at", now)
                        put("last_seen_at", now)
                        put("seen_count", 1)
                    }
                )
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

                val vehicleInfo = decryptVehicleInfo(
                    cursor.getString(infoCipherIndex),
                    cursor.getString(infoIvIndex)
                )

                records += PlateRecord(
                    id = cursor.getLong(idIndex),
                    plate = plate,
                    brand = vehicleInfo.brand,
                    model = vehicleInfo.model,
                    color = vehicleInfo.color,
                    firstSeenAt = cursor.getLong(firstIndex),
                    lastSeenAt = cursor.getLong(lastIndex),
                    seenCount = cursor.getInt(countIndex)
                )
            }
        }
        return records
    }

    fun updateVehicleInfo(id: Long, brand: String, model: String, color: String) {
        val cleanBrand = brand.trim()
        val cleanModel = model.trim()
        val cleanColor = color.trim()
        val values = ContentValues()

        if (cleanBrand.isBlank() && cleanModel.isBlank() && cleanColor.isBlank()) {
            values.put("vehicle_info_cipher", "")
            values.put("vehicle_info_iv", "")
        } else {
            val plainText = listOf(cleanBrand, cleanModel, cleanColor).joinToString(INFO_SEPARATOR)
            val encrypted = crypto.encrypt(plainText)
            values.put("vehicle_info_cipher", encrypted.ciphertext)
            values.put("vehicle_info_iv", encrypted.iv)
        }

        writableDatabase.update(
            TABLE_RECORDS,
            values,
            "id = ?",
            arrayOf(id.toString())
        )
    }

    fun deleteRecord(id: Long) {
        writableDatabase.delete(TABLE_RECORDS, "id = ?", arrayOf(id.toString()))
    }

    fun clearAll() {
        writableDatabase.delete(TABLE_RECORDS, null, null)
    }

    private fun decryptVehicleInfo(ciphertext: String, iv: String): VehicleInfo {
        if (ciphertext.isBlank() || iv.isBlank()) return VehicleInfo()

        return runCatching {
            val parts = crypto.decrypt(ciphertext, iv).split(INFO_SEPARATOR, limit = 3)
            VehicleInfo(
                brand = parts.getOrElse(0) { "" },
                model = parts.getOrElse(1) { "" },
                color = parts.getOrElse(2) { "" }
            )
        }.getOrDefault(VehicleInfo())
    }

    private data class VehicleInfo(
        val brand: String = "",
        val model: String = "",
        val color: String = ""
    )

    companion object {
        private const val DATABASE_NAME = "plate_records.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_RECORDS = "plate_records"
        private const val INFO_SEPARATOR = "\u001F"
    }
}
