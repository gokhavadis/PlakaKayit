package com.ogul.plakakayit.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ogul.plakakayit.capture.CaptureMode
import com.ogul.plakakayit.data.VehicleObservation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PlateAnalyzer(
    context: Context,
    aiEnabled: Boolean,
    aiThreshold: Float,
    private val captureMode: CaptureMode,
    private val onPlatePreview: (String, RectF, Int, Int) -> Unit = { _, _, _, _ -> },
    private val onPlateDetected: (String, VehicleObservation?, Bitmap, RectF) -> Unit,
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
    private val candidateHits = ConcurrentHashMap<String, CandidateHit>()
    private var lastAnalysisStartedAt = 0L

    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisStartedAt < analysisIntervalMs() ||
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
                val candidates = buildList {
                    result.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            val plate = PlateParser.findPlates(listOf(line.text))
                                .firstOrNull()
                                ?: return@forEach
                            val rect = line.boundingBox ?: return@forEach
                            val normalized = RectF(
                                rect.left.toFloat() / bitmap.width,
                                rect.top.toFloat() / bitmap.height,
                                rect.right.toFloat() / bitmap.width,
                                rect.bottom.toFloat() / bitmap.height
                            )
                            add(PlateCandidate(plate, normalized))
                        }
                    }
                }

                candidates.forEach { candidate ->
                    onPlatePreview(candidate.plate, candidate.box, bitmap.width, bitmap.height)
                    val hit = updateCandidateHit(candidate.plate, now)
                    val requiredHits = if (captureMode == CaptureMode.PARK) 2 else 1
                    val previous = recentlyReported[candidate.plate] ?: 0L
                    if (hit.count >= requiredHits &&
                        now - previous >= samePlateCooldownMs()
                    ) {
                        recentlyReported[candidate.plate] = now
                        candidateHits.remove(candidate.plate)
                        val frameCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        onPlateDetected(candidate.plate, vehicle, frameCopy, candidate.box)
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

    private fun updateCandidateHit(plate: String, now: Long): CandidateHit {
        val previous = candidateHits[plate]
        val next = if (previous != null && now - previous.lastSeenAt <= HIT_WINDOW_MS) {
            CandidateHit(previous.count + 1, now)
        } else {
            CandidateHit(1, now)
        }
        candidateHits[plate] = next
        return next
    }

    private fun analysisIntervalMs(): Long =
        if (captureMode == CaptureMode.DRIVE) 320L else 700L

    private fun samePlateCooldownMs(): Long =
        if (captureMode == CaptureMode.DRIVE) 8_000L else 20_000L

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun pruneOldEntries(now: Long) {
        recentlyReported.entries.removeIf { now - it.value > CACHE_RETENTION_MS }
        candidateHits.entries.removeIf { now - it.value.lastSeenAt > HIT_WINDOW_MS }
    }

    private data class PlateCandidate(val plate: String, val box: RectF)
    private data class CandidateHit(val count: Int, val lastSeenAt: Long)

    companion object {
        private const val HIT_WINDOW_MS = 1_800L
        private const val CACHE_RETENTION_MS = 120_000L
    }
}
