package com.securemessenger.app.network

import com.securemessenger.app.BuildConfig
import com.securemessenger.core.B64
import com.securemessenger.core.crypto.LibsodiumWrapper
import com.securemessenger.core.crypto.MailboxToken
import com.securemessenger.core.net.DirectoryProtocol
import com.securemessenger.core.net.Envelopes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end verification of the username-directory path, run on a real
 * device against the **actually deployed directory service** — not a mock.
 * Mirrors [RelayRoundtripTest]'s shape and reasoning (raw HTTP against the
 * real service, `assumeTrue`-skipped on a build with no directory compiled
 * in). Deliberately does NOT re-check pure signing-payload/verification
 * logic already covered by [DirectoryProtocol]'s own unit tests — this file
 * is about what only the real network round trip can prove: the server
 * actually enforces username uniqueness, actually 404s an unregistered
 * recipient, actually reads destructively, and actually gates fetch on a
 * live signature rather than mere knowledge of the mailbox id.
 */
class IntroductionRoundtripTest {

    private val directoryUrl = BuildConfig.DIRECTORY_URL.trimEnd('/')
    private val http = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    @Before
    fun installPlatform() {
        // Real libsodium for real Ed25519/X25519, same as every other
        // instrumented test in this module — there is no mock for it.
        com.securemessenger.core.Platform.installSodium(
            com.goterl.lazysodium.LazySodiumAndroid(com.goterl.lazysodium.SodiumAndroid())
        )
    }

    private data class TestIdentity(
        val identityPublic: ByteArray,
        val identitySecret: ByteArray,
        val signingPublic: ByteArray,
        val signingSecret: ByteArray,
        val username: String
    )

    /** A fresh identity, claimed against the real directory. */
    private fun freshClaimedIdentity(label: String): TestIdentity {
        val (identityPublic, identitySecret) = LibsodiumWrapper.generateKeyPair()
        val (signingPublic, signingSecret) = LibsodiumWrapper.generateSigningKeyPair()
        val username = "t${label}${System.nanoTime()}".take(20).lowercase()
        val timestamp = System.currentTimeMillis()
        val payload = DirectoryProtocol.claimSigningPayload(username, identityPublic, signingPublic, timestamp)
        val signature = LibsodiumWrapper.signDetached(payload, signingSecret)
        val response = post(
            "/claim",
            JSONObject().apply {
                put("username", username)
                put("identityPublicKey", B64.toHex(identityPublic))
                put("signingPublicKey", B64.toHex(signingPublic))
                put("timestamp", timestamp)
                put("signature", B64.toHex(signature))
            }.toString()
        )
        assertEquals("claim of a fresh username must succeed", 204, response.code)
        return TestIdentity(identityPublic, identitySecret, signingPublic, signingSecret, username)
    }

    private data class RawResponse(val code: Int, val body: String)

    private fun post(path: String, body: String): RawResponse {
        val request = Request.Builder().url("$directoryUrl$path").post(body.toRequestBody(jsonType)).build()
        http.newCall(request).execute().use { return RawResponse(it.code, it.body?.string() ?: "") }
    }

    private fun get(path: String): RawResponse {
        val request = Request.Builder().url("$directoryUrl$path").get().build()
        http.newCall(request).execute().use { return RawResponse(it.code, it.body?.string() ?: "") }
    }

    @Test
    fun claimedUsername_resolvesViaLookup() {
        assumeTrue("no directory compiled into this build", directoryUrl.isNotBlank())
        val identity = freshClaimedIdentity("lookup")

        val response = get("/u/${identity.username}")
        assertEquals(200, response.code)
        val json = JSONObject(response.body)
        assertEquals(B64.toHex(identity.identityPublic), json.getString("identityPublicKey"))
        assertEquals(B64.toHex(identity.signingPublic), json.getString("signingPublicKey"))
    }

