package com.kunk.singbox.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kunk.singbox.ui.screens.AddNodeScreen
import com.kunk.singbox.ui.screens.DashboardScreen
import com.kunk.singbox.ui.screens.NodeProtocolSelectScreen
import com.kunk.singbox.ui.screens.NodesScreen
import com.kunk.singbox.ui.screens.ProfilesScreen
import com.kunk.singbox.ui.screens.SettingsScreen
// New Screens
import com.kunk.singbox.ui.screens.ProfileEditorScreen
import com.kunk.singbox.ui.screens.NodeDetailScreen
import com.kunk.singbox.ui.screens.RoutingSettingsScreen
import com.kunk.singbox.ui.screens.DnsSettingsScreen
import com.kunk.singbox.ui.screens.TunSettingsScreen
import com.kunk.singbox.ui.screens.DiagnosticsScreen
import com.kunk.singbox.ui.screens.ConnectionSettingsScreen
import com.kunk.singbox.ui.screens.LogsScreen
import com.kunk.singbox.ui.screens.RuleSetsScreen
import com.kunk.singbox.ui.screens.CustomRulesScreen
import com.kunk.singbox.ui.screens.AppRoutingScreen
import com.kunk.singbox.ui.screens.RuleSetHubScreen
import com.kunk.singbox.ui.screens.DomainRulesScreen
import com.kunk.singbox.ui.screens.TrafficStatsScreen
import com.kunk.singbox.ui.screens.ConnectionInfoScreen
import com.kunk.singbox.ui.theme.liquidGlassBackdrop
import com.kunk.singbox.viewmodel.DashboardViewModel
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object Nodes : Screen("nodes")
    object Profiles : Screen("profiles")
    object Settings : Screen("settings")

    // Details & Wizards
    object ProfileEditor : Screen("profile_editor/{profileId}") {
        fun createRoute(profileId: String) = "profile_editor/${Uri.encode(profileId)}"
    }
    object NodeDetail : Screen("node_detail/{nodeId}") {
        fun createRoute(nodeId: String) = "node_detail/$nodeId"
    }
    object NodeCreate : Screen("node_create/{protocol}") {
        fun createRoute(protocol: String) = "node_create/$protocol"
    }
    object CustomProfileNodeCreate : Screen("custom_profile_node_create/{protocol}") {
        fun createRoute(protocol: String) = "custom_profile_node_create/$protocol"
    }
    object NodeAdd : Screen("node_add")
    object NodeProtocolSelect : Screen("node_protocol_select")
    object CustomProfileNodeProtocolSelect : Screen("custom_profile_node_protocol_select")
    object RoutingSettings : Screen("routing_settings")
    object DnsSettings : Screen("dns_settings")
    object TunSettings : Screen("tun_settings")
    object Diagnostics : Screen("diagnostics")
    object Logs : Screen("logs")
    object ConnectionSettings : Screen("connection_settings")
    object RuleSets : Screen("rule_sets")
    object CustomRules : Screen("custom_rules")
    object DomainRules : Screen("domain_rules")
    object AppRouting : Screen("app_rules")
    object RuleSetHub : Screen("rule_set_hub")
    object TrafficStats : Screen("traffic_stats")
    object ConnectionInfo : Screen("connection_info")
}

const val NAV_ANIMATION_DURATION = 450

private fun tabIndex(route: String?): Int {
    val tab = getTabForRoute(route)
    return when (tab) {
        Screen.Dashboard.route -> 0
        Screen.Nodes.route -> 1
        Screen.Profiles.route -> 2
        Screen.Settings.route -> 3
        else -> 0
    }
}

@Composable
private fun TopLevelPage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .liquidGlassBackdrop()
    ) {
        content()
    }
}

// Map sub-routes to their parent tab
fun getTabForRoute(route: String?): String {
    if (route == null) return Screen.Dashboard.route
    return when {
        route == Screen.Dashboard.route -> Screen.Dashboard.route

        route == Screen.Nodes.route -> Screen.Nodes.route
        route.startsWith("node_detail") -> Screen.Nodes.route
        route.startsWith("node_create") -> Screen.Nodes.route
        route == Screen.NodeAdd.route -> Screen.Nodes.route
        route == Screen.NodeProtocolSelect.route -> Screen.Nodes.route

        route == Screen.Profiles.route -> Screen.Profiles.route
        route.startsWith("profile_editor") -> Screen.Profiles.route
        route.startsWith("custom_profile_node_") -> Screen.Profiles.route

        route == Screen.Settings.route -> Screen.Settings.route
        route == Screen.RoutingSettings.route -> Screen.Settings.route
        route == Screen.DnsSettings.route -> Screen.Settings.route
        route == Screen.TunSettings.route -> Screen.Settings.route
        route == Screen.ConnectionSettings.route -> Screen.Settings.route
        route == Screen.RuleSets.route -> Screen.Settings.route
        route == Screen.CustomRules.route -> Screen.Settings.route
        route == Screen.DomainRules.route -> Screen.Settings.route
        route == Screen.AppRouting.route -> Screen.Settings.route
        route == Screen.RuleSetHub.route -> Screen.Settings.route
        route == Screen.Diagnostics.route -> Screen.Settings.route
        route == Screen.Logs.route -> Screen.Settings.route
        route == Screen.TrafficStats.route -> Screen.Settings.route
        route == Screen.ConnectionInfo.route -> Screen.Settings.route

        else -> Screen.Dashboard.route
    }
}

