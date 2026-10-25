package dk.kidsvolume.limiter.volume

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Hvor medielyden (STREAM_MUSIC) bliver afspillet lige nu. */
enum class OutputRoute(val id: String) {
    SPEAKER("speaker"),
    HEADPHONES("headphones"),
    BLUETOOTH("bluetooth"),
    OTHER("other"),
}

// Nyere TYPE_*-konstanter er tal, der kompileres ind; på ældre Android forekommer de bare aldrig.
@SuppressLint("InlinedApi")
object AudioRoutes {

    private val MEDIA_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
    )

    private val HEADPHONE_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
    )

    private val SPEAKER_TYPES = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    )

    /**
     * Android 13+ kan fortælle præcist, hvilken enhed medielyd sendes til. På ældre versioner
     * gætter vi ud fra de tilsluttede udgange (Android sender medielyd til tilsluttede
     * hovedtelefoner/Bluetooth frem for højttaleren).
     */
    fun current(audio: AudioManager): OutputRoute {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val devices = try {
                audio.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES)
            } catch (_: Exception) {
                emptyList()
            }
            if (devices.isNotEmpty()) return classify(devices.map { it.type })
        }
        return legacyGuess(audio)
    }

    private fun classify(types: List<Int>): OutputRoute = when {
        types.any { it in BLUETOOTH_TYPES } -> OutputRoute.BLUETOOTH
        types.any { it in HEADPHONE_TYPES } -> OutputRoute.HEADPHONES
        types.any { it in SPEAKER_TYPES } -> OutputRoute.SPEAKER
        else -> OutputRoute.OTHER
    }

    private fun legacyGuess(audio: AudioManager): OutputRoute {
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        return when {
            outputs.any { it in BLUETOOTH_TYPES && it != AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> OutputRoute.BLUETOOTH
            outputs.any { it in HEADPHONE_TYPES } -> OutputRoute.HEADPHONES
            else -> OutputRoute.SPEAKER
        }
    }
}
