plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions sätter GITHUB_RUN_NUMBER, så varje bygge får ett högre versionsnummer
// och kan installeras ovanpå det förra. Uppdateraren i appen jämför mot samma nummer.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// Signeringsnyckeln ligger krypterad i repot (app/jptv.keystore.enc). Bygget i GitHub Actions
// dekrypterar den med hemligheten JPTV_SIGNING_PASSWORD och pekar hit via JPTV_KEYSTORE.
// Lokala byggen utan nyckel signeras med vanliga debug-nyckeln.
val releaseKeystore = System.getenv("JPTV_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
val signingPassword = System.getenv("JPTV_SIGNING_PASSWORD")

android {
    namespace = "se.jptv"
    compileSdk = 35

    defaultConfig {
        applicationId = "se.jptv"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"thesummerboat/jptv\"")
    }

    signingConfigs {
        if (releaseKeystore != null && signingPassword != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingPassword
                keyAlias = "jptv"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // Visa hela felet i byggloggen när ett test inte går igenom.
            test.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")
    // Telefonen: vanliga Material 3-komponenter för pekskärm.
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Casta från telefonen till Chromecast/Google TV (t.ex. hotell-TV).
    implementation("androidx.media3:media3-cast:$media3")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // XmlPullParser-implementation för att testa XMLTV-läsningen utanför Android.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
