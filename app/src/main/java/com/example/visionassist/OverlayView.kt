package com.example.visionassist

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private val boxPaint = Paint()
    private val textBackgroundPaint = Paint()
    private val textPaint = Paint()
    private val infoPaint = Paint()
    private val infoBgPaint = Paint()
    private val bounds = Rect()

    init {
        initPaints()
    }

    private fun initPaints() {
        textPaint.apply {
            color = Color.WHITE
            textSize = 36f
            style = Paint.Style.FILL
        }

        textBackgroundPaint.apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }

        boxPaint.apply {
            strokeWidth = 8f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        infoPaint.apply {
            color = Color.WHITE
            textSize = 38f
            isAntiAlias = true
        }

        infoBgPaint.apply {
            color = Color.argb(220, 30, 30, 30)
            style = Paint.Style.FILL
        }
    }

    fun clear() {
        results = listOf()
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (results.isEmpty()) return

        val infoMargin = 20f
        val infoLeft = infoMargin
        val infoTop = infoMargin
        val infoWidth = width - 2 * infoMargin
        val lineHeight = 50f
        val infoHeight = (results.size + 1) * lineHeight + 30f

        val infoRect = RectF(infoLeft, infoTop, infoLeft + infoWidth, infoTop + infoHeight)
        val radius = 25f
        val yellow = Paint().apply { color = Color.rgb(255, 204, 0); style = Paint.Style.STROKE; strokeWidth = 4f }

        canvas.drawRoundRect(infoRect, radius, radius, infoBgPaint)
        canvas.drawRoundRect(infoRect, radius, radius, yellow)

        val header = "⚠️ Objects Detected: ${results.size}"
        canvas.drawText(header, infoLeft + 25f, infoTop + 50f, infoPaint)

        var textY = infoTop + 100f
        results.forEach {
            val distanceCm = it.distance.toInt()
            val label = "• ${it.clsName} – ${distanceCm} cm on the ${it.direction}"
            canvas.drawText(label, infoLeft + 40f, textY, infoPaint)
            textY += lineHeight
        }

        results.forEach {
            val left = it.x1 * width
            val top = it.y1 * height
            val right = it.x2 * width
            val bottom = it.y2 * height

            val color = when {
                it.distance <= 100 -> Color.RED
                it.distance <= 200 -> Color.rgb(255, 140, 0)
                it.distance <= 300 -> Color.YELLOW
                it.distance <= 400 -> Color.GREEN
                else -> Color.CYAN
            }
            boxPaint.color = color

            canvas.drawRect(left, top, right, bottom, boxPaint)

            val labelText = "${it.clsName} ${(it.cnf * 100).toInt()}% ${it.distance.toInt()}cm ${it.direction}"
            textPaint.getTextBounds(labelText, 0, labelText.length, bounds)
            canvas.drawRect(
                left,
                top - bounds.height() - 10,
                left + bounds.width() + 20,
                top,
                textBackgroundPaint
            )
            canvas.drawText(labelText, left + 10, top - 10, textPaint)
        }
    }

    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        invalidate()
    }
}
