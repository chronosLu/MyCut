package com.mycut.app

import android.app.Activity
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class CaptureTriggerActivity : AppCompatActivity() {
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                AppLog.write(this, "CaptureTrigger", "projection canceled")
                MediaProjectionCaptureService.notifyPermissionCanceled(this)
                finish()
                overridePendingTransition(0, 0)
                return@registerForActivityResult
            }
            AppLog.write(this, "CaptureTrigger", "projection granted")
            runCatching {
                MediaProjectionCaptureService.grantSession(
                    context = this,
                    resultCode = result.resultCode,
                    resultData = result.data!!
                )
            }.onFailure {
                AppLog.write(this, "CaptureTrigger", "start projection service failed", it)
                Toast.makeText(this, "截图异常，已记录日志", Toast.LENGTH_SHORT).show()
            }
            finish()
            overridePendingTransition(0, 0)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        AppLog.write(this, "CaptureTrigger", "launch projection request")
        launchProjectionRequest()
    }

    private fun launchProjectionRequest() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionLauncher.launch(
                manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            )
            return
        }
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
