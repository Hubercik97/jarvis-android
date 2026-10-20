plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Adres backendu można wstrzyknąć w GitHub Actions (zmienna repozytorium JARVIS_BACKEND_URL).
// To NIE jest sekret — klucz API modelu AI nigdy nie trafia do aplikacji.
// Przyjmujemy wyłącznie adres https:// bez spacji i cudzysłowów; cokolwiek innego = brak adresu
// (aplikacja pokaże wtedy „Brak adresu backendu AI” zamiast łączyć się z niepewnym serwerem).
val defaultBackendUrl: String =
    (System.getenv("JARVIS_BACKEND_URL") ?: (project.findProperty("jarvisBackendUrl") as String?) ?: "")
        .trim()
        .trimEnd('/')
        .takeIf { Regex("^https://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~/-]*)?$").matches(it) }
        ?: ""

val ciRunNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Podgląd ekranów (Paparazzi) działa tylko przy: gradle -Ppreview :app:recordPaparazziDebug
// Zwykłe testy i budowanie APK w ogóle nie ładują tej wtyczki ani testów podglądu.
val previewMode = project.hasProperty("preview")
if (previewMode) apply(plugin = "app.cash.paparazzi")

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
        compose = true
    }

    composeOptions {
        // Zgodne z Kotlin 1.9.24
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("test") {
            if (previewMode) java.srcDir("src/preview/java")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // Prawdziwa implementacja org.json dla testów jednostkowych (android.jar ma tylko atrapy).
    testImplementation("org.json:json:20240303")
}
