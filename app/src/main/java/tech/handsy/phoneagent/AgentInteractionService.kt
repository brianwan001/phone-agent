package tech.handsy.phoneagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Proper digital-assistant implementation so HyperOS's gesture slots (long-press
 * power / nav bar) can hand over to us instead of XiaoAI: the session does nothing
 * fancy — it opens the app immediately.
 */
class AgentInteractionService : VoiceInteractionService()

class AgentSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AgentSession(this)
}

class AgentSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}

/** Persistent low-key notification: one tap into the app from anywhere. */
object QuickOpenNotification {
    private const val CHANNEL = "quick_open"
    private const val ID = 43

    fun show(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "快速打开", NotificationManager.IMPORTANCE_MIN),
        )
        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            ID,
            android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Phone Agent")
                .setContentText("点击打开 · 随时下任务")
                .setContentIntent(pi)
                .setOngoing(true)
                .build(),
        )
    }
}
