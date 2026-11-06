package com.yourname.watchreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** One watch box in view coordinates with the number shown next to it (same as dial_index). */
data class LiveBox(val rect: RectF, val number: Int, val score: Float, val reading: String? = null)

/** Draws the live detection boxes over the camera preview. */
class LiveOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 40f
        isFakeBoldText = true
    }

    private var boxes: List<LiveBox> = emptyList()

    fun setBoxes(newBoxes: List<LiveBox>) {
        boxes = newBoxes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (b in boxes) {
            canvas.drawRect(b.rect, boxPaint)
            val label = if (b.reading != null) "${b.number}  ${b.reading}" else "${b.number}  %.0f%%".format(b.score * 100)
            val w = textPaint.measureText(label) + 20f
            val top = (b.rect.top - 52f).coerceAtLeast(0f)
            canvas.drawRect(b.rect.left, top, b.rect.left + w, top + 52f, tagPaint)
            canvas.drawText(label, b.rect.left + 10f, top + 40f, textPaint)
        }
    }
}
