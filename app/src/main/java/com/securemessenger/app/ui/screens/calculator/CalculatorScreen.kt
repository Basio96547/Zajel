package com.securemessenger.app.ui.screens.calculator

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.securemessenger.app.security.AppSettings
import java.text.DecimalFormat

/**
 * Colors follow the device's own light/dark setting instead of being fixed —
 * one more thing that makes this look like an ordinary, actively-maintained
 * calculator app rather than a static disguise. Deliberately does NOT reuse
 * the real messenger's brand palette wholesale — the calculator must read as
 * an unrelated, ordinary app, not a reskin of it.
 */
private data class CalcPalette(
    val screenBg: Brush,
    val display: Color,
    val expression: Color,
    val topKey: Color,
    val topKeyText: Color,
    val digitKey: Color,
    val digitText: Color,
    val operatorKey: Color,
    val equalsKey: Color,
    val keyText: Color
)

private fun calcPalette(dark: Boolean): CalcPalette = if (dark) {
    CalcPalette(
        screenBg = Brush.linearGradient(listOf(Color(0xFF0C0E13), Color(0xFF0C0E13))),
        display = Color(0xFFF2F4F8),
        expression = Color(0xFF6B7180),
        topKey = Color(0x17FFFFFF),
        topKeyText = Color(0xFFA7ADBB),
        digitKey = Color(0x0FFFFFFF),
        digitText = Color(0xFFEEF0F4),
        operatorKey = Color(0xE62AABEE),
        equalsKey = Color(0xFF2AABEE),
        keyText = Color.White
    )
} else {
    CalcPalette(
        screenBg = Brush.linearGradient(listOf(Color(0xFFEEF1F5), Color(0xFFE7ECF2))),
        display = Color(0xFF1A1D24),
        expression = Color(0xFF9AA0AC),
        topKey = Color(0xD9FFFFFF),
        topKeyText = Color(0xFF5A6070),
        digitKey = Color(0xB3FFFFFF),
        digitText = Color(0xFF1A1D24),
        operatorKey = Color(0xFF2AABEE),
        equalsKey = Color(0xFF178ACB),
        keyText = Color.White
    )
}

private enum class Op(val symbol: String) { ADD("+"), SUB("−"), MUL("×"), DIV("÷") }

/**
 * A real, fully-working calculator — the app's actual disguise. Typing the
 * secret access code (Settings → الخصوصية) as a plain number and pressing
 * "=" opens the hidden messenger instead of showing a result; anyone else
 * just sees an ordinary calculator.
 */
