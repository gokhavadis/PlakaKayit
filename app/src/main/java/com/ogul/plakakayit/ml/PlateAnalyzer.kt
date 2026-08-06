package com.ogul.plakakayit.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ogul.plakakayit.data.VehicleObservation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PlateAnalyzer(
    context: Context,
    aiEnabled: Boolean,
    aiThreshold: Float,
    private val onPlateDetected: (String, VehicleObservation?) -> Unit,
    private val onVehicleObserved: (VehicleObservation?) -> Unit = {},
    private val onAnalyzerError: (Throwable) -> Unit = {}
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val vehicleDetector = if (aiEnabled) {
        runCatching { VehicleDetector(context.applicationContext, aiThreshold) }
            .onFailure(onAnalyzerError)
            .getOrNull()
    } else {
        null
    }
    private val processing = AtomicBoolean(false)
    private val recentlyReported = ConcurrentHashMap<String, Long>()
    private var lastAnalysisStartedAt = 0L

    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisStartedAt < ANALYSIS_INTERVAL_MS ||
            !processing.compareAndSet(false, true)
        ) {
            imageProxy.close()
            return
        }
        lastAnalysisStartedAt = now

        val rawBitmap = runCatching { imageProxy.toBitmap() }
            .onFailure(onAnalyzerError)
            .getOrNull()
        val rotation = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        if (rawBitmap == null) {
            processing.set(false)
            return
        }

        val bitmap = rotateBitmap(rawBitmap, rotation)
        if (bitmap !== rawBitmap) rawBitmap.recycle()

        val vehicle = runCatching { vehicleDetector?.detect(bitmap) }
            .onFailure(onAnalyzerError)
            .getOrNull()
        onVehicleObserved(vehicle)

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { block -> block.lines.map { it.text } }
                PlateParser.findPlates(lines).forEach { plate ->
                    val previous = recentlyReported[plate] ?: 0L
                    if (now - previous >= SAME_PLATE_COOLDOWN_MS) {
                        recentlyReported[plate] = now
                        onPlateDetected(plate, vehicle)
                    }
                }
                pruneOldEntries(now)
            }
            .addOnFailureListener(onAnalyzerError)
            .addOnCompleteListener {
                bitmap.recycle()
                processing.set(false)
            }
    }

    fun close() {
        recognizer.close()
        vehicleDetector?.close()
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun pruneOldEntries(now: Long) {
        recentlyReported.entries.removeIf { now - it.value > CACHE_RETENTION_MS }
    }

    companion object {
        private const val ANALYSIS_INTERVAL_MS = 750L
        private const val SAME_PLATE_COOLDOWN_MS = 20_000L
        private const val CACHE_RETENTION_MS = 120_000L
    }
}
