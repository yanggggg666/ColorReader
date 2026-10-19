package com.oceanreader.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.oceanreader.MainActivity
import com.oceanreader.R

/**
 * Keeps read-aloud alive while the reader screen is backgrounded, and exposes
 * the transport in the notification shade and on the lock screen.
 */
class SpeechService : Service() {

    private var notificationManager: NotificationManager? = null
    private var controller: SpeechController? = null
    private var isForeground = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        ServiceBridge.sync = { c -> syncNotification(c) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = controller ?: SpeechServiceHolder.controller
        when (intent?.action) {
            ACTION_PLAY -> c?.resume()
            ACTION_PAUSE -> c?.pause()
            ACTION_NEXT -> c?.next()
            ACTION_PREV -> c?.previous()
            ACTION_STOP -> {
                c?.stop()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ensureForeground(c)
        syncNotification(c)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopForegroundCompat()
        super.onDestroy()
    }

    fun attach(controller: SpeechController) {
        this.controller = controller
    }

    fun syncNotification(c: SpeechController?) {
        val target = c ?: controller ?: return
        val state = target.state.value
        notificationManager?.notify(NOTIFICATION_ID, build(state, target))
        if (!isForeground) ensureForeground(target)
    }

    private fun ensureForeground(c: SpeechController?) {
        if (isForeground) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0
        val notification = build(c?.state?.value ?: SpeechState(), c)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        isForeground = true
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
    }

    private fun build(state: SpeechState, controller: SpeechController?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_speech)
            .setContentTitle(state.chapterTitle())
            .setContentText(state.subtitle(controller))
            .setSubText(state.bookId)
            .setContentIntent(open)
            .setOngoing(state.playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val total = state.segmentCount
        if (total > 0) {
            val done = (state.segmentIndex + 1).coerceAtMost(total)
            builder.setProgress(total, done, false)
            builder.addAction(
                R.drawable.ic_stat_skip_previous,
                "上一句",
                serviceIntent(ACTION_PREV, 1)
            )
            if (state.playing) {
                builder.addAction(
                    R.drawable.ic_stat_pause,
                    "暂停",
                    serviceIntent(ACTION_PAUSE, 2)
                )
            } else {
                builder.addAction(
                    R.drawable.ic_stat_play,
                    "继续",
                    serviceIntent(ACTION_PLAY, 2)
                )
            }
            builder.addAction(
                R.drawable.ic_stat_skip_next,
                "下一句",
                serviceIntent(ACTION_NEXT, 3)
            )
            builder.setStyle(MediaStyle().setShowActionsInCompactView(0, 1, 2))
        }
        return builder.build()
    }

    private fun serviceIntent(action: String, request: Int): PendingIntent =
        PendingIntent.getService(
            this, request,
            Intent(this, SpeechService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tts_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.tts_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "ocean_speech"
        private const val NOTIFICATION_ID = 0x5EEC4
        const val ACTION_PLAY = "com.oceanreader.speech.PLAY"
        const val ACTION_PAUSE = "com.oceanreader.speech.PAUSE"
        const val ACTION_NEXT = "com.oceanreader.speech.NEXT"
        const val ACTION_PREV = "com.oceanreader.speech.PREV"
        const val ACTION_STOP = "com.oceanreader.speech.STOP"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, SpeechService::class.java).setAction(ACTION_PLAY)
            )
        }
    }
}

private fun SpeechState.chapterTitle(): String = bookId.ifBlank { "朗读" }

private fun SpeechState.subtitle(controller: SpeechController?): String {
    val total = segmentCount
    if (total <= 0) return "准备中"
    val position = (segmentIndex + 1).coerceIn(0, total)
    val percent = if (total > 0) position * 100 / total else 0
    return "$percent% · 第 $position/$total 句"
}

/** Global handle so the service can reach the controller it was started for. */
object SpeechServiceHolder {
    @Volatile
    var controller: SpeechController? = null
    @Volatile
    var service: SpeechService? = null
}