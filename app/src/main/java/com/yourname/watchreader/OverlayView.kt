package com.yourname.watchreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private var detectionBox: RectF? = null
    var isOverlayEnabled: Boolean = true

    fun setDetectionBox(box: RectF?) {
        detectionBox = box
        invalidate() // Trigger a redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isOverlayEnabled) {
            detectionBox?.let { 
                canvas.drawRect(it, paint)
            }
        }
    }
}
