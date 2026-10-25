package dk.kidsvolume.limiter.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dk.kidsvolume.limiter.volume.OutputRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kids_volume_limiter")

data class LimiterSettings(
    val enabled: Boolean = false,
    /** Grænse for de indbyggede højttalere (altid aktiv, når beskyttelsen er slået til). */
    val limitPercent: Int = DEFAULT_LIMIT_PERCENT,
    val headphonesLimitEnabled: Boolean = false,
    val headphonesLimitPercent: Int = DEFAULT_LIMIT_PERCENT,
    val bluetoothLimitEnabled: Boolean = false,
    val bluetoothLimitPercent: Int = DEFAULT_LIMIT_PERCENT,
    val onboardingDone: Boolean = false,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val pinIterations: Int = 0,
    val failedPinAttempts: Int = 0,
    val pinLockoutUntil: Long = 0L,
) {
    val pinSet: Boolean get() = pinHash != null && pinSalt != null

    /** Den gemte grænse for en udgang, også når den ikke er slået til. */
    fun percentFor(route: OutputRoute): Int = when (route) {
        OutputRoute.HEADPHONES -> headphonesLimitPercent
        OutputRoute.BLUETOOTH -> bluetoothLimitPercent
        else -> limitPercent
    }

    fun isLimited(route: OutputRoute): Boolean = when (route) {
        OutputRoute.SPEAKER -> true
        OutputRoute.HEADPHONES -> headphonesLimitEnabled
        OutputRoute.BLUETOOTH -> bluetoothLimitEnabled
        OutputRoute.OTHER -> false
    }

    /** Grænsen i procent for en udgang, eller null hvis udgangen ikke begrænses. */
    fun limitPercentFor(route: OutputRoute): Int? = if (isLimited(route)) percentFor(route) else null

    companion object {
        const val DEFAULT_LIMIT_PERCENT = 40
        const val MIN_LIMIT_PERCENT = 10
        const val MAX_LIMIT_PERCENT = 100
    }
}

data class CorrectionEntry(
    val at: Long,
    val from: Int,
    val to: Int,
    val trigger: String,
    val handlingMs: Long,
    val route: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("at", at).put("from", from).put("to", to)
        .put("trigger", trigger).put("handlingMs", handlingMs).put("route", route)

    companion object {
        fun fromJson(o: JSONObject) = CorrectionEntry(
            at = o.optLong("at"), from = o.optInt("from"), to = o.optInt("to"),
            trigger = o.optString("trigger"), handlingMs = o.optLong("handlingMs"), route = o.optString("route"),
        )
    }
}

data class Diagnostics(
    val corrections: Long = 0,
    val recent: List<CorrectionEntry> = emptyList(),
    val serviceStartedAt: Long = 0,
    val serviceStartReason: String = "",
    val lastBootAt: Long = 0,
    val lastBootStartOk: Boolean = false,
    val lastWatchdogAt: Long = 0,
)

/**
 * Lokal lagring i Android DataStore. Værdierne caches i hukommelsen, så lydstyrkekontrollen
 * aldrig skal vente på disk-I/O, når en lydstyrkeændring skal håndteres.
 */
class SettingsRepository private constructor(context: Context) {

    private val store = context.applicationContext.dataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings: MutableStateFlow<LimiterSettings>
    private val _diagnostics: MutableStateFlow<Diagnostics>

    init {
        // Lille fil; den første læsning er hurtig og gør resten af appen synkron og enkel.
        val initial = runBlocking { store.data.first() }
        _settings = MutableStateFlow(initial.toSettings())
        _diagnostics = MutableStateFlow(initial.toDiagnostics())
    }

    val settings: StateFlow<LimiterSettings> get() = _settings
    val current: LimiterSettings get() = _settings.value
    val diagnostics: Diagnostics get() = _diagnostics.value

    suspend fun setEnabled(enabled: Boolean) = edit { it[KEY_ENABLED] = enabled }

    suspend fun setLimitPercent(route: OutputRoute, percent: Int) = edit {
        val key = when (route) {
            OutputRoute.HEADPHONES -> KEY_HEADPHONES_LIMIT_PERCENT
            OutputRoute.BLUETOOTH -> KEY_BLUETOOTH_LIMIT_PERCENT
            else -> KEY_LIMIT_PERCENT
        }
        it[key] = percent.coerceIn(LimiterSettings.MIN_LIMIT_PERCENT, LimiterSettings.MAX_LIMIT_PERCENT)
    }

    suspend fun setRouteLimitEnabled(route: OutputRoute, enabled: Boolean) = edit {
        when (route) {
            OutputRoute.HEADPHONES -> it[KEY_HEADPHONES_LIMIT_ENABLED] = enabled
            OutputRoute.BLUETOOTH -> it[KEY_BLUETOOTH_LIMIT_ENABLED] = enabled
            else -> Unit
        }
    }

    suspend fun setOnboardingDone() = edit { it[KEY_ONBOARDING_DONE] = true }

    suspend fun setPin(hash: String, salt: String, iterations: Int) = edit {
        it[KEY_PIN_HASH] = hash
        it[KEY_PIN_SALT] = salt
        it[KEY_PIN_ITERATIONS] = iterations
        it[KEY_FAILED_ATTEMPTS] = 0
        it[KEY_LOCKOUT_UNTIL] = 0L
    }

