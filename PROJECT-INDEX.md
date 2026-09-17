# فهرس رموز المشروع (مولّد آلياً — قائم على regex/عمق الأقواس، **ليس تحليل AST كامل**)

مولّد بـ `scripts/gen-index-kotlin.mjs` — لا تُحرّره يدوياً. يُعاد توليده تلقائياً عند إيقاف/مسح/ضغط الجلسة (Stop hook)، أو يدوياً — شغّل `node scripts/gen-index-kotlin.mjs` مباشرة.
⚠ **هذا فهرس نصّي (regex + تتبّع عمق الأقواس)، وليس تحليل AST حقيقياً عبر مترجم Kotlin** (خلافاً لنسخة TypeScript/Python) — لأن الوصول لمترجم Kotlin برمجياً يتطلب JVM + إعداد classpath ثقيل غير متناسب هنا. النتيجة دقيقة غالباً لكنها قد تُخطئ في تواقيع معقّدة جداً (generics متداخلة، sealed hierarchies طويلة). يفهرس مستوى الملف والمستوى الأول داخل class/object/interface فقط (ليس دوال متداخلة أعمق).
ابحث هنا أولاً بـ Grep قبل فتح أي ملف مصدر كامل. سطر `يُستخدم في:` تحت أي class/object/interface = تطابق استيراد FQN فعلي، أفضل-جهد وليس مضموناً 100%.
⚠ بجانب اسم ملف = يتجاوز 300 سطر.

**136** ملف مفحوص، **2549** تعريفاً.


## app/

