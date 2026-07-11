package com.kunk.singbox.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.res.stringResource
import com.kunk.singbox.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.IconButton
import com.kunk.singbox.model.InstalledAppUi
import com.kunk.singbox.repository.InstalledAppsRepository
import com.kunk.singbox.viewmodel.InstalledAppsViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassButtonColors
import com.kunk.singbox.ui.theme.liquidGlassButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassCheckboxColors
import com.kunk.singbox.ui.theme.liquidGlassDividerColor
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassPressFeedback
import com.kunk.singbox.ui.theme.liquidGlassProgressColor
import com.kunk.singbox.ui.theme.liquidGlassProgressTrackColor
import com.kunk.singbox.ui.theme.liquidGlassOutlinedTextFieldColors
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel
import com.kunk.singbox.ui.theme.liquidGlassTextButtonColors
import com.kunk.singbox.ui.theme.liquidGlassTextButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassTextButtonPanel

import com.kunk.singbox.ui.theme.liquidGlassDialogPanel
import com.kunk.singbox.ui.theme.LiquidGlassDialogEffect

@Composable
private fun Modifier.appSelectDialogPanel(shape: RoundedCornerShape = RoundedCornerShape(28.dp)): Modifier {
    return this.liquidGlassDialogPanel(shape = shape, shadowElevation = 24.dp)
        .then(if (!isLiquidGlassTheme()) Modifier.background(MaterialTheme.colorScheme.surface, shape) else Modifier)
}

@Composable
private fun Modifier.appSelectActionPanel(shape: RoundedCornerShape = RoundedCornerShape(10.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = true, shadowElevation = 6.dp)
    } else {
        background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
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

@Composable
private fun Modifier.appSelectFilterPanel(shape: RoundedCornerShape = RoundedCornerShape(8.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 3.dp)
    } else {
        clip(shape)
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
    var showSystemApps by remember { mutableStateOf(false) }
    var showNoLauncherApps by remember { mutableStateOf(false) }
    var tempSelected by remember(selectedPackages) { mutableStateOf(selectedPackages.toSet()) }

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
    val useLiquidGlass = isLiquidGlassTheme()

    Dialog(onDismissRequest = onDismiss) {
        LiquidGlassDialogEffect()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .appSelectDialogPanel()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = installedAppsViewModel::reloadApps,
                    enabled = !isLoading,
                    modifier = Modifier
                        .size(32.dp)
                        .liquidGlassIconButtonPanel(enabled = !isLoading, shadowElevation = 3.dp)
                ) {
                    val tintColor = if (isLoading) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.common_refresh),
                        tint = tintColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                val loading = loadingState as InstalledAppsRepository.LoadingState.Loading
                Column(
                    modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = liquidGlassProgressColor(MaterialTheme.colorScheme.primary),
                        trackColor = liquidGlassProgressTrackColor(MaterialTheme.colorScheme.outline)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            val searchFieldShape = RoundedCornerShape(12.dp)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.app_list_search_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassTextFieldPanel(shape = searchFieldShape),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = liquidGlassOutlinedTextFieldColors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                    unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                    focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                shape = searchFieldShape
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .appSelectFilterPanel()
                        .liquidGlassPressFeedback(
                            label = "liquid_glass_app_select_system_filter_scale"
                        ) {
                            showSystemApps = !showSystemApps
                        }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = showSystemApps,
                        onCheckedChange = { showSystemApps = it },
                        modifier = Modifier.scale(0.8f).size(16.dp),
                        colors = liquidGlassCheckboxColors()
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.app_list_show_system), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    modifier = Modifier
                        .appSelectFilterPanel()
                        .liquidGlassPressFeedback(
                            label = "liquid_glass_app_select_no_launcher_filter_scale"
                        ) {
                            showNoLauncherApps = !showNoLauncherApps
                        }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = showNoLauncherApps,
                        onCheckedChange = { showNoLauncherApps = it },
                        modifier = Modifier.scale(0.8f).size(16.dp),
                        colors = liquidGlassCheckboxColors()
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.app_list_show_no_launcher), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.weight(1f))

                if (enableQuickSelectCommonApps) {
                    Box(
                        modifier = Modifier
                            .padding(end = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .appSelectActionPanel()
                            .liquidGlassPressFeedback(
                                label = "liquid_glass_app_select_quick_select_scale"
                            ) {
                                val matches = if (quickSelectExcludeCommonApps) {
                                    allApps
                                        .asSequence()
                                        .map { it.packageName }
                                        .filter { pkg -> pkg !in commonMatches }
                                        .toSet()
                                } else {
                                    commonMatches
                                }

                                tempSelected = tempSelected + matches
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.app_list_quick_select),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(
                color = liquidGlassDividerColor(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val checked = tempSelected.contains(app.packageName)
                    val iconSize = 40.dp
                    var icon by remember(app.packageName) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(app.packageName) {
                        icon = installedAppsViewModel.loadIcon(app.packageName)
                    }
                    val iconBitmap = remember(icon) { icon?.asImageBitmap() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (useLiquidGlass) {
                                    Modifier.liquidGlassPanel(
                                        shape = RoundedCornerShape(12.dp),
                                        selected = checked,
                                        shadowElevation = 0.dp
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .liquidGlassPressFeedback(
                                label = "liquid_glass_app_select_item_scale"
                            ) {
                                tempSelected = if (checked) {
                                    tempSelected - app.packageName
                                } else {
                                    tempSelected + app.packageName
                                }
                            }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { newChecked ->
                                tempSelected = if (newChecked) {
                                    tempSelected + app.packageName
                                } else {
                                    tempSelected - app.packageName
                                }
                            },
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
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = app.packageName,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
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

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .liquidGlassTextButtonPanel(shape = RoundedCornerShape(25.dp)),
                    colors = liquidGlassTextButtonColors(
                        contentColor = liquidGlassTextButtonContentColor(
                            defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                ) {
                    Text(stringResource(R.string.common_cancel))
                }

                Button(
                    onClick = {
                        onConfirm(
                            resolveVisibleSelectedPackages(
                                selectedPackages = tempSelected,
                                visiblePackages = allApps.map { it.packageName }.toSet(),
                                originalSelectedPackages = selectedPackages
                            )
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .liquidGlassButtonPanel(shape = RoundedCornerShape(25.dp)),
                    colors = liquidGlassButtonColors(
                        defaultContainerColor = MaterialTheme.colorScheme.primary,
                        defaultContentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(25.dp)
                ) {
                    Text(
                        text = confirmText,
                        fontWeight = FontWeight.Bold,
                        color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                    )
                }
            }
        }
    }
}
