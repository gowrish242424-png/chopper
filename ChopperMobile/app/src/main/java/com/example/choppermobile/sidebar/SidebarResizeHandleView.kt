package com.example.choppermobile.sidebar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** A small curved corner handle that remains visible without looking like a dot. */
class SidebarResizeHandleView(
    context: Context,
    private val leftCorner: Boolean
) : View(context) {

    private val density = resources.displayMetrics.density
    private val stroke = 2.5f * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C4B5FD")
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val radius = minOf(width, height).toFloat() * 0.38f
        val bottom = height.toFloat() - stroke
        val top = bottom - radius * 2f

        if (leftCorner) {
            val bounds = RectF(
                stroke,
                top,
                stroke + radius * 2f,
                bottom
            )
            canvas.drawArc(bounds, 90f, 90f, false, paint)
        } else {
            val bounds = RectF(
                width.toFloat() - stroke - radius * 2f,
                top,
                width.toFloat() - stroke,
                bottom
            )
            canvas.drawArc(bounds, 0f, 90f, false, paint)
        }
    }
}
