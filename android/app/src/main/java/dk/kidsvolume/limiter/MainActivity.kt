package dk.kidsvolume.limiter

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import dk.kidsvolume.limiter.plugin.VolumeLimiterPlugin

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Lokale plugins skal registreres før super.onCreate().
        registerPlugin(VolumeLimiterPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