### app/src/androidTest/java/com/securemessenger/app/crypto/MetadataPrivacyTest.kt (65 سطر)  [package com.securemessenger.app.crypto]
- L20 `class MetadataPrivacyTest` — Verifies the metadata-privacy primitives: fixed-size padding (hides message
  - L23 `@Test fun padding_roundTripsForVariousLengths ()`
  - L34 `@Test fun padding_hidesLength_shortMessagesShareBucketSize ()`
  - L43 `@Test fun sealedBox_onlyRecipientCanOpen ()`

### app/src/androidTest/java/com/securemessenger/app/crypto/PqKemTest.kt (36 سطر)  [package com.securemessenger.app.crypto]
- L13 `class PqKemTest` — Verifies the post-quantum ML-KEM-768 primitive: encapsulate/decapsulate
  - L16 `@Test fun encapsulateDecapsulate_agreeOnSecret ()`
  - L26 `@Test fun wrongKey_doesNotRecoverSecret ()`

### app/src/androidTest/java/com/securemessenger/app/crypto/RatchetRoundtripTest.kt (265 سطر)  [package com.securemessenger.app.crypto]
- L16 `class RatchetRoundtripTest` — Instrumented (on-device) verification of the messaging crypto path — libsodium
  - L18 `private fun newInitiatorResponder (useOtk: Boolean): Pair<SignalProtocol, SignalProtocol>`
  - L44 `@Test fun firstMessageDecrypts_withoutOtk ()`
  - L51 `@Test fun firstMessageDecrypts_withOtk ()`
  - L58 `@Test fun manyMessagesInOrder ()`
  - L67 `@Test fun outOfOrderAndSkippedMessages ()`
  - L88 `@Test fun hybridPostQuantumHandshake ()` — Hybrid X25519 + ML-KEM handshake: both sides mix the same PQ secret into
  - L131 `@Test fun bidirectionalConversation ()`
  - L145 `@Test fun manyBidirectionalRounds_exerciseDhRatchet ()` — Many alternating rounds. Each direction change makes the peer publish a new
  - L161 `@Test fun outOfOrderAcrossDhRatchet ()` — A message from an *old* sending chain arrives after the receiver has already
  - L184 `@Test fun sessionStateSurvivesExportImport ()` — Serializing a live session (exportState) and restoring it into a fresh
  - L228 `@Test fun receiverRebuildsSessionAfterRestart ()` — Simulates the receiver restarting: its in-memory session is gone, so it

### app/src/androidTest/java/com/securemessenger/app/crypto/SignalProtocolTest.kt (74 سطر)  [package com.securemessenger.app.crypto]
- L13 `class SignalProtocolTest` — Tests X3DH key agreement symmetry between initiator and responder.
  - L16 `@Test fun x3dh_initiatorAndResponderDeriveSameRootKey ()`
  - L44 `@Test fun signalProtocol_encryptDecryptRoundtrip ()`

### app/src/androidTest/java/com/securemessenger/app/data/ConversationStorageTest.kt (198 سطر)  [package com.securemessenger.app.data]
- L36 `@RunWith(AndroidJUnit4::class) class ConversationStorageTest` — A message arrives, is stored, and shows up in the conversation — the one
  - L38 `private lateinit var context : Context`
  - L39 `private lateinit var repository : SecureRepository`
  - L40 `private val passphrase`
  - L43 `private val theirKey`
  - L44 `private val them`
  - L47 `@Before fun setUp ()`
  - L60 `@After fun tearDown ()`
  - L66 `@Test fun aReceivedMessageAppearsInTheConversationWithThatContact ()`
  - L83 `@Test fun bothDirectionsLandInOneThread ()`
  - L103 `@Test fun theChatListSeesTheSameConversation ()`
  - L130 `@Test fun openingTheConversationClearsItsUnreadCount ()`
  - L147 `@Test fun aDelayedMessageSitsWhereItWasSent_notWhereItArrived ()`
  - L176 `@Test fun theWindowReturnsTheNewestMessagesAndCanBeWidened ()`

### app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt (403 سطر) ⚠  [package com.securemessenger.app.data.local]
- L37 `@Entity(tableName =           )
data class OldContact ( @PrimaryKey val id: String, val publicKey: ByteArray, val signingPublicKey: ByteArray? = null, val displayNameEncrypted: ByteArray, val avatarHash: String? = null, val isVerified: Boolean = false, val verificationData: ByteArray? = null, val addedAt: Long = System.currentTimeMillis(), val lastSeenAt: Long? = null, val isBlocked: Boolean = false, val avatarEncrypted: ByteArray? = null, val nicknameEncrypted: ByteArray? = null, val isMuted: Boolean = false, val relaySendSecretEncrypted: ByteArray? = null, val relayRecvSecretEncrypted: ByteArray? = null )`
- L38 `@PrimaryKey val id : String,`
- L39 `val publicKey : ByteArray,`
- L40 `val signingPublicKey : ByteArray?`
- L41 `val displayNameEncrypted : ByteArray,`
- L42 `val avatarHash : String?`
- L43 `val isVerified : Boolean`
- L44 `val verificationData : ByteArray?`
- L45 `val addedAt : Long`
- L46 `val lastSeenAt : Long?`
- L47 `val isBlocked : Boolean`
- L48 `val avatarEncrypted : ByteArray?`
- L49 `val nicknameEncrypted : ByteArray?`
- L50 `val isMuted : Boolean`
- L51 `val relaySendSecretEncrypted : ByteArray?`
- L52 `val relayRecvSecretEncrypted : ByteArray?`
- L56 `@Dao interface OldContactDao`
  - L58 `@Insert
    suspend fun insert (contact: OldContact)`
- L72 `@Entity(tableName =           )
data class OldEncryptedMessage ( @PrimaryKey val id: Long? = null, val sessionId: String, val senderId: String, val recipientId: String, val direction: Int, val type: Int, val ciphertext: ByteArray, val iv: ByteArray, val timestamp: Long = System.currentTimeMillis(), val expiresAt: Long? = null, val isRead: Boolean = false, val isExpired: Boolean = false, val metadataEncrypted: ByteArray? = null, val clientMessageId: String? = null, val reactionsJson: String? = null, val replyToClientId: String? = null, val replySnippetEncrypted: ByteArray? = null, val isDeleted: Boolean = false, val editedAt: Long? = null )`
- L73 `@PrimaryKey val id : Long?`
- L74 `val sessionId : String,`
- L75 `val senderId : String,`
- L76 `val recipientId : String,`
- L77 `val direction : Int,`
- L78 `val type : Int,`
- L79 `val ciphertext : ByteArray,`
- L80 `val iv : ByteArray,`
- L81 `val timestamp : Long`
- L82 `val expiresAt : Long?`
- L83 `val isRead : Boolean`
- L84 `val isExpired : Boolean`
- L85 `val metadataEncrypted : ByteArray?`
- L86 `val clientMessageId : String?`
- L87 `val reactionsJson : String?`
- L88 `val replyToClientId : String?`
- L89 `val replySnippetEncrypted : ByteArray?`
- L90 `val isDeleted : Boolean`
- L91 `val editedAt : Long?`
- L95 `@Dao interface OldMessageDao`
  - L97 `@Insert
    suspend fun insert (message: OldEncryptedMessage)`
- L115 `abstract class OldSchemaDatabase : RoomDatabase()`
  - L116 `abstract fun oldContactDao ()`
- L134 `class SecureDatabaseMigrationTest` — Proves MIGRATION_10_11 (contacts.pinnedAt) preserves an existing contact
  - L136 `private val passphrase`
  - L139 `@Test fun migration10to11_preservesExistingContactAndDefaultsPinnedAtToNull ()`
  - L210 `abstract class V11SchemaDatabase : RoomDatabase()`
  - L234 `abstract class V12SchemaDatabase : RoomDatabase()`
  - L248 `@Test fun migration11to12_preservesExistingContactAndCreatesConnectionRequestTables ()` — Proves MIGRATION_11_12 (the two connection-request tables) preserves
  - L328 `@Test fun migration12to13_backfillsContactIdForBothDirections ()` — Proves MIGRATION_12_13 gives every existing message the right

### app/src/androidTest/java/com/securemessenger/app/media/MediaSandboxTest.kt (167 سطر)  [package com.securemessenger.app.media]
- L31 `@RunWith(AndroidJUnit4::class) class MediaSandboxTest` — The tests ADR-0001 said could not be written without a device.
  - L33 `private val context`
  - L35 `private fun shell (command: String): String`
  - L44 `private fun samplePng (width: Int, height: Int): ByteArray`
  - L56 `private fun sandboxProcessLine (): String?`
  - L61 `@Test fun decodesARealImageInTheSandbox ()`
  - L84 `@Test fun sandboxRunsUnderItsOwnIsolatedUid ()` — Proves the isolation is real, not merely declared.
  - L108 `@Test fun malformedBytesFailCleanlyRatherThanCrashing ()`
  - L140 `@Test fun killingTheSandboxLeavesTheAppAliveAndItRecovers ()` — Survivability, which is **not** the same thing as containment — worth

### app/src/androidTest/java/com/securemessenger/app/media/WhereDoesMediaParsingHappenTest.kt (158 سطر)  [package com.securemessenger.app.media]
- L33 `@RunWith(AndroidJUnit4::class) class WhereDoesMediaParsingHappenTest` — Answers the question that decides whether a video sandbox is worth building
  - L35 `private fun ownMaps (): List<String>`
  - L39 `private fun mediaLibsInOurProcess (): List<String>`
  - L65 `private fun parserPluginsInOurProcess (): List<String>`
  - L71 `private fun sampleWav (samples: Int = 8000): ByteArray`
  - L92 `@Test fun containerParsingDoesNotHappenInOurProcess ()`
  - L133 `@Test fun malformedContainersAreNotParsedInOurProcessEither ()` — The same question for hostile input, which is the case that actually

### app/src/androidTest/java/com/securemessenger/app/network/AckAuthenticationTest.kt (130 سطر)  [package com.securemessenger.app.network]
- L36 `@RunWith(AndroidJUnit4::class) class AckAuthenticationTest` — Instrumented (on-device) verification of authenticated transport acks.
  - L38 `private lateinit var context : Context`
  - L39 `private lateinit var repo : SecureRepository`
  - L40 `private lateinit var client : SecureMessagingClient`
  - L43 `@Before fun setUp ()`
  - L55 `@After fun tearDown ()`
  - L61 `private suspend fun stageOutboxEntry (): String`
  - L68 `private suspend fun outboxContains (id: String): Boolean`
  - L73 `@Test fun validAck_clearsOutboxEntry ()` — A genuine ack (recipient echoed the sealed token) clears the outbox entry.
  - L85 `@Test fun forgedAck_keepsOutboxEntry ()` — A forged token (on-path attacker who saw only the cleartext id) is rejected.
  - L96 `@Test fun legacyAckWithoutToken_keepsOutboxEntry ()` — A legacy peer's tokenless ack fails closed (entry kept, keeps retrying).
  - L106 `@Test fun ackForOtherEnvelope_keepsOurEntry ()` — An ack for a DIFFERENT envelope id (even with a valid-looking token) doesn't touch ours.
  - L118 `@Test fun ackToken_isDeterministicUniqueAndSecretBound ()` — Token derivation: deterministic, unique per envelope id, and bound to the identity secret.

### app/src/androidTest/java/com/securemessenger/app/network/IntroductionRoundtripTest.kt (272 سطر)  [package com.securemessenger.app.network]
- L33 `class IntroductionRoundtripTest` — End-to-end verification of the username-directory path, run on a real
  - L35 `private val directoryUrl`
  - L36 `private val http`
  - L37 `private val jsonType`
  - L40 `@Before fun installPlatform ()`
  - L48 `private data class TestIdentity ( val identityPublic: ByteArray, val identitySecret: ByteArray, val signingPublic: ByteArray, val signingSecret: ByteArray, val username: String )`
  - L49 `val identityPublic : ByteArray,`
  - L50 `val identitySecret : ByteArray,`
  - L51 `val signingPublic : ByteArray,`
  - L52 `val signingSecret : ByteArray,`
  - L53 `val username : String`
  - L57 `private fun freshClaimedIdentity (label: String): TestIdentity`
  - L78 `private data class RawResponse (val code: Int, val body: String)`
  - L80 `private fun post (path: String, body: String): RawResponse`
  - L85 `private fun get (path: String): RawResponse`
  - L91 `@Test fun claimedUsername_resolvesViaLookup ()`
  - L103 `@Test fun lookingUpAnUnclaimedUsername_returns404 ()`
  - L110 `@Test fun claimingAnAlreadyTakenUsername_isRejected ()`
  - L138 `@Test fun introduction_depositAndFetchRoundTripsAndVerifies ()` — Full client-shaped round trip: build+seal+sign exactly as [SecureMessagingClient.sendConnectionRequest] does, deposit, fetch, unseal+verify exactly as [SecureMessagingClient.handleIntroRequest] does.
  - L203 `@Test fun depositingForAnUnregisteredIdentity_isRejected ()`
  - L217 `@Test fun introductionFetchIsDestructive_secondReadFindsNothing ()`
  - L251 `@Test fun fetchingWithSomeoneElsesSignature_isRejected ()`

### app/src/androidTest/java/com/securemessenger/app/network/RelayRoundtripTest.kt (170 سطر)  [package com.securemessenger.app.network]
- L32 `class RelayRoundtripTest` — End-to-end verification of the blind mailbox path, run on a real device
  - L34 `private val relayUrl`
  - L35 `private val http`
  - L36 `private val jsonType`
  - L38 `private fun post (path: String, body: String): String?`
  - L46 `private fun deposit (mailboxId: String, blobs: List<String>)`
  - L52 `private fun collect (mailboxId: String): List<String>`
  - L60 `private fun reassemble (secret: ByteArray, blobs: List<String>): ByteArray`
  - L68 `@Test fun shortMessage_roundTripsThroughTheRealRelay ()`
  - L83 `@Test fun collectIsDestructive_secondReadFindsNothing ()`
  - L96 `@Test fun largeMessage_splitsAndReassembles ()`
  - L117 `@Test fun blobIsUselessWithoutTheRightPairSecret ()`
  - L129 `@Test fun blobLengthCollapsesIntoSizeClasses_soLengthDoesNotLeak ()`
  - L152 `@Test fun mailboxIdRotatesAndToleratesClockSkew ()`

### app/src/androidTest/java/com/securemessenger/app/security/testing/CryptoSecurityTests.kt (170 سطر)  [package com.securemessenger.app.security.testing]
- L12 `class CryptoSecurityTests` — Cryptographic Security Tests
  - L15 `@Test fun testSymmetricEncryptionDecryption ()`
  - L31 `@Test fun testSymmetricEncryptionAuthentication ()`
  - L50 `@Test fun testAsymmetricEncryption ()`
  - L75 `@Test fun testKeyAgreement ()`
  - L93 `@Test fun testSecureMemoryWipe ()`
  - L106 `@Test fun testConstantTimeCompare ()`
  - L117 `@Test fun testSignalProtocolEncryption ()`
  - L150 `@Test fun testHashFunctionDeterministic ()`
  - L160 `@Test fun testHashFunctionCollisionResistance ()`

### app/src/androidTest/java/com/securemessenger/app/security/testing/SecurityConfigurationTests.kt (174 سطر)  [package com.securemessenger.app.security.testing]
- L25 `@RunWith(AndroidJUnit4::class) class SecurityConfigurationTests` — These assertions read the MERGED, INSTALLED manifest via PackageManager —
  - L27 `private val context`
  - L30 `@Test fun testNoDangerousPermissions ()`
  - L69 `@Test fun testRequiredPermissionsOnly ()`
  - L127 `@Test fun testBackupDisabled ()`
  - L149 `@Test fun testCleartextTrafficIntentionallyAllowedForLocalP2P ()` — Cleartext IS intentionally allowed — there is no external server at
  - L165 `@Test fun testSecureRandomGeneration ()`

### app/src/androidTest/java/com/securemessenger/app/ui/HomeScreenshotTest.kt (141 سطر)  [package com.securemessenger.app.ui]
- L39 `@RunWith(AndroidJUnit4::class) class HomeScreenshotTest` — Photographs the home screen so a human can judge it.
  - L42 `val compose`
  - L44 `private val sample`
  - L73 `@Test fun captureHomeDark ()`
  - L76 `@Test fun captureHomeLight ()`
  - L79 `@Test fun captureHomeEmpty ()`
  - L92 `@Test fun captureUntouchedLoadingScreen ()` — Two screens this redesign never opened.
  - L96 `@Test fun captureUntouchedStealthScreen ()`
  - L103 `private fun captureContent (name: String, dark: Boolean, content: @Composable () -> Unit)`
  - L109 `private fun writeRootTo (name: String)`
  - L122 `private fun capture (name: String, dark: Boolean, contacts: List<ContactUiModel>)`

### app/src/androidTest/java/com/securemessenger/app/ui/LiquidHomeRenderTest.kt (229 سطر)  [package com.securemessenger.app.ui]
- L52 `@RunWith(AndroidJUnit4::class) class LiquidHomeRenderTest` — The redesigned home screen, rendered on a real device.
  - L55 `val compose`
  - L57 `private val unreadContact`
  - L66 `private val pinnedContact`
  - L79 `@Test fun chatRowRendersItsNamePreviewAndUnreadCount_inDark ()`
  - L94 `@Test fun chatRowRenders_inLight ()`
  - L108 `@Test fun aRowStillReportsTheClickTheScreenExistsToDeliver ()`
  - L128 `@Test fun theEntranceAnimationLeavesTheRowVisible ()`
  - L145 `@Test fun theAuroraAndItsSurfacesDrawWithoutFailing ()`
  - L172 `@Test fun theHomeScreenNoLongerOwnsAnyRootDestination ()`
  - L211 `@Test fun thePendingRequestsBannerIsAbsentWhenThereAreNone ()`

### app/src/androidTest/java/com/securemessenger/app/ui/ScreenTourTest.kt (244 سطر)  [package com.securemessenger.app.ui]
- L66 `@RunWith(AndroidJUnit4::class) class ScreenTourTest` — A walk through the app, one screen per test.
  - L69 `val compose`
  - L71 `@Test fun tourCalculator ()`
  - L75 `@Test fun tourSetup ()`
  - L79 `@Test fun tourSettings ()`
  - L86 `@Test fun tourProfile ()`
  - L90 `@Test fun tourNewChat ()`
  - L94 `@Test fun tourUsernameSearch ()`
  - L108 `@Test fun tourBottomNavBar ()` — The restored bottom bar, in all three of its states.
  - L129 `@Test fun tourKeyVerification ()`
  - L133 `@Test fun tourConnectionRequests ()`
  - L137 `@Test fun tourContactDetail ()`
  - L163 `@Test fun tourConversation ()` — The conversation — the app's most-used screen, and until now the only
  - L203 `private val sampleConversation`
  - L229 `private fun shoot (name: String, content: @Composable () -> Unit)`

### app/src/androidTest/java/com/securemessenger/app/ui/screens/chat/QrImageTest.kt (189 سطر)  [package com.securemessenger.app.ui.screens.chat]
- L20 `class QrImageTest` — Covers pairing by picture rather than by camera: the code has to survive
  - L22 `private fun samplePayload (withSecret: Boolean = true, asciiName: Boolean = false): String`
  - L31 `@Test fun renderedCodeDecodesBackToTheSamePayload ()`
  - L74 `@Test fun asciiOnlyPayloadsAlwaysSurvive ()` — Isolates the one remaining variable: the non-ASCII display name.
  - L83 `@Test fun everyRandomPayloadSurvivesTheRoundTrip ()`
  - L133 `@Test fun codeSurvivesJpegRecompression ()`
  - L154 `@Test fun codeSurvivesBeingScaledDown ()`
  - L163 `@Test fun localOnlyShareDropsTheRelaySecretAndKeepsTheRest ()`
  - L182 `@Test fun aPictureWithNoCodeInItReturnsNull ()`

### app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt (411 سطر) ⚠  [package com.securemessenger.app]
- L32 `class SecureMessengerApp : Application()`
  يُستخدم في: app/src/main/java/com/securemessenger/app/service/MessengerService.kt, app/src/main/java/com/securemessenger/app/ui/navigation/AppNavigation.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatCommon.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/NewChatScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/settings/ProfileScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/settings/SettingsScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/setup/SetupScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/verification/KeyVerificationScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConnectionRequestsViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
  - L34 `val applicationScope`
  - L36 `val repository`
  - L43 `private val _messagingClientFlow`
  - L44 `val messagingClientFlow : StateFlow<SecureMessagingClient?>`
  - L46 `var messagingClient : SecureMessagingClient?`
  - L56 `@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) val connectionState : StateFlow<ConnectionState>`
  - L61 `suspend fun awaitMessagingClient (timeoutMs: Long = 10_000): SecureMessagingClient?`
  - L64 `private var incomingJob : Job?`
  - L66 `suspend fun initializeMessagingClient ()`
  - L185 `private suspend fun announceArrival (senderId: String, previewText: String?)`
  - L216 `fun stopMessagingClient ()` — Stops this device's own local relay + network discovery entirely — the
  - L231 `private var hideJob : Job?`
  - L266 `private fun installDisguiseHideOnLeavingApp ()`
  - L283 `override fun onCreate ()`
  - L359 `override fun attachBaseContext (base: Context)`
  - L373 `private fun isMediaSandboxProcess (): Boolean`

### app/src/main/java/com/securemessenger/app/crypto/AndroidKeyStoreManager.kt (164 سطر)  [package com.securemessenger.app.crypto]
- L16 `object AndroidKeyStoreManager` — AndroidKeyStoreManager - manages keys stored in Android's secure keystore.
  يُستخدم في: app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/verification/KeyVerificationScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
  - L17 `private const val ANDROID_KEYSTORE`
  - L18 `private const val KEY_ALIAS`
  - L19 `private const val TRANSFORMATION`
  - L20 `private const val GCM_TAG_LENGTH`
  - L21 `private const val GCM_IV_LENGTH`
  - L23 `private val keyStore`
  - L53 `@Volatile
    private var cachedMasterKey : SecretKey?`
  - L59 `fun getOrCreateMasterKey (): SecretKey` — Generate or retrieve the master key from Android Keystore.
  - L79 `private fun generateMasterKey (preferStrongBox: Boolean): SecretKey`
  - L111 `fun encryptWithMasterKey (plaintext: ByteArray): ByteArray` — Encrypt data using the master key with AES-GCM.
  - L126 `fun decryptWithMasterKey (encryptedData: ByteArray): ByteArray` — Decrypt data encrypted with the master key.
  - L148 `fun wipeKeys ()` — Delete the master key itself. Called at the very end of a full wipe: the

### app/src/main/java/com/securemessenger/app/data/local/Daos.kt (398 سطر) ⚠  [package com.securemessenger.app.data.local]
- L8 `data class UnreadCount ( val contactId: String, val unreadCount: Int )`
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
- L9 `val contactId : String,`
- L10 `val unreadCount : Int`
- L17 `@Dao interface UserProfileDao` — Data Access Object for user profile operations.
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L20 `@Query(                                    ) fun getProfile ()`
  - L23 `@Query(                                    )
    suspend fun getProfileOnce ()`
  - L26 `@Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile (profile: UserProfile)`
  - L29 `@Query(                                                   )
    suspend fun setAvatar (avatar: ByteArray?)`
  - L32 `@Query(                                                      )
    suspend fun setDisplayName (name: ByteArray?)`
- L39 `@Dao interface ContactDao` — Data Access Object for contact operations.
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L42 `@Query(                                                      ) fun getAllContacts ()`
  - L45 `@Query(                                              )
    suspend fun getContact (contactId: String)`
  - L48 `@Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact (contact: Contact)`
  - L51 `@Update
    suspend fun updateContact (contact: Contact)`
  - L54 `@Query(                                                                                            )
    suspend fun setVerified (contactId: String, verified: Boolean, data: ByteArray?)`
  - L57 `@Query(                                                                )
    suspend fun setBlocked (contactId: String, blocked: Boolean)`
  - L60 `@Query(                                                            )
    suspend fun setMuted (contactId: String, muted: Boolean)`
  - L63 `@Query(                                                                         )
    suspend fun setNickname (contactId: String, nickname: ByteArray?)`
  - L66 `@Query(                                                                )
    suspend fun setPinned (contactId: String, pinnedAt: Long?)`
  - L69 `@Delete
    suspend fun deleteContact (contact: Contact)`
  - L72 `@Query(                                            )
    suspend fun deleteContactById (contactId: String)`
  - L75 `@Query(                                                                     )
    suspend fun setAvatar (contactId: String, avatar: ByteArray?)`
- L82 `@Dao interface SessionDao` — Data Access Object for session operations.
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L85 `@Query(                                                                                      )
    suspend fun getCurrentSession (contactId: String)`
  - L88 `@Query(                                                     )
    suspend fun getSessionById (sessionId: String)`
  - L91 `@Insert
    suspend fun insertSession (session: Session)`
  - L98 `suspend fun updateSessionState ( sessionId: String, rootKey: ByteArray, sendKey: ByteArray, receiveKey: ByteArray?, dhPublic: ByteArray, dhSecret: ByteArray, sendCounter: Int, receiveCounter: Int, timestamp: Long )`
  - L111 `@Query(                                                   )
    suspend fun deleteAllSessionsForContact (contactId: String)`
- L118 `@Dao interface MessageDao` — Data Access Object for message operations.
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L138 `fun getRecentMessagesForContact (contactId: String, limit: Int)`
  - L142 `@Query(                                                            ) fun countMessagesForContact (contactId: String)`
  - L153 `@Query(                                                                                                             ) fun observeMediaForContact (contactId: String)` — Shared media in one conversation, newest first.
  - L174 `fun observeLatestMessagePerContact ()`
  - L181 `fun observeUnreadCounts ()`
  - L184 `@Query(                                              )
    suspend fun getMessage (messageId: Long)`
  - L187 `@Query(                                                                                                    )
    suspend fun getExpiredMessages (currentTime: Long = System.currentTimeMillis())`
  - L190 `@Insert
    suspend fun insertMessage (message: EncryptedMessage)`
  - L196 `@Query(                                                                                      )
    suspend fun getUnreadReceivedMessagesForContact (contactId: String)`
  - L199 `@Query(                                                                                              )
    suspend fun markAllReceivedAsReadForContact (contactId: String)`
  - L206 `@Query(                                                                                                                              )
    suspend fun markSentMessagesReadByClientIdsForRecipient (clientMessageIds: List<String>, recipientId: String)`
  - L209 `@Query(                                                                                     )
    suspend fun markAllExpiredMessages (currentTime: Long = System.currentTimeMillis())`
  - L212 `@Query(                                          )
    suspend fun deleteExpiredMessages ()`
  - L219 `@Query(                                                     )
    suspend fun getMessagesForContactOnce (contactId: String)`
  - L222 `@Query(                                                   )
    suspend fun deleteMessagesForContact (contactId: String)`
  - L227 `@Query(                                                                  )
    suspend fun getMessageByClientId (clientId: String)`
  - L230 `@Query(                                                                                      )
    suspend fun updateReactions (clientId: String, reactionsJson: String?)`
  - L235 `@Query(                                                                                                                 )
    suspend fun markDeletedByClientId (clientId: String)`
  - L238 `@Query(                                                                                                                   )
    suspend fun applyEdit (clientId: String, newTextEncrypted: ByteArray, editedAt: Long)`
  - L241 `@Query(                                            )
    suspend fun deleteById (messageId: Long)`
- L248 `@Dao interface KeyBundleDao` — Data Access Object for key bundle operations.
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L251 `@Query(                                                                                                                  )
    suspend fun getOurUnusedPreKeys ()`
  - L254 `@Query(                                                                                                      )
    suspend fun getOurLastPreKeyId ()`
  - L257 `@Query(                                                                                                        )
    suspend fun getOurPreKeyById (preKeyId: Int)`
  - L260 `@Query(                                                                               )
    suspend fun getUnusedRemotePreKey (contactId: String)`
  - L263 `@Query(                                                        )
    suspend fun getAllPreKeysForContact (contactId: String)`
  - L266 `@Insert
    suspend fun insertKeyBundle (keyBundle: KeyBundle)`
  - L269 `@Query(                                                                                                        )
    suspend fun markOurPreKeyAsUsed (preKeyId: Int)`
  - L272 `@Query(                                                      )
    suspend fun deleteAllPreKeysForContact (contactId: String)`
- L279 `@Dao interface OutboxDao` — Data Access Object for the durable outgoing-envelope queue.
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L282 `@Query(                                             )
    suspend fun getAll ()`
  - L285 `@Query(                                             ) fun observeAll ()`
  - L288 `@Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert (envelope: OutboxEnvelope)`
  - L291 `@Query(                                   )
    suspend fun deleteById (id: String)`
  - L294 `@Query(                    )
    suspend fun deleteAll ()`
- L302 `@Dao interface PendingSendDao` — Data Access Object for messages that failed before reaching the outbox
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L305 `@Query(                                                    )
    suspend fun getAll ()`
  - L308 `@Query(                                                    ) fun observeAll ()`
  - L311 `@Query(                                                                                   )
    suspend fun getForContact (contactId: String)`
  - L314 `@Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert (pending: PendingSend)`
  - L317 `@Query(                                                       )
    suspend fun deleteById (id: String)`
  - L320 `@Query(                                                          )
    suspend fun deleteAllForContact (contactId: String)`
- L328 `@Dao interface SeenEnvelopeDao` — Data Access Object for the persisted twin of the in-memory envelope-dedup
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L331 `@Query(                                                               )
    suspend fun get (key: String)`
  - L334 `@Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert (envelope: SeenEnvelope)`
  - L337 `@Query(                                                   )
    suspend fun pruneOlderThan (cutoff: Long)`
- L344 `@Dao interface RatchetSessionDao` — Data Access Object for persisted Double-Ratchet sessions.
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L347 `@Query(                                                             )
    suspend fun get (contactId: String)`
  - L350 `@Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert (session: RatchetSession)`
  - L353 `@Query(                                                           )
    suspend fun delete (contactId: String)`
  - L356 `@Query(                              )
    suspend fun deleteAll ()`
- L364 `@Dao interface IncomingConnectionRequestDao` — Data Access Object for self-introductions received via the username
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L367 `@Query(                                                                     ) fun observeAll ()`
  - L370 `@Query(                                                                                       )
    suspend fun get (keyHex: String)`
  - L373 `@Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert (request: IncomingConnectionRequest)`
  - L376 `@Query(                                                                                     )
    suspend fun deleteByKey (keyHex: String)`
- L384 `@Dao interface OutgoingConnectionRequestDao` — Data Access Object for self-introductions we sent via the username
  يُستخدم في: app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L387 `@Query(                                                                 ) fun observeAll ()`
  - L390 `@Query(                                                                                          )
    suspend fun get (keyHex: String)`
  - L393 `@Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert (request: OutgoingConnectionRequest)`
  - L396 `@Query(                                                                                        )
    suspend fun deleteByKey (keyHex: String)`

### app/src/main/java/com/securemessenger/app/data/local/SecureDatabase.kt (233 سطر)  [package com.securemessenger.app.data.local]
- L39 `abstract class SecureDatabase : RoomDatabase()`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/ConversationStorageTest.kt, app/src/androidTest/java/com/securemessenger/app/network/AckAuthenticationTest.kt, app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt
  - L41 `abstract fun userProfileDao ()`
  - L42 `abstract fun contactDao ()`
  - L43 `abstract fun sessionDao ()`
  - L44 `abstract fun messageDao ()`
  - L45 `abstract fun keyBundleDao ()`
  - L46 `abstract fun ratchetSessionDao ()`
  - L47 `abstract fun outboxDao ()`
  - L48 `abstract fun pendingSendDao ()`
  - L49 `abstract fun seenEnvelopeDao ()`
  - L50 `abstract fun incomingConnectionRequestDao ()`
  - L51 `abstract fun outgoingConnectionRequestDao ()`

### app/src/main/java/com/securemessenger/app/data/model/Entities.kt (361 سطر) ⚠  [package com.securemessenger.app.data.model]
- L12 `@Entity(tableName =               )
data class UserProfile ( @PrimaryKey val id: String = generateSecureId(), val publicKey: ByteArray, // Identity public key (not encrypted, needed for key exchange) val secretKeyEncrypted: ByteArray, // Identity secret key (encrypted with master key) val signedPreKeyId: Int, val signedPreKeyPublic: ByteArray, val signedPreKeySecretEncrypted: ByteArray, // Post-quantum ML-KEM keypair for the hybrid handshake. val mlkemPublicKey: ByteArray = ByteArray(0), val mlkemSecretEncrypted: ByteArray = ByteArray(0), // Ed25519 signing keypair — signs this profile's signed prekey so a // recipient (or a compromised relay in between) can't swap it unnoticed. // Separate from `publicKey` above, which is an X25519 key used for DH and // can't be used to verify a signature. val signingPublicKey: ByteArray = ByteArray(0), val signingSecretKeyEncrypted: ByteArray = ByteArray(0), val createdAt: Long = System.currentTimeMillis(), val lastBackupAt: Long? = null, // Own profile photo, Keystore-encrypted like a contact's avatarEncrypted. // Never uploaded anywhere — there is no profile-photo-sync protocol. val avatarEncrypted: ByteArray? = null, // The friendly name typed during Setup — shown to me on my own Profile // card. Local-only, like the avatar: contacts still only ever learn my // username (there is no profile-broadcast protocol to push this to them). val displayNameEncrypted: ByteArray? = null )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L14 `@PrimaryKey val id : String`
- L15 `val publicKey`
- L16 `val secretKeyEncrypted`
- L17 `val signedPreKeyId : Int,`
- L18 `val signedPreKeyPublic : ByteArray,`
- L19 `val signedPreKeySecretEncrypted : ByteArray,`
- L21 `val mlkemPublicKey : ByteArray`
- L22 `val mlkemSecretEncrypted : ByteArray`
- L27 `val signingPublicKey : ByteArray`
- L28 `val signingSecretKeyEncrypted : ByteArray`
- L29 `val createdAt : Long`
- L30 `val lastBackupAt : Long?`
- L33 `val avatarEncrypted : ByteArray?`
- L37 `val displayNameEncrypted : ByteArray?`
- L54 `@Entity(tableName =           )
data class Contact ( @PrimaryKey val id: String, // Contact's user ID val publicKey: ByteArray, // Identity public key // Signing (Ed25519) public key, pinned the first time we learn it (trust- // on-first-use) — later signed-prekey fetches are verified against this // same key, so a relay can't swap in a different signer unnoticed. val signingPublicKey: ByteArray? = null, val displayNameEncrypted: ByteArray, // Encrypted display name val avatarHash: String? = null, // Hash of avatar (not the avatar itself for privacy) val isVerified: Boolean = false, // Whether their key has been verified val verificationData: ByteArray? = null, // QR code verification data val addedAt: Long = System.currentTimeMillis(), val lastSeenAt: Long? = null, val isBlocked: Boolean = false, // Small (downscaled) avatar photo, Keystore-encrypted like displayNameEncrypted — // never leaves the device, never touches the relay. Null = show the gradient // initials avatar instead. val avatarEncrypted: ByteArray? = null, // Local-only display override; if set, shown instead of displayNameEncrypted // everywhere in the UI (the real name is still used for key/session lookups). val nicknameEncrypted: ByteArray? = null, val isMuted: Boolean = false, // Relay pair secrets. Each is 32 bytes that reached the other device by // being photographed off a QR code, never over a network, and each carries // exactly one direction of traffic (see MailboxToken). // // Scanned off THEIR QR — we deposit into the mailbox it addresses. Null for // contacts paired before relay support, or from an older QR: those have no // relay path at all and stay local-network-only. val relaySendSecretEncrypted: ByteArray? = null, // Minted by US and photographed by them — we listen on the mailbox it // addresses. Null until their first relay message actually lands in one of // our outstanding minted secrets, which is what tells us which one they // took; bound to this contact at that moment and never changed after. val relayRecvSecretEncrypted: ByteArray? = null, // Purely local — there is no multi-device concept for one identity here, // so there is nothing to sync. Non-null = pinned, and doubles as the sort // key among several pinned chats (most-recently-pinned first). val pinnedAt: Long? = null )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L56 `@PrimaryKey val id`
- L57 `val publicKey`
- L61 `val signingPublicKey : ByteArray?`
- L62 `val displayNameEncrypted`
- L63 `val avatarHash : String?`
- L64 `val isVerified : Boolean`
- L65 `val verificationData : ByteArray?`
- L66 `val addedAt : Long`
- L67 `val lastSeenAt : Long?`
- L68 `val isBlocked : Boolean`
- L72 `val avatarEncrypted : ByteArray?`
- L75 `val nicknameEncrypted : ByteArray?`
- L76 `val isMuted : Boolean`
- L84 `val relaySendSecretEncrypted : ByteArray?`
- L89 `val relayRecvSecretEncrypted : ByteArray?`
- L93 `val pinnedAt : Long?`
- L101 `@Entity(tableName =           )
data class Session ( @PrimaryKey val id: Long? = null, val contactId: String, // Foreign key to contacts.id val sessionId: String, // Unique session identifier val rootKeyEncrypted: ByteArray, // Encrypted root key val chainKeySendEncrypted: ByteArray, // Encrypted send chain key val chainKeyReceiveEncrypted: ByteArray?, // Encrypted receive chain key val dhRatchetPublic: ByteArray, val dhRatchetSecretEncrypted: ByteArray, val sendChainCounter: Int, val receiveChainCounter: Int, val createdAt: Long = System.currentTimeMillis(), val lastUsedAt: Long = System.currentTimeMillis() )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L103 `@PrimaryKey val id : Long?`
- L104 `val contactId`
- L105 `val sessionId`
- L106 `val rootKeyEncrypted`
- L107 `val chainKeySendEncrypted`
- L108 `val chainKeyReceiveEncrypted`
- L109 `val dhRatchetPublic : ByteArray,`
- L110 `val dhRatchetSecretEncrypted : ByteArray,`
- L111 `val sendChainCounter : Int,`
- L112 `val receiveChainCounter : Int,`
- L113 `val createdAt : Long`
- L114 `val lastUsedAt : Long`
- L130 `data class EncryptedMessage ( @PrimaryKey val id: Long? = null, val sessionId: String, // Foreign key to sessions.sessionId val senderId: String, val recipientId: String, val direction: Int, // 0 = received, 1 = sent val type: Int, // 0 = text, 1 = image, 2 = system val ciphertext: ByteArray, // Signal Protocol encrypted content val iv: ByteArray, // Initialization vector val timestamp: Long = System.currentTimeMillis(), val expiresAt: Long? = null, // Self-destruct timestamp (null = never expires) val isRead: Boolean = false, val isExpired: Boolean = false, val metadataEncrypted: ByteArray? = null, // Additional encrypted metadata // Shared between sender and recipient so a read receipt (which travels by // this id) can mark the *sender's* copy of the message as read. val clientMessageId: String? = null, // --- Social message-interaction fields (all local; the whole row is // SQLCipher-encrypted, and any sensitive text here is additionally // Keystore-encrypted, matching how metadataEncrypted is handled). --- // Emoji reactions as a small JSON object {"me":"👍","them":"❤️"}; either // side may be absent. Just emoji, not sensitive content, so plaintext. val reactionsJson: String? = null, // If this message is a reply, the clientMessageId it replies to, plus a // short Keystore-encrypted snippet of that message cached for display so // we never have to decrypt/scan the whole thread to render the quote. val replyToClientId: String? = null, val replySnippetEncrypted: ByteArray? = null, // Delete-for-everyone tombstone: the row stays (so the ordering/quote // references survive) but renders as "حُذفت"; the text is wiped locally. val isDeleted: Boolean = false, // Non-null once the sender edits the text; UI shows a "معدّلة" marker. val editedAt: Long? = null, /** * WHO THIS MESSAGE IS A CONVERSATION WITH — the app's missing concept, * finally written down. * * There was no "conversation" anywhere in this model. The conversation * screen keyed off [sessionId]; the chat list keyed off senderId / * recipientId. Those two agreed only by the accident that every contact * happens to have exactly one session — while the schema plainly expects * otherwise (SessionDao.getCurrentSession is written "ORDER BY lastUsedAt * DESC LIMIT 1"). A second session for one contact, from a re-pair after * a key change, would have split the history: the list showing a last * message the conversation screen could not display, and an unread count * counted across all sessions that markAllReceivedAsRead(sessionId) could * never clear. * * One key ends that. Both screens now read by contact, so history follows * the person rather than the cryptographic session underneath them, and * a re-keyed session keeps the thread intact. * * Derived, never passed: the default computes it from the fields above, * so every existing construction site got it right without being touched * and no future one can forget it. * * Nullable purely for migration safety. Adding a NOT NULL column with a * default to a live table risks Room's schema validation disagreeing with * SQLite over the default's spelling, and this app has no backup or export * — a failed validation falls back to destructive migration, which here * means every message a real user has. A plain nullable column cannot * mismatch. The backfill in MIGRATION_12_13 leaves no NULLs behind. */ val contactId: String? = if (direction == 0) senderId else recipientId )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L132 `@PrimaryKey val id : Long?`
- L133 `val sessionId`
- L134 `val senderId : String,`
- L135 `val recipientId : String,`
- L136 `val direction`
- L137 `val type`
- L138 `val ciphertext`
- L139 `val iv`
- L140 `val timestamp : Long`
- L141 `val expiresAt : Long?`
- L142 `val isRead : Boolean`
- L143 `val isExpired : Boolean`
- L144 `val metadataEncrypted : ByteArray?`
- L147 `val clientMessageId : String?`
- L153 `val reactionsJson : String?`
- L157 `val replyToClientId : String?`
- L158 `val replySnippetEncrypted : ByteArray?`
- L161 `val isDeleted : Boolean`
- L163 `val editedAt : Long?`
- L194 `val contactId : String?` — WHO THIS MESSAGE IS A CONVERSATION WITH — the app's missing concept,
- L203 `@Entity(tableName =                   )
data class RatchetSession ( @PrimaryKey val contactId: String, val rootKeyEnc: ByteArray, val chainKeySendEnc: ByteArray, val chainKeyReceiveEnc: ByteArray?, val dhRatchetPublic: ByteArray, val dhRatchetSecretEnc: ByteArray, val theirRatchetPublic: ByteArray?, val sendChainCounter: Int, val receiveChainCounter: Int, val previousSendChainLength: Int, val initiatorEphemeralPublic: ByteArray?, val isInitiator: Boolean, val responderEphemeralHex: String?, val initiatorOtkId: Int?, val skippedKeysEnc: ByteArray?, val updatedAt: Long = System.currentTimeMillis() )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L205 `@PrimaryKey val contactId : String,`
- L206 `val rootKeyEnc : ByteArray,`
- L207 `val chainKeySendEnc : ByteArray,`
- L208 `val chainKeyReceiveEnc : ByteArray?,`
- L209 `val dhRatchetPublic : ByteArray,`
- L210 `val dhRatchetSecretEnc : ByteArray,`
- L211 `val theirRatchetPublic : ByteArray?,`
- L212 `val sendChainCounter : Int,`
- L213 `val receiveChainCounter : Int,`
- L214 `val previousSendChainLength : Int,`
- L215 `val initiatorEphemeralPublic : ByteArray?,`
- L216 `val isInitiator : Boolean,`
- L217 `val responderEphemeralHex : String?,`
- L218 `val initiatorOtkId : Int?,`
- L219 `val skippedKeysEnc : ByteArray?,`
- L220 `val updatedAt : Long`
- L232 `@Entity(tableName =         )
data class OutboxEnvelope ( @PrimaryKey val id: String, val recipientId: String, val envelope: String, val createdAt: Long = System.currentTimeMillis(), // Set only for plain chat messages (null for read receipts/control ops) so // the UI can show a "sending…" clock for exactly this message until the // relay's ack removes the row. val clientMessageId: String? = null )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L234 `@PrimaryKey val id : String,`
- L235 `val recipientId : String,`
- L236 `val envelope : String,`
- L237 `val createdAt : Long`
- L241 `val clientMessageId : String?`
- L255 `@Entity(tableName =                )
data class PendingSend ( @PrimaryKey val clientMessageId: String, val recipientId: String, val plaintextEncrypted: ByteArray, val ttlSeconds: Int? = null, val createdAt: Long = System.currentTimeMillis() )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L257 `@PrimaryKey val clientMessageId : String,`
- L258 `val recipientId : String,`
- L259 `val plaintextEncrypted : ByteArray,`
- L260 `val ttlSeconds : Int?`
- L261 `val createdAt : Long`
- L276 `@Entity(tableName =                 )
data class SeenEnvelope ( @PrimaryKey val envelopeKey: String, val ackToken: String, val seenAt: Long = System.currentTimeMillis() )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L278 `@PrimaryKey val envelopeKey : String,`
- L279 `val ackToken : String,`
- L280 `val seenAt : Long`
- L288 `@Entity(tableName =              )
data class KeyBundle ( @PrimaryKey val id: Long? = null, val contactId: String, // Foreign key to contacts.id (null for our own prekeys) val preKeyId: Int, val publicKey: ByteArray, val secretKeyEncrypted: ByteArray?, // null for remote prekeys val isOneTime: Boolean = true, val isUsed: Boolean = false, val createdAt: Long = System.currentTimeMillis() )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L290 `@PrimaryKey val id : Long?`
- L291 `val contactId`
- L292 `val preKeyId : Int,`
- L293 `val publicKey : ByteArray,`
- L294 `val secretKeyEncrypted`
- L295 `val isOneTime : Boolean`
- L296 `val isUsed : Boolean`
- L297 `val createdAt : Long`
- L320 `@Entity(tableName =                               )
data class IncomingConnectionRequest ( @PrimaryKey val senderIdentityPublicKeyHex: String, val senderUserId: String, val senderUsername: String, val senderSigningPublicKey: ByteArray, // The pair secret THEY minted for us to listen on — real secret // material, Keystore-encrypted like Contact.relaySendSecretEncrypted. val pairSecretEncrypted: ByteArray, val directAddress: String? = null, val receivedAt: Long = System.currentTimeMillis() )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L322 `@PrimaryKey val senderIdentityPublicKeyHex : String,`
- L323 `val senderUserId : String,`
- L324 `val senderUsername : String,`
- L325 `val senderSigningPublicKey : ByteArray,`
- L328 `val pairSecretEncrypted : ByteArray,`
- L329 `val directAddress : String?`
- L330 `val receivedAt : Long`
- L351 `@Entity(tableName =                               )
data class OutgoingConnectionRequest ( @PrimaryKey val recipientIdentityPublicKeyHex: String, val recipientUsername: String, val recipientSigningPublicKey: ByteArray, // The pair secret WE minted for them to listen on. Keystore-encrypted // like every other pair secret at rest. val mintedPairSecretEncrypted: ByteArray, val sentAt: Long = System.currentTimeMillis() )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/local/SecureDatabaseMigrationTest.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L353 `@PrimaryKey val recipientIdentityPublicKeyHex : String,`
- L354 `val recipientUsername : String,`
- L355 `val recipientSigningPublicKey : ByteArray,`
- L358 `val mintedPairSecretEncrypted : ByteArray,`
- L359 `val sentAt : Long`

### app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt (1562 سطر) ⚠  [package com.securemessenger.app.data.repository]
- L26 `data class LoadedRatchetSession ( val protocol: SignalProtocol, val isInitiator: Boolean, val responderEphemeralHex: String?, val initiatorOtkId: Int? )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/ConversationStorageTest.kt, app/src/androidTest/java/com/securemessenger/app/network/AckAuthenticationTest.kt, app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/network/RelayClient.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/verification/KeyVerificationScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConnectionRequestsViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L27 `val protocol : SignalProtocol,`
- L28 `val isInitiator : Boolean,`
- L29 `val responderEphemeralHex : String?,`
- L30 `val initiatorOtkId : Int?`
- L34 `enum class ContactPairResult`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/ConversationStorageTest.kt, app/src/androidTest/java/com/securemessenger/app/network/AckAuthenticationTest.kt, app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/network/RelayClient.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/verification/KeyVerificationScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConnectionRequestsViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L62 `enum class KeyScanResult`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/ConversationStorageTest.kt, app/src/androidTest/java/com/securemessenger/app/network/AckAuthenticationTest.kt, app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/network/RelayClient.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/verification/KeyVerificationScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConnectionRequestsViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
- L77 `private fun identityKeyHexFromScan (scanned: String): String?`
  - L78 `val raw`
  - L79 `val hex`
- L98 `class SecureRepository (private val context: Context)` — SecureRepository - manages all data operations with encryption/decryption.
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/data/ConversationStorageTest.kt, app/src/androidTest/java/com/securemessenger/app/network/AckAuthenticationTest.kt, app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/network/RelayClient.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/verification/KeyVerificationScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConnectionRequestsViewModel.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
  - L100 `private var database : SecureDatabase?`
  - L101 `private var dbPassphrase : CharArray?`
  - L103 `private fun requireDb (): SecureDatabase`
  - L109 `suspend fun initialize (passphrase: CharArray)`
  - L119 `fun close ()` — Close the database and wipe the passphrase from memory.
  - L131 `suspend fun createProfile (): UserProfile`
  - L163 `suspend fun getProfile (): UserProfile?`
  - L167 `suspend fun getIdentityKeyPair (): SignalProtocol.IdentityKeyPair?`
  - L174 `suspend fun getSignedPreKeyPair (): SignalProtocol.PreKeyPair?`
  - L182 `suspend fun getMlkemPublicKey (): ByteArray?`
  - L187 `suspend fun getMlkemSecret (): ByteArray?`
  - L193 `suspend fun getSigningPublicKey (): ByteArray?`
  - L198 `suspend fun getSigningSecretKey (): ByteArray?`
  - L212 `suspend fun addContact (contact: Contact)`
  - L216 `fun getContacts (): Flow<List<Contact>>`
  - L221 `suspend fun getAllContactsOnce (): List<Contact>`
  - L225 `suspend fun getContact (contactId: String): Contact?`
  - L235 `suspend fun setContactAvatar (contactId: String, rawImageBytes: ByteArray?)`
  - L240 `suspend fun getContactAvatar (contactId: String): ByteArray?`
  - L249 `suspend fun setMyAvatar (rawImageBytes: ByteArray?)`
  - L254 `suspend fun setMyDisplayName (name: String)`
  - L261 `suspend fun getMyDisplayName (): String?`
  - L271 `fun getMyAvatarFlow (): Flow<ByteArray?>` — Reactive so a Profile screen updates the instant the photo changes.
  - L279 `private fun downscaleAvatar (rawImageBytes: ByteArray, maxDimension: Int = 256): ByteArray`
  - L296 `suspend fun verifyContact (contactId: String, verificationData: ByteArray)`
  - L300 `suspend fun blockContact (contactId: String)`
  - L304 `suspend fun unblockContact (contactId: String)`
  - L308 `suspend fun setContactMuted (contactId: String, muted: Boolean)`
  - L313 `suspend fun setContactPinned (contactId: String, pinned: Boolean)`
  - L317 `suspend fun setContactNickname (contactId: String, nickname: String?)`
  - L324 `suspend fun getContactNickname (contactId: String): String?`
  - L360 `suspend fun verifyScannedKey (contactId: String, scanned: String): KeyScanResult`
  - L375 `suspend fun deleteContact (contactId: String)`
  - L387 `suspend fun createSession (contactId: String, protocol: SignalProtocol): String`
  - L414 `suspend fun loadSession (sessionId: String): SignalProtocol?`
  - L447 `suspend fun saveSessionState (sessionId: String, protocol: SignalProtocol)`
  - L472 `suspend fun sendMessage ( sessionId: String, senderId: String, recipientId: String, plaintext: ByteArray, ttlSeconds: Int? = null ): EncryptedMessage`
  - L504 `fun getRecentMessages (contactId: String, limit: Int): Flow<List<EncryptedMessage>>` — The newest [limit] messages with this contact, oldest-first. See MessageDao.getRecentMessagesForContact.
  - L509 `fun countMessages (contactId: String): Flow<Int>` — Total messages in the conversation — lets the UI know whether there is older history to load.
  - L514 `fun observeLatestMessagePerContact (): Flow<List<EncryptedMessage>>` — The latest message of every conversation — one row each, for the chat list.
  - L519 `fun observeUnreadCounts (): Flow<List<UnreadCount>>` — Unread tallies per conversation, counted in SQL.
  - L524 `fun observeMedia (contactId: String): Flow<List<EncryptedMessage>>` — Shared media in one conversation, newest first — filtered in SQL, not by loading the thread.
  - L536 `suspend fun saveIncomingMessage ( senderId: String, senderIdentityKey: ByteArray?, plaintext: ByteArray, ttlSeconds: Int? = null, clientMessageId: String? = null, senderUsername: String? = null, replyToClientId: String? = null, replySnippet: String? = null, /** The sender's claimed send time from the envelope — see [orderingTimestamp]. */ sentAt: Long? = null ): String?`
  - L599 `data class SentInteraction (val wirePayload: ByteArray, val message: EncryptedMessage? = null)`
  - L602 `suspend fun snippetOf (clientMessageId: String): String`
  - L608 `suspend fun sendReplyMessage ( contactId: String, text: String, replyToClientId: String, ttlSeconds: Int? = null ): SentInteraction`
  - L644 `suspend fun reactToMessage (targetClientId: String, emoji: String): ByteArray`
  - L653 `suspend fun editMessage (targetClientId: String, newText: String): ByteArray`
  - L663 `suspend fun deleteForEveryone (targetClientId: String): ByteArray`
  - L670 `suspend fun deleteForMe (messageId: Long)`
  - L681 `suspend fun applyIncomingControl (control: ChatPayloads.Control, senderId: String)`
  - L727 `private fun parseReactions (json: String?): MutableMap<String, String>`
  - L739 `private fun serializeReactions (map: Map<String, String>): String?`
  - L747 `fun decryptReplySnippet (message: EncryptedMessage): String?` — Decrypt a reply's cached snippet for display (empty if none/failed).
  - L793 `private fun mediaDir (): File`
  - L795 `private fun saveMediaFile (ref: String, bytes: ByteArray)`
  - L799 `private fun readMediaFile (ref: String): ByteArray?`
  - L811 `private fun deleteMediaBlobFor (message: EncryptedMessage)`
  - L832 `fun purgeDecryptedMediaCache ()` — Delete every plaintext file ever written to cacheDir/decrypted_media —
  - L836 `data class SentMedia (val message: EncryptedMessage, val wirePayload: ByteArray)`
  - L844 `suspend fun sendMediaMessage ( contactId: String, fileBytes: ByteArray, mimeType: String, fileName: String, mediaType: Int, ttlSeconds: Int? = null, waveform: List<Float> = emptyList(), caption: String? = null ): SentMedia`
  - L883 `suspend fun saveIncomingMediaMessage ( senderId: String, senderIdentityKey: ByteArray?, media: MediaCodec.WireMedia, ttlSeconds: Int? = null, clientMessageId: String? = null, senderUsername: String? = null, /** The sender's claimed send time from the envelope — see [orderingTimestamp]. */ sentAt: Long? = null ): String?`
  - L951 `fun decryptMediaDescriptor (message: EncryptedMessage): MediaCodec.LocalMedia?` — Decrypt the (small) local descriptor for a media message row.
  - L961 `suspend fun loadDecryptedMediaBytes (local: MediaCodec.LocalMedia): ByteArray?`
  - L975 `suspend fun markReceivedAsReadAndGetIds (contactId: String): List<String>`
  - L988 `suspend fun markMessagesReadByClientIds (clientMessageIds: List<String>, senderId: String)`
  - L994 `fun decryptDisplayText (message: EncryptedMessage): String`
  - L1012 `suspend fun getUserId (): String?`
  - L1016 `suspend fun getOrCreateSessionForContact (contactId: String): String`
  - L1057 `suspend fun sendMessageToContact ( contactId: String, plaintext: String, ttlSeconds: Int? = null ): EncryptedMessage`
  - L1067 `suspend fun storeContactPrekeys ( contactId: String, identityKey: ByteArray, signedPreKey: ByteArray, oneTimePreKey: ByteArray? = null, signingPublicKey: ByteArray? = null )`
  - L1118 `suspend fun getPublicKeyFingerprint (): String?`
  - L1134 `suspend fun getPublicKeyHex (): String?`
  - L1154 `suspend fun addContactWithPublicKey ( contactId: String, publicKeyHex: String, displayName: String, relaySendSecret: ByteArray? = null, allowKeyChange: Boolean = false ): ContactPairResult`
  - L1197 `suspend fun getRelaySendSecret (contactId: String): ByteArray?`
  - L1201 `suspend fun getRelayRecvSecret (contactId: String): ByteArray?`
  - L1204 `private suspend fun decryptContactSecret (encrypted: ByteArray?): ByteArray?`
  - L1222 `suspend fun bindRelayRecvSecret (contactId: String, secret: ByteArray)`
  - L1240 `fun getIncomingConnectionRequests (): Flow<List<IncomingConnectionRequest>>`
  - L1243 `suspend fun getIncomingConnectionRequest (senderIdentityPublicKeyHex: String): IncomingConnectionRequest?`
  - L1248 `suspend fun saveIncomingConnectionRequest ( senderIdentityPublicKeyHex: String, senderUserId: String, senderUsername: String, senderSigningPublicKey: ByteArray, pairSecret: ByteArray, directAddress: String? )`
  - L1268 `suspend fun deleteIncomingConnectionRequest (senderIdentityPublicKeyHex: String)`
  - L1272 `fun getOutgoingConnectionRequests (): Flow<List<OutgoingConnectionRequest>>`
  - L1275 `suspend fun getOutgoingConnectionRequest (recipientIdentityPublicKeyHex: String): OutgoingConnectionRequest?`
  - L1280 `suspend fun saveOutgoingConnectionRequest ( recipientIdentityPublicKeyHex: String, recipientUsername: String, recipientSigningPublicKey: ByteArray, mintedPairSecret: ByteArray )`
  - L1296 `suspend fun deleteOutgoingConnectionRequest (recipientIdentityPublicKeyHex: String)`
  - L1300 `private fun hexToBytes (hex: String): ByteArray`
  - L1308 `suspend fun deleteExpiredMessages (): Int`
  - L1319 `suspend fun generatePreKeys (count: Int = 100): List<KeyBundle>`
  - L1344 `suspend fun getUnusedPreKeys (): List<KeyBundle>`
  - L1352 `suspend fun getOurPreKeySecret (preKeyId: Int): ByteArray?`
  - L1357 `suspend fun markPreKeyAsUsed (preKeyId: Int)`
  - L1367 `suspend fun saveRatchetSession ( contactId: String, protocol: SignalProtocol, isInitiator: Boolean, responderEphemeralHex: String?, initiatorOtkId: Int? )`
  - L1404 `suspend fun loadRatchetSession (contactId: String): LoadedRatchetSession?`
  - L1440 `suspend fun saveOutboxEnvelope (id: String, recipientId: String, envelope: String, clientMessageId: String? = null)`
  - L1446 `suspend fun deleteOutboxEnvelope (id: String)`
  - L1450 `suspend fun getAllOutboxEnvelopes (): List<OutboxEnvelope>`
  - L1455 `fun observePendingClientMessageIds (): Flow<Set<String>>` — clientMessageId set of every message still awaiting the relay's ack, or still pending a resend — drives the "sending…" tick.
  - L1464 `data class DecryptedPendingSend ( val clientMessageId: String, val recipientId: String, val plaintext: ByteArray, val ttlSeconds: Int? )`
  - L1465 `val clientMessageId : String,`
  - L1466 `val recipientId : String,`
  - L1467 `val plaintext : ByteArray,`
  - L1468 `val ttlSeconds : Int?`
  - L1471 `private fun PendingSend ()`
  - L1478 `suspend fun savePendingSend (clientMessageId: String, recipientId: String, plaintext: ByteArray, ttlSeconds: Int?)`
  - L1490 `suspend fun deletePendingSend (clientMessageId: String)`
  - L1494 `suspend fun getAllPendingSends (): List<DecryptedPendingSend>`
  - L1498 `suspend fun getPendingSendsForContact (contactId: String): List<DecryptedPendingSend>`
  - L1505 `suspend fun getSeenEnvelopeAckToken (key: String): String?`
  - L1510 `suspend fun rememberSeenEnvelope (key: String, ackToken: String)`
  - L1514 `suspend fun pruneSeenEnvelopesOlderThan (maxAgeMs: Long)`
  - L1520 `suspend fun wipeAllData ()`

### app/src/main/java/com/securemessenger/app/media/ByteArrayMediaDataSource.kt (33 سطر)  [package com.securemessenger.app.media]
- L10 `class ByteArrayMediaDataSource (private val data: ByteArray) : MediaDataSource()` — Lets MediaPlayer read decrypted audio/video straight from a byte array —
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/screens/chat/MediaContent.kt
  - L11 `override fun readAt (position: Long, buffer: ByteArray, offset: Int, size: Int): Int`
  - L29 `override fun getSize (): Long`
  - L31 `override fun close ()`

### app/src/main/java/com/securemessenger/app/media/MediaSandbox.kt (326 سطر) ⚠  [package com.securemessenger.app.media]
- L23 `private const val TAG`
- L36 `object MediaSandbox` — Main-process half of the media sandbox: hands peer-controlled image bytes to
  - L38 `const val KEY_OK`
  - L39 `const val KEY_ERR`
  - L40 `const val KEY_PIXELS`
  - L41 `const val KEY_WIDTH`
  - L42 `const val KEY_HEIGHT`
  - L44 `const val ERR_NONE`
  - L45 `const val ERR_MALFORMED`
  - L46 `const val ERR_TOO_LARGE`
  - L47 `const val ERR_UNSUPPORTED`
  - L54 `private const val DECODE_TIMEOUT_MS`
  - L57 `private const val BIND_TIMEOUT_MS`
  - L65 `private const val SIZE_SLACK`
  - L68 `private const val MAX_PIXEL_BYTES`
  - L71 `private const val BYTES_PER_PIXEL`
  - L79 `private val gate`
  - L82 `@Volatile
    private var connection : Connection?`
  - L89 `val isSupported` — Whether this device can use the sandbox at all. SharedMemory arrived in
  - L95 `suspend fun decode (context: Context, bytes: ByteArray, maxDimension: Int): Bitmap?`
  - L111 `private suspend fun decodeLocked (context: Context, bytes: ByteArray, maxDimension: Int): Bitmap?`
  - L232 `internal fun isPlausibleResult ( width: Int, height: Int, maxDimension: Int, bufferBytes: Long ): Boolean`
  - L255 `@Suppress(             )
    private fun pixelsOf (result: Bundle): SharedMemory?`
  - L262 `private suspend fun connect (context: Context): IMediaSandbox?`
  - L297 `private fun dropConnection (context: Context)`
  - L310 `private class Connection (val pending: CompletableDeferred<IMediaSandbox?>) : ServiceConnection`

### app/src/main/java/com/securemessenger/app/media/MediaSandboxService.kt (158 سطر)  [package com.securemessenger.app.media]
- L38 `class MediaSandboxService : Service()` — Decodes peer-controlled image bytes, inside a process that has nothing worth
  - L40 `private val binder`
  - L67 `@RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun decode (src: ParcelFileDescriptor?, byteLength: Long, maxDimension: Int): Bundle`
  - L143 `private fun failure (code: Int)`
  - L148 `override fun onBind (intent: Intent?): IBinder`

### app/src/main/java/com/securemessenger/app/media/VoiceRecorder.kt (204 سطر)  [package com.securemessenger.app.media]
- L18 `object VoiceRecorder` — Records a voice message as raw PCM, then automatically disguises the
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/screens/chat/MessageInput.kt
  - L19 `private const val SAMPLE_RATE`
  - L22 `private const val PITCH_FACTOR`
  - L24 `private const val WAVEFORM_BUCKETS`
  - L26 `private var audioRecord : AudioRecord?`
  - L27 `private var recordingThread : Thread?`
  - L28 `private val buffer`
  - L29 `@Volatile private var isRecording`
  - L32 `data class RecordingResult (val wav: ByteArray, val waveform: List<Float>)`
  - L42 `private val lock`
  - L44 `fun start ()`
  - L92 `suspend fun stopAndEncode (): RecordingResult`
  - L108 `fun cancel ()`
  - L123 `private fun extractWaveform (pcm16: ByteArray, buckets: Int): List<Float>`
  - L149 `private fun pitchShift (pcm16: ByteArray, factor: Float): ByteArray`
  - L172 `private fun wrapAsWav (pcm: ByteArray, sampleRate: Int): ByteArray`

### app/src/main/java/com/securemessenger/app/network/DirectoryClient.kt (167 سطر)  [package com.securemessenger.app.network]
- L14 `private const val TAG`
- L29 `class DirectoryClient ( private val baseUrl: String, private val http: OkHttpClient )` — The one place the app talks to `directory/` — claiming a username,
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/setup/SetupScreen.kt
- L30 `private val baseUrl : String,`
- L31 `private val http : OkHttpClient`
  - L33 `private val random`
  - L35 `val isEnabled`
  - L37 `sealed class ClaimResult`
  - L45 `suspend fun claim ( username: String, identityPublicKey: ByteArray, signingPublicKey: ByteArray, sign: (ByteArray) -> ByteArray ): ClaimResult`
  - L72 `sealed class LookupResult`
  - L78 `suspend fun lookup (username: String): LookupResult`
  - L96 `suspend fun depositIntroduction (recipientIdentityPublicKey: ByteArray, blob: String): Boolean`
  - L111 `suspend fun fetchIntroductions (identityPublicKey: ByteArray, sign: (ByteArray) -> ByteArray): List<String>?`
  - L135 `private data class RawResponse (val code: Int, val bodyText: String)`
  - L143 `private fun postRaw (path: String, body: String): RawResponse?`
  - L153 `private fun getRaw (path: String): RawResponse?`

### app/src/main/java/com/securemessenger/app/network/RelayClient.kt (430 سطر) ⚠  [package com.securemessenger.app.network]
- L29 `private const val TAG`
- L62 `class RelayClient ( context: Context, private val repository: SecureRepository, private val baseUrl: String, private val http: OkHttpClient, private val onEnvelope: suspend (envelopeJson: String, arrivedOnPendingSecretHex: String?) -> Unit )` — RelayClient — the fallback path for contacts who aren't on the local network.
- L64 `private val repository : SecureRepository,`
- L65 `private val baseUrl : String,`
- L66 `private val http : OkHttpClient,`
- L67 `private val onEnvelope`
  - L69 `private val appContext`
  - L70 `private val scope`
  - L71 `private val random`
  - L73 `private var pollJob : Job?`
  - L74 `private var coverJob : Job?`
  - L77 `private val subscriptions`
  - L80 `private val partials`
  - L82 `private data class Subscription (val secret: ByteArray, val pendingSecretHex: String?)`
  - L84 `private class Partial (val count: Int)`
  - L90 `val isEnabled`
  - L92 `fun start ()`
  - L120 `fun stop ()`
  - L127 `fun shutdown ()`
  - L138 `suspend fun refreshSubscriptions ()`
  - L180 `suspend fun enqueue (contactId: String, envelopeJson: String): Boolean`
  - L202 `suspend fun enqueueAwait (contactId: String, envelopeJson: String): Boolean`
  - L226 `private suspend fun pollOnce ()`
  - L257 `private suspend fun handleBlob (mailboxId: String, bodyBase64: String)`
  - L328 `private fun prunePartials ()`
  - L336 `private fun sealIntoBlobs (secret: ByteArray, payload: ByteArray, minSizeClasses: Int = 1): List<String>`
  - L342 `private suspend fun depositBlobs (mailboxId: String, blobs: List<String>): Boolean`
  - L358 `private suspend fun sendCoverBlob ()`
  - L374 `private suspend fun post (path: String, body: String): String?`
  - L398 `private fun randomBetween (minMs: Long, maxMs: Long): Long`

### app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt (2130 سطر) ⚠  [package com.securemessenger.app.network]
- L51 `private const val TAG`
- L54 `const val LOCAL_RELAY_PORT`
- L57 `private const val TOKEN_ROTATION_MS`
- L59 `private const val TOKEN_ROTATION_CHECK_MS`
- L61 `private const val SERVER_IDLE_TIMEOUT_MS`
- L64 `private const val OUTBOX_RETRY_MS`
- L66 `private const val RELAY_RETRY_FLOOR_MS`
- L68 `private const val RELAY_RETRY_FLOOR_STALE_MS`
- L70 `private const val RELAY_RETRY_FLOOR_ABANDONED_MS`
- L73 `private const val LOCAL_BUNDLE_TIMEOUT_MS`
- L80 `private const val RELAY_BUNDLE_TIMEOUT_MS`
- L83 `private const val BUNDLE_HANDOUT_MIN_INTERVAL_MS`
- L86 `private const val SEEN_ENVELOPE_TTL_MS`
- L89 `private const val INTRO_POLL_MIN_MS`
- L90 `private const val INTRO_POLL_MAX_MS`
- L104 `class SecureMessagingClient ( context: Context, private val repository: SecureRepository, private val userId: String )` — SecureMessagingClient — fully peer-to-peer. There is no external server of
  يُستخدم في: app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatCommon.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt
- L106 `private val repository : SecureRepository,`
- L107 `private val userId : String`
  - L109 `private val appContext`
  - L110 `private val scope`
  - L113 `private val lanBinder`
  - L120 `private val localHttp`
  - L130 `private val relayHttp`
  - L138 `private var localServer`
  - L143 `private var boundPort`
  - L144 `private val discovery`
  - L145 `private var discoveryJob : Job?`
  - L146 `private var rotationJob : Job?`
  - L147 `private var outboxJob : Job?`
  - L150 `private val discoveryRestartLock`
  - L153 `private val relayRetryAt`
  - L158 `private val relay : RelayClient?`
  - L172 `private val directory : DirectoryClient?`
  - L177 `private var introductionPollJob : Job?`
  - L200 `private val seenEnvelopes`
  - L211 `private val unsealCache`
  - L215 `private val _connectionState`
  - L216 `val connectionState : StateFlow<ConnectionState>`
  - L219 `private val peerSockets`
  - L221 `private val discoveredByToken`
  - L223 `private val tokenToContactId`
  - L225 `private val pendingBundleRequests`
  - L229 `private val pendingChallengeNonces`
  - L232 `private val lastBundleHandout`
  - L237 `private val sessions`
  - L240 `private val responderEphemerals`
  - L242 `private val initiatorOtkIds`
  - L244 `private val initiatorPqCiphertexts`
  - L246 `private val contactLocks`
  - L247 `private fun lockFor (contactId: String): Mutex`
  - L260 `private val handshakeLocks`
  - L261 `private fun handshakeLockFor (contactId: String): Mutex`
  - L264 `private val _incomingMessages`
  - L265 `val incomingMessages`
  - L267 `private val _typingSignals`
  - L268 `val typingSignals : SharedFlow<String>`
  - L270 `private fun wireServer (server: LocalRelayServer)`
  - L280 `fun connect ()` — Starts this device's own local relay, advertises it, and starts watching for known contacts.
  - L385 `private suspend fun registerAndDiscover ()`
  - L446 `private fun currentTimeBucket (): Long`
  - L448 `private suspend fun myIdentityToken (): String`
  - L461 `private fun shortToken (publicKey: ByteArray, bucket: Long): String`
  - L466 `suspend fun refreshTokenMap ()`
  - L480 `fun retryNow ()` — User-triggered "retry now" — re-registers and re-scans instead of waiting for the next discovery tick.
  - L496 `fun myDirectAddress (): String?` — What this device is listening on, for the user to read out to whoever
  - L500 `fun setDirectAddress (contactId: String, hostPort: String?)` — Manually pin (or clear) where a contact can be dialled. See [connectionTo].
  - L518 `suspend fun transportStatus (contactId: String): TransportStatus`
  - L533 `fun disconnect ()`
  - L572 `private suspend fun connectionTo (contactId: String): WebSocket?`
  - L620 `private suspend fun openConnection (contactId: String, host: String, port: Int): WebSocket?`
  - L624 `private suspend fun attemptConnection ( contactId: String, host: String, port: Int, client: OkHttpClient ): WebSocket?`
  - L664 `private suspend fun sendDirect (contactId: String, envelopeJson: String): Boolean`
  - L682 `private suspend fun sendToContact (contactId: String, envelopeJson: String): Boolean`
  - L702 `private fun relayRetryFloorFor (ageMs: Long): Long`
  - L721 `private suspend fun retryOutbox ()`
  - L755 `private suspend fun retryPendingSends ()`
  - L762 `private suspend fun retryPendingSendsTo (contactId: String)`
  - L773 `suspend fun refreshRelaySubscriptions ()`
  - L791 `private suspend fun handleRelayEnvelope (envelopeJson: String, pendingSecretHex: String?)`
  - L839 `private suspend fun ensureUsernameClaimed ()`
  - L872 `private suspend fun pollIntroductions ()`
  - L897 `private suspend fun handleIntroductionBlob (blobBase64: String)`
  - L918 `private suspend fun handleIntroRequest (inner: JSONObject)`
  - L971 `private suspend fun handleIntroAccept (inner: JSONObject)`
  - L1019 `private fun decryptOutgoingPairSecret (request: OutgoingConnectionRequest): ByteArray?`
  - L1035 `private suspend fun flushOutboxTo (contactId: String)`
  - L1053 `private suspend fun enqueueAndSend (id: String, recipientId: String, envelopeJson: String, clientMessageId: String? = null)`
  - L1059 `private suspend fun handleAck (json: JSONObject)`
  - L1085 `private suspend fun ackTokenFor (envelopeId: String): String?`
  - L1092 `@androidx.annotation.VisibleForTesting
    internal suspend fun ackTokenForTest (envelopeId: String): String?`
  - L1095 `@androidx.annotation.VisibleForTesting
    internal suspend fun handleAckForTest (json: JSONObject)`
  - L1097 `suspend fun sendMessage (recipientId: String, plaintext: ByteArray, ttlSeconds: Int? = null, messageId: String? = null): Boolean`
  - L1121 `private suspend fun sendMessageInternal (recipientId: String, plaintext: ByteArray, ttlSeconds: Int?, messageId: String?)`
  - L1203 `private suspend fun handleIncomingMessage (jsonString: String, reply: (String) -> Unit = {})`
  - L1233 `private suspend fun handleAndAck ( type: String, json: JSONObject, reply: (String) -> Unit, handler: suspend () -> String? )`
  - L1274 `private suspend fun onceOnly (type: String, json: JSONObject, handler: suspend () -> String?): String?`
  - L1303 `private fun sealedEnvelope ( type: String, inner: JSONObject, recipientPublicKey: ByteArray, envelopeId: String? = null ): JSONObject`
  - L1326 `private suspend fun unsealEnvelope (outer: JSONObject): JSONObject`
  - L1337 `suspend fun sendReadReceipt (recipientId: String, messageIds: List<String>): Boolean`
  - L1360 `private suspend fun handleReceiptEnvelope (outer: JSONObject): String?`
  - L1380 `suspend fun sendTypingSignal (recipientId: String): Boolean`
  - L1399 `private suspend fun handleTypingEnvelope (outer: JSONObject)`
  - L1405 `private suspend fun handleMessageEnvelope (outer: JSONObject): String?`
  - L1461 `private suspend fun cachedOrPersisted (contactId: String): SignalProtocol?`
  - L1472 `private suspend fun persistSession (contactId: String)`
  - L1498 `private suspend fun selectSessionForIncoming (senderId: String, json: JSONObject): SignalProtocol?`
  - L1525 `private suspend fun createResponderSession (senderId: String, json: JSONObject): SignalProtocol`
  - L1582 `private suspend fun getOrCreateInitiatorSession (contactId: String): SignalProtocol`
  - L1640 `private suspend fun fetchPrekeyBundle (contactId: String): PrekeyBundle`
  - L1692 `private suspend fun handleChallenge (outer: JSONObject, reply: (String) -> Unit)`
  - L1735 `private suspend fun handleBundleAnnounce (outer: JSONObject)`
  - L1766 `private fun parseBundleJson (json: JSONObject, expectedNonce: ByteArray?): PrekeyBundle?`
  - L1783 `private suspend fun buildMyBundleAnnounce (nonce: ByteArray): JSONObject`
  - L1834 `suspend fun pairWithScannedContact ( scannedUserId: String, identityKeyHex: String, displayName: String, pairSecretHex: String? = null, directAddress: String? = null, allowKeyChange: Boolean = false ): com.securemessenger.app.data.repository.ContactPairResult?`
  - L1887 `sealed class ConnectionRequestResult`
  - L1905 `suspend fun lookupUsername (username: String): DirectoryClient.LookupResult.Found?`
  - L1917 `suspend fun sendConnectionRequest (username: String): ConnectionRequestResult`
  - L1979 `suspend fun acceptConnectionRequest (senderIdentityPublicKeyHex: String): Boolean`
  - L2044 `suspend fun rejectConnectionRequest (senderIdentityPublicKeyHex: String)`
  - L2048 `private suspend fun getIdentityKeyPair (): SignalProtocol.IdentityKeyPair?`
  - L2052 `private suspend fun getSignedPreKey (): SignalProtocol.PreKeyPair?`
  - L2056 `private suspend fun getOneTimePreKeys (): List<SignalProtocol.PreKeyPair>`
  - L2065 `private suspend fun getContactIdentityKey (contactId: String): ByteArray?`
- L2074 `data class TransportStatus ( /** A direct socket to them is open right now. */ val isConnected: Boolean, /** Their discovery token is currently visible on this network (mDNS worked). */ val isDiscovered: Boolean, /** "host:port" we can dial even when mDNS finds nothing, or null if we've never had one. */ val rememberedAddress: String?, /** Relaying is on and we hold an outbound pair secret for them — so off-LAN delivery is possible. */ val hasRelayPath: Boolean )`
  يُستخدم في: app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatCommon.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt
- L2076 `val isConnected : Boolean,` — A direct socket to them is open right now.
- L2078 `val isDiscovered : Boolean,` — Their discovery token is currently visible on this network (mDNS worked).
- L2080 `val rememberedAddress : String?,` — "host:port" we can dial even when mDNS finds nothing, or null if we've never had one.
- L2082 `val hasRelayPath : Boolean` — Relaying is on and we hold an outbound pair secret for them — so off-LAN delivery is possible.
  - L2085 `val hasNoRouteAtAll : Boolean` — True when there is no way at all to get a message to them; the outbox will queue forever.
  - L2089 `fun describe (): String` — A short Arabic description of the current situation, suitable for showing directly.
- L2100 `sealed class ConnectionState`
  يُستخدم في: app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatCommon.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt
  - L2101 `object Connecting`
  - L2102 `object Connected`
  - L2103 `object Disconnected`
  - L2104 `data class Error (val message: String) : ConnectionState()`
- L2107 `data class MessageReceived ( val senderId: String, val plaintext: ByteArray, val senderIdentityKey: ByteArray? = null, val ttlSeconds: Int? = null, val messageId: String? = null, /** * The sender's own send time, straight out of the sealed envelope. Null on * an envelope that carried none. It is a claim by the other device, not a * fact — [SecureRepository.orderingTimestamp] decides how far it is * allowed to move a message. */ val sentAt: Long? = null )`
  يُستخدم في: app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatCommon.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt
- L2108 `val senderId : String,`
- L2109 `val plaintext : ByteArray,`
- L2110 `val senderIdentityKey : ByteArray?`
- L2111 `val ttlSeconds : Int?`
- L2112 `val messageId : String?`
- L2119 `val sentAt : Long?` — The sender's own send time, straight out of the sealed envelope. Null on
- L2122 `data class PrekeyBundle ( val identityKey: ByteArray, val signedPreKey: ByteArray, val signingPublicKey: ByteArray? = null, val oneTimePreKey: ByteArray?, val oneTimePreKeyId: Int? = null, val mlkemPublicKey: ByteArray? = null )`
  يُستخدم في: app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatCommon.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt
- L2123 `val identityKey : ByteArray,`
- L2124 `val signedPreKey : ByteArray,`
- L2125 `val signingPublicKey : ByteArray?`
- L2126 `val oneTimePreKey : ByteArray?,`
- L2127 `val oneTimePreKeyId : Int?`
- L2128 `val mlkemPublicKey : ByteArray?`

### app/src/main/java/com/securemessenger/app/network/local/LanNetworkBinder.kt (147 سطر)  [package com.securemessenger.app.network.local]
- L13 `private const val TAG`
- L46 `class LanNetworkBinder (context: Context)` — Keeps local peer traffic on the Wi-Fi/Ethernet interface even while a VPN is up.
  يُستخدم في: app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt
  - L48 `private val appContext`
  - L49 `private val connectivity`
  - L54 `@Volatile
    private var lanNetwork : Network?`
  - L56 `private var callback : ConnectivityManager.NetworkCallback?`
  - L58 `fun start ()`
  - L102 `fun stop ()`
  - L108 `private fun isLan (network: Network): Boolean`
  - L116 `@Suppress(             )
    private fun currentLanNetwork (): Network?`
  - L128 `val socketFactory : SocketFactory` — A socket factory that resolves the LAN network **per socket**, not once at

### app/src/main/java/com/securemessenger/app/network/local/LocalDiscovery.kt (181 سطر)  [package com.securemessenger.app.network.local]
- L11 `private const val TAG`
- L12 `private const val SERVICE_TYPE`
- L15 `data class DiscoveredPeer (val host: String, val port: Int, val token: String)`
  يُستخدم في: app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt
- L24 `class LocalDiscovery (context: Context)` — Finds other instances of this app on the same local network (WiFi) via
  يُستخدم في: app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt
  - L25 `private val nsdManager`
  - L26 `private var registrationListener : NsdManager.RegistrationListener?`
  - L36 `@Volatile
    private var registeredServiceName : String?`
  - L39 `fun register (port: Int, token: String)` — Advertise this device's local server. [token] should be a short, non-reversible hash of the identity key — enough to recognize a known contact, not enough to leak who we are to strangers on the LAN.
  - L67 `fun unregister ()`
  - L80 `fun discoverPeers (): Flow<List<DiscoveredPeer>>` — The live set of currently-discovered peers, keyed by service name —

### app/src/main/java/com/securemessenger/app/receiver/BootReceiver.kt (37 سطر)  [package com.securemessenger.app.receiver]
- L13 `class BootReceiver : BroadcastReceiver()` — BootReceiver - restores the periodic cleanup job, and background message
  - L14 `override fun onReceive (context: Context, intent: Intent)`

### app/src/main/java/com/securemessenger/app/receiver/SecretCodeReceiver.kt (41 سطر)  [package com.securemessenger.app.receiver]
- L17 `class SecretCodeReceiver : BroadcastReceiver()` — Stealth-mode re-entry. When the user dials the secret code (*#*#73287#*#*),
  - L18 `override fun onReceive (context: Context, intent: Intent)`

### app/src/main/java/com/securemessenger/app/security/AppSettings.kt (580 سطر) ⚠  [package com.securemessenger.app.security]
- L14 `object AppSettings` — AppSettings - central, persistent store for user-facing app options.
  يُستخدم في: app/src/main/java/com/securemessenger/app/network/RelayClient.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/receiver/SecretCodeReceiver.kt, app/src/main/java/com/securemessenger/app/service/MessengerService.kt, app/src/main/java/com/securemessenger/app/service/StealthExitTileService.kt, app/src/main/java/com/securemessenger/app/ui/MainActivity.kt, app/src/main/java/com/securemessenger/app/ui/navigation/AppNavigation.kt, app/src/main/java/com/securemessenger/app/ui/screens/calculator/CalculatorScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/NewChatScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/settings/ProfileScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/settings/SettingsScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/settings/StealthModeScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/setup/SetupScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/setup/SetupSteps.kt, app/src/main/java/com/securemessenger/app/ui/screens/verification/KeyVerificationScreen.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt
  - L16 `private const val KEY_AUTODESTRUCT`
  - L17 `private const val KEY_AUTODESTRUCT_SECONDS`
  - L18 `private const val KEY_STEALTH`
  - L19 `private const val KEY_USERNAME`
  - L20 `private const val KEY_USERNAME_CLAIMED`
  - L21 `private const val KEY_ACCESS_CODE`
  - L22 `private const val KEY_DURESS_CODE`
  - L23 `private const val KEY_THEME_MODE`
  - L24 `private const val KEY_COVER_TRAFFIC`
  - L25 `private const val KEY_RELAY_ENABLED`
  - L26 `private const val KEY_PENDING_PAIR_SECRETS`
  - L27 `private const val KEY_DIRECT_ADDRESSES`
  - L28 `private const val KEY_BACKGROUND_DELIVERY`
  - L29 `private const val KEY_NOTIFICATION_MODE`
  - L31 `const val DEFAULT_AUTODESTRUCT_SECONDS`
  - L57 `val CODE_LENGTH_RANGE` — The one rule both places that *choose* a code must agree on.
  - L60 `fun isTypeableCode (code: String): Boolean` — True when [code] is a code the calculator can actually be used to enter.
  - L64 `fun hasAccessCode (context: Context): Boolean` — True once an unlock code has been chosen (the gate never matches while unset).
  - L67 `fun setAccessCode (context: Context, code: String)`
  - L77 `fun verifyAccessCode (context: Context, input: String): Boolean` — Constant-time check of a typed number against the stored access code.
  - L89 `fun hasDuressCode (context: Context): Boolean` — An optional, separate digit sequence: typed on the calculator instead of
  - L92 `fun setDuressCode (context: Context, code: String?)`
  - L97 `fun verifyDuressCode (context: Context, input: String): Boolean`
  - L120 `private const val BOOTSTRAP_PREFS_NAME`
  - L121 `private const val KEY_BOOTSTRAP_ARMED`
  - L123 `private fun bootstrapPrefs (context: Context)`
  - L127 `fun isBootstrapArmed (context: Context): Boolean` — True while the fresh-install bootstrap path should still work. Defaults true (a never-before-seen install has no reason to be locked out of Setup).
  - L131 `fun disarmBootstrap (context: Context)` — Called the moment a real access code is chosen (see setAccessCode) — the bootstrap path has done its one job.
  - L141 `fun rearmBootstrap (context: Context)` — Re-opens the bootstrap path. Only ever called from [SecretCodeReceiver],
  - L155 `private const val KEY_CODE_MISS_STREAK`
  - L156 `private const val KEY_CODE_LOCKED_UNTIL`
  - L158 `fun codeMissStreak (context: Context): Int`
  - L161 `fun setCodeMissStreak (context: Context, streak: Int)`
  - L165 `fun codeCheckLockedUntil (context: Context): Long` — Epoch millis until which further code checks are paused — the calculator itself keeps working normally throughout.
  - L168 `fun setCodeCheckLockedUntil (context: Context, until: Long)`
  - L173 `private fun hashCode (code: String): String`
  - L185 `private val DUMMY_SALT`
  - L187 `private fun verifyStoredCode ( context: Context, key: String, input: String, migrate: (String) -> Unit ): Boolean`
  - L228 `fun isAutoDestructEnabled (context: Context): Boolean`
  - L231 `fun setAutoDestructEnabled (context: Context, enabled: Boolean)`
  - L234 `fun getAutoDestructSeconds (context: Context): Int`
  - L237 `fun setAutoDestructSeconds (context: Context, seconds: Int)`
  - L244 `fun messageTtlSeconds (context: Context): Int?` — The TTL (in seconds) to apply to a newly sent message, or null if
  - L249 `fun isStealthEnabled (context: Context): Boolean`
  - L252 `fun setStealthEnabled (context: Context, enabled: Boolean)`
  - L257 `fun getUsername (context: Context): String?`
  - L260 `fun setUsername (context: Context, username: String)`
  - L263 `fun clearUsername (context: Context)`
  - L276 `fun isUsernameClaimedRemotely (context: Context): Boolean` — Whether our local username has actually been claimed on the directory
  - L279 `fun setUsernameClaimedRemotely (context: Context, claimed: Boolean)`
  - L285 `val autoDestructLabels : List<String>` — Ordered list of the labels shown in the settings dropdown.
  - L288 `private val labelToSeconds : Map<String, Int>`
  - L295 `fun labelToSeconds (label: String): Int`
  - L298 `fun secondsToLabel (seconds: Int): String`
  - L303 `enum class ThemeMode`
  - L307 `private val _themeMode`
  - L313 `val themeMode`
  - L315 `fun setThemeMode (context: Context, mode: ThemeMode)`
  - L328 `fun isCoverTrafficEnabled (context: Context): Boolean` — Cover traffic: send indistinguishable dummy messages at random intervals
  - L331 `fun setCoverTrafficEnabled (context: Context, enabled: Boolean)`
  - L335 `fun isRelayEnabled (context: Context): Boolean` — Whether to fall back to the blind relay at all when a contact isn't on the local network.
  - L338 `fun setRelayEnabled (context: Context, enabled: Boolean)`
  - L369 `private const val MAX_PENDING_SECRETS`
  - L370 `private const val MAX_EXPORTED_SECRETS`
  - L371 `private const val PENDING_SECRET_TTL_MS`
  - L374 `const val EXPORTED_SECRET_VALIDITY_DAYS`
  - L375 `private const val EXPORTED_SECRET_TTL_MS`
  - L377 `internal data class PendingSecret (val hex: String, val issuedAt: Long, val exported: Boolean)`
  - L386 `internal fun withNewSecret ( existing: List<PendingSecret>, secretHex: String, now: Long ): List<PendingSecret>`
  - L398 `internal fun withExported ( existing: List<PendingSecret>, secretHex: String, now: Long ): List<PendingSecret>`
  - L412 `fun addPendingPairSecret (context: Context, secretHex: String)` — Record a freshly-minted secret as "issued, not yet claimed".
  - L425 `fun markPairSecretExported (context: Context, secretHex: String)` — This code has left the device — as an image or as text — so somebody may
  - L435 `fun pendingPairSecrets (context: Context): List<String>` — Every still-valid issued secret, newest first. Expired entries are pruned on read.
  - L437 `private fun readPending (context: Context): List<PendingSecret>`
  - L457 `private fun writePending (context: Context, entries: List<PendingSecret>)`
  - L471 `fun removePendingPairSecret (context: Context, secretHex: String)` — The secret has been bound to a real contact — stop listening for it as an unclaimed one.
  - L493 `fun isBackgroundDeliveryEnabled (context: Context): Boolean`
  - L496 `fun setBackgroundDeliveryEnabled (context: Context, enabled: Boolean)`
  - L506 `enum class NotificationMode`
  - L515 `fun notificationMode (context: Context): NotificationMode`
  - L522 `fun setNotificationMode (context: Context, mode: NotificationMode)`
  - L552 `private fun readDirectAddresses (context: Context): MutableMap<String, String>`
  - L565 `fun directAddress (context: Context, contactId: String): String?` — The remembered address for [contactId], or null if we've never had one.
  - L573 `fun setDirectAddress (context: Context, contactId: String, hostPort: String?)` — Remember (or clear, with a null/blank [hostPort]) where this contact can

### app/src/main/java/com/securemessenger/app/security/BiometricGate.kt (136 سطر)  [package com.securemessenger.app.security]
- L21 `object BiometricGate` — Gates revealing the hidden messenger behind the device's own biometric/PIN
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/navigation/AppNavigation.kt
  - L23 `private const val ALLOWED`
  - L28 `private val GATE_PROBE`
  - L36 `fun canAuthenticate (context: Context): Boolean` — Whether the device has something enrolled (fingerprint, face, or a
  - L39 `fun authenticate ( activity: FragmentActivity, onSuccess: () -> Unit, onFailure: () -> Unit )`
  - L61 `private fun authenticateCryptoBound ( activity: FragmentActivity, cipher: javax.crypto.Cipher, onSuccess: () -> Unit, onFailure: () -> Unit )`
  - L105 `private fun authenticatePlain ( activity: FragmentActivity, onSuccess: () -> Unit, onFailure: () -> Unit )`

### app/src/main/java/com/securemessenger/app/security/DevicePassphrase.kt (49 سطر)  [package com.securemessenger.app.security]
- L21 `object DevicePassphrase` — A random, high-entropy passphrase for the local encrypted database,
  يُستخدم في: app/src/main/java/com/securemessenger/app/service/MessengerService.kt, app/src/main/java/com/securemessenger/app/ui/navigation/AppNavigation.kt, app/src/main/java/com/securemessenger/app/ui/screens/setup/SetupScreen.kt
  - L23 `private const val KEY`
  - L25 `suspend fun getOrCreate (context: Context): CharArray`

### app/src/main/java/com/securemessenger/app/security/DisguiseGateKey.kt (75 سطر)  [package com.securemessenger.app.security]
- L19 `object DisguiseGateKey` — A biometric-gated AES key in the Android Keystore whose ONLY purpose is to
  - L21 `private const val ALIAS`
  - L22 `private const val TRANSFORMATION`
  - L29 `fun encryptCipher (): Cipher` — A fresh ENCRYPT-mode cipher bound to the gate key, to hand to
  - L36 `fun reset ()` — Drop the key (e.g. after a biometric-enrollment change invalidated it).
  - L43 `private fun keyStore (): KeyStore`
  - L46 `private fun getOrCreateKey (): SecretKey`

### app/src/main/java/com/securemessenger/app/security/DisguiseState.kt (24 سطر)  [package com.securemessenger.app.security]
- L12 `object DisguiseState` — Whether the hidden messenger is currently revealed (true) or the app is
  يُستخدم في: app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/ui/navigation/AppNavigation.kt
  - L13 `private val _isRevealed`
  - L14 `val isRevealed : StateFlow<Boolean>`
  - L16 `fun reveal ()`
  - L20 `fun hide ()`

### app/src/main/java/com/securemessenger/app/security/RootDetector.kt (96 سطر)  [package com.securemessenger.app.security]
- L21 `object RootDetector` — Best-effort root/jailbreak detection, checked only at the reveal gate
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/navigation/AppNavigation.kt
  - L23 `private val suPaths`
  - L38 `private val rootPackages`
  - L54 `fun isDeviceRooted (context: Context): Boolean`
  - L57 `private fun hasSuBinary (): Boolean`
  - L60 `private fun hasRootPackage (context: Context): Boolean`
  - L73 `private fun hasTestKeys (): Boolean`
  - L76 `private fun canExecuteSu (): Boolean`
  - L88 `private fun hasMagiskTraces (): Boolean`

### app/src/main/java/com/securemessenger/app/security/SecurePreferences.kt (108 سطر)  [package com.securemessenger.app.security]
- L11 `object SecurePreferences` — SecurePreferences - Encrypted key-value storage using AndroidX Security.
  - L15 `private const val PREFS_NAME`
  - L20 `private fun getMasterKey (context: Context): MasterKey`
  - L29 `fun getPreferences (context: Context)` — Get encrypted shared preferences instance.
  - L40 `fun putString (context: Context, key: String, value: String)` — Store a string value securely.
  - L47 `fun getString (context: Context, key: String, defaultValue: String? = null): String?` — Retrieve a string value securely.
  - L54 `fun putBoolean (context: Context, key: String, value: Boolean)` — Store a boolean value securely.
  - L61 `fun getBoolean (context: Context, key: String, defaultValue: Boolean = false): Boolean` — Retrieve a boolean value securely.
  - L68 `fun putInt (context: Context, key: String, value: Int)` — Store an integer value securely.
  - L75 `fun getInt (context: Context, key: String, defaultValue: Int = 0): Int` — Retrieve an integer value securely.
  - L82 `fun putLong (context: Context, key: String, value: Long)` — Store a long value securely.
  - L89 `fun getLong (context: Context, key: String, defaultValue: Long = 0L): Long` — Retrieve a long value securely.
  - L96 `fun remove (context: Context, key: String)` — Remove a key.
  - L104 `fun clearAll (context: Context)` — Wipe every stored preference — used by a full/duress data wipe so the

### app/src/main/java/com/securemessenger/app/service/MessageCleanupService.kt (103 سطر)  [package com.securemessenger.app.service]
- L16 `class MessageCleanupService : Service()` — MessageCleanupService - Background service for cleaning up expired messages.
  يُستخدم في: app/src/main/java/com/securemessenger/app/receiver/BootReceiver.kt
  - L18 `private val serviceScope`
  - L20 `override fun onCreate ()`
  - L26 `override fun onStartCommand (intent: Intent?, flags: Int, startId: Int): Int`
  - L39 `override fun onBind (intent: Intent?): IBinder?`
  - L41 `override fun onDestroy ()`
- L82 `class MessageCleanupWorker ( context: Context, params: WorkerParameters ) : CoroutineWorker(context, params)` — WorkManager worker for message cleanup.
  يُستخدم في: app/src/main/java/com/securemessenger/app/receiver/BootReceiver.kt
  - L87 `override suspend fun doWork (): Result`

### app/src/main/java/com/securemessenger/app/service/MessengerService.kt (203 سطر)  [package com.securemessenger.app.service]
- L27 `private const val TAG`
- L52 `class MessengerService : Service()` — Keeps the transport alive while the app is closed, so a message arrives when
  يُستخدم في: app/src/main/java/com/securemessenger/app/receiver/BootReceiver.kt, app/src/main/java/com/securemessenger/app/ui/navigation/AppNavigation.kt, app/src/main/java/com/securemessenger/app/ui/screens/settings/SettingsScreen.kt
  - L54 `private val scope`
  - L56 `override fun onBind (intent: Intent?): IBinder?`
  - L58 `override fun onCreate ()`
  - L63 `override fun onStartCommand (intent: Intent?, flags: Int, startId: Int): Int`
  - L87 `override fun onDestroy ()`
  - L192 `private fun buildOngoingNotification (): Notification`

### app/src/main/java/com/securemessenger/app/service/StealthExitTileService.kt (70 سطر)  [package com.securemessenger.app.service]
- L34 `class StealthExitTileService : TileService()` — Quick Settings tile — a second, OS-standard way back in when stealth mode
  - L36 `override fun onClick ()`

### app/src/main/java/com/securemessenger/app/ui/CommonUi.kt (27 سطر)  [package com.securemessenger.app.ui]
- L19 `@Composable fun BackIcon (contentDescription: String? = "رجوع")` — A back-navigation arrow that points the right way in RTL layouts. This
  - L20 `val isRtl`

### app/src/main/java/com/securemessenger/app/ui/ContactDisplay.kt (12 سطر)  [package com.securemessenger.app.ui]
- L3 `private val USERNAME_LIKE`
- L10 `fun formatContactName (raw: String): String` — A stored contact name is either a claimed @username or (when we never

### app/src/main/java/com/securemessenger/app/ui/GlassComponents.kt (266 سطر)  [package com.securemessenger.app.ui]
- L52 `fun Modifier (colors: List<Color>): Modifier` — The page backdrop every glass screen sits on — now the drifting aurora the
- L61 `fun Modifier` — The backdrop for onboarding and security moments (Setup, Loading, Stealth
- L71 `fun Modifier (radius: Dp = 16.dp, strong: Boolean = false): Modifier` — The single reusable "glass card" surface — a translucent rounded panel
- L96 `@Composable fun GlassTopBar ( modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, navigationIcon: (@Composable () -> Unit)? = null, actions: (@Composable RowScope.() -> Unit)? = null, title: @Composable RowScope.() -> Unit )` — The single top bar used by every screen — a floating frosted pill matching
  - L103 `val mc`
- L128 `@Composable fun GlassTopBar ( title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, actions: (@Composable RowScope.() -> Unit)? = null )` — Plain-text convenience overload of [GlassTopBar].
  - L134 `val mc`
- L148 `data class GlassNavItem ( val label: String, val icon: ImageVector, /** e.g. pending connection-request count on "جهات الاتصال" — 0 shows no badge at all. */ val badgeCount: Int = 0 )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/ui/ScreenTourTest.kt, app/src/main/java/com/securemessenger/app/ui/navigation/AppNavigation.kt
- L149 `val label : String,`
- L150 `val icon : ImageVector,`
- L152 `val badgeCount : Int` — e.g. pending connection-request count on "جهات الاتصال" — 0 shows no badge at all.
- L179 `@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GlassBottomNavBar ( items: List<GlassNavItem>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier )` — Floating frosted-pill bottom navigation bar — Telegram-style, glassy, with
  - L185 `val mc`

### app/src/main/java/com/securemessenger/app/ui/MainActivity.kt (75 سطر)  [package com.securemessenger.app.ui]
- L23 `class MainActivity : FragmentActivity()` — MainActivity - Entry point for the app UI.
  يُستخدم في: app/src/main/java/com/securemessenger/app/service/StealthExitTileService.kt
  - L25 `override fun onCreate (savedInstanceState: Bundle?)`
- L62 `@Composable fun SecureMessengerTheme ( content: @Composable () -> Unit )`
  - L67 `val mode`
  - L68 `val darkTheme`

### app/src/main/java/com/securemessenger/app/ui/gesture/Gestures.kt (156 سطر)  [package com.securemessenger.app.ui.gesture]
- L36 `fun Modifier ( enabled: Boolean = true, triggerPx: Float = 140f, maxOffsetPx: Float = 200f, onProgress: (Float) -> Unit = {}, onTriggered: () -> Unit ): Modifier` — A horizontal drag that springs back to rest on release, firing
  - L43 `var rawOffset`
  - L44 `var triggeredThisDrag`
  - L45 `val animatedOffset`
- L77 `fun swipeProgress (offsetPx: Float, triggerPx: Float = 140f): Float` — How far into a [swipeToTrigger] drag the reveal icon should fade/scale in, 0f..1f.
- L84 `class ZoomState (private val minScale: Float = 1f, private val maxScale: Float = 5f)` — Pinch-to-zoom + pan state with clamped scale and a reset — the gesture set
  - L85 `var scale`
  - L87 `var offsetX`
  - L89 `var offsetY`
  - L92 `fun onGesture (pan: Offset, zoom: Float)`
- L109 `@Composable fun rememberZoomState (minScale: Float = 1f, maxScale: Float = 5f): ZoomState`
- L113 `fun Modifier (state: ZoomState): Modifier` — Applies [state]'s current scale/pan and listens for pinch/pan gestures to update it.
- L130 `fun Modifier ( pressedScale: Float = 0.92f, interactionSource: MutableInteractionSource? = null ): Modifier` — A light scale-down while pressed — the tactile "the app noticed my touch"
  - L134 `val source`
  - L135 `var pressed`
  - L137 `val scale`
- L146 `@Composable
private fun LaunchedEffectPressState (source: MutableInteractionSource, onPressed: (Boolean) -> Unit)`

### app/src/main/java/com/securemessenger/app/ui/liquid/LiquidGlass.kt (370 سطر) ⚠  [package com.securemessenger.app.ui.liquid]
- L95 `@Immutable
data class LiquidPalette ( /** The page floor the aurora is painted onto. */ val floor: Color, /** Three drifting light sources. Order is back-to-front. */ val blobs: List<Color>, val surface: Color, val surfaceRaised: Color, /** Top-edge highlight painted over a surface's fill. */ val sheen: Color, val edgeHigh: Color, val edgeLow: Color, val onSurface: Color, val muted: Color, val shadow: Color, )`
- L97 `val floor : Color,` — The page floor the aurora is painted onto.
- L99 `val blobs : List<Color>,` — Three drifting light sources. Order is back-to-front.
- L100 `val surface : Color,`
- L101 `val surfaceRaised : Color,`
- L103 `val sheen : Color,` — Top-edge highlight painted over a surface's fill.
- L104 `val edgeHigh : Color,`
- L105 `val edgeLow : Color,`
- L106 `val onSurface : Color,`
- L107 `val muted : Color,`
- L108 `val shadow : Color,`
- L111 `val LocalLiquid`
- L122 `@Composable
private fun rememberLiquidPalette (dark: Boolean): LiquidPalette`
  - L123 `val primary`
  - L124 `val secondary`
- L179 `@Composable fun LiquidTheme ( dark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f, content: @Composable () -> Unit )` — Follows the Material scheme actually in force, not the system setting.
- L198 `@Composable fun AuroraBackdrop (modifier: Modifier = Modifier, parallaxPx: () -> Float = { 0f })` — The drifting backdrop.
- L214 `fun Modifier ( floor: Color? = null, parallaxPx: () -> Float = { 0f } ): Modifier` — The same backdrop as a modifier, which is what lets every screen in the app
  - L218 `val palette`
  - L219 `val drift`
  - L220 `val a`
  - L225 `val b`
  - L230 `val c`
- L267 `private fun DrawScope (color: Color, center: Offset, radius: Float)`
- L292 `fun Modifier ( shape: Shape = RoundedCornerShape(22.dp), raised: Boolean = false, elevation: Dp = 12.dp, ): Modifier` — One pane of glass: shadow underneath, translucent fill, sheen down from the
  - L297 `val palette`
- L338 `fun Modifier (color: Color, radius: Dp = 18.dp, alpha: Float = 0.45f): Modifier` — A soft coloured halo cast *outside* a shape — used for focus and for unread counts.
  - L339 `val spread`
- L356 `@Composable fun rememberPressScale ( source: MutableInteractionSource, pressedScale: Float = 0.972f ): State<Float>` — Press feedback as a spring rather than a ripple. Glass does not ripple; it
  - L360 `val pressed`

### app/src/main/java/com/securemessenger/app/ui/navigation/AppNavigation.kt (563 سطر) ⚠  [package com.securemessenger.app.ui.navigation]
- L59 `sealed class Screen (val route: String)`
  - L60 `object Calculator`
  - L61 `object Loading`
  - L62 `object Setup`
  - L63 `object ChatList`
  - L64 `object Conversation`
  - L67 `object Settings`
  - L68 `object KeyVerification`
  - L72 `object StealthMode`
  - L73 `object NewChat`
  - L74 `object UsernameSearch`
  - L75 `object ConnectionRequests`
  - L76 `object ContactDetail`
  - L79 `object Profile`
- L101 `private val DISGUISE_ROUTES`
- L110 `private val ROOT_ROUTES`
- L116 `private val NavEasing`
- L117 `private const val SLIDE_MS`
- L118 `private const val CROSSFADE_MS`
- L120 `private fun baseRoute (entry: NavBackStackEntry): String`
- L123 `private fun AnimatedContentTransitionScope`
- L143 `@Composable
private fun RevealedOnly (content: @Composable () -> Unit)`
  - L144 `val isRevealed`
- L149 `@Composable fun AppNavigation ()`
  - L150 `val navController`
  - L151 `val context`
  - L152 `val isRevealed`
  - L153 `val currentRoute`
  - L158 `var showRootWarning`
  - L183 `val selectTab`

### app/src/main/java/com/securemessenger/app/ui/screens/calculator/CalculatorScreen.kt (372 سطر) ⚠  [package com.securemessenger.app.ui.screens.calculator]
- L37 `private data class CalcPalette ( val screenBg: Brush, val display: Color, val expression: Color, val topKey: Color, val topKeyText: Color, val digitKey: Color, val digitText: Color, val operatorKey: Color, val equalsKey: Color, val keyText: Color )`
- L38 `val screenBg : Brush,`
- L39 `val display : Color,`
- L40 `val expression : Color,`
- L41 `val topKey : Color,`
- L42 `val topKeyText : Color,`
- L43 `val digitKey : Color,`
- L44 `val digitText : Color,`
- L45 `val operatorKey : Color,`
- L46 `val equalsKey : Color,`
- L47 `val keyText : Color`
- L50 `private fun calcPalette (dark: Boolean): CalcPalette`
- L78 `private enum class Op (val symbol: String)`
- L87 `@Composable fun CalculatorScreen (onUnlock: () -> Unit, onDuress: () -> Unit = {})` — A real, fully-working calculator — the app's actual disguise. Typing the
  - L88 `val context`
  - L89 `val palette`
  - L90 `var display`
  - L91 `var expression`
  - L92 `var firstOperand`
  - L93 `var pendingOp`
  - L94 `var currentInput`
  - L95 `var justEvaluated`
  - L96 `val formatter`
  - L98 `fun applyOp (op: Op, a: Double, b: Double): Double`
  - L105 `fun reset ()`
  - L114 `fun onDigit (d: String)`
  - L126 `fun onDot ()`
  - L137 `fun onOperator (op: Op)`
  - L155 `fun onEquals ()`
  - L227 `fun onPercent ()`
  - L233 `fun onSign ()`
- L327 `@Preview(name =                    , showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CalculatorScreenDarkPreview ()`
- L333 `@Preview(name =                     , showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun CalculatorScreenLightPreview ()`
- L339 `@Composable
private fun RowScope (btn: CalcButton, wide: Boolean)`
  - L340 `val interactionSource`
  - L341 `val isPressed`
  - L342 `val scale`
  - L343 `val haptics`
  - L344 `val shape`
- L369 `private data class CalcButton (val label: String, val color: Color, val textColor: Color, val onClick: () -> Unit)`
- L371 `private fun Int ()`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/AlbumBubble.kt (106 سطر)  [package com.securemessenger.app.ui.screens.chat]
- L29 `@Composable
internal fun AlbumBubble ( images: List<MessageUiModel>, loadMedia: MediaLoader, onOpenImageViewer: (MediaCodec.LocalMedia) -> Unit )`
  - L34 `val mc`
  - L35 `val isSent`
  - L36 `val shape`
  - L37 `val columns`
- L80 `@Composable
private fun AlbumThumbnail (media: MediaCodec.LocalMedia, loadMedia: MediaLoader, onClick: () -> Unit)`
  - L81 `var bitmap`
  - L82 `val context`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/AttachmentPicking.kt (40 سطر)  [package com.securemessenger.app.ui.screens.chat]
- L13 `internal fun createCameraOutputUri (context: android.content.Context): Uri`
  - L14 `val dir`
  - L15 `val file`
- L19 `internal data class PickedFile (val bytes: ByteArray, val mimeType: String, val fileName: String, val mediaType: Int)`
- L21 `internal fun readPickedFile (context: android.content.Context, uri: Uri): PickedFile?`
  - L22 `val resolver`
  - L23 `val mimeType`
  - L24 `val mediaType`
  - L32 `var fileName`
  - L37 `val bytes`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatCommon.kt (267 سطر)  [package com.securemessenger.app.ui.screens.chat]
- L55 `internal val chatTimeLocale : java.util.Locale`
- L64 `@Composable fun ShimmerBox (modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(8.dp))` — A soft sweeping gradient over a shape — used while media is still decoding/loading.
  - L65 `val transition`
  - L66 `val translate`
  - L75 `val base`
  - L76 `val highlight`
- L98 `@Composable fun ConnectionStatusBar ()` — Thin live bar reflecting the real WebSocket connection state — follows the
  - L99 `val state`
- L145 `private val avatarColors`
- L154 `private fun avatarInitials (name: String): String`
  - L155 `val parts`
- L169 `@Composable fun Avatar ( name: String, size: androidx.compose.ui.unit.Dp, id: String = name, avatarBytes: ByteArray? = null, isVerified: Boolean = false )` — Gradient two-letter initials by default (like Telegram's placeholder
  - L176 `val color`
  - L177 `val bitmap`
- L224 `@Composable
internal fun EncryptedBanner ()`
  - L225 `val mc`
- L254 `@Composable
internal fun DateSeparator (label: String)`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListItem.kt (343 سطر) ⚠  [package com.securemessenger.app.ui.screens.chat]
- L52 `private fun formatChatTime (timestamp: Long): String`
  - L54 `val now`
  - L55 `val then`
  - L56 `val sameYear`
  - L57 `val dayDelta`
  - L62 `val pattern`
- L81 `@OptIn(ExperimentalFoundationApi::class)
@Composable fun ChatListItem ( contact: ContactUiModel, onClick: () -> Unit, onTogglePin: () -> Unit = {}, entranceDelayMillis: Int = -1, )` — One conversation, as a pane of glass.
  - L87 `val palette`
  - L88 `val haptics`
  - L89 `val primary`
  - L90 `var showMenu`
  - L92 `val interaction`
  - L93 `val pressScale`
  - L95 `val entrance`
  - L103 `val unread`
  - L104 `val pinned`
- L243 `@Composable
private fun AvatarWithRing (contact: ContactUiModel, unread: Boolean)`
  - L244 `val palette`
  - L245 `val primary`
- L272 `@Composable
private fun UnreadPill (count: Int)`
  - L273 `val primary`
  - L274 `val pulse`
  - L275 `val glow`
- L302 `@Preview(name =                        , showBackground = true, backgroundColor = 0xFF06080D)
@Composable
private fun ChatListItemDarkPreview ()`
- L328 `@Preview(name =                         , showBackground = true, backgroundColor = 0xFFEDF1F8)
@Composable
private fun ChatListItemLightPreview ()`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListScreen.kt (646 سطر) ⚠  [package com.securemessenger.app.ui.screens.chat]
- L71 `@Composable fun ChatListScreen ( onConversationClick: (String) -> Unit, onNewChatClick: () -> Unit, onConnectionRequestsClick: () -> Unit = {}, viewModel: ChatListViewModel = viewModel(), connectionRequestsViewModel: ConnectionRequestsViewModel = viewModel() )` — The home screen, rebuilt on the liquid-glass layer in
  - L78 `val contacts`
  - L79 `val isLoading`
  - L80 `val incomingRequests`
- L105 `@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChatListContent ( contacts: List<ContactUiModel>, isLoading: Boolean, pendingRequestCount: Int, onConversationClick: (String) -> Unit, onTogglePin: (String) -> Unit, onNewChatClick: () -> Unit, onConnectionRequestsClick: () -> Unit, modifier: Modifier = Modifier )`
- L249 `@Composable
private fun HeaderTitle ( collapse: Float, conversationCount: Int )`
  - L253 `val palette`
  - L254 `val primary`
- L310 `private fun conversationSubtitle (count: Int): String`
- L327 `@Composable
private fun PendingRequestsBanner (count: Int, onClick: () -> Unit)`
  - L328 `val primary`
- L364 `private fun pendingRequestsLabel (count: Int): String`
- L381 `@Composable
private fun LiquidConnectionStrip ()`
  - L382 `val palette`
  - L383 `val state`
- L438 `@Composable
private fun SearchField ( value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier )`
  - L443 `val palette`
  - L444 `val primary`
  - L445 `val interaction`
  - L446 `val focused`
- L499 `@Composable
private fun EmptyState (onNewChatClick: () -> Unit, topPadding: androidx.compose.ui.unit.Dp)`
  - L500 `val palette`
  - L501 `val primary`
  - L502 `val breath`
  - L503 `val scale`
- L571 `@Composable
private fun NoResults (query: String, topPadding: androidx.compose.ui.unit.Dp)`
  - L572 `val palette`
- L598 `@Composable
private fun LoadingList (topPadding: androidx.compose.ui.unit.Dp)`
- L611 `@Composable
private fun ChatListItemSkeleton ()`
- L634 `private fun Modifier (onClick: () -> Unit): Modifier`
  - L635 `val interaction`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/ComposeStrips.kt (126 سطر)  [package com.securemessenger.app.ui.screens.chat]
- L28 `@Composable
internal fun ComposeContextStrip (isEdit: Boolean, snippet: String, onCancel: () -> Unit)`
  - L29 `val mc`
- L73 `@Composable
internal fun MediaCaptionStrip (media: PickedFile, onCancel: () -> Unit)`
  - L74 `val thumbnail`
  - L79 `val mc`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/ConnectionRequestsScreen.kt (129 سطر)  [package com.securemessenger.app.ui.screens.chat]
- L35 `@Composable fun ConnectionRequestsScreen ( onBackClick: () -> Unit, onAccepted: (contactId: String) -> Unit, viewModel: ConnectionRequestsViewModel = viewModel() )` — Incoming self-introductions found via username search, waiting on an
  - L40 `val requests`
  - L41 `val actionInProgress`
  - L42 `val mc`
- L95 `@Composable
private fun ConnectionRequestRow ( request: IncomingRequestUiModel, busy: Boolean, onAccept: () -> Unit, onReject: () -> Unit )`
  - L101 `val mc`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt (77 سطر)  [package com.securemessenger.app.ui.screens.chat]
- L31 `@Composable
internal fun MediaGridTile ( message: EncryptedMessage, repository: com.securemessenger.app.data.repository.SecureRepository, onImageClick: (androidx.compose.ui.graphics.ImageBitmap) -> Unit )`
  - L36 `val mc`
  - L37 `val local`
  - L38 `var bitmap`
  - L40 `val context`
  - L56 `val bmp`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt (578 سطر) ⚠  [package com.securemessenger.app.ui.screens.chat]
- L55 `@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ContactDetailScreen ( contactId: String, onBackClick: () -> Unit, onVerifyClick: () -> Unit )` — Contact info screen: avatar (tap to set a local photo), nickname, mute,
  - L60 `val repository`
  - L61 `val scope`
  - L62 `val context`
  - L63 `val mc`
  - L65 `var contact`
  - L66 `var avatarBytes`
  - L67 `var displayName`
  - L68 `var nickname`
  - L69 `var mediaMessages`
  - L70 `var showBlockConfirm`
  - L71 `var showNicknameDialog`
  - L72 `var showAddressDialog`
  - L73 `var fullscreenImage`
  - L74 `var transport`
  - L78 `suspend fun reload ()`
  - L128 `val avatarPicker`
  - L141 `val shownName`
  - L142 `val isMuted`
  - L143 `val isBlocked`
  - L144 `val isVerified`
  - L149 `val contactKeyHex`
  - L437 `val shownImage`
- L464 `@Composable
private fun ConnectivityCard ( status: com.securemessenger.app.network.TransportStatus?, onEditAddress: () -> Unit )`
  - L468 `val mc`
  - L469 `val tint`
- L515 `@Composable
private fun QuickAction (icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit)`
  - L516 `val mc`
- L529 `@Composable
private fun SecurityNumberCard (keyHex: String?, onClick: () -> Unit)`
  - L530 `val mc`
- L560 `@Composable
private fun MediaPreviewStrip (tiles: List<androidx.compose.ui.graphics.ImageBitmap>)`
  - L561 `val gradients`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationRows.kt (90 سطر)  [package com.securemessenger.app.ui.screens.chat]
- L11 `internal sealed class ChatRow (val key: String)`
  - L12 `class Day (val label: String, id: String) : ChatRow( )`
  - L13 `class Msg ( val message: MessageUiModel, // Whether this bubble sits first/last in a run of consecutive // same-sender messages (same calendar day) — drives the tighter // grouped spacing and the "tail only on the last one" shape. val isFirstInGroup: Boolean, val isLastInGroup: Boolean ) : ChatRow( )`
  - L14 `val message : MessageUiModel,`
  - L18 `val isFirstInGroup : Boolean,`
  - L19 `val isLastInGroup : Boolean`
  - L24 `class Album (val images: List<MessageUiModel>) : ChatRow( )`
- L27 `private const val ALBUM_BATCH_WINDOW_MS`
- L29 `internal fun buildRows (messages: List<MessageUiModel>): List<ChatRow>`
  - L30 `val rows`
  - L31 `var lastDay`
  - L32 `var i`
- L73 `private fun dayKey (ts: Long): String`
- L76 `private fun dayLabel (ts: Long): String`
  - L77 `val now`
  - L78 `val then`
  - L79 `fun sameDay (offset: Int): Boolean`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationScreen.kt (703 سطر) ⚠  [package com.securemessenger.app.ui.screens.chat]
- L58 `@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun ConversationScreen ( contactId: String, onBackClick: () -> Unit, onVerificationClick: () -> Unit, onContactInfoClick: () -> Unit = {}, viewModel: ConversationViewModel = viewModel( key = contactId, factory = ConversationViewModelFactory(contactId) ) )`
  - L68 `var messageText`
  - L69 `val messages`
  - L70 `val isSending`
  - L71 `val error`
  - L72 `val contactName`
  - L73 `val contactAvatar`
  - L74 `val contactVerified`
  - L75 `val isContactTyping`
  - L76 `val establishingSession`
  - L81 `val loadMedia`
  - L83 `val listState`
  - L84 `val mc`
  - L85 `val context`
  - L86 `val scope`
  - L90 `val seenBubbleKeys`
  - L92 `var highlightedClientId`
  - L94 `var selectionMode`
  - L95 `var selectedIds`
  - L96 `var actionTarget`
  - L97 `var replyingTo`
  - L98 `var editingTarget`
  - L99 `var viewerMedia`
  - L100 `var searchActive`
  - L101 `var searchQuery`
  - L102 `val clipboard`
  - L103 `val snackbarHostState`
  - L119 `var showAttachSheet`
  - L120 `var pendingCameraUri`
  - L121 `var pendingMedia`
  - L123 `val attachLauncher`
  - L139 `val multiPhotoLauncher`
  - L151 `val cameraLauncher`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationSheets.kt (155 سطر)  [package com.securemessenger.app.ui.screens.chat]
- L33 `@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet ( onDismiss: () -> Unit, onCameraClick: () -> Unit, onGalleryClick: () -> Unit, onAlbumClick: () -> Unit, onFileClick: () -> Unit )`
- L57 `@Composable
private fun AttachmentOption (icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit)`
- L80 `@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionSheet ( target: MessageUiModel, onDismiss: () -> Unit, onReact: (String) -> Unit, onReply: () -> Unit, onCopy: () -> Unit, onEdit: () -> Unit, onDeleteForEveryone: () -> Unit, onDeleteForMe: () -> Unit, onSelect: () -> Unit )`
  - L91 `val sheetState`
  - L92 `val isMine`
  - L93 `val isText`
- L141 `@Composable
private fun ActionRow (icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false)`
  - L142 `val tint`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationViewModelFactory.kt (18 سطر)  [package com.securemessenger.app.ui.screens.chat]
- L7 `class ConversationViewModelFactory ( private val contactId: String ) : ViewModelProvider.Factory`
- L8 `private val contactId : String`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/MediaContent.kt (726 سطر) ⚠  [package com.securemessenger.app.ui.screens.chat]
- L59 `internal fun decodeSampledBitmap (bytes: ByteArray, maxDimension: Int): android.graphics.Bitmap?`
- L94 `internal suspend fun decodeGuarded ( context: android.content.Context, bytes: ByteArray, maxDimension: Int ): android.graphics.Bitmap?`
- L117 `internal fun interface`
  - L118 `suspend fun load (media: MediaCodec.LocalMedia)`
- L122 `@Composable
internal fun MediaContent ( media: MediaCodec.LocalMedia, loadMedia: MediaLoader, isSent: Boolean, onOpenImageViewer: (MediaCodec.LocalMedia) -> Unit )`
  - L128 `val mc`
- L139 `@Composable
private fun ImageContent ( media: MediaCodec.LocalMedia, loadMedia: MediaLoader, onOpenImageViewer: (MediaCodec.LocalMedia) -> Unit )`
  - L144 `var bitmap`
  - L145 `var aspectRatio`
  - L146 `var failed`
  - L147 `val context`
- L198 `@Composable
private fun VideoContent (media: MediaCodec.LocalMedia, loadMedia: MediaLoader)`
  - L199 `var thumbnail`
  - L200 `var durationMs`
  - L201 `var bytesCache`
  - L202 `var showPlayer`
  - L203 `var failed`
  - L210 `var revealed`
  - L211 `var loading`
- L313 `@Composable
private fun VideoPlayerDialog (bytes: ByteArray, onDismiss: () -> Unit)`
  - L314 `var mediaPlayer`
  - L315 `var isPlaying`
  - L316 `var progress`
  - L317 `var prepared`
- L400 `@Composable
private fun FileContent ( media: MediaCodec.LocalMedia, loadMedia: MediaLoader, icon: androidx.compose.ui.graphics.vector.ImageVector, isSent: Boolean )`
  - L406 `val mc`
  - L407 `val context`
  - L408 `val scope`
  - L409 `var opening`
- L450 `@Composable
private fun VoiceContent (media: MediaCodec.LocalMedia, loadMedia: MediaLoader, isSent: Boolean)`
  - L451 `val mc`
  - L452 `val scope`
  - L453 `var isPlaying`
  - L454 `var isLoading`
  - L455 `var player`
  - L456 `var progress`
  - L457 `var speed`
  - L478 `fun seekTo (fraction: Float)`
- L573 `@Composable
private fun WaveformScrubber ( waveform: List<Float>, progress: Float, tint: Color, enabled: Boolean, onSeek: (Float) -> Unit, modifier: Modifier = Modifier )`
- L612 `private fun openInExternalApp (context: android.content.Context, bytes: ByteArray, media: MediaCodec.LocalMedia)`
  - L623 `val dir`
  - L624 `val file`
  - L626 `val uri`
  - L632 `val extension`
  - L634 `val resolvedType`
  - L637 `val intent`
- L649 `internal fun formatElapsed (ms: Long): String`
  - L650 `val totalSeconds`
- L657 `@Composable
internal fun FullScreenImageViewer ( media: MediaCodec.LocalMedia, loadMedia: MediaLoader, onDismiss: () -> Unit )`
  - L662 `var bitmap`
  - L663 `val context`
  - L674 `val zoomState`
  - L675 `var dragOffsetY`
  - L676 `val scrimAlpha`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/MessageBubble.kt (302 سطر) ⚠  [package com.securemessenger.app.ui.screens.chat]
- L53 `@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble ( message: MessageUiModel, isLastInGroup: Boolean, loadMedia: MediaLoader, onLongPress: () -> Unit, onSwipeReply: () -> Unit, onOpenImageViewer: (MediaCodec.LocalMedia) -> Unit, seenKeys: MutableSet<String>, isHighlighted: Boolean = false, onJumpToReplyTarget: (String) -> Unit = {}, isSelectionMode: Boolean = false, isSelected: Boolean = false, onToggleSelect: () -> Unit = {} )`
  - L67 `val mc`
  - L68 `val isSent`
  - L69 `val time`
  - L76 `val tailCorner`
  - L77 `val shape`
  - L81 `var swipeProgress`
  - L86 `val bubbleKey`
  - L87 `val alreadySeen`
  - L89 `val visibleState`
  - L92 `val highlightColor`
- L284 `@Composable
private fun SelectionCheckmark (isSelected: Boolean, onToggle: () -> Unit)`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/MessageInput.kt (302 سطر) ⚠  [package com.securemessenger.app.ui.screens.chat]
- L54 `@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageInput ( value: String, onValueChange: (String) -> Unit, onSendClick: () -> Unit, onAttachClick: () -> Unit, onVoiceMessageReady: (VoiceRecorder.RecordingResult) -> Unit, onVoiceError: (String) -> Unit, sending: Boolean, // True while an attachment is staged and waiting for an (optional) // caption — the send button stays active even with an empty text field. forceSendButton: Boolean = false )`
  - L66 `val context`
  - L67 `val scope`
  - L68 `val mc`
  - L69 `var isRecording`
  - L70 `var cancelProgress`
  - L71 `var elapsedMs`
  - L72 `var showEmojiPanel`
  - L74 `val micPermissionLauncher`
- L276 `@Composable
private fun EmojiPanel (onEmojiClick: (String) -> Unit)`
  - L277 `val emojis`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/NewChatScreen.kt (600 سطر) ⚠  [package com.securemessenger.app.ui.screens.chat]
- L61 `private const val PAIR_TAG`
- L70 `@Composable fun NewChatScreen ( /** Null on the tab root — a root has nowhere to go back to. */ onBackClick: (() -> Unit)? = null, onContactAdded: (String) -> Unit, onSearchByUsernameClick: () -> Unit = {}, onConnectionRequestsClick: () -> Unit = {}, connectionRequestsViewModel: ConnectionRequestsViewModel = viewModel() )` — No directory server to search anymore — adding someone is an in-person
  - L78 `val repository`
  - L79 `val mc`
  - L80 `val context`
  - L81 `val scope`
  - L82 `val pendingRequests`
  - L83 `val clipboard`
  - L84 `var isLoading`
  - L85 `var errorMessage`
  - L89 `var statusMessage`
  - L90 `var myUsername`
  - L91 `var showShareDialog`
  - L97 `var showCopyDialog`
  - L102 `var pendingKeyChangeScan`
  - L104 `var myQrPayload`
  - L109 `var myPairSecretHex`
  - L116 `var qrLoadAttempted`
  - L148 `val myQrBitmap`
  - L152 `suspend fun pairFromPayload (scanned: String, allowKeyChange: Boolean = false)`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/QrImage.kt (312 سطر) ⚠  [package com.securemessenger.app.ui.screens.chat]
- L39 `object QrImage` — Rendering a pairing QR to an image, sharing it, and reading one back out of a
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/screens/verification/KeyVerificationScreen.kt
  - L58 `private const val SIZE_PX`
  - L61 `private const val MAX_DECODE_EDGE`
  - L72 `private val ERROR_CORRECTION_LADDER`
  - L97 `fun identityPayload ( userId: String, publicKeyHex: String, username: String, directAddress: String? = null, pairSecretHex: String? = null ): String` — THE app's code. One shape, built in one place.
  - L117 `fun stripRelaySecret (payload: String): String` — A copy of [payload] with the relay pair secret removed. The result pairs
  - L148 `fun render (data: String): Bitmap?` — Render a pairing code, and **prove we can read it back before returning
  - L173 `private fun verifyReadable (bitmap: Bitmap): String?`
  - L190 `private fun renderAt (data: String, correction: ErrorCorrectionLevel): Bitmap?`
  - L228 `fun share (context: Context, payload: String, caption: String): Boolean` — Write the code to a cache file and hand it to the system share sheet.
  - L258 `fun decodeFromImage (context: Context, uri: Uri): String?` — Read a QR code out of a picture the user selected. Returns null when the
  - L268 `fun decodeFromBitmap (bitmap: Bitmap): String?` — The decoding itself, split out from the file loading so it can be exercised directly.
  - L296 `private fun loadDownsampled (context: Context, uri: Uri): Bitmap?`

### app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt (219 سطر)  [package com.securemessenger.app.ui.screens.chat]
- L30 `private sealed class LookupState`
  - L31 `data object Idle`
  - L32 `data object Loading`
  - L33 `data class Found (val result: DirectoryClient.LookupResult.Found) : LookupState()`
  - L34 `data object NotFound`
  - L35 `data object Unreachable`
- L46 `@Composable fun UsernameSearchScreen (onBackClick: () -> Unit)` — Search for someone by @username instead of QR — additive to [NewChatScreen]'s
  - L47 `val repository`
  - L48 `val mc`
  - L49 `val scope`
  - L51 `var query`
  - L52 `var lookupState`
  - L53 `var alreadyPending`
  - L54 `var sending`
  - L55 `var sendError`
  - L56 `var justSent`
- L141 `@Composable
private fun SearchField (value: String, onValueChange: (String) -> Unit, textColor: Color)`
- L165 `@Composable
private fun EmptyResultMessage (text: String, textColor: Color)`
- L176 `@Composable
private fun FoundResultCard ( username: String, alreadyPending: Boolean, sending: Boolean, errorMessage: String?, onSendRequest: () -> Unit )`
  - L183 `val mc`

### app/src/main/java/com/securemessenger/app/ui/screens/loading/LoadingScreen.kt (77 سطر)  [package com.securemessenger.app.ui.screens.loading]
- L26 `@Composable fun LoadingScreen ()` — Shown briefly while an existing account unlocks itself with its device-stored key.
  - L27 `val mc`
- L68 `@Preview(name =                 , showBackground = true)
@Composable
private fun LoadingScreenDarkPreview ()`
- L74 `@Preview(name =                  , showBackground = true)
@Composable
private fun LoadingScreenLightPreview ()`

### app/src/main/java/com/securemessenger/app/ui/screens/settings/ProfileScreen.kt (282 سطر)  [package com.securemessenger.app.ui.screens.settings]
- L46 `@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ProfileScreen ( onBackClick: () -> Unit, onVerifyClick: () -> Unit, onShowQrClick: () -> Unit = {} )` — My own profile: photo, display name (local-only — never synced to
  - L51 `val repository`
  - L52 `val scope`
  - L53 `val context`
  - L54 `val mc`
  - L56 `var avatarBytes`
  - L57 `var displayName`
  - L58 `var username`
  - L59 `var fingerprint`
  - L60 `var showNameDialog`
  - L88 `val avatarPicker`

### app/src/main/java/com/securemessenger/app/ui/screens/settings/SettingsComponents.kt (152 سطر)  [package com.securemessenger.app.ui.screens.settings]
- L23 `@Composable
internal fun GlossaryItem (term: String, explanation: String)`
  - L24 `val mc`
- L33 `@Composable
internal fun SettingsGroupLabel (title: String)`
  - L34 `val mc`
- L45 `@Composable
internal fun SettingsGroupCard (content: @Composable ColumnScope.() -> Unit)`
- L56 `@Composable
internal fun SettingsDivider ()`
  - L57 `val mc`
- L73 `@Composable
internal fun SettingsRow ( icon: androidx.compose.ui.graphics.vector.ImageVector, iconTint: Color, title: String, onClick: (() -> Unit)? = null, danger: Boolean = false, trailing: @Composable () -> Unit = {} )`
  - L81 `val mc`
- L103 `@Composable
internal fun RowChevron ()`
  - L104 `val mc`
- L110 `@Composable
internal fun RowValue (text: String)`
  - L111 `val mc`
- L117 `@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDropdownSheet ( title: String, options: List<String>, selectedOption: String, onDismiss: () -> Unit, onOptionSelected: (String) -> Unit )`
  - L124 `val mc`

### app/src/main/java/com/securemessenger/app/ui/screens/settings/SettingsScreen.kt (767 سطر) ⚠  [package com.securemessenger.app.ui.screens.settings]
- L46 `@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsScreen ( /** Null on the tab root — a root has nowhere to go back to. */ onBackClick: (() -> Unit)? = null, onVerificationClick: () -> Unit, onStealthModeClick: () -> Unit, onDataWiped: () -> Unit, onProfileClick: () -> Unit = {}, )` — SettingsScreen - Security settings and app configuration.
  - L54 `val context`
  - L55 `val scope`
  - L56 `val snackbarHostState`
  - L57 `val mc`
  - L59 `val themeMode`
  - L60 `var autoDestructEnabled`
  - L61 `var autoDestructTime`
  - L64 `var showThemeDialog`
  - L65 `var showDestructTimeDialog`
  - L66 `var showWipeDialog`
  - L67 `var showCodeDialog`
  - L68 `var showDuressDialog`
  - L71 `var accessCodeSet`
  - L72 `var duressCodeSet`
  - L73 `var relayEnabled`
  - L74 `var backgroundDelivery`
  - L75 `var notificationMode`
  - L76 `var showNotificationDialog`
  - L77 `var coverTrafficEnabled`
  - L80 `val relayAvailable`
  - L81 `var showCryptoGlossary`
  - L82 `val username`
  - L88 `var updateState`
  - L89 `var isCheckingUpdate`
  - L90 `var downloadProgress`
  - L94 `var awaitingInstallPermissionFor`
  - L96 `suspend fun downloadAndInstallUpdate (available: UpdateChecker.CheckResult.Available)`
  - L110 `val installPermissionLauncher`
  - L127 `var myDisplayName`
  - L128 `var myAvatar`
  - L143 `val themeLabel`

### app/src/main/java/com/securemessenger/app/ui/screens/settings/StealthModeScreen.kt (271 سطر)  [package com.securemessenger.app.ui.screens.settings]
- L43 `@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StealthModeScreen ( onBackClick: () -> Unit, onEnableStealth: () -> Unit )` — StealthModeScreen - Hide app icon and enable secret access.
  - L47 `val context`
  - L48 `val packageManager`
  - L49 `val mc`
  - L51 `var isStealthEnabled`
  - L52 `var showWarning`
  - L53 `var showDetails`
- L235 `@Preview(name =                      , showBackground = true)
@Composable
private fun StealthModeScreenDarkPreview ()`
- L241 `@Preview(name =                       , showBackground = true)
@Composable
private fun StealthModeScreenLightPreview ()`
- L246 `@Composable fun StepItem ( number: String, text: String )`
  - L250 `val mc`

### app/src/main/java/com/securemessenger/app/ui/screens/setup/SetupScreen.kt (234 سطر)  [package com.securemessenger.app.ui.screens.setup]
- L29 `internal val USERNAME_REGEX`
- L36 `@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SetupScreen ( onSetupComplete: () -> Unit )` — SetupScreen - Initial app setup and key generation.
  - L39 `var step`
  - L40 `var displayName`
  - L41 `var username`
  - L42 `var accessCode`
  - L43 `var isGenerating`
  - L44 `var errorMessage`
  - L45 `var profileReady`
  - L48 `var avatarBytes`
  - L50 `val scrollState`
  - L51 `val scope`
  - L52 `val context`
  - L53 `val mc`
  - L55 `val avatarPicker`
- L218 `@Composable
private fun StepDots (step: Int)`
  - L219 `val mc`
  - L220 `val inactive`

### app/src/main/java/com/securemessenger/app/ui/screens/setup/SetupSteps.kt (414 سطر) ⚠  [package com.securemessenger.app.ui.screens.setup]
- L53 `@Composable fun WelcomeStep (onContinue: () -> Unit)` — The three [SetupScreen] steps, plus their small supporting composables.
  - L54 `val mc`
- L98 `@Composable
private fun SetupField ( label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, prefix: String? = null, isError: Boolean = false, supportingText: String? = null, letterSpaced: Boolean = false, keyboardOptions: KeyboardOptions = KeyboardOptions.Default )`
  - L109 `val mc`
- L149 `private fun Int ()`
- L152 `@Composable fun ProfileStep ( displayName: String, onDisplayNameChange: (String) -> Unit, username: String, onUsernameChange: (String) -> Unit, accessCode: String, onAccessCodeChange: (String) -> Unit, avatarBytes: ByteArray?, onPickAvatar: () -> Unit, onNext: () -> Unit )`
  - L165 `val isValidFormat`
  - L166 `val isValidCode`
  - L167 `val mc`
- L275 `@Composable fun GeneratingStep ( isGenerating: Boolean, onComplete: () -> Unit )`
  - L279 `val mc`
- L339 `@Composable
private fun KeyGenerationHelix (modifier: Modifier = Modifier)`
  - L340 `val infiniteTransition`
  - L341 `val angle`
  - L347 `val strandA`
  - L348 `val strandB`
  - L349 `val centerDot`
  - L350 `val strokePx`
- L382 `@Preview(name =                  , showBackground = true)
@Composable
private fun WelcomeStepPreview ()`
- L388 `@Preview(name =                  , showBackground = true)
@Composable
private fun ProfileStepPreview ()`
- L409 `@Preview(name =                     , showBackground = true)
@Composable
private fun GeneratingStepPreview ()`
- L413 `private fun Modifier`

### app/src/main/java/com/securemessenger/app/ui/screens/verification/KeyVerificationScreen.kt (533 سطر) ⚠  [package com.securemessenger.app.ui.screens.verification]
- L44 `@OptIn(ExperimentalMaterial3Api::class)
@Composable fun KeyVerificationScreen ( contactId: String?, onBackClick: () -> Unit )`
  - L48 `var showMyQR`
  - L49 `var verificationStatus`
  - L50 `var myPublicKeyHex`
  - L51 `var fingerprint`
  - L52 `var myUserId`
  - L53 `val scope`
  - L54 `val repository`
  - L55 `val mc`
  - L56 `val context`
  - L73 `var chosenContactId`
  - L74 `var chosenContactName`
  - L75 `var contacts`
  - L76 `var contactsLoaded`
  - L77 `var showContactPicker`
  - L111 `val scanLauncher`
  - L125 `fun launchScan ()`
  - L136 `fun scanOrChooseFirst ()` — Scan straight away when we know the target, otherwise ask who first.
  - L158 `var loadAttempted`
  - L170 `val pendingLabel`
  - L171 `var myUsername`
  - L181 `val canScan`
  - L194 `val qrPayload`
  - L204 `val myQRBitmap`
- L510 `@Composable
private fun StatusPill ( icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, subtitle: String )`

### app/src/main/java/com/securemessenger/app/ui/screens/verification/VerificationComponents.kt (29 سطر)  [package com.securemessenger.app.ui.screens.verification]
- L14 `sealed class VerificationStatus`
  - L15 `object None`
  - L16 `object Verified`
  - L19 `object Failed` — A readable identity key that isn't this contact's — the only state that should alarm anyone.
  - L27 `object NotAKey` — The scan was not an identity key at all. Previously indistinguishable

### app/src/main/java/com/securemessenger/app/ui/theme/Color.kt (27 سطر)  [package com.securemessenger.app.ui.theme]
- L10 `val Brand` — Brand + semantic color palette (shared across light/dark). Values are
- L11 `val AccentPurple`
- L12 `val SemanticRed`
- L13 `val SemanticGreen`
- L14 `val SemanticOrange`
- L15 `val SemanticAmber`
- L16 `val SemanticPink`
- L19 `object SemanticColors` — Named accessors for the semantic palette above — used wherever a screen needs a specific accent by meaning rather than by hex.
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListItem.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/loading/LoadingScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/settings/ProfileScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/settings/SettingsScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/settings/StealthModeScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/setup/SetupSteps.kt, app/src/main/java/com/securemessenger/app/ui/screens/verification/KeyVerificationScreen.kt
  - L20 `val red`
  - L21 `val green`
  - L22 `val orange`
  - L23 `val amber`
  - L24 `val pink`
  - L25 `val purple`

### app/src/main/java/com/securemessenger/app/ui/theme/Theme.kt (172 سطر)  [package com.securemessenger.app.ui.theme]
- L25 `object Dims`
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/screens/chat/AlbumBubble.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatCommon.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ComposeStrips.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationSheets.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/MediaContent.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/MessageBubble.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/MessageInput.kt, app/src/main/java/com/securemessenger/app/ui/screens/settings/ProfileScreen.kt
  - L26 `val s2`
  - L27 `val s4`
  - L28 `val s6`
  - L29 `val s8`
  - L30 `val s12`
  - L31 `val s16`
  - L32 `val s24`
  - L33 `val avatarSmall`
  - L34 `val avatarContactDetail`
  - L35 `val avatarProfile`
  - L36 `val bubbleRadius`
  - L37 `val bubbleTail`
  - L38 `val bubbleMaxWidth`
- L42 `data class MessengerColors ( val sentBubble: Color, val receivedBubble: Color, val onSent: Color, val onReceived: Color, val sentMeta: Color, val receivedMeta: Color, val readTick: Color, // Thin, low-alpha separators between rows (settings sections, composer // top edge, chat-list rows). val divider: Color, // Stronger, near-solid border used specifically at screen-level header // and footer edges (conversation top bar / input bar). val headerBorder: Color, // Page backdrops: two colors each. In dark mode both stops are the same // flat color (mockups render most dark screens as flat, not gradient); // in light mode they are a genuine soft two-stop diagonal gradient. val listGradient: List<Color>, val chatGradient: List<Color>, // Radial backdrop reserved for onboarding/security moments (Setup, // Loading, Stealth Mode). val onboardingGradient: List<Color>, val glassCard: Color, val glassCardStrong: Color, val glassOnCard: Color, )`
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/screens/chat/AlbumBubble.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatCommon.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ComposeStrips.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationSheets.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/MediaContent.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/MessageBubble.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/MessageInput.kt, app/src/main/java/com/securemessenger/app/ui/screens/settings/ProfileScreen.kt
- L43 `val sentBubble : Color,`
- L44 `val receivedBubble : Color,`
- L45 `val onSent : Color,`
- L46 `val onReceived : Color,`
- L47 `val sentMeta : Color,`
- L48 `val receivedMeta : Color,`
- L49 `val readTick : Color,`
- L52 `val divider : Color,`
- L55 `val headerBorder : Color,`
- L59 `val listGradient : List<Color>,`
- L60 `val chatGradient : List<Color>,`
- L63 `val onboardingGradient : List<Color>,`
- L64 `val glassCard : Color,`
- L65 `val glassCardStrong : Color,`
- L66 `val glassOnCard : Color,`
- L69 `val LocalMessengerColors`
- L86 `private val DarkColors`
- L101 `private val LightColors`
- L116 `private val DarkMessenger`
- L131 `private val LightMessenger`
- L148 `val ConcreteShapes`
- L157 `@Composable fun MessengerTheme ( darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit )`
  - L161 `val colorScheme`
  - L162 `val messenger`

### app/src/main/java/com/securemessenger/app/ui/theme/Type.kt (120 سطر)  [package com.securemessenger.app.ui.theme]
- L13 `val Typography` — Typography for Material Design 3.

### app/src/main/java/com/securemessenger/app/ui/viewmodel/ChatListViewModel.kt (139 سطر)  [package com.securemessenger.app.ui.viewmodel]
- L21 `data class ContactUiModel ( val id: String, val displayName: String, val isVerified: Boolean, val lastMessage: String, val lastTimestamp: Long, val unreadCount: Int, val avatarBytes: ByteArray? = null, val lastIsMine: Boolean = false, val lastIsRead: Boolean = false, val lastIsSelfDestruct: Boolean = false, val pinnedAt: Long? = null )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/ui/HomeScreenshotTest.kt, app/src/androidTest/java/com/securemessenger/app/ui/LiquidHomeRenderTest.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListItem.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListScreen.kt
- L22 `val id : String,`
- L23 `val displayName : String,`
- L24 `val isVerified : Boolean,`
- L25 `val lastMessage : String,`
- L26 `val lastTimestamp : Long,`
- L27 `val unreadCount : Int,`
- L28 `val avatarBytes : ByteArray?`
- L29 `val lastIsMine : Boolean`
- L30 `val lastIsRead : Boolean`
- L31 `val lastIsSelfDestruct : Boolean`
- L32 `val pinnedAt : Long?`
- L35 `class ChatListViewModel ( private val repository: SecureRepository = SecureMessengerApp.instance.repository ) : ViewModel()`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/ui/HomeScreenshotTest.kt, app/src/androidTest/java/com/securemessenger/app/ui/LiquidHomeRenderTest.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListItem.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListScreen.kt
- L36 `private val repository : SecureRepository`
  - L39 `private val _isLoading`
  - L41 `val isLoading : StateFlow<Boolean>` — True only until the first contacts/messages snapshot arrives — drives the list-skeleton.
  - L51 `val contacts : StateFlow<List<ContactUiModel>>` — Chat list with a last-message preview, time and unread badge — recomputed
  - L91 `fun togglePin (contactId: String)`
  - L98 `private fun preview (message: EncryptedMessage): String`
  - L105 `private fun Contact ( lastMessage: String, lastTimestamp: Long, unreadCount: Int, lastIsMine: Boolean, lastIsRead: Boolean, lastIsSelfDestruct: Boolean ): ContactUiModel`

### app/src/main/java/com/securemessenger/app/ui/viewmodel/ConnectionRequestsViewModel.kt (97 سطر)  [package com.securemessenger.app.ui.viewmodel]
- L18 `data class IncomingRequestUiModel ( val senderIdentityPublicKeyHex: String, val senderUserId: String, val senderUsername: String, val receivedAt: Long )`
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/navigation/AppNavigation.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConnectionRequestsScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/NewChatScreen.kt
- L19 `val senderIdentityPublicKeyHex : String,`
- L20 `val senderUserId : String,`
- L21 `val senderUsername : String,`
- L22 `val receivedAt : Long`
- L31 `class ConnectionRequestsViewModel ( private val repository: SecureRepository = SecureMessengerApp.instance.repository ) : ViewModel()` — Pending self-introductions found via username search, waiting on an
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/navigation/AppNavigation.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ChatListScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConnectionRequestsScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/NewChatScreen.kt
- L32 `private val repository : SecureRepository`
  - L58 `val incomingRequests : StateFlow<List<IncomingRequestUiModel>>` — Built lazily, and that is a crash fix rather than a style preference.
  - L69 `private val _actionInProgress`
  - L70 `val actionInProgress : StateFlow<String?>`
  - L72 `fun accept (senderIdentityPublicKeyHex: String, onResult: (Boolean) -> Unit)`
  - L85 `fun reject (senderIdentityPublicKeyHex: String)`

### app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt (587 سطر) ⚠  [package com.securemessenger.app.ui.viewmodel]
- L27 `data class MessageUiModel ( val id: Long?, val clientId: String?, val text: String, val direction: Int, val timestamp: Long, val isRead: Boolean, val isExpired: Boolean, val media: MediaCodec.LocalMedia? = null, // Social interaction state val reactionMine: String? = null, val reactionTheirs: String? = null, val replyToClientId: String? = null, val replySnippet: String? = null, val isDeleted: Boolean = false, val edited: Boolean = false, // True while the outgoing envelope still sits in the durable outbox // (queued/in-flight, no relay ack yet) — drives the "sending…" clock tick. val isPending: Boolean = false )`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/ui/ScreenTourTest.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/AlbumBubble.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationRows.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationSheets.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationViewModelFactory.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/MessageBubble.kt
- L28 `val id : Long?,`
- L29 `val clientId : String?,`
- L30 `val text : String,`
- L31 `val direction : Int,`
- L32 `val timestamp : Long,`
- L33 `val isRead : Boolean,`
- L34 `val isExpired : Boolean,`
- L35 `val media : MediaCodec.LocalMedia?`
- L37 `val reactionMine : String?`
- L38 `val reactionTheirs : String?`
- L39 `val replyToClientId : String?`
- L40 `val replySnippet : String?`
- L41 `val isDeleted : Boolean`
- L42 `val edited : Boolean`
- L45 `val isPending : Boolean`
- L49 `@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) class ConversationViewModel ( private val contactId: String, private val repository: SecureRepository = SecureMessengerApp.instance.repository ) : ViewModel()`
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/ui/ScreenTourTest.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/AlbumBubble.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationRows.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationSheets.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationViewModelFactory.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/MessageBubble.kt
- L50 `private val contactId : String,`
- L51 `private val repository : SecureRepository`
  - L54 `private val _messages`
  - L55 `val messages : StateFlow<List<MessageUiModel>>`
  - L57 `private val _isSending`
  - L58 `val isSending : StateFlow<Boolean>`
  - L60 `private val _error`
  - L61 `val error : StateFlow<String?>`
  - L68 `private val _establishingSession`
  - L69 `val establishingSession : StateFlow<Boolean>`
  - L71 `private val _contactName`
  - L72 `val contactName : StateFlow<String>`
  - L74 `private val _contactAvatar`
  - L75 `val contactAvatar : StateFlow<ByteArray?>`
  - L77 `private val _contactVerified`
  - L78 `val contactVerified : StateFlow<Boolean>`
  - L80 `private val _isContactTyping`
  - L81 `val isContactTyping : StateFlow<Boolean>`
  - L82 `private var typingResetJob : Job?`
  - L83 `private var lastTypingSentAt`
  - L94 `private val windowSize`
  - L96 `private val _totalMessages`
  - L99 `val hasMoreHistory : StateFlow<Boolean>` — True while there is older history above what is currently loaded.
  - L104 `fun loadOlderMessages ()` — Widen the window — called when the user scrolls to the top of what is loaded.
  - L119 `fun loadEntireHistoryForSearch ()` — Load the whole thread, because in-conversation search reads the loaded
  - L125 `private var lastFailedAction`
  - L136 `private enum class FailureSeverity`
  - L158 `private var lastFailureSeverity : FailureSeverity?`
  - L166 `private fun reportFailure (message: String, severity: FailureSeverity, action: (() -> Unit)? = null)`
  - L174 `fun clearError ()` — Clears the currently-shown error (the Snackbar dismissed/timed out on its own).
  - L179 `fun retryLastAction ()` — Re-run whatever just failed, if anything did.
  - L188 `fun notifyTyping ()` — Call on every text change — throttled so it's a light pulse, not a signal per keystroke.
  - L294 `private suspend fun needsHandshake (): Boolean`
  - L296 `fun sendMessage (text: String)`
  - L353 `private suspend fun loadContactName (): String`
  - L362 `private fun EncryptedMessage (pendingIds: Set<String> = emptySet()): MessageUiModel`
  - L384 `private fun parseReactionsJson (json: String?): Map<String, String>`
  - L395 `suspend fun loadMediaBytes (media: MediaCodec.LocalMedia): ByteArray?`
  - L398 `fun reportError (message: String)` — Surface a failure that happened outside the send/receive pipeline (e.g. mic access).
  - L406 `fun sendMedia ( bytes: ByteArray, mimeType: String, fileName: String, mediaType: Int, waveform: List<Float> = emptyList(), caption: String? = null )` — Send a picked/recorded file as an encrypted media message. Mirrors
  - L480 `private fun deliver (wirePayload: ByteArray, messageId: String?, ttlSeconds: Int?)`
  - L507 `fun sendReply (text: String, replyToClientId: String)` — Send a reply that quotes an earlier message.
  - L538 `private fun controlOpTrackingId ()`
  - L541 `fun react (targetClientId: String, emoji: String)` — Toggle an emoji reaction on a message and mirror it to the peer.
  - L553 `fun editMessage (targetClientId: String, newText: String)` — Edit the text of a message I sent.
  - L566 `fun deleteForEveryone (targetClientId: String)` — Delete a message for everyone (tombstone on both sides).
  - L578 `fun deleteForMe (messageId: Long)` — Delete a message from my device only.

### app/src/main/java/com/securemessenger/app/update/UpdateChecker.kt (167 سطر)  [package com.securemessenger.app.update]
- L33 `object UpdateChecker` — Checks BuildConfig.UPDATE_REPO's GitHub Releases for a newer build than the
  يُستخدم في: app/src/main/java/com/securemessenger/app/ui/screens/settings/SettingsScreen.kt
  - L35 `private const val TAG_PREFIX`
  - L36 `private const val USER_AGENT`
  - L38 `private val client`
  - L43 `sealed class CheckResult`
  - L54 `suspend fun check (): CheckResult`
  - L99 `internal fun isNewer (remote: String, current: String): Boolean`
  - L110 `sealed class DownloadResult`
  - L116 `suspend fun download (context: Context, url: String, onProgress: (Float) -> Unit): DownloadResult`
  - L149 `fun canInstallPackages (context: Context): Boolean` — Android 8+: whether this app currently holds the per-app "install unknown apps" toggle.
  - L153 `fun installPermissionSettingsIntent (context: Context): Intent` — The one Settings screen where the user grants that toggle for this app specifically.
  - L157 `fun install (context: Context, apkFile: File)` — Hands [apkFile] to the system package installer UI. Caller must have checked [canInstallPackages] first.

### app/src/test/java/com/securemessenger/app/data/repository/OrderingTimestampTest.kt (74 سطر)  [package com.securemessenger.app.data.repository]
- L22 `class OrderingTimestampTest` — Where an arriving message lands in the thread.
  - L24 `private val now`
  - L25 `private val hour`
  - L27 `private fun at (sentAt: Long?)`
  - L30 `@Test fun anEnvelopeWithoutATimestampUsesArrival ()`
  - L37 `@Test fun aRealDelayIsHonoured ()`
  - L43 `@Test fun aClaimOlderThanTheRelayCouldHoldIsRejected ()`
  - L53 `@Test fun aClaimFromTheFutureIsRejected ()`
  - L60 `@Test fun smallForwardSkewIsClampedToArrivalRatherThanRejected ()`
  - L68 `@Test fun theBoundariesThemselvesAreAccepted ()`

### app/src/test/java/com/securemessenger/app/media/MediaSandboxGeometryTest.kt (133 سطر)  [package com.securemessenger.app.media]
- L28 `class MediaSandboxGeometryTest` — The guard on the media sandbox's **return** channel — the one direction in
  - L31 `@Test fun acceptsAnHonestDecode ()`
  - L39 `@Test fun acceptsAPanoramaThatOvershootsOnOneAxis ()`
  - L49 `@Test fun acceptsAVeryWidePanorama ()`
  - L61 `@Test fun refusesAnAbsurdClaimedSize ()`
  - L85 `@Test fun areaArithmeticDoesNotOverflowIntoAcceptance ()` — Pins the arithmetic to Long, which the test above does NOT do.
  - L112 `@Test fun refusesABigClaimBackedByASmallBuffer ()`
  - L120 `@Test fun refusesAnythingOverTheAbsoluteCeilingHoweverLargeTheRequestWas ()`
  - L127 `@Test fun refusesNonPositiveGeometry ()`

### app/src/test/java/com/securemessenger/app/security/AccessCodeRuleTest.kt (71 سطر)  [package com.securemessenger.app.security]
- L27 `class AccessCodeRuleTest` — The rule that decides whether a chosen unlock code can ever be entered.
  - L30 `@Test fun acceptsAnOrdinaryCode ()`
  - L37 `@Test fun rejectsALeadingZero ()`
  - L45 `@Test fun acceptsAZeroThatIsNotFirst ()`
  - L53 `@Test fun rejectsLengthsOutsideTheOneAgreedRange ()`
  - L63 `@Test fun rejectsAnythingThatIsNotDigits ()`

### app/src/test/java/com/securemessenger/app/security/PendingPairSecretBudgetTest.kt (97 سطر)  [package com.securemessenger.app.security]
- L27 `class PendingPairSecretBudgetTest` — Which outstanding pair secrets survive, and which get evicted.
  - L29 `private fun displayed (hex: String, at: Long = 0L)`
  - L30 `private fun exported (hex: String, at: Long = 0L)`
  - L32 `private fun List`
  - L35 `@Test fun newestOnScreenCodeGoesFirstAndIsNotExported ()`
  - L43 `@Test fun onScreenCodesStayCappedAtFive ()`
  - L52 `@Test fun aSharedCodeSurvivesABurstOfOnScreenCodes ()`
  - L68 `@Test fun exportingRestampsTheClock ()`
  - L77 `@Test fun exportingIsIdempotentAndIgnoresUnknownCodes ()`
  - L84 `@Test fun sharedCodesHaveTheirOwnCeilingToo ()`


## core/

### core/src/main/kotlin/com/securemessenger/core/B64.kt (57 سطر)  [package com.securemessenger.core]
- L17 `object B64` — Base64, in exactly the encoding this app's wire format has always used.
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/network/IntroductionRoundtripTest.kt, app/src/main/java/com/securemessenger/app/network/DirectoryClient.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/UsernameSearchScreen.kt, core/src/main/kotlin/com/securemessenger/core/crypto/AckToken.kt, core/src/main/kotlin/com/securemessenger/core/crypto/MediaCodec.kt, core/src/main/kotlin/com/securemessenger/core/net/DirectoryProtocol.kt, core/src/main/kotlin/com/securemessenger/core/net/Envelopes.kt, core/src/main/kotlin/com/securemessenger/core/net/RelayBlob.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopStore.kt
  - L19 `private val encoder`
  - L20 `private val decoder`
  - L22 `fun encode (bytes: ByteArray): String`
  - L24 `fun decode (text: String): ByteArray`
  - L37 `fun decodeOrNull (text: String?): ByteArray?` — Null-safe decode for optional wire fields — returns null instead of throwing on junk.
  - L46 `fun toHex (bytes: ByteArray): String`
  - L48 `fun fromHex (hex: String): ByteArray?`

### core/src/main/kotlin/com/securemessenger/core/Platform.kt (57 سطر)  [package com.securemessenger.core]
- L14 `object Platform` — The two things this module can't provide for itself, supplied once at startup
  يُستخدم في: core/src/main/kotlin/com/securemessenger/core/crypto/LibsodiumWrapper.kt, core/src/main/kotlin/com/securemessenger/core/net/LocalRelayServer.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopRelayClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopStore.kt, desktop/src/main/kotlin/com/securemessenger/desktop/Main.kt, desktop/src/test/kotlin/com/securemessenger/core/net/DirectoryProtocolTest.kt, desktop/src/test/kotlin/com/securemessenger/desktop/LoopbackMessagingTest.kt, desktop/src/test/kotlin/com/securemessenger/desktop/RelayMessagingTest.kt, desktop/src/test/kotlin/com/securemessenger/desktop/ScopeReuseTest.kt
  - L17 `@Volatile
    private var sodiumInstance : LazySodium?`
  - L20 `@Volatile
    private var logger : CoreLogger`
  - L28 `fun installSodium (instance: LazySodium)` — Install the platform's libsodium binding: `LazySodiumAndroid(SodiumAndroid())`
  - L32 `fun installLogger (instance: CoreLogger)`
  - L36 `val sodium : LazySodium`
  - L41 `val log`
- L45 `interface CoreLogger` — Where :core's diagnostics go — logcat on Android, stderr (or a file) on the desktop.
  يُستخدم في: core/src/main/kotlin/com/securemessenger/core/crypto/LibsodiumWrapper.kt, core/src/main/kotlin/com/securemessenger/core/net/LocalRelayServer.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopRelayClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopStore.kt, desktop/src/main/kotlin/com/securemessenger/desktop/Main.kt, desktop/src/test/kotlin/com/securemessenger/core/net/DirectoryProtocolTest.kt, desktop/src/test/kotlin/com/securemessenger/desktop/LoopbackMessagingTest.kt, desktop/src/test/kotlin/com/securemessenger/desktop/RelayMessagingTest.kt, desktop/src/test/kotlin/com/securemessenger/desktop/ScopeReuseTest.kt
  - L46 `fun debug (tag: String, message: String)`
  - L47 `fun warn (tag: String, message: String, error: Throwable? = null)`
  - L48 `fun error (tag: String, message: String, error: Throwable? = null)`
  - L51 `object Silent` — Used until the host installs a real one, so a missing install never crashes.

### core/src/main/kotlin/com/securemessenger/core/crypto/AckToken.kt (36 سطر)  [package com.securemessenger.core.crypto]
- L20 `object AckToken` — Per-envelope delivery token used to authenticate transport `ack` frames.
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/network/AckAuthenticationTest.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt
  - L22 `private const val INFO`
  - L23 `private const val TOKEN_BYTES`
  - L26 `fun compute (identitySecret: ByteArray, envelopeId: String): String` — Deterministic token for [envelopeId], bound to [identitySecret].

### core/src/main/kotlin/com/securemessenger/core/crypto/ChatPayloads.kt (90 سطر)  [package com.securemessenger.core.crypto]
- L15 `object ChatPayloads` — Wire payloads for social message-interaction features. These ride the exact
  يُستخدم في: app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt
  - L17 `const val OP_REACT`
  - L18 `const val OP_EDIT`
  - L19 `const val OP_DELETE`
  - L22 `data class Control ( val op: String, val targetClientId: String, val emoji: String? = null, // for react ("" = remove reaction) val text: String? = null // for edit (the new text) )`
  - L23 `val op : String,`
  - L24 `val targetClientId : String,`
  - L25 `val emoji : String?`
  - L26 `val text : String?`
  - L30 `data class TextPayload ( val text: String, val replyToClientId: String?, val replySnippet: String? )`
  - L31 `val text : String,`
  - L32 `val replyToClientId : String?,`
  - L33 `val replySnippet : String?`
  - L38 `fun buildReaction (targetClientId: String, emoji: String): ByteArray`
  - L43 `fun buildEdit (targetClientId: String, newText: String): ByteArray`
  - L48 `fun buildDelete (targetClientId: String): ByteArray`
  - L54 `fun buildReplyText (text: String, replyToClientId: String, snippet: String): ByteArray` — A reply carries the reply target + a short snippet so the peer can render the quote.
  - L62 `fun tryParseControl (plaintext: ByteArray): Control?`
  - L74 `fun tryParseText (plaintext: ByteArray): TextPayload?`
  - L83 `private fun parseObj (plaintext: ByteArray): JSONObject?`

### core/src/main/kotlin/com/securemessenger/core/crypto/LibsodiumWrapper.kt (252 سطر)  [package com.securemessenger.core.crypto]
- L15 `object LibsodiumWrapper` — LibsodiumWrapper - high-level encryption API over libsodium.
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/crypto/MetadataPrivacyTest.kt, app/src/androidTest/java/com/securemessenger/app/crypto/SignalProtocolTest.kt, app/src/androidTest/java/com/securemessenger/app/network/IntroductionRoundtripTest.kt, app/src/androidTest/java/com/securemessenger/app/security/testing/CryptoSecurityTests.kt, app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt, app/src/main/java/com/securemessenger/app/network/RelayClient.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/setup/SetupScreen.kt, core/src/main/kotlin/com/securemessenger/core/crypto/SignalProtocol.kt, core/src/main/kotlin/com/securemessenger/core/net/DirectoryProtocol.kt, core/src/main/kotlin/com/securemessenger/core/net/Envelopes.kt, core/src/main/kotlin/com/securemessenger/core/net/RelayBlob.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopStore.kt, desktop/src/test/kotlin/com/securemessenger/core/net/DirectoryProtocolTest.kt
  - L17 `private val lazySodium`
  - L19 `fun generateSecretKey (keyLength: Int = 32): ByteArray`
  - L23 `fun generateKeyPair (): Pair<ByteArray, ByteArray>`
  - L28 `fun generateSigningKeyPair (): Pair<ByteArray, ByteArray>`
  - L38 `fun encryptSymmetric (plaintext: ByteArray, key: ByteArray): ByteArray`
  - L60 `fun decryptSymmetric (ciphertext: ByteArray, key: ByteArray): ByteArray`
  - L81 `fun encryptAsymmetric (plaintext: ByteArray, recipientPublicKey: ByteArray, senderSecretKey: ByteArray): ByteArray`
  - L98 `fun decryptAsymmetric (ciphertext: ByteArray, senderPublicKey: ByteArray, recipientSecretKey: ByteArray): ByteArray`
  - L125 `fun sealTo (message: ByteArray, recipientPublicKey: ByteArray): ByteArray` — Anonymous "sealed box": encrypt to a recipient's public key such that only
  - L136 `fun sealOpen (sealed: ByteArray, recipientPublicKey: ByteArray, recipientSecretKey: ByteArray): ByteArray` — Open a sealed box with the recipient's own keypair.
  - L146 `fun deriveSharedSecret (mySecretKey: ByteArray, theirPublicKey: ByteArray): ByteArray`
  - L163 `fun blake2b (data: ByteArray, key: ByteArray? = null, length: Int = 64): ByteArray`
  - L171 `fun hkdf (inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 64): ByteArray`
  - L191 `fun sign (message: ByteArray, secretKey: ByteArray): ByteArray`
  - L203 `fun signDetached (message: ByteArray, secretKey: ByteArray): ByteArray` — Detached Ed25519 signature — just the 64 signature bytes, message sent separately.
  - L213 `fun verifyDetached (message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean` — Verify a detached Ed25519 signature. Never throws — returns false on any failure.
  - L222 `fun verify (signedMessage: ByteArray, publicKey: ByteArray): ByteArray`
  - L239 `fun secureWipe (array: ByteArray)`
  - L243 `fun secureWipe (vararg arrays: ByteArray)`
  - L247 `fun constantTimeCompare (a: ByteArray, b: ByteArray): Boolean`

### core/src/main/kotlin/com/securemessenger/core/crypto/MailboxToken.kt (159 سطر)  [package com.securemessenger.core.crypto]
- L52 `object MailboxToken` — MailboxToken — how a pair of devices address each other through the blind
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/network/IntroductionRoundtripTest.kt, app/src/androidTest/java/com/securemessenger/app/network/RelayRoundtripTest.kt, app/src/androidTest/java/com/securemessenger/app/ui/screens/chat/QrImageTest.kt, app/src/main/java/com/securemessenger/app/network/RelayClient.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/NewChatScreen.kt, core/src/main/kotlin/com/securemessenger/core/net/RelayBlob.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopRelayClient.kt, desktop/src/test/kotlin/com/securemessenger/core/net/DirectoryProtocolTest.kt
  - L55 `const val ROTATION_MS`
  - L58 `const val PAIR_SECRET_BYTES`
  - L66 `const val BUCKET_TOLERANCE`
  - L68 `fun currentBucket (nowMillis: Long = System.currentTimeMillis()): Long`
  - L71 `fun newPairSecret (): ByteArray` — Freshly minted whenever a QR code is displayed. Never transmitted over any network.
  - L77 `fun pairSecretToHex (secret: ByteArray): String`
  - L79 `fun pairSecretFromHex (hex: String): ByteArray?`
  - L89 `fun mailboxId (pairSecret: ByteArray, bucket: Long): String` — The mailbox this secret's one direction of traffic flows through during [bucket].
  - L101 `fun inboundIds (pairSecret: ByteArray, now: Long = System.currentTimeMillis()): List<String>` — Every mailbox id worth asking the relay about for a secret we listen on —
  - L111 `fun outboundId (pairSecret: ByteArray, now: Long = System.currentTimeMillis()): String` — The single id to deposit into right now. Only the current window is used:
  - L121 `fun blobKey (pairSecret: ByteArray): ByteArray` — The key the outer envelope is encrypted under before it is handed to the
  - L153 `fun introMailboxId (identityPublicKey: ByteArray): String` — The one-shot, non-rotating mailbox a stranger deposits an introduction

### core/src/main/kotlin/com/securemessenger/core/crypto/MediaCodec.kt (176 سطر)  [package com.securemessenger.core.crypto]
- L15 `object MediaCodec` — Media messages get an extra symmetric-encryption layer on top of the usual
  يُستخدم في: app/src/main/java/com/securemessenger/app/SecureMessengerApp.kt, app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/AlbumBubble.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/AttachmentPicking.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ComposeStrips.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailComponents.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ContactDetailScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationRows.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/ConversationScreen.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/MediaContent.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/MessageBubble.kt, app/src/main/java/com/securemessenger/app/ui/screens/chat/MessageInput.kt, app/src/main/java/com/securemessenger/app/ui/viewmodel/ConversationViewModel.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt
  - L16 `const val TYPE_TEXT`
  - L17 `const val TYPE_IMAGE`
  - L18 `const val TYPE_FILE`
  - L19 `const val TYPE_VIDEO`
  - L20 `const val TYPE_AUDIO`
  - L24 `private val SAFE_FILENAME_CHARS`
  - L26 `data class WireMedia ( val mediaType: Int, val mimeType: String, val fileName: String, val key: ByteArray, val ciphertext: ByteArray, // Voice-message amplitude envelope (0f..1f per bucket), empty for // anything else. Small enough to just ride along as plain JSON. val waveform: List<Float> = emptyList(), val caption: String? = null )`
  - L27 `val mediaType : Int,`
  - L28 `val mimeType : String,`
  - L29 `val fileName : String,`
  - L30 `val key : ByteArray,`
  - L31 `val ciphertext : ByteArray,`
  - L34 `val waveform : List<Float>`
  - L35 `val caption : String?`
  - L38 `data class LocalMedia ( val mediaType: Int, val mimeType: String, val fileName: String, val size: Int, val key: ByteArray, val ref: String, val waveform: List<Float> = emptyList(), val caption: String? = null )`
  - L39 `val mediaType : Int,`
  - L40 `val mimeType : String,`
  - L41 `val fileName : String,`
  - L42 `val size : Int,`
  - L43 `val key : ByteArray,`
  - L44 `val ref : String,`
  - L45 `val waveform : List<Float>`
  - L46 `val caption : String?`
  - L49 `private fun waveformToJson (waveform: List<Float>): JSONArray?`
  - L58 `private const val MAX_WAVEFORM_BUCKETS`
  - L60 `private fun waveformFromJson (array: JSONArray?): List<Float>`
  - L67 `fun buildWirePayload ( mediaType: Int, mimeType: String, fileName: String, key: ByteArray, ciphertext: ByteArray, waveform: List<Float> = emptyList(), caption: String? = null ): ByteArray` — What actually goes over the wire (as the ratchet's plaintext) for a media message.
  - L93 `private fun sanitizeFileName (raw: String): String`
  - L110 `private fun isKnownMediaType (type: Int): Boolean`
  - L114 `fun tryParseWirePayload (plaintext: ByteArray): WireMedia?` — Best-effort sniff: is this plaintext a media payload, or plain text?
  - L141 `fun buildLocalDescriptor ( mediaType: Int, mimeType: String, fileName: String, size: Int, key: ByteArray, ref: String, waveform: List<Float> = emptyList(), caption: String? = null ): ByteArray` — The small descriptor stored locally (encrypted) — points at the on-disk ciphertext instead of embedding it.
  - L158 `fun parseLocalDescriptor (bytes: ByteArray): LocalMedia?`

### core/src/main/kotlin/com/securemessenger/core/crypto/MessagePadding.kt (55 سطر)  [package com.securemessenger.core.crypto]
- L10 `object MessagePadding` — MessagePadding - pads plaintext to fixed-size buckets before encryption so the
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/crypto/MetadataPrivacyTest.kt, app/src/main/java/com/securemessenger/app/network/RelayClient.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, core/src/main/kotlin/com/securemessenger/core/net/RelayBlob.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt
  - L12 `private const val BUCKET`
  - L13 `private const val HEADER`
  - L15 `fun pad (plaintext: ByteArray): ByteArray`
  - L27 `fun unpad (padded: ByteArray): ByteArray`
  - L50 `private fun paddedSize (size: Int): Int`

### core/src/main/kotlin/com/securemessenger/core/crypto/PqKem.kt (66 سطر)  [package com.securemessenger.core.crypto]
- L20 `object PqKem` — PqKem - post-quantum key encapsulation using ML-KEM-768 (NIST FIPS 203).
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/crypto/PqKemTest.kt, app/src/androidTest/java/com/securemessenger/app/crypto/RatchetRoundtripTest.kt, app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt
  - L22 `private val params : MLKEMParameters`
  - L34 `private const val PUBLIC_KEY_BYTES`
  - L35 `private const val SECRET_KEY_BYTES`
  - L36 `private const val CIPHERTEXT_BYTES`
  - L38 `data class KeyPair (val publicKey: ByteArray, val secretKey: ByteArray)`
  - L39 `data class Encapsulation (val ciphertext: ByteArray, val sharedSecret: ByteArray)`
  - L41 `fun generateKeyPair (): KeyPair`
  - L51 `fun encapsulate (publicKey: ByteArray): Encapsulation` — Initiator side: produce a fresh shared secret + its ciphertext for [publicKey].
  - L59 `fun decapsulate (secretKey: ByteArray, ciphertext: ByteArray): ByteArray` — Responder side: recover the same shared secret from [ciphertext].

### core/src/main/kotlin/com/securemessenger/core/crypto/SignalProtocol.kt (498 سطر) ⚠  [package com.securemessenger.core.crypto]
- L17 `class SignalProtocol ( private val identityKeyPair: IdentityKeyPair, private val signedPreKeyPair: PreKeyPair, private val oneTimePreKeys: List<PreKeyPair> )` — SignalProtocol - end-to-end encryption via X3DH + the Double Ratchet.
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/crypto/RatchetRoundtripTest.kt, app/src/androidTest/java/com/securemessenger/app/crypto/SignalProtocolTest.kt, app/src/androidTest/java/com/securemessenger/app/data/ConversationStorageTest.kt, app/src/androidTest/java/com/securemessenger/app/security/testing/CryptoSecurityTests.kt, app/src/main/java/com/securemessenger/app/data/repository/SecureRepository.kt, app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopStore.kt
- L18 `private val identityKeyPair : IdentityKeyPair,`
- L19 `private val signedPreKeyPair : PreKeyPair,`
- L20 `private val oneTimePreKeys : List<PreKeyPair>`
  - L24 `private var dhRatchetPublicKey : ByteArray`
  - L25 `private var dhRatchetSecretKey : ByteArray`
  - L26 `private var chainKeySend : ByteArray`
  - L27 `private var chainKeyReceive : ByteArray?`
  - L28 `private var rootKey : ByteArray`
  - L31 `private var theirRatchetPublicKey : ByteArray?`
  - L34 `private var sendChainCounter : Int`
  - L35 `private var receiveChainCounter : Int`
  - L38 `private var previousSendChainLength : Int`
  - L41 `private var initiatorEphemeralPublicKey : ByteArray?`
  - L46 `private val skippedMessageKeys`
  - L47 `private val maxSkip`
  - L60 `private val maxTotalSkipped`
  - L81 `fun initializeAsInitiator ( recipientIdentityKey: ByteArray, recipientSignedPreKey: ByteArray, recipientOneTimePreKey: ByteArray? = null, pqSharedSecret: ByteArray? = null )` — Initialize the protocol as the initiator (sender).
  - L153 `fun initializeAsResponder ( initiatorIdentityKey: ByteArray, initiatorEphemeralKey: ByteArray, ourSignedPreKeySecret: ByteArray, ourIdentitySecretKey: ByteArray, ourOneTimePreKeySecret: ByteArray? = null, pqSharedSecret: ByteArray? = null )` — Initialize the protocol as the responder (receiver).
  - L210 `fun getInitiatorEphemeralPublicKey (): ByteArray?` — Returns the initiator ephemeral public key to include in the first message.
  - L218 `fun encryptMessage (plaintext: ByteArray): EncryptedMessage` — Encrypt a message using the current chain key.
  - L252 `fun decryptMessage (encryptedMessage: EncryptedMessage): ByteArray` — Decrypt a message using the appropriate chain.
  - L272 `private fun decryptMessageInternal (encryptedMessage: EncryptedMessage): ByteArray`
  - L317 `private fun skipReceiveChain (untilCounter: Int)`
  - L341 `private fun dhRatchet (theirNewRatchetKey: ByteArray)`
  - L368 `private fun kdfRootKey (root: ByteArray, dhOutput: ByteArray): Pair<ByteArray, ByteArray>`
  - L374 `private fun skipKey (dhPublicKey: ByteArray, counter: Int): String`
  - L390 `private fun deriveMessageKey (chainKey: ByteArray): ByteArray`
  - L398 `private fun advanceChainKey (chainKey: ByteArray): ByteArray`
  - L406 `fun exportState (): SessionState` — Get the current state for serialization.
  - L425 `fun importState (state: SessionState)` — Restore state from serialized data.
  - L442 `data class IdentityKeyPair ( val publicKey: ByteArray, val secretKey: ByteArray )`
  - L443 `val publicKey : ByteArray,`
  - L444 `val secretKey : ByteArray`
  - L459 `data class PreKeyPair ( val id: Int, val publicKey: ByteArray, val secretKey: ByteArray )`
  - L460 `val id : Int,`
  - L461 `val publicKey : ByteArray,`
  - L462 `val secretKey : ByteArray`
  - L477 `data class EncryptedMessage ( val ciphertext: ByteArray, val chainCounter: Int, val dhPublicKey: ByteArray, val previousChainLength: Int = 0 )`
  - L478 `val ciphertext : ByteArray,`
  - L479 `val chainCounter : Int,`
  - L480 `val dhPublicKey : ByteArray,`
  - L481 `val previousChainLength : Int`
  - L484 `data class SessionState ( var rootKey: ByteArray, var chainKeySend: ByteArray, var chainKeyReceive: ByteArray?, var dhRatchetPublicKey: ByteArray, var dhRatchetSecretKey: ByteArray, var sendChainCounter: Int, var receiveChainCounter: Int, var theirRatchetPublicKey: ByteArray? = null, var previousSendChainLength: Int = 0, var initiatorEphemeralPublicKey: ByteArray? = null, var skippedMessageKeys: Map<String, ByteArray> = emptyMap() )`
  - L485 `var rootKey : ByteArray,`
  - L486 `var chainKeySend : ByteArray,`
  - L487 `var chainKeyReceive : ByteArray?,`
  - L488 `var dhRatchetPublicKey : ByteArray,`
  - L489 `var dhRatchetSecretKey : ByteArray,`
  - L490 `var sendChainCounter : Int,`
  - L491 `var receiveChainCounter : Int,`
  - L492 `var theirRatchetPublicKey : ByteArray?`
  - L493 `var previousSendChainLength : Int`
  - L494 `var initiatorEphemeralPublicKey : ByteArray?`
  - L495 `var skippedMessageKeys : Map<String, ByteArray>`

### core/src/main/kotlin/com/securemessenger/core/net/DirectoryProtocol.kt (199 سطر)  [package com.securemessenger.core.net]
- L20 `object DirectoryProtocol` — Everything specific to the optional username-directory path: claiming a
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/network/IntroductionRoundtripTest.kt, app/src/main/java/com/securemessenger/app/network/DirectoryClient.kt
  - L36 `fun claimSigningPayload ( username: String, identityPublicKey: ByteArray, signingPublicKey: ByteArray, timestampMillis: Long ): ByteArray` — The exact bytes a username claim is signed over. The directory
  - L63 `data class SelfIntroduction ( val senderUserId: String, val senderUsername: String, val senderIdentityPublicKey: ByteArray, val senderSigningPublicKey: ByteArray, val pairSecret: ByteArray, val directAddress: String?, val timestampMillis: Long, val signature: ByteArray )`
  - L64 `val senderUserId : String,`
  - L65 `val senderUsername : String,`
  - L66 `val senderIdentityPublicKey : ByteArray,`
  - L67 `val senderSigningPublicKey : ByteArray,`
  - L68 `val pairSecret : ByteArray,`
  - L69 `val directAddress : String?,`
  - L70 `val timestampMillis : Long,`
  - L71 `val signature : ByteArray`
  - L81 `fun introSigningPayload ( senderIdentityPublicKey: ByteArray, addresseeIdentityPublicKey: ByteArray, pairSecret: ByteArray, timestampMillis: Long ): ByteArray` — The exact bytes a [SelfIntroduction] is signed over. Binding
  - L101 `fun buildSelfIntroduction ( senderUserId: String, senderUsername: String, senderIdentityPublicKey: ByteArray, senderSigningPublicKey: ByteArray, addresseeIdentityPublicKey: ByteArray, pairSecret: ByteArray, directAddress: String?, timestampMillis: Long, sign: (ByteArray) -> ByteArray ): JSONObject` — Builds and signs a [SelfIntroduction] payload, ready to pass as `inner`
  - L128 `fun parseSelfIntroduction (json: JSONObject): SelfIntroduction?` — Structural parse only — never trust a [SelfIntroduction] this returns until [verifySelfIntroduction] passes.
  - L160 `fun verifySelfIntroduction ( intro: SelfIntroduction, ourIdentityPublicKey: ByteArray, trustedSigningPublicKey: ByteArray, nowMillis: Long = System.currentTimeMillis(), toleranceMillis: Long = 10 * 60 * 1000 ): Boolean` — Verify a parsed [SelfIntroduction] was actually signed by the holder
  - L189 `fun introFetchSigningPayload (nonce: ByteArray, timestampMillis: Long): ByteArray` — The exact bytes an `/introductions/fetch` proof is signed over —
  - L193 `private fun longToBigEndianBytes (value: Long): ByteArray`

### core/src/main/kotlin/com/securemessenger/core/net/Envelopes.kt (236 سطر)  [package com.securemessenger.core.net]
- L42 `object Envelopes` — The on-the-wire frame format, in one place.
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/network/IntroductionRoundtripTest.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt
  - L44 `const val TYPE_MESSAGE`
  - L45 `const val TYPE_RECEIPT`
  - L46 `const val TYPE_TYPING`
  - L47 `const val TYPE_ACK`
  - L48 `const val TYPE_CHALLENGE`
  - L49 `const val TYPE_BUNDLE_ANNOUNCE`
  - L59 `const val TYPE_INTRO_REQUEST`
  - L60 `const val TYPE_INTRO_ACCEPT`
  - L63 `const val TYPE_NOISE`
  - L66 `const val NOISE_ENVELOPE`
  - L75 `fun seal ( type: String, inner: JSONObject, recipientPublicKey: ByteArray, envelopeId: String? = null ): JSONObject` — Wrap [inner] as an anonymous sealed box addressed to [recipientPublicKey].
  - L94 `fun open (outer: JSONObject, ourPublicKey: ByteArray, ourSecretKey: ByteArray): JSONObject` — Recover the inner object from an outer frame. A frame with no `sealed`
  - L112 `fun bundleSigningPayload ( nonce: ByteArray, signedPreKey: ByteArray, identityKey: ByteArray, mlkemPublicKey: ByteArray, oneTimePreKey: ByteArray ): ByteArray` — The exact byte string an outgoing prekey bundle is signed over, and that
  - L129 `fun buildBundleAnnounce ( userId: String, nonce: ByteArray, identityKey: ByteArray, signedPreKey: ByteArray, signedPreKeyId: Int, signingPublicKey: ByteArray?, mlkemPublicKey: ByteArray, oneTimePreKeyId: Int?, oneTimePreKey: ByteArray?, sign: (ByteArray) -> ByteArray ): JSONObject` — Build the `bundle_announce` payload for our own keys, signed over
  - L164 `fun buildChallenge (userId: String, nonce: ByteArray): JSONObject` — The `challenge` that must precede any bundle handout — proves the exchange is live.
  - L171 `fun ack (envelopeId: String, ackToken: String?): String` — The `ack` a recipient sends back so the sender can clear its outbox row.
  - L185 `data class PrekeyBundle ( val identityKey: ByteArray, val signedPreKey: ByteArray, val signingPublicKey: ByteArray? = null, val oneTimePreKey: ByteArray?, val oneTimePreKeyId: Int? = null, val mlkemPublicKey: ByteArray? = null )`
  - L186 `val identityKey : ByteArray,`
  - L187 `val signedPreKey : ByteArray,`
  - L188 `val signingPublicKey : ByteArray?`
  - L189 `val oneTimePreKey : ByteArray?,`
  - L190 `val oneTimePreKeyId : Int?`
  - L191 `val mlkemPublicKey : ByteArray?`
  - L201 `fun parseBundle (json: JSONObject, expectedNonce: ByteArray?): PrekeyBundle?` — Read and verify a `bundle_announce` payload.

### core/src/main/kotlin/com/securemessenger/core/net/LanAddress.kt (191 سطر)  [package com.securemessenger.core.net]
- L19 `object LanAddress` — This device's own address on the local network.
  يُستخدم في: desktop/src/main/kotlin/com/securemessenger/desktop/DesktopDiscovery.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/LocalBoundSocketFactory.kt
  - L30 `fun best (): String?` — The best guess at "the address a peer on my network should dial", or null
  - L55 `fun candidates (): List<String>` — Every plausible local IPv4 address, most-likely-correct first.
  - L77 `private fun score (iface: NetworkInterface, ip: String): Int`
  - L98 `private val VIRTUAL_HINTS`
  - L104 `private val PHYSICAL_HINTS`
  - L120 `fun matching (destination: String): String?` — The local address that shares [destination]'s /24, or null if none does.
  - L128 `fun isLoopback (host: String): Boolean` — True for addresses that only ever reach this same machine.
  - L153 `fun isOwnAddress (host: String): Boolean` — True when [host] is an address of *this* device — dialling it would mean
  - L170 `private fun isPrivate (ip: String): Boolean`
  - L181 `fun parse (raw: String, defaultPort: Int): Pair<String, Int>?` — Split a user-supplied or remembered address into host and port, filling in

### core/src/main/kotlin/com/securemessenger/core/net/LocalRelayServer.kt (114 سطر)  [package com.securemessenger.core.net]
- L8 `private const val TAG`
- L11 `private const val MAX_CONCURRENT_CONNECTIONS`
- L19 `private const val MAX_FRAME_CHARS`
- L29 `private const val MAX_FRAMES_PER_WINDOW`
- L30 `private const val RATE_WINDOW_MS`
- L42 `class LocalRelayServer (port: Int) : NanoWSD(port)` — This device's own relay — there is no external server at all. Every phone
  يُستخدم في: app/src/main/java/com/securemessenger/app/network/SecureMessagingClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt
  - L45 `var onEnvelope` — Called for every text frame from any connected peer, with a way to reply on that same socket.
  - L47 `private val openConnections`
  - L49 `override fun openWebSocket (handshake: IHTTPSession): WebSocket`

### core/src/main/kotlin/com/securemessenger/core/net/RelayBlob.kt (109 سطر)  [package com.securemessenger.core.net]
- L34 `object RelayBlob` — RelayBlob — the wire format of a single opaque blob handed to the blind relay.
  يُستخدم في: app/src/androidTest/java/com/securemessenger/app/network/RelayRoundtripTest.kt, app/src/main/java/com/securemessenger/app/network/RelayClient.kt, desktop/src/main/kotlin/com/securemessenger/desktop/DesktopRelayClient.kt
  - L37 `const val CHUNK_BYTES`
  - L40 `const val MAX_CHUNKS`
  - L43 `const val SIZE_CLASS_BYTES`
  - L46 `private const val FILLER_FIELD_OVERHEAD`
  - L49 `data class Chunk (val group: String, val index: Int, val count: Int, val data: ByteArray)`
  - L59 `fun seal ( pairSecret: ByteArray, payload: ByteArray, group: String, minSizeClasses: Int = 1 ): List<String>` — Split [payload] and seal each chunk under the key derived from [pairSecret].
  - L96 `fun open (pairSecret: ByteArray, blobBase64: String): Chunk?` — Open one blob. Returns null for anything that doesn't decrypt or doesn't


## desktop/

### desktop/src/main/kotlin/com/securemessenger/desktop/AppRoot.kt (223 سطر)  [package com.securemessenger.desktop]
- L37 `@Composable fun AppRoot (file: File, relayUrl: String)` — Top-level state machine: locked → unlocked, with a first-run branch that
  - L38 `val scope`
  - L39 `var store`
  - L40 `var client`
- L66 `@Composable
private fun UnlockScreen (file: File, onOpened: (DesktopStore) -> Unit)`
  - L67 `val scope`
  - L70 `var generation`
  - L71 `val store`
  - L72 `val isFirstRun`
  - L73 `var confirmDiscard`
  - L75 `var passphrase`
  - L76 `var confirm`
  - L77 `var name`
  - L78 `var error`
  - L79 `var busy`
  - L81 `fun submit ()`

### desktop/src/main/kotlin/com/securemessenger/desktop/DesktopDiscovery.kt (101 سطر)  [package com.securemessenger.desktop]
- L11 `data class DesktopPeer (val host: String, val port: Int, val token: String)`
- L27 `class DesktopDiscovery` — mDNS/DNS-SD for the desktop client, the counterpart to Android's NsdManager.
  - L33 `private var jmdns : JmDNS?`
  - L34 `private var registered : ServiceInfo?`
  - L37 `private val peers`
  - L40 `@Volatile
    private var onPeersChanged`
  - L44 `@Volatile var boundAddress : String?` — The interface address jmDNS bound to — also what we advertise as our direct address.
  - L47 `fun start (port: Int, token: String, onPeers: (List<DesktopPeer>) -> Unit)`
  - L83 `fun register (port: Int, token: String)` — (Re-)advertise under [token]. Called again whenever the rotating token rolls over.
  - L91 `fun stop ()`

### desktop/src/main/kotlin/com/securemessenger/desktop/DesktopMessagingClient.kt (965 سطر) ⚠  [package com.securemessenger.desktop]
- L38 `private const val TAG`
- L41 `const val LOCAL_RELAY_PORT`
- L43 `private const val TOKEN_ROTATION_MS`
- L44 `private const val SERVER_IDLE_TIMEOUT_MS`
- L45 `private const val LOCAL_BUNDLE_TIMEOUT_MS`
- L46 `private const val RELAY_BUNDLE_TIMEOUT_MS`
- L47 `private const val OUTBOX_RETRY_MS`
- L48 `private const val BUNDLE_HANDOUT_MIN_INTERVAL_MS`
- L50 `private const val TYPING_TIMEOUT_MS`
- L65 `class DesktopMessagingClient ( private val store: DesktopStore, private val relayUrl: String, private val onMessage: (contactId: String, text: String, clientMessageId: String?) -> Unit, private val onStateChanged: () -> Unit )` — The desktop client's half of the protocol.
- L66 `private val store : DesktopStore,`
- L67 `private val relayUrl : String,`
- L68 `private val onMessage`
- L69 `private val onStateChanged`
  - L80 `private var scope`
  - L92 `private val localHttp`
  - L102 `private val relayHttp`
  - L106 `private var server : LocalRelayServer?`
  - L107 `var boundPort : Int`
  - L110 `private val discovery`
  - L111 `private val peerSockets`
  - L112 `private val discoveredByToken`
  - L113 `private val pendingBundleRequests`
  - L114 `private val pendingChallengeNonces`
  - L115 `private val lastBundleHandout`
  - L117 `private val lastTypingAt`
  - L119 `private val sessions`
  - L120 `private val responderEphemerals`
  - L121 `private val initiatorOtkIds`
  - L122 `private val initiatorPqCiphertexts`
  - L124 `private val contactLocks`
  - L125 `private fun lockFor (contactId: String): Mutex`
  - L134 `private val handshakeLocks`
  - L135 `private fun handshakeLockFor (contactId: String): Mutex`
  - L145 `private val seenEnvelopes`
  - L149 `private val relay : DesktopRelayClient?`
  - L156 `@Volatile var status : String`
  - L159 `private var jobs`
  - L163 `fun start ()`
  - L230 `fun stop ()`
  - L243 `fun myDirectAddress (): String?`
  - L248 `private fun currentBucket (): Long`
  - L251 `private fun shortToken (publicKey: ByteArray, bucket: Long): String`
  - L256 `private fun myToken (): String`
  - L258 `private fun contactIdForToken (token: String): String?`
  - L267 `private suspend fun connectionTo (contactId: String): WebSocket?`
  - L293 `private suspend fun open (contactId: String, host: String, port: Int): WebSocket?`
  - L324 `private suspend fun sendDirect (contactId: String, envelope: String): Boolean`
  - L327 `private suspend fun sendToContact (contactId: String, envelope: String): Boolean`
  - L332 `private suspend fun flushOutboxTo (contactId: String)`
  - L337 `private suspend fun retryOutbox ()`
  - L347 `suspend fun sendMessage (contactId: String, text: String): Boolean`
  - L368 `private suspend fun sendMessageAndTrack (contactId: String, text: String, clientMessageId: String): Boolean`
  - L385 `private suspend fun sendMessageInternal (contactId: String, text: String, clientMessageId: String)`
  - L446 `private suspend fun retryPendingSends ()`
  - L453 `private suspend fun retryPendingSendsTo (contactId: String)`
  - L461 `private suspend fun handleIncoming (jsonString: String, reply: (String) -> Unit = {})`
  - L504 `private suspend fun onceOnly (type: String, json: JSONObject, handler: suspend () -> String?): String?`
  - L515 `private fun unseal (outer: JSONObject): JSONObject`
  - L518 `private suspend fun handleMessage (outer: JSONObject): String?`
  - L578 `private fun handleAck (json: JSONObject)`
  - L592 `private fun handleReceipt (outer: JSONObject): String?`
  - L609 `private fun handleTyping (outer: JSONObject)`
  - L618 `private suspend fun handleChallenge (outer: JSONObject, reply: (String) -> Unit)`
  - L639 `private fun buildBundle (nonce: ByteArray): JSONObject`
  - L659 `private fun handleBundleAnnounce (outer: JSONObject)`
  - L675 `private suspend fun fetchBundle (contactId: String): Envelopes.PrekeyBundle`
  - L711 `private fun cached (contactId: String): SignalProtocol?`
  - L723 `private fun persistSession (contactId: String)`
  - L736 `private suspend fun initiatorSession (contactId: String): SignalProtocol`
  - L770 `private fun sessionForIncoming (senderId: String, json: JSONObject): SignalProtocol?`
  - L785 `private fun responderSession (senderId: String, json: JSONObject): SignalProtocol`
  - L814 `private suspend fun handleRelayEnvelope (envelopeJson: String, pendingSecretHex: String?)`
  - L848 `fun myQrPayload (): String` — The QR payload this client shows for a phone to scan. Same schema the phone produces.
  - L863 `class KeyChangedException (val payload: String, val contactId: String) : Exception( )` — Thrown by [pairFromPayload] when the scanned key differs from one already pinned — see [DesktopStore.ContactPairResult.KEY_CHANGED].
  - L872 `fun pairFromPayload (payload: String, allowKeyChange: Boolean = false): Result<String>` — warned the user this contact's pinned key is about to change and
  - L907 `fun describeRoute (contactId: String): String` — Why a message to this contact will or won't get through — same three states the phone reports.
  - L920 `fun setDirectAddress (contactId: String, hostPort: String?)`
  - L926 `fun isTyping (contactId: String): Boolean` — True while a typing pulse from this contact is still within its display window.
  - L935 `suspend fun sendTypingSignal (contactId: String): Boolean`
  - L946 `suspend fun sendReadReceipt (contactId: String, clientMessageIds: List<String>): Boolean`

### desktop/src/main/kotlin/com/securemessenger/desktop/DesktopRelayClient.kt (230 سطر)  [package com.securemessenger.desktop]
- L19 `private const val TAG`
- L33 `class DesktopRelayClient ( private val store: DesktopStore, private val baseUrl: String, private val http: OkHttpClient, private val onEnvelope: suspend (envelopeJson: String, arrivedOnPendingSecretHex: String?) -> Unit )` — The blind-mailbox fallback for contacts who aren't on this LAN.
- L34 `private val store : DesktopStore,`
- L35 `private val baseUrl : String,`
- L36 `private val http : OkHttpClient,`
- L37 `private val onEnvelope`
  - L39 `private data class Subscription (val secret: ByteArray, val pendingSecretHex: String?)`
  - L41 `private class Partial (val count: Int)`
  - L47 `private val subscriptions`
  - L48 `private val partials`
  - L49 `private val random`
  - L50 `private var pollJob : Job?`
  - L51 `private lateinit var scope : CoroutineScope`
  - L53 `fun start (scope: CoroutineScope)`
  - L71 `fun stop ()`
  - L82 `fun refreshSubscriptions ()` — Recompute the mailboxes we listen on: one per contact whose inbound
  - L107 `suspend fun enqueue (contactId: String, envelopeJson: String): Boolean`
  - L130 `private suspend fun pollOnce ()`
  - L152 `private suspend fun handleBlob (mailboxId: String, bodyBase64: String)`
  - L184 `private fun prunePartials ()`
  - L189 `private suspend fun depositBlobs (mailboxId: String, blobs: List<String>)`
  - L197 `private fun post (path: String, body: String): String?`
  - L215 `private fun randomBetween (minMs: Long, maxMs: Long): Long`

### desktop/src/main/kotlin/com/securemessenger/desktop/DesktopStore.kt (592 سطر) ⚠  [package com.securemessenger.desktop]
- L33 `class DesktopStore (private val file: File)` — All persistent state for the desktop client, encrypted at rest.
  - L41 `private var masterKey : ByteArray?`
  - L45 `var userId : String`
  - L47 `var displayName : String`
  - L49 `lateinit var identity : SignalProtocol.IdentityKeyPair`
  - L51 `lateinit var signedPreKey : SignalProtocol.PreKeyPair`
  - L53 `var signingPublicKey : ByteArray`
  - L55 `var signingSecretKey : ByteArray`
  - L57 `var mlkemPublicKey : ByteArray`
  - L59 `var mlkemSecretKey : ByteArray`
  - L62 `private val oneTimePreKeys`
  - L63 `private val contacts`
  - L64 `private val sessions`
  - L65 `private val messages`
  - L66 `private val outbox`
  - L67 `private val pendingSends`
  - L70 `private val pendingPairSecrets`
  - L72 `data class StoredPreKey (val id: Int, val publicKey: ByteArray, val secretKey: ByteArray, var used: Boolean)`
  - L74 `data class StoredContact ( val id: String, val publicKey: ByteArray, var displayName: String, /** Scanned off THEIR QR — we deposit into the mailbox it addresses. */ var relaySendSecret: ByteArray? = null, /** Minted by US and taken by them — we listen on the mailbox it addresses. */ var relayRecvSecret: ByteArray? = null, /** "host:port" that worked, came in on a QR, or was typed by hand. */ var directAddress: String? = null, /** Local-only, like the phone — non-null = pinned, and doubles as the sort key among several pins. */ var pinnedAt: Long? = null )`
  - L75 `val id : String,`
  - L76 `val publicKey : ByteArray,`
  - L77 `var displayName : String,`
  - L79 `var relaySendSecret : ByteArray?` — Scanned off THEIR QR — we deposit into the mailbox it addresses.
  - L81 `var relayRecvSecret : ByteArray?` — Minted by US and taken by them — we listen on the mailbox it addresses.
  - L83 `var directAddress : String?` — "host:port" that worked, came in on a QR, or was typed by hand.
  - L85 `var pinnedAt : Long?` — Local-only, like the phone — non-null = pinned, and doubles as the sort key among several pins.
  - L88 `data class StoredSession ( val state: SignalProtocol.SessionState, val isInitiator: Boolean, val responderEphemeralHex: String?, val initiatorOtkId: Int? )`
  - L89 `val state : SignalProtocol.SessionState,`
  - L90 `val isInitiator : Boolean,`
  - L91 `val responderEphemeralHex : String?,`
  - L92 `val initiatorOtkId : Int?`
  - L95 `data class StoredMessage ( val contactId: String, val outgoing: Boolean, val text: String, val timestamp: Long, val clientMessageId: String?, var delivered: Boolean = false, // Outgoing: the recipient's receipt confirmed they read it. Incoming: // we've already told them we saw it, so we don't re-send a receipt. var read: Boolean = false )`
  - L96 `val contactId : String,`
  - L97 `val outgoing : Boolean,`
  - L98 `val text : String,`
  - L99 `val timestamp : Long,`
  - L100 `val clientMessageId : String?,`
  - L101 `var delivered : Boolean`
  - L104 `var read : Boolean`
  - L107 `data class StoredOutbox ( val id: String, val recipientId: String, val envelope: String, val clientMessageId: String? )`
  - L108 `val id : String,`
  - L109 `val recipientId : String,`
  - L110 `val envelope : String,`
  - L111 `val clientMessageId : String?`
  - L120 `data class StoredPendingSend (val clientMessageId: String, val contactId: String, val text: String)`
  - L124 `fun exists (): Boolean`
  - L131 `fun unlock (passphrase: CharArray): Boolean` — Open an existing store. Returns false when the passphrase is wrong — the
  - L154 `fun create (passphrase: CharArray, name: String)` — Create a brand-new identity and write the first encrypted state file.
  - L181 `private var saltForFile : ByteArray`
  - L183 `private fun deriveKey (passphrase: CharArray, salt: ByteArray): ByteArray`
  - L201 `enum class ContactPairResult`
  - L212 `@Synchronized fun upsertContact (contact: StoredContact, allowKeyChange: Boolean = false): ContactPairResult` — Refuses to silently replace an EXISTING contact's pinned identity key
  - L231 `@Synchronized fun contact (id: String): StoredContact?`
  - L233 `@Synchronized fun allContacts (): List<StoredContact>`
  - L236 `@Synchronized fun setDirectAddress (contactId: String, hostPort: String?)`
  - L242 `@Synchronized fun setPinned (contactId: String, pinned: Boolean)` — Local-only, like the phone — nothing about this is ever sent to a peer or the relay.
  - L247 `@Synchronized fun bindRelayRecvSecret (contactId: String, secret: ByteArray)`
  - L252 `@Synchronized fun unusedPreKeys (): List<SignalProtocol.PreKeyPair>`
  - L256 `@Synchronized fun preKeySecret (id: Int): ByteArray?`
  - L259 `@Synchronized fun markPreKeyUsed (id: Int)`
  - L264 `@Synchronized fun session (contactId: String): StoredSession?`
  - L267 `@Synchronized fun saveSession (contactId: String, session: StoredSession)`
  - L273 `@Synchronized fun addMessage (message: StoredMessage)`
  - L279 `@Synchronized fun messagesWith (contactId: String): List<StoredMessage>`
  - L283 `@Synchronized fun markDelivered (clientMessageId: String?)`
  - L295 `@Synchronized fun markReadByRecipient (contactId: String, clientMessageIds: List<String>)` — Apply an incoming read receipt. Scoped to [contactId] so a receipt can
  - L314 `@Synchronized fun markSeenLocallyAndGetNewIds (contactId: String): List<String>` — Mark every not-yet-seen incoming message from [contactId] as seen, and
  - L325 `@Synchronized fun addOutbox (entry: StoredOutbox)`
  - L331 `@Synchronized fun removeOutbox (id: String)`
  - L340 `@Synchronized fun allOutbox (): List<StoredOutbox>`
  - L343 `@Synchronized fun addPendingSend (entry: StoredPendingSend)`
  - L349 `@Synchronized fun removePendingSend (clientMessageId: String)`
  - L354 `@Synchronized fun allPendingSends (): List<StoredPendingSend>`
  - L357 `@Synchronized fun pendingSendsForContact (contactId: String): List<StoredPendingSend>`
  - L361 `@Synchronized fun pendingSecrets (): List<String>`
  - L364 `@Synchronized fun addPendingSecret (hex: String)`
  - L375 `@Synchronized fun removePendingSecret (hex: String)`
  - L382 `@Synchronized
    private fun save ()`
  - L463 `private fun load (json: JSONObject)`
  - L556 `private fun encodeSessionState (s: SignalProtocol.SessionState): JSONObject`
  - L572 `private fun decodeSessionState (o: JSONObject): SignalProtocol.SessionState`

### desktop/src/main/kotlin/com/securemessenger/desktop/LocalBoundSocketFactory.kt (73 سطر)  [package com.securemessenger.desktop]
- L31 `object LocalBoundSocketFactory` — Routes local peer traffic out the interface that faces the peer, so a VPN's
  - L34 `override fun createSocket (): Socket`
  - L36 `override fun createSocket (host: String, port: Int): Socket`
  - L39 `override fun createSocket (host: InetAddress, port: Int): Socket`
  - L42 `override fun createSocket (host: String, port: Int, localHost: InetAddress, localPort: Int): Socket`
  - L48 `override fun createSocket (address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket`
  - L54 `private fun connect (target: InetSocketAddress, host: String): Socket`

### desktop/src/main/kotlin/com/securemessenger/desktop/Main.kt (102 سطر)  [package com.securemessenger.desktop]
- L28 `fun storeFile (): File` — Desktop entry point.
  - L29 `val home`
  - L30 `val dir`
- L51 `private fun preferredWindowSize (): DpSize`
  - L56 `val screen`
- L63 `fun relayUrl (): String`
- L68 `fun main ()`
  - L86 `val windowState`
  - L90 `var open`

### desktop/src/main/kotlin/com/securemessenger/desktop/MainScreen.kt (527 سطر) ⚠  [package com.securemessenger.desktop]
- L41 `@Composable fun MainScreen (store: DesktopStore, client: DesktopMessagingClient)` — Chat list on the left, conversation on the right — the ordinary desktop messenger shape.
  - L42 `var selected`
  - L43 `var showPairing`
  - L47 `var revision`
- L177 `@Composable
private fun ConversationPane ( store: DesktopStore, client: DesktopMessagingClient, contactId: String, revision: Int, modifier: Modifier = Modifier )`
  - L184 `val scope`
  - L185 `var draft`
  - L186 `var error`
  - L193 `var sending`
  - L194 `var showAddress`
  - L195 `var lastTypingSentAt`
  - L196 `val listState`
  - L198 `val contact`
  - L199 `val messages`
  - L211 `fun send ()`
- L379 `@Composable
private fun PairingDialog (client: DesktopMessagingClient, onClose: () -> Unit)`
  - L380 `val scope`
  - L381 `val payload`
  - L382 `val qr`
  - L383 `var pasted`
  - L384 `var message`
  - L387 `var pendingKeyChange`
  - L389 `fun consume (text: String, allowKeyChange: Boolean = false)`
- L520 `private fun pickImageFile (): File?`
  - L521 `val dialog`
  - L523 `val dir`
  - L524 `val name`

### desktop/src/main/kotlin/com/securemessenger/desktop/QrCodec.kt (77 سطر)  [package com.securemessenger.desktop]
- L28 `object QrCodec` — QR in both directions, because pairing has to work without a camera on this side.
  - L35 `fun render (payload: String, size: Int = 640): ImageBitmap` — Error correction level H and a wide quiet zone on purpose: the phone may
  - L59 `fun decodeFile (file: File): String?` — Read a QR payload out of a saved picture. Returns null if there is no

### desktop/src/test/kotlin/com/securemessenger/core/net/DirectoryProtocolTest.kt (211 سطر)  [package com.securemessenger.core.net]
- L27 `class DirectoryProtocolTest` — Cross-language contract tests. The three hex vectors below were not
  - L30 `@Before fun installPlatform ()`
  - L34 `private val identityPublicKey`
  - L35 `private val signingPublicKey`
  - L89 `private fun signer (secretKey: ByteArray)`
  - L172 `val json`
  - L178 `val parsed`
  - L180 `val farInTheFuture`
- L194 `private fun ByteArray (): String`

### desktop/src/test/kotlin/com/securemessenger/desktop/LoopbackMessagingTest.kt (236 سطر)  [package com.securemessenger.desktop]
- L33 `class LoopbackMessagingTest` — Two real clients, two real sockets, one real conversation.
  - L36 `@Before fun installPlatform ()`
  - L45 `private fun newStore (name: String): DesktopStore`
  - L165 `val newlySeen`
  - L169 `val readConfirmed`
- L181 `val dir`
- L182 `val file`
- L183 `val passphrase`
- L185 `val original`
- L187 `val userId`
- L188 `val identityPublic`
- L190 `val reopened`
- L198 `val wrong`
- L202 `private fun waitFor (timeoutMs: Long, condition: () -> Boolean): Boolean`
- L203 `val deadline`

### desktop/src/test/kotlin/com/securemessenger/desktop/RelayMessagingTest.kt (190 سطر)  [package com.securemessenger.desktop]
- L23 `private const val LOCAL_RELAY_URL`
- L62 `class RelayMessagingTest` — Two real clients, no sockets between them at all — everything routes through
  - L65 `@Before fun installPlatform ()`
  - L75 `@Before fun ensureLocalRelayIsRunning ()`
  - L89 `private fun newStore (name: String): DesktopStore`
  - L97 `private fun stripAddress (payload: String): String`
  - L181 `private fun waitFor (timeoutMs: Long, condition: () -> Boolean): Boolean`

### desktop/src/test/kotlin/com/securemessenger/desktop/ScopeReuseTest.kt (105 سطر)  [package com.securemessenger.desktop]
- L35 `class ScopeReuseTest` — Reproduces, and guards against, a real bug: DesktopMessagingClient.scope
  - L38 `@Before fun installPlatform ()`
  - L47 `private fun newStore (name: String): DesktopStore`


## hisn/

### hisn/src/androidTest/java/com/hisn/app/audit/AuditLogicTest.kt (129 سطر)  [package com.hisn.app.audit]
- L13 `class AuditLogicTest` — JVM unit tests for the pure detection/scoring/chain logic. These run on plain
  - L17 `@Test fun usbDebuggingOpen_isMedium ()`
  - L20 `@Test fun rootedDevice_isHigh ()`
  - L23 `@Test fun noScreenLock_isHigh ()`
  - L26 `@Test fun screenLockPresent_isOk ()`
  - L29 `@Test fun accessibilityService_isHigh ()`
  - L32 `@Test fun appWithThreeSensitivePerms_isMedium ()`
  - L35 `@Test fun appWithTwoSensitivePerms_isOk ()`
  - L38 `@Test fun securityPatch_bands ()`
  - L47 `private fun f (sev: Severity)`
  - L49 `@Test fun cleanDevice_scores100 ()`
  - L52 `@Test fun penaltiesAddUp ()`
  - L58 `@Test fun scoreFloorsAtZero ()`
  - L65 `private fun sampleChain (): List<AuditEntry>`
  - L72 `@Test fun validChain_verifies ()`
  - L75 `@Test fun alteredEntry_breaksChain ()`
  - L82 `@Test fun deletedEntry_breaksChain ()`
  - L88 `@Test fun reorderedEntries_breakChain ()`
  - L94 `@Test fun emptyChain_isTriviallyValid ()`
  - L99 `private fun finding (id: String, title: String, sev: Severity)`
  - L101 `@Test fun firstScan_noChange ()`
  - L106 `@Test fun identicalScan_noChange ()`
  - L112 `@Test fun accessibilityGained_isWorsened ()`
  - L120 `@Test fun screenLockAdded_isImproved ()`

### hisn/src/androidTest/java/com/hisn/app/audit/VerifyImportTest.kt (75 سطر)  [package com.hisn.app.audit]
- L16 `class VerifyImportTest` — End-to-end tests for exporting a log and verifying an imported copy — the
  - L18 `private val ctx`
  - L21 `@Before fun freshLog ()`
  - L26 `private fun exportJson (): String`
  - L33 `@Test fun validExport_intactAndSealMatchesThisDevice ()`
  - L54 `@Test fun tamperedEntry_breaksChain ()`
  - L65 `@Test fun foreignDeviceSeal_chainIntactButSealDoesNotMatch ()`

### hisn/src/androidTest/java/com/hisn/app/notify/NotificationTest.kt (63 سطر)  [package com.hisn.app.notify]
- L19 `class NotificationTest` — Proves the notification path actually renders a posted notification (channel,
  - L22 `val permission : GrantPermissionRule`
  - L25 `private val context : Context`
  - L29 `@Test fun changeNotification_isPostedWithReasonAndScores ()`

### hisn/src/main/java/com/hisn/app/HisnApp.kt (24 سطر)  [package com.hisn.app]
- L11 `class HisnApp : Application()`
  - L12 `override fun onCreate ()`

### hisn/src/main/java/com/hisn/app/audit/AuditChain.kt (73 سطر)  [package com.hisn.app.audit]
- L13 `data class AuditEntry ( val timestamp: Long, val score: Int, val critical: Int, val high: Int, val medium: Int, val low: Int, val prevHash: String, val hash: String )`
- L14 `val timestamp : Long,`
- L15 `val score : Int,`
- L16 `val critical : Int,`
- L17 `val high : Int,`
- L18 `val medium : Int,`
- L19 `val low : Int,`
- L20 `val prevHash : String,`
- L21 `val hash : String`
- L30 `object AuditChain` — AuditChain - pure hash-chain logic (no Android APIs), so it is unit-tested on
  - L32 `const val GENESIS`
  - L35 `fun payload (timestamp: Long, score: Int, critical: Int, high: Int, medium: Int, low: Int, prevHash: String): String` — Deterministic content of an entry that the hash commits to.
  - L38 `fun hashOf (timestamp: Long, score: Int, critical: Int, high: Int, medium: Int, low: Int, prevHash: String): String`
  - L42 `fun append (prev: AuditEntry?, timestamp: Long, score: Int, critical: Int, high: Int, medium: Int, low: Int): AuditEntry` — Build the next entry that links to [prev] (or GENESIS for the first).
  - L51 `data class ChainResult (val valid: Boolean, val brokenAtIndex: Int)`
  - L54 `fun verifyDetailed (entries: List<AuditEntry>): ChainResult` — Device-independent integrity check — pure SHA-256, needs no secret key.
  - L66 `fun verify (entries: List<AuditEntry>): Boolean` — True iff every entry links correctly to its predecessor.
  - L68 `fun sha256Hex (input: String): String`

### hisn/src/main/java/com/hisn/app/audit/AuditLog.kt (195 سطر)  [package com.hisn.app.audit]
- L21 `object AuditLog` — AuditLog - append-only, tamper-evident history of scans.
  يُستخدم في: hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt, hisn/src/main/java/com/hisn/app/ui/MainActivity.kt, hisn/src/main/java/com/hisn/app/ui/VerifyActivity.kt
  - L23 `private const val PREFS`
  - L24 `private const val KEY_HISTORY`
  - L25 `private const val KEY_SNAPSHOT`
  - L26 `private const val KEY_DEVICE_KEY`
  - L27 `private const val MAX_ENTRIES`
  - L28 `const val EXPORT_VERSION`
  - L30 `private fun prefs (context: Context)`
  - L39 `fun record (context: Context, timestamp: Long, findings: List<Finding>): AuditEntry`
  - L56 `fun history (context: Context): List<AuditEntry>`
  - L60 `fun saveSnapshot (context: Context, snapshot: ScanSnapshot)`
  - L71 `fun loadSnapshot (context: Context): ScanSnapshot?`
  - L88 `data class ExportResult (val json: String, val entryCount: Int)`
  - L90 `fun export (context: Context, exportedAt: Long): ExportResult`
  - L111 `data class VerificationReport ( val parseError: String?, val entryCount: Int, val chainIntact: Boolean, val brokenAtIndex: Int, // -1 if intact val sealMatchesThisDevice: Boolean, val firstTs: Long, val lastTs: Long, val timeline: List<Pair<Long, Int>> // (timestamp, score) )`
  - L112 `val parseError : String?,`
  - L113 `val entryCount : Int,`
  - L114 `val chainIntact : Boolean,`
  - L115 `val brokenAtIndex`
  - L116 `val sealMatchesThisDevice : Boolean,`
  - L117 `val firstTs : Long,`
  - L118 `val lastTs : Long,`
  - L119 `val timeline`
  - L122 `fun verifyImport (context: Context, json: String): VerificationReport`
  - L152 `private fun readEntries (p: android.content.SharedPreferences): List<AuditEntry>`
  - L155 `private fun entriesToJson (entries: List<AuditEntry>): JSONArray`
  - L168 `private fun jsonToEntries (arr: JSONArray): List<AuditEntry>`
  - L179 `private fun deviceKey (p: android.content.SharedPreferences): ByteArray`
  - L186 `private fun hmacHex (key: ByteArray, message: String): String`
  - L192 `private fun hexToBytes (hex: String): ByteArray`

### hisn/src/main/java/com/hisn/app/audit/ChangeDetector.kt (68 سطر)  [package com.hisn.app.audit]
- L4 `data class SnapFinding (val id: String, val title: String, val severity: Severity)`
  يُستخدم في: hisn/src/androidTest/java/com/hisn/app/notify/NotificationTest.kt, hisn/src/main/java/com/hisn/app/notify/HisnNotifications.kt, hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt
- L5 `data class ScanSnapshot (val score: Int, val findings: List<SnapFinding>)`
  يُستخدم في: hisn/src/androidTest/java/com/hisn/app/notify/NotificationTest.kt, hisn/src/main/java/com/hisn/app/notify/HisnNotifications.kt, hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt
- L7 `enum class ChangeDirection`
  يُستخدم في: hisn/src/androidTest/java/com/hisn/app/notify/NotificationTest.kt, hisn/src/main/java/com/hisn/app/notify/HisnNotifications.kt, hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt
- L13 `data class Change ( val direction: ChangeDirection, val oldScore: Int, val newScore: Int, val reasons: List<String> )`
  يُستخدم في: hisn/src/androidTest/java/com/hisn/app/notify/NotificationTest.kt, hisn/src/main/java/com/hisn/app/notify/HisnNotifications.kt, hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt
- L14 `val direction : ChangeDirection,`
- L15 `val oldScore : Int,`
- L16 `val newScore : Int,`
- L17 `val reasons : List<String>`
- L24 `object ChangeDetector` — ChangeDetector - pure comparison of a previous [ScanSnapshot] to new findings.
  يُستخدم في: hisn/src/androidTest/java/com/hisn/app/notify/NotificationTest.kt, hisn/src/main/java/com/hisn/app/notify/HisnNotifications.kt, hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt
  - L26 `fun detect (previous: ScanSnapshot?, newFindings: List<Finding>, newScore: Int): Change?`
  - L54 `fun snapshotOf (findings: List<Finding>): ScanSnapshot`
  - L60 `private fun label (s: Severity): String`

### hisn/src/main/java/com/hisn/app/audit/Finding.kt (30 سطر)  [package com.hisn.app.audit]
- L8 `enum class Severity (val rank: Int)`
  يُستخدم في: hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt, hisn/src/main/java/com/hisn/app/ui/MainActivity.kt, hisn/src/main/java/com/hisn/app/ui/VerifyActivity.kt
- L23 `data class Finding ( val id: String, val title: String, val detail: String, val recommendation: String, val severity: Severity )`
  يُستخدم في: hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt, hisn/src/main/java/com/hisn/app/ui/MainActivity.kt, hisn/src/main/java/com/hisn/app/ui/VerifyActivity.kt
- L24 `val id : String,`
- L25 `val title : String,`
- L26 `val detail : String,`
- L27 `val recommendation : String,`
- L28 `val severity : Severity`

### hisn/src/main/java/com/hisn/app/audit/HygieneScore.kt (31 سطر)  [package com.hisn.app.audit]
- L11 `object HygieneScore` — HygieneScore - a single 0..100 number computed from findings, so the user gets
  يُستخدم في: hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt, hisn/src/main/java/com/hisn/app/ui/MainActivity.kt, hisn/src/main/java/com/hisn/app/ui/VerifyActivity.kt
  - L13 `fun penalty (severity: Severity): Int`
  - L21 `fun compute (findings: List<Finding>): Int`
  - L25 `fun band (score: Int): Severity` — A coarse band for colouring the gauge.

### hisn/src/main/java/com/hisn/app/audit/Rules.kt (44 سطر)  [package com.hisn.app.audit]
- L11 `object Rules` — Rules - the pure severity-classification logic, separated from any Android API
  - L13 `fun screenLock (secure: Boolean): Severity`
  - L16 `fun usbDebugging (enabled: Boolean): Severity`
  - L20 `fun securityPatch (monthsOld: Int): Severity` — monthsOld < 0 means "unknown".
  - L27 `fun rootIndicators (present: Boolean): Severity`
  - L31 `fun accessibility (nonSystemServiceCount: Int): Severity` — Any third-party accessibility service can read the whole screen.
  - L34 `fun deviceAdmin (nonSystemAdminCount: Int): Severity`
  - L37 `fun sideloadedApps (count: Int): Severity`
  - L41 `fun sensitivePermissionCombo (grantedSensitiveCount: Int): Severity` — An app holding 3+ sensitive permissions (mic/cam/sms/...) at once.

### hisn/src/main/java/com/hisn/app/audit/SystemAuditor.kt (269 سطر)  [package com.hisn.app.audit]
- L21 `class SystemAuditor (private val context: Context)` — SystemAuditor - runs a set of on-device security-hygiene checks that a normal
  يُستخدم في: hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt
  - L23 `private val knownStores`
  - L33 `private val sensitivePermissions`
  - L44 `suspend fun runAudit (): List<Finding>`
  - L56 `private fun checkScreenLock (): Finding`
  - L68 `private fun checkSecurityPatch (): Finding`
  - L81 `private fun checkUsbDebugging (): Finding`
  - L92 `private fun checkRootIndicators (): Finding`
  - L109 `private fun checkAccessibilityServices (): Finding`
  - L128 `private fun checkDeviceAdmins (): Finding`
  - L145 `private fun checkInstalledApps (): List<Finding>`
  - L222 `private fun installerOf (pkg: String): String?`
  - L235 `private fun isSystemApp (pkg: String): Boolean`
  - L244 `private fun appLabel (pkg: String): String`
  - L254 `private fun monthsSince (dateStr: String?): Int`

### hisn/src/main/java/com/hisn/app/notify/HisnNotifications.kt (75 سطر)  [package com.hisn.app.notify]
- L23 `object HisnNotifications` — HisnNotifications - posts one clear notification when a scan finds a meaningful
  يُستخدم في: hisn/src/main/java/com/hisn/app/HisnApp.kt, hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt
  - L25 `private const val CHANNEL`
  - L26 `const val NOTIF_ID`
  - L28 `fun ensureChannel (context: Context)`
  - L37 `fun notifyChange (context: Context, change: Change)`
  - L68 `private fun hasPermission (context: Context): Boolean`

### hisn/src/main/java/com/hisn/app/scan/HygieneScanWorker.kt (22 سطر)  [package com.hisn.app.scan]
- L11 `class HygieneScanWorker (context: Context, params: WorkerParameters) : CoroutineWorker(context, params)` — Runs a hygiene scan in the background (periodic 24h, or on a package change) and
  يُستخدم في: hisn/src/main/java/com/hisn/app/HisnApp.kt
  - L12 `override suspend fun doWork (): Result`

### hisn/src/main/java/com/hisn/app/scan/HygieneScanner.kt (35 سطر)  [package com.hisn.app.scan]
- L17 `object HygieneScanner` — HygieneScanner - the one scan path shared by the UI (manual "فحص الآن") and the
  يُستخدم في: hisn/src/main/java/com/hisn/app/ui/MainActivity.kt
  - L19 `data class Outcome (val findings: List<Finding>, val change: Change?)`
  - L21 `suspend fun scanAndRecord (context: Context, notify: Boolean): Outcome`

### hisn/src/main/java/com/hisn/app/scan/PackageChangeReceiver.kt (21 سطر)  [package com.hisn.app.scan]
- L14 `class PackageChangeReceiver : BroadcastReceiver()` — Triggers an immediate hygiene scan when an app is installed/removed/replaced —
  - L15 `override fun onReceive (context: Context, intent: Intent)`

### hisn/src/main/java/com/hisn/app/ui/MainActivity.kt (312 سطر) ⚠  [package com.hisn.app.ui]
- L38 `class MainActivity : ComponentActivity()`
  يُستخدم في: hisn/src/main/java/com/hisn/app/notify/HisnNotifications.kt
  - L39 `override fun onCreate (savedInstanceState: Bundle?)`
- L47 `@Composable
private fun HisnTheme (content: @Composable () -> Unit)`
  - L48 `val colors`
- L59 `private fun severityColor (s: Severity): Color`
- L67 `private fun severityLabel (s: Severity): String`
- L77 `@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HisnScreen ()`
  - L78 `val context`
  - L79 `val scope`
  - L81 `var findings`
  - L82 `var scanning`
  - L83 `var scannedOnce`
  - L86 `val notifPermission`
  - L95 `fun scan ()`
  - L108 `var exportMsg`
  - L110 `fun exportLog ()`
  - L127 `val worst`
  - L128 `val issues`
  - L129 `val score`
- L210 `@Composable
private fun StatusHeader (score: Int, worst: Severity, issues: Int, scanning: Boolean, scannedOnce: Boolean)`
  - L211 `val color`
- L252 `@Composable
private fun FindingCard (f: Finding)`
- L280 `@Composable
private fun SeverityChip (s: Severity)`
  - L281 `val c`
- L293 `@Composable
private fun Disclaimer ()`

### hisn/src/main/java/com/hisn/app/ui/VerifyActivity.kt (200 سطر)  [package com.hisn.app.ui]
- L35 `class VerifyActivity : ComponentActivity()`
  - L36 `override fun onCreate (savedInstanceState: Bundle?)`
- L51 `private fun sevColor (s: Severity)`
- L58 `private fun fmt (ts: Long)`
- L63 `@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyScreen ()`
  - L64 `val context`
  - L65 `val scope`
  - L66 `var report`
  - L67 `var loading`
  - L69 `val picker`
- L126 `@Composable
private fun ChainCard (r: AuditLog.VerificationReport)`
- L137 `@Composable
private fun SealCard (r: AuditLog.VerificationReport)`
- L149 `@Composable
private fun SummaryCard (r: AuditLog.VerificationReport)`
- L162 `@Composable
private fun TimelineRow (ts: Long, score: Int)`
  - L163 `val color`
- L181 `@Composable
private fun ResultCard (ok: Boolean?, title: String, body: String)`
  - L182 `val color`
  - L183 `val icon`

