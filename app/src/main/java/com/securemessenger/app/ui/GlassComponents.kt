package com.securemessenger.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.securemessenger.app.ui.liquid.LocalLiquid
import com.securemessenger.app.ui.liquid.auroraBackground
import com.securemessenger.app.ui.liquid.liquidSurface
import com.securemessenger.app.ui.theme.LocalMessengerColors

/**
 * Liquid Glass shared building blocks: a soft diagonal gradient backdrop and
 * translucent "frosted" surfaces floating on top of it.
 */

/**
 * The page backdrop every glass screen sits on — now the drifting aurora the
 * home screen was rebuilt around.
 *
 * The signature is unchanged on purpose, and that is the whole point: twelve
 * screens already call this, so re-pointing it at
 * [com.securemessenger.app.ui.liquid.auroraBackground] gave all of them the
 * new backdrop without one of them being edited. [colors] still decides the
 * page's base tone; the moving light on top of it comes from the theme.
 */
fun Modifier.glassBackground(colors: List<Color>): Modifier =
    this.auroraBackground(floor = colors.firstOrNull())

/**
 * The backdrop for onboarding and security moments (Setup, Loading, Stealth
 * Mode). Same aurora, deliberately: those screens used to be the one place
 * with a different backdrop shape, and there was never a reason for the app
 * to change materials the moment it asks you to trust it.
 */
fun Modifier.onboardingBackground(colors: List<Color>): Modifier =
    this.auroraBackground(floor = colors.lastOrNull())

/**
 * The single reusable "glass card" surface — a translucent rounded panel
 * (16-18dp corners) reused across every screen instead of each site
 * repeating its own `.clip(RoundedCornerShape(x)).background(mc.glassCard)`
 * chain. Pass [strong] for the slightly more opaque variant used on
 * emphasized cards (e.g. dialogs, the input bar).
 */
fun Modifier.glassCard(radius: Dp = 16.dp, strong: Boolean = false): Modifier =
    // Forty-seven call sites across the app, and none of them changed: the
    // signature is the same, the material underneath is not. Every card,
    // sheet, dialog and input bar in the app now gets the shadow, the
    // top-edge sheen and the light-catching hairline border that the home
    // screen's rows were rebuilt with, instead of a flat translucent fill.
    this.liquidSurface(
        shape = RoundedCornerShape(radius),
        raised = strong,
        elevation = if (strong) 12.dp else 6.dp
    )

/**
 * The single top bar used by every screen — a floating frosted pill matching
 * [GlassBottomNavBar], so the app frames content between two glass pills
 * instead of each screen hand-rolling its own header (the old state: three
 * different Material TopAppBars plus five ad-hoc Rows, each with its own
 * padding, typography and back-arrow handling).
 *
 * [onBack] renders the RTL-aware [BackIcon]; pass [navigationIcon] instead
 * for a custom leading control (e.g. the selection-mode close button).
 * The slot [title] is for rich titles (avatar + presence line); use the
 * String overload for plain ones.
 */
@Composable
fun GlassTopBar(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    title: @Composable RowScope.() -> Unit
) {
    val mc = LocalMessengerColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(56.dp)
            .liquidSurface(shape = RoundedCornerShape(26.dp), raised = true, elevation = 16.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides mc.glassOnCard) {
            when {
                navigationIcon != null -> navigationIcon()
                onBack != null -> IconButton(onClick = onBack) { BackIcon() }
                else -> Spacer(Modifier.width(10.dp))
            }
            title()
            actions?.invoke(this)
        }
    }
}

/** Plain-text convenience overload of [GlassTopBar]. */
@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    val mc = LocalMessengerColors.current
    GlassTopBar(modifier = modifier, onBack = onBack, actions = actions) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = mc.glassOnCard,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

data class GlassNavItem(
    val label: String,
    val icon: ImageVector,
    /** e.g. pending connection-request count on "جهات الاتصال" — 0 shows no badge at all. */
    val badgeCount: Int = 0
)

/**
 * Floating frosted-pill bottom navigation bar — Telegram-style, glassy, with
 * a sliding highlight and an icon/label tint crossfade so a tab switch
 * itself visibly animates rather than hard-cutting. Pass [selectedIndex] =
 * -1 for drill-down screens that don't correspond to any single tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val mc = LocalMessengerColors.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            // Fixed height is required, not cosmetic — without it the
            // indicator's fillMaxHeight() below resolves against the
            // unbounded height Scaffold's bottomBar slot can pass down,
            // which made the whole bar balloon to cover the entire screen.
            .height(64.dp)
            .liquidSurface(shape = RoundedCornerShape(26.dp), raised = true, elevation = 16.dp)
            .padding(vertical = 8.dp, horizontal = 6.dp)
    ) {
        val itemWidth = maxWidth / items.size
        if (selectedIndex in items.indices) {
            val indicatorOffset by animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label = "navIndicatorOffset"
            )
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val tint by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary else mc.glassOnCard.copy(alpha = 0.55f),
                    label = "navTint"
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 4.dp)
                ) {
                    BadgedBox(badge = {
                        if (item.badgeCount > 0) {
                            Badge { Text(if (item.badgeCount > 99) "99+" else item.badgeCount.toString()) }
                        }
                    }) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = tint,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
