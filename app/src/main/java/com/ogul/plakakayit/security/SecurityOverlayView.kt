package com.ogul.plakakayit.security

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.ogul.plakakayit.data.PersonObservation

class SecurityOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 193, 7)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val zoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 255, 193, 7)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private var observations: List<PersonObservation> = emptyList()
    var restrictedZoneVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    fun setObservations(newItems: List<PersonObservation>) {
        observations = newItems
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (restrictedZoneVisible) drawRestrictedZone(canvas)
        observations.forEach { observation -> drawPerson(canvas, observation) }
    }

    private fun drawRestrictedZone(canvas: Canvas) {
        val rect = RectF(
            width * SecurityTracker.ZONE_LEFT,
            height * SecurityTracker.ZONE_TOP,
            width * SecurityTracker.ZONE_RIGHT,
            height * SecurityTracker.ZONE_BOTTOM
        )
        canvas.drawRect(rect, zoneFillPaint)
        canvas.drawRect(rect, zonePaint)
        drawLabel(canvas, "Kısıtlı bölge", rect.left + 8f, rect.top + 38f)
    }

    private fun drawPerson(canvas: Canvas, observation: PersonObservation) {
        val rect = RectF(
            observation.box.left * width,
            observation.box.top * height,
            observation.box.right * width,
            observation.box.bottom * height
        )
        boxPaint.color = if (observation.inRestrictedZone) {
            Color.rgb(255, 82, 82)
        } else {
            Color.rgb(0, 230, 118)
        }
        canvas.drawRect(rect, boxPaint)
        val label = buildString {
            append("Kişi-").append(observation.trackId)
            append(" • ").append(observation.movement)
            if (observation.upperColor.isNotBlank()) append(" • ").append(observation.upperColor)
        }
        drawLabel(canvas, label, rect.left, (rect.top - 10f).coerceAtLeast(34f))
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, baselineY: Float) {
        val width = labelPaint.measureText(text)
        val top = baselineY - labelPaint.textSize - 8f
        canvas.drawRoundRect(
            x,
            top,
            (x + width + 18f).coerceAtMost(this.width.toFloat()),
            baselineY + 8f,
            8f,
            8f,
            labelBackgroundPaint
        )
        canvas.drawText(text, x + 9f, baselineY, labelPaint)
    }
}
