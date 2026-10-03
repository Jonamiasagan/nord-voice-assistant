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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
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
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "gideon_animations")

    // Slow ambient hologram rotation
    val ringRotation1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring_rotation_1"
    )

    // Reverse faster ring rotation
    val ringRotation2 by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF030712)) // Sci-fi deep space black
    ) {
        // ── 1. BACKGROUND HOLOGRAPHIC DOT MATRIX WALL (Gideon's Chamber) ──
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
                    val factor = (1f - (dist / maxDist).coerceIn(0f, 1f)) * 0.45f

                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = factor * 0.45f),
                        radius = 2.2f,
                        center = Offset(cx, cy)
                    )
                }
            }
        }

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
                                    AssistantState.IDLE -> Color(0xFF00B0FF)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "G.I.D.E.O.N. // SYSTEM ONLINE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        color = Color(0xFF00E5FF).copy(alpha = 0.85f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (state) {
                        AssistantState.LISTENING -> "STATUS: AUDIO SENSORS ACTIVE..."
                        AssistantState.THINKING -> "STATUS: QUANTUM NEURAL PROCESSING..."
                        AssistantState.SPEAKING -> "STATUS: VOCAL SYNTHESIS ACTIVE"
                        AssistantState.IDLE -> "STATUS: STANDBY // TAP TO ENGAGE"
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
                                Color(0xFF00E5FF).copy(alpha = glowAlpha * 0.35f),
                                Color(0xFF0052CC).copy(alpha = glowAlpha * 0.15f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius * 1.35f
                        )
                    )

                    // Outer Cyberspace HUD Ring 1 (Dashed / Segmented)
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.3f),
                        radius = radius + 12.dp.toPx(),
                        style = Stroke(
                            width = 1.8f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f), ringRotation1 * 2f)
                        )
                    )

                    // Inner Cyberspace HUD Ring 2 (Counter-rotating)
                    drawCircle(
                        color = Color(0xFF18FFFF).copy(alpha = 0.5f),
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
                        color = Color(0xFFE0F7FA),
                        radius = 4.5f,
                        center = Offset(nodeX, nodeY)
                    )

                    val node2AngleRad = Math.toRadians((ringRotation1 + 180.0))
                    val node2X = center.x + (radius + 8.dp.toPx()) * cos(node2AngleRad).toFloat()
                    val node2Y = center.y + (radius + 8.dp.toPx()) * sin(node2AngleRad).toFloat()
                    drawCircle(
                        color = Color(0xFF00E5FF),
                        radius = 3.5f,
                        center = Offset(node2X, node2Y)
                    )

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

                // Gideon Face Image in Hologram Circular Frame
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(CircleShape)
                        .border(
                            width = 2.5.dp,
                            brush = Brush.sweepGradient(
                                listOf(
                                    Color(0xFF00E5FF),
                                    Color(0xFF0091EA),
                                    Color(0xFF80D8FF),
                                    Color(0xFF00E5FF)
                                )
                            ),
                            shape = CircleShape
                        )
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.gideon_face_sq),
                        contentDescription = "Gideon AI Hologram Face",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(hologramPulse)
                    )

                    // Holographic Sci-Fi Cyan Overlay Tint
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x3300E5FF),
                                        Color(0x1000B0FF),
                                        Color(0x440052CC)
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
                        .background(Color(0xFF061325).copy(alpha = 0.75f))
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF00E5FF).copy(alpha = 0.4f),
                                    Color(0xFF0052CC).copy(alpha = 0.2f),
                                    Color(0xFF00E5FF).copy(alpha = 0.4f)
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
                                text = "COMMUNICATION LOG",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF00E5FF).copy(alpha = 0.6f),
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
                    }
                }
            }
        }
    }
}
