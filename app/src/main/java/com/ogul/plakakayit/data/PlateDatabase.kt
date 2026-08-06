package com.ogul.plakakayit.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import kotlin.math.max

class PlateDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val crypto = CryptoManager()

    override fun onCreate(db: SQLiteDatabase) {
        createRecordsTable(db)
        createMovementsTable(db)
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
        if (oldVersion < 4) {
            db.execSQL(
                "ALTER TABLE $TABLE_RECORDS ADD COLUMN is_inside INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE $TABLE_RECORDS ADD COLUMN last_entry_at INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE $TABLE_RECORDS ADD COLUMN last_exit_at INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE $TABLE_RECORDS ADD COLUMN total_entries INTEGER NOT NULL DEFAULT 0"
            )
            createMovementsTable(db)
        }
    }

    fun upsertPlate(
        plate: String,
        observation: VehicleObservation? = null,
        movementType: MovementType = MovementType.OBSERVATION,
        now: Long = System.currentTimeMillis()
    ): DetectionOutcome {
        val hash = crypto.hash(plate)
        val db = writableDatabase
        var outcome = DetectionOutcome(movementType, false, false)

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

                outcome = applyMovement(
                    db = db,
                    recordId = existing.id,
                    plateHash = hash,
                    currentInside = existing.isInside,
                    movementType = movementType,
                    now = now
                )
            } else {
                val encryptedPlate = crypto.encrypt(plate)
                val initialInside = movementType == MovementType.ENTRY
                val values = ContentValues().apply {
                    put("plate_cipher", encryptedPlate.ciphertext)
                    put("plate_iv", encryptedPlate.iv)
                    put("plate_hash", hash)
                    put("first_seen_at", now)
                    put("last_seen_at", now)
                    put("seen_count", 1)
                    put("is_inside", if (initialInside) 1 else 0)
                    put("last_entry_at", if (movementType == MovementType.ENTRY) now else 0L)
                    put("last_exit_at", if (movementType == MovementType.EXIT) now else 0L)
                    put("total_entries", if (movementType == MovementType.ENTRY) 1 else 0)
                }
                if (observation != null) {
                    putVehicleInfo(values, mergeObservation(VehicleInfo(), observation))
                }
                val id = db.insertOrThrow(TABLE_RECORDS, null, values)
                val changed = movementType != MovementType.OBSERVATION
                if (changed) insertMovement(db, hash, movementType, now)
                outcome = DetectionOutcome(movementType, changed, initialInside)
                if (id <= 0L) error("Kayıt oluşturulamadı")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return outcome
    }

    fun recordManualMovement(
        id: Long,
        movementType: MovementType,
        now: Long = System.currentTimeMillis()
    ): DetectionOutcome {
        require(movementType != MovementType.OBSERVATION) { "Giriş veya çıkış seçilmeli" }
        val db = writableDatabase
        var outcome = DetectionOutcome(movementType, false, false)
        db.beginTransaction()
        try {
            val existing = findExistingById(db, id) ?: error("Kayıt bulunamadı")
            outcome = applyMovement(
                db = db,
                recordId = existing.id,
                plateHash = existing.plateHash,
                currentInside = existing.isInside,
                movementType = movementType,
                now = now
            )
            db.execSQL(
                "UPDATE $TABLE_RECORDS SET last_seen_at = ? WHERE id = ?",
                arrayOf<Any?>(now, id)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return outcome
    }

    fun getRecent(limit: Int = 500): List<PlateRecord> {
        val records = mutableListOf<PlateRecord>()
        readableDatabase.query(
            TABLE_RECORDS,
            RECORD_COLUMNS,
            null,
            null,
            null,
            null,
            "last_seen_at DESC",
            limit.coerceIn(1, 1000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) records += recordFromCursor(cursor)
        }
        return records
    }

    fun getRecord(id: Long): PlateRecord? {
        return readableDatabase.query(
            TABLE_RECORDS,
            RECORD_COLUMNS,
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) recordFromCursor(cursor) else null
        }
    }

    fun getMovementHistory(id: Long, limit: Int = 100): List<MovementEvent> {
        val plateHash = readableDatabase.query(
            TABLE_RECORDS,
            arrayOf("plate_hash"),
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else return emptyList()
        }

        val events = mutableListOf<MovementEvent>()
        readableDatabase.query(
            TABLE_MOVEMENTS,
            arrayOf("id", "event_type", "event_time"),
            "plate_hash = ?",
            arrayOf(plateHash),
            null,
            null,
            "event_time DESC",
            limit.coerceIn(1, 500).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                events += MovementEvent(
                    id = cursor.getLong(0),
                    type = MovementType.fromValue(cursor.getString(1)),
                    time = cursor.getLong(2)
                )
            }
        }
        return events
    }

    fun countInside(): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_RECORDS WHERE is_inside = 1",
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun updateVehicleInfo(id: Long, brand: String, model: String, color: String) {
        val db = writableDatabase
        val existing = findVehicleInfoById(db, id)
        updateEncryptedVehicleInfo(
            db,
            id,
            existing.copy(
                brand = brand.trim(),
                model = model.trim(),
                color = color.trim()
            )
        )
    }

    fun updateVehicleProfile(
        id: Long,
        brand: String,
        model: String,
        color: String,
        category: String,
        note: String
    ) {
        val db = writableDatabase
        val existing = findVehicleInfoById(db, id)
        updateEncryptedVehicleInfo(
            db,
            id,
            existing.copy(
                brand = brand.trim(),
                model = model.trim(),
                color = color.trim(),
                category = category.trim(),
                note = note.trim()
            )
        )
    }

    fun deleteRecord(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val hash = db.query(
                TABLE_RECORDS,
                arrayOf("plate_hash"),
                "id = ?",
                arrayOf(id.toString()),
                null,
                null,
                null,
                "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            db.delete(TABLE_RECORDS, "id = ?", arrayOf(id.toString()))
            if (hash != null) db.delete(TABLE_MOVEMENTS, "plate_hash = ?", arrayOf(hash))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MOVEMENTS, null, null)
            db.delete(TABLE_RECORDS, null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createRecordsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_RECORDS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                plate_cipher TEXT NOT NULL,
                plate_iv TEXT NOT NULL,
                plate_hash TEXT NOT NULL UNIQUE,
                vehicle_info_cipher TEXT NOT NULL DEFAULT '',
                vehicle_info_iv TEXT NOT NULL DEFAULT '',
                is_inside INTEGER NOT NULL DEFAULT 0,
                last_entry_at INTEGER NOT NULL DEFAULT 0,
                last_exit_at INTEGER NOT NULL DEFAULT 0,
                total_entries INTEGER NOT NULL DEFAULT 0,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                seen_count INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_records_last_seen ON $TABLE_RECORDS(last_seen_at DESC)")
        db.execSQL("CREATE INDEX idx_records_inside ON $TABLE_RECORDS(is_inside)")
    }

    private fun createMovementsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MOVEMENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                plate_hash TEXT NOT NULL,
                event_type TEXT NOT NULL,
                event_time INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_movements_plate_time ON $TABLE_MOVEMENTS(plate_hash, event_time DESC)"
        )
    }

    private fun applyMovement(
        db: SQLiteDatabase,
        recordId: Long,
        plateHash: String,
        currentInside: Boolean,
        movementType: MovementType,
        now: Long
    ): DetectionOutcome {
        if (movementType == MovementType.OBSERVATION) {
            return DetectionOutcome(movementType, false, currentInside)
        }

        val shouldChange = when (movementType) {
            MovementType.ENTRY -> !currentInside
            MovementType.EXIT -> currentInside
            MovementType.OBSERVATION -> false
        }
        if (!shouldChange) {
            return DetectionOutcome(movementType, false, currentInside)
        }

        val values = ContentValues()
        val newInside = movementType == MovementType.ENTRY
        values.put("is_inside", if (newInside) 1 else 0)
        if (movementType == MovementType.ENTRY) {
            values.put("last_entry_at", now)
            db.execSQL(
                "UPDATE $TABLE_RECORDS SET total_entries = total_entries + 1 WHERE id = ?",
                arrayOf<Any?>(recordId)
            )
        } else {
            values.put("last_exit_at", now)
        }
        db.update(TABLE_RECORDS, values, "id = ?", arrayOf(recordId.toString()))
        insertMovement(db, plateHash, movementType, now)
        return DetectionOutcome(movementType, true, newInside)
    }

    private fun insertMovement(
        db: SQLiteDatabase,
        plateHash: String,
        movementType: MovementType,
        now: Long
    ) {
        val values = ContentValues().apply {
            put("plate_hash", plateHash)
            put("event_type", movementType.value)
            put("event_time", now)
        }
        db.insertOrThrow(TABLE_MOVEMENTS, null, values)
    }

    private fun findExistingByHash(db: SQLiteDatabase, hash: String): ExistingRecord? {
        return db.query(
            TABLE_RECORDS,
            arrayOf("id", "plate_hash", "vehicle_info_cipher", "vehicle_info_iv", "is_inside"),
            "plate_hash = ?",
            arrayOf(hash),
            null,
            null,
            null,
            "1"
        ).use { cursor -> existingFromCursor(cursor) }
    }

    private fun findExistingById(db: SQLiteDatabase, id: Long): ExistingRecord? {
        return db.query(
            TABLE_RECORDS,
            arrayOf("id", "plate_hash", "vehicle_info_cipher", "vehicle_info_iv", "is_inside"),
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor -> existingFromCursor(cursor) }
    }

    private fun existingFromCursor(cursor: Cursor): ExistingRecord? {
        if (!cursor.moveToFirst()) return null
        return ExistingRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            plateHash = cursor.getString(cursor.getColumnIndexOrThrow("plate_hash")),
            vehicleInfo = decryptVehicleInfo(
                cursor.getString(cursor.getColumnIndexOrThrow("vehicle_info_cipher")),
                cursor.getString(cursor.getColumnIndexOrThrow("vehicle_info_iv"))
            ),
            isInside = cursor.getInt(cursor.getColumnIndexOrThrow("is_inside")) == 1
        )
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

    private fun recordFromCursor(cursor: Cursor): PlateRecord {
        val plate = runCatching {
            crypto.decrypt(
                cursor.getString(cursor.getColumnIndexOrThrow("plate_cipher")),
                cursor.getString(cursor.getColumnIndexOrThrow("plate_iv"))
            )
        }.getOrElse { "Şifreli kayıt" }
        val info = decryptVehicleInfo(
            cursor.getString(cursor.getColumnIndexOrThrow("vehicle_info_cipher")),
            cursor.getString(cursor.getColumnIndexOrThrow("vehicle_info_iv"))
        )
        return PlateRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            plate = plate,
            brand = info.brand,
            model = info.model,
            color = info.color,
            vehicleType = info.vehicleType,
            aiConfidence = info.aiConfidence,
            category = info.category,
            note = info.note,
            isInside = cursor.getInt(cursor.getColumnIndexOrThrow("is_inside")) == 1,
            lastEntryAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_entry_at")),
            lastExitAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_exit_at")),
            totalEntries = cursor.getInt(cursor.getColumnIndexOrThrow("total_entries")),
            firstSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_at")),
            lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at")),
            seenCount = cursor.getInt(cursor.getColumnIndexOrThrow("seen_count"))
        )
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
        val plainText = JSONObject().apply {
            put("brand", info.brand)
            put("model", info.model)
            put("color", info.color)
            put("vehicleType", info.vehicleType)
            put("aiConfidence", info.aiConfidence.toDouble())
            put("category", info.category)
            put("note", info.note)
        }.toString()
        val encrypted = crypto.encrypt(plainText)
        values.put("vehicle_info_cipher", encrypted.ciphertext)
        values.put("vehicle_info_iv", encrypted.iv)
    }

    private fun decryptVehicleInfo(ciphertext: String, iv: String): VehicleInfo {
        if (ciphertext.isBlank() || iv.isBlank()) return VehicleInfo()
        return runCatching {
            val plainText = crypto.decrypt(ciphertext, iv)
            if (plainText.trimStart().startsWith("{")) {
                val json = JSONObject(plainText)
                VehicleInfo(
                    brand = json.optString("brand"),
                    model = json.optString("model"),
                    color = json.optString("color"),
                    vehicleType = json.optString("vehicleType"),
                    aiConfidence = json.optDouble("aiConfidence", 0.0).toFloat(),
                    category = json.optString("category"),
                    note = json.optString("note")
                )
            } else {
                val parts = plainText.split(INFO_SEPARATOR)
                VehicleInfo(
                    brand = parts.getOrElse(0) { "" },
                    model = parts.getOrElse(1) { "" },
                    color = parts.getOrElse(2) { "" },
                    vehicleType = parts.getOrElse(3) { "" },
                    aiConfidence = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
                )
            }
        }.getOrDefault(VehicleInfo())
    }

    private data class ExistingRecord(
        val id: Long,
        val plateHash: String,
        val vehicleInfo: VehicleInfo,
        val isInside: Boolean
    )

    private data class VehicleInfo(
        val brand: String = "",
        val model: String = "",
        val color: String = "",
        val vehicleType: String = "",
        val aiConfidence: Float = 0f,
        val category: String = "",
        val note: String = ""
    ) {
        fun isEmpty(): Boolean =
            brand.isBlank() && model.isBlank() && color.isBlank() &&
                vehicleType.isBlank() && aiConfidence <= 0f &&
                category.isBlank() && note.isBlank()
    }

    companion object {
        private const val DATABASE_NAME = "plate_records.db"
        private const val DATABASE_VERSION = 4
        private const val TABLE_RECORDS = "plate_records"
        private const val TABLE_MOVEMENTS = "movement_events"
        private const val INFO_SEPARATOR = "\u001F"

        private val RECORD_COLUMNS = arrayOf(
            "id",
            "plate_cipher",
            "plate_iv",
            "vehicle_info_cipher",
            "vehicle_info_iv",
            "is_inside",
            "last_entry_at",
            "last_exit_at",
            "total_entries",
            "first_seen_at",
            "last_seen_at",
            "seen_count"
        )
    }
}
