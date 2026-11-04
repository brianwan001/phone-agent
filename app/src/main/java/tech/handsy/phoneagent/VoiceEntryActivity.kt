package tech.handsy.phoneagent

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import tech.handsy.phoneagent.ai.TranscribeClient
import tech.handsy.phoneagent.service.AgentService
import java.io.File

/**
 * Voice task entry (assistant slot / QS tile).
 * Tries the on-device recognizer (XiaoAI engine on HyperOS) first — Google's legacy
 * RecognizerIntent is being retired; if on-device fails, falls back to recording +
 * GLM cloud transcription.
 */
class VoiceEntryActivity : androidx.activity.ComponentActivity() {

    private var audioFile: File? = null
    private var recording = false
    private val main = Handler(Looper.getMainLooper())
    private var status: TextView? = null
    private var toggle: Button? = null

    private var permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            buildManualUi()
        } else {
            Toast.makeText(this, "需要麦克风权限才能语音输入", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Invoked from the system assistant slot (long-press power/home): jump straight
        // into the app, no voice round-trip.
        if (intent?.action == Intent.ACTION_ASSIST) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            finish()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        buildManualUi()
    }

    /** Fallback UI: tap to record, tap to stop, cloud transcription. */
    private fun buildManualUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#E6101420")) }
        status = TextView(this).apply {
            text = "点下方按钮开始说话\n(说完再点一下)"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = 500 }
        }
        toggle = Button(this).apply {
            text = "🎤 开始录音"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setOnClickListener { if (recording) stopAndTranscribe() else startRecording() }
        }
        root.addView(status)
        root.addView(
            toggle,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL,
            ).apply { topMargin = 260 },
        )
        setContentView(root)
    }

    private var audioThread: Thread? = null
    private var audioRecord: android.media.AudioRecord? = null
    @Volatile
    private var stopFlag = false

    /** Records 16 kHz mono PCM straight into a WAV file (best server compatibility). */
    private fun startRecording() {
        val file = File(cacheDir, "voice_task.wav")
        try {
            val rate = 16000
            val minBuf = android.media.AudioRecord.getMinBufferSize(
                rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
            )
            val record = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC, rate,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
            audioRecord = record
            audioFile = file
            stopFlag = false
            record.startRecording()
            val startedAt = System.currentTimeMillis()
            val lastLoudAt = java.util.concurrent.atomic.AtomicLong(startedAt)
            val speechSeen = java.util.concurrent.atomic.AtomicBoolean(false)
            // per-second UI tick
            val ticker = Thread {
                while (!stopFlag) {
                    val sec = (System.currentTimeMillis() - startedAt) / 1000
                    main.post {
                        if (!stopFlag) status?.text = "🎙 正在录音……说完停顿约2秒自动结束(${sec}s)"
                    }
                    Thread.sleep(500)
                }
            }
            ticker.start()
            audioThread = Thread {
                try {
                    val out = java.io.RandomAccessFile(file, "rw").apply { setLength(0) }
                    // 44-byte placeholder header, patched after stop
                    val header = ByteArray(44)
                    out.write(header)
                    val buf = ShortArray(1600)
                    var total = 0L
                    while (!stopFlag) {
                        val n = record.read(buf, 0, buf.size)
                        if (n > 0) {
                            var level = 0
                            for (i in 0 until n) {
                                val a = kotlin.math.abs(buf[i].toInt())
                                if (a > level) level = a
                            }
                            if (level > 2500) {
                                lastLoudAt.set(System.currentTimeMillis())
                                speechSeen.set(true)
                            }
                            val silentMs = System.currentTimeMillis() - lastLoudAt.get()
                            if (speechSeen.get() && silentMs > 2200) stopFlag = true
                            val bytes = ByteArray(n * 2)
                            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer().put(buf, 0, n)
                            out.write(bytes)
                            total += n * 2L
                        }
                    }
                    // patch RIFF header
                    val byteRate = (rate * 2).toLong()
                    out.seek(0)
                    val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    h.put("RIFF".toByteArray())
                    h.putInt((36 + total).toInt())
                    h.put("WAVE".toByteArray())
                    h.put("fmt ".toByteArray())
                    h.putInt(16)
                    h.putShort(1) // PCM
                    h.putShort(1) // mono
                    h.putInt(rate)
                    h.putInt(byteRate.toInt())
                    h.putShort(2)
                    h.putShort(16)
                    h.put("data".toByteArray())
                    h.putInt(total.toInt())
                    out.write(h.array())
                    out.close()
                } catch (e: Exception) {
                    main.post { toast("录音失败: ${e.message}") }
                } finally {
                }
            }.apply { start() }
            recording = true
            toggle?.text = "⏹ 结束并发送"
            status?.text = "正在录音……(最长12秒)"
            main.postDelayed({ if (recording) { status?.text = "达到60秒上限,自动发送"; stopAndTranscribe() } }, 60_000)
        } catch (e: Exception) {
            runOnUiThread {
                toast("录音启动失败: ${e.message}")
                toggle?.isEnabled = true
            }
        }
    }

    private fun stopAndTranscribe() {
        if (!recording) return
        recording = false
        stopFlag = true
        try {
            audioThread?.join(1500)
        } catch (_: InterruptedException) {
        }
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        toggle?.isEnabled = false
        status?.text = "识别中……(GLM-ASR)"
        val file = audioFile
        if (file == null || file.length() <= 44L) {
            finish()
            return
        }
        Thread {
            val text = try {
                TranscribeClient.transcribe(this, file)
            } catch (e: Exception) {
                runOnUiThread {
                    status?.text = "识别失败:${e.message?.take(60)}"
                }
                null
            }
            runOnUiThread {
                if (!text.isNullOrBlank()) startRun(text) else toggle?.isEnabled = true
            }
        }.start()
    }

    private fun startRun(text: String) {
        if (intent?.getBooleanExtra("fill", false) == true) {
            AgentVoiceHeard.set(text.trim())
            Toast.makeText(this, "🎤 已填入:$text", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "▶ $text", Toast.LENGTH_LONG).show()
            AgentService.start(this, text.trim())
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopFlag = true
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun runOnUiThread(body: () -> Unit) = main.post(body)
}
