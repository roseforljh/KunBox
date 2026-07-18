package com.kunk.singbox.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kunk.singbox.model.AppThemeStyle
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.navigation.getTabForRoute

private val liquidGlassButtonShape = RoundedCornerShape(percent = 50)
private val liquidGlassNavCapsuleHeight = 60.dp
private val liquidGlassNavItemMinTouchSize = 56.dp
private val liquidGlassSelectedIndicatorSize = 44.dp

private data class LiquidGlassNavColors(
    val capsuleBorderColor: Color,
    val selectedIconColor: Color,
    val unselectedIconColor: Color
)

private data class LiquidGlassNavMetrics(
    val scale: Float,
    val iconSize: Dp
)

private data class LiquidGlassSelectedButtonBrushAlphas(
    val top: Float,
    val middle: Float,
    val bottom: Float
)

@Composable
fun AppNavBar(
    navController: NavController,
    themeStyle: AppThemeStyle = AppThemeStyle.DEFAULT
) {
    val items = listOf(
        Screen.Dashboard,
        Screen.Nodes,
        Screen.Profiles,
        Screen.Settings
    )

    when (themeStyle) {
        AppThemeStyle.DEFAULT -> DefaultAppNavBar(navController = navController, items = items)
        AppThemeStyle.LIQUID_GLASS -> LiquidGlassAppNavBar(navController = navController, items = items)
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
    items: List<Screen>
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
            isDark = isDark,
            selectedIndex = selectedIndex,
            itemCount = items.size
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
    isDark: Boolean,
    selectedIndex: Int,
    itemCount: Int,
    content: @Composable () -> Unit
) {
    val colors = liquidGlassNavColors(isDark = isDark)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(liquidGlassNavCapsuleHeight)
            .shadow(elevation = 14.dp, shape = liquidGlassButtonShape, clip = false)
            .clip(liquidGlassButtonShape)
            .background(brush = liquidGlassCapsuleBrush(isDark = isDark))
            .border(BorderStroke(1.dp, colors.capsuleBorderColor), liquidGlassButtonShape)
            .consumeUnclaimedClicks()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlassSelectedIndicator(
            isDark = isDark,
            selectedIndex = selectedIndex,
            itemCount = itemCount,
            maxWidth = maxWidth
        )
        content()
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
    isDark: Boolean,
    selectedIndex: Int,
    itemCount: Int,
    maxWidth: Dp
) {
    val targetOffset = liquidGlassSelectedIndicatorOffset(
        selectedIndex = selectedIndex,
        itemCount = itemCount,
        maxWidth = maxWidth
    )
    val indicatorOffset = targetOffset

    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .offset(x = indicatorOffset)
            .size(liquidGlassSelectedIndicatorSize)
            .clip(liquidGlassButtonShape)
            .background(brush = liquidGlassSelectedButtonBrush(isDark = isDark))
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
        animationSpec = spring(stiffness = 520f, dampingRatio = 0.72f),
        label = "${screen.route}_liquid_glass_nav_scale"
    )
    val iconSize by animateDpAsState(
        targetValue = if (isSelected) 23.dp else 21.dp,
        animationSpec = spring(stiffness = 460f, dampingRatio = 0.82f),
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
    return (slotWidth * selectedSlotIndex.toFloat()) + ((slotWidth - liquidGlassSelectedIndicatorSize) / 2f)
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
        capsuleBorderColor = if (isDark) {
            Color.White.copy(alpha = 0.14f)
        } else {
            Color.White.copy(alpha = 0.62f)
        },
        selectedIconColor = MaterialTheme.colorScheme.onBackground,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.86f else 0.72f)
    )
}

@Composable
private fun liquidGlassCapsuleBrush(isDark: Boolean): Brush {
    return Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = if (isDark) 0.14f else 0.48f),
            MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.48f else 0.58f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isDark) 0.24f else 0.34f)
        )
    )
}

@Composable
private fun liquidGlassSelectedButtonBrush(isDark: Boolean): Brush {
    val alphas = liquidGlassSelectedButtonBrushAlphas(isDark = isDark)

    return Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = alphas.top),
            Color.White.copy(alpha = alphas.middle),
            Color.White.copy(alpha = alphas.bottom)
        )
    )
}

private fun liquidGlassSelectedButtonBrushAlphas(
    isDark: Boolean
): LiquidGlassSelectedButtonBrushAlphas {
    return if (isDark) {
        LiquidGlassSelectedButtonBrushAlphas(top = 0.24f, middle = 0.16f, bottom = 0.10f)
    } else {
        LiquidGlassSelectedButtonBrushAlphas(top = 0.78f, middle = 0.48f, bottom = 0.26f)
    }
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
            restoreState = screen != Screen.Settings
        }
    } else if (screen == Screen.Settings) {
        navController.popBackStack(Screen.Settings.route, false)
    }
}
