package com.mycut.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object ScreenCaptureHelper {
    data class ScreenSize(
        val width: Int,
        val height: Int,
        val densityDpi: Int
    )

    data class ProjectionSession(
        val imageReader: ImageReader,
        val virtualDisplay: VirtualDisplay,
        val width: Int,
        val height: Int
    )

    fun createSession(
        context: Context,
        mediaProjection: MediaProjection
    ): ProjectionSession? {
        return runCatching {
            val size = getCurrentScreenSize(context)
            AppLog.write(context, "ScreenCapture", "screen size w=${size.width} h=${size.height}")
            val imageReader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 3)
            val virtualDisplay = mediaProjection.createVirtualDisplay(
                "mycut_capture",
                size.width,
                size.height,
                size.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )
            AppLog.write(context, "ScreenCapture", "projection session display ready")
            ProjectionSession(
                imageReader = imageReader,
                virtualDisplay = virtualDisplay,
                width = size.width,
                height = size.height
            )
        }.onFailure {
            AppLog.write(context, "ScreenCapture", "create session failed", it)
        }.getOrNull()
    }

    suspend fun captureFromSession(
        context: Context,
        session: ProjectionSession,
        delayMs: Long = 80L
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            delay(delayMs)
            val image = awaitImage(session.imageReader) ?: return@withContext null
            image.use {
                AppLog.write(context, "ScreenCapture", "image acquired")
                return@withContext imageToBitmap(it, session.width, session.height)
            }
        }.onFailure {
            AppLog.write(context, "ScreenCapture", "capture failed", it)
        }.getOrNull()
    }

    suspend fun captureWithProjection(
        context: Context,
        mediaProjection: MediaProjection
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val size = getCurrentScreenSize(context)
            AppLog.write(context, "ScreenCapture", "screen size w=${size.width} h=${size.height}")
            val imageReader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 3)
            var virtualDisplay: VirtualDisplay? = null
            try {
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "mycut_capture",
                    size.width,
                    size.height,
                    size.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    null
                )
                delay(80)
                val image = awaitImage(imageReader) ?: return@withContext null
                image.use {
                    AppLog.write(context, "ScreenCapture", "image acquired")
                    return@withContext imageToBitmap(it, size.width, size.height)
                }
            } finally {
                imageReader.setOnImageAvailableListener(null, null)
                virtualDisplay?.release()
                imageReader.close()
            }
        }.onFailure {
            AppLog.write(context, "ScreenCapture", "capture failed", it)
        }.getOrNull()
    }

    suspend fun captureScreen(
        context: Context,
        resultCode: Int,
        data: Intent
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val projectionManager =
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = projectionManager.getMediaProjection(resultCode, data) ?: return@withContext null
            val projectionCallback = object : MediaProjection.Callback() {}
            val callbackHandler = Handler(Looper.getMainLooper())
            mediaProjection.registerCallback(projectionCallback, callbackHandler)
            AppLog.write(context, "ScreenCapture", "mediaProjection created")
            try {
                return@withContext captureWithProjection(context, mediaProjection)
            } finally {
                mediaProjection.unregisterCallback(projectionCallback)
                mediaProjection.stop()
            }
        }.onFailure {
            AppLog.write(context, "ScreenCapture", "capture failed", it)
        }.getOrNull()
    }

    fun getCurrentScreenSize(context: Context): ScreenSize {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = context.resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        return ScreenSize(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi
        )
    }

    private suspend fun awaitImage(imageReader: ImageReader): Image? =
        withTimeoutOrNull(2500) {
            suspendCancellableCoroutine { continuation ->
                val directImage = imageReader.acquireLatestImage()
                if (directImage != null && continuation.isActive) {
                    continuation.resume(directImage)
                    return@suspendCancellableCoroutine
                }
                val handler = Handler(Looper.getMainLooper())
                imageReader.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null && continuation.isActive) {
                        continuation.resume(image)
                    }
                }, handler)
            }
        }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }
}
