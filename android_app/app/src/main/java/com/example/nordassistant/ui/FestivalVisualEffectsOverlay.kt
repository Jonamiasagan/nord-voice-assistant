package com.example.nordassistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class ParticleEffectType {
    NONE,
    DIWALI_FIREWORKS,      // Sparking rockets, bursting radial sparks, shimmering flares
    DHANTERAS_GOLD_COINS,  // Drifting 3D golden coins and sparkling metallic confetti
    NARAKA_AARTI_FLAMES,   // Rising sacred fire embers, saffron sparks, victory aura
    BHAI_DOOJ_ROSE_PETALS, // Floating rose petals, Akshat sparkles, soft Mauli ribbons
    RAJYOTSAVA_CONFETTI    // Fluttering yellow and red celebratory paper ribbons & sparkles
}

data class FireworkRocket(
    val launchXNorm: Float,
    val targetXNorm: Float,
    val targetYNorm: Float,
    val color: Color,
    val sparkCount: Int = 24,
    val maxRadius: Float = 175f,
    val phaseOffset: Float = 0f
)

data class DriftingCoin(
    val xNorm: Float,
    val speed: Float,
    val size: Float,
    val phaseOffset: Float
)

data class FloatingPetal(
    val xNorm: Float,
    val speed: Float,
    val radiusX: Float,
    val radiusY: Float,
    val color: Color,
    val phaseOffset: Float,
    val rotationSpeed: Float = 1.0f,
    val isFullBlossom: Boolean = false
)

