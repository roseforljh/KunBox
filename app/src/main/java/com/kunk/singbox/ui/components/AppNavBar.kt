package com.kunk.singbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.navigation.getTabForRoute
import androidx.compose.material3.MaterialTheme

@Composable
fun AppNavBar(
    navController: NavController
) {
    val items = listOf(
        Screen.Dashboard,
        Screen.Nodes,
        Screen.Profiles,
        Screen.Settings
    )

    val gradientColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)

    Column {
        // Top gradient line (from center to edges)
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

        NavigationBar(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.height(64.dp) // Reduced height
        ) {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            items.forEach { screen ->
                val isSelected = getTabForRoute(currentRoute) == screen.route

                NavigationBarItem(
                    icon = {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isSelected) {
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
                                },
                                contentDescription = screen.route,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    label = null, // Removed text label
                    selected = isSelected,
                    onClick = {
                        val currentTab = getTabForRoute(currentRoute)
                        // Only navigate if switching to a different tab
                        if (currentTab != screen.route) {
                            navController.navigate(screen.route) {
                                popUpTo(Screen.Dashboard.route) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = screen != Screen.Settings
                            }
                        } else if (screen == Screen.Settings) {
                            // If user taps Settings again while inside a Settings sub-screen, return to Settings root.
                            navController.popBackStack(Screen.Settings.route, false)
                        }
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onBackground,
                        indicatorColor = Color.Transparent, // No pill indicator
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}
