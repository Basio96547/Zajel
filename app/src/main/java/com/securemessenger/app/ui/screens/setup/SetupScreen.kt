package com.securemessenger.app.ui.screens.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.network.DirectoryClient
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.security.DevicePassphrase
import com.securemessenger.app.ui.onboardingBackground
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.core.crypto.LibsodiumWrapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val USERNAME_REGEX = Regex("^[a-z0-9_]{3,20}$")

/**
 * SetupScreen - Initial app setup and key generation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit
) {
    var step by remember { mutableStateOf(0) }
    var displayName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var accessCode by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var profileReady by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val mc = LocalMessengerColors.current

    Box(modifier = Modifier.fillMaxSize().onboardingBackground(mc.onboardingGradient)) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 26.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(22.dp))
                StepDots(step = step)
                Spacer(modifier = Modifier.height(if (step == 1) 34.dp else 40.dp))

                when (step) {
                    0 -> WelcomeStep(onContinue = { step = 1 })
                    1 -> ProfileStep(
                        displayName = displayName,
                        onDisplayNameChange = { displayName = it },
                        username = username,
                        onUsernameChange = { username = it.trim().lowercase() },
                        accessCode = accessCode,
                        onAccessCodeChange = { accessCode = it.filter { c -> c.isDigit() }.take(10) },
                        onNext = {
                            when {
                                displayName.isBlank() -> errorMessage = "الرجاء إدخال الاسم"
                                !USERNAME_REGEX.matches(username) ->
                                    errorMessage = "اسم المستخدم: 3-20 حرفاً (أحرف إنجليزية صغيرة وأرقام و _)"
                                accessCode.length !in 4..10 ->
                                    errorMessage = "رمز الفتح: من 4 إلى 10 أرقام"
                                else -> {
                                    // Chosen by the user — the app ships with no
                                    // built-in/guessable unlock code.
                                    AppSettings.setAccessCode(context, accessCode)
                                    step = 2
                                    errorMessage = null
                                }
                            }
                        }
                    )
                    2 -> GeneratingStep(
                        isGenerating = isGenerating,
                        onComplete = {
                            isGenerating = true
                            scope.launch {
                                try {
                                    val app = SecureMessengerApp.instance
                                    val repository = app.repository
                                    if (!profileReady) {
                                        // No password to type or remember — the database
                                        // key is a random secret held in Keystore-backed
                                        // storage, unique to this device.
                                        repository.initialize(DevicePassphrase.getOrCreate(context))
                                        repository.createProfile()
                                        repository.generatePreKeys(50)
                                        repository.setMyDisplayName(displayName)
                                        profileReady = true
                                    }
                                    // The handle is local-first regardless — shown on our
                                    // own QR code for in-person pairing whether or not the
                                    // optional directory claim below succeeds.
                                    AppSettings.setUsername(context, username)

                                    // Optional directory claim — reserves the username so
                                    // someone can find us by search instead of only QR.
                                    // Skipped entirely on a build with no directoryUrl
                                    // compiled in. A network failure here is NOT fatal:
                                    // SecureMessagingClient.connect() retries opportunistically
                                    // on every reveal (see AppSettings.isUsernameClaimedRemotely).
                                    // Only a live, positive "someone else already has this
                                    // exact name" answer sends the user back to pick another.
                                    val directoryUrl = com.securemessenger.app.BuildConfig.DIRECTORY_URL
                                    if (directoryUrl.isNotBlank() && !AppSettings.isUsernameClaimedRemotely(context)) {
                                        val identity = repository.getIdentityKeyPair()
                                        val signingPublic = repository.getSigningPublicKey()
                                        val signingSecret = repository.getSigningSecretKey()
                                        if (identity != null && signingPublic != null && signingSecret != null) {
                                            val claimResult = DirectoryClient(
                                                baseUrl = directoryUrl.trimEnd('/'),
                                                http = okhttp3.OkHttpClient()
                                            ).claim(username, identity.publicKey, signingPublic) { payload ->
                                                LibsodiumWrapper.signDetached(payload, signingSecret)
                                            }
                                            when (claimResult) {
                                                DirectoryClient.ClaimResult.Success, DirectoryClient.ClaimResult.AlreadyRegistered ->
                                                    AppSettings.setUsernameClaimedRemotely(context, true)
                                                DirectoryClient.ClaimResult.Taken -> {
                                                    isGenerating = false
                                                    step = 1
                                                    errorMessage = "اسم المستخدم \"@$username\" محجوز — اختر اسماً آخر"
                                                    return@launch
                                                }
                                                DirectoryClient.ClaimResult.Error -> {
                                                    // Offline/unreachable — proceed; the retry
                                                    // on next reveal will pick this up.
                                                }
                                            }
                                        }
                                    }

                                    app.initializeMessagingClient()
                                    delay(400)
                                    isGenerating = false
                                    onSetupComplete()
                                } catch (e: Exception) {
                                    android.util.Log.e("SetupScreen", "setup failed", e)
                                    isGenerating = false
                                    errorMessage = "فشل الإعداد. تحقّق من اتصالك بالإنترنت وحاول مجدداً."
                                }
                            }
                        }
                    )
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** Three dots — a wide active pill plus two narrow inactive ones — matching the reference step indicator. */
@Composable
private fun StepDots(step: Int) {
    val mc = LocalMessengerColors.current
    val inactive = mc.glassOnCard.copy(alpha = 0.15f)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index ->
            val active = index == step
            Box(
                modifier = Modifier
                    .width(if (active) 22.dp else 8.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else inactive)
            )
        }
    }
}
