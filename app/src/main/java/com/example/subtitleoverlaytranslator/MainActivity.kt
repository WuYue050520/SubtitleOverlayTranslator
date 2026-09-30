package com.example.subtitleoverlaytranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val captureRequest = 1001
    private val notificationRequest = 1002
    private lateinit var status: TextView
    private lateinit var apiKeyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 40, 32, 32)
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
        }
        val desc = TextView(this).apply {
            text = "在 YouTube、浏览器、播放器等 App 上方实时识别英文/日文字幕，并显示简体中文。\n\n默认只扫描画面下半部，并降低扫描频率以减少耗电。"
            textSize = 16f
            setPadding(0, 20, 0, 30)
        }
        status = TextView(this).apply {
            textSize = 15f
            text = "状态：未启动"
        }
        val start = Button(this).apply {
            text = "开启实时翻译"
            setOnClickListener { requestOverlayThenCapture() }
        }
        val stop = Button(this).apply {
            text = "停止翻译"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                status.text = "状态：已停止"
            }
        }
        apiKeyInput = EditText(this).apply {
            hint = "OpenAI API Key（可选）"
            text = getPreferences(Context.MODE_PRIVATE).getString("openai_key", "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val saveKey = Button(this).apply {
            text = "保存 AI Key / 启用 AI 视觉翻译"
            setOnClickListener {
                getPreferences(Context.MODE_PRIVATE).edit().putString("openai_key", apiKeyInput.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, if (apiKeyInput.text.toString().trim().isEmpty()) "已关闭 AI 模式，使用本地 OCR" else "AI Key 已保存", Toast.LENGTH_SHORT).show()
            }
        }
        val note = TextView(this).apply {
            text = "第一次使用需要允许“显示在其他应用上层”和“录制/投射屏幕”。首次翻译英文/日文时还需要下载 ML Kit 翻译模型。"
            textSize = 13f
            setPadding(0, 20, 0, 0)
        }
        root.addView(title)
        root.addView(desc)
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        root.addView(apiKeyInput)
        root.addView(saveKey)
        root.addView(note)
        setContentView(root)
    }

    private fun requestOverlayThenCapture() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "请允许“显示在其他应用上层”，回来后再次点击开启。", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Kept for simple AndroidIDE compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            status.text = "状态：正在实时识别（现在可以切到 YouTube 等 App）"
        } else if (requestCode == captureRequest) {
            status.text = "状态：未获得屏幕录制权限"
        }
    }
}