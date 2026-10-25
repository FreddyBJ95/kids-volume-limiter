package dk.kidsvolume.limiter.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dk.kidsvolume.limiter.settings.SettingsRepository
import java.util.concurrent.TimeUnit

object ServiceController {
    private const val TAG = "ServiceController"
    private const val WATCHDOG_WORK = "volume-watchdog"

    /**
     * Starter forgrundstjenesten. Android 12+ kan afvise start fra baggrunden
     * (ForegroundServiceStartNotAllowedException); så returneres false i stedet for at crashe.
     */
    fun start(context: Context, reason: String): Boolean {
        val intent = Intent(context, VolumeLimiterService::class.java)
            .putExtra(VolumeLimiterService.EXTRA_REASON, reason)
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Android tillod ikke at starte overvågningen ($reason)", e)
            false
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, VolumeLimiterService::class.java))
    }

    /** Starter eller stopper overvågning og vagthund, så de svarer til den gemte indstilling. */
    fun applyEnabledState(context: Context, reason: String): Boolean {
        return if (SettingsRepository.get(context).current.enabled) {
            scheduleWatchdog(context)
            start(context, reason)
        } else {
            cancelWatchdog(context)
            stop(context)
            true
        }
    }

    /**
     * Batterivenlig vagthund: WorkManager kører højst hvert 15. minut (Android samler kørslerne)
     * og genstarter overvågningen, hvis Android har stoppet den.
     */
    fun scheduleWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WATCHDOG_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelWatchdog(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_WORK)
    }
}
