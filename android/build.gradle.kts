plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Zrzuty ekranów bez emulatora (podgląd w CI). Włączany tylko z -Ppreview.
    id("app.cash.paparazzi") version "1.3.4" apply false
}
