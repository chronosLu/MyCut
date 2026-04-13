package com.mycut.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var prefs: SharedPreferences

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleImageSelection(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        applyThemeFromPrefs()
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarStyle()
        
        // Apply background if custom one exists
        applyCustomBackground()

        AppLog.write(this, "MainActivity", "onCreate")

        statusText = findViewById(R.id.statusText)
        
        // Setup Grid Cards
        val ballCard: View = findViewById(R.id.ballCard)
        val overlayCard: View = findViewById(R.id.overlayCard)
        val captureCard: View = findViewById(R.id.captureCard)
        
        // Setup Bottom Actions
        val exportLayout: View = findViewById(R.id.exportLayout)
        val clearLayout: View = findViewById(R.id.clearLayout)
        
        // Setup Header Buttons
        val settingsButton: ImageButton = findViewById(R.id.settingsButton)

        ballCard.setOnClickListener { startBallService() }
        overlayCard.setOnClickListener { openOverlayPermission() }
        captureCard.setOnClickListener { openCaptureTrigger() }
        
        clearLayout.setOnClickListener {
            val clearIntent = Intent(this, FloatingImageService::class.java)
            clearIntent.action = FloatingImageService.ACTION_CLEAR
            startService(clearIntent)
            MediaProjectionCaptureService.stopSession(this)
            val stopBallIntent = Intent(this, QuickBallService::class.java)
            stopBallIntent.action = QuickBallService.ACTION_STOP
            startService(stopBallIntent)
            statusText.text = getString(R.string.status_idle)
            statusText.background = ContextCompat.getDrawable(this, R.drawable.bg_status_capsule_idle)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.status_idle_text))
        }
        
        exportLayout.setOnClickListener { openLogViewer() }
        
        settingsButton.setOnClickListener { openSettingsDialog() }
    }

    override fun onResume() {
        super.onResume()
        applySystemBarStyle()
        if (Settings.canDrawOverlays(this)) {
            statusText.text = getString(R.string.status_ready)
            statusText.background = ContextCompat.getDrawable(this, R.drawable.bg_status_capsule_active)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.status_active_text))
            runCatching {
                startBallService()
            }.onFailure {
                AppLog.write(this, "MainActivity", "auto start ball failed", it)
            }
        } else {
            statusText.text = getString(R.string.status_idle)
            statusText.background = ContextCompat.getDrawable(this, R.drawable.bg_status_capsule_idle)
            statusText.setTextColor(ContextCompat.getColor(this, R.color.status_idle_text))
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.isCtrlPressed &&
            event.isShiftPressed &&
            event.keyCode == KeyEvent.KEYCODE_S
        ) {
            openCaptureTrigger()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openCaptureTrigger() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show()
            AppLog.write(this, "MainActivity", "capture trigger blocked by overlay permission")
            openOverlayPermission()
            return
        }
        ensureNotificationPermission()
        AppLog.write(this, "MainActivity", "request capture")
        MediaProjectionCaptureService.requestCapture(this)
    }

    private fun startBallService() {
        if (!Settings.canDrawOverlays(this)) return
        ensureNotificationPermission()
        val intent = Intent(this, QuickBallService::class.java).apply {
            action = QuickBallService.ACTION_START
        }
        AppLog.write(this, "MainActivity", "start quick ball service")
        startService(intent)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION
        )
        startActivity(intent)
    }

    private fun openLogViewer() {
        startActivity(Intent(this, LogViewerActivity::class.java))
    }

    private fun openSettingsDialog() {
        val content = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null, false)
        val borderSwitch = content.findViewById<SwitchMaterial>(R.id.borderSwitch)
        val preview = content.findViewById<View>(R.id.ballPreview)
        val seekBar = content.findViewById<SeekBar>(R.id.ballSizeSeekbar)
        val sizeLabel = content.findViewById<TextView>(R.id.ballSizeLabel)
        val chooseBgButton = content.findViewById<MaterialButton>(R.id.chooseBgButton)
        val clearBgButton = content.findViewById<MaterialButton>(R.id.clearBgButton)
        val doneButton = content.findViewById<MaterialButton>(R.id.settingsDoneButton)
        val minSize = MIN_BALL_SIZE_DP
        val maxSize = MAX_BALL_SIZE_DP
        val currentSize = prefs.getInt(KEY_BALL_SIZE_DP, DEFAULT_BALL_SIZE_DP).coerceIn(minSize, maxSize)
        borderSwitch.isChecked = prefs.getBoolean(KEY_FLOATING_BORDER_ENABLED, true)
        seekBar.max = maxSize - minSize
        seekBar.progress = currentSize - minSize
        updateBallPreview(preview, currentSize)
        sizeLabel.text = "${getString(R.string.settings_ball_size)} ${currentSize}dp"
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(content)
            .setCancelable(true)
            .create()
        borderSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_FLOATING_BORDER_ENABLED, checked).apply()
            FloatingImageService.refreshStyle(this)
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sizeDp = (minSize + progress).coerceIn(minSize, maxSize)
                updateBallPreview(preview, sizeDp)
                sizeLabel.text = "${getString(R.string.settings_ball_size)} ${sizeDp}dp"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val sizeDp = (minSize + (seekBar?.progress ?: 0)).coerceIn(minSize, maxSize)
                prefs.edit().putInt(KEY_BALL_SIZE_DP, sizeDp).apply()
                QuickBallService.refresh(this@MainActivity)
            }
        })
        chooseBgButton.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }
        clearBgButton.setOnClickListener {
            clearCustomBackground()
        }
        doneButton.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun updateBallPreview(preview: View, sizeDp: Int) {
        val px = (sizeDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val params = preview.layoutParams
        params.width = px
        params.height = px
        preview.layoutParams = params
    }
    
    private fun toggleTheme() {
        val currentMode = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val newMode = if (currentMode == AppCompatDelegate.MODE_NIGHT_YES) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        prefs.edit().putInt(KEY_THEME_MODE, newMode).apply()
        AppCompatDelegate.setDefaultNightMode(newMode)
    }

    private fun applySystemBarStyle() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun applyThemeFromPrefs() {
        val mode = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun handleImageSelection(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val file = File(filesDir, CUSTOM_BG_FILENAME)
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            prefs.edit().putBoolean(KEY_HAS_CUSTOM_BG, true).apply()
            applyCustomBackground()
            Toast.makeText(this, "背景已更新", Toast.LENGTH_SHORT).show()
        }.onFailure {
            AppLog.write(this, "MainActivity", "save custom bg failed", it)
            Toast.makeText(this, "背景设置失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyCustomBackground() {
        val backgroundView = findViewById<ImageView>(R.id.backgroundImageView)
        val rootView = findViewById<View>(R.id.rootLayout)
        if (prefs.getBoolean(KEY_HAS_CUSTOM_BG, false)) {
            val file = File(filesDir, CUSTOM_BG_FILENAME)
            if (file.exists()) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    backgroundView.setImageDrawable(BitmapDrawable(resources, bitmap))
                    backgroundView.visibility = View.VISIBLE
                    rootView.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent))
                    return
                }
            }
            prefs.edit().putBoolean(KEY_HAS_CUSTOM_BG, false).apply()
        }
        backgroundView.setImageDrawable(null)
        backgroundView.visibility = View.GONE
        rootView.setBackgroundColor(ContextCompat.getColor(this, R.color.app_background))
    }

    private fun clearCustomBackground() {
        val file = File(filesDir, CUSTOM_BG_FILENAME)
        if (file.exists()) {
            file.delete()
        }
        prefs.edit().putBoolean(KEY_HAS_CUSTOM_BG, false).apply()
        applyCustomBackground()
        Toast.makeText(this, "背景已重置", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_HAS_CUSTOM_BG = "has_custom_bg"
        private const val KEY_FLOATING_BORDER_ENABLED = "floating_border_enabled"
        private const val KEY_BALL_SIZE_DP = "ball_size_dp"
        private const val CUSTOM_BG_FILENAME = "custom_bg.png"
        private const val DEFAULT_BALL_SIZE_DP = 58
        private const val MIN_BALL_SIZE_DP = 40
        private const val MAX_BALL_SIZE_DP = 96
    }
}
