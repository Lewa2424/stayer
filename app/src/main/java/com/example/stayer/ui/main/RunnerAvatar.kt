package com.example.stayer.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private enum class RunnerMotionState {
    Idle,
    Running,
    Paused
}

/**
 * Анимированный персонаж для центральной кнопки старта.
 * Animated runner avatar for the central action button.
 */
@Composable
fun RunnerAvatar(
    isRunning: Boolean,
    isPaused: Boolean,
    modifier: Modifier = Modifier,
    primaryTint: Color = Color(0xFF163A70),
) {
    val state = when {
        isPaused -> RunnerMotionState.Paused
        isRunning -> RunnerMotionState.Running
        else -> RunnerMotionState.Idle
    }
    val transition = rememberInfiniteTransition(label = "runner_avatar")

    val motionX: Float
    val motionY: Float
    val rotation: Float
    val avatarScaleX: Float
    val avatarScaleY: Float
    val glowAlpha: Float
    val trailShift: Float
    val highlightAlpha: Float

    when (state) {
        RunnerMotionState.Idle -> {
            val pose by transition.animateFloat(
                initialValue = -4f,
                targetValue = -4f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 3600
                        -4f at 0
                        5f at 950
                        -1.5f at 1900
                        4f at 2750
                        -4f at 3600
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "runner_idle_pose"
            )
            val breathe by transition.animateFloat(
                initialValue = 0.98f,
                targetValue = 1.04f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "runner_idle_breathe"
            )
            motionX = pose * 0.16f
            motionY = -abs(pose) * 0.2f
            rotation = pose
            avatarScaleX = breathe
            avatarScaleY = breathe
            glowAlpha = 0.62f
            trailShift = 2.5f
            highlightAlpha = 0.2f
        }

        RunnerMotionState.Running -> {
            val stride by transition.animateFloat(
                initialValue = -1f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 320, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "runner_running_stride"
            )
            motionX = stride * 2.5f
            motionY = -abs(stride) * 5.5f
            rotation = -10f + (stride * 9f)
            avatarScaleX = 1f + (abs(stride) * 0.08f)
            avatarScaleY = 1f - (abs(stride) * 0.06f)
            glowAlpha = 0.95f
            trailShift = if (stride >= 0f) -8f else 8f
            highlightAlpha = 0.3f
        }

        RunnerMotionState.Paused -> {
            val pace by transition.animateFloat(
                initialValue = -10f,
                targetValue = -10f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1500
                        -10f at 0
                        10f at 700
                        -10f at 1500
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "runner_paused_pace"
            )
            val impatience by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1500
                        0f at 0
                        1f at 250
                        0.2f at 500
                        1f at 900
                        0f at 1500
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "runner_paused_impatience"
            )
            motionX = pace
            motionY = -impatience * 2.4f
            rotation = if (pace >= 0f) 6f else -6f
            avatarScaleX = if (pace >= 0f) 1.02f else -1.02f
            avatarScaleY = 1f - (impatience * 0.04f)
            glowAlpha = 0.75f
            trailShift = if (pace >= 0f) -4f else 4f
            highlightAlpha = 0.24f
        }
    }

    Box(
        modifier = modifier.size(38.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .alpha(glowAlpha)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x40FF6B00),
                            Color(0x260052FF),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.DirectionsRun,
            contentDescription = null,
            tint = Color(0x55FF6B00),
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer {
                    translationX = trailShift
                    translationY = 2f
                    rotationZ = rotation
                    scaleX = avatarScaleX * 1.08f
                    scaleY = avatarScaleY * 1.08f
                }
        )

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.DirectionsRun,
            contentDescription = null,
            tint = Color(0x660052FF),
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer {
                    translationX = trailShift * 0.55f
                    translationY = 1f
                    rotationZ = rotation
                    scaleX = avatarScaleX * 1.04f
                    scaleY = avatarScaleY * 1.04f
                }
        )

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.DirectionsRun,
            contentDescription = null,
            tint = primaryTint,
            modifier = Modifier
                .size(32.dp)
                .graphicsLayer {
                    translationX = motionX
                    translationY = motionY
                    rotationZ = rotation
                    scaleX = avatarScaleX
                    scaleY = avatarScaleY
                }
        )

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.DirectionsRun,
            contentDescription = null,
            tint = Color.White.copy(alpha = highlightAlpha),
            modifier = Modifier
                .size(30.dp)
                .graphicsLayer {
                    translationX = motionX - 0.6f
                    translationY = motionY - 0.6f
                    rotationZ = rotation
                    scaleX = avatarScaleX
                    scaleY = avatarScaleY
                }
        )
    }
}
