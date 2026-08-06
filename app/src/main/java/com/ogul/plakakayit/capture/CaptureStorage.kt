package com.ogul.plakakayit.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.ogul.plakakayit.data.VehicleObservation
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CaptureStorage(context: Context) {
    private val root = File(context.filesDir, "captures").apply { mkdirs() }
    private val logFile = File(root, "capture-log.jsonl")

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

        val directory = File(root, day).apply { mkdirs() }
        val fullFile = File(directory, "${stamp}-${safePlate}-${type.value}-full.jpg")
        val plateFile = normalizedBox
            ?.let { cropPlate(frame, it) }
            ?.let { cropped ->
                File(directory, "${stamp}-${safePlate}-${type.value}-plate.jpg").also { file ->
                    saveJpeg(cropped, file, 95)
                    cropped.recycle()
                }
            }

        try {
            saveJpeg(frame, fullFile, 92)
            appendLog(
                timestamp = now,
                plate = plate,
                mode = mode,
                type = type,
                observation = observation,
                fullFile = fullFile,
                plateFile = plateFile
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

    @Synchronized
    private fun appendLog(
        timestamp: Long,
        plate: String?,
        mode: CaptureMode,
        type: CaptureType,
        observation: VehicleObservation?,
        fullFile: File,
        plateFile: File?
    ) {
        val json = JSONObject()
            .put("timestamp", timestamp)
            .put("plate", plate ?: JSONObject.NULL)
            .put("captureMode", mode.value)
            .put("captureType", type.value)
            .put("fullImagePath", fullFile.absolutePath)
            .put("plateImagePath", plateFile?.absolutePath ?: JSONObject.NULL)
            .put("vehicleType", observation?.type ?: JSONObject.NULL)
            .put("vehicleColor", observation?.color ?: JSONObject.NULL)
            .put("vehicleConfidence", observation?.confidence ?: JSONObject.NULL)
        logFile.appendText(json.toString() + "\n")
    }
}
