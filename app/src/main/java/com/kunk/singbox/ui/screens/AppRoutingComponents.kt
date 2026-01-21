package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.kunk.singbox.model.*
import com.kunk.singbox.ui.components.ClickableDropdownField
import com.kunk.singbox.ui.components.ProfileNodeSelectDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.Neutral700

@Composable
fun AppRuleItem(
    rule: AppRule,
    outboundText: String,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val mode = rule.outboundMode ?: RuleSetOutboundMode.DIRECT
    val (outboundIcon, color) = when (mode) {
        RuleSetOutboundMode.PROXY, RuleSetOutboundMode.NODE, RuleSetOutboundMode.PROFILE -> Icons.Rounded.Shield to MaterialTheme.colorScheme.primary
        RuleSetOutboundMode.DIRECT -> Icons.Rounded.Public to MaterialTheme.colorScheme.tertiary
        RuleSetOutboundMode.BLOCK -> Icons.Rounded.Block to MaterialTheme.colorScheme.error
    }
    val appIcon = remember(rule.packageName) {
        try {
            context.packageManager.getApplicationIcon(rule.packageName).toBitmap(160, 160).asImageBitmap()
        } catch (e: Exception) { null }
    }

    StandardCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (appIcon != null) {
                    Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                } else {
                    Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Neutral700), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Apps, contentDescription = null, tint = Neutral500, modifier = Modifier.size(24.dp))
                    }
                }
                Box(modifier = Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Icon(outboundIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(10.dp))
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = rule.appName, style = MaterialTheme.typography.titleMedium, color = if (rule.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = "${stringResource(mode.displayNameRes)} → $outboundText", style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
            }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
        }
        Switch(checked = rule.enabled, onCheckedChange = { onToggle() }, colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onPrimary, checkedTrackColor = MaterialTheme.colorScheme.primary, uncheckedThumbColor = Neutral500, uncheckedTrackColor = Neutral700))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRuleEditorDialog(
    initialRule: AppRule? = null,
    installedApps: List<InstalledApp>,
    existingPackages: Set<String>,
    nodes: List<NodeUi>,
    nodesForSelection: List<NodeUi>? = null,
    profiles: List<ProfileUi>,
    onDismiss: () -> Unit,
    onConfirm: (AppRule) -> Unit
) {
    var selectedApp by remember { mutableStateOf<InstalledApp?>(initialRule?.let { InstalledApp(it.packageName, it.appName) }) }
    var outboundMode by remember { mutableStateOf(initialRule?.outboundMode ?: RuleSetOutboundMode.PROXY) }
    var outboundValue by remember { mutableStateOf(initialRule?.outboundValue) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showOutboundModeDialog by remember { mutableStateOf(false) }
    var showTargetSelectionDialog by remember { mutableStateOf(false) }
    var showNodeSelectionDialog by remember { mutableStateOf(false) }
    var targetSelectionTitle by remember { mutableStateOf("") }
    var targetOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val context = LocalContext.current

    val selectionNodes = nodesForSelection ?: nodes

    fun resolveNodeByStoredValue(value: String?): NodeUi? {
        if (value.isNullOrBlank()) return null
        val parts = value.split("::", limit = 2)
        if (parts.size == 2) {
            val profileId = parts[0]
            val name = parts[1]
            return nodes.find { it.sourceProfileId == profileId && it.name == name }
        }
        return nodes.find { it.id == value } ?: nodes.find { it.name == value }
    }

    fun toNodeRef(node: NodeUi): String = "${node.sourceProfileId}::${node.name}"

    if (showAppPicker) {
        AppPickerDialog(apps = installedApps, existingPackages = existingPackages, onSelect = { selectedApp = it; showAppPicker = false }, onDismiss = { showAppPicker = false })
    }

    if (showOutboundModeDialog) {
        // 应用分流不显示直连选项
        val appRoutingModes = RuleSetOutboundMode.entries.filter { it != RuleSetOutboundMode.DIRECT }
        val options = appRoutingModes.map { stringResource(it.displayNameRes) }
        val currentIndex = appRoutingModes.indexOf(outboundMode).coerceAtLeast(0)
        SingleSelectDialog(title = stringResource(R.string.rulesets_select_outbound), options = options, selectedIndex = currentIndex, onSelect = { index ->
            val selectedMode = appRoutingModes[index]
            outboundMode = selectedMode
            if (selectedMode != initialRule?.outboundMode) outboundValue = null
            showOutboundModeDialog = false
            if (selectedMode == RuleSetOutboundMode.NODE || selectedMode == RuleSetOutboundMode.PROFILE) {
                when (selectedMode) {
                    RuleSetOutboundMode.NODE -> {
                        showNodeSelectionDialog = true
                    }
                    RuleSetOutboundMode.PROFILE -> { targetSelectionTitle = "选择配置"; targetOptions = profiles.map { it.name to it.id } }
                    else -> {}
                }
                if (selectedMode != RuleSetOutboundMode.NODE) {
                    showTargetSelectionDialog = true
                }
            }
        }, onDismiss = { showOutboundModeDialog = false })
    }

    if (showNodeSelectionDialog) {
        val currentRef = resolveNodeByStoredValue(outboundValue)?.let { toNodeRef(it) } ?: outboundValue
        ProfileNodeSelectDialog(
            title = stringResource(R.string.rulesets_select_node),
            profiles = profiles,
            nodesForSelection = selectionNodes,
            selectedNodeRef = currentRef,
            onSelect = { ref -> outboundValue = ref },
            onDismiss = { showNodeSelectionDialog = false }
        )
    }

    if (showTargetSelectionDialog) {
        val currentRef = resolveNodeByStoredValue(outboundValue)?.let { toNodeRef(it) } ?: outboundValue
        SingleSelectDialog(title = targetSelectionTitle, options = targetOptions.map { it.first }, selectedIndex = targetOptions.indexOfFirst { it.second == currentRef }.coerceAtLeast(0), onSelect = { index -> outboundValue = targetOptions[index].second; showTargetSelectionDialog = false }, onDismiss = { showTargetSelectionDialog = false })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = if (initialRule == null) stringResource(R.string.app_rules_add) else stringResource(R.string.app_rules_edit), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                ClickableDropdownField(label = stringResource(R.string.app_rules_select_app), value = selectedApp?.appName ?: stringResource(R.string.app_rules_click_to_select), onClick = { showAppPicker = true })
                ClickableDropdownField(label = stringResource(R.string.common_outbound), value = stringResource(outboundMode.displayNameRes), onClick = { showOutboundModeDialog = true })
                if (outboundMode == RuleSetOutboundMode.NODE || outboundMode == RuleSetOutboundMode.PROFILE) {
                    val targetName = when (outboundMode) {
                        RuleSetOutboundMode.NODE -> {
                            val node = resolveNodeByStoredValue(outboundValue)
                            val profileName = profiles.find { it.id == node?.sourceProfileId }?.name
                            if (node != null && profileName != null) "${node.name} ($profileName)" else node?.name
                        }
                        RuleSetOutboundMode.PROFILE -> profiles.find { it.id == outboundValue }?.name
                        else -> null
                    } ?: "点击选择..."
                    ClickableDropdownField(label = "选择目标", value = targetName, onClick = {
                        when (outboundMode) {
                            RuleSetOutboundMode.NODE -> {
                                showNodeSelectionDialog = true
                            }
                            RuleSetOutboundMode.PROFILE -> { targetSelectionTitle = context.getString(R.string.rulesets_select_profile); targetOptions = profiles.map { it.name to it.id } }
                            else -> {}
                        }
                        if (outboundMode != RuleSetOutboundMode.NODE) {
                            showTargetSelectionDialog = true
                        }
                    })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selectedApp?.let { app -> val rule = initialRule?.copy(packageName = app.packageName, appName = app.appName, outboundMode = outboundMode, outboundValue = outboundValue) ?: AppRule(packageName = app.packageName, appName = app.appName, outboundMode = outboundMode, outboundValue = outboundValue); onConfirm(rule) } }, enabled = selectedApp != null) { Text(stringResource(R.string.common_save), color = if (selectedApp != null) MaterialTheme.colorScheme.primary else Neutral500) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerDialog(apps: List<InstalledApp>, existingPackages: Set<String>, onSelect: (InstalledApp) -> Unit, onDismiss: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    val filteredApps = remember(apps, searchQuery, showSystemApps, existingPackages) {
        apps.filter { app ->
            val matchesSearch = searchQuery.isBlank() || app.appName.contains(searchQuery, ignoreCase = true) || app.packageName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = showSystemApps || !app.isSystemApp
            val notExisting = app.packageName !in existingPackages
            matchesSearch && matchesFilter && notExisting
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = null,
        text = {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.common_search), color = Neutral500, fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Neutral500, modifier = Modifier.size(20.dp)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showSystemApps = !showSystemApps }
                            .padding(4.dp)
                    ) {
                        Checkbox(
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                            modifier = Modifier.size(20.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = Neutral500
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.common_system), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredApps) { app ->
                        AppListItem(app = app, onClick = { onSelect(app) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

@Composable
fun AppListItem(app: InstalledApp, onClick: () -> Unit) {
    val context = LocalContext.current
    val appIcon = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
        } catch (e: Exception) { null }
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (appIcon != null) {
            Image(bitmap = appIcon, contentDescription = app.appName, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
        } else {
            Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Neutral700), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Apps, contentDescription = null, tint = Neutral500, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.appName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = app.packageName, fontSize = 11.sp, color = Neutral500, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (app.isSystemApp) {
            Text(stringResource(R.string.common_system), fontSize = 10.sp, color = Neutral500)
        }
    }
}

@Composable
fun AppIconSmall(packageName: String) {
    val context = LocalContext.current
    val appIcon = remember(packageName) {
        try {
            context.packageManager.getApplicationIcon(packageName).toBitmap(128, 128).asImageBitmap()
        } catch (e: Exception) { null }
    }
    
    if (appIcon != null) {
        Image(
            bitmap = appIcon,
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(Neutral700),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Apps, contentDescription = null, tint = Neutral500, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun AppGroupCard(
    group: AppGroup,
    outboundText: String,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val mode = group.outboundMode ?: RuleSetOutboundMode.DIRECT
    val (outboundIcon, color) = when (mode) {
        RuleSetOutboundMode.PROXY, RuleSetOutboundMode.NODE, RuleSetOutboundMode.PROFILE -> Icons.Rounded.Shield to MaterialTheme.colorScheme.primary
        RuleSetOutboundMode.DIRECT -> Icons.Rounded.Public to MaterialTheme.colorScheme.tertiary
        RuleSetOutboundMode.BLOCK -> Icons.Rounded.Block to MaterialTheme.colorScheme.error
    }

    StandardCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(outboundIcon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (group.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${stringResource(mode.displayNameRes)} → $outboundText • " + stringResource(R.string.import_count_items, group.apps.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
                Switch(
                    checked = group.enabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = Neutral500,
                        uncheckedTrackColor = Neutral700
                    )
                )
            }

            if (group.apps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(group.apps.take(8)) { app ->
                        AppIconSmall(packageName = app.packageName)
                    }
                    if (group.apps.size > 8) {
                        item {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Neutral700),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("+${group.apps.size - 8}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SelectedAppChip(app: AppInfo, onRemove: () -> Unit) {
    val context = LocalContext.current
    val appIcon = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
        } catch (e: Exception) { null }
    }
    
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Neutral700)
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (appIcon != null) {
            Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(app.appName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            Icons.Rounded.Close,
            contentDescription = stringResource(R.string.app_groups_remove),
            tint = Neutral500,
            modifier = Modifier.size(16.dp).clickable(onClick = onRemove)
        )
    }
}

@Composable
fun SelectableAppItem(
    app: InstalledApp,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(app.packageName) {
        try {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
        } catch (e: Exception) { null }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClick() },
            modifier = Modifier.size(20.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = Neutral500,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (appIcon != null) {
            Image(bitmap = appIcon, contentDescription = app.appName, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)))
        } else {
            Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Neutral700), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Apps, contentDescription = null, tint = Neutral500, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.appName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = app.packageName, fontSize = 10.sp, color = Neutral500, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (app.isSystemApp) {
            Text(stringResource(R.string.common_system), fontSize = 9.sp, color = Neutral500)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiAppSelectorDialog(
    installedApps: List<InstalledApp>,
    selectedApps: Set<AppInfo>,
    onConfirm: (Set<AppInfo>) -> Unit,
    onDismiss: () -> Unit
) {
    var tempSelected by remember { mutableStateOf(selectedApps) }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    val filteredApps = remember(installedApps, searchQuery, showSystemApps, tempSelected) {
        val filtered = installedApps.filter { app ->
            val matchesSearch = searchQuery.isBlank() ||
                app.appName.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = showSystemApps || !app.isSystemApp
            matchesSearch && matchesFilter
        }
        val selectedPackages = tempSelected.map { it.packageName }.toSet()
        filtered.sortedByDescending { it.packageName in selectedPackages }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = null,
        text = {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.import_count_items, tempSelected.size),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showSystemApps = !showSystemApps }
                            .padding(4.dp)
                    ) {
                        Checkbox(
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                            modifier = Modifier.size(18.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = Neutral500
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.common_system), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.common_search), color = Neutral500, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Neutral500, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredApps) { app ->
                        val appInfo = AppInfo(app.packageName, app.appName)
                        val isSelected = tempSelected.any { it.packageName == app.packageName }
                        SelectableAppItem(
                            app = app,
                            isSelected = isSelected,
                            onClick = {
                                tempSelected = if (isSelected) {
                                    tempSelected.filter { it.packageName != app.packageName }.toSet()
                                } else {
                                    tempSelected + appInfo
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(tempSelected) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.common_ok) + " (${tempSelected.size})", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppGroupEditorDialog(
    initialGroup: AppGroup? = null,
    installedApps: List<InstalledApp>,
    nodes: List<NodeUi>,
    nodesForSelection: List<NodeUi>? = null,
    profiles: List<ProfileUi>,
    onDismiss: () -> Unit,
    onConfirm: (AppGroup) -> Unit
) {
    var groupName by remember { mutableStateOf(initialGroup?.name ?: "") }
    var outboundMode by remember { mutableStateOf(initialGroup?.outboundMode ?: RuleSetOutboundMode.PROXY) }
    var outboundValue by remember { mutableStateOf(initialGroup?.outboundValue) }
    var selectedApps by remember { mutableStateOf(initialGroup?.apps?.toSet() ?: emptySet()) }
    
    var showAppSelector by remember { mutableStateOf(false) }
    var showOutboundModeDialog by remember { mutableStateOf(false) }
    var showTargetSelectionDialog by remember { mutableStateOf(false) }
    var showNodeSelectionDialog by remember { mutableStateOf(false) }
    
    var targetSelectionTitle by remember { mutableStateOf("") }
    var targetOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val context = LocalContext.current

    val selectionNodes = nodesForSelection ?: nodes

    fun resolveNodeByStoredValue(value: String?): NodeUi? {
        if (value.isNullOrBlank()) return null
        val parts = value.split("::", limit = 2)
        if (parts.size == 2) {
            val profileId = parts[0]
            val name = parts[1]
            return nodes.find { it.sourceProfileId == profileId && it.name == name }
        }
        return nodes.find { it.id == value } ?: nodes.find { it.name == value }
    }

    fun toNodeRef(node: NodeUi): String = "${node.sourceProfileId}::${node.name}"

    if (showAppSelector) {
        MultiAppSelectorDialog(
            installedApps = installedApps,
            selectedApps = selectedApps,
            onConfirm = { apps ->
                selectedApps = apps
                showAppSelector = false
            },
            onDismiss = { showAppSelector = false }
        )
    }
    
    if (showOutboundModeDialog) {
        // 应用分流不显示直连选项
        val appRoutingModes = RuleSetOutboundMode.entries.filter { it != RuleSetOutboundMode.DIRECT }
        val options = appRoutingModes.map { stringResource(it.displayNameRes) }
        val currentIndex = appRoutingModes.indexOf(outboundMode).coerceAtLeast(0)
        SingleSelectDialog(
            title = stringResource(R.string.rulesets_select_outbound),
            options = options,
            selectedIndex = currentIndex,
            onSelect = { index ->
                val selectedMode = appRoutingModes[index]
                outboundMode = selectedMode
                if (selectedMode != initialGroup?.outboundMode) {
                    outboundValue = null
                } else {
                    outboundValue = initialGroup?.outboundValue
                }
                showOutboundModeDialog = false
                if (selectedMode == RuleSetOutboundMode.NODE ||
                    selectedMode == RuleSetOutboundMode.PROFILE) {
                    when (selectedMode) {
                        RuleSetOutboundMode.NODE -> {
                            showNodeSelectionDialog = true
                        }
                        RuleSetOutboundMode.PROFILE -> {
                            targetSelectionTitle = context.getString(R.string.rulesets_select_profile)
                            targetOptions = profiles.map { it.name to it.id }
                        }
                        else -> {}
                    }
                    if (selectedMode != RuleSetOutboundMode.NODE) {
                        showTargetSelectionDialog = true
                    }
                }
            },
            onDismiss = { showOutboundModeDialog = false }
        )
    }

    if (showNodeSelectionDialog) {
        val currentRef = resolveNodeByStoredValue(outboundValue)?.let { toNodeRef(it) } ?: outboundValue
        ProfileNodeSelectDialog(
            title = stringResource(R.string.rulesets_select_node),
            profiles = profiles,
            nodesForSelection = selectionNodes,
            selectedNodeRef = currentRef,
            onSelect = { ref -> outboundValue = ref },
            onDismiss = { showNodeSelectionDialog = false }
        )
    }

    if (showTargetSelectionDialog) {
        val currentRef = resolveNodeByStoredValue(outboundValue)?.let { toNodeRef(it) } ?: outboundValue
        SingleSelectDialog(
            title = targetSelectionTitle,
            options = targetOptions.map { it.first },
            selectedIndex = targetOptions.indexOfFirst { it.second == currentRef }.coerceAtLeast(0),
            onSelect = { index ->
                outboundValue = targetOptions[index].second
                showTargetSelectionDialog = false
            },
            onDismiss = { showTargetSelectionDialog = false }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (initialGroup == null) stringResource(R.string.app_groups_create) else stringResource(R.string.app_groups_edit),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StyledTextField(
                    label = stringResource(R.string.app_groups_name),
                    value = groupName,
                    onValueChange = { groupName = it },
                    placeholder = "e.g. Social, Games" // TODO: add to strings.xml
                )

                ClickableDropdownField(
                    label = stringResource(R.string.common_outbound),
                    value = stringResource(outboundMode.displayNameRes),
                    onClick = { showOutboundModeDialog = true }
                )

                if (outboundMode == RuleSetOutboundMode.NODE ||
                    outboundMode == RuleSetOutboundMode.PROFILE) {

                    val targetName = when (outboundMode) {
                        RuleSetOutboundMode.NODE -> {
                            val node = resolveNodeByStoredValue(outboundValue)
                            val profileName = profiles.find { it.id == node?.sourceProfileId }?.name
                            if (node != null && profileName != null) "${node.name} ($profileName)" else node?.name
                        }
                        RuleSetOutboundMode.PROFILE -> profiles.find { it.id == outboundValue }?.name
                        else -> null
                    } ?: stringResource(R.string.app_rules_click_to_select)

                    ClickableDropdownField(
                        label = stringResource(R.string.app_rules_select_target),
                        value = targetName,
                        onClick = {
                            when (outboundMode) {
                                RuleSetOutboundMode.NODE -> {
                                    showNodeSelectionDialog = true
                                }
                                RuleSetOutboundMode.PROFILE -> {
                                    targetSelectionTitle = context.getString(R.string.rulesets_select_profile)
                                    targetOptions = profiles.map { it.name to it.id }
                                }
                                else -> {}
                            }
                            if (outboundMode != RuleSetOutboundMode.NODE) {
                                showTargetSelectionDialog = true
                            }
                        }
                    )
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.app_rules_tabs_individual) + " (${selectedApps.size})", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                        TextButton(onClick = { showAppSelector = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.app_groups_select_apps))
                        }
                    }
                    
                    if (selectedApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Neutral700.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(stringResource(R.string.app_groups_click_to_add), color = Neutral500, fontSize = 13.sp)
                        }
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(selectedApps.toList()) { app ->
                                SelectedAppChip(app = app, onRemove = { selectedApps = selectedApps - app })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val group = initialGroup?.copy(
                        name = groupName,
                        apps = selectedApps.toList(),
                        outboundMode = outboundMode,
                        outboundValue = outboundValue
                    ) ?: AppGroup(
                        name = groupName,
                        apps = selectedApps.toList(),
                        outboundMode = outboundMode,
                        outboundValue = outboundValue
                    )
                    onConfirm(group)
                },
                enabled = groupName.isNotBlank() && selectedApps.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
