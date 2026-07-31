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
    // This project lives under a path containing Arabic characters. Gradle
    // hands the test worker its classpath through a generated manifest, and the
    // worker JVM reads that back using its *platform* default charset — which
    // on Windows is a legacy codepage, not UTF-8. The decoded path then points
    // nowhere and every test class fails to load with ClassNotFoundException,
    // which looks exactly like a broken test but isn't one.
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

/**
 * Runs the desktop tests WITHOUT Gradle's test worker.
 *
 * This project lives under a path containing Arabic characters. Gradle hands
 * its test worker the classpath indirectly (a generated manifest), and the
 * worker JVM resolves those entries using the platform default charset, which
 * on Windows is a legacy codepage rather than UTF-8 — every entry decodes to a
 * path that does not exist, and `gradlew :desktop:test` fails with
 * ClassNotFoundException on classes that compiled perfectly well. It is the
 * same root cause as the `android.overridePathCheck=true` line :app needs.
 *
 * JavaExec passes the classpath straight through on the command line, so it is
 * unaffected. Moving the project to an ASCII path would remove the need for
 * this task entirely.
 *
 *     gradlew :desktop:testDirect
 */
tasks.register<JavaExec>("testDirect") {
    group = "verification"
    description = "Run the desktop tests directly, bypassing Gradle's test worker (non-ASCII path workaround)."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.junit.runner.JUnitCore")
    args("com.securemessenger.desktop.LoopbackMessagingTest")
}