package com.securemessenger.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.securemessenger.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Checks BuildConfig.UPDATE_REPO's GitHub Releases for a newer build than the
 * one running, and hands off to the system installer for one the user chose
 * to fetch.
 *
 * Talks to api.github.com directly and nowhere else — no token, because
 * UPDATE_REPO is public (see app/build.gradle.kts): embedding one in an APK
 * anyone can decompile would hand out repo access to whoever bothered to
 * look. What this class cannot verify is that the APK actually came from
 * this project rather than an attacker with push access to the same repo;
 * that check is Android's, not ours — installing over an existing copy of
 * this app fails outright unless the new APK is signed with the exact same
 * key (see keystore.properties / release.yml), so a compromised release
 * still cannot silently replace what people already have installed.
 */
object UpdateChecker {

    private const val TAG_PREFIX = "v" // GitHub tags are v1.2.3; BuildConfig.VERSION_NAME is 1.2.3.
    private const val USER_AGENT = "Zajel-Android"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class CheckResult {
        data object UpToDate : CheckResult()
        data class Available(
            val versionName: String,
            val downloadUrl: String,
            val sizeBytes: Long,
            val notes: String
        ) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        if (BuildConfig.UPDATE_REPO.isBlank()) {
            return@withContext CheckResult.Error("لم يُضبط مستودع التحديثات لهذا البناء")
        }
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext CheckResult.Error("تعذّر الاتصال بخادم التحديثات (${response.code})")
                }
                val json = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
                val tag = json.optString("tag_name").removePrefix(TAG_PREFIX)
                if (tag.isBlank()) {
                    return@withContext CheckResult.Error("لا يوجد إصدار منشور بعد")
                }
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var apkSize = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name")
                        if (name.endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url")
                            apkSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }
                when {
                    !isNewer(tag, BuildConfig.VERSION_NAME) -> CheckResult.UpToDate
                    apkUrl.isNullOrBlank() -> CheckResult.Error("الإصدار $tag منشور بلا ملف APK")
                    else -> CheckResult.Available(tag, apkUrl, apkSize, json.optString("body"))
                }
            }
        } catch (e: Exception) {
            CheckResult.Error("تعذّر التحقق من التحديثات: ${e.message ?: e::class.simpleName}")
        }
    }

    /** True when [remote] outranks [current] as a dotted numeric version (1.10.2 > 1.9.9). A non-numeric part compares as 0. */
    internal fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    /** Streams [url] into cache/updates/update.apk, overwriting any previous download. */
    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit): DownloadResult =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val outFile = File(dir, "update.apk")
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext DownloadResult.Error("فشل تنزيل التحديث (${response.code})")
                    }
                    val body = response.body ?: return@withContext DownloadResult.Error("لا محتوى للتنزيل")
                    val total = body.contentLength()
                    var readSoFar = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(outFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n == -1) break
                                output.write(buffer, 0, n)
                                readSoFar += n
                                if (total > 0) onProgress(readSoFar.toFloat() / total.toFloat())
                            }
                        }
                    }
                }
                DownloadResult.Success(outFile)
            } catch (e: Exception) {
                DownloadResult.Error("فشل تنزيل التحديث: ${e.message ?: e::class.simpleName}")
            }
        }

    /** Android 8+: whether this app currently holds the per-app "install unknown apps" toggle. */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** The one Settings screen where the user grants that toggle for this app specifically. */
    fun installPermissionSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Hands [apkFile] to the system package installer UI. Caller must have checked [canInstallPackages] first. */
    fun install(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
