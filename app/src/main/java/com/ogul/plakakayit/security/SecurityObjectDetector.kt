package com.ogul.plakakayit.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

class SecurityObjectDetector(
    context: Context,
    threshold: Float
) {
    private var detector: ObjectDetector? = null

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_NAME)
            .setDelegate(Delegate.CPU)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setScoreThreshold(threshold.coerceIn(0.30f, 0.90f))
            .setMaxResults(25)
            .setRunningMode(RunningMode.IMAGE)
            .build()
        detector = ObjectDetector.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap): SecurityScene {
        val objectDetector = detector ?: return SecurityScene(emptyList(), emptyList())
        val result = objectDetector.detect(BitmapImageBuilder(bitmap).build())
        val persons = mutableListOf<DetectedObject>()
        val accessories = mutableListOf<DetectedObject>()

        result.detections().forEach { detection ->
            val category = detection.categories().maxByOrNull { it.score() } ?: return@forEach
            val label = category.categoryName().trim().lowercase()
            val item = DetectedObject(label, category.score(), RectF(detection.boundingBox()))
            when (label) {
                "person" -> persons += item
                "backpack", "handbag", "suitcase" -> accessories += item
            }
        }
        return SecurityScene(persons, accessories)
    }

    fun close() {
        detector?.close()
        detector = null
    }

    companion object {
        private const val MODEL_NAME = "efficientdet-lite0.tflite"
    }
}

data class SecurityScene(
    val persons: List<DetectedObject>,
    val accessories: List<DetectedObject>
)

data class DetectedObject(
    val label: String,
    val score: Float,
    val box: RectF
)
