package com.mycut.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.math.abs

class FloatingImageService : Service() {
    private val windowItems = mutableListOf<WindowItem>()
    private val actionPanelMap = mutableMapOf<View, ActionPanelItem>()
    private val sourceBitmapMap = mutableMapOf<View, Bitmap>()
    private val ocrWindowViews = mutableListOf<View>()
    private lateinit var windowManager: WindowManager
    private var preferredScale = 1f

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        preferredScale = loadPreferredScale()
        createChannel()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }.onFailure {
            AppLog.write(this, "FloatingImage", "startForeground failed", it)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val targetX = intent.getIntExtra(EXTRA_TARGET_X, -1)
                val targetY = intent.getIntExtra(EXTRA_TARGET_Y, -1)
                val targetWidth = intent.getIntExtra(EXTRA_TARGET_WIDTH, -1)
                val targetHeight = intent.getIntExtra(EXTRA_TARGET_HEIGHT, -1)
                val bitmapKey = intent.getStringExtra(EXTRA_IMAGE_KEY)
                val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
                AppLog.write(this, "FloatingImage", "show image key=$bitmapKey uri=$uriString x=$targetX y=$targetY w=$targetWidth h=$targetHeight")
                showImage(bitmapKey, uriString, targetX, targetY, targetWidth, targetHeight)
            }

            ACTION_CLEAR -> {
                AppLog.write(this, "FloatingImage", "clear all windows")
                clearAll()
                stopSelf()
            }

            ACTION_REFRESH_STYLE -> {
                AppLog.write(this, "FloatingImage", "refresh window style")
                refreshWindowStyles()
            }
        }
        return START_STICKY
    }

    private fun showImage(
        bitmapKey: String?,
        uriString: String?,
        targetX: Int,
        targetY: Int,
        targetWidth: Int,
        targetHeight: Int
    ) {
        val bitmap = BitmapMemoryStore.take(bitmapKey)
            ?: uriString?.let { uri -> contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) } }
            ?: return
        val root = FrameLayout(this)
        val image = ImageView(this)
        image.setImageBitmap(bitmap)
        image.adjustViewBounds = true
        val maxInitialWidth = (resources.displayMetrics.widthPixels * 0.45f).toInt()
        val fitScale = if (bitmap.width > maxInitialWidth) {
            maxInitialWidth.toFloat() / bitmap.width.toFloat()
        } else {
            1f
        }
        val baseWidth = (bitmap.width * fitScale).toInt().coerceAtLeast(1)
        val baseHeight = (bitmap.height * fitScale).toInt().coerceAtLeast(1)
        val hasExplicitTargetSize = targetWidth > 0 && targetHeight > 0
        val initialWidth = if (hasExplicitTargetSize) {
            targetWidth.coerceIn(80, resources.displayMetrics.widthPixels * 3)
        } else {
            (baseWidth * preferredScale).toInt().coerceIn(80, resources.displayMetrics.widthPixels * 3)
        }
        val initialHeight = if (hasExplicitTargetSize) {
            targetHeight.coerceIn(80, resources.displayMetrics.heightPixels * 3)
        } else {
            (baseHeight * preferredScale).toInt().coerceIn(80, resources.displayMetrics.heightPixels * 3)
        }
        root.addView(
            image,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val actionPanel = createActionPanel(root)
        val actionPanelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        actionPanelMap[root] = ActionPanelItem(actionPanel, actionPanelParams)
        sourceBitmapMap[root] = bitmap
        applyBorderStyle(root)

        val params = WindowManager.LayoutParams(
            initialWidth,
            initialHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (targetX >= 0) targetX else 120 + windowItems.size * 40
            y = if (targetY >= 0) targetY else 180 + windowItems.size * 40
        }
        AppLog.write(
            this,
            "FloatingImage",
            "pos:update source=showImage.init left_top=(${params.x},${params.y}) size=${params.width}x${params.height} scale=$preferredScale explicit_size=$hasExplicitTargetSize"
        )

        bindMoveAndGesture(root, image, params, bitmap, baseWidth)

        runCatching {
            windowManager.addView(root, params)
            windowItems.add(WindowItem(root, params))
            updateActionPanelPosition(root, params)
        }.onFailure {
            AppLog.write(this, "FloatingImage", "add image window failed", it)
        }
    }

    private fun bindMoveAndGesture(
        root: View,
        image: ImageView,
        params: WindowManager.LayoutParams,
        bitmap: Bitmap,
        baseWidth: Int
    ) {
        val imageLayoutParams = image.layoutParams as FrameLayout.LayoutParams
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val mainHandler = Handler(Looper.getMainLooper())
        var frameLeft = params.x.toFloat()
        var frameTop = params.y.toFloat()
        var frameWidth = params.width.toFloat()
        var frameHeight = params.height.toFloat()
        var downLocalX = 0f
        var downLocalY = 0f
        var longPressArmed = false
        var copiedInCurrentPress = false
        var resetDragBaseline = false
        val longPressRunnable = Runnable {
            if (!longPressArmed || copiedInCurrentPress) {
                return@Runnable
            }
            val uri = ImageStore.saveBitmap(this@FloatingImageService, bitmap, "copy")
            ImageStore.copyImageUriToClipboard(this@FloatingImageService, uri)
            AppLog.write(this@FloatingImageService, "FloatingImage", "long press copy image")
            Toast.makeText(this@FloatingImageService, getString(R.string.copy_success), Toast.LENGTH_SHORT).show()
            copiedInCurrentPress = true
            longPressArmed = false
        }
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    mainHandler.removeCallbacks(longPressRunnable)
                    longPressArmed = false
                    toggleActionPanel(root)
                    return true
                }

                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }
            }
        )
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                frameLeft = params.x.toFloat()
                frameTop = params.y.toFloat()
                frameWidth = params.width.toFloat()
                frameHeight = params.height.toFloat()
                resetDragBaseline = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldWidth = frameWidth
                val oldHeight = frameHeight
                val newWidth = (oldWidth * detector.scaleFactor).coerceIn(80f, (resources.displayMetrics.widthPixels * 3).toFloat())
                val newHeight = (oldHeight * detector.scaleFactor).coerceIn(80f, (resources.displayMetrics.heightPixels * 3).toFloat())
                if (newWidth == oldWidth && newHeight == oldHeight) return true
                val focusX = detector.focusX
                val focusY = detector.focusY
                val scaleX = newWidth / oldWidth
                val scaleY = newHeight / oldHeight
                frameLeft = frameLeft + focusX - focusX * scaleX
                frameTop = frameTop + focusY - focusY * scaleY
                frameWidth = newWidth
                frameHeight = newHeight
                val beforeX = params.x
                val beforeY = params.y
                params.x = frameLeft.toInt()
                params.y = frameTop.toInt()
                params.width = frameWidth.toInt()
                params.height = frameHeight.toInt()
                imageLayoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                imageLayoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
                image.layoutParams = imageLayoutParams
                longPressArmed = false
                mainHandler.removeCallbacks(longPressRunnable)
                runCatching {
                    windowManager.updateViewLayout(root, params)
                }
                updateActionPanelPosition(root, params)
                logPositionChange(
                    source = "bindMoveAndGesture.onScale",
                    beforeX = beforeX,
                    beforeY = beforeY,
                    afterX = params.x,
                    afterY = params.y,
                    width = params.width,
                    height = params.height
                )
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val beforeX = params.x
                val beforeY = params.y
                params.x = frameLeft.toInt()
                params.y = frameTop.toInt()
                params.width = frameWidth.toInt()
                params.height = frameHeight.toInt()
                if (params.x != beforeX || params.y != beforeY) {
                    runCatching {
                        windowManager.updateViewLayout(root, params)
                    }
                    updateActionPanelPosition(root, params)
                    logPositionChange(
                        source = "bindMoveAndGesture.onScaleEndStabilize",
                        beforeX = beforeX,
                        beforeY = beforeY,
                        afterX = params.x,
                        afterY = params.y,
                        width = params.width,
                        height = params.height
                    )
                }
                resetDragBaseline = true
                if (baseWidth > 0) {
                    val scale = frameWidth / baseWidth.toFloat()
                    savePreferredScale(scale)
                }
            }
        })

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        root.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downLocalX = event.x
                    downLocalY = event.y
                    startX = params.x
                    startY = params.y
                    copiedInCurrentPress = false
                    longPressArmed = true
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_TRIGGER_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        if (resetDragBaseline) {
                            downX = event.rawX
                            downY = event.rawY
                            startX = params.x
                            startY = params.y
                            resetDragBaseline = false
                            return@setOnTouchListener true
                        }
                        if (longPressArmed &&
                            (abs(event.x - downLocalX) > touchSlop || abs(event.y - downLocalY) > touchSlop)
                        ) {
                            longPressArmed = false
                            mainHandler.removeCallbacks(longPressRunnable)
                        }
                        if (!copiedInCurrentPress) {
                            val beforeX = params.x
                            val beforeY = params.y
                            params.x = startX + (event.rawX - downX).toInt()
                            params.y = startY + (event.rawY - downY).toInt()
                            if (params.x != beforeX || params.y != beforeY) {
                                runCatching {
                                    windowManager.updateViewLayout(root, params)
                                }
                                updateActionPanelPosition(root, params)
                                logPositionChange(
                                    source = "bindMoveAndGesture.onMove",
                                    beforeX = beforeX,
                                    beforeY = beforeY,
                                    afterX = params.x,
                                    afterY = params.y,
                                    width = params.width,
                                    height = params.height
                                )
                            }
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    longPressArmed = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    resetDragBaseline = true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    resetDragBaseline = true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressArmed = false
                    mainHandler.removeCallbacks(longPressRunnable)
                }
            }
            true
        }
    }

    private fun loadPreferredScale(): Float {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(PREF_SCALE, 1f).coerceIn(0.4f, 2.5f)
    }

    private fun savePreferredScale(scale: Float) {
        preferredScale = scale.coerceIn(0.4f, 2.5f)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(PREF_SCALE, preferredScale).apply()
        AppLog.write(this, "FloatingImage", "scale:persist value=$preferredScale")
    }

    private fun removeWindow(view: View) {
        val panelItem = actionPanelMap.remove(view)
        if (panelItem?.visible == true) {
            runCatching {
                windowManager.removeView(panelItem.view)
            }
        }
        runCatching {
            windowManager.removeView(view)
        }
        windowItems.removeAll { it.view == view }
        sourceBitmapMap.remove(view)
        if (windowItems.isEmpty()) {
            stopSelf()
        }
    }

    private fun logPositionChange(
        source: String,
        beforeX: Int,
        beforeY: Int,
        afterX: Int,
        afterY: Int,
        width: Int,
        height: Int
    ) {
        AppLog.write(
            this,
            "FloatingImage",
            "pos:update source=$source before_left_top=($beforeX,$beforeY) after_left_top=($afterX,$afterY) size=${width}x${height}"
        )
    }

    private fun clearAll() {
        ocrWindowViews.toList().forEach { view ->
            runCatching {
                windowManager.removeView(view)
            }
        }
        ocrWindowViews.clear()
        actionPanelMap.values.forEach { panelItem ->
            if (panelItem.visible) {
                runCatching {
                    windowManager.removeView(panelItem.view)
                }
            }
        }
        windowItems.toList().forEach { item ->
            runCatching {
                windowManager.removeView(item.view)
            }
        }
        windowItems.clear()
        actionPanelMap.clear()
        sourceBitmapMap.clear()
    }

    private fun toggleActionPanel(root: View) {
        val panelItem = actionPanelMap[root] ?: return
        if (panelItem.visible) {
            runCatching {
                windowManager.removeView(panelItem.view)
            }
            panelItem.visible = false
            return
        }
        val imageParams = windowItems.firstOrNull { it.view == root }?.params ?: return
        updateActionPanelPosition(root, imageParams)
        runCatching {
            windowManager.addView(panelItem.view, panelItem.params)
            panelItem.visible = true
        }.onFailure {
            AppLog.write(this, "FloatingImage", "show action panel failed", it)
        }
    }

    private fun createActionPanel(root: View): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
        }
        val closeButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_panel_close)
            setColorFilter(Color.parseColor("#E53935"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#33E53935"))
                setStroke(dp(1), Color.parseColor("#66E53935"))
            }
            contentDescription = getString(R.string.floating_action_close)
            setOnClickListener {
                copyImageToClipboard(root)
                removeWindow(root)
            }
        }
        val ocrButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_panel_ocr)
            setColorFilter(Color.parseColor("#2196F3"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#332196F3"))
                setStroke(dp(1), Color.parseColor("#662196F3"))
            }
            contentDescription = getString(R.string.floating_action_ocr)
            setOnClickListener {
                runOcr(root)
            }
        }
        panel.addView(closeButton, LinearLayout.LayoutParams(dp(30), dp(32)))
        panel.addView(ocrButton, LinearLayout.LayoutParams(dp(30), dp(32)).apply {
            marginStart = dp(4)
        })
        return panel
    }

    private fun updateActionPanelPosition(root: View, imageParams: WindowManager.LayoutParams) {
        val panelItem = actionPanelMap[root] ?: return
        val panel = panelItem.view
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val panelWidth = panel.measuredWidth.coerceAtLeast(dp(88))
        val panelHeight = panel.measuredHeight.coerceAtLeast(dp(36))
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val desiredX = imageParams.x + imageParams.width - panelWidth
        val desiredY = imageParams.y + imageParams.height + dp(6)
        panelItem.params.x = desiredX.coerceIn(0, (screenWidth - panelWidth).coerceAtLeast(0))
        panelItem.params.y = desiredY.coerceIn(0, (screenHeight - panelHeight).coerceAtLeast(0))
        if (panelItem.visible) {
            runCatching {
                windowManager.updateViewLayout(panelItem.view, panelItem.params)
            }
        }
    }

    private fun runOcr(root: View) {
        val bitmap = sourceBitmapMap[root] ?: return
        val panelItem = actionPanelMap[root]
        if (panelItem?.visible == true) {
            runCatching {
                windowManager.removeView(panelItem.view)
            }
            panelItem.visible = false
        }
        Toast.makeText(this, getString(R.string.ocr_scanning), Toast.LENGTH_SHORT).show()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text.ifBlank { getString(R.string.ocr_empty) }
                showOcrTextWindow(text)
            }
            .addOnFailureListener { error ->
                AppLog.write(this, "FloatingImage", "ocr failed", error)
                Toast.makeText(this, error.message ?: "OCR 失败", Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener {
                recognizer.close()
            }
    }

    private fun showOcrTextWindow(text: String) {
        val card = FrameLayout(this).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#EE1E1E24"))
                setStroke(dp(1), Color.parseColor("#88FFFFFF"))
            }
        }
        val title = TextView(this).apply {
            this.text = getString(R.string.ocr_result_title)
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val body = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#F0FFFFFF"))
            textSize = 13f
            movementMethod = ScrollingMovementMethod.getInstance()
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, 0)
        }
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_panel_close)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#33445566"))
                setStroke(dp(1), Color.parseColor("#66FFFFFF"))
            }
            contentDescription = getString(R.string.floating_action_close)
        }
        val copy = ImageButton(this).apply {
            setImageResource(R.drawable.ic_panel_copy)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#33607D8B"))
                setStroke(dp(1), Color.parseColor("#88B3E5FC"))
            }
            contentDescription = "copy"
            setOnClickListener {
                copyTextToClipboard(text)
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
        }
        card.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        card.addView(close, FrameLayout.LayoutParams(dp(34), dp(34)).apply {
            gravity = Gravity.TOP or Gravity.END
        })
        card.addView(copy, FrameLayout.LayoutParams(dp(34), dp(34)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(2)
            bottomMargin = dp(2)
        })
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.78f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = (resources.displayMetrics.heightPixels * 0.18f).toInt()
        }
        close.setOnClickListener {
            runCatching { windowManager.removeView(card) }
            ocrWindowViews.remove(card)
        }
        runCatching {
            windowManager.addView(card, params)
            ocrWindowViews.add(card)
        }.onFailure {
            AppLog.write(this, "FloatingImage", "add ocr window failed", it)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    private fun copyTextToClipboard(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("ocr_text", text))
        Toast.makeText(this, getString(R.string.ocr_copy_success), Toast.LENGTH_SHORT).show()
    }

    private fun copyImageToClipboard(root: View) {
        val bitmap = sourceBitmapMap[root] ?: return
        val uri = ImageStore.saveBitmap(this, bitmap, "copy")
        ImageStore.copyImageUriToClipboard(this, uri)
        Toast.makeText(this, getString(R.string.copy_success), Toast.LENGTH_SHORT).show()
    }

    private fun refreshWindowStyles() {
        windowItems.forEach { item ->
            val root = item.view as? FrameLayout ?: return@forEach
            applyBorderStyle(root)
            runCatching {
                windowManager.updateViewLayout(root, item.params)
            }
        }
    }

    private fun applyBorderStyle(root: FrameLayout) {
        val prefs = getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_BORDER_ENABLED, true)
        if (!enabled) {
            root.setPadding(0, 0, 0, 0)
            root.background = null
            return
        }
        val strokeWidth = (2 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val cornerRadiusPx = 8f * resources.displayMetrics.density
        root.setPadding(strokeWidth, strokeWidth, strokeWidth, strokeWidth)
        root.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(Color.parseColor("#10000000"))
            setStroke(strokeWidth, Color.parseColor("#CC88E6FF"))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        clearAll()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    data class WindowItem(
        val view: View,
        val params: WindowManager.LayoutParams
    )

    data class ActionPanelItem(
        val view: View,
        val params: WindowManager.LayoutParams,
        var visible: Boolean = false
    )

    companion object {
        const val ACTION_SHOW = "com.mycut.app.action.SHOW"
        const val ACTION_CLEAR = "com.mycut.app.action.CLEAR"
        const val ACTION_REFRESH_STYLE = "com.mycut.app.action.REFRESH_STYLE"
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_IMAGE_KEY = "extra_image_key"
        const val EXTRA_TARGET_X = "extra_target_x"
        const val EXTRA_TARGET_Y = "extra_target_y"
        const val EXTRA_TARGET_WIDTH = "extra_target_width"
        const val EXTRA_TARGET_HEIGHT = "extra_target_height"
        private const val PREFS_APP = "app_prefs"
        private const val PREF_BORDER_ENABLED = "floating_border_enabled"
        private const val PREFS_NAME = "floating_image_prefs"
        private const val PREF_SCALE = "floating_preferred_scale"
        private const val LONG_PRESS_TRIGGER_MS = 1000L
        private const val CHANNEL_ID = "mycut_overlay_channel"
        private const val NOTIFICATION_ID = 11001

        fun refreshStyle(context: Context) {
            val intent = Intent(context, FloatingImageService::class.java).apply {
                action = ACTION_REFRESH_STYLE
            }
            context.startService(intent)
        }
    }
}
