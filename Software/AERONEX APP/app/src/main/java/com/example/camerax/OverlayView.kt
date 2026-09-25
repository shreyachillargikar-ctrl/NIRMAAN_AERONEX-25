
package com.example.camerax

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var boundingBoxes: List<RectF> = emptyList()
    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    fun setResults(boxes: List<RectF>) {
        this.boundingBoxes = boxes
        invalidate() // Request a redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw all detected human boxes
        for (box in boundingBoxes) {
            canvas.drawRect(box, boxPaint)
        }
    }
}