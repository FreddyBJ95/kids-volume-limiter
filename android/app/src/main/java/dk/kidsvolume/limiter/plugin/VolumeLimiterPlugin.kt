package dk.kidsvolume.limiter.plugin

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.core.app.NotificationManagerCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import dk.kidsvolume.limiter.security.PinManager
import dk.kidsvolume.limiter.service.ServiceController
import dk.kidsvolume.limiter.service.VolumeLimiterService
import dk.kidsvolume.limiter.settings.LimiterSettings
import dk.kidsvolume.limiter.settings.SettingsRepository
import dk.kidsvolume.limiter.volume.LimiterEvents
import dk.kidsvolume.limiter.volume.OutputRoute
import dk.kidsvolume.limiter.volume.VolumeLimiter
import dk.kidsvolume.limiter.volume.VolumeWatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bro mellem den dansksprogede web-brugerflade og den native lydstyrkebegrænsning.
 * Alle ændringer af indstillinger kræver, at PIN-koden er indtastet (se [PinManager]);
 * kun det at slå beskyttelsen TIL kan ske uden PIN.
 */
@CapacitorPlugin(
    name = "VolumeLimiter",
    permissions = [Permission(alias = "notifications", strings = [Manifest.permission.POST_NOTIFICATIONS])],
)
class VolumeLimiterPlugin : Plugin() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: SettingsRepository
    private lateinit var limiter: VolumeLimiter
    private lateinit var pin: PinManager
    private var watcher: VolumeWatcher? = null

    /** Sat, når vi selv åbner en systemskærm, så PIN-oplåsningen ikke nulstilles imens. */
    private var openedSystemScreen = false

    private val eventListener = LimiterEvents.Listener {
        notifyListeners("statusChanged", statusJson())
    }

    override fun load() {
        settings = SettingsRepository.get(context)
        limiter = VolumeLimiter.get(context)
        pin = PinManager.get(context)
        LimiterEvents.add(eventListener)
    }

    override fun handleOnResume() {
        openedSystemScreen = false
        // Mens appen er åben, lytter vi også selv, så forsiden viser lydstyrken live.
        if (watcher == null) {
            watcher = VolumeWatcher(context) { trigger, at -> limiter.enforce(trigger, at) }.also { it.start() }
        }
        if (settings.current.enabled && !VolumeLimiterService.isRunning) {
            ServiceController.start(context, "app åbnet")
        }
        limiter.enforce("app åbnet")
        notifyListeners("statusChanged", statusJson())
        notifyListeners("appResumed", JSObject())
    }

    override fun handleOnStop() {
        watcher?.stop()
        watcher = null
        if (!openedSystemScreen) pin.lock()
    }

    override fun handleOnDestroy() {
        LimiterEvents.remove(eventListener)
        watcher?.stop()
        watcher = null
        scope.cancel()
    }

    // region Status

    @PluginMethod
    fun getStatus(call: PluginCall) = call.resolve(statusJson())

    @PluginMethod
    fun getDeviceState(call: PluginCall) {
        val power = context.getSystemService(PowerManager::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        call.resolve(JSObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("notificationsGranted", NotificationManagerCompat.from(context).areNotificationsEnabled())
            put("ignoringBatteryOptimizations", power.isIgnoringBatteryOptimizations(context.packageName))
            put(
                "backgroundRestricted",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activityManager.isBackgroundRestricted,
            )
        })
    }

    @PluginMethod
    fun getDiagnostics(call: PluginCall) {
        val d = settings.diagnostics
        val recent = JSArray()
        d.recent.forEach { recent.put(JSObject.fromJSONObject(it.toJson())) }
        call.resolve(JSObject().apply {
            put("corrections", d.corrections)
            put("recent", recent)
            put("serviceStartedAt", d.serviceStartedAt)
            put("serviceStartReason", d.serviceStartReason)
            put("lastBootAt", d.lastBootAt)
            put("lastBootStartOk", d.lastBootStartOk)
            put("lastWatchdogAt", d.lastWatchdogAt)
        })
    }

    // endregion

    // region PIN

    @PluginMethod
    fun createPin(call: PluginCall) = launch(call) {
        pin.create(call.getString("pin").orEmpty())
        statusJson()
    }

    @PluginMethod
    fun verifyPin(call: PluginCall) = launch(call) {
        val result = pin.verify(call.getString("pin").orEmpty())
        notifyListeners("statusChanged", statusJson())
        JSObject().apply {
            put("ok", result.ok)
            put("attemptsLeft", result.attemptsLeft)
            put("lockoutSeconds", result.lockoutSeconds)
        }
    }

    @PluginMethod
    fun changePin(call: PluginCall) = launch(call) {
        pin.change(call.getString("pin").orEmpty())
        null
    }

    @PluginMethod
    fun lock(call: PluginCall) {
        pin.lock()
        call.resolve(statusJson())
    }

    // endregion

    // region Indstillinger

    /** Sætter grænsen for en lydudgang ("speaker", "headphones" eller "bluetooth"). */
    @PluginMethod
    fun setLimit(call: PluginCall) = launch(call) {
        val route = limitRoute(call.getString("route"))
        val percent = call.getInt("percent")
            ?.takeIf { it in LimiterSettings.MIN_LIMIT_PERCENT..LimiterSettings.MAX_LIMIT_PERCENT }
            ?: throw PinManager.PinException("INVALID_VALUE", "Grænsen skal være mellem 10 og 100 %")
        pin.requireUnlocked()
        settings.setLimitPercent(route, percent)
        pin.touch()
        VolumeLimiterService.refreshNotification(context)
        limiter.enforce("grænse ændret")
        statusJson()
    }

    @PluginMethod
    fun setEnabled(call: PluginCall) = launch(call) {
        val enabled = call.getBoolean("enabled") ?: throw PinManager.PinException("INVALID_VALUE", "Mangler værdi")
        // At slå beskyttelsen til kræver ikke PIN – kun at slå den fra.
        if (!enabled) pin.requireUnlocked()
        settings.setEnabled(enabled)
        pin.touch()
        ServiceController.applyEnabledState(context, if (enabled) "slået til i appen" else "slået fra i appen")
        limiter.enforce("beskyttelse slået til")
        statusJson()
    }

    /** Slår en separat grænse for hovedtelefoner eller Bluetooth til/fra. */
    @PluginMethod
    fun setRouteLimitEnabled(call: PluginCall) = launch(call) {
        val route = limitRoute(call.getString("route"))
        if (route == OutputRoute.SPEAKER) {
            throw PinManager.PinException("INVALID_VALUE", "Højttalerne begrænses altid, når beskyttelsen er slået til")
        }
        val enabled = call.getBoolean("enabled") ?: throw PinManager.PinException("INVALID_VALUE", "Mangler værdi")
        pin.requireUnlocked()
        settings.setRouteLimitEnabled(route, enabled)
        pin.touch()
        VolumeLimiterService.refreshNotification(context)
        limiter.enforce("grænse ændret")
        statusJson()
    }

    @PluginMethod
    fun completeOnboarding(call: PluginCall) = launch(call) {
        if (!settings.current.pinSet) throw PinManager.PinException("NO_PIN", "Opret en PIN-kode først")
        settings.setOnboardingDone()
        statusJson()
    }

    // endregion

    // region Tilladelser og systemindstillinger

    @PluginMethod
    fun requestNotificationPermission(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            getPermissionState("notifications") == PermissionState.GRANTED
        ) {
            call.resolve(JSObject().put("granted", NotificationManagerCompat.from(context).areNotificationsEnabled()))
            return
        }
        openedSystemScreen = true
        requestPermissionForAlias("notifications", call, "notificationsCallback")
    }

    @PermissionCallback
    private fun notificationsCallback(call: PluginCall) {
        VolumeLimiterService.refreshNotification(context)
        call.resolve(JSObject().put("granted", getPermissionState("notifications") == PermissionState.GRANTED))
    }

    @SuppressLint("BatteryLife")
    @PluginMethod
    fun requestIgnoreBatteryOptimizations(call: PluginCall) {
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(context.packageName)) {
            call.resolve(JSObject().put("ignoring", true))
            return
        }
        openedSystemScreen = true
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        try {
            startActivityForResult(call, direct, "batteryResult")
        } catch (_: ActivityNotFoundException) {
            try {
                startActivityForResult(call, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), "batteryResult")
            } catch (_: ActivityNotFoundException) {
                openedSystemScreen = false
                call.resolve(JSObject().put("ignoring", false))
            }
        }
    }

    @ActivityCallback
    private fun batteryResult(call: PluginCall?, @Suppress("UNUSED_PARAMETER") result: ActivityResult) {
        val power = context.getSystemService(PowerManager::class.java)
        call?.resolve(JSObject().put("ignoring", power.isIgnoringBatteryOptimizations(context.packageName)))
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        openedSystemScreen = true
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        try {
            activity.startActivity(intent)
            call.resolve()
        } catch (e: ActivityNotFoundException) {
            openedSystemScreen = false
            call.reject("Kunne ikke åbne app-indstillinger", "ERROR", e)
        }
    }

    // endregion

    // region Selvtest

    @PluginMethod
    fun runSelfTest(call: PluginCall) = launch(call) { selfTest() }

    private suspend fun selfTest(): JSObject {
        val steps = JSArray()
        var allOk = true
        var latencyMs: Long? = null
        fun step(id: String, label: String, status: String, detail: String) {
            if (status == "fail") allOk = false
            steps.put(JSObject().apply {
                put("id", id); put("label", label); put("status", status); put("detail", detail)
            })
        }

        val s = settings.current
        val snap = limiter.snapshot(s)

        val running = VolumeLimiterService.isRunning
        when {
            !s.enabled -> step("service", "Overvågning i baggrunden", "fail", "Beskyttelsen er slået fra.")
            running -> step("service", "Overvågning i baggrunden", "ok", "Forgrundstjenesten kører.")
            else -> step("service", "Overvågning i baggrunden", "fail", "Tjenesten kører ikke. Android kan have stoppet den.")
        }

        step(
            "route", "Lydudgang", "ok",
            "${routeLabel(snap.route)} – ${if (snap.routeLimited) "begrænses" else "begrænses ikke"}.",
        )

        if (snap.volumeFixed) {
            step("fixed", "Lydstyrken kan styres", "fail", "Enheden bruger fast lydstyrke på denne udgang.")
            return selfTestResult(false, steps, null)
        }

        val original = snap.currentIndex
        val probe = if (original > maxOf(snap.minIndex, 1)) original - 1 else (original + 1).coerceAtMost(snap.maxIndex)
        val readBack = limiter.setVolumeIndex(probe)
        limiter.setVolumeIndex(original)
        val canControl = readBack == probe
        step(
            "control", "Appen kan ændre lydstyrken", if (canControl) "ok" else "fail",
            if (canControl) "Lydstyrken blev ændret og sat tilbage (trin $original af ${snap.maxIndex})."
            else "Android afviste ændringen (fx 'Forstyr ikke' eller en producentbegrænsning).",
        )

        when {
            !s.enabled || !canControl ->
                step("auto", "Automatisk sænkning", "skip", "Kan ikke testes, før ovenstående virker.")
            !snap.routeLimited ->
                step("auto", "Automatisk sænkning", "skip", "Springes over: ${routeLabel(snap.route).lowercase()} begrænses ikke.")
            snap.limitIndex >= snap.maxIndex ->
                step("auto", "Automatisk sænkning", "skip", "Grænsen er 100 %, så der er intet at sænke.")
            else -> {
                val corrected = CompletableDeferred<Pair<Long, String>>()
                val listener = LimiterEvents.Listener { e ->
                    if (e is LimiterEvents.Event.Corrected) corrected.complete(SystemClock.uptimeMillis() to e.entry.trigger)
                }
                LimiterEvents.add(listener)
                val start = SystemClock.uptimeMillis()
                limiter.setVolumeIndex(snap.limitIndex + 1)
                val result = withTimeoutOrNull(AUTO_TEST_TIMEOUT_MS) { corrected.await() }
                LimiterEvents.remove(listener)
                if (result != null) {
                    latencyMs = result.first - start
                    step(
                        "auto", "Automatisk sænkning", "ok",
                        "Lydstyrken blev sænket efter $latencyMs ms (udløst af: ${result.second}).",
                    )
                } else {
                    step(
                        "auto", "Automatisk sænkning", "fail",
                        "Ingen automatisk sænkning inden for ${AUTO_TEST_TIMEOUT_MS / 1000} sekunder.",
                    )
                    limiter.setVolumeIndex(snap.limitIndex)
                }
                limiter.setVolumeIndex(minOf(original, snap.limitIndex))
            }
        }
        limiter.publishIfChanged()
        return selfTestResult(allOk, steps, latencyMs)
    }

    private fun selfTestResult(ok: Boolean, steps: JSArray, latencyMs: Long?) = JSObject().apply {
        put("ok", ok)
        put("steps", steps)
        put("latencyMs", latencyMs ?: JSObject.NULL)
    }

    // endregion

    private fun statusJson(): JSObject {
        val s = settings.current
        val snap = limiter.snapshot(s)
        return JSObject().apply {
            put("enabled", s.enabled)
            put("serviceRunning", VolumeLimiterService.isRunning)
            put("currentIndex", snap.currentIndex)
            put("minIndex", snap.minIndex)
            put("maxIndex", snap.maxIndex)
            put("currentPercent", snap.currentPercent)
            put("limitIndex", snap.limitIndex)
            put("effectiveLimitPercent", snap.effectiveLimitPercent)
            put("route", snap.route.id)
            put("routeLimited", snap.routeLimited)
            // Den udgang, hvis grænse forsiden skal vise (headset/Bluetooth, når de er tilsluttet).
            put("limitRoute", (if (snap.route in LIMIT_ROUTES) snap.route else OutputRoute.SPEAKER).id)
            put("limits", JSObject().apply {
                for (route in LIMIT_ROUTES) {
                    val percent = s.percentFor(route)
                    val index = VolumeLimiter.limitIndexFor(percent, snap.minIndex, snap.maxIndex)
                    put(route.id, JSObject().apply {
                        put("enabled", s.isLimited(route))
                        put("percent", percent)
                        put("index", index)
                        put("effectivePercent", VolumeLimiter.percentOf(index, snap.maxIndex))
                    })
                }
            })
            put("volumeFixed", snap.volumeFixed)
            put("pinSet", s.pinSet)
            put("onboardingDone", s.onboardingDone)
            put("unlocked", pin.isUnlocked())
            put("unlockSecondsLeft", pin.unlockSecondsLeft())
        }
    }

    private fun launch(call: PluginCall, block: suspend () -> JSObject?) {
        scope.launch {
            try {
                val result = block()
                if (result == null) call.resolve() else call.resolve(result)
            } catch (e: PinManager.PinException) {
                call.reject(e.message, e.code)
            } catch (e: Exception) {
                call.reject(e.message ?: "Ukendt fejl", "ERROR", e)
            }
        }
    }

    private fun routeLabel(route: OutputRoute) = when (route) {
        OutputRoute.SPEAKER -> "Indbyggede højttalere"
        OutputRoute.HEADPHONES -> "Hovedtelefoner"
        OutputRoute.BLUETOOTH -> "Bluetooth"
        OutputRoute.OTHER -> "Anden lydudgang"
    }

    private fun limitRoute(id: String?): OutputRoute = when (id) {
        null, OutputRoute.SPEAKER.id -> OutputRoute.SPEAKER
        OutputRoute.HEADPHONES.id -> OutputRoute.HEADPHONES
        OutputRoute.BLUETOOTH.id -> OutputRoute.BLUETOOTH
        else -> throw PinManager.PinException("INVALID_VALUE", "Ukendt lydudgang: $id")
    }

    companion object {
        private const val AUTO_TEST_TIMEOUT_MS = 3_000L
        private val LIMIT_ROUTES = listOf(OutputRoute.SPEAKER, OutputRoute.HEADPHONES, OutputRoute.BLUETOOTH)
    }
}
