package com.angeljo0801.calendario

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class OcrOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class WordBox(
        val text: String,
        val rect: Rect,
        val order: Int
    )

    private val words = mutableListOf<WordBox>()
    private val selectedIndices = linkedSetOf<Int>()
    private var imageWidth = 1
    private var imageHeight = 1
    private var startIndex = -1

    var onSelectionChanged: ((String) -> Unit)? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = 0x99FFFFFF.toInt()
    }

    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x663F51B5
    }

    private val selectedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFFB8C0FF.toInt()
    }

    fun setImageSize(width: Int, height: Int) {
        imageWidth = max(1, width)
        imageHeight = max(1, height)
        invalidate()
    }

    fun setWords(newWords: List<WordBox>) {
        words.clear()
        words.addAll(newWords.sortedBy { it.order })
        selectedIndices.clear()
        startIndex = -1
        notifySelection()
        invalidate()
    }

    fun selectAllWords() {
        selectedIndices.clear()
        words.indices.forEach { selectedIndices += it }
        notifySelection()
        invalidate()
    }

    fun clearSelection() {
        selectedIndices.clear()
        startIndex = -1
        notifySelection()
        invalidate()
    }

    fun selectedText(): String = selectedIndices
        .sorted()
        .mapNotNull { words.getOrNull(it)?.text?.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .trim()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        words.forEachIndexed { index, word ->
            val rect = imageRectToView(word.rect)
            if (index in selectedIndices) {
                canvas.drawRoundRect(rect, dp(3f), dp(3f), selectedPaint)
                canvas.drawRoundRect(rect, dp(3f), dp(3f), selectedStrokePaint)
            } else {
                canvas.drawRoundRect(rect, dp(2f), dp(2f), boxPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (words.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startIndex = findWordIndex(event.x, event.y)
                if (startIndex >= 0) {
                    selectedIndices.clear()
                    selectedIndices += startIndex
                    notifySelection()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (startIndex >= 0) {
                    val currentIndex = findWordIndex(event.x, event.y)
                    if (currentIndex >= 0) {
                        selectRange(startIndex, currentIndex)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (startIndex >= 0) {
                    val currentIndex = findWordIndex(event.x, event.y)
                    if (currentIndex >= 0) selectRange(startIndex, currentIndex)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun selectRange(a: Int, b: Int) {
        val from = min(a, b)
        val to = max(a, b)
        selectedIndices.clear()
        for (index in from..to) selectedIndices += index
        notifySelection()
        invalidate()
    }

    private fun findWordIndex(x: Float, y: Float): Int {
        words.forEachIndexed { index, word ->
            if (imageRectToView(word.rect).contains(x, y)) return index
        }

        var nearest = -1
        var nearestDistance = Float.MAX_VALUE
        val maxDistance = dp(52f)

        words.forEachIndexed { index, word ->
            val rect = imageRectToView(word.rect)
            val cx = rect.centerX()
            val cy = rect.centerY()
            val distance = hypot(x - cx, y - cy)
            if (distance < nearestDistance && distance <= maxDistance) {
                nearestDistance = distance
                nearest = index
            }
        }
        return nearest
    }

    private fun imageRectToView(source: Rect): RectF {
        val scale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val renderedWidth = imageWidth * scale
        val renderedHeight = imageHeight * scale
        val offsetX = (width - renderedWidth) / 2f
        val offsetY = (height - renderedHeight) / 2f

        return RectF(
            offsetX + source.left * scale,
            offsetY + source.top * scale,
            offsetX + source.right * scale,
            offsetY + source.bottom * scale
        )
    }

    private fun notifySelection() {
        onSelectionChanged?.invoke(selectedText())
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
