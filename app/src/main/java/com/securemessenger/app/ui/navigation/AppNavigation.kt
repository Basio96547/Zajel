package com.securemessenger.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.securemessenger.app.ui.GlassBottomNavBar
import com.securemessenger.app.ui.GlassNavItem
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
import com.securemessenger.app.ui.viewmodel.ConnectionRequestsViewModel
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
 * Two tiers of navigation motion:
 *  - Disguise moments (calculator/loading/setup) keep the original quiet
 *    150ms crossfade — the reveal/hide of the messenger must never look
 *    like a "special" transition to a shoulder-surfer.
 *  - Everything else is drilling in (open a conversation, contact info,
 *    settings, key verification…) and slides directionally with a parallax
 *    under-layer, Telegram/iOS-style. SlideDirection.Start/End are
 *    layout-direction aware, so the push comes from the correct edge in this
 *    RTL app.
 *
 * There used to be a third tier: a lateral fade-with-scale for "switching
 * between the bottom-nav roots". The bottom nav bar was deleted (it promised
 * tab-switching it never performed — every entry pushed a new screen with its
 * own back stack), but this rule outlived it, so chat list -> contacts, ->
 * settings and -> profile still animated as sideways tab switches while
 * wearing a back arrow and behaving like drill-ins. The motion now says what
 * the navigation actually does.
 */
private val DISGUISE_ROUTES = setOf("calculator", "loading", "setup")

/**
 * The three roots of the revealed app, in bar order.
 *
 * A root is a place: it has no back arrow, it is reached only from the bar,
 * and it is never stacked on top of another root. Everything not in this list
 * is a drill-down that pushes and pops normally.
 */
private val ROOT_ROUTES = listOf(
    Screen.ChatList.route,
    Screen.NewChat.route,
    Screen.Settings.route
)

private val NavEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f) // Material emphasized-decelerate
private const val SLIDE_MS = 350
private const val CROSSFADE_MS = 150

private fun baseRoute(entry: NavBackStackEntry): String =
    entry.destination.route?.substringBefore('/')?.substringBefore('?') ?: ""

private fun AnimatedContentTransitionScope<NavBackStackEntry>.involvesDisguise(): Boolean =
    baseRoute(initialState) in DISGUISE_ROUTES || baseRoute(targetState) in DISGUISE_ROUTES

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

    // Switch to a tab root, the way a tab is supposed to behave: pop back to
    // the home root and put this one on top, reusing the entry that is
    // already there rather than stacking a second copy, and restoring
    // whatever scroll/state that tab had.
    //
    // popUpTo targets ChatList and NOT the graph's start destination, which
    // is the calculator: popping to the real start would tear down the whole
    // revealed section and drop the user back onto the disguise.
    val selectTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Screen.ChatList.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Back on the home tab hides the app; back on another tab returns to the
    // home tab first.
    //
    // Hiding from the chat list is deliberately the only "hide" gesture
    // rather than a visible button: back is the single most unremarkable exit
    // there is — to anyone watching it looks exactly like closing an app,
    // which is exactly what should appear to happen. But it only reads that
    // way from home. Firing it from a tab the user merely wandered into would
    // make settings and contacts into trapdoors that close the messenger, and
    // force the full code + biometric unlock to get back.
    //
    // Drill-down screens (a conversation, contact info, verification…) are
    // untouched: back there navigates up one level, as expected.
    BackHandler(enabled = isRevealed && currentRoute in ROOT_ROUTES) {
        if (currentRoute == Screen.ChatList.route) DisguiseState.hide()
        else selectTab(Screen.ChatList.route)
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

    Box(modifier = Modifier.fillMaxSize()) {

    NavHost(
        navController = navController,
        startDestination = Screen.Calculator.route,
        enterTransition = {
            when {
                involvesDisguise() -> fadeIn(tween(CROSSFADE_MS))
                else -> slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(SLIDE_MS, easing = NavEasing)
                )
            }
        },
        exitTransition = {
            when {
                involvesDisguise() -> fadeOut(tween(CROSSFADE_MS))
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
                    // The empty state's call to action switches tabs rather
                    // than stacking the pairing screen on top of home.
                    onNewChatClick = { selectTab(Screen.NewChat.route) },
                    // Straight to the requests, not via NewChat. The home
                    // screen's pending-request count used to sit on a control
                    // that opened NewChat, one level away from the thing it
                    // was counting.
                    onConnectionRequestsClick = { navController.navigate(Screen.ConnectionRequests.route) }
                )
            }
        }

        composable(Screen.NewChat.route) {
            RevealedOnly {
                NewChatScreen(
                    // A tab root: no back arrow. Back is handled above and
                    // returns to the home tab.
                    onBackClick = null,
                    onContactAdded = { contactId ->
                        // Lands the new conversation on the HOME stack, not on
                        // whatever happened to be underneath the pairing
                        // screen. This used to pop only NewChat itself, so
                        // reaching it from the profile screen and adding
                        // someone left the stack as [home, settings, profile,
                        // conversation] — press back out of your brand-new
                        // chat and you were standing in your own profile.
                        navController.navigate(Screen.Conversation.createRoute(contactId)) {
                            popUpTo(Screen.ChatList.route) { inclusive = false }
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
                    // A tab root: no back arrow, same as the other two.
                    onBackClick = null,
                    onVerificationClick = { navController.navigate(Screen.KeyVerification.createRoute()) },
                    onStealthModeClick = { navController.navigate(Screen.StealthMode.route) },
                    onDataWiped = {
                        navController.navigate(Screen.Setup.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onProfileClick = { navController.navigate(Screen.Profile.route) },
                )
            }
        }

        composable(Screen.Profile.route) {
            RevealedOnly {
                ProfileScreen(
                    onBackClick = { navController.popBackStack() },
                    onVerifyClick = { navController.navigate(Screen.KeyVerification.createRoute()) },
                    // Switches to the tab that owns your code instead of
                    // pushing it on top of the profile — which is how a
                    // conversation opened from here used to end up sitting
                    // above the profile screen in the first place.
                    onShowQrClick = { selectTab(Screen.NewChat.route) }
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

        // The bar floats over the graph rather than insetting it, so the
        // aurora keeps running edge to edge underneath it — the three roots
        // reserve their own bottom room instead. Composed only on a root, and
        // only while revealed: it must never flash over the calculator.
        val rootIndex = ROOT_ROUTES.indexOf(currentRoute)
        if (isRevealed && rootIndex >= 0) {
            val requestsViewModel: ConnectionRequestsViewModel = viewModel()
            val pending by requestsViewModel.incomingRequests.collectAsState()
            GlassBottomNavBar(
                items = listOf(
                    GlassNavItem("المحادثات", Icons.Default.Forum),
                    GlassNavItem("جهات الاتصال", Icons.Default.PersonAdd, badgeCount = pending.size),
                    GlassNavItem("الإعدادات", Icons.Default.Settings)
                ),
                selectedIndex = rootIndex,
                onSelect = { index -> selectTab(ROOT_ROUTES[index]) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            )
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
