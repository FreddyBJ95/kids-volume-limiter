package dk.kidsvolume.limiter.volume

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dk.kidsvolume.limiter.settings.CorrectionEntry
import dk.kidsvolume.limiter.settings.LimiterSettings
import dk.kidsvolume.limiter.settings.SettingsRepository

/**
 * Kernen i appen: læser medielydstyrken (STREAM_MUSIC) og sænker den til grænsen for den aktuelle
 * lydudgang (højttaler, hovedtelefoner eller Bluetooth), hvis den er for høj. Kaldes kun, når der er sket noget (en hændelse),
 * aldrig i en polling-løkke. Skal kaldes på main-tråden.
 */
class VolumeLimiter private constructor(context: Context) {

    enum class Result { DISABLED, FIXED_VOLUME, ROUTE_NOT_LIMITED, WITHIN_LIMIT, CORRECTED, FAILED }

    data class Snapshot(
        val currentIndex: Int,
        val minIndex: Int,
        val maxIndex: Int,
        val limitIndex: Int,
        val route: OutputRoute,
        val routeLimited: Boolean,
        val volumeFixed: Boolean,
    ) {
        val currentPercent: Int get() = percentOf(currentIndex, maxIndex)
        val effectiveLimitPercent: Int get() = percentOf(limitIndex, maxIndex)
    }

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val settings = SettingsRepository.get(context)
    private var lastPublished: Snapshot? = null

    /**
     * Aflæser lydstyrke og lydudgang. Android har en separat medielydstyrke for hver udgang, så
     * [Snapshot.limitIndex] er grænsen for den udgang, lyden går til lige nu.
     */
    fun snapshot(s: LimiterSettings = settings.current): Snapshot {
        val max = audio.getStreamMaxVolume(STREAM)
        val min = minIndex()
        val route = AudioRoutes.current(audio)
        return Snapshot(
            currentIndex = audio.getStreamVolume(STREAM),
            minIndex = min,
            maxIndex = max,
            limitIndex = limitIndexFor(s.percentFor(route), min, max),
            route = route,
            routeLimited = s.isLimited(route),
            volumeFixed = audio.isVolumeFixed,
        )
    }

    /** Grænsetrin for en given procent på denne enhed. */
    fun limitIndexFor(percent: Int): Int =
        limitIndexFor(percent, minIndex(), audio.getStreamMaxVolume(STREAM))

    /** Den procent, en grænse faktisk svarer til efter afrunding til enhedens trin. */
    fun effectivePercentFor(percent: Int): Int =
        percentOf(limitIndexFor(percent), audio.getStreamMaxVolume(STREAM))

    fun enforce(trigger: String, eventUptime: Long = SystemClock.uptimeMillis()): Result {
        val s = settings.current
        val snap = snapshot(s)
        val result = when {
            !s.enabled -> Result.DISABLED
            snap.volumeFixed -> Result.FIXED_VOLUME
            !snap.routeLimited -> Result.ROUTE_NOT_LIMITED
            snap.currentIndex <= snap.limitIndex -> Result.WITHIN_LIMIT
            else -> correct(snap, trigger, eventUptime)
        }
        publishIfChanged()
        return result
    }

    /** Sætter lydstyrken direkte (bruges af selvtesten). Returnerer den aflæste værdi bagefter. */
    fun setVolumeIndex(index: Int): Int {
        try {
            audio.setStreamVolume(STREAM, index, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "setStreamVolume blev afvist", e)
        }
        return audio.getStreamVolume(STREAM)
    }

    fun publishIfChanged() {
        val snap = snapshot()
        if (snap != lastPublished) {
            lastPublished = snap
            LimiterEvents.emit(LimiterEvents.Event.StateChanged)
        }
    }

    private fun correct(snap: Snapshot, trigger: String, eventUptime: Long): Result {
        try {
            audio.setStreamVolume(STREAM, snap.limitIndex, 0)
        } catch (e: SecurityException) {
            // Kan ske i "Forstyr ikke"-tilstand på nogle enheder.
            Log.w(TAG, "Kunne ikke sænke lydstyrken", e)
            return Result.FAILED
        }
        val after = audio.getStreamVolume(STREAM)
        if (after > snap.limitIndex) {
            Log.w(TAG, "Lydstyrken er stadig $after efter forsøg på at sætte ${snap.limitIndex}")
            return Result.FAILED
        }
        val entry = CorrectionEntry(
            at = System.currentTimeMillis(),
            from = snap.currentIndex,
            to = after,
            trigger = trigger,
            handlingMs = SystemClock.uptimeMillis() - eventUptime,
            route = snap.route.id,
        )
        Log.i(TAG, "Sænkede lydstyrken ${entry.from} -> ${entry.to} (${entry.trigger}, ${entry.handlingMs} ms)")
        settings.recordCorrection(entry)
        LimiterEvents.emit(LimiterEvents.Event.Corrected(entry))
        return Result.CORRECTED
    }

    private fun minIndex(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(STREAM) else 0

    companion object {
        private const val TAG = "VolumeLimiter"
        const val STREAM = AudioManager.STREAM_MUSIC

        @Volatile
        private var instance: VolumeLimiter? = null

        fun get(context: Context): VolumeLimiter =
            instance ?: synchronized(this) {
                instance ?: VolumeLimiter(context.applicationContext).also { instance = it }
            }

        /**
         * Grænsen beregnes ud fra enhedens egne lydstyrketrin og rundes NED, så den valgte procent
         * aldrig overskrides. Den holdes på mindst ét trin, så lyden aldrig slukkes helt.
         */
        fun limitIndexFor(percent: Int, min: Int, max: Int): Int {
            if (max <= 0) return 0
            val floor = max * percent.coerceIn(0, 100) / 100
            return maxOf(floor, min, 1).coerceAtMost(max)
        }

        fun percentOf(index: Int, max: Int): Int =
            if (max <= 0) 0 else Math.round(index * 100f / max)
    }
}
