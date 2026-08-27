package com.securemessenger.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.securemessenger.app.ui.screens.loading.LoadingScreen
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.securemessenger.app.SecureMessengerApp
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.security.BiometricGate
import com.securemessenger.app.security.DevicePassphrase
import com.securemessenger.app.security.DisguiseState
import com.securemessenger.app.security.RootDetector
import com.securemessenger.app.service.MessengerService
import com.securemessenger.app.ui.screens.calculator.CalculatorScreen
import com.securemessenger.app.ui.screens.chat.ChatListScreen
import com.securemessenger.app.ui.screens.chat.ConnectionRequestsScreen
import com.securemessenger.app.ui.screens.chat.ContactDetailScreen
import com.securemessenger.app.ui.screens.chat.ConversationScreen
import com.securemessenger.app.ui.screens.chat.NewChatScreen
import com.securemessenger.app.ui.screens.chat.UsernameSearchScreen
import com.securemessenger.app.ui.screens.settings.ProfileScreen
import com.securemessenger.app.ui.screens.settings.SettingsScreen
import com.securemessenger.app.ui.screens.settings.StealthModeScreen
import com.securemessenger.app.ui.screens.setup.SetupScreen
import com.securemessenger.app.ui.screens.verification.KeyVerificationScreen
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    object Calculator : Screen("calculator")
    object Loading : Screen("loading")
    object Setup : Screen("setup")
    object ChatList : Screen("chat_list")
    object Conversation : Screen("conversation/{contactId}") {
        fun createRoute(contactId: String) = "conversation/$contactId"
    }
    object Settings : Screen("settings")
    object KeyVerification : Screen("key_verification?contactId={contactId}") {
        fun createRoute(contactId: String? = null) =
            if (contactId != null) "key_verification?contactId=$contactId" else "key_verification"
    }
    object StealthMode : Screen("stealth_mode")
    object NewChat : Screen("new_chat")
    object UsernameSearch : Screen("username_search")
    object ConnectionRequests : Screen("connection_requests")
    object ContactDetail : Screen("contact_detail/{contactId}") {
        fun createRoute(contactId: String) = "contact_detail/$contactId"
    }
    object Profile : Screen("profile")
}

/*
 * Three tiers of navigation motion:
 *  - Disguise moments (calculator/loading/setup) keep the original quiet
 *    150ms crossfade — the reveal/hide of the messenger must never look
 *    like a "special" transition to a shoulder-surfer.
 *  - Switching between the bottom-nav roots is lateral, not hierarchical,
 *    so it fades through with a subtle scale instead of sliding.
 *  - Drilling into a screen (open a conversation, contact info, key
 *    verification…) slides directionally with a parallax under-layer,
 *    Telegram/iOS-style. SlideDirection.Start/End are layout-direction
 *    aware, so the push comes from the correct edge in this RTL app.
 */
private val DISGUISE_ROUTES = setOf("calculator", "loading", "setup")
private val TAB_ROUTES = setOf("chat_list", "new_chat", "settings", "profile")

private val NavEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) // Material emphasized-decelerate
private const val SLIDE_MS = 350
private const val CROSSFADE_MS = 150

private fun baseRoute(entry: NavBackStackEntry): String =
    entry.destination.route?.substringBefore('/')?.substringBefore('?') ?: ""

private fun AnimatedContentTransitionScope<NavBackStackEntry>.involvesDisguise(): Boolean =
    baseRoute(initialState) in DISGUISE_ROUTES || baseRoute(targetState) in DISGUISE_ROUTES

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    baseRoute(initialState) in TAB_ROUTES && baseRoute(targetState) in TAB_ROUTES

/**
 * Guards every screen that shows real messenger content: renders nothing
 * (just the background behind it — see MainActivity's Surface) unless
 * [DisguiseState.isRevealed] is actually true right now.
 *
 * This exists because Navigation-Compose's back stack survives process death
 * (rememberNavController() participates in the SavedStateRegistry) while
 * DisguiseState does not and correctly resets to hidden on a fresh process.
 * If Android kills the process while the user is mid-conversation and later
 * restores the Activity, the NavController can come back pointed at
 * conversation/{id} before the LaunchedEffect above ever runs (that
 * correction is reactive — it fires on a recomposition, not before the
 * first one). Without this, the restored screen could compose real message
 * content, even briefly, ahead of being redirected back to the calculator.
 * With it, that same race just renders blank instead.
 */
