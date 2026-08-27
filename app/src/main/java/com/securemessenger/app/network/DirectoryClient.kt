package com.securemessenger.app.network

import android.util.Log
import com.securemessenger.core.B64
import com.securemessenger.core.net.DirectoryProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val TAG = "DirectoryClient"

/**
 * The one place the app talks to `directory/` — claiming a username,
 * resolving one, and depositing/collecting introductions. Deliberately
 * repository-agnostic (unlike [RelayClient], which owns subscription state):
 * every call here is a single request/response with no session to keep, so
 * the caller ([SecureMessagingClient]) supplies whatever key material a call
 * needs and this class never stores anything itself.
 *
 * This is a wholly separate, opt-in service from the blind relay — see
 * `directory/src/index.ts`'s module doc for exactly what it does and does not
 * learn. [isEnabled] follows the same "empty URL compiles it out entirely"
 * convention as [RelayClient.isEnabled].
 */
class DirectoryClient(
    private val baseUrl: String,
    private val http: OkHttpClient
) {
    private val random = java.security.SecureRandom()

    val isEnabled: Boolean get() = baseUrl.isNotBlank()

    sealed class ClaimResult {
        data object Success : ClaimResult()
        data object Taken : ClaimResult()
        data object AlreadyRegistered : ClaimResult()
        data object Error : ClaimResult()
    }

    /** Reserve [username] for the identity/signing keypair whose secret [sign] wraps. */
    suspend fun claim(
        username: String,
        identityPublicKey: ByteArray,
        signingPublicKey: ByteArray,
        sign: (ByteArray) -> ByteArray
    ): ClaimResult = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val payload = DirectoryProtocol.claimSigningPayload(username, identityPublicKey, signingPublicKey, timestamp)
        val body = JSONObject().apply {
            put("username", username)
            put("identityPublicKey", B64.toHex(identityPublicKey))
            put("signingPublicKey", B64.toHex(signingPublicKey))
            put("timestamp", timestamp)
            put("signature", B64.toHex(sign(payload)))
        }.toString()

        val response = postRaw("/claim", body) ?: return@withContext ClaimResult.Error
        when (response.code) {
            204 -> ClaimResult.Success
            409 -> when (response.reasonFromBody()) {
                "taken" -> ClaimResult.Taken
                else -> ClaimResult.AlreadyRegistered
            }
            else -> ClaimResult.Error
        }
    }

    sealed class LookupResult {
        data class Found(val username: String, val identityPublicKey: ByteArray, val signingPublicKey: ByteArray) : LookupResult()
        data object NotFound : LookupResult()
        data object Error : LookupResult()
    }

    suspend fun lookup(username: String): LookupResult = withContext(Dispatchers.IO) {
        val response = getRaw("/u/$username") ?: return@withContext LookupResult.Error
        when (response.code) {
            200 -> try {
                val json = JSONObject(response.bodyText)
                val identityPublicKey = B64.fromHex(json.getString("identityPublicKey"))
                val signingPublicKey = B64.fromHex(json.getString("signingPublicKey"))
                if (identityPublicKey == null || signingPublicKey == null) LookupResult.Error
                else LookupResult.Found(json.getString("username"), identityPublicKey, signingPublicKey)
            } catch (e: Exception) {
                LookupResult.Error
            }
            404 -> LookupResult.NotFound
            else -> LookupResult.Error
        }
    }

    /** Deposit a sealed [blob] (base64) into whoever owns [recipientIdentityPublicKey]'s introduction mailbox. False if they aren't registered, or on any error. */
    suspend fun depositIntroduction(recipientIdentityPublicKey: ByteArray, blob: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("recipientIdentityPublicKey", B64.toHex(recipientIdentityPublicKey))
                put("blob", blob)
            }.toString()
            postRaw("/introductions/deposit", body)?.code == 204
        }

    /**
     * Collect and forget every introduction waiting for [identityPublicKey] —
     * destructive on the server, same as [RelayClient]'s fetch. [sign] must
     * come from the same identity's signing secret; a mismatched key makes
     * every call 400.
     */
    suspend fun fetchIntroductions(identityPublicKey: ByteArray, sign: (ByteArray) -> ByteArray): List<String>? =
        withContext(Dispatchers.IO) {
            val nonce = ByteArray(16).also { random.nextBytes(it) }
            val timestamp = System.currentTimeMillis()
            val payload = DirectoryProtocol.introFetchSigningPayload(nonce, timestamp)
            val body = JSONObject().apply {
                put("identityPublicKey", B64.toHex(identityPublicKey))
                put("nonce", B64.toHex(nonce))
                put("timestamp", timestamp)
                put("signature", B64.toHex(sign(payload)))
            }.toString()

            val response = postRaw("/introductions/fetch", body) ?: return@withContext null
            if (response.code != 200) return@withContext null
            try {
                val items = JSONObject(response.bodyText).getJSONArray("items")
                (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optString("b")?.takeIf { b -> b.isNotBlank() } }
            } catch (e: Exception) {
                null
            }
        }

    // ---- transport ----

    private data class RawResponse(val code: Int, val bodyText: String) {
        fun reasonFromBody(): String? = try {
            JSONObject(bodyText).optString("reason").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun postRaw(path: String, body: String): RawResponse? = try {
        val request = Request.Builder().url("$baseUrl$path").post(body.toRequestBody(JSON_MEDIA_TYPE)).build()
        http.newCall(request).execute().use { response ->
            RawResponse(response.code, response.body?.string() ?: "")
        }
    } catch (e: Exception) {
        Log.w(TAG, "directory $path unreachable: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun getRaw(path: String): RawResponse? = try {
        val request = Request.Builder().url("$baseUrl$path").get().build()
        http.newCall(request).execute().use { response ->
            RawResponse(response.code, response.body?.string() ?: "")
        }
    } catch (e: Exception) {
        Log.w(TAG, "directory $path unreachable: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
