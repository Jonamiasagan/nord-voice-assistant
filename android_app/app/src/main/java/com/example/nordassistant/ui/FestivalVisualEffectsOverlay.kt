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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
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

data class FireworkBurst(
    val cxNorm: Float,
    val cyNorm: Float,
    val color: Color,
    val sparkCount: Int = 12,
    val maxRadius: Float = 60f,
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
    val phaseOffset: Float
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
            val bursts = remember {
                listOf(
                    FireworkBurst(0.18f, 0.22f, Color(0xFFFFD700), 14, 85f, 0.0f),
                    FireworkBurst(0.82f, 0.26f, Color(0xFFFF3D00), 16, 95f, 0.25f),
                    FireworkBurst(0.50f, 0.14f, Color(0xFFFFEA00), 18, 110f, 0.5f),
                    FireworkBurst(0.25f, 0.45f, Color(0xFFFF6D00), 12, 70f, 0.75f),
                    FireworkBurst(0.78f, 0.48f, Color(0xFFFFAB00), 14, 80f, 0.12f),
                    FireworkBurst(0.12f, 0.65f, Color(0xFFFF1744), 10, 65f, 0.62f),
                    FireworkBurst(0.88f, 0.68f, Color(0xFFFFD600), 12, 75f, 0.38f)
                )
            }

            Canvas(modifier = modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                bursts.forEach { burst ->
                    val localTime = (timeProg + burst.phaseOffset) % 1f
                    // Repeat burst cycle twice per global period
                    val subCycle = (localTime * 2f) % 1f
                    // Explosion starts around 0.3, lasts till 1.0
                    if (subCycle > 0.15f) {
                        val expandProg = (subCycle - 0.15f) / 0.85f
                        val currentRadius = burst.maxRadius * expandProg
                        val alpha = (1f - expandProg).coerceIn(0f, 1f)
                        val originX = burst.cxNorm * w
                        val originY = burst.cyNorm * h

                        // Center flash
                        if (expandProg < 0.25f) {
                            drawCircle(
                                color = Color.White.copy(alpha = (1f - expandProg * 4f) * 0.9f),
                                radius = 6f,
                                center = Offset(originX, originY)
                            )
                        }

                        // Spark rays
                        for (i in 0 until burst.sparkCount) {
                            val angleRad = (Math.PI * 2.0 / burst.sparkCount) * i
                            val sparkDist = currentRadius * (0.85f + 0.3f * (i % 3))
                            val sx = originX + sparkDist * cos(angleRad).toFloat()
                            val sy = originY + sparkDist * sin(angleRad).toFloat() + (expandProg * 18f) // slight gravity fall

                            drawCircle(
                                color = burst.color.copy(alpha = alpha),
                                radius = (3.2f * (1f - expandProg * 0.6f)).coerceAtLeast(1f),
                                center = Offset(sx, sy)
                            )

                            // Tiny trail spark
                            if (expandProg > 0.3f) {
                                val trailDist = sparkDist * 0.75f
                                val tx = originX + trailDist * cos(angleRad).toFloat()
                                val ty = originY + trailDist * sin(angleRad).toFloat()
                                drawCircle(
                                    color = Color(0xFFFFE082).copy(alpha = alpha * 0.5f),
                                    radius = 1.4f,
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
                List(22) { idx ->
                    val rnd = Random(idx * 79)
                    DriftingCoin(
                        xNorm = rnd.nextFloat(),
                        speed = 0.5f + rnd.nextFloat() * 0.7f,
                        size = 12f + rnd.nextFloat() * 14f,
                        phaseOffset = rnd.nextFloat()
                    )
                }
            }

            Canvas(modifier = modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                coins.forEach { coin ->
                    val yProgress = (timeProg * coin.speed + coin.phaseOffset) % 1f
                    val cx = coin.xNorm * w + sin(yProgress * 12f) * 16f
                    val cy = yProgress * (h + 80f) - 40f

                    // 3D spinning aspect ratio
                    val spinScale = cos(yProgress * 18f + coin.phaseOffset * 6f)
                    val rX = coin.size * kotlin.math.abs(spinScale).coerceAtLeast(0.2f)
                    val rY = coin.size

                    // Outer golden coin rim
                    drawOval(
                        color = Color(0xFFFFD700).copy(alpha = 0.85f),
                        topLeft = Offset(cx - rX, cy - rY),
                        size = androidx.compose.ui.geometry.Size(rX * 2f, rY * 2f),
                        style = Fill
                    )
                    // Inner metallic shine
                    drawOval(
                        color = Color(0xFFFFF9C4).copy(alpha = 0.95f),
                        topLeft = Offset(cx - rX * 0.65f, cy - rY * 0.65f),
                        size = androidx.compose.ui.geometry.Size(rX * 1.3f, rY * 1.3f),
                        style = Stroke(width = 1.8f)
                    )
                }
            }
        }

        ParticleEffectType.NARAKA_AARTI_FLAMES -> {
            val embers = remember {
                List(32) { idx ->
                    val rnd = Random(idx * 43)
                    DriftingCoin(
                        xNorm = rnd.nextFloat(),
                        speed = 0.7f + rnd.nextFloat() * 0.8f,
                        size = 3.5f + rnd.nextFloat() * 4.5f,
                        phaseOffset = rnd.nextFloat()
                    )
                }
            }

            Canvas(modifier = modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                embers.forEach { ember ->
                    // Rising from bottom to top
                    val riseProgress = 1f - ((timeProg * ember.speed + ember.phaseOffset) % 1f)
                    val cx = ember.xNorm * w + sin(riseProgress * 14f) * 22f
                    val cy = riseProgress * (h + 40f)

                    val alpha = (sin(riseProgress * Math.PI.toFloat())).coerceIn(0f, 1f) * 0.85f
                    val emberColor = if (ember.size > 5f) Color(0xFFFF5722) else Color(0xFFFFB300)

                    drawCircle(
                        color = emberColor.copy(alpha = alpha),
                        radius = ember.size,
                        center = Offset(cx, cy)
                    )
                    // Core hot spark
                    drawCircle(
                        color = Color(0xFFFFF9C4).copy(alpha = alpha),
                        radius = ember.size * 0.45f,
                        center = Offset(cx, cy)
                    )
                }
            }
        }

        ParticleEffectType.BHAI_DOOJ_ROSE_PETALS -> {
            val petals = remember {
                List(24) { idx ->
                    val rnd = Random(idx * 101)
                    FloatingPetal(
                        xNorm = rnd.nextFloat(),
                        speed = 0.4f + rnd.nextFloat() * 0.5f,
                        radiusX = 7f + rnd.nextFloat() * 6f,
                        radiusY = 12f + rnd.nextFloat() * 8f,
                        color = if (rnd.nextBoolean()) Color(0xFFE91E63) else Color(0xFFFF4081),
                        phaseOffset = rnd.nextFloat()
                    )
                }
            }

            Canvas(modifier = modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                petals.forEach { petal ->
                    val fallProg = (timeProg * petal.speed + petal.phaseOffset) % 1f
                    val cx = petal.xNorm * w + sin(fallProg * 8f) * 28f
                    val cy = fallProg * (h + 60f) - 30f

                    val wobble = cos(fallProg * 10f + petal.phaseOffset * 4f)
                    val rX = petal.radiusX * kotlin.math.abs(wobble).coerceAtLeast(0.3f)

                    drawOval(
                        color = petal.color.copy(alpha = 0.75f),
                        topLeft = Offset(cx - rX, cy - petal.radiusY),
                        size = androidx.compose.ui.geometry.Size(rX * 2f, petal.radiusY * 2f)
                    )
                    // Akshat grain sparkle beside it
                    if (wobble > 0.5f) {
                        drawCircle(
                            color = Color(0xFFFFFDE7).copy(alpha = 0.8f),
                            radius = 2f,
                            center = Offset(cx + 8f, cy - 6f)
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

