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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.kunk.singbox.ipc.VpnStateStore
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
    val settings by settingsRepository.settings.collectAsState(initial = null)
    val dashboardViewModel: DashboardViewModel = viewModel()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        dashboardViewModel.refreshState()
    }

    LaunchedEffect(settings?.appLanguage) {
        val language = settings?.appLanguage ?: return@LaunchedEffect
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("app_language_cache", language.name).apply()
    }

    val isVpnRunningForUpdate by SingBoxRemote.isRunning.collectAsState()
    var updateChecked by remember { mutableStateOf(false) }

    LaunchedEffect(settings?.autoCheckUpdate, isVpnRunningForUpdate) {
        if (settings?.autoCheckUpdate != true || updateChecked) return@LaunchedEffect

        if (isVpnRunningForUpdate) {

            kotlinx.coroutines.delay(1000L)
            updateChecked = true
            com.kunk.singbox.utils.AppUpdateChecker.checkAndNotify(context)
        }
    }

    LaunchedEffect(settings?.autoCheckUpdate) {
        if (settings?.autoCheckUpdate != true) return@LaunchedEffect
        kotlinx.coroutines.delay(10000L)
        if (!updateChecked) {
            updateChecked = true
            com.kunk.singbox.utils.AppUpdateChecker.checkAndNotify(context)
        }
    }

    // Handle App Shortcuts - need navController reference
    var pendingNavigation by remember { mutableStateOf<String?>(null) }
    val intentEvent by MainIntentEvents.events.collectAsState(initial = null)

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
    val connectionState by dashboardViewModel.connectionState.collectAsState()
    val isRunning by SingBoxRemote.isRunning.collectAsState()
    val isStarting by SingBoxRemote.isStarting.collectAsState()
    val manuallyStopped by SingBoxRemote.manuallyStopped.collectAsState()

    LaunchedEffect(isRunning, isStarting) {

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.kunk.singbox.utils.NetworkClient.clearConnectionPool()
        }
    }

    LaunchedEffect(settings?.autoConnect, connectionState, isRunning, isStarting, manuallyStopped) {
        fun shouldAutoConnect(persistedManuallyStopped: Boolean): Boolean {
            return settings?.autoConnect == true &&
                connectionState == ConnectionState.Idle &&
                !isRunning &&
                !isStarting &&
                !manuallyStopped &&
                !persistedManuallyStopped
        }

        val persistedManuallyStopped = VpnStateStore.isManuallyStopped()
        val shouldAutoConnectNow = shouldAutoConnect(persistedManuallyStopped)
        if (shouldAutoConnectNow) {
            // Delay a bit to ensure everything is initialized
            delay(1000)
            val shouldAutoConnectAfterDelay = shouldAutoConnect(VpnStateStore.isManuallyStopped())
            if (shouldAutoConnectAfterDelay) {
                dashboardViewModel.toggleConnection()
            }
        }
    }

    LaunchedEffect(settings?.excludeFromRecent) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.appTasks?.forEach {
            it.setExcludeFromRecents(settings?.excludeFromRecent == true)
        }
    }

    LaunchedEffect(Unit) {
        SettingsRepository.restartRequiredEvents.collectLatest {

            if (!SingBoxRemote.isRunning.value && !SingBoxRemote.isStarting.value) return@collectLatest

            AppNotificationManager.showMessage(
                context = context,
                message = context.getString(R.string.settings_restart_needed)
            )
        }
    }

    val appTheme = settings?.appTheme ?: com.kunk.singbox.model.AppThemeMode.SYSTEM
    val appThemeStyle = settings?.appThemeStyle ?: AppThemeStyle.DEFAULT

    SingBoxTheme(appTheme = appTheme) {
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
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { innerPadding ->
                val dashboardContentBottomPadding = if (useLiquidGlassNav) {
                    64.dp
                } else {
                    0.dp
                }
                val topLevelContentBottomPadding = if (useLiquidGlassNav) {
                    64.dp
                } else {
                    0.dp
                }
                val bottomPadding =
                    if (useLiquidGlassNav) 0.dp else innerPadding.calculateBottomPadding()

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = bottomPadding),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    AppNavigation(
                        navController = navController,
                        dashboardViewModel = dashboardViewModel,
                        dashboardBottomContentPadding = dashboardContentBottomPadding,
                        topLevelBottomContentPadding = topLevelContentBottomPadding
                    )
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
