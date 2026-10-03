plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing reads from ~/.gradle/gradle.properties (never committed) so the keystore
// and its passwords never touch this repo. Falls back to unsigned when they're absent -
// e.g. debug builds or a CI checkout without the release secrets.
val releaseStoreFile = providers.gradleProperty("PHONEMIC_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("PHONEMIC_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("PHONEMIC_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("PHONEMIC_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigningConfig = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.scylla.tool.phonemic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.scylla.tool.phonemic"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.0"
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFile!!.replaceFirst("~", System.getProperty("user.home")))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // QR pairing: scan the code the PC receiver displays.
    val cameraXVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
