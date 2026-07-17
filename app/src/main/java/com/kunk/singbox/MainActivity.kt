package com.kunk.singbox

import android.content.Intent
import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.viewmodel.DashboardViewModel
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.AppThemeStyle
import com.kunk.singbox.model.AppLanguage
import com.kunk.singbox.utils.LocaleHelper
import com.kunk.singbox.utils.DeepLinkHandler
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.AppNavBar
import com.kunk.singbox.ui.navigation.AppNavigation
import com.kunk.singbox.ui.theme.SingBoxTheme
import android.content.ComponentName
import android.service.quicksettings.TileService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.kunk.singbox.ui.scanner.QrScannerActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.concurrent.atomic.AtomicLong

private data class MainIntentEvent(
    val id: Long,
    val intent: Intent
)

private object MainIntentEvents {
    private val nextIntentEventId = AtomicLong()
    private val _events = MutableStateFlow<MainIntentEvent?>(null)
    val events: StateFlow<MainIntentEvent?> = _events

    fun emit(intent: Intent) {
        _events.value = MainIntentEvent(
            id = nextIntentEventId.incrementAndGet(),
            intent = intent
        )
    }
}

class MainActivity : ComponentActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        MainIntentEvents.emit(intent)
    }

    override fun attachBaseContext(newBase: Context) {

        val prefs = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val languageName = prefs.getString("app_language_cache", null)
        val language = if (languageName != null) {
            try {
                AppLanguage.valueOf(languageName)
            } catch (e: Exception) {
                AppLanguage.SYSTEM
            }
        } else {
            AppLanguage.SYSTEM
        }

        val context = LocaleHelper.wrap(newBase, language)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        MainIntentEvents.emit(intent)
        setContent {
            SingBoxApp()
        }

        // 不在 onCreate 中无条件取消后台配置/规则集自动更新，避免用户频繁打开应用导致定时任务失效
        // // cancelRuleSetUpdateWork()
    }
}

