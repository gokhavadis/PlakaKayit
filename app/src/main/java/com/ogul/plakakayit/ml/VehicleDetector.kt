package com.ogul.plakakayit.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.ogul.plakakayit.data.VehicleObservation
import kotlin.math.max

class VehicleDetector(
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
            .setMaxResults(10)
            .setRunningMode(RunningMode.IMAGE)
            .build()
        detector = ObjectDetector.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap): VehicleObservation? {
        val objectDetector = detector ?: return null
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = objectDetector.detect(mpImage)

        val candidate = result.detections()
            .mapNotNull { detection ->
                val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                val type = translateVehicleType(category.categoryName()) ?: return@mapNotNull null
                Candidate(
                    type = type,
                    score = category.score(),
                    box = detection.boundingBox()
                )
            }
            .maxByOrNull { it.score * max(1f, it.box.width() * it.box.height()) }
            ?: return null

        val crop = cropSafely(bitmap, candidate.box)
        val color = crop?.let(VehicleColorClassifier::classify).orEmpty()
        if (crop != null && crop !== bitmap) crop.recycle()

        return VehicleObservation(
            type = candidate.type,
            color = color,
            confidence = candidate.score
        )
    }

    fun close() {
        detector?.close()
        detector = null
    }

    private fun cropSafely(bitmap: Bitmap, box: RectF): Bitmap? {
        val left = box.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = box.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width < 10 || height < 10) return null
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun translateVehicleType(label: String): String? = when (label.trim().lowercase()) {
        "car" -> "Otomobil"
        "truck" -> "Kamyon"
        "bus" -> "Otobüs"
        "motorcycle", "motorbike" -> "Motosiklet"
        else -> null
    }

    private data class Candidate(
        val type: String,
        val score: Float,
        val box: RectF
    )

    companion object {
        private const val MODEL_NAME = "efficientdet-lite0.tflite"
    }
}

private object VehicleColorClassifier {
    fun classify(bitmap: Bitmap): String {
        val startX = (bitmap.width * 0.18f).toInt()
        val endX = (bitmap.width * 0.82f).toInt().coerceAtLeast(startX + 1)
        val startY = (bitmap.height * 0.20f).toInt()
        val endY = (bitmap.height * 0.82f).toInt().coerceAtLeast(startY + 1)
        val stepX = ((endX - startX) / 28).coerceAtLeast(1)
        val stepY = ((endY - startY) / 28).coerceAtLeast(1)

        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L

        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val pixel = bitmap.getPixel(x, y)
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
                x += stepX
            }
            y += stepY
        }

        if (count == 0L) return ""
        val r = (red / count).toInt()
        val g = (green / count).toInt()
        val b = (blue / count).toInt()
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val saturation = hsv[1]
        val value = hsv[2]

        return when {
            value < 0.18f -> "Siyah"
            saturation < 0.11f && value > 0.82f -> "Beyaz"
            saturation < 0.18f -> "Gri"
            hue < 15f || hue >= 345f -> "Kırmızı"
            hue < 35f && value < 0.55f -> "Kahverengi"
            hue < 50f -> "Turuncu"
            hue < 70f -> "Sarı"
            hue < 165f -> "Yeşil"
            hue < 255f -> "Mavi"
            hue < 295f -> "Mor"
            hue < 345f -> "Kırmızı"
            else -> ""
        }
    }
}
