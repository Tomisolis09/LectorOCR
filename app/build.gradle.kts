import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Datos de firma: variables de entorno (GitHub Actions) o keystore.properties (tu PC).
// Si no hay nada, se compila un APK release SIN firmar para firmarlo después a mano.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val ksPath = signingValue("KEYSTORE_PATH", "storeFile")
val ksPass = signingValue("KEYSTORE_PASSWORD", "storePassword")
val ksAlias = signingValue("KEY_ALIAS", "keyAlias")
val ksKeyPass = signingValue("KEY_PASSWORD", "keyPassword")
val canSign = ksPath != null && ksPass != null && ksAlias != null && ksKeyPass != null

// IDs de AdMob: variable de entorno (GitHub Actions) o gradle.properties.
// Los de prueba son los oficiales de Google.
val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
val TEST_BANNER_ID = "ca-app-pub-3940256099942544/9214589741"
val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"

fun adValue(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(key) as String?)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.lectorocr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lectorocr"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = rootProject.file(ksPath!!)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        // Debug: SIEMPRE anuncios de prueba (tocar anuncios reales en pruebas puede bloquear la cuenta).
        debug {
            manifestPlaceholders["admobAppId"] = TEST_APP_ID
            buildConfigField("String", "ADMOB_BANNER_ID", "\"$TEST_BANNER_ID\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$TEST_INTERSTITIAL_ID\"")
        }
        // Release: tus IDs de AdMob (ADMOB_APP_ID, ADMOB_BANNER_ID, ADMOB_INTERSTITIAL_ID);
        // si no están definidos, usa los de prueba.
        release {
            isMinifyEnabled = false
            // Con tu keystore: firma propia. Sin keystore: firma de depuración, para que el APK
            // se pueda instalar igual (sirve para uso personal, NO para subir a Google Play).
            signingConfig = if (canSign) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            manifestPlaceholders["admobAppId"] = adValue("ADMOB_APP_ID") ?: TEST_APP_ID
            buildConfigField("String", "ADMOB_BANNER_ID", "\"${adValue("ADMOB_BANNER_ID") ?: TEST_BANNER_ID}\"")
            buildConfigField(
                "String", "ADMOB_INTERSTITIAL_ID",
                "\"${adValue("ADMOB_INTERSTITIAL_ID") ?: TEST_INTERSTITIAL_ID}\""
            )
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OCR en el dispositivo (gratis, modelo incluido en la app)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Texto de PDFs digitales (Apache 2.0)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Escáner de documentos con la cámara (gratis, de Google)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // Publicidad (AdMob)
    implementation("com.google.android.gms:play-services-ads:23.6.0")
}