    @Test
    fun lookingUpAnUnclaimedUsername_returns404() {
        assumeTrue("no directory compiled into this build", directoryUrl.isNotBlank())
        val response = get("/u/definitely-not-claimed-${System.nanoTime()}")
        assertEquals(404, response.code)
    }

    @Test
    fun claimingAnAlreadyTakenUsername_isRejected() {
        assumeTrue("no directory compiled into this build", directoryUrl.isNotBlank())
        val first = freshClaimedIdentity("taken")

        val (impostorIdentity, _) = LibsodiumWrapper.generateKeyPair()
        val (impostorSigningPublic, impostorSigningSecret) = LibsodiumWrapper.generateSigningKeyPair()
        val timestamp = System.currentTimeMillis()
        val payload = DirectoryProtocol.claimSigningPayload(first.username, impostorIdentity, impostorSigningPublic, timestamp)
        val response = post(
            "/claim",
            JSONObject().apply {
                put("username", first.username)
                put("identityPublicKey", B64.toHex(impostorIdentity))
                put("signingPublicKey", B64.toHex(impostorSigningPublic))
                put("timestamp", timestamp)
                put("signature", B64.toHex(LibsodiumWrapper.signDetached(payload, impostorSigningSecret)))
            }.toString()
        )
        assertEquals(409, response.code)
        assertEquals("taken", JSONObject(response.body).optString("reason"))

        // The original claim must survive untouched.
        val lookup = get("/u/${first.username}")
        assertEquals(B64.toHex(first.identityPublic), JSONObject(lookup.body).getString("identityPublicKey"))
    }

    /** Full client-shaped round trip: build+seal+sign exactly as [SecureMessagingClient.sendConnectionRequest] does, deposit, fetch, unseal+verify exactly as [SecureMessagingClient.handleIntroRequest] does. */
    @Test
    fun introduction_depositAndFetchRoundTripsAndVerifies() {
        assumeTrue("no directory compiled into this build", directoryUrl.isNotBlank())
        val sender = freshClaimedIdentity("sender")
        val recipient = freshClaimedIdentity("recipient")

        val pairSecret = MailboxToken.newPairSecret()
        val timestamp = System.currentTimeMillis()
        val inner = DirectoryProtocol.buildSelfIntroduction(
            senderUserId = "sender-user-id",
            senderUsername = sender.username,
            senderIdentityPublicKey = sender.identityPublic,
            senderSigningPublicKey = sender.signingPublic,
            addresseeIdentityPublicKey = recipient.identityPublic,
            pairSecret = pairSecret,
            directAddress = null,
            timestampMillis = timestamp,
            sign = { payload -> LibsodiumWrapper.signDetached(payload, sender.signingSecret) }
        )
        val outer = Envelopes.seal(Envelopes.TYPE_INTRO_REQUEST, inner, recipient.identityPublic)
        val blob = B64.encode(outer.toString().toByteArray(Charsets.UTF_8))

        val depositResponse = post(
            "/introductions/deposit",
            JSONObject().apply {
                put("recipientIdentityPublicKey", B64.toHex(recipient.identityPublic))
                put("blob", blob)
            }.toString()
        )
        assertEquals("deposit to a registered identity must succeed", 204, depositResponse.code)

        // Recipient's side: fetch with a live signed proof.
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val fetchTimestamp = System.currentTimeMillis()
        val fetchPayload = DirectoryProtocol.introFetchSigningPayload(nonce, fetchTimestamp)
        val fetchResponse = post(
            "/introductions/fetch",
            JSONObject().apply {
                put("identityPublicKey", B64.toHex(recipient.identityPublic))
                put("nonce", B64.toHex(nonce))
                put("timestamp", fetchTimestamp)
                put("signature", B64.toHex(LibsodiumWrapper.signDetached(fetchPayload, recipient.signingSecret)))
            }.toString()
        )
        assertEquals(200, fetchResponse.code)
        val items = JSONObject(fetchResponse.body).getJSONArray("items")
        assertEquals(1, items.length())

        // Full client-side unseal, exactly as handleIntroductionBlob does.
        val collectedOuter = JSONObject(String(B64.decode(items.getJSONObject(0).getString("b")), Charsets.UTF_8))
        assertEquals(Envelopes.TYPE_INTRO_REQUEST, collectedOuter.getString("type"))
        val collectedInner = Envelopes.open(collectedOuter, recipient.identityPublic, recipient.identitySecret)
        val parsed = DirectoryProtocol.parseSelfIntroduction(collectedInner)
        assertNotNull("collected payload must parse back to a SelfIntroduction", parsed)
        parsed!!
        assertEquals(sender.username, parsed.senderUsername)
        assertTrue(pairSecret.contentEquals(parsed.pairSecret))
        assertTrue(
            "must verify against the sender's REGISTERED signing key",
            DirectoryProtocol.verifySelfIntroduction(
                parsed, ourIdentityPublicKey = recipient.identityPublic, trustedSigningPublicKey = sender.signingPublic
            )
        )
    }

