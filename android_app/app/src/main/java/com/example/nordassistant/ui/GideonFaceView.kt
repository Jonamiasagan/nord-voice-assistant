package com.example.nordassistant.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nordassistant.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Assistant operational states for Gideon hologram animation
 */
enum class AssistantState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

/**
 * Futuristic Gideon Hologram Interface inspired by The Flash series.
 * Features:
 * - Hologram dot-matrix emitter wall (from Gideon's Time Vault in S.T.A.R. Labs)
 * - Concentric rotating cybernetic HUD rings
 * - Hologram scanlines and neural frequency pulses
 * - State-driven reaction animations (IDLE, LISTENING, THINKING, SPEAKING)
 * - Sci-fi terminal transcript readouts
 */
@Composable
fun GideonFaceView(
    state: AssistantState,
    transcript: String,
    onAvatarClick: () -> Unit,
    securityStatus: String = "DEVIL // BOSS SECURED",
    modifier: Modifier = Modifier
) {
    val activeFestival = remember { FestivalThemeManager.getActiveFestivalTheme() }
    val infiniteTransition = rememberInfiniteTransition(label = "gideon_animations")

    // Calm, slow ambient hologram rotation
    val ringRotation1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 32000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rotation_1"
    )

    // Gentle reverse slow ring rotation
    val ringRotation2 by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rotation_2"
    )

    // Pulse scale (intensified during THINKING & SPEAKING)
    val pulseDuration = when (state) {
        AssistantState.THINKING -> 750
        AssistantState.SPEAKING -> 900
        AssistantState.LISTENING -> 1100
        AssistantState.IDLE -> 2400
    }

    val hologramPulse by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = when (state) {
            AssistantState.THINKING -> 1.06f
            AssistantState.SPEAKING -> 1.04f
            AssistantState.LISTENING -> 1.05f
            AssistantState.IDLE -> 1.01f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = pulseDuration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hologram_pulse"
    )

    // Vertical holographic scanline sweep
    val scanlineOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (state == AssistantState.THINKING) 800 else 2200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanline_sweep"
    )

    // Glow intensity
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = when (state) {
            AssistantState.THINKING -> 0.95f
            AssistantState.SPEAKING -> 0.85f
            AssistantState.LISTENING -> 0.80f
            AssistantState.IDLE -> 0.55f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (state == AssistantState.THINKING) 500 else 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    // Animated sacred blessing energy stream (Sister -> Brother for Bhai Dooj)
    val blessingProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "blessing_progress"
    )

    // Shimmering Raksha Kavach (Protective Energy Shield) pulse
    val shieldPulse by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shield_pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        activeFestival.bgTopColor,
                        activeFestival.bgBottomColor
                    )
                )
            )
    ) {
        // ── 1. BACKGROUND HOLOGRAPHIC DOT MATRIX WALL (Dynamic Festival Glow) ──
        Canvas(modifier = Modifier.fillMaxSize()) {
            val dotSpacing = 36.dp.toPx()
            val cols = (size.width / dotSpacing).toInt() + 1
            val rows = (size.height / dotSpacing).toInt() + 1

            for (r in 0..rows) {
                for (c in 0..cols) {
                    val cx = c * dotSpacing
                    val cy = r * dotSpacing
                    // Distance from center to create radial fading
                    val dx = cx - size.width / 2f
                    val dy = cy - size.height / 2f
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    val maxDist = kotlin.math.sqrt(size.width * size.width + size.height * size.height) / 2f
                    val factor = (1f - (dist / maxDist).coerceIn(0f, 1f)) * 0.50f

                    drawCircle(
                        color = activeFestival.dotMatrixColor.copy(alpha = factor * 0.55f),
                        radius = 2.4f,
                        center = Offset(cx, cy)
                    )
                }
            }
        }

        // ── 1.5 DYNAMIC FESTIVAL VISUAL EFFECTS (Fireworks, Coins, Aarti Flames, Petals) ──
        FestivalVisualEffectsOverlay(
            effectType = activeFestival.effectType,
            modifier = Modifier.fillMaxSize()
        )

        // ── 2. CENTER HOLOGRAPHIC GIDEON AVATAR & HUD ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // TOP HUD TELEMETRY BAR
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (state) {
                                    AssistantState.THINKING -> Color(0xFFFFD600)
                                    AssistantState.LISTENING -> Color(0xFF00E676)
                                    AssistantState.SPEAKING -> Color(0xFF00E5FF)
                                    AssistantState.IDLE -> if (securityStatus.contains("GUEST")) Color(0xFFFFD600) else Color(0xFF00B0FF)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${activeFestival.festivalName.uppercase()} EDITION // ${securityStatus}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        color = activeFestival.primaryRingColor.copy(alpha = 0.95f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (state == AssistantState.IDLE) activeFestival.greeting else when (state) {
                        AssistantState.LISTENING -> "STATUS: LISTENING TO YOU..."
                        AssistantState.THINKING -> "STATUS: DEVIL ANALYZING..."
                        AssistantState.SPEAKING -> "STATUS: DEVIL RESPONDING..."
                        else -> "STATUS: STANDBY"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = when (state) {
                        AssistantState.THINKING -> Color(0xFFFFD600).copy(alpha = 0.9f)
                        AssistantState.LISTENING -> Color(0xFF00E676).copy(alpha = 0.9f)
                        AssistantState.SPEAKING -> Color(0xFF00E5FF).copy(alpha = 0.9f)
                        AssistantState.IDLE -> Color(0xFF90CAF9).copy(alpha = 0.6f)
                    },
                    letterSpacing = 1.sp
                )
            }

            // CENTER HOLOGRAPHIC FACE DISPLAY
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(310.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onAvatarClick() }
            ) {
                // ── BACKGROUND FESTIVAL SACRED PURPOSE (Embossed Holographic Watermark Behind Avatar) ──
                if (activeFestival.purposeBrief.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = activeFestival.purposeBrief,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic,
                            lineHeight = 18.sp,
                            color = activeFestival.primaryRingColor.copy(alpha = 0.22f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Outer Holographic Energy Glow & Wave Rings
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(hologramPulse)
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension / 2f - 18.dp.toPx()

                    // Radial glow background behind Gideon
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                activeFestival.primaryRingColor.copy(alpha = glowAlpha * 0.45f),
                                activeFestival.secondaryRingColor.copy(alpha = glowAlpha * 0.25f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius * 1.35f
                        )
                    )

                    // ── KANNADA BI-COLOR FLAG RING (Top Half Yellow / Bottom Half Red) ──
                    if (activeFestival.festivalName.contains("Kannada", ignoreCase = true)) {
                        // Top Arc: Turmeric Yellow (180° to 360°)
                        drawArc(
                            color = Color(0xFFFFD700),
                            startAngle = 180f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(center.x - radius - 8.dp.toPx(), center.y - radius - 8.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size((radius + 8.dp.toPx()) * 2f, (radius + 8.dp.toPx()) * 2f),
                            style = Stroke(width = 4.5f)
                        )
                        // Bottom Arc: Kumkuma Red (0° to 180°)
                        drawArc(
                            color = Color(0xFFD32F2F),
                            startAngle = 0f,
                            sweepAngle = 180f,
                            useCenter = false,
                            topLeft = Offset(center.x - radius - 8.dp.toPx(), center.y - radius - 8.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size((radius + 8.dp.toPx()) * 2f, (radius + 8.dp.toPx()) * 2f),
                            style = Stroke(width = 4.5f)
                        )
                        // Subtle golden split divider nodes at 0° and 180°
                        drawCircle(
                            color = Color(0xFFFFF9C4),
                            radius = 4f,
                            center = Offset(center.x + radius + 8.dp.toPx(), center.y)
                        )
                        drawCircle(
                            color = Color(0xFFFFF9C4),
                            radius = 4f,
                            center = Offset(center.x - (radius + 8.dp.toPx()), center.y)
                        )
                    } else if (activeFestival.festivalName == "Bhai Dooj") {
                        // ── SACRED BLOOMING FLOWER PETALS MANDALA (Lotus/Rose Petals instead of circles) ──
                        val numPetals = 16
                        val petalRingRadius = radius + 8.dp.toPx()
                        val petalLen = 15.dp.toPx()
                        val petalW = 7.5.dp.toPx()

                        for (i in 0 until numPetals) {
                            val baseAngle = (i.toFloat() / numPetals) * 360f + (ringRotation1 * 0.35f)
                            val rad = Math.toRadians(baseAngle.toDouble())
                            val px = center.x + petalRingRadius * cos(rad).toFloat()
                            val py = center.y + petalRingRadius * sin(rad).toFloat()

                            withTransform({
                                translate(px, py)
                                rotate(degrees = baseAngle + 90f)
                            }) {
                                val petalPath = Path().apply {
                                    moveTo(0f, -petalLen)
                                    cubicTo(petalW * 1.35f, -petalLen * 0.45f, petalW * 0.95f, petalLen * 0.55f, 0f, petalLen * 0.85f)
                                    cubicTo(-petalW * 0.95f, petalLen * 0.55f, -petalW * 1.35f, -petalLen * 0.45f, 0f, -petalLen)
                                    close()
                                }
                                drawPath(
                                    path = petalPath,
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFFFF4081).copy(alpha = 0.85f),
                                            Color(0xFFD81B60).copy(alpha = 0.70f),
                                            Color(0xFFFFD54F).copy(alpha = 0.50f)
                                        )
                                    )
                                )
                                drawPath(
                                    path = petalPath,
                                    color = Color(0xFFFFD54F).copy(alpha = 0.90f),
                                    style = Stroke(width = 1.2f)
                                )
                            }
                        }

                        // Inner counter-rotating flower garland ring
                        val numInnerPetals = 12
                        val innerRingRadius = radius - 1.dp.toPx()
                        for (i in 0 until numInnerPetals) {
                            val baseAngle = (i.toFloat() / numInnerPetals) * 360f - (ringRotation2 * 0.25f)
                            val rad = Math.toRadians(baseAngle.toDouble())
                            val px = center.x + innerRingRadius * cos(rad).toFloat()
                            val py = center.y + innerRingRadius * sin(rad).toFloat()

                            withTransform({
                                translate(px, py)
                                rotate(degrees = baseAngle + 90f)
                            }) {
                                val innerPetalPath = Path().apply {
                                    moveTo(0f, -petalLen * 0.65f)
                                    cubicTo(petalW * 0.9f, -petalLen * 0.25f, petalW * 0.7f, petalLen * 0.35f, 0f, petalLen * 0.5f)
                                    cubicTo(-petalW * 0.7f, petalLen * 0.35f, -petalW * 0.9f, -petalLen * 0.25f, 0f, -petalLen * 0.65f)
                                    close()
                                }
                                drawPath(
                                    path = innerPetalPath,
                                    color = Color(0xFFFF80AB).copy(alpha = 0.65f)
                                )
                            }
                        }
                    } else {
                        // Standard Cyberspace HUD Ring 1 (Dashed / Segmented)
                        drawCircle(
                            color = activeFestival.primaryRingColor.copy(alpha = 0.4f),
                            radius = radius + 12.dp.toPx(),
                            style = Stroke(
                                width = 1.8f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f), ringRotation1 * 2f)
                            )
                        )

                        // Inner Cyberspace HUD Ring 2 (Counter-rotating)
                        drawCircle(
                            color = activeFestival.secondaryRingColor.copy(alpha = 0.6f),
                            radius = radius + 4.dp.toPx(),
                            style = Stroke(
                                width = 2.2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 25f, 10f, 25f), ringRotation2 * 2f)
                            )
                        )

                        // Orbiting Data Nodes
                        val nodeAngleRad = Math.toRadians(ringRotation1.toDouble())
                        val nodeX = center.x + (radius + 8.dp.toPx()) * cos(nodeAngleRad).toFloat()
                        val nodeY = center.y + (radius + 8.dp.toPx()) * sin(nodeAngleRad).toFloat()
                        drawCircle(
                            color = activeFestival.accentColor,
                            radius = 4.5f,
                            center = Offset(nodeX, nodeY)
                        )

                        val node2AngleRad = Math.toRadians((ringRotation1 + 180.0))
                        val node2X = center.x + (radius + 8.dp.toPx()) * cos(node2AngleRad).toFloat()
                        val node2Y = center.y + (radius + 8.dp.toPx()) * sin(node2AngleRad).toFloat()
                        drawCircle(
                            color = activeFestival.secondaryRingColor,
                            radius = 3.5f,
                            center = Offset(node2X, node2Y)
                        )
                    }

                    // If THINKING, draw dynamic expanding neural frequency wave
                    if (state == AssistantState.THINKING) {
                        drawCircle(
                            color = Color(0xFFFFD600).copy(alpha = (1f - hologramPulse + 0.98f).coerceIn(0.1f, 0.7f)),
                            radius = radius * 1.15f * hologramPulse,
                            style = Stroke(width = 2.5f)
                        )
                    }

                    // If LISTENING, draw pulsing audio ring
                    if (state == AssistantState.LISTENING) {
                        drawCircle(
                            color = Color(0xFF00E676).copy(alpha = 0.6f),
                            radius = radius * 1.1f,
                            style = Stroke(
                                width = 3f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), ringRotation2 * 3f)
                            )
                        )
                    }
                }

                // Gideon Face Image in Hologram Circular Frame (Dynamic Festival Theme)
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(CircleShape)
                        .border(
                            width = 3.dp,
                            brush = if (activeFestival.festivalName.contains("Kannada", ignoreCase = true)) {
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFFFFD700), // Pure Yellow Top
                                        Color(0xFFFFD700),
                                        Color(0xFFD32F2F), // Pure Red Bottom
                                        Color(0xFFD32F2F)
                                    )
                                )
                            } else if (activeFestival.festivalName == "Bhai Dooj") {
                                Brush.sweepGradient(
                                    listOf(
                                        Color(0xFFE74C3C), // Crimson
                                        Color(0xFFFF4081), // Sister Pink
                                        Color(0xFFFFD54F), // Gold
                                        Color(0xFFE74C3C)
                                    )
                                )
                            } else {
                                Brush.sweepGradient(
                                    listOf(
                                        activeFestival.primaryRingColor,
                                        activeFestival.secondaryRingColor,
                                        activeFestival.primaryRingColor.copy(alpha = 0.7f),
                                        activeFestival.primaryRingColor
                                    )
                                )
                            },
                            shape = CircleShape
                        )
                ) {
                    if (activeFestival.festivalName == "Bhai Dooj") {
                        // ── TWO GIDEONS INSIDE A SINGLE CIRCLE: SISTER BLESSING & SHIELDING BROTHER ──
                        val lotusArchShape = RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomStartPercent = 25, bottomEndPercent = 25)

                        Box(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. BROTHER GIDEON (Left, framed in Lotus Petal Arch & protected by Raksha Shield)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(0.90f)
                                        .clip(lotusArchShape)
                                        .border(
                                            width = 2.2.dp,
                                            brush = Brush.sweepGradient(
                                                listOf(
                                                    Color(0xFFFFD54F).copy(alpha = shieldPulse),
                                                    Color(0xFF00E5FF).copy(alpha = 0.85f),
                                                    Color(0xFFFFD54F).copy(alpha = shieldPulse)
                                                )
                                            ),
                                            shape = lotusArchShape
                                        )
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.gideon_bhai_dooj),
                                        contentDescription = "Brother Gideon",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .scale(hologramPulse)
                                    )

                                    // Raksha Kavach (Golden-Cyan Protective Forcefield over Brother)
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        // Shimmering protective energy dome
                                        drawCircle(
                                            brush = Brush.radialGradient(
                                                colors = listOf(
                                                    Color(0xFFFFD54F).copy(alpha = shieldPulse * 0.25f),
                                                    Color(0xFF00E5FF).copy(alpha = shieldPulse * 0.18f),
                                                    Color.Transparent
                                                )
                                            )
                                        )
                                        // Concentric cyber shield ripples
                                        drawCircle(
                                            color = Color(0xFFFFD54F).copy(alpha = shieldPulse * 0.45f),
                                            radius = size.minDimension / 2f * 0.92f,
                                            style = Stroke(width = 1.8.dp.toPx())
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // 2. SISTER GIDEON (Right, female Gideon bestowing blessing in Lotus Petal Arch)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(0.90f)
                                        .clip(lotusArchShape)
                                        .border(
                                            width = 2.2.dp,
                                            brush = Brush.sweepGradient(
                                                listOf(
                                                    Color(0xFFFF4081),
                                                    Color(0xFFFF80AB),
                                                    Color(0xFFFFD54F),
                                                    Color(0xFFFF4081)
                                                )
                                            ),
                                            shape = lotusArchShape
                                        )
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.gideon_sister),
                                        contentDescription = "Sister Gideon",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .scale(scaleX = -hologramPulse, scaleY = hologramPulse)
                                    )

                                    // Sister Divine Rose-Gold Blessing Aura
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color(0xFFFF4081).copy(alpha = 0.18f),
                                                        Color.Transparent,
                                                        Color(0xFFFFD54F).copy(alpha = 0.14f)
                                                    )
                                                )
                                            )
                                    )
                                }
                            }

                            // 3. ANIMATED BLESSING LIGHT STREAM (Sister -> Brother Tilak)
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val w = size.width
                                val h = size.height

                                // Start from Sister's blessing side (x ~ 0.68w, y ~ 0.58h)
                                // Arching across to Brother's forehead tilak (x ~ 0.28w, y ~ 0.38h)
                                val startX = w * 0.68f
                                val startY = h * 0.58f
                                val endX = w * 0.28f
                                val endY = h * 0.38f
                                val controlX = w * 0.48f
                                val controlY = h * 0.22f

                                fun getBezierPoint(t: Float): Offset {
                                    val oneMinusT = 1f - t
                                    val x = oneMinusT * oneMinusT * startX + 2f * oneMinusT * t * controlX + t * t * endX
                                    val y = oneMinusT * oneMinusT * startY + 2f * oneMinusT * t * controlY + t * t * endY
                                    return Offset(x, y)
                                }

                                val path = Path().apply {
                                    moveTo(startX, startY)
                                    quadraticTo(controlX, controlY, endX, endY)
                                }

                                // Glowing baseline curve
                                drawPath(
                                    path = path,
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFFFF4081).copy(alpha = 0.75f),
                                            Color(0xFFFFD54F).copy(alpha = 0.95f),
                                            Color(0xFF00E5FF).copy(alpha = 0.85f)
                                        ),
                                        start = Offset(startX, startY),
                                        end = Offset(endX, endY)
                                    ),
                                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                                )

                                // Flowing light photon nodes
                                val numParticles = 6
                                for (i in 0 until numParticles) {
                                    val particleT = (blessingProgress + (i.toFloat() / numParticles)) % 1f
                                    val pt = getBezierPoint(particleT)
                                    val alpha = (kotlin.math.sin(particleT * Math.PI.toFloat()) * 0.95f).coerceIn(0f, 1f)
                                    val particleRadius = 3.2.dp.toPx()

                                    drawCircle(
                                        color = Color(0xFFFFEB3B).copy(alpha = alpha),
                                        radius = particleRadius,
                                        center = pt
                                    )
                                    drawCircle(
                                        color = Color(0xFFFF80AB).copy(alpha = alpha * 0.55f),
                                        radius = particleRadius * 2.2f,
                                        center = pt
                                    )
                                }

                                // Flare at Brother's Tilak impact point
                                val flareRadius = 8.dp.toPx() + (4.dp.toPx() * shieldPulse)
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFFFFD700).copy(alpha = 0.95f),
                                            Color(0xFFFF4081).copy(alpha = 0.50f),
                                            Color.Transparent
                                        ),
                                        center = Offset(endX, endY),
                                        radius = flareRadius
                                    ),
                                    radius = flareRadius,
                                    center = Offset(endX, endY)
                                )
                            }
                        }
                    } else {
                        Image(
                            painter = painterResource(id = activeFestival.drawableResId),
                            contentDescription = "Gideon AI Hologram Face - ${activeFestival.festivalName}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(hologramPulse)
                        )
                    }

                    // Subtle Holographic Overlay Tint (transparent to preserve vibrant turban & clothes)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        activeFestival.primaryRingColor.copy(alpha = 0.08f),
                                        Color.Transparent,
                                        activeFestival.secondaryRingColor.copy(alpha = 0.10f)
                                    )
                                )
                            )
                    )

                    // Sweeping Holographic Scanline Overlay
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scanlineY = size.height * scanlineOffset
                        drawLine(
                            color = Color(0xFFE0F7FA).copy(alpha = 0.75f),
                            start = Offset(0f, scanlineY),
                            end = Offset(size.width, scanlineY),
                            strokeWidth = 3.5f
                        )
                        drawLine(
                            color = Color(0xFF00E5FF).copy(alpha = 0.35f),
                            start = Offset(0f, (scanlineY - 6.dp.toPx()).coerceAtLeast(0f)),
                            end = Offset(size.width, (scanlineY - 6.dp.toPx()).coerceAtLeast(0f)),
                            strokeWidth = 8f
                        )

                        // Subtle horizontal scanline raster across the hologram
                        val rasterStep = 6.dp.toPx()
                        var currY = 0f
                        while (currY < size.height) {
                            drawLine(
                                color = Color(0xFF001020).copy(alpha = 0.22f),
                                start = Offset(0f, currY),
                                end = Offset(size.width, currY),
                                strokeWidth = 1.2f
                            )
                            currY += rasterStep
                        }
                    }
                }
            }

            // In Bhai Dooj: Dedicated Sister Assurance Holographic Pill
            if (activeFestival.festivalName == "Bhai Dooj") {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF220810).copy(alpha = 0.92f))
                        .border(
                            width = 1.2.dp,
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFFF4081),
                                    Color(0xFFFFD54F),
                                    Color(0xFFFF4081)
                                )
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "🛡️ SISTER GIDEON: \"I WILL PROTECT YOU, BROTHER\"",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFFFE082),
                        letterSpacing = 0.6.sp
                    )
                }
            }

            // ── 3. BOTTOM HOLOGRAPHIC TERMINAL & TRANSCRIPT ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Interactive State Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when (state) {
                                AssistantState.LISTENING -> Color(0xFF00E676).copy(alpha = 0.15f)
                                AssistantState.THINKING -> Color(0xFFFFD600).copy(alpha = 0.15f)
                                AssistantState.SPEAKING -> Color(0xFF00E5FF).copy(alpha = 0.15f)
                                AssistantState.IDLE -> Color(0xFF0091EA).copy(alpha = 0.12f)
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = when (state) {
                                AssistantState.LISTENING -> Color(0xFF00E676).copy(alpha = 0.7f)
                                AssistantState.THINKING -> Color(0xFFFFD600).copy(alpha = 0.7f)
                                AssistantState.SPEAKING -> Color(0xFF00E5FF).copy(alpha = 0.7f)
                                AssistantState.IDLE -> Color(0xFF00B0FF).copy(alpha = 0.4f)
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onAvatarClick() }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = when (state) {
                            AssistantState.LISTENING -> "● LISTENING..."
                            AssistantState.THINKING -> "⚡ PROCESSING QUERY..."
                            AssistantState.SPEAKING -> "▶ RESPONDING..."
                            AssistantState.IDLE -> "START LISTENING"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (state) {
                            AssistantState.LISTENING -> Color(0xFF00E676)
                            AssistantState.THINKING -> Color(0xFFFFD600)
                            AssistantState.SPEAKING -> Color(0xFF00E5FF)
                            AssistantState.IDLE -> Color(0xFFE0F7FA)
                        },
                        letterSpacing = 1.2.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Terminal Transcript Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(activeFestival.bgBottomColor.copy(alpha = 0.85f))
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(
                                listOf(
                                    activeFestival.primaryRingColor.copy(alpha = 0.5f),
                                    activeFestival.secondaryRingColor.copy(alpha = 0.3f),
                                    activeFestival.primaryRingColor.copy(alpha = 0.5f)
                                )
                            ),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp)
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = "COMMUNICATION LOG // ${activeFestival.festivalName.uppercase()}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = activeFestival.primaryRingColor.copy(alpha = 0.8f),
                                letterSpacing = 1.5.sp
                            )
                        }

                        Text(
                            text = transcript.ifEmpty { "Ready for voice command..." },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 22.sp,
                            color = Color(0xFFECEFF1),
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (activeFestival.purposeBrief.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = activeFestival.purposeBrief,
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                lineHeight = 16.sp,
                                color = activeFestival.accentColor.copy(alpha = 0.90f),
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