@Composable
private fun RevealedOnly(content: @Composable () -> Unit) {
    val isRevealed by DisguiseState.isRevealed.collectAsState()
    if (isRevealed) content()
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val isRevealed by DisguiseState.isRevealed.collectAsState()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    // Shown instead of revealing when the access code is correct but the
    // device looks rooted — stays on the calculator either way, so a wrong
    // guess and a "blocked, rooted" case both look the same from the outside
    // until this dialog actually appears.
    var showRootWarning by remember { mutableStateOf(false) }

    // The real calculator is always the true entry point. Revealing (secret
    // code) moves forward into the messenger; hiding (app backgrounded) snaps
    // straight back to the calculator regardless of where the user was.
    LaunchedEffect(isRevealed, currentRoute) {
        if (isRevealed && currentRoute == Screen.Calculator.route) {
            navController.navigate(Screen.Loading.route) {
                popUpTo(Screen.Calculator.route) { inclusive = true }
            }
        } else if (!isRevealed && currentRoute != null && currentRoute != Screen.Calculator.route) {
            navController.navigate(Screen.Calculator.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    // Pressing back from the chat list — the root of the revealed section —
    // hides instantly instead of leaving MainActivity's onStop() debounce
    // (HIDE_GRACE_PERIOD_MS, ~10s) to do it later, or waiting for the app to
    // simply be closed and left in the background. This is deliberately the
    // ONLY new "hide" trigger added rather than a visible button: back is the
    // single most unremarkable exit gesture there is — to anyone watching, it
    // looks exactly like closing an app, which is exactly what should appear
    // to happen. Deeper screens (a conversation, settings…) are untouched;
    // back there still just navigates up one level, as expected.
    BackHandler(enabled = isRevealed && currentRoute == Screen.ChatList.route) {
        DisguiseState.hide()
    }

    // "يشتغل ويطفى حسب الاستخدام": this device's own local relay + network
    // discovery only ever run while the messenger is actually revealed —
    // there is no external server to stay logged into in the background.
    LaunchedEffect(isRevealed) {
        if (!isRevealed) {
            // ...unless the user asked for background delivery, in which case
            // the whole point is that the transport outlives the visible UI.
            // Tearing it down here would silently defeat the service that is
            // holding it up.
            if (AppSettings.isBackgroundDeliveryEnabled(context)) {
                MessengerService.start(context)
            } else {
                SecureMessengerApp.instance.stopMessagingClient()
            }
        }
    }

    // Shared tab-jump actions for the glass bottom nav bar (present on
    // ChatList and Settings, the two root screens). "الدردشات" pops back to
    // the existing ChatList entry instead of stacking a fresh one on every
    // tap; the others just launchSingleTop to avoid piling up duplicates
    // when a user bounces between tabs repeatedly.
    val navToChats: () -> Unit = {
        navController.navigate(Screen.ChatList.route) {
            popUpTo(Screen.ChatList.route) { inclusive = false }
            launchSingleTop = true
        }
    }
    val navToContacts: () -> Unit = {
        navController.navigate(Screen.NewChat.route) { launchSingleTop = true }
    }
    val navToProfile: () -> Unit = {
        navController.navigate(Screen.Profile.route) { launchSingleTop = true }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Calculator.route,
        enterTransition = {
            when {
                involvesDisguise() -> fadeIn(tween(CROSSFADE_MS))
                isTabSwitch() -> fadeIn(tween(240)) +
                    scaleIn(initialScale = 0.96f, animationSpec = tween(320, easing = NavEasing))
                else -> slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(SLIDE_MS, easing = NavEasing)
                )
            }
        },
        exitTransition = {
            when {
                involvesDisguise() -> fadeOut(tween(CROSSFADE_MS))
                isTabSwitch() -> fadeOut(tween(200))
                // The screen going under doesn't leave — it drifts a third of
                // the way and gets covered, which is what reads as depth.
                else -> slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(SLIDE_MS, easing = NavEasing),
                    targetOffset = { it / 3 }
                )
            }
        },
        popEnterTransition = {
            when {
                involvesDisguise() -> fadeIn(tween(CROSSFADE_MS))
                isTabSwitch() -> fadeIn(tween(240)) +
                    scaleIn(initialScale = 0.96f, animationSpec = tween(320, easing = NavEasing))
                // Returns from the parallax position it was parked at.
                else -> slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(SLIDE_MS, easing = NavEasing),
                    initialOffset = { it / 3 }
                )
            }
        },
        popExitTransition = {
            when {
                involvesDisguise() -> fadeOut(tween(CROSSFADE_MS))
                isTabSwitch() -> fadeOut(tween(200))
                else -> slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(SLIDE_MS, easing = NavEasing)
                )
            }
        }
    ) {
        composable(Screen.Calculator.route) {
            val activity = context as? FragmentActivity
            CalculatorScreen(
                onUnlock = {
                    // A rooted device can't be trusted to keep the app's
                    // secrets from other apps/processes (root breaks the
                    // normal per-app sandbox), so the reveal itself is
                    // refused there — checked only on a correct code, never
                    // on the calculator itself, so this never tips off
                    // someone who doesn't know the code.
                    if (RootDetector.isDeviceRooted(context)) {
                        showRootWarning = true
                    } else if (activity != null && BiometricGate.canAuthenticate(activity)) {
                        // Knowing the calculator's access code is not enough on
                        // its own anymore — whoever is holding the phone right
                        // now must also pass the device's own lock
                        // (fingerprint/face/PIN). If the device has nothing
                        // enrolled at all, there is no OS lock to gate on, so
                        // fail open exactly as before rather than permanently
                        // locking the owner out of their own app.
                        BiometricGate.authenticate(
                            activity = activity,
                            onSuccess = { DisguiseState.reveal() },
                            onFailure = { /* stays on the calculator — no visible tell */ }
                        )
                    } else {
                        DisguiseState.reveal()
                    }
                },
                onDuress = {
                    // A separate, deliberately-memorized code that looks like
                    // just another failed unlock attempt from the outside —
                    // wipes everything silently in the background instead of
                    // revealing anything. Runs on the app-wide scope (NOT this
                    // screen's) so the wipe finishes even if the app is
                    // backgrounded/killed the instant it starts — exactly what
                    // happens when someone is forced to hand the phone over.
                    // wipeAllData() is itself NonCancellable and clears the DB,
                    // media blobs, decrypted caches and the secure prefs.
                    SecureMessengerApp.instance.applicationScope.launch {
                        try {
                            SecureMessengerApp.instance.repository.wipeAllData()
                        } catch (e: Exception) {
                            android.util.Log.e("Duress", "duress wipe failed", e)
                        }
                    }
                }
            )
        }

        composable(Screen.Loading.route) {
            // No password to type: an existing account unlocks itself with the
            // device's stored key; a new device goes straight to setup.
            LaunchedEffect(Unit) {
                val app = SecureMessengerApp.instance
                app.repository.initialize(DevicePassphrase.getOrCreate(context))
                // A schema upgrade (or any wipe of just the DB file) can leave a
                // stale username behind with no matching identity keypair — that
                // half-state would otherwise skip Setup yet fail every send with
                // a silently-swallowed "Identity key not found". Treat it the
                // same as a fresh install.
                val hasProfile = app.repository.getProfile() != null
                if (AppSettings.getUsername(context) == null || !hasProfile) {
                    if (!hasProfile) AppSettings.clearUsername(context)
                    navController.navigate(Screen.Setup.route) {
                        popUpTo(Screen.Loading.route) { inclusive = true }
                    }
                } else {
                    app.applicationScope.launch {
                        try {
                            app.initializeMessagingClient()
                        } catch (_: Exception) {
                            // Relay may be offline — the chat list still opens.
                        }
                    }
                    navController.navigate(Screen.ChatList.route) {
                        popUpTo(Screen.Loading.route) { inclusive = true }
                    }
                }
            }
            LoadingScreen()
        }

        composable(Screen.Setup.route) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Screen.ChatList.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.ChatList.route) {
            RevealedOnly {
                ChatListScreen(
                    onConversationClick = { contactId ->
                        navController.navigate(Screen.Conversation.createRoute(contactId))
                    },
                    onSettingsClick = { navController.navigate(Screen.Settings.route) },
                    onNewChatClick = { navController.navigate(Screen.NewChat.route) },
                    onProfileClick = { navController.navigate(Screen.Profile.route) }
                )
            }
        }

        composable(Screen.NewChat.route) {
            RevealedOnly {
                NewChatScreen(
                    onBackClick = { navController.popBackStack() },
                    onContactAdded = { contactId ->
                        navController.navigate(Screen.Conversation.createRoute(contactId)) {
                            popUpTo(Screen.NewChat.route) { inclusive = true }
                        }
                    },
                    onSearchByUsernameClick = { navController.navigate(Screen.UsernameSearch.route) },
                    onConnectionRequestsClick = { navController.navigate(Screen.ConnectionRequests.route) }
                )
            }
        }

        composable(Screen.UsernameSearch.route) {
            RevealedOnly {
                UsernameSearchScreen(onBackClick = { navController.popBackStack() })
            }
        }

        composable(Screen.ConnectionRequests.route) {
            RevealedOnly {
                ConnectionRequestsScreen(
                    onBackClick = { navController.popBackStack() },
                    onAccepted = { contactId ->
                        navController.navigate(Screen.Conversation.createRoute(contactId)) {
                            popUpTo(Screen.ChatList.route) { inclusive = false }
                        }
                    }
                )
            }
        }

        composable(Screen.Conversation.route) { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
            RevealedOnly {
                ConversationScreen(
                    contactId = contactId,
                    onBackClick = { navController.popBackStack() },
                    onVerificationClick = {
                        navController.navigate(Screen.KeyVerification.createRoute(contactId))
                    },
                    onContactInfoClick = {
                        navController.navigate(Screen.ContactDetail.createRoute(contactId))
                    }
                )
            }
        }

        composable(
            route = Screen.ContactDetail.route,
            arguments = listOf(navArgument("contactId") { type = NavType.StringType })
        ) { backStackEntry ->
            val contactId = backStackEntry.arguments?.getString("contactId") ?: return@composable
            RevealedOnly {
                ContactDetailScreen(
                    contactId = contactId,
                    onBackClick = { navController.popBackStack() },
                    onVerifyClick = {
                        navController.navigate(Screen.KeyVerification.createRoute(contactId))
                    }
                )
            }
        }

        composable(Screen.Settings.route) {
            RevealedOnly {
                SettingsScreen(
                    onBackClick = { navController.popBackStack() },
                    onVerificationClick = { navController.navigate(Screen.KeyVerification.createRoute()) },
                    onStealthModeClick = { navController.navigate(Screen.StealthMode.route) },
                    onDataWiped = {
                        navController.navigate(Screen.Setup.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onProfileClick = { navController.navigate(Screen.Profile.route) },
                    onNavChats = navToChats,
                    onNavContacts = navToContacts,
                    onNavProfile = navToProfile
                )
            }
        }

        composable(Screen.Profile.route) {
            RevealedOnly {
                ProfileScreen(
                    onBackClick = { navController.popBackStack() },
                    onVerifyClick = { navController.navigate(Screen.KeyVerification.createRoute()) },
                    onShowQrClick = { navController.navigate(Screen.NewChat.route) }
                )
            }
        }

        composable(
            route = Screen.KeyVerification.route,
            arguments = listOf(navArgument("contactId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { backStackEntry ->
            RevealedOnly {
                KeyVerificationScreen(
                    contactId = backStackEntry.arguments?.getString("contactId"),
                    onBackClick = { navController.popBackStack() }
                )
            }
        }

        composable(Screen.StealthMode.route) {
            RevealedOnly {
                StealthModeScreen(
                    onBackClick = { navController.popBackStack() },
                    onEnableStealth = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }

    if (showRootWarning) {
        AlertDialog(
            onDismissRequest = { showRootWarning = false },
            title = { Text("تعذّر المتابعة") },
            text = {
                Text(
                    "لأسباب أمنية، لا يمكن استخدام هذا التطبيق على جهاز يحتوي على " +
                        "صلاحيات الروت (Root). الروت يكسر العزل الذي يحمي بيانات " +
                        "التطبيقات من بعضها، ويجعل حماية المحادثات المشفّرة غير " +
                        "موثوقة. يرجى إزالة صلاحيات الروت (Unroot) عن الجهاز، أو " +
                        "استخدام جهاز آخر غير مروّت."
                )
            },
            confirmButton = {
                TextButton(onClick = { showRootWarning = false }) {
                    Text("حسناً")
                }
            }
        )
    }
}
