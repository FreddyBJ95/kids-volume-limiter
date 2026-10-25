package dk.kidsvolume.limiter.volume

import dk.kidsvolume.limiter.settings.LimiterSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeLimiterTest {

    @Test
    fun `grænsen rundes ned til enhedens trin`() {
        // Typisk tablet med 15 trin: 40 % = trin 6 (40 %), 45 % = stadig trin 6.
        assertEquals(6, VolumeLimiter.limitIndexFor(40, 0, 15))
        assertEquals(6, VolumeLimiter.limitIndexFor(45, 0, 15))
        assertEquals(15, VolumeLimiter.limitIndexFor(100, 0, 15))
        // Enhed med 25 trin.
        assertEquals(10, VolumeLimiter.limitIndexFor(40, 0, 25))
    }

    @Test
    fun `den valgte procent overskrides aldrig`() {
        for (max in 5..160) {
            for (percent in 10..100 step 5) {
                val index = VolumeLimiter.limitIndexFor(percent, 0, max)
                assertTrue("max=$max percent=$percent", index * 100 <= max * percent || index == 1)
            }
        }
    }

    @Test
    fun `lyden slukkes aldrig helt`() {
        assertEquals(1, VolumeLimiter.limitIndexFor(10, 0, 7))
        assertEquals(2, VolumeLimiter.limitIndexFor(10, 2, 15))
    }

    @Test
    fun `kun højttalere begrænses som standard`() {
        val s = LimiterSettings(enabled = true)
        assertEquals(40, s.limitPercentFor(OutputRoute.SPEAKER))
        assertNull(s.limitPercentFor(OutputRoute.HEADPHONES))
        assertNull(s.limitPercentFor(OutputRoute.BLUETOOTH))
        assertNull(s.limitPercentFor(OutputRoute.OTHER))
    }

    @Test
    fun `hovedtelefoner og Bluetooth har hver deres grænse`() {
        val s = LimiterSettings(
            enabled = true,
            limitPercent = 50,
            headphonesLimitEnabled = true,
            headphonesLimitPercent = 30,
            bluetoothLimitEnabled = true,
            bluetoothLimitPercent = 35,
        )
        assertEquals(50, s.limitPercentFor(OutputRoute.SPEAKER))
        assertEquals(30, s.limitPercentFor(OutputRoute.HEADPHONES))
        assertEquals(35, s.limitPercentFor(OutputRoute.BLUETOOTH))
        assertNull(s.limitPercentFor(OutputRoute.OTHER))
    }

    @Test
    fun `en slået-fra grænse huskes, men bruges ikke`() {
        val s = LimiterSettings(headphonesLimitEnabled = false, headphonesLimitPercent = 25)
        assertFalse(s.isLimited(OutputRoute.HEADPHONES))
        assertEquals(25, s.percentFor(OutputRoute.HEADPHONES))
        assertNull(s.limitPercentFor(OutputRoute.HEADPHONES))
        assertTrue(s.isLimited(OutputRoute.SPEAKER))
    }

    @Test
    fun `procent af lydstyrke`() {
        assertEquals(40, VolumeLimiter.percentOf(6, 15))
        assertEquals(0, VolumeLimiter.percentOf(0, 15))
        assertEquals(100, VolumeLimiter.percentOf(15, 15))
    }
}