    suspend fun setPinAttempts(failed: Int, lockoutUntil: Long) = edit {
        it[KEY_FAILED_ATTEMPTS] = failed
        it[KEY_LOCKOUT_UNTIL] = lockoutUntil
    }

    fun recordCorrection(entry: CorrectionEntry) = launchEdit {
        it[KEY_CORRECTIONS] = (it[KEY_CORRECTIONS] ?: 0L) + 1
        val list = parseRecent(it[KEY_RECENT]).toMutableList()
        list.add(0, entry)
        it[KEY_RECENT] = JSONArray(list.take(MAX_RECENT).map(CorrectionEntry::toJson)).toString()
    }

    fun recordServiceStart(reason: String) = launchEdit {
        it[KEY_SERVICE_STARTED_AT] = System.currentTimeMillis()
        it[KEY_SERVICE_START_REASON] = reason
    }

    fun recordBoot(startOk: Boolean) = launchEdit {
        it[KEY_LAST_BOOT_AT] = System.currentTimeMillis()
        it[KEY_LAST_BOOT_OK] = startOk
    }

    fun recordWatchdog() = launchEdit { it[KEY_LAST_WATCHDOG_AT] = System.currentTimeMillis() }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        val prefs = store.edit(block)
        _settings.value = prefs.toSettings()
        _diagnostics.value = prefs.toDiagnostics()
    }

    private fun launchEdit(block: (MutablePreferences) -> Unit) {
        scope.launch { edit(block) }
    }

    private fun Preferences.toSettings() = LimiterSettings(
        enabled = this[KEY_ENABLED] ?: false,
        limitPercent = this[KEY_LIMIT_PERCENT] ?: LimiterSettings.DEFAULT_LIMIT_PERCENT,
        headphonesLimitEnabled = this[KEY_HEADPHONES_LIMIT_ENABLED] ?: false,
        headphonesLimitPercent = this[KEY_HEADPHONES_LIMIT_PERCENT] ?: LimiterSettings.DEFAULT_LIMIT_PERCENT,
        bluetoothLimitEnabled = this[KEY_BLUETOOTH_LIMIT_ENABLED] ?: false,
        bluetoothLimitPercent = this[KEY_BLUETOOTH_LIMIT_PERCENT] ?: LimiterSettings.DEFAULT_LIMIT_PERCENT,
        onboardingDone = this[KEY_ONBOARDING_DONE] ?: false,
        pinHash = this[KEY_PIN_HASH],
        pinSalt = this[KEY_PIN_SALT],
        pinIterations = this[KEY_PIN_ITERATIONS] ?: 0,
        failedPinAttempts = this[KEY_FAILED_ATTEMPTS] ?: 0,
        pinLockoutUntil = this[KEY_LOCKOUT_UNTIL] ?: 0L,
    )

    private fun Preferences.toDiagnostics() = Diagnostics(
        corrections = this[KEY_CORRECTIONS] ?: 0L,
        recent = parseRecent(this[KEY_RECENT]),
        serviceStartedAt = this[KEY_SERVICE_STARTED_AT] ?: 0L,
        serviceStartReason = this[KEY_SERVICE_START_REASON] ?: "",
        lastBootAt = this[KEY_LAST_BOOT_AT] ?: 0L,
        lastBootStartOk = this[KEY_LAST_BOOT_OK] ?: false,
        lastWatchdogAt = this[KEY_LAST_WATCHDOG_AT] ?: 0L,
    )

    private fun parseRecent(raw: String?): List<CorrectionEntry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { CorrectionEntry.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val MAX_RECENT = 20

        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_LIMIT_PERCENT = intPreferencesKey("limit_percent")
        private val KEY_HEADPHONES_LIMIT_ENABLED = booleanPreferencesKey("headphones_limit_enabled")
        private val KEY_HEADPHONES_LIMIT_PERCENT = intPreferencesKey("headphones_limit_percent")
        private val KEY_BLUETOOTH_LIMIT_ENABLED = booleanPreferencesKey("bluetooth_limit_enabled")
        private val KEY_BLUETOOTH_LIMIT_PERCENT = intPreferencesKey("bluetooth_limit_percent")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_PIN_HASH = stringPreferencesKey("pin_hash")
        private val KEY_PIN_SALT = stringPreferencesKey("pin_salt")
        private val KEY_PIN_ITERATIONS = intPreferencesKey("pin_iterations")
        private val KEY_FAILED_ATTEMPTS = intPreferencesKey("pin_failed_attempts")
        private val KEY_LOCKOUT_UNTIL = longPreferencesKey("pin_lockout_until")
        private val KEY_CORRECTIONS = longPreferencesKey("diag_corrections")
        private val KEY_RECENT = stringPreferencesKey("diag_recent")
        private val KEY_SERVICE_STARTED_AT = longPreferencesKey("diag_service_started_at")
        private val KEY_SERVICE_START_REASON = stringPreferencesKey("diag_service_start_reason")
        private val KEY_LAST_BOOT_AT = longPreferencesKey("diag_last_boot_at")
        private val KEY_LAST_BOOT_OK = booleanPreferencesKey("diag_last_boot_ok")
        private val KEY_LAST_WATCHDOG_AT = longPreferencesKey("diag_last_watchdog_at")

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
