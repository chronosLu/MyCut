package com.mycut.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SelectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var onSelectionChanged: ((RectF) -> Unit)? = null
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val selectionRect = RectF()
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moving = false

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        if (!selectionRect.isEmpty) {
            canvas.drawRect(selectionRect, clearPaint)
            canvas.drawRect(selectionRect, borderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
                moving = selectionRect.contains(event.x, event.y) && !selectionRect.isEmpty
                if (!moving) {
                    selectionRect.set(startX, startY, startX, startY)
                }
                onSelectionChanged?.invoke(RectF(selectionRect))
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (moving) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    selectionRect.offset(dx, dy)
                    if (selectionRect.left < 0f) selectionRect.offset(-selectionRect.left, 0f)
                    if (selectionRect.top < 0f) selectionRect.offset(0f, -selectionRect.top)
                    if (selectionRect.right > width) selectionRect.offset(width - selectionRect.right, 0f)
                    if (selectionRect.bottom > height) selectionRect.offset(0f, height - selectionRect.bottom)
                    lastX = event.x
                    lastY = event.y
                } else {
                    selectionRect.set(
                        min(startX, event.x),
                        min(startY, event.y),
                        max(startX, event.x),
                        max(startY, event.y)
                    )
                }
                onSelectionChanged?.invoke(RectF(selectionRect))
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                moving = false
                onSelectionChanged?.invoke(RectF(selectionRect))
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun getSelectionRect(): RectF = RectF(selectionRect)
}
