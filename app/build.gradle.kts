import java.util.Properties

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

// Base URL of the optional username directory (see directory/) — a wholly
// separate, opt-in service from the relay above. Same escape hatch, same
// reasoning: empty compiles it out entirely, so a build with no directoryUrl
// set has no username-search/claim code path reachable at all, only QR
// pairing. Point it at your own deployment with `-PdirectoryUrl=https://...`
// or a `directoryUrl=` line in gradle.properties.
val directoryUrl: String = (project.findProperty("directoryUrl") as String?) ?: ""

// owner/repo whose GitHub Releases the in-app update checker polls (see
// update/UpdateChecker.kt). Overridable the same way as the URLs above with
// `-PupdateRepo=owner/repo`, so a fork can point updates at its own releases
// instead of silently checking someone else's.
val updateRepo: String = (project.findProperty("updateRepo") as String?) ?: "Basio96547/Zajel"

// Release signing. Loaded from keystore.properties locally (gitignored — see
// that file's own warning about not committing it) or from these same names
// as environment variables in CI, which decodes the keystore from a secret
// instead of ever writing it to a tracked file. Absent both, release builds
// are simply left unsigned rather than failing every other Gradle task for
// anyone who clones this repo without the signing key.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingProp(envVar: String, propKey: String): String? =
    System.getenv(envVar) ?: keystoreProps.getProperty(propKey)
val releaseStoreFile = signingProp("KEYSTORE_PATH", "storeFile")
val hasReleaseSigning = releaseStoreFile != null

// Version shown to users and compared against GitHub release tags — set by CI
// from the pushed tag (`-PreleaseVersionName=1.2.3 -PreleaseVersionCode=42`)
// so a release never ships without bumping both. Local/dev builds fall back
// to the placeholders below.
val releaseVersionName: String = (project.findProperty("releaseVersionName") as String?) ?: "1.0.0"
val releaseVersionCode: Int = (project.findProperty("releaseVersionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.securemessenger.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.securemessenger.app"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        android.buildFeatures.buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingProp("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingProp("KEY_ALIAS", "keyAlias")
                keyPassword = signingProp("KEY_PASSWORD", "keyPassword")
            }
        }
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
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("Boolean", "OFFLINE_MODE", "false")
            buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
            buildConfigField("String", "DIRECTORY_URL", "\"$directoryUrl\"")
            buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            buildConfigField("Boolean", "OFFLINE_MODE", "false")
            buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
            buildConfigField("String", "DIRECTORY_URL", "\"$directoryUrl\"")
            buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
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
        // The one AIDL interface in the project: the boundary to the isolated
        // media-decoding process. AGP 8 no longer enables AIDL by default.
        aidl = true
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
    // ProcessLifecycleOwner: tells us the user left the APP, not that an
    // Activity stopped. See SecureMessengerApp.installDisguiseHideOnLeavingApp
    // — reading an Activity stop as "left the app" hid the messenger while the
    // QR scanner was open and silently killed every pairing attempt.
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
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
    // Almost everything here is instrumented, because almost every test touches
    // libsodium and its native library cannot load outside a device run. The
    // JVM source set exists for the exceptions — logic that touches neither
    // Android nor native code, where needing a connected phone would only make
    // the test harder to run. MediaSandboxGeometryTest (the guard on what the
    // sandbox is allowed to claim on its way back) is the first of those.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
