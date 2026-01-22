package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunk.singbox.ui.theme.AppBackground
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.Neutral800
import com.kunk.singbox.ui.theme.PureWhite
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    onSplashComplete: () -> Unit
) {
    val scale = remember { Animatable(0.3f) }
    val alpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Logo animation
        scale.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 800,
                easing = FastOutSlowInEasing
            )
        )
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 600)
        )

        // Text fade in
        textAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 500)
        )

        // Wait and navigate
        delay(800)
        onSplashComplete()
    }

    // Start alpha animation immediately
    LaunchedEffect(Unit) {
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 600)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Neutral800.copy(alpha = 0.3f),
                        AppBackground
                    ),
                    radius = 800f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo icon
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale.value)
                    .alpha(alpha.value),
                contentAlignment = Alignment.Center
            ) {
                // Outer ring
                androidx.compose.foundation.Canvas(
                    modifier = Modifier.size(120.dp)
                ) {
                    // Draw outer ring
                    drawCircle(
                        color = PureWhite,
                        radius = size.minDimension / 2 - 4.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                    )

                    // Draw inner decorative ring
                    drawCircle(
                        color = PureWhite.copy(alpha = 0.3f),
                        radius = size.minDimension / 2 - 16.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )
                }

                // S letter in center
                Text(
                    text = "S",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // App name
            Text(
                text = "KunBox",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = PureWhite,
                modifier = Modifier.alpha(textAlpha.value)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Tagline
            Text(
                text = stringResource(R.string.splash_tagline),
                fontSize = 14.sp,
                color = Neutral500,
                modifier = Modifier.alpha(textAlpha.value),
                letterSpacing = 4.sp
            )

            Spacer(modifier = Modifier.height(80.dp))

            // Version
            Text(
                text = "v1.0.0",
                fontSize = 12.sp,
                color = Neutral500.copy(alpha = 0.6f),
                modifier = Modifier.alpha(textAlpha.value)
            )
        }
    }
}
