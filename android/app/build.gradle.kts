import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "de.rb64.live"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.rb64.live"
        minSdk = 31   // Widget-Liste mit Startposition (RemoteCollectionItems) ab Android 12
        versionCode = 17
        versionName = "0.16.1"
        targetSdk = 36
    }

    // Release-Schluessel liegt ausserhalb des Repos (~/.rb64-release/keystore.properties).
    // Fehlt er (z. B. bei anderen, die den Code bauen), bricht assembleRelease ab (siehe unten); assembleDebug geht immer.
    val releaseProps = Properties().apply {
        val f = file(System.getProperty("user.home") + "/.rb64-release/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (releaseProps.isNotEmpty()) create("release") {
            storeFile = file(releaseProps.getProperty("storeFile"))
            storePassword = releaseProps.getProperty("storePassword")
            keyAlias = releaseProps.getProperty("keyAlias")
            keyPassword = releaseProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8: ungenutzten Bibliothekscode (v. a. Material-Icons, Firebase) entfernen - App etwa ein Drittel so gross
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-firestore")   // Rueckkanal Handy -> VM
    implementation("com.google.firebase:firebase-auth")        // anonyme Anmeldung fuer die Firestore-Regeln
    implementation("com.google.android.gms:play-services-location:21.3.0")   // GPS + Geofencing
}

// Release ohne echten Release-Schluessel abbrechen: sonst entsteht still eine debug-signierte "Release"-APK, die sich
// nicht als Update ueber die installierte App spielen laesst (Nutzer muessten neu installieren und verloeren Daten).
tasks.matching { it.name in setOf("assembleRelease", "bundleRelease", "packageRelease") }.configureEach {
    doFirst {
        val f = file(System.getProperty("user.home") + "/.rb64-release/keystore.properties")
        if (!f.exists()) throw GradleException("Release-Schlüssel fehlt (~/.rb64-release/keystore.properties) - Abbruch statt Debug-Signatur")
    }
}
