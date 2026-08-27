package com.securemessenger.app.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * AppSettings - central, persistent store for user-facing app options.
 *
 * All values are persisted through [SecurePreferences] (EncryptedSharedPreferences)
 * so the choices survive app restarts and take effect across the whole app
 * (login flow, screen security, message TTL, stealth mode).
 */
object AppSettings {

    private const val KEY_AUTODESTRUCT = "setting_autodestruct_enabled"
    private const val KEY_AUTODESTRUCT_SECONDS = "setting_autodestruct_seconds"
    private const val KEY_STEALTH = "setting_stealth_enabled"
    private const val KEY_USERNAME = "profile_username"
    private const val KEY_USERNAME_CLAIMED = "profile_username_claimed_remotely"
    private const val KEY_ACCESS_CODE = "calculator_access_code"
    private const val KEY_DURESS_CODE = "calculator_duress_code"
    private const val KEY_THEME_MODE = "setting_theme_mode"
    private const val KEY_COVER_TRAFFIC = "setting_cover_traffic"
    private const val KEY_RELAY_ENABLED = "setting_relay_enabled"
    private const val KEY_PENDING_PAIR_SECRETS = "pending_pair_secrets"
    private const val KEY_DIRECT_ADDRESSES = "peer_direct_addresses"
    private const val KEY_BACKGROUND_DELIVERY = "setting_background_delivery"
    private const val KEY_NOTIFICATION_MODE = "setting_notification_mode"

    const val DEFAULT_AUTODESTRUCT_SECONDS = 60

    // ==================== Calculator disguise access/duress codes ====================
    //
    // Codes are stored ONLY as a salted hash ("v1:salt:hash"), never reversibly,
    // and there is deliberately NO built-in default — a code must be chosen at
    // setup, so the app never ships with a guessable unlock code.

    /** True once an unlock code has been chosen (the gate never matches while unset). */
    fun hasAccessCode(context: Context): Boolean =
        !SecurePreferences.getString(context, KEY_ACCESS_CODE, null).isNullOrBlank()

    fun setAccessCode(context: Context, code: String) {
        SecurePreferences.putString(context, KEY_ACCESS_CODE, hashCode(code))
        // A real code now exists — the calculator's fresh-install bootstrap
        // path (see isBootstrapArmed) has done its one job and must not stay
        // usable, or a wipe later (which clears the code above right back to
        // "unset") would silently re-open it.
        disarmBootstrap(context)
    }

    /** Constant-time check of a typed number against the stored access code. */
    fun verifyAccessCode(context: Context, input: String): Boolean =
        verifyStoredCode(context, KEY_ACCESS_CODE, input) { setAccessCode(context, it) }

    // ==================== Duress code ====================

    /**
     * An optional, separate digit sequence: typed on the calculator instead of
     * the real access code, it silently wipes all messenger data in the
     * background instead of revealing anything — protects against being forced
     * to unlock under duress. Unset until deliberately chosen; never matches
     * while unset.
     */
    fun hasDuressCode(context: Context): Boolean =
        !SecurePreferences.getString(context, KEY_DURESS_CODE, null).isNullOrBlank()

    fun setDuressCode(context: Context, code: String?) {
        if (code.isNullOrBlank()) SecurePreferences.remove(context, KEY_DURESS_CODE)
        else SecurePreferences.putString(context, KEY_DURESS_CODE, hashCode(code))
    }

    fun verifyDuressCode(context: Context, input: String): Boolean =
        verifyStoredCode(context, KEY_DURESS_CODE, input) { setDuressCode(context, it) }

    // ==================== Calculator bootstrap gate ====================
    //
    // CalculatorScreen's "no access code chosen yet -> a plain number opens
    // Setup" path exists only so a fresh install has some way in. Gating it
    // on hasAccessCode() alone is not safe: a full or duress wipe clears the
    // access code too, which would silently re-open that exact same path —
    // so someone probing "the calculator" right after a duress wipe just
    // ran would have the app reveal "generate your encryption keys" the
    // moment they typed a few more digits. Precisely the tell the duress
    // feature exists to prevent.
    //
    // This flag answers a different question than hasAccessCode(): not "is
    // a code set right now" but "has this install ever been through Setup."
    // It lives in its own plain SharedPreferences file, deliberately NOT
    // SecurePreferences — clearAll() (called by every wipe, see
    // SecureRepository.wipeAllData) must never touch it, or it would just
    // reset to its default-armed state on every wipe and solve nothing. It
    // holds no secret (its own existence reveals nothing SettingUp a
    // calculator icon doesn't already), so it needs no encryption.