@Composable
fun SingBoxApp() {
    val context = LocalContext.current
    val restartNeededMessage = stringResource(R.string.settings_restart_needed)

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
        }
    )

    LaunchedEffect(Unit) {
        SingBoxRemote.ensureBound(context)
        // Best-effort: ask system to refresh QS tile state after app process restarts/force-stops.
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, VpnTileService::class.java))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)

            if (permission != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val dashboardViewModel: DashboardViewModel = viewModel()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        dashboardViewModel.refreshState()
    }

    LaunchedEffect(settings.appLanguage) {
        val language = settings.appLanguage
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("app_language_cache", language.name).apply()
    }

    val isVpnRunningForUpdate by SingBoxRemote.isRunning.collectAsStateWithLifecycle()
    var updateChecked by remember { mutableStateOf(false) }

    LaunchedEffect(settings.autoCheckUpdate, isVpnRunningForUpdate) {
        if (!settings.autoCheckUpdate || updateChecked) return@LaunchedEffect

        if (isVpnRunningForUpdate) {

            kotlinx.coroutines.delay(1000L)
            updateChecked = true
            com.kunk.singbox.utils.AppUpdateChecker.checkAndNotify(context)
        }
    }

    LaunchedEffect(settings.autoCheckUpdate) {
        if (!settings.autoCheckUpdate) return@LaunchedEffect
        kotlinx.coroutines.delay(10000L)
        if (!updateChecked) {
            updateChecked = true
            com.kunk.singbox.utils.AppUpdateChecker.checkAndNotify(context)
        }
    }

    // Handle App Shortcuts - need navController reference
    var pendingNavigation by remember { mutableStateOf<String?>(null) }
    val intentEvent by MainIntentEvents.events.collectAsStateWithLifecycle()

    LaunchedEffect(intentEvent?.id) {
        val intent = intentEvent?.intent ?: return@LaunchedEffect
        when (intent.action) {
            "com.kunk.singbox.action.SCAN" -> {
                val scanIntent = android.content.Intent(context, QrScannerActivity::class.java)
                context.startActivity(scanIntent)
                intent.action = null
            }
            "com.kunk.singbox.action.SWITCH_NODE" -> {
                pendingNavigation = "nodes"
                intent.action = null
            }
            android.content.Intent.ACTION_VIEW -> {

                intent.data?.let { uri ->
                    val scheme = uri.scheme
                    val host = uri.host

                    if ((scheme == "singbox" || scheme == "kunbox") && host == "install-config") {
                        val url = uri.getQueryParameter("url")
                        val name = uri.getQueryParameter("name") ?: "Imported Subscription"
                        val intervalStr = uri.getQueryParameter("interval")
                        val interval = intervalStr?.toIntOrNull() ?: 0

                        if (!url.isNullOrBlank()) {
                            DeepLinkHandler.setPendingSubscriptionImport(name, url, interval)

                            pendingNavigation = "profiles"
                        }
                    }
                }
                intent.data = null
            }
        }
    }
    val connectionState by dashboardViewModel.connectionState.collectAsStateWithLifecycle()
    val isRunning by SingBoxRemote.isRunning.collectAsStateWithLifecycle()
    val isStarting by SingBoxRemote.isStarting.collectAsStateWithLifecycle()
    // 每次进入主界面只尝试一次，避免手动断开后同会话立刻重连
    var autoConnectAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning, isStarting) {

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.kunk.singbox.utils.NetworkClient.clearConnectionPool()
        }
    }

    LaunchedEffect(settings.autoConnect, connectionState, isRunning, isStarting) {
        if (autoConnectAttempted || !settings.autoConnect) return@LaunchedEffect

        // 必须先取得 AIDL 实时状态，禁止用冷启动默认 STOPPED 误触发二次点火
        SingBoxRemote.ensureBound(context)
        repeat(5) {
            if (SingBoxRemote.isBound()) return@repeat
            delay(300L)
        }
        if (!SingBoxRemote.isBound()) return@LaunchedEffect
        if (!SingBoxRemote.queryAndSyncState(context)) return@LaunchedEffect

        // 启动时核心已运行也要消费本次机会，防止同会话手动断开后自动重连
        if (SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value) {
            autoConnectAttempted = true
            return@LaunchedEffect
        }

        fun shouldAutoConnectNow(): Boolean {
            return settings.autoConnect &&
                connectionState == ConnectionState.Idle &&
                !SingBoxRemote.isRunning.value &&
                !SingBoxRemote.isStarting.value
        }

        if (shouldAutoConnectNow()) {
            delay(1_000L)
            if (SingBoxRemote.queryAndSyncState(context) && shouldAutoConnectNow()) {
                autoConnectAttempted = true
                dashboardViewModel.toggleConnection()
            }
        }
    }

    LaunchedEffect(settings.excludeFromRecent) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.appTasks?.forEach {
            it.setExcludeFromRecents(settings.excludeFromRecent)
        }
    }

    LaunchedEffect(Unit) {
        SettingsRepository.restartRequiredEvents.collectLatest {

            if (!SingBoxRemote.isRunning.value && !SingBoxRemote.isStarting.value) return@collectLatest

            AppNotificationManager.showMessage(
                context = context,
                message = restartNeededMessage
            )
        }
    }

    val appTheme = settings.appTheme
    val appThemeStyle = settings.appThemeStyle

    SingBoxTheme(appTheme = appTheme, appThemeStyle = appThemeStyle) {
        val navController = rememberNavController()
        val useLiquidGlassNav = appThemeStyle == AppThemeStyle.LIQUID_GLASS

        // Handle pending navigation from App Shortcuts
        LaunchedEffect(pendingNavigation) {
            pendingNavigation?.let { route ->
                delay(100)
                navController.navigate(route) {
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
                pendingNavigation = null
            }
        }

        // Get current destination
        val navBackStackEntry = navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry.value?.destination?.route
        val showBottomBar = currentRoute in listOf(
            "dashboard", "nodes", "profiles", "settings"
        )
        val rootContainerColor = if (useLiquidGlassNav) {
            MaterialTheme.colorScheme.background
        } else {
            MaterialTheme.colorScheme.surface
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    if (!useLiquidGlassNav) {
                        AnimatedAppNavBar(
                            visible = showBottomBar,
                            navController = navController,
                            themeStyle = appThemeStyle
                        )
                    }
                },
                containerColor = rootContainerColor,
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { innerPadding ->
                val rawPadding = WindowInsets.navigationBars
                    .asPaddingValues()
                    .calculateBottomPadding()
                val safeBottomPadding = if (rawPadding < 12.dp) 12.dp else rawPadding

                val dashboardContentBottomPadding = if (useLiquidGlassNav) {
                    68.dp + safeBottomPadding
                } else {
                    0.dp
                }
                val topLevelContentBottomPadding = if (useLiquidGlassNav) {
                    68.dp + safeBottomPadding
                } else {
                    0.dp
                }
                val bottomPadding =
                    if (useLiquidGlassNav) 0.dp else innerPadding.calculateBottomPadding()

                val contentModifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding)

                if (useLiquidGlassNav) {
                    Box(
                        modifier = contentModifier.background(rootContainerColor)
                    ) {
                        AppNavigation(
                            navController = navController,
                            dashboardViewModel = dashboardViewModel,
                            dashboardBottomContentPadding = dashboardContentBottomPadding,
                            topLevelBottomContentPadding = topLevelContentBottomPadding
                        )
                    }
                } else {
                    Surface(
                        modifier = contentModifier,
                        color = rootContainerColor
                    ) {
                        AppNavigation(
                            navController = navController,
                            dashboardViewModel = dashboardViewModel,
                            dashboardBottomContentPadding = dashboardContentBottomPadding,
                            topLevelBottomContentPadding = topLevelContentBottomPadding
                        )
                    }
                }
            }

            if (useLiquidGlassNav) {
                AnimatedAppNavBar(
                    visible = showBottomBar,
                    navController = navController,
                    themeStyle = appThemeStyle,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun AnimatedAppNavBar(
    visible: Boolean,
    navController: NavController,
    themeStyle: AppThemeStyle,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        ) + expandVertically(
            expandFrom = Alignment.Bottom,
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(durationMillis = 400)),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        ) + shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(durationMillis = 400))
    ) {
        AppNavBar(navController = navController, themeStyle = themeStyle)
    }
}