@Composable
fun CalculatorScreen(onUnlock: () -> Unit, onDuress: () -> Unit = {}) {
    val context = LocalContext.current
    val palette = calcPalette(isSystemInDarkTheme())
    var display by remember { mutableStateOf("0") }
    var expression by remember { mutableStateOf("") }
    var firstOperand by remember { mutableStateOf<Double?>(null) }
    var pendingOp by remember { mutableStateOf<Op?>(null) }
    var currentInput by remember { mutableStateOf("0") }
    var justEvaluated by remember { mutableStateOf(false) }
    val formatter = remember { DecimalFormat("#,##0.##########") }
    // Invisible brute-force throttle for the secret code. Code entry is always
    // "type a bare number, press =", never using an operator, so genuine
    // calculator use (which resets this in onOperator) never trips it; only a
    // run of bare-number "=" presses — the shape of code-guessing — does. After
    // too many misses, code checks pause briefly while the calculator keeps
    // working normally (so nothing visibly "locks").
    var codeMissStreak by remember { mutableStateOf(0) }
    var codeCheckLockedUntil by remember { mutableStateOf(0L) }

    fun applyOp(op: Op, a: Double, b: Double): Double = when (op) {
        Op.ADD -> a + b
        Op.SUB -> a - b
        Op.MUL -> a * b
        Op.DIV -> if (b == 0.0) Double.NaN else a / b
    }

    fun reset() {
        display = "0"
        expression = ""
        firstOperand = null
        pendingOp = null
        currentInput = "0"
        justEvaluated = false
    }

    fun onDigit(d: String) {
        if (justEvaluated) {
            currentInput = "0"
            firstOperand = null
            pendingOp = null
            expression = ""
            justEvaluated = false
        }
        currentInput = if (currentInput == "0") d else currentInput + d
        display = currentInput
    }

    fun onDot() {
        if (justEvaluated) {
            currentInput = "0"
            justEvaluated = false
        }
        if (!currentInput.contains(".")) {
            currentInput += "."
            display = currentInput
        }
    }

    fun onOperator(op: Op) {
        // Using an operator is a clear signal of genuine calculator use, not
        // code-guessing — clear the miss streak.
        codeMissStreak = 0
        val value = currentInput.toDoubleOrNull() ?: 0.0
        firstOperand = if (firstOperand == null) value else {
            val result = applyOp(pendingOp ?: op, firstOperand!!, value)
            display = formatter.format(result)
            result
        }
        pendingOp = op
        // Small muted line above the result, digit-spaced like the reference
        // design — a running readout of "<previous operand> <operator>".
        expression = formatter.format(firstOperand ?: value).toCharArray().joinToString(" ") + " " + op.symbol
        currentInput = "0"
        justEvaluated = false
    }

    fun onEquals() {
        // Secret gate: a plain number (no operator used) matching the access
        // code opens the messenger instead of "calculating" it. Codes are
        // verified against a salted hash in constant time (never compared as
        // plaintext), and repeated misses throttle further checks.
        if (pendingOp == null && firstOperand == null && currentInput.length >= 3) {
            val now = System.currentTimeMillis()
            if (now >= codeCheckLockedUntil) {
                if (!AppSettings.hasAccessCode(context)) {
                    // Bootstrap: no code has ever been chosen yet (fresh
                    // install) — there is nothing to check the input against,
                    // and nothing sensitive exists yet either (no account, no
                    // keys, no messages: Setup is what creates all of that).
                    // Any plain number proceeds straight in, landing on Setup,
                    // where choosing a real access code is mandatory before
                    // anything sensitive is ever created. Without this special
                    // case, a fresh install could never get past this screen
                    // at all: onUnlock() is only ever called from here, and
                    // verifyAccessCode()/verifyDuressCode() both always return
                    // false when no code is stored yet — a dead end with no
                    // way in whatsoever.
                    codeMissStreak = 0
                    reset()
                    onUnlock()
                    return
                }
                // Duress takes effect only when it does NOT also match the real
                // access code — safer to fail toward "just unlocks" than toward
                // "silently wipes everything" if the two ever collide.
                val duressMatch = AppSettings.verifyDuressCode(context, currentInput)
                val accessMatch = AppSettings.verifyAccessCode(context, currentInput)
                if (duressMatch && !accessMatch) {
                    codeMissStreak = 0
                    reset()
                    onDuress()
                    return
                }
                if (accessMatch) {
                    codeMissStreak = 0
                    reset()
                    onUnlock()
                    return
                }
                codeMissStreak++
                if (codeMissStreak >= 5) {
                    codeCheckLockedUntil = now + 30_000L
                    codeMissStreak = 0
                }
            }
        }
        val value = currentInput.toDoubleOrNull() ?: 0.0
        val op = pendingOp
        val result = if (op != null && firstOperand != null) applyOp(op, firstOperand!!, value) else value
        display = if (result.isNaN()) "خطأ" else formatter.format(result)
        firstOperand = null
        pendingOp = null
        expression = ""
        currentInput = if (result.isNaN()) "0" else formatter.format(result)
        justEvaluated = true
    }

    fun onPercent() {
        val value = (currentInput.toDoubleOrNull() ?: 0.0) / 100.0
        currentInput = formatter.format(value)
        display = currentInput
    }

    fun onSign() {
        val value = (currentInput.toDoubleOrNull() ?: 0.0) * -1
        currentInput = formatter.format(value)
        display = currentInput
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.screenBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.BottomEnd
        ) {
            Column(horizontalAlignment = Alignment.End) {
                if (expression.isNotBlank()) {
                    Text(
                        text = expression,
                        color = palette.expression,
                        fontSize = 15.sp(),
                        textAlign = TextAlign.End,
                        maxLines = 1
                    )
                }
                Text(
                    text = display,
                    color = palette.display,
                    fontSize = 52.sp(),
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.End,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        val rows = listOf(
            listOf(
                CalcButton("C", palette.topKey, palette.topKeyText) { reset() },
                CalcButton("±", palette.topKey, palette.topKeyText) { onSign() },
                CalcButton("%", palette.topKey, palette.topKeyText) { onPercent() },
                CalcButton("÷", palette.operatorKey, palette.keyText) { onOperator(Op.DIV) }
            ),
            listOf(
                CalcButton("7", palette.digitKey, palette.digitText) { onDigit("7") },
                CalcButton("8", palette.digitKey, palette.digitText) { onDigit("8") },
                CalcButton("9", palette.digitKey, palette.digitText) { onDigit("9") },
                CalcButton("×", palette.operatorKey, palette.keyText) { onOperator(Op.MUL) }
            ),
            listOf(
                CalcButton("4", palette.digitKey, palette.digitText) { onDigit("4") },
                CalcButton("5", palette.digitKey, palette.digitText) { onDigit("5") },
                CalcButton("6", palette.digitKey, palette.digitText) { onDigit("6") },
                CalcButton("−", palette.operatorKey, palette.keyText) { onOperator(Op.SUB) }
            ),
            listOf(
                CalcButton("1", palette.digitKey, palette.digitText) { onDigit("1") },
                CalcButton("2", palette.digitKey, palette.digitText) { onDigit("2") },
                CalcButton("3", palette.digitKey, palette.digitText) { onDigit("3") },
                CalcButton("+", palette.operatorKey, palette.keyText) { onOperator(Op.ADD) }
            ),
            listOf(
                CalcButton("0", palette.digitKey, palette.digitText) { onDigit("0") },
                CalcButton(".", palette.digitKey, palette.digitText) { onDot() },
                CalcButton("=", palette.equalsKey, palette.keyText) { onEquals() }
            )
        )

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { btn ->
                    val wide = row.size == 3 && btn.label == "0"
                    CalcKey(btn, wide)
                }
            }
        }
    }
}

