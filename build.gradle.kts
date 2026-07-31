// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.26" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // :core (plain JVM protocol library) and :desktop (Compose Desktop client).
    // Same Kotlin version as the Android modules on purpose — the three share
    // compiled protocol code, so they must agree on the metadata version.
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.1" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
