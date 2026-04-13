package com.mycut.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MediaProjectionCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var captureSession: ScreenCaptureHelper.ProjectionSession? = null
    private var firstCaptureAfterGrant = false
    private var requestingPermission = false
    private var capturing = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REQUEST_CAPTURE -> {
                AppLog.write(this, "ProjectionService", "capture action received")
                handleCaptureRequest()
            }

            ACTION_GRANT_SESSION -> {
                AppLog.write(this, "ProjectionService", "grant session action received")
                handleGrantSession(intent)
            }

            ACTION_PERMISSION_CANCELED -> {
                AppLog.write(this, "ProjectionService", "projection permission canceled")
                requestingPermission = false
            }

            ACTION_STOP_SESSION -> {
                AppLog.write(this, "ProjectionService", "stop session action received")
                requestingPermission = false
                releaseSession(stopProjection = true)
                runCatching {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun handleCaptureRequest() {
        val projection = mediaProjection
        val session = captureSession
        if (projection == null || session == null) {
            AppLog.write(this, "ProjectionService", "session missing, request permission")
            requestProjectionPermission()
            return
        }
        val currentSize = ScreenCaptureHelper.getCurrentScreenSize(this)
        if (session.width != currentSize.width || session.height != currentSize.height) {
            AppLog.write(
                this,
                "ProjectionService",
                "screen size changed old=${session.width}x${session.height} new=${currentSize.width}x${currentSize.height}, re-request permission"
            )
            releaseSession(stopProjection = true)
            requestProjectionPermission()
            return
        }
        AppLog.write(this, "ProjectionService", "reuse existing session")
        captureWithSession(session)
    }

    private fun requestProjectionPermission() {
        if (requestingPermission) {
            AppLog.write(this, "ProjectionService", "projection request already pending")
            return
        }
        requestingPermission = true
        runCatching {
            val intent = Intent(this, CaptureTriggerActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(intent)
            AppLog.write(this, "ProjectionService", "launch projection permission activity")
        }.onFailure {
            requestingPermission = false
            AppLog.write(this, "ProjectionService", "launch projection permission failed", it)
        }
    }

    private fun handleGrantSession(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = getResultData(intent)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            requestingPermission = false
            AppLog.write(this, "ProjectionService", "missing projection result data")
            return
        }
        val foregroundStarted = runCatching {
            startProjectionForeground()
        }.onFailure {
            AppLog.write(this, "ProjectionService", "start projection foreground failed", it)
        }.isSuccess
        if (!foregroundStarted) {
            requestingPermission = false
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            requestingPermission = false
            AppLog.write(this, "ProjectionService", "getMediaProjection returned null")
            return
        }
        releaseSession(stopProjection = true)
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                AppLog.write(this@MediaProjectionCaptureService, "ProjectionService", "projection session stopped by system")
                releaseSession(stopProjection = false)
                requestingPermission = false
                runCatching {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
        projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        projectionCallback = callback
        mediaProjection = projection
        val session = ScreenCaptureHelper.createSession(this, projection)
        if (session == null) {
            AppLog.write(this, "ProjectionService", "create projection session failed")
            releaseSession(stopProjection = true)
            requestingPermission = false
            return
        }
        captureSession = session
        requestingPermission = false
        firstCaptureAfterGrant = true
        AppLog.write(this, "ProjectionService", "projection session ready")
        captureWithSession(session)
    }

    private fun captureWithSession(session: ScreenCaptureHelper.ProjectionSession) {
        if (capturing) {
            AppLog.write(this, "ProjectionService", "capture skipped, previous still running")
            return
        }
        capturing = true
        scope.launch {
            AppLog.write(this@MediaProjectionCaptureService, "ProjectionService", "start capture with session")
            val delayMs = if (firstCaptureAfterGrant) FIRST_CAPTURE_DELAY_MS else 80L
            if (firstCaptureAfterGrant) {
                AppLog.write(this@MediaProjectionCaptureService, "ProjectionService", "first capture delay ${delayMs}ms to avoid permission dialog residue")
            }
            val bitmap = ScreenCaptureHelper.captureFromSession(
                context = this@MediaProjectionCaptureService,
                session = session,
                delayMs = delayMs
            )
            firstCaptureAfterGrant = false
            if (bitmap == null) {
                AppLog.write(this@MediaProjectionCaptureService, "ProjectionService", "capture result is null")
                capturing = false
                return@launch
            }
            val bitmapKey = BitmapMemoryStore.put(bitmap)
            AppLog.write(this@MediaProjectionCaptureService, "ProjectionService", "capture success bitmapKey=$bitmapKey")
            val selectionIntent = Intent(this@MediaProjectionCaptureService, SelectionOverlayService::class.java).apply {
                action = SelectionOverlayService.ACTION_SHOW
                putExtra(SelectionOverlayService.EXTRA_BITMAP_KEY, bitmapKey)
            }
            startService(selectionIntent)
            capturing = false
        }
    }

    private fun startProjectionForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun getResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.projection_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.projection_notification_title))
            .setContentText(getString(R.string.projection_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        requestingPermission = false
        releaseSession(stopProjection = true)
        scope.cancel()
        super.onDestroy()
    }

    private fun releaseSession(stopProjection: Boolean) {
        val projection = mediaProjection
        val callback = projectionCallback
        val session = captureSession
        mediaProjection = null
        projectionCallback = null
        captureSession = null
        firstCaptureAfterGrant = false
        capturing = false
        if (session != null) {
            runCatching {
                session.virtualDisplay.release()
            }
            runCatching {
                session.imageReader.close()
            }
        }
        if (projection != null && callback != null) {
            runCatching {
                projection.unregisterCallback(callback)
            }
        }
        if (projection != null && stopProjection) {
            runCatching {
                projection.stop()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_REQUEST_CAPTURE = "com.mycut.app.action.PROJECTION_REQUEST_CAPTURE"
        const val ACTION_GRANT_SESSION = "com.mycut.app.action.PROJECTION_GRANT_SESSION"
        const val ACTION_PERMISSION_CANCELED = "com.mycut.app.action.PROJECTION_PERMISSION_CANCELED"
        const val ACTION_STOP_SESSION = "com.mycut.app.action.PROJECTION_STOP_SESSION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val CHANNEL_ID = "mycut_projection_channel"
        private const val NOTIFICATION_ID = 11021
        private const val FIRST_CAPTURE_DELAY_MS = 700L

        fun requestCapture(context: Context) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                action = ACTION_REQUEST_CAPTURE
            }
            context.startService(intent)
        }

        fun grantSession(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                action = ACTION_GRANT_SESSION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun notifyPermissionCanceled(context: Context) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                action = ACTION_PERMISSION_CANCELED
            }
            context.startService(intent)
        }

        fun stopSession(context: Context) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java).apply {
                action = ACTION_STOP_SESSION
            }
            context.startService(intent)
        }
    }
}