@Suppress("LongMethod", "CognitiveComplexMethod")
@Composable
fun AppNavigation(
    navController: NavHostController,
    dashboardViewModel: DashboardViewModel,
    dashboardBottomContentPadding: Dp = 0.dp,
    topLevelBottomContentPadding: Dp = 0.dp
) {
    val slideSpec = tween<IntOffset>(
        durationMillis = NAV_ANIMATION_DURATION,
        easing = FastOutSlowInEasing
    )
    val fadeSpec = tween<Float>(
        durationMillis = NAV_ANIMATION_DURATION,
        easing = FastOutSlowInEasing
    )

    val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        slideInHorizontally(initialOffsetX = { it / 8 }, animationSpec = slideSpec) + fadeIn(animationSpec = fadeSpec)
    }
    val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        fadeOut(animationSpec = fadeSpec)
    }
    val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        fadeIn(animationSpec = fadeSpec)
    }
    val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        slideOutHorizontally(targetOffsetX = { it / 8 }, animationSpec = slideSpec) + fadeOut(animationSpec = fadeSpec)
    }

    val tabEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val fromRoute = initialState.destination.route
        val toRoute = targetState.destination.route
        val fromIndex = tabIndex(fromRoute)
        val toIndex = tabIndex(toRoute)
        if (toIndex > fromIndex) {
            slideInHorizontally(
                initialOffsetX = { it / 8 },
                animationSpec = slideSpec
            ) + fadeIn(animationSpec = fadeSpec)
        } else {
            slideInHorizontally(
                initialOffsetX = { -it / 8 },
                animationSpec = slideSpec
            ) + fadeIn(animationSpec = fadeSpec)
        }
    }

    val tabExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        val fromRoute = initialState.destination.route
        val toRoute = targetState.destination.route
        val fromTab = getTabForRoute(fromRoute)
        val toTab = getTabForRoute(toRoute)

        if (fromTab == toTab) {
            fadeOut(animationSpec = fadeSpec)
        } else {
            val fromIndex = tabIndex(fromRoute)
            val toIndex = tabIndex(toRoute)
            if (toIndex > fromIndex) {
                slideOutHorizontally(
                    targetOffsetX = { -it / 8 },
                    animationSpec = slideSpec
                ) + fadeOut(animationSpec = fadeSpec)
            } else {
                slideOutHorizontally(
                    targetOffsetX = { it / 8 },
                    animationSpec = slideSpec
                ) + fadeOut(animationSpec = fadeSpec)
            }
        }
    }

    val tabPopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val fromRoute = initialState.destination.route
        val toRoute = targetState.destination.route
        val fromTab = getTabForRoute(fromRoute)
        val toTab = getTabForRoute(toRoute)

        if (fromTab == toTab) {
            fadeIn(animationSpec = fadeSpec)
        } else {
            val fromIndex = tabIndex(fromRoute)
            val toIndex = tabIndex(toRoute)
            if (toIndex > fromIndex) {
                slideInHorizontally(
                    initialOffsetX = { it / 8 },
                    animationSpec = slideSpec
                ) + fadeIn(animationSpec = fadeSpec)
            } else {
                slideInHorizontally(
                    initialOffsetX = { -it / 8 },
                    animationSpec = slideSpec
                ) + fadeIn(animationSpec = fadeSpec)
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Dashboard.route
    ) {
        // Main Tabs
        composable(
            route = Screen.Dashboard.route,
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = tabPopEnterTransition,
            popExitTransition = tabExitTransition
        ) {
            TopLevelPage {
                DashboardScreen(
                    navController = navController,
                    viewModel = dashboardViewModel,
                    bottomContentPadding = dashboardBottomContentPadding
                )
            }
        }
        composable(
            route = Screen.Nodes.route,
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = tabPopEnterTransition,
            popExitTransition = tabExitTransition
        ) {
            TopLevelPage {
                NodesScreen(
                    navController = navController,
                    bottomContentPadding = topLevelBottomContentPadding
                )
            }
        }
        composable(
            route = Screen.Profiles.route,
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = tabPopEnterTransition,
            popExitTransition = tabExitTransition
        ) {
            TopLevelPage {
                ProfilesScreen(
                    navController = navController,
                    bottomContentPadding = topLevelBottomContentPadding
                )
            }
        }
        composable(
            route = Screen.Settings.route,
            enterTransition = tabEnterTransition,
            exitTransition = tabExitTransition,
            popEnterTransition = tabPopEnterTransition,
            popExitTransition = tabExitTransition
        ) {
            TopLevelPage {
                SettingsScreen(
                    navController = navController,
                    bottomContentPadding = topLevelBottomContentPadding
                )
            }
        }

        // Sub Screens
        composable(
            route = Screen.ProfileEditor.route,
            arguments = listOf(
                androidx.navigation.navArgument("profileId") { type = androidx.navigation.NavType.StringType }
            ),
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { backStackEntry ->
            val profileId = backStackEntry.arguments?.getString("profileId") ?: ""
            ProfileEditorScreen(navController, profileId)
        }

        composable(
            route = Screen.NodeDetail.route,
            arguments = listOf(
                androidx.navigation.navArgument("nodeId") { type = androidx.navigation.NavType.StringType }
            ),
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { backStackEntry ->
            val nodeId = backStackEntry.arguments?.getString("nodeId") ?: ""
            NodeDetailScreen(navController = navController, nodeId = nodeId)
        }
        composable(
            route = Screen.NodeCreate.route,
            arguments = listOf(
                androidx.navigation.navArgument("protocol") { type = androidx.navigation.NavType.StringType }
            ),
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { backStackEntry ->
            val protocol = backStackEntry.arguments?.getString("protocol") ?: "vmess"
            NodeDetailScreen(navController = navController, nodeId = "", createProtocol = protocol)
        }
        composable(
            route = Screen.CustomProfileNodeCreate.route,
            arguments = listOf(
                androidx.navigation.navArgument("protocol") { type = androidx.navigation.NavType.StringType }
            ),
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { backStackEntry ->
            val protocol = backStackEntry.arguments?.getString("protocol") ?: "vmess"
            val profilesEntry = remember(backStackEntry) {
                navController.getBackStackEntry(Screen.Profiles.route)
            }
            val profilesViewModel = viewModel<ProfilesViewModel>(profilesEntry)
            NodeDetailScreen(
                navController = navController,
                nodeId = "",
                createProtocol = protocol,
                onCreateDraft = { outbound ->
                    profilesViewModel.addCustomDraftOutbound(outbound)
                    navController.popBackStack(Screen.Profiles.route, false)
                }
            )
        }
        composable(
            route = Screen.NodeAdd.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { backStackEntry ->
            val nodesEntry = remember(backStackEntry) {
                navController.getBackStackEntry(Screen.Nodes.route)
            }
            AddNodeScreen(
                navController = navController,
                viewModel = viewModel<NodesViewModel>(nodesEntry)
            )
        }
        composable(
            route = Screen.NodeProtocolSelect.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) {
            NodeProtocolSelectScreen(navController = navController)
        }
        composable(
            route = Screen.CustomProfileNodeProtocolSelect.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) {
            NodeProtocolSelectScreen(
                navController = navController,
                onProtocolSelected = { protocol ->
                    navController.navigate(Screen.CustomProfileNodeCreate.createRoute(protocol)) {
                        popUpTo(Screen.CustomProfileNodeProtocolSelect.route) {
                            inclusive = true
                        }
                    }
                }
            )
        }
        composable(
            route = Screen.RoutingSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { RoutingSettingsScreen(navController) }
        composable(
            route = Screen.DnsSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { DnsSettingsScreen(navController) }
        composable(
            route = Screen.TunSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { TunSettingsScreen(navController) }
        composable(
            route = Screen.Diagnostics.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { DiagnosticsScreen(navController) }
        composable(
            route = Screen.Logs.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { LogsScreen(navController) }
        composable(
            route = Screen.ConnectionSettings.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { ConnectionSettingsScreen(navController) }
        composable(
            route = Screen.RuleSets.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { RuleSetsScreen(navController) }
        composable(
            route = Screen.CustomRules.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { CustomRulesScreen(navController) }
        composable(
            route = Screen.DomainRules.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { DomainRulesScreen(navController) }
        composable(
            route = Screen.AppRouting.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { AppRoutingScreen(navController) }
        composable(
            route = Screen.RuleSetHub.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { RuleSetHubScreen(navController) }
        composable(
            route = Screen.TrafficStats.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { TrafficStatsScreen(navController) }
        composable(
            route = Screen.ConnectionInfo.route,
            enterTransition = enterTransition,
            exitTransition = exitTransition,
            popEnterTransition = popEnterTransition,
            popExitTransition = popExitTransition
        ) { ConnectionInfoScreen(navController) }
    }
}