@Composable
fun FestivalVisualEffectsOverlay(
    effectType: ParticleEffectType,
    modifier: Modifier = Modifier
) {
    if (effectType == ParticleEffectType.NONE) return

    val infiniteTransition = rememberInfiniteTransition(label = "festival_particles")
    val timeProg by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particle_time"
    )

    when (effectType) {
        ParticleEffectType.DIWALI_FIREWORKS -> {
            val rockets = remember {
                listOf(
                    FireworkRocket(0.18f, 0.20f, 0.10f, Color(0xFFFFD700), 32, 260f, 0.0f),
                    FireworkRocket(0.82f, 0.80f, 0.14f, Color(0xFFFF3D00), 34, 280f, 0.25f),
                    FireworkRocket(0.50f, 0.50f, 0.07f, Color(0xFFFFEA00), 40, 320f, 0.50f),
                    FireworkRocket(0.32f, 0.28f, 0.20f, Color(0xFFFF6D00), 30, 240f, 0.75f),
                    FireworkRocket(0.68f, 0.72f, 0.22f, Color(0xFFFFAB00), 32, 250f, 0.12f),
                    FireworkRocket(0.12f, 0.10f, 0.28f, Color(0xFFFF1744), 28, 230f, 0.62f),
                    FireworkRocket(0.88f, 0.90f, 0.30f, Color(0xFFFFD600), 30, 250f, 0.38f)
                )
            }

            Canvas(modifier = modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                rockets.forEach { rocket ->
                    val localTime = (timeProg * 2.2f + rocket.phaseOffset) % 1f

                    val launchX = rocket.launchXNorm * w
                    val targetX = rocket.targetXNorm * w
                    val targetY = rocket.targetYNorm * h
                    val launchY = h + 20f

                    // Stage 1: Long Ascent from Ground all the way to Upper Sky (0.0 to 0.45)
                    if (localTime <= 0.45f) {
                        val ascentProg = (localTime / 0.45f).coerceIn(0f, 1f)
                        val currX = launchX + (targetX - launchX) * ascentProg
                        val currY = launchY + (targetY - launchY) * ascentProg

                        // Glowing rocket head
                        drawCircle(
                            color = Color(0xFFFFFFFF),
                            radius = 5.5f,
                            center = Offset(currX, currY)
                        )
                        // Deep long rocket propulsion spark tail (long flame streak)
                        for (trailIdx in 1..24) {
                            val trailBack = trailIdx * 7.0f // Up to ~170px long glowing tail!
                            val trailY = currY + trailBack
                            val trailX = currX + sin(trailIdx.toFloat() * 1.5f) * 2.5f
                            val trailAlpha = (1f - (trailIdx / 25f)) * 0.95f
                            val tailColor = if (trailIdx < 6) Color(0xFFFFF9C4) else if (trailIdx < 14) rocket.color else Color(0xFFFF5722)

                            drawCircle(
                                color = tailColor.copy(alpha = trailAlpha),
                                radius = (3.8f * (1f - trailIdx / 26f)).coerceAtLeast(1.2f),
                                center = Offset(trailX, trailY)
                            )
                        }
                    }
                    // Stage 2: Grand Cracker Blast Explosion (0.45 to 1.0)
                    else {
                        val blastProg = ((localTime - 0.45f) / 0.55f).coerceIn(0f, 1f)
                        val currentRadius = rocket.maxRadius * blastProg
                        val alpha = (1f - blastProg).coerceIn(0f, 1f)

                        // 1. Massive Center Flash on initial blast
                        if (blastProg < 0.20f) {
                            val flashAlpha = (1f - blastProg * 5f).coerceIn(0f, 1f)
                            drawCircle(
                                color = Color.White.copy(alpha = flashAlpha * 0.95f),
                                radius = 28f * (1f - blastProg),
                                center = Offset(targetX, targetY)
                            )
                            // Outer shockwave ring
                            drawCircle(
                                color = rocket.color.copy(alpha = flashAlpha * 0.8f),
                                radius = 55f * blastProg * 5f,
                                style = Stroke(width = 4.0f),
                                center = Offset(targetX, targetY)
                            )
                        }

                        // 2. High-density Spark Rays exploding radially
                        for (i in 0 until rocket.sparkCount) {
                            val angleRad = (Math.PI * 2.0 / rocket.sparkCount) * i
                            // Alternating distance for realistic burst dispersion
                            val sparkDist = currentRadius * (0.80f + 0.35f * (i % 3))
                            val sx = targetX + sparkDist * cos(angleRad).toFloat()
                            val gravityFall = (blastProg * blastProg) * 35f // accelerating gravity fall
                            val sy = targetY + sparkDist * sin(angleRad).toFloat() + gravityFall

                            // Primary glowing spark
                            drawCircle(
                                color = rocket.color.copy(alpha = alpha),
                                radius = (5.5f * (1f - blastProg * 0.55f)).coerceAtLeast(1.8f),
                                center = Offset(sx, sy)
                            )
                            // White-hot core inside each spark
                            if (blastProg < 0.7f) {
                                drawCircle(
                                    color = Color.White.copy(alpha = alpha * 0.85f),
                                    radius = 2.2f * (1f - blastProg),
                                    center = Offset(sx, sy)
                                )
                            }

                            // Secondary trailing sparkles (sparkler fallout)
                            if (blastProg > 0.25f && i % 2 == 0) {
                                val trailDist = sparkDist * 0.72f
                                val tx = targetX + trailDist * cos(angleRad).toFloat()
                                val ty = targetY + trailDist * sin(angleRad).toFloat() + (gravityFall * 0.6f)
                                drawCircle(
                                    color = Color(0xFFFFE082).copy(alpha = alpha * 0.6f),
                                    radius = 2.0f,
                                    center = Offset(tx, ty)
                                )
                            }
                        }
                    }
                }
            }
        }

        ParticleEffectType.DHANTERAS_GOLD_COINS -> {
            val coins = remember {
                List(28) { idx ->
                    val rnd = Random(idx * 79)
                    DriftingCoin(
                        xNorm = rnd.nextFloat(),
                        speed = 0.35f + rnd.nextFloat() * 0.45f,
                        size = 14f + rnd.nextFloat() * 12f,
                        phaseOffset = rnd.nextFloat()
                    )
                }
            }

            Canvas(modifier = modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                coins.forEach { coin ->
                    val yProgress = (timeProg * coin.speed + coin.phaseOffset) % 1f
                    val cx = coin.xNorm * w + sin(yProgress * 8f) * 25f
                    val cy = yProgress * (h + 80f) - 40f

                    // 3D smooth horizontal tumbling
                    val spinScale = cos(yProgress * 10f + coin.phaseOffset * 4f)
                    val rX = coin.size * kotlin.math.abs(spinScale).coerceAtLeast(0.18f)
                    val rY = coin.size

                    // Outer golden coin body
                    drawOval(
                        color = Color(0xFFFFD700).copy(alpha = 0.92f),
                        topLeft = Offset(cx - rX, cy - rY),
                        size = androidx.compose.ui.geometry.Size(rX * 2f, rY * 2f),
                        style = Fill
                    )
                    // Inner minted coin ring
                    drawOval(
                        color = Color(0xFFB78103).copy(alpha = 0.75f),
                        topLeft = Offset(cx - rX * 0.72f, cy - rY * 0.72f),
                        size = androidx.compose.ui.geometry.Size(rX * 1.44f, rY * 1.44f),
                        style = Stroke(width = 2.0f)
                    )
                    // Gleaming specular reflection on face
                    if (kotlin.math.abs(spinScale) > 0.4f) {
                        drawOval(
                            color = Color(0xFFFFFDE7).copy(alpha = 0.85f),
                            topLeft = Offset(cx - rX * 0.4f, cy - rY * 0.45f),
                            size = androidx.compose.ui.geometry.Size(rX * 0.7f, rY * 0.5f),
                            style = Fill
                        )
                    }

                    // Floating golden sparkle star beside drifting coins
                    if (yProgress in 0.2f..0.8f && coin.size > 20f) {
                        val sparkX = cx + cos(yProgress * 15f) * (rX + 16f)
                        val sparkY = cy + sin(yProgress * 15f) * 12f
                        drawCircle(
                            color = Color(0xFFFFF9C4).copy(alpha = 0.9f),
                            radius = 2.4f,
                            center = Offset(sparkX, sparkY)
                        )
                    }
                }
            }
        }

        ParticleEffectType.NARAKA_AARTI_FLAMES -> {
            val embers = remember {
                List(42) { idx ->
                    val rnd = Random(idx * 43)
                    DriftingCoin(
                        xNorm = rnd.nextFloat(),
                        speed = 0.35f + rnd.nextFloat() * 0.45f,
                        size = 3.5f + rnd.nextFloat() * 6.0f,
                        phaseOffset = rnd.nextFloat()
                    )
                }
            }

            Canvas(modifier = modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                embers.forEach { ember ->
                    // Rising gracefully from bottom Aarti diyas to sky
                    val riseProgress = 1f - ((timeProg * ember.speed + ember.phaseOffset) % 1f)
                    val cx = ember.xNorm * w + sin(riseProgress * 8f) * 28f
                    val cy = riseProgress * (h + 40f)

                    val alpha = (sin(riseProgress * Math.PI.toFloat())).coerceIn(0f, 1f) * 0.90f
                    val emberColor = if (ember.size > 6.5f) Color(0xFFFF3D00) else if (ember.size > 4.5f) Color(0xFFFF9100) else Color(0xFFFFD700)

                    // Outer fire halo
                    drawCircle(
                        color = emberColor.copy(alpha = alpha * 0.5f),
                        radius = ember.size * 1.6f,
                        center = Offset(cx, cy)
                    )
                    // Solid flame ember core
                    drawCircle(
                        color = emberColor.copy(alpha = alpha),
                        radius = ember.size,
                        center = Offset(cx, cy)
                    )
                    // White-hot center spark
                    drawCircle(
                        color = Color(0xFFFFFFFF).copy(alpha = alpha * 0.95f),
                        radius = ember.size * 0.45f,
                        center = Offset(cx, cy)
                    )
                }
            }
        }

        ParticleEffectType.BHAI_DOOJ_ROSE_PETALS -> {
            val petals = remember {
                List(36) { idx ->
                    val rnd = Random(idx * 101)
                    FloatingPetal(
                        xNorm = rnd.nextFloat(),
                        speed = 0.22f + rnd.nextFloat() * 0.32f,
                        radiusX = 13f + rnd.nextFloat() * 8f,
                        radiusY = 20f + rnd.nextFloat() * 12f,
                        color = when (idx % 5) {
                            0 -> Color(0xFFD81B60) // Deep Rose Crimson
                            1 -> Color(0xFFFF4081) // Radiant Pink
                            2 -> Color(0xFFFF80AB) // Soft Blossom Pink
                            3 -> Color(0xFFFF9800) // Saffron Marigold Petal
                            else -> Color(0xFFFFD54F) // Golden Auspicious Petal
                        },
                        phaseOffset = rnd.nextFloat(),
                        rotationSpeed = 0.6f + rnd.nextFloat() * 1.2f,
                        isFullBlossom = (idx % 6 == 0) // Every 6th particle is a full 5-petal flower
                    )
                }
            }

            Canvas(modifier = modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                petals.forEach { petal ->
                    val fallProg = (timeProg * petal.speed + petal.phaseOffset) % 1f
                    val cx = petal.xNorm * w + sin(fallProg * 5f) * 36f
                    val cy = fallProg * (h + 100f) - 50f

                    // 3D tumbling flutter
                    val wobble = cos(fallProg * 6f + petal.phaseOffset * 5f)
                    val scaleX = kotlin.math.abs(wobble).coerceAtLeast(0.3f)
                    val rotAngle = (fallProg * 360f * petal.rotationSpeed + petal.phaseOffset * 360f) % 360f

                    val rX = petal.radiusX
                    val rY = petal.radiusY

                    withTransform({
                        translate(cx, cy)
                        rotate(degrees = rotAngle)
                        scale(scaleX = scaleX, scaleY = 1f)
                    }) {
                        if (petal.isFullBlossom) {
                            // ── FULL 5-PETAL FLOWER BLOSSOM ──
                            val blossomPetalRadiusX = rX * 0.65f
                            val blossomPetalRadiusY = rY * 0.85f

                            for (petalIdx in 0 until 5) {
                                withTransform({
                                    rotate(degrees = petalIdx * 72f)
                                }) {
                                    val petalPath = Path().apply {
                                        moveTo(0f, 0f)
                                        cubicTo(
                                            blossomPetalRadiusX * 1.2f, -blossomPetalRadiusY * 0.4f,
                                            blossomPetalRadiusX * 0.8f, -blossomPetalRadiusY * 0.95f,
                                            0f, -blossomPetalRadiusY
                                        )
                                        cubicTo(
                                            -blossomPetalRadiusX * 0.8f, -blossomPetalRadiusY * 0.95f,
                                            -blossomPetalRadiusX * 1.2f, -blossomPetalRadiusY * 0.4f,
                                            0f, 0f
                                        )
                                        close()
                                    }
                                    drawPath(
                                        path = petalPath,
                                        color = petal.color.copy(alpha = 0.90f)
                                    )
                                }
                            }
                            // Golden flower core center
                            drawCircle(color = Color(0xFFFFD54F), radius = blossomPetalRadiusX * 0.55f)
                            drawCircle(color = Color(0xFFFFF9C4), radius = blossomPetalRadiusX * 0.28f)
                        } else {
                            // ── INDIVIDUAL REALISTIC FLOWER PETAL (Rose / Lotus Petal) ──
                            // Organic curved petal contour with pointed apex tip and heart-curved base
                            val petalPath = Path().apply {
                                moveTo(0f, -rY) // Pointed apex
                                // Right flank curving down to base
                                cubicTo(rX * 1.35f, -rY * 0.45f, rX * 1.10f, rY * 0.65f, 0f, rY)
                                // Left flank curving back up to apex
                                cubicTo(-rX * 1.10f, rY * 0.65f, -rX * 1.35f, -rY * 0.45f, 0f, -rY)
                                close()
                            }

                            // Velvet petal gradient fill
                            drawPath(
                                path = petalPath,
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        petal.color.copy(alpha = 0.92f),
                                        Color(0xFF880E4F).copy(alpha = 0.85f)
                                    ),
                                    center = Offset(0f, rY * 0.25f),
                                    radius = rY * 1.3f
                                )
                            )

                            // Delicate central petal vein
                            val veinPath = Path().apply {
                                moveTo(0f, rY * 0.85f)
                                quadraticTo(rX * 0.15f * sin(fallProg * 4f), 0f, 0f, -rY * 0.70f)
                            }
                            drawPath(
                                path = veinPath,
                                color = Color.White.copy(alpha = 0.40f),
                                style = Stroke(width = 1.2f)
                            )
                        }
                    }

                    // Twinkling golden Akshat grain sparkle drifting alongside
                    if (kotlin.math.abs(wobble) > 0.4f) {
                        drawCircle(
                            color = Color(0xFFFFFDE7).copy(alpha = 0.85f),
                            radius = 2.2f,
                            center = Offset(cx + rX + 6f, cy - 4f)
                        )
                    }
                }
            }
        }

        ParticleEffectType.RAJYOTSAVA_CONFETTI -> {
            val ribbons = remember {
                List(32) { idx ->
                    val rnd = Random(idx * 67)
                    FloatingPetal(
                        xNorm = rnd.nextFloat(),
                        speed = 0.25f + rnd.nextFloat() * 0.35f, // Calm, gentle drifting descent
                        radiusX = 7f + rnd.nextFloat() * 5f,
                        radiusY = 18f + rnd.nextFloat() * 10f,
                        color = if (idx % 2 == 0) Color(0xFFFFD700) else Color(0xFFD32F2F),
                        phaseOffset = rnd.nextFloat()
                    )
                }
            }

            Canvas(modifier = modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                ribbons.forEach { ribbon ->
                    val fallProg = (timeProg * ribbon.speed + ribbon.phaseOffset) % 1f
                    val cx = ribbon.xNorm * w + sin(fallProg * 5f) * 22f
                    val cy = fallProg * (h + 80f) - 40f

                    // Very slow and graceful tumbling 3D rotation
                    val rotationProg = (fallProg * 4.5f + ribbon.phaseOffset * 3f)
                    val scaleX = cos(rotationProg)

                    // Draw distinct fluttering ribbon with subtle highlight
                    val rWidth = ribbon.radiusX * kotlin.math.abs(scaleX).coerceAtLeast(0.20f)
                    drawOval(
                        color = ribbon.color.copy(alpha = 0.88f),
                        topLeft = Offset(cx - rWidth, cy - ribbon.radiusY),
                        size = androidx.compose.ui.geometry.Size(rWidth * 2f, ribbon.radiusY * 2f)
                    )
                    // Soft edge gleam
                    drawOval(
                        color = Color.White.copy(alpha = 0.35f),
                        topLeft = Offset(cx - rWidth * 0.7f, cy - ribbon.radiusY * 0.7f),
                        size = androidx.compose.ui.geometry.Size(rWidth * 1.4f, ribbon.radiusY * 1.4f),
                        style = Stroke(width = 1.2f)
                    )
                }
            }
        }

        else -> {}
    }
}

