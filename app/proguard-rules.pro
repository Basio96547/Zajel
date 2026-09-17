# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ${sdk.dir}/tools/proguard/proguard-android.txt

# Keep data classes for Room
-keep class com.securemessenger.app.data.model.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.**

# Keep Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Libsodium
-keep class com.guardsquare.** { *; }
-dontwarn com.guardsquare.**

# Keep SQLCipher
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Keep ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Google Tink (used for AEAD/keyset handling): its errorprone annotations are
# compile-time-only checks, never present at runtime, but referenced widely
# enough that R8 refuses to proceed without being told they're expected to be
# missing. First surfaced only now — this project's release build type was
# never actually signable before, so minification against the real dependency
# set had never run to completion.
-dontwarn com.google.errorprone.annotations.**

# Security: Obfuscate package names
-overloadaggressively
-repackageclasses ""

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Prevent reflection attacks
-assumevalues class * {
    *** *;
}
