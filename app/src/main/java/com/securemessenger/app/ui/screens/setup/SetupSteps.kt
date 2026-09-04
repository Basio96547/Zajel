package com.securemessenger.app.ui.screens.setup

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.securemessenger.app.security.AppSettings
import com.securemessenger.app.ui.glassCard
import com.securemessenger.app.ui.screens.chat.decodeSampledBitmap
import com.securemessenger.app.ui.theme.LocalMessengerColors
import com.securemessenger.app.ui.theme.MessengerTheme
import com.securemessenger.app.ui.theme.SemanticColors

/** The three [SetupScreen] steps, plus their small supporting composables. */

@Composable
fun WelcomeStep(onContinue: () -> Unit) {
    val mc = LocalMessengerColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, SemanticColors.purple))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Security, contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
        }

        Text(
            text = "تشفير من الطرف للطرف",
            style = MaterialTheme.typography.headlineSmall,
            color = mc.glassOnCard,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Text(
            text = "رسائلك محميّة ببروتوكول Double Ratchet المدعوم بتشفير ما بعد الكم ML-KEM-768. لا أحد — ولا حتى نحن — يقدر يقرأها.",
            style = MaterialTheme.typography.bodyMedium,
            color = mc.receivedMeta,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.15f
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("ابدأ", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SetupField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    letterSpaced: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    val mc = LocalMessengerColors.current
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = mc.receivedMeta)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassCard(radius = 14.dp, strong = true)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (prefix != null) {
                    Text(prefix, style = MaterialTheme.typography.bodyLarge, color = mc.glassOnCard.copy(alpha = 0.5f))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    keyboardOptions = keyboardOptions,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = mc.glassOnCard,
                        letterSpacing = if (letterSpaced) 6.sp() else 0.sp(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        textDirection = androidx.compose.ui.text.style.TextDirection.Ltr
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (isError && supportingText != null) {
            Spacer(Modifier.height(4.dp))
            Text(supportingText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        } else if (supportingText != null) {
            Spacer(Modifier.height(4.dp))
            Text(supportingText, style = MaterialTheme.typography.labelSmall, color = mc.receivedMeta)
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

@Composable
fun ProfileStep(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    accessCode: String,
    onAccessCodeChange: (String) -> Unit,
    avatarBytes: ByteArray?,
    onPickAvatar: () -> Unit,
    onNext: () -> Unit
) {
    // No directory to check against anymore — just a local format check.
    // Nothing to reserve, so nothing can ever be "taken."
    val isValidFormat = username.isBlank() || USERNAME_REGEX.matches(username)
    val isValidCode = AppSettings.isTypeableCode(accessCode)
    val mc = LocalMessengerColors.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "ملفك الشخصي",
            style = MaterialTheme.typography.titleLarge,
            color = mc.glassOnCard
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "تُخزَّن كل البيانات على جهازك فقط.",
            style = MaterialTheme.typography.bodyMedium,
            color = mc.receivedMeta
        )

        Spacer(modifier = Modifier.height(24.dp))

        // This circle used to be `.clickable { }` — an empty lambda under a
        // camera glyph labelled "إضافة صورة". It looked like the first thing
        // you do in this app, and it did nothing at all. It now opens the
        // picker and shows what you chose; SetupScreen holds the bytes until
        // step 3, where the profile it belongs to actually gets created.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // Bounded decode, like every other image path in this app — a
            // gallery pick can easily be a 12-megapixel photo.
            val preview = remember(avatarBytes) {
                avatarBytes?.let { decodeSampledBitmap(it, maxDimension = 300)?.asImageBitmap() }
            }
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(mc.glassCard)
                    .border(1.dp, mc.receivedMeta.copy(alpha = 0.4f), CircleShape)
                    .clickable(onClick = onPickAvatar),
                contentAlignment = Alignment.Center
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = "الصورة المختارة",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.Camera, contentDescription = "إضافة صورة", tint = mc.receivedMeta, modifier = Modifier.size(26.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        SetupField(
            label = "الاسم المعروض",
            value = displayName,
            onValueChange = onDisplayNameChange,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        Spacer(modifier = Modifier.height(16.dp))

        SetupField(
            label = "اسم المستخدم",
            value = username,
            onValueChange = onUsernameChange,
            prefix = "@",
            isError = !isValidFormat,
            supportingText = "أحرف إنجليزية صغيرة وأرقام و _ (3-20) — يظهر فقط لمن تُقرن معهم شخصياً",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
        )

        Spacer(modifier = Modifier.height(16.dp))

        SetupField(
            label = "رمز فتح المراسل",
            value = accessCode,
            onValueChange = onAccessCodeChange,
            isError = accessCode.isNotEmpty() && !isValidCode,
            supportingText = if (accessCode.startsWith("0"))
                "لا يبدأ بصفر — الحاسبة لا تقبل صفراً في أول الرقم، فلن تستطيع كتابته لاحقاً."
            else
                "رقم من 4 إلى 10 خانات، لا يبدأ بصفر — تكتبه في الحاسبة ثم = لفتح المراسل. لا يوجد رمز افتراضي، فاحفظه جيداً.",
            letterSpaced = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            )
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onNext,
            enabled = displayName.isNotBlank() && username.isNotBlank() && isValidFormat && isValidCode,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("التالي")
        }
    }
}

@Composable
fun GeneratingStep(
    isGenerating: Boolean,
    onComplete: () -> Unit
) {
    val mc = LocalMessengerColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        if (isGenerating) {
            KeyGenerationHelix()

            Spacer(modifier = Modifier.height(26.dp))

            Text(
                text = "جارٍ توليد مفاتيحك",
                style = MaterialTheme.typography.titleMedium,
                color = mc.glassOnCard
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Double Ratchet + ML-KEM-768\nيتم إنشاء أزواج المفاتيح على جهازك…",
                style = MaterialTheme.typography.bodyMedium,
                color = mc.receivedMeta,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.2f
            )

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .width(150.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(mc.glassOnCard.copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.75f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, SemanticColors.purple)))
                )
            }
        } else {
            Button(onClick = onComplete) {
                Text("بدء إنشاء المفاتيح")
            }
        }
    }
}

/**
 * The "two strands crossing" motif — the Double Ratchet root key mixed with
 * the ML-KEM-768 hybrid secret rendered as two curves crossing at the
 * center, exactly matching the approved reference illustration's geometry
 * (a gentle continuous rotation is the only liberty taken, to read as
 * "generating" rather than a static diagram).
 */
@Composable
private fun KeyGenerationHelix(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "helix")
    val angle by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(animation = tween(1600, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "helixAngle"
    )
    val strandA = MaterialTheme.colorScheme.primary
    val strandB = SemanticColors.purple
    val centerDot = MaterialTheme.colorScheme.onBackground
    val strokePx = with(androidx.compose.ui.platform.LocalDensity.current) { 5.dp.toPx() }

    Canvas(
        modifier = modifier
            .size(150.dp)
            .graphicsLayerRotation(angle)
    ) {
        // Reference viewBox is 140x140 — scale every coordinate by size/140.
        val s = size.width / 140f
        fun p(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * s, y * s)

        val pathA = Path().apply {
            moveTo(35f * s, 15f * s)
            cubicTo(95f * s, 45f * s, 45f * s, 95f * s, 105f * s, 125f * s)
        }
        val pathB = Path().apply {
            moveTo(105f * s, 15f * s)
            cubicTo(45f * s, 45f * s, 95f * s, 95f * s, 35f * s, 125f * s)
        }
        drawPath(pathA, color = strandA, style = Stroke(width = strokePx, cap = StrokeCap.Round))
        drawPath(pathB, color = strandB, style = Stroke(width = strokePx, cap = StrokeCap.Round))

        drawCircle(color = centerDot, radius = 7f * s, center = p(70f, 70f))
        drawCircle(color = strandA, radius = 5f * s, center = p(35f, 15f))
        drawCircle(color = strandB, radius = 5f * s, center = p(105f, 15f))
        drawCircle(color = strandA, radius = 5f * s, center = p(105f, 125f))
        drawCircle(color = strandB, radius = 5f * s, center = p(35f, 125f))
    }
}

@Preview(name = "Setup — welcome", showBackground = true)
@Composable
private fun WelcomeStepPreview() {
    MessengerTheme { WelcomeStep(onContinue = {}) }
}

@Preview(name = "Setup — profile", showBackground = true)
@Composable
private fun ProfileStepPreview() {
    MessengerTheme {
        var displayName by remember { mutableStateOf("باسل") }
        var username by remember { mutableStateOf("basil") }
        var accessCode by remember { mutableStateOf("1234") }
        ProfileStep(
            displayName = displayName,
            onDisplayNameChange = { displayName = it },
            username = username,
            onUsernameChange = { username = it },
            accessCode = accessCode,
            onAccessCodeChange = { accessCode = it },
            avatarBytes = null,
            onPickAvatar = {},
            onNext = {}
        )
    }
}

@Preview(name = "Setup — generating", showBackground = true)
@Composable
private fun GeneratingStepPreview() {
    MessengerTheme { GeneratingStep(isGenerating = true, onComplete = {}) }
}

private fun Modifier.graphicsLayerRotation(angle: Float): Modifier = this.graphicsLayer(rotationZ = angle)
