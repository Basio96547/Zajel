import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// Same reasoning as :core — target 17 on the JDK Gradle already runs on
// instead of requesting a toolchain that isn't installed.
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
    // The protocol itself — identical bytes to what the phone runs.
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // The desktop half of the libsodium binding :core compiles against. The
    // Android app supplies lazysodium-android instead; both expose the same
    // LazySodium API, so the shared crypto code is genuinely shared.
    implementation("com.goterl:lazysodium-java:5.1.4")
    implementation("net.java.dev.jna:jna:5.17.0")

    // mDNS/DNS-SD, the desktop counterpart to Android's NsdManager. Advertises
    // under the exact same `_securemessenger._tcp.` service type and the same
    // `sm-<token>` naming, so a phone discovers this client as just another peer.
    implementation("org.jmdns:jmdns:3.5.9")

    // QR: rendered for a phone camera to scan, and decoded out of an image file
    // the phone shared (a desktop has no camera to point at the phone).
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.google.zxing:javase:3.5.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // Ordinary hygiene, no longer a workaround: Gradle hands the test worker
    // its classpath through a generated file that the worker decodes with the
    // platform default charset, which on Windows is still a legacy codepage.
    // Pinning UTF-8 keeps that correct whatever the project path turns out to
    // be — including if this repo is ever moved back under a non-ASCII path.
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    systemProperty("java.io.tmpdir", layout.buildDirectory.dir("tmp/test").get().asFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

compose.desktop {
    application {
        mainClass = "com.securemessenger.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "SecureMessenger"
            packageVersion = "1.0.0"
        }
    }
}

// A `testDirect` JavaExec task used to live here, running the tests outside
// Gradle's test worker. It existed for exactly one reason: the project sat
// under a path containing Arabic characters, and the worker decoded its
// generated classpath file with the Windows legacy codepage, so every entry
// resolved to a path that did not exist and all tests died with
// ClassNotFoundException despite compiling. The project moved to an ASCII path
// on 2026-07-31, `gradlew :desktop:test` works normally, and the task was
// deleted rather than kept "just in case" — a workaround nobody needs is a
// second way to run the tests that will quietly drift from the first.