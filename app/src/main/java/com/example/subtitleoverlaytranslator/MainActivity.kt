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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val captureRequest = 1001
    private val notificationRequest = 1002
    private val audioRequest = 1003
    private lateinit var status: TextView
    private lateinit var apiKeyInput: EditText
    private lateinit var modeSpinner: Spinner
    private lateinit var audioSpinner: Spinner

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
            text = "在 YouTube、浏览器、播放器等 App 上方实时识别英文/日文字幕，并显示简体中文。\n\n可选择纪录片、动画、漫画、游戏等模式，让 AI 根据内容类型处理专有名词、语气和上下文。"
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
        val modeLabel = TextView(this).apply {
            text = "翻译模式（影响 AI 对术语、语气和上下文的处理）"
            textSize = 15f
            setPadding(0, 18, 0, 6)
        }
        modeSpinner = Spinner(this)
        val modes = arrayOf(
            "纪录片（河中巨怪等）",
            "动画 / 番剧",
            "漫画 / 漫画视频",
            "游戏",
            "电影 / 连续剧",
            "普通通用"
        )
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        val prefs = getSharedPreferences("subtitle_settings", Context.MODE_PRIVATE)
        modeSpinner.setSelection(prefs.getInt("translation_mode", 0).coerceIn(0, modes.lastIndex))
        modeSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt("translation_mode", position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        val audioLabel = TextView(this).apply {
            text = "声音识别来源（可与画面字幕一起交给 AI）"
            textSize = 15f
            setPadding(0, 18, 0, 6)
        }
        audioSpinner = Spinner(this)
        val audioSources = arrayOf(
            "🔇 不使用声音（只识别画面字幕）",
            "📱 手机 App 内部声音（YouTube / 播放器 / 游戏）",
            "🎤 手机外部声音（麦克风）"
        )
        audioSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, audioSources)
        audioSpinner.setSelection(prefs.getInt("audio_source", 0).coerceIn(0, audioSources.lastIndex))
        audioSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt("audio_source", position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        apiKeyInput = EditText(this).apply {
            hint = "Gemini API Key（免费额度）"
            setText(getSharedPreferences("subtitle_settings", Context.MODE_PRIVATE).getString("gemini_key", "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val saveKey = Button(this).apply {
            text = "保存 Gemini Key / 启用 AI 视觉翻译"
            setOnClickListener {
                getSharedPreferences("subtitle_settings", Context.MODE_PRIVATE).edit().putString("gemini_key", apiKeyInput.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, if (apiKeyInput.text.toString().trim().isEmpty()) "已关闭 AI 模式，使用本地 OCR" else "Gemini Key 已保存", Toast.LENGTH_SHORT).show()
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
        root.addView(modeLabel)
        root.addView(modeSpinner)
        root.addView(audioLabel)
        root.addView(audioSpinner)
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

        val audioSource = getSharedPreferences("subtitle_settings", Context.MODE_PRIVATE).getInt("audio_source", 0)
        if (audioSource == 2 && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), audioRequest)
            Toast.makeText(this, "请允许麦克风权限后，再点击一次开启翻译。", Toast.LENGTH_LONG).show()
            return
        }

        startScreenCaptureRequest()
    }

    private fun startScreenCaptureRequest() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioRequest) {
            startScreenCaptureRequest()
        }
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