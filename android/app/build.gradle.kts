plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Adres backendu można wstrzyknąć w GitHub Actions (zmienna repozytorium JARVIS_BACKEND_URL).
// To NIE jest sekret — klucz API modelu AI nigdy nie trafia do aplikacji.
val defaultBackendUrl: String =
    (System.getenv("JARVIS_BACKEND_URL") ?: (project.findProperty("jarvisBackendUrl") as String?) ?: "")
        .trim()
        .replace("\"", "")

val ciRunNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "pl.zamirski.jarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.zamirski.jarvis"
        minSdk = 26
        targetSdk = 34
        versionCode = ciRunNumber
        versionName = "0.2.$ciRunNumber"
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"$defaultBackendUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // Prawdziwa implementacja org.json dla testów jednostkowych (android.jar ma tylko atrapy).
    testImplementation("org.json:json:20240303")
}
