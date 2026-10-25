package dk.kidsvolume.limiter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dk.kidsvolume.limiter.settings.SettingsRepository

/**
 * Genstarter overvågningen efter genstart af tabletten og efter opdatering af appen.
 * BOOT_COMPLETED og MY_PACKAGE_REPLACED er blandt de få situationer, hvor Android 12+ tillader,
 * at en forgrundstjeneste startes fra baggrunden.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "genstart af tablet"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app opdateret"
            else -> return
        }
        val settings = SettingsRepository.get(context)
        if (!settings.current.enabled) return
        val started = ServiceController.start(context, reason)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) settings.recordBoot(started)
        ServiceController.scheduleWatchdog(context)
    }
}
