package dk.kidsvolume.limiter.security

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import dk.kidsvolume.limiter.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min

/**
 * PIN-koden gemmes aldrig i klartekst: kun et salt og en PBKDF2-HMAC-SHA256-hash ligger i
 * appens private DataStore. Efter gentagne forkerte forsøg spærres der i stigende tid.
 *
 * Når PIN-koden er indtastet korrekt, er indstillingerne "låst op" i en kort periode
 * (glidende vindue), så forælderen kan ændre flere ting uden at taste koden igen.
 */
class PinManager private constructor(context: Context) {

    data class VerifyResult(val ok: Boolean, val attemptsLeft: Int, val lockoutSeconds: Int)

    class PinException(val code: String, message: String) : Exception(message)

    private val settings = SettingsRepository.get(context)
    private val random = SecureRandom()

    @Volatile
    private var unlockedUntil = 0L

    fun isValidFormat(pin: String?): Boolean = pin != null && PIN_REGEX.matches(pin)

    suspend fun create(pin: String) {
        if (settings.current.pinSet) throw PinException("PIN_EXISTS", "Der findes allerede en PIN-kode")
        store(pin)
        unlock()
    }

    suspend fun change(newPin: String) {
        requireUnlocked()
        store(newPin)
        unlock()
    }

    suspend fun verify(pin: String): VerifyResult {
        val s = settings.current
        if (!s.pinSet) throw PinException("NO_PIN", "Der er ikke oprettet en PIN-kode")
        val now = System.currentTimeMillis()
        val lockLeft = lockoutSecondsLeft(s.pinLockoutUntil, now)
        if (lockLeft > 0) return VerifyResult(false, 0, lockLeft)

        val salt = Base64.decode(s.pinSalt, Base64.NO_WRAP)
        val expected = Base64.decode(s.pinHash, Base64.NO_WRAP)
        val actual = hash(pin, salt, s.pinIterations)
        if (MessageDigest.isEqual(expected, actual)) {
            if (s.failedPinAttempts != 0) settings.setPinAttempts(0, 0L)
            unlock()
            return VerifyResult(true, MAX_ATTEMPTS, 0)
        }

        val failed = s.failedPinAttempts + 1
        val lockoutUntil = if (failed >= MAX_ATTEMPTS) {
            val steps = min(failed - MAX_ATTEMPTS, 5)
            now + min(BASE_LOCKOUT_MS shl steps, MAX_LOCKOUT_MS)
        } else {
            0L
        }
        settings.setPinAttempts(failed, lockoutUntil)
        return VerifyResult(
            ok = false,
            attemptsLeft = (MAX_ATTEMPTS - failed).coerceAtLeast(0),
            lockoutSeconds = lockoutSecondsLeft(lockoutUntil, now),
        )
    }

    fun isUnlocked(): Boolean = SystemClock.elapsedRealtime() < unlockedUntil

    fun unlockSecondsLeft(): Int =
        ((unlockedUntil - SystemClock.elapsedRealtime()) / 1000).toInt().coerceAtLeast(0)

    /** Forlænger oplåsningen, når forælderen er aktiv. */
    fun touch() {
        if (isUnlocked()) unlock()
    }

    fun lock() {
        unlockedUntil = 0L
    }

    fun requireUnlocked() {
        if (!isUnlocked()) throw PinException("LOCKED", "Indtast PIN-koden først")
    }

    private fun unlock() {
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_WINDOW_MS
    }

    private suspend fun store(pin: String) {
        if (!isValidFormat(pin)) throw PinException("INVALID_PIN", "PIN-koden skal være 4-6 cifre")
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = hash(pin, salt, ITERATIONS)
        settings.setPin(
            hash = Base64.encodeToString(hash, Base64.NO_WRAP),
            salt = Base64.encodeToString(salt, Base64.NO_WRAP),
            iterations = ITERATIONS,
        )
    }

    private suspend fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray =
        withContext(Dispatchers.Default) {
            val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS)
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

    private fun lockoutSecondsLeft(lockoutUntil: Long, now: Long): Int {
        if (lockoutUntil <= now) return 0
        // Beskyt mod, at uret er stillet tilbage: spærringen kan aldrig være længere end maksimum.
        val left = min(lockoutUntil - now, MAX_LOCKOUT_MS)
        return ((left + 999) / 1000).toInt()
    }

    companion object {
        private val PIN_REGEX = Regex("^\\d{4,6}$")
        private const val ITERATIONS = 120_000
        private const val SALT_BYTES = 16
        private const val HASH_BITS = 256
        private const val MAX_ATTEMPTS = 5
        private const val BASE_LOCKOUT_MS = 30_000L
        private const val MAX_LOCKOUT_MS = 15 * 60_000L
        private const val UNLOCK_WINDOW_MS = 3 * 60_000L

        @Volatile
        private var instance: PinManager? = null

        fun get(context: Context): PinManager =
            instance ?: synchronized(this) {
                instance ?: PinManager(context.applicationContext).also { instance = it }
            }
    }
}