// CalculatorScreen reads isSystemInDarkTheme() directly (it deliberately does
// NOT use the messenger's own brand theme — see the palette doc comment
// above), so the dark/light variants below are forced via uiMode rather than
// MessengerTheme's darkTheme param.
@Preview(name = "Calculator — dark", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CalculatorScreenDarkPreview() {
    CalculatorScreen(onUnlock = {}, onDuress = {})
}

@Preview(name = "Calculator — light", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun CalculatorScreenLightPreview() {
    CalculatorScreen(onUnlock = {}, onDuress = {})
}

/** A single circular key: a light press-scale plus a haptic tick, like a real device's number pad. */
@Composable
private fun RowScope.CalcKey(btn: CalcButton, wide: Boolean) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.94f else 1f, label = "calcKeyScale")
    val haptics = LocalHapticFeedback.current
    val shape = if (wide) RoundedCornerShape(32.dp) else CircleShape

    Box(
        modifier = Modifier
            .weight(if (wide) 2f else 1f)
            .aspectRatio(if (wide) 2.3f else 1f)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(shape)
            .background(btn.color)
            .clickable(interactionSource = interactionSource, indication = null) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                btn.onClick()
            },
        contentAlignment = if (wide) Alignment.CenterStart else Alignment.Center
    ) {
        Text(
            btn.label,
            color = btn.textColor,
            fontSize = if (btn.label.length <= 1 && btn.label[0].isDigit().not() && btn.label != ".") 20.sp() else 22.sp(),
            fontWeight = FontWeight.Normal,
            modifier = if (wide) Modifier.padding(start = 26.dp) else Modifier
        )
    }
}

private data class CalcButton(val label: String, val color: Color, val textColor: Color, val onClick: () -> Unit)

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
