package com.mycut.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat

class SelectionOverlayService : Service() {
    data class SelectionMapping(
        val bitmapRect: RectF,
        val displayRect: RectF
    )
    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var imageView: ImageView? = null
    private var overlayView: SelectionOverlayView? = null
    private var controlPanel: LinearLayout? = null
    private var sourceBitmap: Bitmap? = null
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val bitmapKey = intent.getStringExtra(EXTRA_BITMAP_KEY)
                AppLog.write(this, "SelectionOverlay", "show overlay bitmapKey=$bitmapKey")
                showOverlay(bitmapKey)
            }

            ACTION_HIDE -> {
                AppLog.write(this, "SelectionOverlay", "hide overlay action")
                clearOverlay()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun showOverlay(bitmapKey: String?) {
        clearOverlay()
        val bitmap = BitmapMemoryStore.take(bitmapKey)
        if (bitmap == null) {
            AppLog.write(this, "SelectionOverlay", "bitmap missing in memory")
            stopSelf()
            return
        }
        screenWidth = resources.displayMetrics.widthPixels
        screenHeight = resources.displayMetrics.heightPixels
        sourceBitmap = bitmap
        runCatching {
            val view = LayoutInflater.from(this).inflate(R.layout.activity_region_select, null)
            val fullImageView = view.findViewById<ImageView>(R.id.fullImageView)
            val selectView = view.findViewById<SelectionOverlayView>(R.id.overlayView)
            val doneButton = view.findViewById<Button>(R.id.doneButton)
            val cancelButton = view.findViewById<Button>(R.id.cancelButton)
            val panel = view.findViewById<LinearLayout>(R.id.controlPanel)
            fullImageView.setImageBitmap(bitmap)
            selectView.onSelectionChanged = { selection ->
                moveControlPanel(selection)
            }
            doneButton.setOnClickListener { onDone() }
            cancelButton.setOnClickListener { onCancel() }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            windowManager.addView(view, params)
            rootView = view
            imageView = fullImageView
            overlayView = selectView
            controlPanel = panel
            AppLog.write(this, "SelectionOverlay", "overlay added")
        }.onFailure {
            AppLog.write(this, "SelectionOverlay", "add overlay failed", it)
            stopSelf()
        }
    }

    private fun onDone() {
        val bitmap = sourceBitmap ?: return
        val fullImageView = imageView ?: return
        val selectView = overlayView ?: return
        val selection = selectView.getSelectionRect()
        if (selection.width() < 10f || selection.height() < 10f) {
            Toast.makeText(this, "请先框选区域", Toast.LENGTH_SHORT).show()
            return
        }
        val mapping = mapSelectionToBitmapRect(fullImageView, selection) ?: run {
            Toast.makeText(this, "选区无效，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        val cropRect = mapping.bitmapRect
        val left = cropRect.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = cropRect.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = cropRect.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = cropRect.bottom.toInt().coerceIn(top + 1, bitmap.height)
        AppLog.write(
            this,
            "SelectionOverlay",
            "selection l=$left t=$top r=$right b=$bottom w=${right - left} h=${bottom - top}"
        )
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val bitmapKey = BitmapMemoryStore.put(cropped)
        val targetX = mapping.displayRect.left.toInt().coerceAtLeast(0)
        val targetY = mapping.displayRect.top.toInt().coerceAtLeast(0)
        val targetWidth = mapping.displayRect.width().toInt().coerceAtLeast(1)
        val targetHeight = mapping.displayRect.height().toInt().coerceAtLeast(1)
        AppLog.write(
            this,
            "SelectionOverlay",
            "pos:update source=SelectionOverlayService.onDone selection_left_top=(${selection.left.toInt()},${selection.top.toInt()}) mapped_left_top=($targetX,$targetY) mapped_size=${targetWidth}x${targetHeight}"
        )
        val showIntent = Intent(this, FloatingImageService::class.java).apply {
            action = FloatingImageService.ACTION_SHOW
            putExtra(FloatingImageService.EXTRA_IMAGE_KEY, bitmapKey)
            putExtra(FloatingImageService.EXTRA_TARGET_X, targetX)
            putExtra(FloatingImageService.EXTRA_TARGET_Y, targetY)
            putExtra(FloatingImageService.EXTRA_TARGET_WIDTH, targetWidth)
            putExtra(FloatingImageService.EXTRA_TARGET_HEIGHT, targetHeight)
        }
        runCatching {
            ContextCompat.startForegroundService(this, showIntent)
            AppLog.write(this, "SelectionOverlay", "floating image started key=$bitmapKey x=$targetX y=$targetY")
        }.onFailure {
            AppLog.write(this, "SelectionOverlay", "start floating image failed", it)
            Toast.makeText(this, "贴图服务启动失败，已记录日志", Toast.LENGTH_SHORT).show()
        }
        clearOverlay()
        stopSelf()
    }

    private fun onCancel() {
        AppLog.write(this, "SelectionOverlay", "selection canceled")
        clearOverlay()
        stopSelf()
    }

    private fun clearOverlay() {
        rootView?.let { view ->
            runCatching {
                windowManager.removeView(view)
            }
        }
        rootView = null
        imageView = null
        overlayView = null
        controlPanel = null
        sourceBitmap = null
    }

    private fun moveControlPanel(selection: RectF) {
        val panel = controlPanel ?: return
        panel.post {
            val layoutParams = panel.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return@post
            if (selection.width() < 10f || selection.height() < 10f) {
                layoutParams.gravity = Gravity.BOTTOM or Gravity.END
                layoutParams.leftMargin = 0
                layoutParams.topMargin = 0
                layoutParams.rightMargin = (12 * resources.displayMetrics.density).toInt()
                layoutParams.bottomMargin = (12 * resources.displayMetrics.density).toInt()
                panel.layoutParams = layoutParams
                return@post
            }
            val spacing = (8 * resources.displayMetrics.density).toInt()
            val panelWidth = panel.width.takeIf { it > 0 } ?: panel.measuredWidth
            val panelHeight = panel.height.takeIf { it > 0 } ?: panel.measuredHeight
            var x = selection.right.toInt() - panelWidth
            var y = selection.bottom.toInt() + spacing
            if (x < spacing) x = spacing
            if (x + panelWidth > screenWidth - spacing) x = screenWidth - panelWidth - spacing
            if (y + panelHeight > screenHeight - spacing) {
                y = selection.top.toInt() - panelHeight - spacing
            }
            if (y < spacing) y = spacing
            layoutParams.gravity = Gravity.TOP or Gravity.START
            layoutParams.leftMargin = x
            layoutParams.topMargin = y
            layoutParams.rightMargin = 0
            layoutParams.bottomMargin = 0
            panel.layoutParams = layoutParams
        }
    }

    private fun mapSelectionToBitmapRect(imageView: ImageView, selection: RectF): SelectionMapping? {
        val drawable = imageView.drawable ?: return null
        val matrix = imageView.imageMatrix
        val values = FloatArray(9)
        matrix.getValues(values)
        val scaleX = values[Matrix.MSCALE_X]
        val scaleY = values[Matrix.MSCALE_Y]
        val transX = values[Matrix.MTRANS_X]
        val transY = values[Matrix.MTRANS_Y]
        if (scaleX == 0f || scaleY == 0f) return null
        val displayedRect = RectF(
            transX,
            transY,
            transX + drawable.intrinsicWidth * scaleX,
            transY + drawable.intrinsicHeight * scaleY
        )
        val intersection = RectF()
        val intersected = intersection.setIntersect(displayedRect, selection)
        if (!intersected || intersection.width() <= 1f || intersection.height() <= 1f) return null
        val bitmapRect = RectF(
            (intersection.left - transX) / scaleX,
            (intersection.top - transY) / scaleY,
            (intersection.right - transX) / scaleX,
            (intersection.bottom - transY) / scaleY
        )
        return SelectionMapping(
            bitmapRect = bitmapRect,
            displayRect = RectF(intersection)
        )
    }

    override fun onDestroy() {
        clearOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SHOW = "com.mycut.app.action.SELECTION_SHOW"
        const val ACTION_HIDE = "com.mycut.app.action.SELECTION_HIDE"
        const val EXTRA_BITMAP_KEY = "extra_bitmap_key"
    }
}
