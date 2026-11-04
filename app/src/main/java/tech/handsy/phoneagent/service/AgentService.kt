package tech.handsy.phoneagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tech.handsy.phoneagent.R
import tech.handsy.phoneagent.agent.AgentBus
import tech.handsy.phoneagent.agent.AgentEngine
import tech.handsy.phoneagent.agent.AgentStatus
import tech.handsy.phoneagent.store.RunRecord
import tech.handsy.phoneagent.store.RunStore
import tech.handsy.phoneagent.store.SettingsStore
import kotlin.concurrent.thread

/**
 * Runs the agent in the foreground and keeps a status notification
 * (the Android counterpart of the iOS Dynamic Island) with a stop button.
 */
class AgentService : Service() {

    private lateinit var store: RunStore
    private lateinit var settings: SettingsStore
    private var engine: AgentEngine? = null
    private var worker: Thread? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        store = RunStore(this)
        settings = SettingsStore(this)
        createChannel()
        scope.launch {
            AgentBus.status.collect { updateNotification(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                engine?.cancelled = true
                if (worker == null) stopSelf()
            }
            ACTION_RESUME -> {
                if (!isBusy()) {
                    store.listRuns().firstOrNull { it.status == "paused" || it.status == "running" }
                        ?.let { record ->
                            goForeground(record.goal, "Resuming…")
                            startEngine(record)
                        } ?: stopSelf()
                }
            }
            else -> {
                val goal = intent?.getStringExtra(EXTRA_GOAL).orEmpty().trim()
                if (goal.isNotEmpty() && !isBusy()) {
                    goForeground(goal, "Starting…")
                    startEngine(store.newRun(goal, settings.actorModelOrDefault))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isBusy(): Boolean = worker != null

    private fun startEngine(record: RunRecord) {
        active = true
        val wakeLock = (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "phoneagent:run")
            .apply { acquire() } // 长时任务:不限时唤醒,任务结束统一释放
        val newEngine = AgentEngine(this, store, record, settings, settings.maxSteps)
        engine = newEngine
        worker = thread(start = true) {
            startSilentAudio()
            try {
                newEngine.execute()
            } catch (t: Throwable) {
                // Last-resort guard: never leave the run stuck in "Running".
                AgentBus.update(AgentStatus.Paused(record.id, record.goal, record.steps.size, t.message ?: t.javaClass.simpleName))
            } finally {
                active = false
                engine = null
                worker = null
                try {
                    if (wakeLock.isHeld) wakeLock.release()
                } catch (_: Exception) {
                }
                stopSilentAudio()
                if (AgentBus.status.value is AgentStatus.Running) {
                    // The engine died without reporting (crash/kill): make the run resumable.
                    AgentBus.update(AgentStatus.Paused(record.id, record.goal, record.steps.size, "Run interrupted; tap resume"))
                }
                if (AgentBus.status.value is AgentStatus.Paused) {
                    // Keep a tappable notification around so the run can be resumed
                    // without reopening the app.
                    ServiceCompat.stopForeground(this@AgentService, ServiceCompat.STOP_FOREGROUND_DETACH)
                    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, pausedNotification())
                } else {
                    ServiceCompat.stopForeground(this@AgentService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun goForeground(title: String, text: String) {
        val notification = buildNotification(title, text)
        // Some FGS types carry permission prerequisites that vary by OEM; never crash on them.
        if (Build.VERSION.SDK_INT >= 34) {
            val attempts = listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            for (type in attempts) {
                try {
                    startForeground(NOTIFICATION_ID, notification, type)
                    return
                } catch (e: SecurityException) {
                    android.util.Log.w("AgentService", "FGS type $type rejected: ${e.message}")
                }
            }
        } else {
            try {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } catch (e: SecurityException) {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    /**
     * MIUI/HyperOS freezes "background" apps even with a foreground service and doze
     * whitelisting. An app that is actively playing audio is treated as user-perceptible
     * and never frozen — so while a run is active we play inaudible silence on a loop.
     */
    private var silentTrack: android.media.AudioTrack? = null

    private fun startSilentAudio() {
        if (silentTrack != null) return
        try {
            val rate = 22050
            val track = android.media.AudioTrack(
                android.media.AudioAttributes.Builder()
                    // ALARM usage is exempt from MIUI's background-playback muting, and a
                    // near-zero (inaudible ~-90dB) waveform defeats its "is silent" heuristic
                    // that muted our pure-zero keep-alive track.
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                android.media.AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                rate * 2,
                android.media.AudioTrack.MODE_STATIC,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            val pcm = ShortArray(rate) { i -> (4 * if (i % 2 == 0) 1 else -1).toShort() }
            track.write(pcm, 0, rate)
            track.setLoopPoints(0, rate, -1)
            track.setVolume(0.01f)
            track.play()
            silentTrack = track
            android.util.Log.d("AgentService", "keep-alive audio started (alarm, inaudible)")
        } catch (e: Exception) {
            android.util.Log.w("AgentService", "silent audio failed: ${e.message}")
        }
    }

    private fun stopSilentAudio() {
        try {
            silentTrack?.let {
                it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }
        silentTrack = null
    }

    private fun updateNotification(status: AgentStatus) {
        if (worker == null) return
        val (title, text) = when (status) {
            is AgentStatus.Running -> {
                val last = status.lastDo.firstOrNull()
                status.goal to buildString {
                    append("Step ").append(status.step + 1)
                    if (last != null) append(" · ").append(last.take(60))
                }
            }
            is AgentStatus.Paused -> status.goal to "Paused: ${status.reason.take(80)}"
            is AgentStatus.Finished -> status.goal to (if (status.ok) "Done: " else "Failed: ") + status.message.take(80)
            AgentStatus.Idle -> return
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = openIntent?.let {
            android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        val stopPi = android.app.PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.stop), stopPi)
            .build()
    }

    private fun pausedNotification(): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPi = openIntent?.let {
            android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        val resumePi = android.app.PendingIntent.getService(
            this,
            2,
            Intent(this, AgentService::class.java).setAction(ACTION_RESUME),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val status = AgentBus.status.value
        val reason = (status as? AgentStatus.Paused)?.reason.orEmpty()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.paused_title))
            .setContentText(reason.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(contentPi)
            .setOngoing(false)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.resume), resumePi)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_RUN = "tech.handsy.phoneagent.RUN"
        const val ACTION_RESUME = "tech.handsy.phoneagent.RESUME"
        const val ACTION_STOP = "tech.handsy.phoneagent.STOP"
        const val EXTRA_GOAL = "goal"
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "agent_status"

        /** True while a run is in progress (the app also checks [AgentBus]). */
        @Volatile
        var active: Boolean = false
            private set

        fun start(context: Context, goal: String) {
            val intent = Intent(context, AgentService::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_GOAL, goal)
            ContextCompat.startForegroundService(context, intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, AgentService::class.java).setAction(ACTION_RESUME)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentService::class.java).setAction(ACTION_STOP)
            // Plain startService: a stop when nothing runs must not require startForeground.
            context.startService(intent)
        }
    }
}