    private const val BOOTSTRAP_PREFS_NAME = "calc_prefs"
    private const val KEY_BOOTSTRAP_ARMED = "setup_bootstrap_armed"

    private fun bootstrapPrefs(context: Context) =
        context.getSharedPreferences(BOOTSTRAP_PREFS_NAME, Context.MODE_PRIVATE)

    /** True while the fresh-install bootstrap path should still work. Defaults true (a never-before-seen install has no reason to be locked out of Setup). */
    fun isBootstrapArmed(context: Context): Boolean =
        bootstrapPrefs(context).getBoolean(KEY_BOOTSTRAP_ARMED, true)

    /** Called the moment a real access code is chosen (see setAccessCode) — the bootstrap path has done its one job. */
    fun disarmBootstrap(context: Context) {
        bootstrapPrefs(context).edit().putBoolean(KEY_BOOTSTRAP_ARMED, false).apply()
    }

    /**
     * Re-opens the bootstrap path. Only ever called from [SecretCodeReceiver],
     * itself only reachable by deliberately dialing a specific out-of-band
     * code in the phone app — never from anything reachable on the visible
     * calculator screen, so this can't become a second way past a real code.
     */
    fun rearmBootstrap(context: Context) {
        bootstrapPrefs(context).edit().putBoolean(KEY_BOOTSTRAP_ARMED, true).apply()
    }

    // ==================== Calculator brute-force throttle ====================
    //
    // Persisted (not just in-memory Compose state in CalculatorScreen) so
    // swiping the app away from Recents and reopening it — an ordinary,
    // tool-free action requiring no exploit — can't reset an accumulating
    // lockout back to zero. Stored in SecurePreferences like the codes
    // themselves: resetting on a wipe is fine, since a wipe is the rare,
    // deliberate event this throttle isn't meant to survive anyway (the
    // codes it was protecting are gone too).

    private const val KEY_CODE_MISS_STREAK = "calc_code_miss_streak"
    private const val KEY_CODE_LOCKED_UNTIL = "calc_code_locked_until"

    fun codeMissStreak(context: Context): Int =
        SecurePreferences.getInt(context, KEY_CODE_MISS_STREAK, 0)

    fun setCodeMissStreak(context: Context, streak: Int) =
        SecurePreferences.putInt(context, KEY_CODE_MISS_STREAK, streak)

    /** Epoch millis until which further code checks are paused — the calculator itself keeps working normally throughout. */
    fun codeCheckLockedUntil(context: Context): Long =
        SecurePreferences.getLong(context, KEY_CODE_LOCKED_UNTIL, 0L)

    fun setCodeCheckLockedUntil(context: Context, until: Long) =
        SecurePreferences.putLong(context, KEY_CODE_LOCKED_UNTIL, until)

    // ---- salted-hash helpers ----

