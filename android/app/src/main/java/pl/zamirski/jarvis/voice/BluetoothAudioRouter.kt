package pl.zamirski.jarvis.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import pl.zamirski.jarvis.core.BluetoothRoutePolicy

/**
 * Kierowanie mikrofonu na słuchawki Bluetooth na czas słuchania polecenia.
 *
 * Zasada: włączamy łącze rozmowy (SCO / BLE Audio) tylko na czas rozpoznawania mowy,
 * a przed odpowiedzią głosową je zwalniamy — wtedy głos Jarvisa idzie przez słuchawki
 * normalną, lepszą jakościowo ścieżką multimediów (A2DP).
 *
 * Ograniczenie: rozpoznawaniem mowy zajmuje się systemowa usługa Google. To, czy pobiera dźwięk
 * z wybranego tu urządzenia, zależy od tej usługi i producenta — wymaga testu na Galaxy S21.
 */
class BluetoothAudioRouter(context: Context) {

    private val am = context.applicationContext.getSystemService(AudioManager::class.java)
    private var active = false

    /** Słuchawki z mikrofonem dostępne jako urządzenie komunikacyjne (SCO lub BLE). */
    fun headset(): AudioDeviceInfo? {
        val manager = am ?: return null
        return if (Build.VERSION.SDK_INT >= BluetoothRoutePolicy.API_COMMUNICATION_DEVICE) {
            manager.availableCommunicationDevices.firstOrNull { isHeadset(it.type) }
        } else {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
    }

    fun headsetName(): String? = headset()?.productName?.toString()?.takeIf { it.isNotBlank() }

    /**
     * Włącza mikrofon słuchawek, jeśli ustawienie jest włączone i słuchawki są dostępne.
     * Zwraca true, gdy przełączenie zostało przyjęte przez system.
     */
    @Suppress("DEPRECATION")
    fun startMicRoute(settingEnabled: Boolean): Boolean {
        val manager = am ?: return false
        val device = headset()
        return when (BluetoothRoutePolicy.strategy(settingEnabled, device != null, Build.VERSION.SDK_INT)) {
            BluetoothRoutePolicy.Strategy.NONE -> false
            BluetoothRoutePolicy.Strategy.COMMUNICATION_DEVICE -> {
                active = try {
                    manager.setCommunicationDevice(device!!)
                } catch (e: Exception) {
                    false
                }
                active
            }
            BluetoothRoutePolicy.Strategy.LEGACY_SCO -> {
                active = try {
                    manager.startBluetoothSco()
                    manager.isBluetoothScoOn = true
                    true
                } catch (e: Exception) {
                    false
                }
                active
            }
        }
    }

    /** Zwalnia łącze słuchawek (bezpieczne do wywołania wielokrotnie). */
    @Suppress("DEPRECATION")
    fun stopMicRoute() {
        if (!active) return
        active = false
        val manager = am ?: return
        try {
            if (Build.VERSION.SDK_INT >= BluetoothRoutePolicy.API_COMMUNICATION_DEVICE) {
                manager.clearCommunicationDevice()
            } else {
                manager.isBluetoothScoOn = false
                manager.stopBluetoothSco()
            }
        } catch (e: Exception) {
            // Urządzenie mogło się rozłączyć — nic więcej do zrobienia.
        }
    }

    val isActive: Boolean get() = active

    private fun isHeadset(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= BluetoothRoutePolicy.API_COMMUNICATION_DEVICE && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    companion object {
        /** Czas na zestawienie łącza słuchawek przed startem rozpoznawania mowy. */
        const val ROUTE_SETTLE_MS = 600L
    }
}
