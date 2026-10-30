package dev.ayoub.wristle.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.ayoub.wristle.MainActivity
import dev.ayoub.wristle.R
import dev.ayoub.wristle.WristleApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChatRequestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startInForeground()
        val session = (application as WristleApplication).container.chatRepository.session
        watcher?.cancel()
        watcher =
            scope.launch {
                session.first { !it.busy }
                ServiceCompat.stopForeground(this@ChatRequestService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.notification_channel_requests))
                .build(),
        )
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val title = getString(R.string.notification_request_in_progress)
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentIntent(openApp)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setSilent(true)
        OngoingActivity
            .Builder(this, NOTIFICATION_ID, notification)
            .setStaticIcon(R.drawable.ic_notification)
            .setTouchIntent(openApp)
            .setStatus(Status.Builder().addTemplate(title).build())
            .build()
            .apply(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        private const val CHANNEL_ID = "requests"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ChatRequestService::class.java))
            } catch (e: IllegalStateException) {
                return
            }
        }
    }
}
