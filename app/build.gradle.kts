plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Base URL of the blind mailbox relay (see relay/). Deliberately overridable
// and deliberately allowed to be empty: an empty value compiles a build with no
// relay path at all, which is the strictest configuration — local network only,
// nothing ever leaves the LAN. Point it at your own deployment with
// `-PrelayUrl=https://...` or a `relayUrl=` line in gradle.properties.
val relayUrl: String = (project.findProperty("relayUrl") as String?) ?: ""

android {
    namespace = "com.securemessenger.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.securemessenger.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        android.buildFeatures.buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = false
            buildConfigField("Boolean", "OFFLINE_MODE", "false")
            buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            buildConfigField("Boolean", "OFFLINE_MODE", "false")
            buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        disable += listOf(
            "AllowBackup",
            "AutoboxingStateCreation",
            "MutableCollectionMutableState"
        )
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Shared protocol core — the crypto, the ratchet and the envelope format,
    // compiled once and used identically by the desktop client, so the two can
    // actually talk to each other. `org.json` is excluded because Android
    // already provides it in the bootclasspath; shipping the jar too would put
    // a second copy of those classes in the APK.
    implementation(project(":core")) {
        exclude(group = "org.json", module = "json")
    }

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Room with SQLCipher
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // net.zetetic:android-database-sqlcipher is deprecated and its native libs
    // aren't 16KB-page-size aligned; sqlcipher-android is the maintained
    // replacement with full 16KB support.
    implementation("net.zetetic:sqlcipher-android:4.9.0")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // Libsodium for encryption (Lazysodium). Exclude its transitive JNA *jar*
    // so we can supply the Android *aar* below (which bundles the native libs).
    implementation("com.goterl:lazysodium-android:5.2.0") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    // JNA Android AAR — bundles libjnidispatch.so for every ABI (incl. x86_64),
    // which lazysodium needs to load libsodium. Without this the native library
    // fails to load on x86_64 devices/emulators. 5.14.0+ is required for
    // libjnidispatch.so to be 16KB-page-size aligned (older versions crash on
    // 16KB-page devices — see java-native-access/jna#1618).
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    // BouncyCastle — post-quantum ML-KEM (Kyber, NIST FIPS 203) for the hybrid
    // handshake, so recorded traffic stays safe against future quantum attacks.
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")

    // Biometric authentication
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")

    // WebSocket (client side — connects directly to a peer's local server)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Embedded local WebSocket server — each device is its own relay, no
    // external server at all. NanoHTTPD is a tiny, dependency-free, purpose-
    // built library for exactly this (embedding a server inside an app).
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // DataStore for secure preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // QR Code generation
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")

    // WorkManager for background tasks (message cleanup)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Testing
    // No JVM unit-test source set: every test here touches libsodium, whose
    // native library cannot load outside an instrumented run, so they all live
    // in androidTest (junit arrives transitively via androidx.test.ext:junit).
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
