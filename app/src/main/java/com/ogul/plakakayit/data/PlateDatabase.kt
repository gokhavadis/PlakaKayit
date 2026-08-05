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
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_records_last_seen ON $TABLE_RECORDS(last_seen_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No migration is needed for schema version 1.
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

    fun getRecent(limit: Int = 100): List<PlateRecord> {
        val records = mutableListOf<PlateRecord>()
        readableDatabase.query(
            TABLE_RECORDS,
            arrayOf("id", "plate_cipher", "plate_iv", "first_seen_at", "last_seen_at", "seen_count"),
            null,
            null,
            null,
            null,
            "last_seen_at DESC",
            limit.coerceIn(1, 500).toString()
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val cipherIndex = cursor.getColumnIndexOrThrow("plate_cipher")
            val ivIndex = cursor.getColumnIndexOrThrow("plate_iv")
            val firstIndex = cursor.getColumnIndexOrThrow("first_seen_at")
            val lastIndex = cursor.getColumnIndexOrThrow("last_seen_at")
            val countIndex = cursor.getColumnIndexOrThrow("seen_count")

            while (cursor.moveToNext()) {
                val plate = runCatching {
                    crypto.decrypt(cursor.getString(cipherIndex), cursor.getString(ivIndex))
                }.getOrElse { "Şifreli kayıt" }

                records += PlateRecord(
                    id = cursor.getLong(idIndex),
                    plate = plate,
                    firstSeenAt = cursor.getLong(firstIndex),
                    lastSeenAt = cursor.getLong(lastIndex),
                    seenCount = cursor.getInt(countIndex)
                )
            }
        }
        return records
    }

    fun clearAll() {
        writableDatabase.delete(TABLE_RECORDS, null, null)
    }

    companion object {
        private const val DATABASE_NAME = "plate_records.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_RECORDS = "plate_records"
    }
}
