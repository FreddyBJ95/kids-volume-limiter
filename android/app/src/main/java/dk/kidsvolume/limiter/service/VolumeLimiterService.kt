package dk.kidsvolume.limiter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dk.kidsvolume.limiter.MainActivity
import dk.kidsvolume.limiter.R
import dk.kidsvolume.limiter.settings.SettingsRepository
import dk.kidsvolume.limiter.volume.LimiterEvents
import dk.kidsvolume.limiter.volume.OutputRoute
import dk.kidsvolume.limiter.volume.VolumeLimiter
import dk.kidsvolume.limiter.volume.VolumeWatcher

/**
 * Forgrundstjeneste (foreground service, type "specialUse"), som holder lydstyrkeovervågningen
 * kørende, når brugerfladen er lukket. Den bruger ingen timere; den vågner kun ved hændelser.
 */
class VolumeLimiterService : Service() {

    private lateinit var limiter: VolumeLimiter
    private lateinit var settings: SettingsRepository
    private var watcher: VolumeWatcher? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository.get(this)
        limiter = VolumeLimiter.get(this)
        isRunning = true
        startInForeground()
        watcher = VolumeWatcher(this) { trigger, at -> limiter.enforce(trigger, at) }.also { it.start() }
        LimiterEvents.emit(LimiterEvents.Event.StateChanged)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android kræver startForeground() efter hver startForegroundService().
        startInForeground()
        if (!settings.current.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        val reason = intent?.getStringExtra(EXTRA_REASON) ?: "genstartet af Android"
        settings.recordServiceStart(reason)
        Log.i(TAG, "Overvågning startet ($reason)")
        // Ekstra kontrol hver gang overvågningen (gen)aktiveres.
        limiter.enforce("overvågning startet")
        return START_STICKY
    }

    override fun onDestroy() {
        watcher?.stop()
        watcher = null
        isRunning = false
        LimiterEvents.emit(LimiterEvents.Event.StateChanged)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this), type)
    }

    companion object {
        private const val TAG = "VolumeLimiterService"
        private const val CHANNEL_ID = "protection"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_REASON = "reason"

        @Volatile
        var isRunning = false
            private set

        /** Opdaterer notifikationsteksten, fx efter at grænsen er ændret. */
        fun refreshNotification(context: Context) {
            if (!isRunning) return
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(context))
        }

        private fun buildNotification(context: Context): Notification {
            ensureChannel(context)
            val text = limitsSummary(context)
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_volume_shield)
                .setContentTitle(context.getString(R.string.notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        /** Fx "Højttalere 40 % · Hovedtelefoner 33 % · Bluetooth ubegrænset". */
        private fun limitsSummary(context: Context): String {
            val s = SettingsRepository.get(context).current
            val limiter = VolumeLimiter.get(context)
            return listOf(
                OutputRoute.SPEAKER to R.string.route_speaker,
                OutputRoute.HEADPHONES to R.string.route_headphones,
                OutputRoute.BLUETOOTH to R.string.route_bluetooth,
            ).joinToString(" · ") { (route, label) ->
                val percent = s.limitPercentFor(route)
                if (percent == null) {
                    context.getString(R.string.notification_route_unlimited, context.getString(label))
                } else {
                    context.getString(R.string.notification_route_limit, context.getString(label), limiter.effectivePercentFor(percent))
                }
            }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notification_channel_description)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
