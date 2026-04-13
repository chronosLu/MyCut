package com.mycut.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class RegionSelectActivity : AppCompatActivity() {
    private lateinit var imageView: ImageView
    private lateinit var overlayView: SelectionOverlayView
    private var sourceBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_region_select)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        imageView = findViewById(R.id.fullImageView)
        overlayView = findViewById(R.id.overlayView)
        val doneButton: Button = findViewById(R.id.doneButton)
        val cancelButton: Button = findViewById(R.id.cancelButton)

        val imageUri = intent.getStringExtra(EXTRA_IMAGE_URI)?.let(Uri::parse)
        if (imageUri == null) {
            AppLog.write(this, "RegionSelect", "image uri missing")
            finish()
            return
        }

        sourceBitmap = contentResolver.openInputStream(imageUri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
        val bitmap = sourceBitmap
        if (bitmap == null) {
            AppLog.write(this, "RegionSelect", "decode source bitmap failed")
            finish()
            return
        }
        AppLog.write(this, "RegionSelect", "image ready w=${bitmap.width} h=${bitmap.height}")
        imageView.setImageBitmap(bitmap)

        doneButton.setOnClickListener { completeSelection() }
        cancelButton.setOnClickListener { finish() }
    }

    private fun completeSelection() {
        val bitmap = sourceBitmap ?: return
        val selectedRect = overlayView.getSelectionRect()
        if (selectedRect.width() < 10f || selectedRect.height() < 10f) {
            Toast.makeText(this, "请先框选区域", Toast.LENGTH_SHORT).show()
            return
        }
        val cropRect = mapSelectionToBitmapRect(selectedRect) ?: run {
            Toast.makeText(this, "选区无效，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        val left = cropRect.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = cropRect.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = cropRect.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = cropRect.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        AppLog.write(
            this,
            "RegionSelect",
            "selection l=$left t=$top r=$right b=$bottom w=${right - left} h=${bottom - top}"
        )
        val uri = ImageStore.saveBitmap(this, cropped, "crop")

        val intent = Intent(this, FloatingImageService::class.java).apply {
            action = FloatingImageService.ACTION_SHOW
            putExtra(FloatingImageService.EXTRA_IMAGE_URI, uri.toString())
        }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure {
            AppLog.write(this, "RegionSelect", "start floating image service failed", it)
            Toast.makeText(this, "贴图服务启动失败，已记录日志", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private fun mapSelectionToBitmapRect(selection: RectF): RectF? {
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
        if (!intersected || intersection.width() <= 1f || intersection.height() <= 1f) {
            return null
        }
        return RectF(
            (intersection.left - transX) / scaleX,
            (intersection.top - transY) / scaleY,
            (intersection.right - transX) / scaleX,
            (intersection.bottom - transY) / scaleY
        )
    }

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
    }
}
