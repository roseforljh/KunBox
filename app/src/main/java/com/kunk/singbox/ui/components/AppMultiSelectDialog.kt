package com.kunk.singbox.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.res.stringResource
import com.kunk.singbox.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.IconButton
import com.kunk.singbox.model.InstalledAppUi
import com.kunk.singbox.repository.InstalledAppsRepository
import com.kunk.singbox.viewmodel.InstalledAppsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassCheckboxColors
import com.kunk.singbox.ui.theme.LiquidGlassFilterChip
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassPressFeedback
import com.kunk.singbox.ui.theme.liquidGlassProgressColor
import com.kunk.singbox.ui.theme.liquidGlassProgressTrackColor

private const val INITIAL_ICON_BATCH_SIZE = 32

internal fun toggleQuickSelectionPreset(
    currentSelection: Set<String>,
    quickTargets: Set<String>,
    selectionBeforeQuickSelect: Set<String>?
): Pair<Set<String>, Set<String>?> {
    return if (selectionBeforeQuickSelect == null) {
        (currentSelection + quickTargets) to currentSelection
    } else {
        selectionBeforeQuickSelect to null
    }
}

@Composable
private fun Modifier.appSelectIconPanel(shape: RoundedCornerShape = RoundedCornerShape(10.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 6.dp)
    } else {
        background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
    }
}

