package com.ogul.plakakayit.ml

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PlateAnalyzer(
    private val onPlateDetected: (String) -> Unit,
    private val onAnalyzerError: (Throwable) -> Unit = {}
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val processing = AtomicBoolean(false)
    private val recentlyReported = ConcurrentHashMap<String, Long>()
    private var lastAnalysisStartedAt = 0L

    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisStartedAt < ANALYSIS_INTERVAL_MS || !processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastAnalysisStartedAt = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            processing.set(false)
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { block -> block.lines.map { it.text } }
                PlateParser.findPlates(lines).forEach { plate ->
                    val previous = recentlyReported[plate] ?: 0L
                    if (now - previous >= SAME_PLATE_COOLDOWN_MS) {
                        recentlyReported[plate] = now
                        onPlateDetected(plate)
                    }
                }
                pruneOldEntries(now)
            }
            .addOnFailureListener(onAnalyzerError)
            .addOnCompleteListener {
                processing.set(false)
                imageProxy.close()
            }
    }

    fun close() {
        recognizer.close()
    }

    private fun pruneOldEntries(now: Long) {
        recentlyReported.entries.removeIf { now - it.value > CACHE_RETENTION_MS }
    }

    companion object {
        private const val ANALYSIS_INTERVAL_MS = 650L
        private const val SAME_PLATE_COOLDOWN_MS = 20_000L
        private const val CACHE_RETENTION_MS = 120_000L
    }
}
