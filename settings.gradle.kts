pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // Compose Multiplatform (the :desktop client) is published here, not on
        // Maven Central.
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
    }
}

rootProject.name = "SecureMessenger"
// Pure Kotlin/JVM protocol core, shared byte-for-byte by the Android app and
// the desktop client. Anything the two must agree on exactly — key derivation,
// the ratchet, the padding, the envelope shapes — lives there and nowhere else.
include(":core")
include(":app")
include(":desktop")
include(":hisn")
