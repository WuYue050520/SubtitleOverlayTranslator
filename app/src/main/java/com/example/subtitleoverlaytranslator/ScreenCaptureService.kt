package com.example.subtitleoverlaytranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "subtitle_translate"
        private const val NOTIFICATION_ID = 42
        private const val OCR_INTERVAL_MS = 750L
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: TextView? = null
    private var lastText = ""
    private var lastShown = ""
    private var processing = false
    private var lastOcrAt = 0L

    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val japaneseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }
    private val translators = HashMap<String, Translator>()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            handler.post { stopSelf() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        } ?: return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, buildNotification())

        if (projection == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(projectionCallback, handler)
            startCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = System.currentTimeMillis()
            if (processing || now - lastOcrAt < OCR_INTERVAL_MS) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            lastOcrAt = now
            processing = true

            executor.execute {
                val bitmap = try { imageToBitmap(image) } catch (_: Exception) { null }
                image.close()
                if (bitmap == null) {
                    processing = false
                    return@execute
                }
                processBitmap(bitmap)
            }
        }, handler)

        virtualDisplay = projection?.createVirtualDisplay(
            "SubtitleOverlayTranslator",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )
    }

    private fun processBitmap(bitmap: Bitmap) {
        val cropTop = (bitmap.height * 0.45f).toInt().coerceAtLeast(0)
        val crop = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop)
        bitmap.recycle()

        val input = InputImage.fromBitmap(crop, 0)
        val latinTask = latinRecognizer.process(input)
        val japaneseTask = japaneseRecognizer.process(input)

        Tasks.whenAllSuccess<Any>(latinTask, japaneseTask)
            .addOnSuccessListener { results ->
                try {
                    val latin = results.getOrNull(0) as? Text
                    val japanese = results.getOrNull(1) as? Text
                    val candidates = linkedSetOf<String>()

                    latin?.textBlocks?.flatMap { it.lines }?.forEach { line ->
                        val text = cleanOcr(line.text)
                        if (text.isNotBlank()) candidates.add(text)
                    }
                    japanese?.textBlocks?.flatMap { it.lines }?.forEach { line ->
                        val text = cleanOcr(line.text)
                        if (containsJapanese(text)) candidates.add(text)
                    }

                    // Japanese OCR can sometimes hallucinate CJK characters from English text.
                    // Prefer a genuine Japanese-script candidate only when it contains kana;
                    // otherwise use the English/Latin candidate.
                    val text = chooseSubtitle(candidates)
                    if (text.isBlank()) {
                        handler.post { processing = false }
                    } else if (text != lastText) {
                        lastText = text
                        translate(text)
                    } else {
                        handler.post { processing = false }
                    }
                } finally {
                    crop.recycle()
                }
            }
            .addOnFailureListener {
                crop.recycle()
                processing = false
            }
    }

    private fun chooseSubtitle(candidates: Set<String>): String {
        if (candidates.isEmpty()) return ""

        val japanese = candidates
            .filter { containsJapanese(it) && hasJapaneseKana(it) }
            .maxByOrNull { japaneseScore(it) }

        val latin = candidates
            .filter { isLikelyLatinSubtitle(it) }
            .maxByOrNull { latinScore(it) }

        // If a real Japanese kana candidate exists, use it. Otherwise prefer
        // the Latin/English OCR result so Japanese OCR false positives do not win.
        return japanese ?: latin ?: candidates.maxByOrNull { it.length }.orEmpty()
    }

    private fun hasJapaneseKana(text: String): Boolean =
        text.any { c -> c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF' }

    private fun japaneseScore(text: String): Int =
        text.count { c -> c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF' } * 4 +
            text.count { c -> c in '\u4E00'..'\u9FFF' }

    private fun isLikelyLatinSubtitle(text: String): Boolean {
        val letters = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        return letters >= 2 && !hasJapaneseKana(text)
    }

    private fun latinScore(text: String): Int =
        text.count { it in 'A'..'Z' || it in 'a'..'z' } * 2 + text.count { it.isDigit() }

    private fun cleanOcr(text: String): String =
        text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()

    private fun containsJapanese(text: String): Boolean =
        text.any { c -> c in '\u3040'..'\u309F' || c in '\u30A0'..'\u30FF' || c in '\u4E00'..'\u9FFF' }

    private fun translate(text: String) {
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { lang ->
                val source = when {
                    lang == "ja" || containsJapanese(text) -> TranslateLanguage.JAPANESE
                    lang == "en" -> TranslateLanguage.ENGLISH
                    text.any { it in 'A'..'Z' || it in 'a'..'z' } -> TranslateLanguage.ENGLISH
                    else -> {
                        processing = false
                        return@addOnSuccessListener
                    }
                }

                val translator = translators.getOrPut(source) {
                    val options = TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(TranslateLanguage.CHINESE)
                        .build()
                    Translation.getClient(options)
                }

                val conditions = DownloadConditions.Builder().requireWifi().build()
                translator.downloadModelIfNeeded(conditions)
                    .continueWithTask { translator.translate(text) }
                    .addOnSuccessListener { result ->
                        if (result.isNotBlank()) {
                            lastShown = result
                            showTranslation(result)
                        }
                        processing = false
                    }
                    .addOnFailureListener {
                        processing = false
                    }
            }
            .addOnFailureListener {
                processing = false
            }
    }

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create("sans", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(18, 8, 18, 8)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setBackgroundColor(Color.argb(65, 0, 0, 0))
            maxLines = 2
            maxWidth = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (resources.displayMetrics.heightPixels * 0.10f).toInt()
        }
        wm.addView(tv, params)
        overlay = tv
    }

    private fun showTranslation(text: String) {
        handler.post { overlay?.text = text }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmapWidth = image.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return if (bitmapWidth == image.width) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also { bitmap.recycle() }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("字幕浮译正在运行")
            .setContentText("正在识别英文/日文并翻译成中文")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "字幕浮译", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) { }
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(overlay) } catch (_: Exception) { }
        overlay = null
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        translators.values.forEach { it.close() }
        latinRecognizer.close()
        japaneseRecognizer.close()
        languageIdentifier.close()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}