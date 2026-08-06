package com.ogul.plakakayit.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ogul.plakakayit.data.SecurityFrameResult
import com.ogul.plakakayit.ml.PlateParser
import java.util.concurrent.atomic.AtomicBoolean

class SecurityFrameAnalyzer(
    context: Context,
    threshold: Float,
    private val restrictedZoneEnabled: () -> Boolean,
    private val dwellThresholdSeconds: () -> Int,
    private val onResult: (SecurityFrameResult) -> Unit,
    private val onError: (Throwable) -> Unit = {}
) : ImageAnalysis.Analyzer {

    private val objectDetector = SecurityObjectDetector(context.applicationContext, threshold)
    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()
            .setMinFaceSize(0.08f)
            .build()
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val tracker = SecurityTracker()
    private val processing = AtomicBoolean(false)
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
            .onFailure(onError)
            .getOrNull()
        val rotation = imageProxy.imageInfo.rotationDegrees
        imageProxy.close()

        if (rawBitmap == null) {
            processing.set(false)
            return
        }

        val bitmap = rotateBitmap(rawBitmap, rotation)
        if (bitmap !== rawBitmap) rawBitmap.recycle()

        val scene = runCatching { objectDetector.detect(bitmap) }
            .onFailure(onError)
            .getOrElse { SecurityScene(emptyList(), emptyList()) }
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faceTask = faceDetector.process(inputImage)
        val textTask = textRecognizer.process(inputImage)

        Tasks.whenAllComplete(faceTask, textTask)
            .addOnCompleteListener {
                try {
                    val faces = if (faceTask.isSuccessful) {
                        faceTask.result ?: emptyList()
                    } else {
                        faceTask.exception?.let(onError)
                        emptyList()
                    }
                    val plates = if (textTask.isSuccessful) {
                        val text = textTask.result
                        val lines = text?.textBlocks.orEmpty().flatMap { block ->
                            block.lines.map { line -> line.text }
                        }
                        PlateParser.findPlates(lines)
                    } else {
                        textTask.exception?.let(onError)
                        emptyList()
                    }
                    val result = tracker.update(
                        bitmap = bitmap,
                        scene = scene,
                        faces = faces,
                        now = now,
                        restrictedZoneEnabled = restrictedZoneEnabled(),
                        dwellThresholdSeconds = dwellThresholdSeconds().coerceIn(5, 120)
                    ).copy(plates = plates)
                    onResult(result)
                } catch (error: Throwable) {
                    onError(error)
                } finally {
                    bitmap.recycle()
                    processing.set(false)
                }
            }
    }

    fun close() {
        objectDetector.close()
        faceDetector.close()
        textRecognizer.close()
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    companion object {
        private const val ANALYSIS_INTERVAL_MS = 900L
    }
}
