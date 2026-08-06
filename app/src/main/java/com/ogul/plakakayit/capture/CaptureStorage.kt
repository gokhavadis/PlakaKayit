package com.ogul.plakakayit.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.ogul.plakakayit.data.VehicleObservation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CaptureStorage(context: Context) {
    private val root = File(context.filesDir, "captures").apply { mkdirs() }
    private val logFile = File(root, "capture-log.jsonl")
    private val metadataFile = File(root, "capture-metadata.json")

    @Synchronized
    fun save(
        frame: Bitmap,
        plate: String?,
        normalizedBox: RectF?,
        mode: CaptureMode,
        type: CaptureType,
        observation: VehicleObservation?
    ): CaptureResult {
        val now = System.currentTimeMillis()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(now))
        val safePlate = plate.orEmpty()
            .uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9]"), "")
            .ifBlank { "PLAKASIZ" }
        val id = "$stamp-$safePlate-${type.value}"

        val directory = File(root, day).apply { mkdirs() }
        val fullFile = File(directory, "$id-full.jpg")
        val plateFile = normalizedBox
            ?.let { cropPlate(frame, it) }
            ?.let { cropped ->
                File(directory, "$id-plate.jpg").also { file ->
                    saveJpeg(cropped, file, 95)
                    cropped.recycle()
                }
            }

        try {
            saveJpeg(frame, fullFile, 92)
            val sha256 = sha256File(fullFile)
            appendLog(
                id = id,
                timestamp = now,
                plate = plate,
                mode = mode,
                type = type,
                observation = observation,
                fullFile = fullFile,
                plateFile = plateFile,
                sha256 = sha256
            )
            return CaptureResult(
                fullImagePath = fullFile.absolutePath,
                plateImagePath = plateFile?.absolutePath,
                logPath = logFile.absolutePath
            )
        } finally {
            if (!frame.isRecycled) frame.recycle()
        }
    }

    @Synchronized
    fun loadAll(): List<CaptureEntry> {
        if (!logFile.exists()) return emptyList()
        val metadata = readMetadata()
        return logFile.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching {
                    val json = JSONObject(line)
                    val fullPath = json.optString("fullImagePath")
                    if (fullPath.isBlank() || !File(fullPath).exists()) return@runCatching null
                    val id = json.optString("id").ifBlank {
                        File(fullPath).name
                            .removeSuffix("-full.jpg")
                            .removeSuffix(".jpg")
                    }
                    val extra = metadata.optJSONObject(id) ?: JSONObject()
                    CaptureEntry(
                        id = id,
                        timestamp = json.optLong("timestamp"),
                        plate = nullableString(json, "plate"),
                        captureMode = CaptureMode.fromValue(json.optString("captureMode")),
                        captureType = CaptureType.fromValue(json.optString("captureType")),
                        fullImagePath = fullPath,
                        plateImagePath = nullableString(json, "plateImagePath")
                            ?.takeIf { File(it).exists() },
                        vehicleType = nullableString(extra, "vehicleType")
                            ?: nullableString(json, "vehicleType"),
                        vehicleColor = nullableString(extra, "vehicleColor")
                            ?: nullableString(json, "vehicleColor"),
                        vehicleConfidence = nullableFloat(extra, "vehicleConfidence")
                            ?: nullableFloat(json, "vehicleConfidence"),
                        correctedPlate = nullableString(extra, "correctedPlate"),
                        note = extra.optString("note"),
                        favorite = extra.optBoolean("favorite", false),
                        manualBrand = extra.optString("manualBrand"),
                        manualModel = extra.optString("manualModel"),
                        sha256 = nullableString(extra, "sha256")
                            ?: nullableString(json, "sha256")
                    )
                }.getOrNull()
            }.filterNotNull().sortedByDescending { it.timestamp }.toList()
        }
    }

    @Synchronized
    fun updateUserData(
        id: String,
        correctedPlate: String?,
        note: String,
        favorite: Boolean,
        manualBrand: String,
        manualModel: String
    ) {
        updateMetadata(id) { json ->
            json.put("correctedPlate", correctedPlate?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            json.put("note", note.trim())
            json.put("favorite", favorite)
            json.put("manualBrand", manualBrand.trim())
            json.put("manualModel", manualModel.trim())
        }
    }

    @Synchronized
    fun updateAnalysis(id: String, observation: VehicleObservation) {
        updateMetadata(id) { json ->
            json.put("vehicleType", observation.type)
            json.put("vehicleColor", observation.color)
            json.put("vehicleConfidence", observation.confidence.toDouble())
        }
    }

    @Synchronized
    fun ensureSha256(entry: CaptureEntry): String {
        entry.sha256?.takeIf { it.isNotBlank() }?.let { return it }
        val value = sha256File(File(entry.fullImagePath))
        updateMetadata(entry.id) { it.put("sha256", value) }
        return value
    }

    @Synchronized
    fun delete(entry: CaptureEntry) {
        File(entry.fullImagePath).delete()
        entry.plateImagePath?.let { File(it).delete() }

        if (logFile.exists()) {
            val kept = logFile.readLines().filter { line ->
                runCatching {
                    JSONObject(line).optString("fullImagePath") != entry.fullImagePath
                }.getOrDefault(true)
            }
            logFile.writeText(
                if (kept.isEmpty()) "" else kept.joinToString(separator = "\n", postfix = "\n")
            )
        }

        val metadata = readMetadata()
        metadata.remove(entry.id)
        writeMetadata(metadata)
        File(entry.fullImagePath).parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
    }

    @Synchronized
    fun totalBytes(entries: List<CaptureEntry>): Long = entries.sumOf { entry ->
        File(entry.fullImagePath).takeIf { it.exists() }?.length().orZero() +
            (entry.plateImagePath?.let(::File)?.takeIf { it.exists() }?.length().orZero())
    }

    @Synchronized
    fun exportZip(entries: List<CaptureEntry>, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            val manifest = JSONArray()
            entries.forEach { entry ->
                val fullFile = File(entry.fullImagePath)
                val plateFile = entry.plateImagePath?.let(::File)
                val safeId = entry.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (fullFile.exists()) addFile(zip, fullFile, "images/$safeId-full.jpg")
                if (plateFile?.exists() == true) addFile(zip, plateFile, "images/$safeId-plate.jpg")

                manifest.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("timestamp", entry.timestamp)
                        .put("plate", entry.plate ?: JSONObject.NULL)
                        .put("correctedPlate", entry.correctedPlate ?: JSONObject.NULL)
                        .put("captureMode", entry.captureMode.value)
                        .put("captureType", entry.captureType.value)
                        .put("vehicleType", entry.vehicleType ?: JSONObject.NULL)
                        .put("vehicleColor", entry.vehicleColor ?: JSONObject.NULL)
                        .put("vehicleConfidence", entry.vehicleConfidence ?: JSONObject.NULL)
                        .put("manualBrand", entry.manualBrand)
                        .put("manualModel", entry.manualModel)
                        .put("note", entry.note)
                        .put("favorite", entry.favorite)
                        .put("sha256", ensureSha256(entry))
                )
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun saveJpeg(bitmap: Bitmap, file: File, quality: Int) {
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                "Fotoğraf JPEG olarak kaydedilemedi"
            }
        }
    }

    private fun cropPlate(frame: Bitmap, normalizedBox: RectF): Bitmap? {
        val padX = 0.035f
        val padY = 0.025f
        val left = ((normalizedBox.left - padX) * frame.width).toInt()
            .coerceIn(0, frame.width - 1)
        val top = ((normalizedBox.top - padY) * frame.height).toInt()
            .coerceIn(0, frame.height - 1)
        val right = ((normalizedBox.right + padX) * frame.width).toInt()
            .coerceIn(left + 1, frame.width)
        val bottom = ((normalizedBox.bottom + padY) * frame.height).toInt()
            .coerceIn(top + 1, frame.height)

        val width = right - left
        val height = bottom - top
        if (width < 20 || height < 10) return null
        return Bitmap.createBitmap(frame, left, top, width, height)
    }

    private fun appendLog(
        id: String,
        timestamp: Long,
        plate: String?,
        mode: CaptureMode,
        type: CaptureType,
        observation: VehicleObservation?,
        fullFile: File,
        plateFile: File?,
        sha256: String
    ) {
        val json = JSONObject()
            .put("id", id)
            .put("timestamp", timestamp)
            .put("plate", plate ?: JSONObject.NULL)
            .put("captureMode", mode.value)
            .put("captureType", type.value)
            .put("fullImagePath", fullFile.absolutePath)
            .put("plateImagePath", plateFile?.absolutePath ?: JSONObject.NULL)
            .put("vehicleType", observation?.type ?: JSONObject.NULL)
            .put("vehicleColor", observation?.color ?: JSONObject.NULL)
            .put("vehicleConfidence", observation?.confidence ?: JSONObject.NULL)
            .put("sha256", sha256)
        logFile.appendText(json.toString() + "\n")
    }

    private fun nullableString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optString(key).takeIf { it.isNotBlank() }
    }

    private fun nullableFloat(json: JSONObject, key: String): Float? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optDouble(key, Double.NaN).takeUnless { it.isNaN() }?.toFloat()
    }

    private fun updateMetadata(id: String, block: (JSONObject) -> Unit) {
        val metadata = readMetadata()
        val item = metadata.optJSONObject(id) ?: JSONObject()
        block(item)
        metadata.put(id, item)
        writeMetadata(metadata)
    }

    private fun readMetadata(): JSONObject = runCatching {
        if (metadataFile.exists()) JSONObject(metadataFile.readText()) else JSONObject()
    }.getOrElse { JSONObject() }

    private fun writeMetadata(metadata: JSONObject) {
        val temporary = File(root, "capture-metadata.tmp")
        temporary.writeText(metadata.toString())
        if (!temporary.renameTo(metadataFile)) {
            metadataFile.writeText(metadata.toString())
            temporary.delete()
        }
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
