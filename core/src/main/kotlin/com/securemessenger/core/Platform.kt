package com.securemessenger.core

import com.goterl.lazysodium.LazySodium

/**
 * The two things this module can't provide for itself, supplied once at startup
 * by whichever app is hosting it.
 *
 * Everything else in :core is deliberately platform-free: the same compiled
 * bytes run on Android and on a desktop JVM, which is the whole point — the
 * ratchet, the key derivation, the padding and the envelope shapes have to be
 * identical on both ends or the two simply cannot talk to each other.
 */
object Platform {

    @Volatile
    private var sodiumInstance: LazySodium? = null

    @Volatile
    private var logger: CoreLogger = CoreLogger.Silent

    /**
     * Install the platform's libsodium binding: `LazySodiumAndroid(SodiumAndroid())`
     * on Android, `LazySodiumJava(SodiumJava())` on the desktop. Must be called
     * before any crypto happens — [sodium] throws rather than silently
     * initialising a wrong or half-loaded native library.
     */
    fun installSodium(instance: LazySodium) {
        sodiumInstance = instance
    }

    fun installLogger(instance: CoreLogger) {
        logger = instance
    }

    val sodium: LazySodium
        get() = sodiumInstance ?: error(
            "Platform.installSodium() was never called — the host app must supply its libsodium binding before any crypto runs"
        )

    val log: CoreLogger get() = logger
}

/** Where :core's diagnostics go — logcat on Android, stderr (or a file) on the desktop. */
interface CoreLogger {
    fun debug(tag: String, message: String)
    fun warn(tag: String, message: String, error: Throwable? = null)
    fun error(tag: String, message: String, error: Throwable? = null)

    /** Used until the host installs a real one, so a missing install never crashes. */
    object Silent : CoreLogger {
        override fun debug(tag: String, message: String) {}
        override fun warn(tag: String, message: String, error: Throwable?) {}
        override fun error(tag: String, message: String, error: Throwable?) {}
    }
}
