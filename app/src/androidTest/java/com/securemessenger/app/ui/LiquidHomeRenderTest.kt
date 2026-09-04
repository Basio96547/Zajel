package com.securemessenger.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.securemessenger.app.ui.liquid.AuroraBackdrop
import com.securemessenger.app.ui.liquid.LiquidTheme
import com.securemessenger.app.ui.liquid.liquidGlow
import com.securemessenger.app.ui.liquid.liquidSurface
import com.securemessenger.app.ui.screens.chat.ChatListContent
import com.securemessenger.app.ui.screens.chat.ChatListItem
import com.securemessenger.app.ui.theme.MessengerTheme
import com.securemessenger.app.ui.viewmodel.ContactUiModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The redesigned home screen, rendered on a real device.
 *
 * Compiling a Compose screen proves almost nothing about it: the ways this
 * particular redesign can fail are all runtime ones. [com.securemessenger.app.ui.liquid.LocalLiquid]
 * throws by design when a surface is used outside `LiquidTheme`, the aurora
 * runs three infinite animations against a Canvas, and the rows drive a
 * `graphicsLayer` from an `Animatable` — none of which a compiler looks at.
 *
 * So this asserts the one thing worth asserting at this layer: every new
 * composable actually composes, measures and draws on a device, in both
 * themes, and a row still reports the click the whole screen exists to
 * deliver.
 *
 * It is deliberately not a screenshot test. Nothing here pins pixels, so a
 * deliberate design change does not have to fight a golden image; what would
 * break this is the screen failing to render at all.
 */
@RunWith(AndroidJUnit4::class)
class LiquidHomeRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val unreadContact = ContactUiModel(
        id = "contact-unread",
        displayName = "سارة",
        isVerified = true,
        lastMessage = "وصلتك الصورة؟",
        lastTimestamp = System.currentTimeMillis(),
        unreadCount = 3
    )

    private val pinnedContact = ContactUiModel(
        id = "contact-pinned",
        displayName = "أحمد",
        isVerified = false,
        lastMessage = "تمام، شكراً لك",
        lastTimestamp = System.currentTimeMillis(),
        unreadCount = 0,
        lastIsMine = true,
        lastIsRead = true,
        pinnedAt = System.currentTimeMillis()
    )

    @Test
    fun chatRowRendersItsNamePreviewAndUnreadCount_inDark() {
        compose.setContent {
            MessengerTheme(darkTheme = true) {
                LiquidTheme(dark = true) {
                    ChatListItem(contact = unreadContact, onClick = {})
                }
            }
        }

        compose.onNodeWithText("سارة").assertIsDisplayed()
        compose.onNodeWithText("وصلتك الصورة؟").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun chatRowRenders_inLight() {
        compose.setContent {
            MessengerTheme(darkTheme = false) {
                LiquidTheme(dark = false) {
                    ChatListItem(contact = pinnedContact, onClick = {})
                }
            }
        }

        compose.onNodeWithText("أحمد").assertIsDisplayed()
        compose.onNodeWithText("تمام، شكراً لك").assertIsDisplayed()
    }

    @Test
    fun aRowStillReportsTheClickTheScreenExistsToDeliver() {
        // The redesign swapped Material's ripple for a spring, which meant
        // rebuilding the clickable by hand — exactly the kind of change that
        // silently loses an onClick.
        var clickedId: String? = null
        compose.setContent {
            MessengerTheme(darkTheme = true) {
                LiquidTheme(dark = true) {
                    ChatListItem(contact = unreadContact, onClick = { clickedId = unreadContact.id })
                }
            }
        }

        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.waitForIdle()

        assertTrue("row click did not reach onClick", clickedId == unreadContact.id)
    }

    @Test
    fun theEntranceAnimationLeavesTheRowVisible() {
        // The row enters from alpha 0 driven by an Animatable. A stagger delay
        // that never resolved would leave a permanently invisible list, and
        // nothing about that fails to compile.
        compose.setContent {
            MessengerTheme(darkTheme = true) {
                LiquidTheme(dark = true) {
                    ChatListItem(contact = unreadContact, onClick = {}, entranceDelayMillis = 45)
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("سارة").assertIsDisplayed()
    }

    @Test
    fun theAuroraAndItsSurfacesDrawWithoutFailing() {
        // Three infinite animations over a Canvas, plus the shadow/sheen/border
        // stack and the outside-the-shape glow. This test passing means they
        // measured and drew; it crashing is the failure it is here to catch.
        compose.setContent {
            MessengerTheme(darkTheme = true) {
                LiquidTheme(dark = true) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AuroraBackdrop(parallaxPx = { 120f })
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .liquidGlow(androidx.compose.ui.graphics.Color.Blue)
                                .liquidSurface(raised = true)
                        ) {
                            Text("زجاج")
                        }
                    }
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("زجاج").assertIsDisplayed()
    }

    @Test
    fun theHomeScreenNoLongerOwnsAnyRootDestination() {
        // This test used to assert the opposite, and the reversal is the
        // point. Settings and profile had been moved into the home header
        // because the bottom bar was deleted; the bar is back with real tab
        // behaviour, so a root destination is reached from the bar and from
        // nowhere else. Two controls for one destination is exactly what got
        // the bar deleted the first time, and it would be no better with the
        // header playing the bar's old part.
        //
        // What home still owns is what belongs to home: opening a
        // conversation, and the pending-requests banner that links to the
        // screen its own count refers to.
        var opened: String? = null
        compose.setContent {
            MessengerTheme(darkTheme = true) {
                ChatListContent(
                    contacts = listOf(unreadContact),
                    isLoading = false,
                    pendingRequestCount = 2,
                    onConversationClick = { opened = "conversation" },
                    onTogglePin = {},
                    onNewChatClick = { opened = "newChat" },
                    onConnectionRequestsClick = { opened = "requests" }
                )
            }
        }

        compose.onAllNodesWithContentDescription("الإعدادات").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("ملفي").assertCountEquals(0)
        // The floating "محادثة جديدة" button went with them: its destination
        // is the contacts tab, one row below where it used to sit.
        compose.onAllNodesWithContentDescription("محادثة جديدة").assertCountEquals(0)

        compose.onNodeWithText("طلبا تواصل بانتظارك").performClick()
        compose.waitForIdle()
        assertTrue("requests banner opened $opened", opened == "requests")
    }

    @Test
    fun thePendingRequestsBannerIsAbsentWhenThereAreNone() {
        compose.setContent {
            MessengerTheme(darkTheme = true) {
                ChatListContent(
                    contacts = listOf(unreadContact),
                    isLoading = false,
                    pendingRequestCount = 0,
                    onConversationClick = {},
                    onTogglePin = {},
                    onNewChatClick = {},
                    onConnectionRequestsClick = {}
                )
            }
        }

        compose.onAllNodesWithText("بانتظارك", substring = true).assertCountEquals(0)
    }
}
