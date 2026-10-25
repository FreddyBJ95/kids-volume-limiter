package dk.kidsvolume.limiter.volume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Lytter efter hændelser, der kan betyde, at medielydstyrken eller lydudgangen er ændret, og
 * beder om en kontrol. Der bruges udelukkende offentlige Android-API'er og ingen polling:
 *
 *  - MediaRouter.Callback.onRouteVolumeChanged: kaldes straks, når medielydstyrken ændres
 *    (fx med de fysiske knapper).
 *  - ContentObserver på Settings.System: Android gemmer lydstyrken her (ca. 0,5 s forsinket).
 *    Det er et ekstra sikkerhedsnet, hvis MediaRouter-hændelsen udebliver på en enhed.
 *  - AudioDeviceCallback og høretelefon-broadcasts: hovedtelefoner/Bluetooth til- eller frakobles.
 *  - SCREEN_ON / USER_PRESENT: ekstra kontrol, når tabletten vækkes.
 *
 * Alle hændelser håndteres på main-tråden. Hændelser, der kommer samtidig, samles til én kontrol.
 */
class VolumeWatcher(
    context: Context,
    private val onCheck: (trigger: String, eventUptime: Long) -> Unit,
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val router = app.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    private var started = false

    private var immediateQueued = false
    private var queuedTrigger = ""
    private var queuedAt = 0L
    private val settleToken = Any()

    private val routerCallback = object : MediaRouter.SimpleCallback() {
        override fun onRouteVolumeChanged(router: MediaRouter, info: MediaRouter.RouteInfo) {
            request("knap/lydstyrke")
        }

        override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) {
            request("lydudgang")
            requestSettled("lydudgang")
        }
    }

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            request("systemindstilling")
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            request("enhed tilsluttet")
            requestSettled("enhed tilsluttet")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            request("enhed frakoblet")
            requestSettled("enhed frakoblet")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> request("skærm tændt")
                Intent.ACTION_USER_PRESENT -> request("skærm låst op")
                else -> {
                    request("hovedtelefoner")
                    requestSettled("hovedtelefoner")
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        router.addCallback(
            MediaRouter.ROUTE_TYPE_LIVE_AUDIO,
            routerCallback,
            MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS,
        )
        app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        if (!started) return
        started = false
        router.removeCallback(routerCallback)
        app.contentResolver.unregisterContentObserver(settingsObserver)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        try {
            app.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        handler.removeCallbacksAndMessages(null)
        immediateQueued = false
    }

    /** Planlægger én kontrol hurtigst muligt; flere hændelser i samme øjeblik giver kun én kontrol. */
    fun request(trigger: String) {
        if (immediateQueued) return
        immediateQueued = true
        queuedTrigger = trigger
        queuedAt = SystemClock.uptimeMillis()
        handler.post {
            immediateQueued = false
            onCheck(queuedTrigger, queuedAt)
        }
    }

    /** Ekstra kontrol, når Android har nået at skifte lydudgang (fx efter frakobling af hovedtelefoner). */
    private fun requestSettled(trigger: String) {
        handler.removeCallbacksAndMessages(settleToken)
        handler.postAtTime(
            { onCheck("$trigger (efterkontrol)", SystemClock.uptimeMillis()) },
            settleToken,
            SystemClock.uptimeMillis() + SETTLE_DELAY_MS,
        )
    }

    companion object {
        private const val SETTLE_DELAY_MS = 700L
    }
}
