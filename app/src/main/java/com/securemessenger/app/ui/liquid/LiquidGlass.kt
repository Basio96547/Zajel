package com.securemessenger.app.ui.liquid

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The home screen's visual layer: a living aurora backdrop and the
 * translucent surfaces that float above it.
 *
 * **Deliberately its own package.** `com.securemessenger.app.ui`'s
 * `GlassComponents` is shared by every screen in the app, and this redesign
 * was scoped to the home screen only — so nothing here touches it. Only
 * `ChatListScreen` and `ChatListItem` import this file; reverting the
 * redesign is reverting three files and no others.
 *
 * **Why there is no true backdrop blur.** Frosted glass, in a design tool,
 * means blurring whatever sits behind the panel. Compose at this project's
 * version has no backdrop-blur API, and `Modifier.blur` is the wrong tool
 * twice over: it blurs a composable's *own* content, and it is a silent no-op
 * below API 31 while this app ships `minSdk 26`. A look that quietly vanishes
 * on part of the supported range is not a look.
 *
 * So the depth here is built from what renders identically on every device
 * the app supports:
 *
 *  - a backdrop that is *already* soft — wide radial falloffs, so a
 *    translucent panel over it reads as frosted without any blur pass;
 *  - a fill with a vertical sheen, brightest at the top edge, the way light
 *    actually falls on a pane;
 *  - a hairline border that is bright where the light hits and nearly gone
 *    at the bottom, which is what separates "glass" from "grey rectangle";
 *  - a real elevation shadow, so panels sit *above* the page rather than in
 *    it;
 *  - parallax: the backdrop drifts against the content scrolling over it,
 *    which is the one depth cue no amount of static styling can fake.
 */

@Immutable
data class LiquidPalette(
    /** The page floor the aurora is painted onto. */
    val floor: Color,
    /** Three drifting light sources. Order is back-to-front. */
    val blobs: List<Color>,
    val surface: Color,
    val surfaceRaised: Color,
    /** Top-edge highlight painted over a surface's fill. */
    val sheen: Color,
    val edgeHigh: Color,
    val edgeLow: Color,
    val onSurface: Color,
    val muted: Color,
    val shadow: Color,
)

val LocalLiquid = compositionLocalOf<LiquidPalette> {
    error("Liquid surfaces used outside LiquidTheme — wrap the screen in LiquidTheme { }")
}

/**
 * Derives the palette from the app's own Material scheme rather than
 * hard-coding a second brand: the aurora is literally the app's primary and
 * secondary colours out of focus, so the redesign cannot drift away from the
 * theme the rest of the app uses.
 */
@Composable
private fun rememberLiquidPalette(dark: Boolean): LiquidPalette {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    return remember(dark, primary, secondary) {
        if (dark) {
            LiquidPalette(
                floor = Color(0xFF06080D),
                blobs = listOf(
                    primary.copy(alpha = 0.34f),
                    secondary.copy(alpha = 0.30f),
                    Color(0xFF1D9E75).copy(alpha = 0.18f),
                ),
                surface = Color(0x14FFFFFF),
                surfaceRaised = Color(0x24FFFFFF),
                sheen = Color(0x2EFFFFFF),
                edgeHigh = Color(0x40FFFFFF),
                edgeLow = Color(0x0AFFFFFF),
                onSurface = Color(0xFFF4F6FB),
                muted = Color(0xFF98A1B4),
                shadow = Color(0xFF000000),
            )
        } else {
            LiquidPalette(
                floor = Color(0xFFEDF1F8),
                blobs = listOf(
                    primary.copy(alpha = 0.28f),
                    secondary.copy(alpha = 0.24f),
                    Color(0xFFD4537E).copy(alpha = 0.14f),
                ),
                surface = Color(0xC7FFFFFF),
                surfaceRaised = Color(0xE8FFFFFF),
                sheen = Color(0x99FFFFFF),
                edgeHigh = Color(0xD9FFFFFF),
                edgeLow = Color(0x12000000),
                onSurface = Color(0xFF131822),
                muted = Color(0xFF5B6373),
                shadow = Color(0xFF64748B),
            )
        }
    }
}

/**
 * Follows the Material scheme actually in force, not the system setting.
 *
 * The first version defaulted to `isSystemInDarkTheme()`, which is the same
 * answer only as long as nothing overrides the theme. The moment anything
 * does — a preview, a screenshot test, a future in-app theme switch — the
 * glass layer kept the system's answer while Material components used the
 * overridden one, and the two disagreed on screen. It was visible as a
 * full-width white slab (the connection bar, a Material surface) sitting in
 * the middle of an otherwise dark screen.
 *
 * Reading the background's luminance instead means this layer cannot disagree
 * with the scheme it is drawn on top of, whatever decided that scheme.
 */
@Composable
fun LiquidTheme(
    dark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalLiquid provides rememberLiquidPalette(dark), content = content)
}