private fun Modifier.appSelectItemPanel(
    shape: RoundedCornerShape = RoundedCornerShape(12.dp)
): Modifier {
    return clip(shape)
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun AppSelectorTopControls(
    query: String,
    onQueryChange: (String) -> Unit,
    isSearchExpanded: Boolean,
    onSearchToggle: () -> Unit,
    selectedCount: Int,
    showSystemApps: Boolean,
    onSystemAppsToggle: () -> Unit,
    showNoLauncherApps: Boolean,
    onNoLauncherAppsToggle: () -> Unit,
    enableQuickSelectCommonApps: Boolean,
    quickSelectSelected: Boolean,
    onQuickSelect: () -> Unit,
    isLoading: Boolean,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ExpandableSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                isExpanded = isSearchExpanded,
                onToggle = onSearchToggle,
                placeholder = stringResource(R.string.app_list_search_hint),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.rulesets_selection_mode, selectedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onRefresh,
                enabled = !isLoading,
                modifier = Modifier
                    .size(40.dp)
                    .liquidGlassIconButtonPanel(enabled = !isLoading)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.common_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (isLoading) 0.45f else 1f
                    )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiquidGlassFilterChip(
                selected = showSystemApps,
                onClick = onSystemAppsToggle,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
                label = {
                    Text(
                        text = stringResource(R.string.common_system),
                        maxLines = 1
                    )
                }
            )
            LiquidGlassFilterChip(
                selected = showNoLauncherApps,
                onClick = onNoLauncherAppsToggle,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
                label = {
                    Text(
                        text = stringResource(R.string.common_background),
                        maxLines = 1
                    )
                }
            )
            if (enableQuickSelectCommonApps) {
                LiquidGlassFilterChip(
                    selected = quickSelectSelected,
                    onClick = onQuickSelect,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 7.dp),
                    label = {
                        Text(
                            text = stringResource(R.string.app_list_quick_select),
                            maxLines = 1
                        )
                    }
                )
            }
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
fun AppMultiSelectDialog(
    title: String,
    selectedPackages: Set<String>,
    confirmText: String = stringResource(R.string.common_ok),
    enableQuickSelectCommonApps: Boolean = false,
    quickSelectExcludeCommonApps: Boolean = false,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    installedAppsViewModel: InstalledAppsViewModel = viewModel()
) {
    val allApps by installedAppsViewModel.appItems.collectAsStateWithLifecycle()
    val loadingState by installedAppsViewModel.loadingState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        installedAppsViewModel.loadAppsIfNeeded()
    }

    var query by remember { mutableStateOf("") }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var showSystemApps by remember { mutableStateOf(false) }
    var showNoLauncherApps by remember { mutableStateOf(false) }
    var tempSelected by remember(selectedPackages) { mutableStateOf(selectedPackages.toSet()) }
    var selectionBeforeQuickSelect by remember(selectedPackages) { mutableStateOf<Set<String>?>(null) }

    val commonExactPackages = remember {
        setOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.gsf.login",
            "com.android.vending",
            "com.google.android.youtube",
            "org.telegram.messenger",
            "org.thunderdog.challegram",
            "com.twitter.android",
            "com.instagram.android",
            "com.discord",
            "com.reddit.frontpage",
            "com.whatsapp",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.google.android.apps.googleassistant"
        )
    }

    val commonPrefixPackages = remember {
        listOf(
            "com.google.",
            "com.android.vending",
            "org.telegram.",
            "com.twitter.",
            "com.instagram.",
            "com.discord",
            "com.reddit.",
            "com.whatsapp"
        )
    }

    val commonMatches = remember(allApps, commonExactPackages, commonPrefixPackages) {
        allApps
            .asSequence()
            .map { it.packageName }
            .filter { pkg ->
                pkg in commonExactPackages || commonPrefixPackages.any { prefix -> pkg.startsWith(prefix) }
            }
            .toSet()
    }
    val quickSelectMatches = remember(allApps, commonMatches, quickSelectExcludeCommonApps) {
        if (quickSelectExcludeCommonApps) {
            allApps
                .asSequence()
                .map { it.packageName }
                .filter { it !in commonMatches }
                .toSet()
        } else {
            commonMatches
        }
    }
    val quickSelectSelected = selectionBeforeQuickSelect != null

    val filteredApps = remember(query, showSystemApps, showNoLauncherApps, allApps, tempSelected) {

        val q = query.trim().lowercase()
        allApps
            .asSequence()
            .filter { showSystemApps || !it.isSystemApp }
            .filter { showNoLauncherApps || it.hasLauncher }
            .filter {
                q.isEmpty() || it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
            .toList()
            .sortedWith(
                compareByDescending<InstalledAppUi> { tempSelected.contains(it.packageName) }
                    .thenBy { it.appName.lowercase() }
            )
    }

    val isLoading = loadingState is InstalledAppsRepository.LoadingState.Loading
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }
    val initialIconPackages = remember(filteredApps) {
        filteredApps.take(INITIAL_ICON_BATCH_SIZE).map(InstalledAppUi::packageName)
    }
    val initialIconPackageSet = remember(initialIconPackages) { initialIconPackages.toSet() }
    var initialIcons by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var completedIconBatch by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(initialIconPackages, installedAppsViewModel) {
        completedIconBatch = emptySet()
        initialIcons = installedAppsViewModel.loadIcons(initialIconPackages)
        completedIconBatch = initialIconPackageSet
    }
    val quickSelectApps = {
        val (selection, previousSelection) = toggleQuickSelectionPreset(
            currentSelection = tempSelected,
            quickTargets = quickSelectMatches,
            selectionBeforeQuickSelect = selectionBeforeQuickSelect
        )
        tempSelected = selection
        selectionBeforeQuickSelect = previousSelection
    }
    val updateAppSelection = { packageName: String, selected: Boolean ->
        selectionBeforeQuickSelect = null
        tempSelected = if (selected) {
            tempSelected + packageName
        } else {
            tempSelected - packageName
        }
    }
    FullScreenDialogPage(
        title = title,
        onDismiss = onDismiss,
        actions = {
            IconButton(
                modifier = Modifier.fillMaxSize(),
                onClick = {
                    onConfirm(
                        resolveVisibleSelectedPackages(
                            selectedPackages = tempSelected,
                            visiblePackages = allApps.map { it.packageName }.toSet(),
                            originalSelectedPackages = selectedPackages
                        )
                    )
                }
            ) {
                Text(
                    text = confirmText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        supportingContentHeight = 92.dp,
        supportingContent = {
            AppSelectorTopControls(
                query = query,
                onQueryChange = { query = it },
                isSearchExpanded = isSearchExpanded,
                onSearchToggle = { isSearchExpanded = !isSearchExpanded },
                selectedCount = tempSelected.size,
                showSystemApps = showSystemApps,
                onSystemAppsToggle = { showSystemApps = !showSystemApps },
                showNoLauncherApps = showNoLauncherApps,
                onNoLauncherAppsToggle = { showNoLauncherApps = !showNoLauncherApps },
                enableQuickSelectCommonApps = enableQuickSelectCommonApps,
                quickSelectSelected = quickSelectSelected,
                onQuickSelect = quickSelectApps,
                isLoading = isLoading,
                onRefresh = installedAppsViewModel::reloadApps
            )
        }
    ) { headerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = headerPadding + 12.dp,
                end = 16.dp,
                bottom = WindowInsets.navigationBars
                    .asPaddingValues()
                    .calculateBottomPadding() + 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (isLoading) {
                item(key = "app_selector_loading") {
                    val loading = loadingState as InstalledAppsRepository.LoadingState.Loading
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            progress = { loading.progress },
                            modifier = Modifier.size(48.dp),
                            color = liquidGlassProgressColor(MaterialTheme.colorScheme.primary),
                            strokeWidth = 4.dp,
                            trackColor = liquidGlassProgressTrackColor(MaterialTheme.colorScheme.outline)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.app_list_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.app_list_loaded, loading.current, loading.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { loading.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = liquidGlassProgressColor(MaterialTheme.colorScheme.primary),
                            trackColor = liquidGlassProgressTrackColor(MaterialTheme.colorScheme.outline)
                        )
                    }
                }
            }

            items(filteredApps, key = { it.packageName }) { app ->
                val checked = tempSelected.contains(app.packageName)
                val iconSize = 40.dp
                val preloadedIcon = initialIcons[app.packageName]
                val loadOnDemand = app.packageName !in initialIconPackageSet ||
                    (app.packageName in completedIconBatch && preloadedIcon == null)
                var onDemandIcon by remember(app.packageName) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(app.packageName, loadOnDemand) {
                    if (loadOnDemand) {
                        onDemandIcon = installedAppsViewModel.loadIcon(app.packageName)
                    }
                }
                val icon = preloadedIcon ?: onDemandIcon
                val iconBitmap = remember(icon) { icon?.asImageBitmap() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .appSelectItemPanel()
                        .liquidGlassPressFeedback(
                            label = "liquid_glass_app_select_item_scale"
                        ) {
                            updateAppSelection(app.packageName, !checked)
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { updateAppSelection(app.packageName, it) },
                        colors = liquidGlassCheckboxColors()
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .size(iconSize)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(iconSize)
                                .appSelectIconPanel()
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.appName,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = app.packageName,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (app.isSystemApp || !app.hasLauncher) {
                        Text(
                            text = when {
                                app.isSystemApp -> stringResource(R.string.common_system)
                                else -> stringResource(R.string.common_background)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
