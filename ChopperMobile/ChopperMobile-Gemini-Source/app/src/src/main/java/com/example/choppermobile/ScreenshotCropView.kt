package com.example.choppermobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class ScreenshotCropView @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null
) : View(context, attributes) {

    data class RecognizedWord(val text: String, val bounds: RectF)

    var onTextSelected: ((String) -> Unit)? = null
    var onImageSelectionChanged: (() -> Unit)? = null

    private var screenshot: Bitmap? = null
    private val words = mutableListOf<RecognizedWord>()
    private val selectedWordIndexes = linkedSetOf<Int>()
    private val displayPath = Path()
    private val bitmapPath = Path()
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastBitmapX = 0f
    private var lastBitmapY = 0f
    private var travelled = 0f
    private var imageSelectionReady = false

    private val shadePaint = Paint().apply { color = Color.argb(68, 0, 0, 0) }
    private val pathGlowPaint = Paint().apply {
        color = Color.argb(115, 255, 30, 30)
        style = Paint.Style.STROKE
        strokeWidth = dp(10).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val pathPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(3).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.argb(105, 255, 45, 45)
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textBorderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2).toFloat()
        isAntiAlias = true
    }

    fun setScreenshot(bitmap: Bitmap) {
        screenshot = bitmap
        clearSelection()
    }

    fun setRecognizedWords(recognizedWords: List<RecognizedWord>) {
        words.clear()
        words.addAll(recognizedWords)
        invalidate()
    }

    fun clearSelection() {
        displayPath.reset()
        bitmapPath.reset()
        selectedWordIndexes.clear()
        travelled = 0f
        imageSelectionReady = false
        invalidate()
    }

    fun hasSelection(): Boolean = imageSelectionReady

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Never draw the screenshot: the real phone screen remains visible.
        canvas.drawColor(Color.TRANSPARENT)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
        drawRedEdgeGlow(canvas)

        selectedWordIndexes.forEach { index ->
            val displayBounds = bitmapRectToDisplay(words[index].bounds)
            canvas.drawRoundRect(displayBounds, dp(7).toFloat(), dp(7).toFloat(), textPaint)
            canvas.drawRoundRect(displayBounds, dp(7).toFloat(), dp(7).toFloat(), textBorderPaint)
        }

        if (!displayPath.isEmpty) {
            canvas.drawPath(displayPath, pathGlowPaint)
            canvas.drawPath(displayPath, pathPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = screenshot ?: return false
        if (width <= 0 || height <= 0) return false
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        val bitmapX = x * bitmap.width / width.toFloat()
        val bitmapY = y * bitmap.height / height.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                clearSelection()
                startX = x
                startY = y
                lastX = x
                lastY = y
                lastBitmapX = bitmapX
                lastBitmapY = bitmapY
                displayPath.moveTo(x, y)
                bitmapPath.moveTo(bitmapX, bitmapY)
                collectWordsAt(bitmapX, bitmapY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                travelled += hypot(x - lastX, y - lastY)
                displayPath.quadTo(lastX, lastY, (lastX + x) / 2f, (lastY + y) / 2f)
                bitmapPath.quadTo(lastBitmapX, lastBitmapY, (lastBitmapX + bitmapX) / 2f, (lastBitmapY + bitmapY) / 2f)
                lastX = x
                lastY = y
                lastBitmapX = bitmapX
                lastBitmapY = bitmapY
                collectWordsAt(bitmapX, bitmapY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                displayPath.lineTo(x, y)
                bitmapPath.lineTo(bitmapX, bitmapY)
                val closesShape = hypot(x - startX, y - startY) <= dp(70) && travelled >= dp(120)

                if (closesShape) {
                    displayPath.close()
                    bitmapPath.close()
                    selectedWordIndexes.clear()
                    imageSelectionReady = true
                    onImageSelectionChanged?.invoke()
                } else if (selectedWordIndexes.isNotEmpty()) {
                    displayPath.reset()
                    bitmapPath.reset()
                    imageSelectionReady = false
                    val text = selectedWordIndexes.sorted().joinToString(" ") { words[it].text }.trim()
                    onTextSelected?.invoke(text)
                } else {
                    displayPath.close()
                    bitmapPath.close()
                    imageSelectionReady = travelled >= dp(40)
                    if (imageSelectionReady) onImageSelectionChanged?.invoke()
                }

                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                clearSelection()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun getCroppedScreenshot(): Bitmap? {
        val bitmap = screenshot ?: return null
        if (!imageSelectionReady) return bitmap
        val bounds = RectF()
        bitmapPath.computeBounds(bounds, true)
        val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val output = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
        val outputCanvas = Canvas(output)
        outputCanvas.drawColor(Color.TRANSPARENT)
        outputCanvas.save()
        outputCanvas.translate(-left.toFloat(), -top.toFloat())
        outputCanvas.clipPath(bitmapPath)
        outputCanvas.drawBitmap(bitmap, 0f, 0f, null)
        outputCanvas.restore()
        return output
    }

    private fun collectWordsAt(bitmapX: Float, bitmapY: Float) {
        val bitmap = screenshot ?: return
        val extraX = dp(12) * bitmap.width / width.toFloat()
        val extraY = dp(12) * bitmap.height / height.toFloat()
        words.forEachIndexed { index, word ->
            val expanded = RectF(word.bounds).apply { inset(-extraX, -extraY) }
            if (expanded.contains(bitmapX, bitmapY)) selectedWordIndexes.add(index)
        }
    }

    private fun bitmapRectToDisplay(rect: RectF): RectF {
        val bitmap = screenshot ?: return RectF()
        return RectF(
            rect.left * width / bitmap.width,
            rect.top * height / bitmap.height,
            rect.right * width / bitmap.width,
            rect.bottom * height / bitmap.height
        )
    }

    private fun drawRedEdgeGlow(canvas: Canvas) {
        val edge = dp(34).toFloat()
        val leftPaint = Paint().apply {
            shader = LinearGradient(0f, 0f, edge, 0f, Color.argb(190, 255, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        val rightPaint = Paint().apply {
            shader = LinearGradient(width.toFloat(), 0f, width - edge, 0f, Color.argb(190, 255, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, edge, height.toFloat(), leftPaint)
        canvas.drawRect(width - edge, 0f, width.toFloat(), height.toFloat(), rightPaint)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
