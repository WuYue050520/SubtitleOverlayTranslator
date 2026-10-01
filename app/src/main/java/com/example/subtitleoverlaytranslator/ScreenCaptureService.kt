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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.media.AudioPlaybackCaptureConfiguration
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.pm.ServiceInfo
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
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "subtitle_translate"
        private const val NOTIFICATION_ID = 42
        private const val OCR_INTERVAL_MS = 900L
        private const val AI_INTERVAL_MS = 2200L
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
    private var lastAiAt = 0L
    private var lastAiImageSignature = ""
    private var dailyQuotaPausedUntil = 0L
    private var quotaMessageShown = false
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var latestAudioWav: ByteArray? = null
    private val httpClient = OkHttpClient.Builder().build()

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
            handler.post { getSharedPreferences("subtitle_settings", MODE_PRIVATE).edit().putBoolean("running", false).apply(); stopSelf() }
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

        val audioSource = getSharedPreferences("subtitle_settings", MODE_PRIVATE).getInt("audio_source", 0)
        if (Build.VERSION.SDK_INT >= 29) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (audioSource == 2) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        getSharedPreferences("subtitle_settings", MODE_PRIVATE).edit().putBoolean("running", true).apply()

        if (projection == null) {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(projectionCallback, handler)
            startCapture()
            startAudioCapture(audioSource)
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
        val cropTop = (bitmap.height * 0.50f).toInt().coerceAtLeast(0)
        val cropBottom = (bitmap.height * 0.90f).toInt().coerceAtMost(bitmap.height)
        val cropHeight = (cropBottom - cropTop).coerceAtLeast(1)
        val crop = Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, cropHeight)
        bitmap.recycle()

        val apiKey = getSharedPreferences("subtitle_settings", MODE_PRIVATE)
            .getString("gemini_key", "")?.trim().orEmpty()

        if (apiKey.isNotEmpty() && System.currentTimeMillis() >= dailyQuotaPausedUntil && System.currentTimeMillis() - lastAiAt >= AI_INTERVAL_MS) {
            quotaMessageShown = false
            lastAiAt = System.currentTimeMillis()
            processWithAiVision(crop, apiKey, latestAudioWav)
        } else {
            processWithLocalOcr(crop)
        }
    }

    private fun startAudioCapture(source: Int) {
        if (source == 0 || audioThread != null) return
        if (source == 2 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (source == 1 && Build.VERSION.SDK_INT < 29) {
            handler.post { showTranslation("手机 App 内部声音需要 Android 10 或更高版本") }
            return
        }
        try {
            val sampleRate = 16000
            val channel = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding).coerceAtLeast(sampleRate * 2)
            val bufferSize = minBuffer * 2
            val record = if (source == 1) {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder().setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate).setChannelMask(channel).build())
                    .setBufferSizeInBytes(bufferSize).setAudioPlaybackCaptureConfig(config).build()
            } else {
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(sampleRate).setChannelMask(channel).build())
                    .setBufferSizeInBytes(bufferSize).build()
            }
            audioRecord = record
            audioThread = Thread {
                val samplesPerChunk = sampleRate * 3
                val pcm = ShortArray(samplesPerChunk)
                try {
                    record.startRecording()
                    while (!Thread.currentThread().isInterrupted && audioRecord === record) {
                        var filled = 0
                        while (filled < samplesPerChunk && !Thread.currentThread().isInterrupted) {
                            val n = record.read(pcm, filled, samplesPerChunk - filled, AudioRecord.READ_BLOCKING)
                            if (n <= 0) break
                            filled += n
                        }
                        if (filled > sampleRate / 2) latestAudioWav = pcmToWav(pcm, filled, sampleRate)
                    }
                } catch (_: Exception) {
                } finally {
                    try { record.stop() } catch (_: Exception) {}
                    record.release()
                }
            }.also { it.start() }
        } catch (_: Exception) {
            audioRecord = null
            audioThread = null
        }
    }

    private fun pcmToWav(samples: ShortArray, count: Int, sampleRate: Int): ByteArray {
        val dataSize = count * 2
        val out = ByteArray(44 + dataSize)
        fun putInt(pos: Int, value: Int) { out[pos]=(value and 255).toByte(); out[pos+1]=((value shr 8) and 255).toByte(); out[pos+2]=((value shr 16) and 255).toByte(); out[pos+3]=((value shr 24) and 255).toByte() }
        fun putShort(pos: Int, value: Int) { out[pos]=(value and 255).toByte(); out[pos+1]=((value shr 8) and 255).toByte() }
        out[0]='R'.code.toByte(); out[1]='I'.code.toByte(); out[2]='F'.code.toByte(); out[3]='F'.code.toByte()
        putInt(4,36+dataSize); out[8]='W'.code.toByte(); out[9]='A'.code.toByte(); out[10]='V'.code.toByte(); out[11]='E'.code.toByte()
        out[12]='f'.code.toByte(); out[13]='m'.code.toByte(); out[14]='t'.code.toByte(); out[15]=' '.code.toByte()
        putInt(16,16); putShort(20,1); putShort(22,1); putInt(24,sampleRate); putInt(28,sampleRate*2); putShort(32,2); putShort(34,16)
        out[36]='d'.code.toByte(); out[37]='a'.code.toByte(); out[38]='t'.code.toByte(); out[39]='a'.code.toByte(); putInt(40,dataSize)
        var p=44
        for(i in 0 until count){ val v=samples[i].toInt(); out[p++]=(v and 255).toByte(); out[p++]=((v shr 8) and 255).toByte() }
        return out
    }

    private fun processWithLocalOcr(crop: Bitmap) {
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
                        if (text.isNotBlank() && containsJapanese(text)) candidates.add(text)
                    }

                    val text = chooseSubtitle(candidates)
                    if (text.isBlank()) {
                        processing = false
                    } else if (text != lastText) {
                        lastText = text
                        translate(text)
                    } else {
                        processing = false
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

    private fun processWithAiVision(crop: Bitmap, apiKey: String, audioWav: ByteArray?) {
        executor.execute {
            try {
                val maxWidth = 1000
                val scaled = if (crop.width > maxWidth) Bitmap.createScaledBitmap(crop, maxWidth, (crop.height * maxWidth.toFloat() / crop.width).toInt().coerceAtLeast(1), true) else crop
                val signature = imageSignature(scaled)
                if (signature == lastAiImageSignature && audioWav == null) {
                    if (scaled !== crop) scaled.recycle()
                    crop.recycle()
                    handler.post { processing = false }
                    return@execute
                }
                lastAiImageSignature = signature
                val output = java.io.ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
                val imageB64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                if (scaled !== crop) scaled.recycle()
                crop.recycle()

                val prefs = getSharedPreferences("subtitle_settings", MODE_PRIVATE)
                val modeIndex = prefs.getInt("translation_mode", 0)
                val modeInstruction = when (modeIndex) {
                    0 -> "This is a nature/wildlife documentary. Use accurate natural-history and wildlife terminology. Preserve species names, locations and measurements. If an established Chinese common name is certain, use it; otherwise do not invent one."
                    1 -> "This is anime/animation. Preserve character names, attacks, organizations and fictional terms consistently. Keep dialogue natural and character-appropriate."
                    2 -> "This is manga/comic content. Preserve character names and speech-bubble wording. Do not invent text outside the bubble."
                    3 -> "This is a video game. Preserve established game terminology, item/skill names, character names, quests, stats and UI terms consistently."
                    4 -> "This is a movie/TV drama. Preserve names and story terminology consistently and translate dialogue naturally according to speaker tone."
                    else -> "Use general subtitle translation. Preserve names and technical terms and translate naturally according to context."
                }
                val hasAudio = audioWav != null && audioWav.isNotEmpty()
                val prompt = "You are a professional subtitle translator. Use BOTH the visible subtitle image and the supplied audio when audio is present. " +
                    "The audio is the surrounding 3-second speech segment; use it to correct OCR errors, missing words and punctuation. " +
                    "Read ONLY spoken subtitle text. Ignore faces, scenery, signs, logos, watermarks, app UI and unrelated background text. " +
                    "The subtitle may be English or Japanese. Never invent words that are not supported by the image or audio. " +
                    modeInstruction + " Return ONLY the final Simplified Chinese translation, with no explanation, no English reconstruction and no quotation marks. " +
                    "If there is no clear spoken subtitle, return an empty string."

                val parts = JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", imageB64)))
                if (hasAudio) {
                    parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", Base64.encodeToString(audioWav, Base64.NO_WRAP))))
                }

                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")).put("maxOutputTokens", 120))

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent")
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        if (response.code == 429 && isDailyQuotaError(raw)) {
                            val resetAt = nextPacificMidnightMillis()
                            dailyQuotaPausedUntil = resetAt
                            handler.post {
                                showTranslation(quotaMessage(resetAt))
                                processing = false
                            }
                            return@use
                        }
                        throw IllegalStateException("Gemini HTTP " + response.code)
                    }
                    val resultText = extractGeminiText(JSONObject(raw)).trim()
                    handler.post {
                        if (resultText.isNotBlank() && resultText != lastShown) {
                            lastShown = resultText
                            showTranslation(resultText)
                        }
                        processing = false
                    }
                }
            } catch (_: Exception) {
                handler.post { processing = false }
            }
        }
    }

    private fun isDailyQuotaError(raw: String): Boolean {
        val lower = raw.lowercase()
        return lower.contains("resource_exhausted") &&
            (lower.contains("perday") || lower.contains("per day") ||
             lower.contains("requests per day") || lower.contains("daily"))
    }

    private fun nextPacificMidnightMillis(): Long {
        val now = java.time.Instant.now()
        val pacific = java.time.ZoneId.of("America/Los_Angeles")
        val nextDate = now.atZone(pacific).toLocalDate().plusDays(1)
        return nextDate.atStartOfDay(pacific).toInstant().toEpochMilli()
    }

    private fun quotaMessage(resetAt: Long): String {
        val malaysia = java.time.ZoneId.of("Asia/Kuala_Lumpur")
        val reset = java.time.Instant.ofEpochMilli(resetAt).atZone(malaysia)
        return "⚠️ 今日 Gemini 免费额度已用完\\nAI 字幕翻译已暂停\\n预计刷新：%04d年%02d月%02d日 %02d:%02d（马来西亚时间）"
            .format(reset.year, reset.monthValue, reset.dayOfMonth, reset.hour, reset.minute)
    }

    private fun extractGeminiText(json: JSONObject): String {
        val candidates = json.optJSONArray("candidates") ?: return ""
        val result = StringBuilder()
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val content = candidate.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                val text = part.optString("text", "")
                if (text.isNotBlank()) result.append(text)
            }
        }
        return result.toString()
    }

    private fun imageSignature(bitmap: Bitmap): String {
        // Small perceptual signature: enough to skip identical subtitle frames,
        // while still detecting a changed subtitle or scene.
        val w = 16
        val h = 9
        var sum = 0L
        var sumSq = 0L
        val samples = IntArray(w * h)
        var index = 0
        for (y in 0 until h) {
            val sy = (y * bitmap.height / h).coerceAtMost(bitmap.height - 1)
            for (x in 0 until w) {
                val sx = (x * bitmap.width / w).coerceAtMost(bitmap.width - 1)
                val p = bitmap.getPixel(sx, sy)
                val gray = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                samples[index++] = gray
                sum += gray
                sumSq += gray.toLong() * gray
            }
        }
        val mean = sum / samples.size
        val bits = StringBuilder(samples.size)
        for (v in samples) bits.append(if (v >= mean) '1' else '0')
        return bits.toString() + ":" + (sumSq / samples.size)
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
        val cjk = text.count { it in '\u4E00'..'\u9FFF' }
        val totalLetters = letters + cjk
        return letters >= 2 && (totalLetters == 0 || letters.toDouble() / totalLetters >= 0.70) && !hasJapaneseKana(text)
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
        getSharedPreferences("subtitle_settings", MODE_PRIVATE).edit().putBoolean("running", false).apply()
        audioThread?.interrupt()
        audioThread = null
        audioRecord = null
        latestAudioWav = null
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