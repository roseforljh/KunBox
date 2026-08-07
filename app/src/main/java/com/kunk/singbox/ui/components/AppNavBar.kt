package com.kunk.singbox.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kunk.singbox.model.AppThemeStyle
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.navigation.getTabForRoute
import com.kunk.singbox.ui.theme.hollowShadow
import com.kunk.singbox.ui.theme.liquidGlassMaterial
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.vibrancy

private val liquidGlassButtonShape = RoundedCornerShape(percent = 50)
private val liquidGlassNavCapsuleHeight = 64.dp
private val liquidGlassNavItemMinTouchSize = 56.dp
private val liquidGlassSelectedIndicatorSize = 44.dp

private const val LIQUID_NAV_REFRACTION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float edgeRefraction;
uniform float centerRefraction;

half4 main(float2 coord) {
    float halfHeight = max(size.y * 0.5, 1.0);
    float centeredY = coord.y - halfHeight;
    float edgeProximity = clamp(abs(centeredY) / halfHeight, 0.0, 1.0);

    float verticalWeight = smoothstep(0.0, 0.75, edgeProximity);
    float distanceToEdge = max(halfHeight - abs(centeredY), 0.0);
    float verticalAmount = min(edgeRefraction * verticalWeight, distanceToEdge * 0.92);
    float verticalOffset = sign(centeredY) * verticalAmount;

    float centerWeight = 1.0 - smoothstep(0.25, 0.75, edgeProximity);
    float horizontalOffset = centerRefraction * centerWeight;
    float2 refractedCoord = coord + float2(horizontalOffset, verticalOffset);
    return content.eval(clamp(refractedCoord, float2(0.0), size - float2(1.0)));
}
"""

private data class LiquidGlassNavColors(
    val selectedIconColor: Color,
    val unselectedIconColor: Color
)

private data class LiquidGlassNavMetrics(
    val scale: Float,
    val iconSize: Dp
)

@Composable
fun AppNavBar(
    navController: NavController,
    themeStyle: AppThemeStyle = AppThemeStyle.DEFAULT,
    backdrop: Backdrop? = null
) {
    val items = listOf(
        Screen.Dashboard,
        Screen.Nodes,
        Screen.Profiles,
        Screen.Settings
    )

    when (themeStyle) {
        AppThemeStyle.DEFAULT -> DefaultAppNavBar(navController = navController, items = items)
        AppThemeStyle.LIQUID_GLASS -> LiquidGlassAppNavBar(
            navController = navController,
            items = items,
            backdrop = backdrop
        )
    }
}

@Composable
private fun DefaultAppNavBar(
    navController: NavController,
    items: List<Screen>
) {
    val gradientColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val rawPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val safeBottomPadding = if (rawPadding < 12.dp) 0.dp else (rawPadding - 12.dp).coerceAtLeast(0.dp)

    Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        DefaultNavDivider(gradientColor = gradientColor)

        NavigationBar(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.height(64.dp)
        ) {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            items.forEach { screen ->
                val isSelected = getTabForRoute(currentRoute) == screen.route
                DefaultNavItem(
                    screen = screen,
                    isSelected = isSelected,
                    onClick = {
                        navigateToTab(
                            navController = navController,
                            currentRoute = currentRoute,
                            screen = screen
                        )
                    }
                )
            }
        }

        androidx.compose.foundation.layout.Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(safeBottomPadding)
        )
    }
}

@Composable
private fun DefaultNavDivider(gradientColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        gradientColor,
                        gradientColor,
                        Color.Transparent
                    ),
                    startX = 0f,
                    endX = Float.POSITIVE_INFINITY
                )
            )
    )
}

@Composable
private fun RowScope.DefaultNavItem(
    screen: Screen,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    NavigationBarItem(
        icon = {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = navIcon(screen = screen, isSelected = isSelected),
                    contentDescription = screen.route,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        label = null,
        selected = isSelected,
        onClick = onClick,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onBackground,
            indicatorColor = Color.Transparent,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun LiquidGlassAppNavBar(
    navController: NavController,
    items: List<Screen>,
    backdrop: Backdrop?
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val selectedRoute = getTabForRoute(currentRoute)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val rawPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val safeBottomPadding = if (rawPadding < 12.dp) 12.dp else rawPadding
    val selectedIndex = items.indexOfFirst { screen -> selectedRoute == screen.route }.coerceAtLeast(0)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp + safeBottomPadding)
            .padding(start = 28.dp, top = 6.dp, end = 28.dp, bottom = 12.dp + safeBottomPadding),
        contentAlignment = Alignment.BottomCenter
    ) {
        LiquidGlassCapsule(
            selectedIndex = selectedIndex,
            itemCount = items.size,
            backdrop = backdrop
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { screen ->
                    val isSelected = selectedRoute == screen.route
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        LiquidGlassNavItem(
                            screen = screen,
                            isSelected = isSelected,
                            isDark = isDark,
                            onClick = {
                                navigateToTab(
                                    navController = navController,
                                    currentRoute = currentRoute,
                                    screen = screen
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassCapsule(
    selectedIndex: Int,
    itemCount: Int,
    backdrop: Backdrop?,
    content: @Composable () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(liquidGlassNavCapsuleHeight)
            .hollowShadow(
                shape = liquidGlassButtonShape,
                color = Color.Black,
                alpha = if (isDark) 0.20f else 0.10f,
                blurRadius = 10.dp,
                offsetY = 4.dp
            )
            .clip(liquidGlassButtonShape)
            .consumeUnclaimedClicks(),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlassRefractionOverlay(
            backdrop = backdrop
        )
        BoxWithConstraints(
            modifier = Modifier
                .matchParentSize()
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            LiquidGlassSelectedIndicator(
                selectedIndex = selectedIndex,
                itemCount = itemCount,
                maxWidth = maxWidth
            )
            content()
        }
    }
}

@Composable
private fun BoxScope.LiquidGlassRefractionOverlay(
    backdrop: Backdrop?
) {
    val surfaceTint = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.20f)
    val backdropModifier = if (backdrop == null) {
        Modifier.background(surfaceTint, liquidGlassButtonShape)
    } else {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { liquidGlassButtonShape },
            effects = {
                vibrancy()
                blur(0.5.dp.toPx())
                liquidNavLens(
                    edgeRefraction = 16.dp.toPx(),
                    centerRefraction = 6.dp.toPx()
                )
            },
            shadow = null,
            onDrawSurface = { drawRect(surfaceTint) }
        )
    }

    Box(
        modifier = Modifier
            .matchParentSize()
            .then(backdropModifier)
            .liquidGlassMaterial(
                shape = liquidGlassButtonShape,
                accented = false,
                backdropVisible = backdrop != null
            )
    )
}

private fun BackdropEffectScope.liquidNavLens(
    edgeRefraction: Float,
    centerRefraction: Float
) {
    runtimeShaderEffect(
        key = "LiquidNavRefraction",
        shaderString = LIQUID_NAV_REFRACTION_SHADER,
        uniformShaderName = "content"
    ) {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("edgeRefraction", edgeRefraction)
        setFloatUniform("centerRefraction", centerRefraction)
    }
}

private fun Modifier.consumeUnclaimedClicks(): Modifier {
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = true,
                pass = PointerEventPass.Main
            )
            down.consume()
            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                event.changes.forEach { change ->
                    if (!change.isConsumed) change.consume()
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

@Composable
private fun BoxScope.LiquidGlassSelectedIndicator(
    selectedIndex: Int,
    itemCount: Int,
    maxWidth: Dp
) {
    if (itemCount <= 0) return

    val targetOffset = liquidGlassSelectedIndicatorOffset(
        selectedIndex = selectedIndex,
        itemCount = itemCount,
        maxWidth = maxWidth
    )
    val indicatorOffset by animateDpAsState(
        targetValue = targetOffset,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "liquid_glass_nav_indicator_offset"
    )
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
            .size(liquidGlassSelectedIndicatorSize)
            .liquidGlassMaterial(
                shape = CircleShape,
                selected = true,
                accented = false,
                backdropVisible = true
            )
    )
}

@Composable
private fun LiquidGlassNavItem(
    screen: Screen,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = liquidGlassNavColors(isDark = isDark)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val metrics = rememberLiquidGlassNavMetrics(
        screen = screen,
        isSelected = isSelected,
        isPressed = isPressed
    )
    val iconTint = if (isSelected) colors.selectedIconColor else colors.unselectedIconColor

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(liquidGlassNavItemMinTouchSize)
            .graphicsLayer {
                scaleX = metrics.scale
                scaleY = metrics.scale
            }
            .clip(liquidGlassButtonShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = navIcon(screen = screen, isSelected = isSelected),
            contentDescription = screen.route,
            tint = iconTint,
            modifier = Modifier.size(metrics.iconSize)
        )
    }
}

@Composable
private fun rememberLiquidGlassNavMetrics(
    screen: Screen,
    isSelected: Boolean,
    isPressed: Boolean
): LiquidGlassNavMetrics {
    val scale by animateFloatAsState(
        targetValue = liquidGlassScaleTarget(isSelected = isSelected, isPressed = isPressed),
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "${screen.route}_liquid_glass_nav_scale"
    )
    val iconSize by animateDpAsState(
        targetValue = if (isSelected) 23.dp else 21.dp,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "${screen.route}_liquid_glass_nav_icon_size"
    )
    return LiquidGlassNavMetrics(
        scale = scale,
        iconSize = iconSize
    )
}

private fun liquidGlassSelectedIndicatorOffset(
    selectedIndex: Int,
    itemCount: Int,
    maxWidth: Dp
): Dp {
    if (itemCount <= 0) {
        return 0.dp
    }
    val selectedSlotIndex = selectedIndex.coerceIn(0, itemCount - 1)
    val slotWidth = maxWidth / itemCount.toFloat()
    return (slotWidth * selectedSlotIndex.toFloat()) +
        ((slotWidth - liquidGlassSelectedIndicatorSize) / 2f)
}

private fun liquidGlassScaleTarget(isSelected: Boolean, isPressed: Boolean): Float {
    return when {
        isPressed -> 0.90f
        isSelected -> 1.04f
        else -> 1f
    }
}

@Composable
private fun liquidGlassNavColors(isDark: Boolean): LiquidGlassNavColors {
    return LiquidGlassNavColors(
        selectedIconColor = MaterialTheme.colorScheme.primary,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.86f else 0.72f)
    )
}

private fun navIcon(screen: Screen, isSelected: Boolean): ImageVector {
    return if (isSelected) {
        when (screen) {
            Screen.Dashboard -> Icons.Filled.Dashboard
            Screen.Nodes -> Icons.Filled.Dns
            Screen.Profiles -> Icons.Filled.FolderCopy
            Screen.Settings -> Icons.Filled.Settings
            else -> Icons.Filled.Dashboard
        }
    } else {
        when (screen) {
            Screen.Dashboard -> Icons.Outlined.Dashboard
            Screen.Nodes -> Icons.Outlined.Dns
            Screen.Profiles -> Icons.Outlined.FolderCopy
            Screen.Settings -> Icons.Outlined.Settings
            else -> Icons.Outlined.Dashboard
        }
    }
}

private fun navigateToTab(
    navController: NavController,
    currentRoute: String?,
    screen: Screen
) {
    val currentTab = getTabForRoute(currentRoute)
    if (currentTab != screen.route) {
        navController.navigate(screen.route) {
            popUpTo(Screen.Dashboard.route) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    } else if (screen == Screen.Settings) {
        navController.popBackStack(Screen.Settings.route, false)
    }
}
