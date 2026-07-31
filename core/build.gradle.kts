plugins {
    id("org.jetbrains.kotlin.jvm")
    // Needed for `api(...)`: several of these libraries are part of this
    // module's public surface (LocalRelayServer *is* a NanoWSD, Envelopes
    // takes and returns JSONObject), so consumers must see them too.
    id("java-library")
}

// Target 17 (matching :app's compileOptions) using whichever JDK Gradle is
// already running on, rather than requesting a toolchain: the only JDK
// installed here is the Android Studio JBR (21), and asking for a separate
// 17 toolchain fails the build outright with nothing to download it from.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Libsodium is deliberately compileOnly. The Android app ships
    // `lazysodium-android` (which bundles the .so for each ABI) and the desktop
    // client ships `lazysodium-java` (which bundles the desktop natives); both
    // artifacts contain the very same `com.goterl.lazysodium.LazySodium` API
    // this module compiles against. Depending on either one here would put a
    // duplicate copy of those classes on the other platform's classpath, so the
    // concrete instance is injected at startup instead — see SodiumBridge.
    compileOnly("com.goterl:lazysodium-java:5.1.4")
    compileOnly("net.java.dev.jna:jna:5.17.0")

    // Post-quantum ML-KEM (FIPS 203) for the hybrid handshake. Pure Java, works
    // identically on Android and the desktop JVM.
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")

    // The wire format is JSON, and JSONObject appears directly in Envelopes'
    // signatures — so `api`, not `implementation`. Android provides `org.json`
    // in its bootclasspath, so :app excludes this artifact and uses the
    // platform's; the desktop client has no such built-in and takes the jar.
    api("org.json:json:20240303")

    // Same WebSocket stack on both platforms, so the transport behaves the same
    // way regardless of which side opened the connection. NanoWSD is
    // LocalRelayServer's supertype, so a consumer that can't see it can't even
    // call `start()` on the server.
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.nanohttpd:nanohttpd-websocket:2.3.1")

    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