    @Test
    fun depositingForAnUnregisteredIdentity_isRejected() {
        assumeTrue("no directory compiled into this build", directoryUrl.isNotBlank())
        val (neverRegisteredIdentity, _) = LibsodiumWrapper.generateKeyPair()
        val response = post(
            "/introductions/deposit",
            JSONObject().apply {
                put("recipientIdentityPublicKey", B64.toHex(neverRegisteredIdentity))
                put("blob", B64.encode("irrelevant".toByteArray()))
            }.toString()
        )
        assertEquals(404, response.code)
    }

    @Test
    fun introductionFetchIsDestructive_secondReadFindsNothing() {
        assumeTrue("no directory compiled into this build", directoryUrl.isNotBlank())
        val recipient = freshClaimedIdentity("destructive")
        post(
            "/introductions/deposit",
            JSONObject().apply {
                put("recipientIdentityPublicKey", B64.toHex(recipient.identityPublic))
                put("blob", B64.encode("once".toByteArray()))
            }.toString()
        )

        fun fetchOnce(): Int {
            val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val timestamp = System.currentTimeMillis()
            val payload = DirectoryProtocol.introFetchSigningPayload(nonce, timestamp)
            val response = post(
                "/introductions/fetch",
                JSONObject().apply {
                    put("identityPublicKey", B64.toHex(recipient.identityPublic))
                    put("nonce", B64.toHex(nonce))
                    put("timestamp", timestamp)
                    put("signature", B64.toHex(LibsodiumWrapper.signDetached(payload, recipient.signingSecret)))
                }.toString()
            )
            assertEquals(200, response.code)
            return JSONObject(response.body).getJSONArray("items").length()
        }

        assertEquals(1, fetchOnce())
        // Nothing retained after delivery — same destructive-read guarantee as the blind relay.
        assertEquals(0, fetchOnce())
    }

    @Test
    fun fetchingWithSomeoneElsesSignature_isRejected() {
        assumeTrue("no directory compiled into this build", directoryUrl.isNotBlank())
        val recipient = freshClaimedIdentity("wrongsig")
        val impostor = freshClaimedIdentity("impostor")

        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val timestamp = System.currentTimeMillis()
        val payload = DirectoryProtocol.introFetchSigningPayload(nonce, timestamp)
        // Claims to be `recipient` but signs with `impostor`'s key.
        val response = post(
            "/introductions/fetch",
            JSONObject().apply {
                put("identityPublicKey", B64.toHex(recipient.identityPublic))
                put("nonce", B64.toHex(nonce))
                put("timestamp", timestamp)
                put("signature", B64.toHex(LibsodiumWrapper.signDetached(payload, impostor.signingSecret)))
            }.toString()
        )
        assertEquals(400, response.code)
    }
}
