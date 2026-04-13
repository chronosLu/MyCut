package com.mycut.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class QuickBallService : Service() {
    private lateinit var windowManager: WindowManager
    private var ballView: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
            AppLog.write(this, "QuickBall", "startForeground failed", it)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val shown = showBall()
                if (shown) {
                    AppLog.write(this, "QuickBall", "action start")
                }
            }
            ACTION_REFRESH -> {
                removeBall()
                val shown = showBall()
                if (shown) {
                    AppLog.write(this, "QuickBall", "action refresh")
                }
            }
            ACTION_STOP -> {
                AppLog.write(this, "QuickBall", "action stop")
                removeBall()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun showBall(): Boolean {
        if (ballView != null) return false
        val root = FrameLayout(this)
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val ballSizeDp = prefs.getInt(PREF_BALL_SIZE_DP, DEFAULT_BALL_SIZE_DP).coerceIn(MIN_BALL_SIZE_DP, MAX_BALL_SIZE_DP)
        val size = (resources.displayMetrics.density * ballSizeDp).toInt()
        val ball = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66FFFFFF.toInt(), 0x33CCCCCC.toInt(), 0x22111111)
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke((1.5f * resources.displayMetrics.density).toInt(), 0x66FFFFFF)
            }
        }
        val shine = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xAAFFFFFF.toInt(), 0x11FFFFFF, 0x00FFFFFF)
            ).apply {
                shape = GradientDrawable.OVAL
            }
        }
        val label = TextView(this).apply {
            text = "✂"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        ball.addView(label, FrameLayout.LayoutParams(size, size))
        ball.addView(
            shine,
            FrameLayout.LayoutParams((size * 0.86f).toInt(), (size * 0.4f).toInt()).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (size * 0.08f).toInt()
            }
        )
        root.addView(ball, FrameLayout.LayoutParams(size, size))

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - 36
            y = resources.displayMetrics.heightPixels / 3
        }

        bindTouch(root, params)
        runCatching {
            windowManager.addView(root, params)
            ballView = root
        }.onFailure {
            AppLog.write(this, "QuickBall", "add ball view failed", it)
            return false
        }
        return true
    }

    private fun bindTouch(view: View, params: WindowManager.LayoutParams) {
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    AppLog.write(this@QuickBallService, "QuickBall", "double tap capture")
                    requestCapture()
                    return true
                }
            }
        )
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    runCatching {
                        windowManager.updateViewLayout(view, params)
                    }
                }
            }
            true
        }
    }

    private fun requestCapture() {
        runCatching {
            MediaProjectionCaptureService.requestCapture(this)
        }.onFailure {
            AppLog.write(this, "QuickBall", "request capture failed", it)
        }
    }

    private fun removeBall() {
        val view = ballView ?: return
        runCatching {
            windowManager.removeView(view)
        }
        ballView = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.quick_ball_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.quick_ball_notification_title))
            .setContentText(getString(R.string.quick_ball_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        removeBall()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.mycut.app.action.BALL_START"
        const val ACTION_REFRESH = "com.mycut.app.action.BALL_REFRESH"
        const val ACTION_STOP = "com.mycut.app.action.BALL_STOP"
        private const val CHANNEL_ID = "mycut_quick_ball_channel"
        private const val NOTIFICATION_ID = 11011
        private const val PREF_BALL_SIZE_DP = "ball_size_dp"
        private const val DEFAULT_BALL_SIZE_DP = 58
        private const val MIN_BALL_SIZE_DP = 40
        private const val MAX_BALL_SIZE_DP = 96

        fun refresh(context: Context) {
            val intent = Intent(context, QuickBallService::class.java).apply {
                action = ACTION_REFRESH
            }
            context.startService(intent)
        }
    }
}
