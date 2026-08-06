package com.ogul.plakakayit.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class PlateOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 140, 0)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 38f
        style = Paint.Style.FILL
        isFakeBoldText = true
    }

    private var normalizedBox: RectF? = null
    private var label: String = ""
    private var sourceWidth: Int = 1
    private var sourceHeight: Int = 1

    fun updateDetection(
        plate: String,
        box: RectF,
        imageWidth: Int,
        imageHeight: Int
    ) {
        label = plate
        normalizedBox = RectF(box)
        sourceWidth = imageWidth.coerceAtLeast(1)
        sourceHeight = imageHeight.coerceAtLeast(1)
        invalidate()
    }

    fun clearDetection() {
        normalizedBox = null
        label = ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val box = normalizedBox ?: return
        if (width <= 0 || height <= 0) return

        val scale = min(
            width.toFloat() / sourceWidth.toFloat(),
            height.toFloat() / sourceHeight.toFloat()
        )
        val displayedWidth = sourceWidth * scale
        val displayedHeight = sourceHeight * scale
        val offsetX = (width - displayedWidth) / 2f
        val offsetY = (height - displayedHeight) / 2f

        val rect = RectF(
            offsetX + box.left * sourceWidth * scale,
            offsetY + box.top * sourceHeight * scale,
            offsetX + box.right * sourceWidth * scale,
            offsetY + box.bottom * sourceHeight * scale
        )
        canvas.drawRoundRect(rect, 12f, 12f, boxPaint)

        if (label.isNotBlank()) {
            val padding = 12f
            val textWidth = labelPaint.measureText(label)
            val textHeight = labelPaint.fontMetrics.run { bottom - top }
            val labelTop = (rect.top - textHeight - padding * 2).coerceAtLeast(0f)
            val labelRect = RectF(
                rect.left,
                labelTop,
                rect.left + textWidth + padding * 2,
                labelTop + textHeight + padding * 2
            )
            canvas.drawRoundRect(labelRect, 10f, 10f, labelBackgroundPaint)
            canvas.drawText(
                label,
                labelRect.left + padding,
                labelRect.bottom - padding - labelPaint.fontMetrics.bottom,
                labelPaint
            )
        }
    }
}