    private fun hashCode(code: String): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hash = com.securemessenger.core.crypto.LibsodiumWrapper.blake2b(
            salt + code.toByteArray(Charsets.UTF_8), length = 32
        )
        fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        return "v1:" + b64(salt) + ":" + b64(hash)
    }

    // Fixed, non-secret salt used only to burn the same amount of hashing
    // time as a real check when nothing is stored — see verifyStoredCode.
    // It is never compared against anything, so it needs no randomness.
    private val DUMMY_SALT = ByteArray(16)

    private fun verifyStoredCode(
        context: Context,
        key: String,
        input: String,
        migrate: (String) -> Unit
    ): Boolean {
        val stored = SecurePreferences.getString(context, key, null)?.takeIf { it.isNotBlank() }
        if (stored == null) {
            // Do the same shape of work (one BLAKE2b call) a real check would
            // do, even though there's nothing to compare against. Without
            // this, checking a code that isn't configured (most commonly: no
            // duress code set) returns measurably faster than checking one
            // that is — a timing measurement alone would then reveal WHETHER
            // this phone has a duress/panic-wipe code at all, before ever
            // guessing at its value.
            com.securemessenger.core.crypto.LibsodiumWrapper.blake2b(
                DUMMY_SALT + input.toByteArray(Charsets.UTF_8), length = 32
            )
            return false
        }
        if (stored.startsWith("v1:")) {
            val parts = stored.split(":")
            if (parts.size != 3) return false
            val salt = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
            val expected = android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP)
            val actual = com.securemessenger.core.crypto.LibsodiumWrapper.blake2b(
                salt + input.toByteArray(Charsets.UTF_8), length = 32
            )
            return com.securemessenger.core.crypto.LibsodiumWrapper.constantTimeCompare(expected, actual)
        }
        // Legacy plaintext code from before hashing: constant-time compare, and
        // migrate it to a salted hash on first successful use.
        val matches = com.securemessenger.core.crypto.LibsodiumWrapper.constantTimeCompare(
            stored.toByteArray(Charsets.UTF_8), input.toByteArray(Charsets.UTF_8)
        )
        if (matches) migrate(input)
        return matches
    }

    // ==================== Self-destructing messages ====================

    fun isAutoDestructEnabled(context: Context): Boolean =
        SecurePreferences.getBoolean(context, KEY_AUTODESTRUCT, false)

    fun setAutoDestructEnabled(context: Context, enabled: Boolean) =
        SecurePreferences.putBoolean(context, KEY_AUTODESTRUCT, enabled)

    fun getAutoDestructSeconds(context: Context): Int =
        SecurePreferences.getInt(context, KEY_AUTODESTRUCT_SECONDS, DEFAULT_AUTODESTRUCT_SECONDS)

    fun setAutoDestructSeconds(context: Context, seconds: Int) =
        SecurePreferences.putInt(context, KEY_AUTODESTRUCT_SECONDS, seconds)

    /**
     * The TTL (in seconds) to apply to a newly sent message, or null if
     * self-destructing messages are disabled.
     */
    fun messageTtlSeconds(context: Context): Int? =
        if (isAutoDestructEnabled(context)) getAutoDestructSeconds(context) else null

    // ==================== Stealth mode ====================

    fun isStealthEnabled(context: Context): Boolean =
        SecurePreferences.getBoolean(context, KEY_STEALTH, false)

    fun setStealthEnabled(context: Context, enabled: Boolean) =
        SecurePreferences.putBoolean(context, KEY_STEALTH, enabled)

    // ==================== Username ====================

    fun getUsername(context: Context): String? =
        SecurePreferences.getString(context, KEY_USERNAME, null)?.takeIf { it.isNotBlank() }

    fun setUsername(context: Context, username: String) =
        SecurePreferences.putString(context, KEY_USERNAME, username.trim().lowercase())

    fun clearUsername(context: Context) =
        SecurePreferences.remove(context, KEY_USERNAME)

    /**
     * Whether our local username has actually been claimed on the directory
     * service yet — separate from [getUsername], which is set locally at
     * setup regardless of connectivity. False either means the claim hasn't
     * been attempted (directory feature not configured), or it was attempted
     * and failed (offline at setup time) — [SecureMessagingClient.connect]
     * retries opportunistically on every reveal while this stays false, so a
     * user who finished setup offline still ends up searchable once they're
     * back online, without needing to redo anything.
     */
    fun isUsernameClaimedRemotely(context: Context): Boolean =
        SecurePreferences.getBoolean(context, KEY_USERNAME_CLAIMED, false)

    fun setUsernameClaimedRemotely(context: Context, claimed: Boolean) =
        SecurePreferences.putBoolean(context, KEY_USERNAME_CLAIMED, claimed)

    // ==================== Auto-destruct label mapping ====================

    /** Ordered list of the labels shown in the settings dropdown. */
    val autoDestructLabels: List<String> =
        listOf("30 ثانية", "1 دقيقة", "5 دقائق", "1 ساعة")

    private val labelToSeconds: Map<String, Int> = mapOf(
        "30 ثانية" to 30,
        "1 دقيقة" to 60,
        "5 دقائق" to 300,
        "1 ساعة" to 3600
    )

    fun labelToSeconds(label: String): Int =
        labelToSeconds[label] ?: DEFAULT_AUTODESTRUCT_SECONDS

    fun secondsToLabel(seconds: Int): String =
        labelToSeconds.entries.firstOrNull { it.value == seconds }?.key ?: "1 دقيقة"

    // ==================== Theme ====================

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    // Reactive so flipping the switch in Settings recolors every open screen
    // immediately — no activity restart needed.
    private val _themeMode: MutableStateFlow<ThemeMode> by lazy {
        val ctx = com.securemessenger.app.SecureMessengerApp.instance
        val stored = SecurePreferences.getString(ctx, KEY_THEME_MODE, null)
        val initial = stored?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
        MutableStateFlow(initial)
    }
    val themeMode: StateFlow<ThemeMode> get() = _themeMode

    fun setThemeMode(context: Context, mode: ThemeMode) {
        SecurePreferences.putString(context, KEY_THEME_MODE, mode.name)
        _themeMode.value = mode
    }

    // ==================== Relay ====================

    /**
     * Cover traffic: send indistinguishable dummy messages at random intervals
     * and delay real ones slightly, so the relay can't infer a conversation
     * from *when* traffic appears. Costs battery and data continuously, even
     * with no real messaging, which is why it is a switch and not a constant.
     */
    fun isCoverTrafficEnabled(context: Context): Boolean =
        SecurePreferences.getBoolean(context, KEY_COVER_TRAFFIC, true)

    fun setCoverTrafficEnabled(context: Context, enabled: Boolean) =
        SecurePreferences.putBoolean(context, KEY_COVER_TRAFFIC, enabled)

    /** Whether to fall back to the blind relay at all when a contact isn't on the local network. */
    fun isRelayEnabled(context: Context): Boolean =
        SecurePreferences.getBoolean(context, KEY_RELAY_ENABLED, true)

    fun setRelayEnabled(context: Context, enabled: Boolean) =
        SecurePreferences.putBoolean(context, KEY_RELAY_ENABLED, enabled)

    // ==================== Pending pair secrets ====================
    //
    // A pair secret is minted when we DISPLAY a QR code — before we know who
    // will scan it. It goes out inside the QR (never over a network), and we
    // hold it here until the scanner's first message lands in the mailbox that
    // secret addresses; at that point it is bound to a real contact and dropped
    // from this list. Until then we have to listen on every outstanding one.

    private const val MAX_PENDING_SECRETS = 5
    private const val PENDING_SECRET_TTL_MS = 24 * 60 * 60 * 1000L

    /** Record a freshly-minted secret as "issued, not yet claimed". */
    fun addPendingPairSecret(context: Context, secretHex: String) {
        val kept = pendingPairSecrets(context).filter { it != secretHex }
        val now = System.currentTimeMillis()
        val array = org.json.JSONArray()
        array.put(org.json.JSONObject().put("s", secretHex).put("t", now))
        // Newest first, oldest dropped once we exceed the cap.
        kept.take(MAX_PENDING_SECRETS - 1).forEach { hex ->
            array.put(org.json.JSONObject().put("s", hex).put("t", pendingIssuedAt(context, hex) ?: now))
        }
        SecurePreferences.putString(context, KEY_PENDING_PAIR_SECRETS, array.toString())
    }

    /** Every still-valid issued secret, newest first. Expired entries are pruned on read. */
    fun pendingPairSecrets(context: Context): List<String> = readPending(context).map { it.first }

    private fun pendingIssuedAt(context: Context, hex: String): Long? =
        readPending(context).firstOrNull { it.first == hex }?.second

    private fun readPending(context: Context): List<Pair<String, Long>> {
        val raw = SecurePreferences.getString(context, KEY_PENDING_PAIR_SECRETS, null) ?: return emptyList()
        val cutoff = System.currentTimeMillis() - PENDING_SECRET_TTL_MS
        return try {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val hex = obj.optString("s").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val issued = obj.optLong("t")
                if (issued < cutoff) null else hex to issued
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The secret has been bound to a real contact — stop listening for it as an unclaimed one. */
    fun removePendingPairSecret(context: Context, secretHex: String) {
        val remaining = readPending(context).filter { it.first != secretHex }
        val array = org.json.JSONArray()
        remaining.forEach { (hex, issued) ->
            array.put(org.json.JSONObject().put("s", hex).put("t", issued))
        }
        SecurePreferences.putString(context, KEY_PENDING_PAIR_SECRETS, array.toString())
    }

    // ==================== Background delivery ====================
    //
    // The original design ran the transport ONLY while the disguise was
    // revealed ("يشتغل ويطفى حسب الاستخدام"), which meant a message could only
    // be delivered if both people happened to have the messenger open at the
    // same moment. Nothing was lost — the sender's outbox and the relay's 48h
    // retention both hold it — but "sent hours ago, arrives when you next open
    // the app" is not what anyone means by a messenger.
    //
    // Switching this on keeps the transport alive in a foreground service, so
    // an incoming message lands (and notifies) while the app is closed.
    //
    // THE TRADE-OFF, STATED PLAINLY: Android does not allow a long-running
    // background service without a visible, non-dismissible notification. That
    // notification is disguised to match the calculator identity, but it is
    // still a permanent sign the app is running — which is a real, if small,
    // weakening of the disguise. Hence a switch rather than a constant.

    fun isBackgroundDeliveryEnabled(context: Context): Boolean =
        SecurePreferences.getBoolean(context, KEY_BACKGROUND_DELIVERY, false)

    fun setBackgroundDeliveryEnabled(context: Context, enabled: Boolean) =
        SecurePreferences.putBoolean(context, KEY_BACKGROUND_DELIVERY, enabled)

    /**
     * How much an arriving message is allowed to reveal on the lock screen.
     *
     * A notification reading "محمد: نراك الساعة ٨" on a locked phone undoes a
     * great deal of what the rest of this app is for, so the default says only
     * that *something* arrived.
     */
    enum class NotificationMode {
        /** No notification at all — the message still arrives silently. */
        SILENT,
        /** "لديك رسالة جديدة" and nothing else: no sender, no content. */
        NEUTRAL,
        /** Sender name plus a preview of the text. */
        FULL
    }

    fun notificationMode(context: Context): NotificationMode =
        when (SecurePreferences.getString(context, KEY_NOTIFICATION_MODE, null)) {
            "silent" -> NotificationMode.SILENT
            "full" -> NotificationMode.FULL
            else -> NotificationMode.NEUTRAL
        }

    fun setNotificationMode(context: Context, mode: NotificationMode) =
        SecurePreferences.putString(
            context, KEY_NOTIFICATION_MODE,
            when (mode) {
                NotificationMode.SILENT -> "silent"
                NotificationMode.NEUTRAL -> "neutral"
                NotificationMode.FULL -> "full"
            }
        )

    // ==================== Direct peer addresses ====================
    //
    // mDNS/NSD is the *preferred* way to find a contact on the LAN, but it is
    // also the single most fragile link in the chain: plenty of consumer
    // routers drop multicast between clients ("AP isolation"), guest networks
    // block it outright, and Android's NsdManager resolve step fails often
    // enough on its own. When it does, two devices sitting on the same Wi-Fi
    // with a perfectly usable route between them could still never find each
    // other, and the message would silently fall through to the relay (or
    // nowhere at all).
    //
    // A remembered "host:port" is the deterministic fallback: an address we
    // either learned from a successful connection, read out of a scanned QR
    // code, or the user typed in by hand. It is a hint, never a trust anchor —
    // whoever answers at that address still has to satisfy the exact same
    // pinned-identity challenge/response as a peer found via mDNS, so pointing
    // this at the wrong machine can fail to connect but can never leak or
    // mis-deliver anything.

    /** contactId -> "host:port" that worked (or was supplied) for that contact. */
    private fun readDirectAddresses(context: Context): MutableMap<String, String> {
        val raw = SecurePreferences.getString(context, KEY_DIRECT_ADDRESSES, null) ?: return mutableMapOf()
        return try {
            val obj = org.json.JSONObject(raw)
            val out = mutableMapOf<String, String>()
            obj.keys().forEach { key -> obj.optString(key).takeIf { it.isNotBlank() }?.let { out[key] = it } }
            out
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    /** The remembered address for [contactId], or null if we've never had one. */
    fun directAddress(context: Context, contactId: String): String? =
        readDirectAddresses(context)[contactId]

    /**
     * Remember (or clear, with a null/blank [hostPort]) where this contact can
     * be reached directly. Accepts "host" alone — the default listening port is
     * filled in by the caller that dials it.
     */
    fun setDirectAddress(context: Context, contactId: String, hostPort: String?) {
        val map = readDirectAddresses(context)
        val cleaned = hostPort?.trim()?.takeIf { it.isNotBlank() }
        if (cleaned == null) map.remove(contactId) else map[contactId] = cleaned
        SecurePreferences.putString(context, KEY_DIRECT_ADDRESSES, org.json.JSONObject(map as Map<*, *>).toString())
    }
}
