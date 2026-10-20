package pl.zamirski.jarvis.core

/**
 * Decyzja, jak (i czy) kierować mikrofon na słuchawki Bluetooth. Czysta logika — testowana jednostkowo.
 *
 * - Android 12+ (API 31+): AudioManager.setCommunicationDevice(...) — oficjalne API wyboru urządzenia
 *   komunikacyjnego (słuchawki SCO lub BLE Audio).
 * - Android 8–11 (API 26–30): starsze startBluetoothSco().
 * Galaxy S21 ma Androida 12+ (zależnie od aktualizacji), więc używa pierwszej ścieżki.
 */
object BluetoothRoutePolicy {

    enum class Strategy { NONE, COMMUNICATION_DEVICE, LEGACY_SCO }

    const val API_COMMUNICATION_DEVICE = 31

    fun strategy(settingEnabled: Boolean, headsetAvailable: Boolean, sdkInt: Int): Strategy = when {
        !settingEnabled || !headsetAvailable -> Strategy.NONE
        sdkInt >= API_COMMUNICATION_DEVICE -> Strategy.COMMUNICATION_DEVICE
        else -> Strategy.LEGACY_SCO
    }

    /** Krótki opis stanu do ekranu Ustawień (bez obietnic — działanie potwierdza dopiero test na telefonie). */
    fun describe(settingEnabled: Boolean, headsetAvailable: Boolean, deviceName: String?): String = when {
        !settingEnabled -> "Wyłączone — mikrofon telefonu"
        !headsetAvailable -> "Brak podłączonych słuchawek z mikrofonem — mikrofon telefonu"
        deviceName.isNullOrBlank() -> "Słuchawki wykryte — Jarvis spróbuje użyć ich mikrofonu"
        else -> "Słuchawki: $deviceName — Jarvis spróbuje użyć ich mikrofonu"
    }
}
