package pl.zamirski.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.zamirski.jarvis.core.BluetoothRoutePolicy.Strategy

class BluetoothRoutePolicyTest {

    @Test
    fun `no routing when disabled or no headset`() {
        assertEquals(Strategy.NONE, BluetoothRoutePolicy.strategy(false, true, 34))
        assertEquals(Strategy.NONE, BluetoothRoutePolicy.strategy(true, false, 34))
    }

    @Test
    fun `android 12 and newer use communication device api, older use legacy sco`() {
        assertEquals(Strategy.COMMUNICATION_DEVICE, BluetoothRoutePolicy.strategy(true, true, 31))
        assertEquals(Strategy.COMMUNICATION_DEVICE, BluetoothRoutePolicy.strategy(true, true, 34))
        assertEquals(Strategy.LEGACY_SCO, BluetoothRoutePolicy.strategy(true, true, 30))
        assertEquals(Strategy.LEGACY_SCO, BluetoothRoutePolicy.strategy(true, true, 26))
    }

    @Test
    fun `status text never claims the headset microphone is confirmed`() {
        val texts = listOf(
            BluetoothRoutePolicy.describe(false, true, "Buds"),
            BluetoothRoutePolicy.describe(true, false, null),
            BluetoothRoutePolicy.describe(true, true, null),
            BluetoothRoutePolicy.describe(true, true, "Galaxy Buds2"),
        )
        texts.forEach { assertTrue(it, !it.contains("działa")) }
        assertTrue(texts[3].contains("Galaxy Buds2"))
    }
}