/**
 * The drifting backdrop.
 *
 * [parallaxPx] is a lambda, not a value, on purpose: called from inside the
 * draw scope it keeps a scrolling list from recomposing this composable on
 * every frame — the scroll offset only ever invalidates the draw phase.
 *
 * The three blobs move on 17s, 23s and 29s cycles. Coprime durations mean the
 * combined pattern does not visibly repeat, so the background never develops
 * a "loop" a user can notice.
 */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier, parallaxPx: () -> Float = { 0f }) {
    val palette = LocalLiquid.current
    val drift = rememberInfiniteTransition(label = "aurora")
    val a by drift.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(17_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "auroraA"
    )
    val b by drift.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(23_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "auroraB"
    )
    val c by drift.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(29_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "auroraC"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(palette.floor)
        // Nearer layers shift more than far ones, which is what makes the
        // page read as having actual distance behind it.
        val shift = parallaxPx()
        blob(
            palette.blobs[0],
            Offset(size.width * (0.16f + 0.26f * a), size.height * (0.06f + 0.10f * b) - shift * 0.22f),
            size.minDimension * 1.15f
        )
        blob(
            palette.blobs[1],
            Offset(size.width * (0.92f - 0.24f * b), size.height * (0.30f + 0.14f * c) - shift * 0.14f),
            size.minDimension * 1.00f
        )
        blob(
            palette.blobs[2],
            Offset(size.width * (0.40f + 0.30f * c), size.height * (0.86f - 0.12f * a) - shift * 0.08f),
            size.minDimension * 0.90f
        )
    }
}

private fun DrawScope.blob(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/**
 * One pane of glass: shadow underneath, translucent fill, sheen down from the
 * top edge, hairline border bright where the light lands.
 *
 * The order matters and is not arbitrary — shadow must come before `clip` to
 * draw outside the bounds, and the sheen after the fill to sit on top of it.
 */
fun Modifier.liquidSurface(
    shape: Shape = RoundedCornerShape(22.dp),
    raised: Boolean = false,
    elevation: Dp = 12.dp,
    fill: Color? = null,
): Modifier = composed {
    val palette = LocalLiquid.current
    this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = palette.shadow,
            spotColor = palette.shadow
        )
        .clip(shape)
        .background(fill ?: if (raised) palette.surfaceRaised else palette.surface)
        .background(
            Brush.verticalGradient(
                0.0f to palette.sheen,
                0.55f to Color.Transparent,
                1.0f to Color.Transparent
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(listOf(palette.edgeHigh, palette.edgeLow)),
            shape = shape
        )
}

/** A soft coloured halo cast *outside* a shape — used for focus and for unread counts. */
fun Modifier.liquidGlow(color: Color, radius: Dp = 18.dp, alpha: Float = 0.45f): Modifier = drawBehind {
    val spread = radius.toPx()
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = center,
            radius = size.minDimension / 2f + spread
        ),
        radius = size.minDimension / 2f + spread,
        center = center
    )
}

/**
 * Press feedback as a spring rather than a ripple. Glass does not ripple; it
 * takes the pressure and comes back.
 */
@Composable
fun rememberPressScale(
    source: MutableInteractionSource,
    pressedScale: Float = 0.972f
): State<Float> {
    val pressed by source.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "liquidPressScale"
    )
}

data class LiquidNavItem(
    val label: String,
    val icon: ImageVector,
    /** 0 shows no badge at all. */
    val badgeCount: Int = 0,
)

/**
 * The floating navigation pill.
 *
 * A local copy rather than a change to the shared `GlassBottomNavBar`: that
 * one is on other screens which this redesign was told to leave alone. The
 * fixed height is load-bearing for the same reason it is in the shared one —
 * `fillMaxHeight` on the moving indicator resolves against an unbounded
 * constraint from Scaffold's bottom slot otherwise, and the bar swallows the
 * screen.
 */
@Composable
fun LiquidNavBar(
    items: List<LiquidNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLiquid.current
    val primary = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
            .liquidSurface(shape = RoundedCornerShape(28.dp), raised = true, elevation = 20.dp)
            .padding(vertical = 7.dp, horizontal = 6.dp)
    ) {
        val itemWidth = maxWidth / items.size
        if (selectedIndex in items.indices) {
            val indicatorOffset by androidx.compose.animation.core.animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "liquidNavIndicator"
            )
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(3.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(primary.copy(alpha = 0.30f), primary.copy(alpha = 0.14f))
                        )
                    )
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val tint by androidx.compose.animation.animateColorAsState(
                    targetValue = if (selected) primary else palette.onSurface.copy(alpha = 0.55f),
                    label = "liquidNavTint"
                )
                val lift by animateFloatAsState(
                    targetValue = if (selected) -2f else 0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "liquidNavLift"
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSelect(index) }
                        .padding(vertical = 5.dp)
                        .graphicsLayer { translationY = lift }
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = tint,
                            modifier = Modifier.size(23.dp)
                        )
                        if (item.badgeCount > 0) {
                            Box(
                                modifier = Modifier
                                    .offset(x = 7.dp, y = (-5).dp)
                                    .size(9.dp)
                                    .liquidGlow(MaterialTheme.colorScheme.error, radius = 5.dp, alpha = 0.7f)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error)
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
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
