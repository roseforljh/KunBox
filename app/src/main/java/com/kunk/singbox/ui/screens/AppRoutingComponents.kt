package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunk.singbox.model.*
import com.kunk.singbox.ui.components.ClickableDropdownField
import com.kunk.singbox.ui.components.ProfileNodeSelectDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.Neutral700
import com.kunk.singbox.ui.theme.LiquidGlassDialogEffect
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassButtonColors
import com.kunk.singbox.ui.theme.liquidGlassButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassCheckboxColors
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassDialogContainerColor
import com.kunk.singbox.ui.theme.liquidGlassDialogPanel
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassMutedContentColor
import com.kunk.singbox.ui.theme.liquidGlassOutlinedTextFieldColors
import com.kunk.singbox.ui.theme.liquidGlassSwitchColors
import com.kunk.singbox.ui.theme.liquidGlassPressFeedback
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel
import com.kunk.singbox.ui.theme.liquidGlassTextButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassTextButtonColors
import com.kunk.singbox.ui.theme.liquidGlassTextButtonPanel

private const val APP_INFO_SEPARATOR = "\t"

@Composable
private fun rememberAppIcon(
    packageName: String,
    loadIcon: suspend (String) -> Bitmap?
): Bitmap? {
    var icon by remember(packageName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(packageName, loadIcon) {
        icon = loadIcon(packageName)
    }
    return icon
}

private fun AppInfo.toSavedValue(): String {
    return "$packageName$APP_INFO_SEPARATOR$appName"
}

private fun String.toAppInfo(): AppInfo {
    val parts = split(APP_INFO_SEPARATOR, limit = 2)
    val packageName = parts.firstOrNull().orEmpty()
    val appName = parts.getOrNull(1).takeUnless { it.isNullOrBlank() } ?: packageName
    return AppInfo(packageName = packageName, appName = appName)
}

private fun Set<AppInfo>.toSavedValues(): List<String> {
    return map { it.toSavedValue() }.sorted()
}

private fun List<String>.toAppInfoSet(): Set<AppInfo> {
    return map { it.toAppInfo() }.toSet()
}

@Composable
private fun Modifier.routingChipPanel(shape: RoundedCornerShape): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 0.dp)
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant, shape)
    }
}

@Composable
private fun Modifier.routingIconPanel(shape: RoundedCornerShape): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 6.dp)
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant, shape)
    }
}

@Composable
private fun Modifier.routingAppListItemPanel(shape: RoundedCornerShape): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 4.dp)
    } else {
        clip(shape)
    }
}

@Composable
private fun Modifier.routingFilterTogglePanel(shape: RoundedCornerShape): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 3.dp)
    } else {
        clip(shape)
    }
}

@Composable
private fun Modifier.routingEmptySelectionPanel(): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 6.dp)
    } else {
        background(Neutral700.copy(alpha = 0.3f), shape)
    }
}

@Composable
private fun Modifier.routingMoreCountPanel(): Modifier {
    val shape = RoundedCornerShape(8.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 6.dp)
    } else {
        background(Neutral700, shape)
    }
}

@Composable
private fun Modifier.routingStatusBadgePanel(defaultColor: Color): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = CircleShape, selected = true, shadowElevation = 4.dp)
    } else {
        background(defaultColor, CircleShape)
    }
}

@Composable
private fun Modifier.routingGroupIconPanel(defaultColor: Color): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = CircleShape, shadowElevation = 6.dp)
    } else {
        background(defaultColor, CircleShape)
    }
}

@Composable
private fun Modifier.routingSelectablePanel(isSelected: Boolean): Modifier {
    val shape = RoundedCornerShape(8.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, selected = isSelected, shadowElevation = 4.dp)
    } else {
        background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent, shape)
    }
}

@Composable
private fun Modifier.routingItemPressFeedback(onClick: () -> Unit): Modifier =
    liquidGlassPressFeedback(
        label = "liquid_glass_routing_item_scale",
        onClick = onClick
    )

@Composable
private fun Modifier.selectedAppRemovePressFeedback(onClick: () -> Unit): Modifier =
    liquidGlassPressFeedback(
        pressedScale = 0.9f,
        label = "liquid_glass_selected_app_remove_scale",
        onClick = onClick
    )

@Composable
private fun RoutingStatusBadge(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(18.dp)
            .routingStatusBadgePanel(color),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isLiquidGlassTheme()) color else MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(10.dp)
        )
    }
}

@Composable
private fun appRuleEnabledSwitch(
    checked: Boolean,
    onToggle: () -> Unit
) {
    Switch(
        checked = checked,
        onCheckedChange = { onToggle() },
        colors = liquidGlassSwitchColors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = liquidGlassMutedContentColor(Neutral500),
            uncheckedTrackColor = Neutral700
        )
    )
}

@Composable
private fun appRuleDeleteButton(onDelete: () -> Unit) {
    IconButton(
        onClick = onDelete,
        modifier = Modifier
            .size(36.dp)
            .liquidGlassIconButtonPanel(shadowElevation = 3.dp)
    ) {
        Icon(
            Icons.Rounded.Delete,
            contentDescription = stringResource(R.string.common_delete),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Suppress("LongMethod", "LongParameterList")
@Composable
fun AppRuleItem(
    rule: AppRule,
    outboundText: String,
    loadIcon: suspend (String) -> Bitmap?,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val mode = rule.outboundMode ?: RuleSetOutboundMode.DIRECT
    val (outboundIcon, color) = when (mode) {
        RuleSetOutboundMode.PROXY, RuleSetOutboundMode.NODE, RuleSetOutboundMode.PROFILE -> Icons.Rounded.Shield to MaterialTheme.colorScheme.primary
        RuleSetOutboundMode.DIRECT -> Icons.Rounded.Public to MaterialTheme.colorScheme.tertiary
        RuleSetOutboundMode.BLOCK -> Icons.Rounded.Block to MaterialTheme.colorScheme.error
    }
    val appIcon = rememberAppIcon(rule.packageName, loadIcon)
    val iconBitmap = remember(appIcon) { appIcon?.asImageBitmap() }

    StandardCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (iconBitmap != null) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .routingIconPanel(RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Apps,
                            contentDescription = null,
                            tint = liquidGlassMutedContentColor(Neutral500),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                RoutingStatusBadge(
                    icon = outboundIcon,
                    color = color,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = rule.appName, style = MaterialTheme.typography.titleMedium, color = if (rule.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${stringResource(mode.displayNameRes)} ·$outboundText",
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    maxLines = 1
                )
            }
            appRuleDeleteButton(onDelete = onDelete)
            Spacer(modifier = Modifier.width(8.dp))
            appRuleEnabledSwitch(
                checked = rule.enabled,
                onToggle = onToggle
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
fun AppRuleEditorDialog(
    initialRule: AppRule? = null,
    installedApps: List<InstalledAppUi>,
    existingPackages: Set<String>,
    nodes: List<NodeUi>,
    nodesForSelection: List<NodeUi>? = null,
    profiles: List<ProfileUi>,
    loadIcon: suspend (String) -> Bitmap?,
    onDismiss: () -> Unit,
    onConfirm: (AppRule) -> Unit
) {
    var selectedAppPackageName by rememberSaveable(initialRule?.packageName) {
        mutableStateOf(initialRule?.packageName)
    }
    var selectedAppName by rememberSaveable(initialRule?.appName) {
        mutableStateOf(initialRule?.appName)
    }
    var outboundMode by rememberSaveable(initialRule?.outboundMode) {
        mutableStateOf(initialRule?.outboundMode ?: RuleSetOutboundMode.PROXY)
    }
    var outboundValue by rememberSaveable(initialRule?.outboundValue) {
        mutableStateOf(initialRule?.outboundValue)
    }
    var showAppPicker by remember { mutableStateOf(false) }
    var showOutboundModeDialog by remember { mutableStateOf(false) }
    var showTargetSelectionDialog by remember { mutableStateOf(false) }
    var showNodeSelectionDialog by remember { mutableStateOf(false) }
    var targetSelectionTitle by remember { mutableStateOf("") }
    var targetOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val selectedApp = selectedAppPackageName?.let { packageName ->
        AppInfo(packageName = packageName, appName = selectedAppName ?: packageName)
    }
    val selectProfileTitle = stringResource(R.string.rulesets_select_profile)

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
        AppPickerDialog(
            apps = installedApps,
            existingPackages = existingPackages,
            loadIcon = loadIcon,
            onSelect = {
                selectedAppPackageName = it.packageName
                selectedAppName = it.appName
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false }
        )
    }

    if (showOutboundModeDialog) {

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
                    RuleSetOutboundMode.PROFILE -> {
                        targetSelectionTitle = selectProfileTitle
                        targetOptions = profiles.map { it.name to it.id }
                    }
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
        modifier = Modifier.liquidGlassDialogPanel(RoundedCornerShape(24.dp)),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = liquidGlassDialogContainerColor(),
        title = { Text(text = if (initialRule == null) stringResource(R.string.app_rules_add) else stringResource(R.string.app_rules_edit), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            LiquidGlassDialogEffect()
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
                    } ?: stringResource(R.string.rulesets_tap_to_select)
                    ClickableDropdownField(
                        label = stringResource(R.string.rulesets_select_target),
                        value = targetName,
                        onClick = {
                            when (outboundMode) {
                                RuleSetOutboundMode.NODE -> {
                                    showNodeSelectionDialog = true
                                }
                                RuleSetOutboundMode.PROFILE -> {
                                    targetSelectionTitle = selectProfileTitle
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
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.liquidGlassTextButtonPanel(enabled = selectedApp != null),
                colors = liquidGlassTextButtonColors(
                    contentColor = liquidGlassTextButtonContentColor(MaterialTheme.colorScheme.primary)
                ),
                onClick = {
                    selectedApp?.let { app ->
                        val rule = initialRule?.copy(
                            packageName = app.packageName,
                            appName = app.appName,
                            outboundMode = outboundMode,
                            outboundValue = outboundValue
                        ) ?: AppRule(
                            packageName = app.packageName,
                            appName = app.appName,
                            outboundMode = outboundMode,
                            outboundValue = outboundValue
                        )
                        onConfirm(rule)
                    }
                },
                enabled = selectedApp != null
            ) {
                Text(
                    stringResource(R.string.common_save),
                    color = if (selectedApp != null) {
                        liquidGlassTextButtonContentColor(MaterialTheme.colorScheme.primary)
                    } else {
                        liquidGlassMutedContentColor(Neutral500)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.liquidGlassTextButtonPanel(),
                colors = liquidGlassTextButtonColors(
                    contentColor = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ),
                onClick = onDismiss
            ) {
                Text(
                    stringResource(R.string.common_cancel),
                    color = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun AppPickerDialog(
    apps: List<InstalledAppUi>,
    existingPackages: Set<String>,
    loadIcon: suspend (String) -> Bitmap?,
    onSelect: (InstalledAppUi) -> Unit,
    onDismiss: () -> Unit
) {
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
        modifier = Modifier.liquidGlassDialogPanel(RoundedCornerShape(20.dp)),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = liquidGlassDialogContainerColor(),
        title = null,
        text = {
            LiquidGlassDialogEffect()
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val searchFieldShape = RoundedCornerShape(12.dp)
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlassTextFieldPanel(shape = searchFieldShape),
                        placeholder = {
                            Text(
                                stringResource(R.string.common_search),
                                color = liquidGlassMutedContentColor(Neutral500),
                                fontSize = 14.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                tint = liquidGlassMutedContentColor(Neutral500),
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        singleLine = true,
                        shape = searchFieldShape,
                        colors = liquidGlassOutlinedTextFieldColors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = liquidGlassTextFieldBorderColor(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            ),
                            unfocusedBorderColor = liquidGlassTextFieldBorderColor(
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            ),
                            focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .routingFilterTogglePanel(RoundedCornerShape(8.dp))
                            .liquidGlassPressFeedback(
                                label = "liquid_glass_routing_system_filter_scale"
                            ) {
                                showSystemApps = !showSystemApps
                            }
                            .padding(4.dp)
                    ) {
                        Checkbox(
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                            modifier = Modifier.size(20.dp),
                            colors = liquidGlassCheckboxColors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = liquidGlassMutedContentColor(Neutral500)
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
                    items(filteredApps, key = { it.packageName }) { app ->
                        AppListItem(
                            app = app,
                            loadIcon = loadIcon,
                            onClick = { onSelect(app) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                modifier = Modifier.liquidGlassTextButtonPanel(),
                colors = liquidGlassTextButtonColors(
                    contentColor = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ),
                onClick = onDismiss
            ) {
                Text(
                    stringResource(R.string.common_cancel),
                    color = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    )
}

@Composable
fun AppListItem(
    app: InstalledAppUi,
    loadIcon: suspend (String) -> Bitmap?,
    onClick: () -> Unit
) {
    val itemShape = RoundedCornerShape(8.dp)
    val icon = rememberAppIcon(app.packageName, loadIcon)
    val appIcon = remember(icon) { icon?.asImageBitmap() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .routingAppListItemPanel(itemShape)
            .routingItemPressFeedback(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (appIcon != null) {
            Image(bitmap = appIcon, contentDescription = app.appName, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .routingIconPanel(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Apps,
                    contentDescription = null,
                    tint = liquidGlassMutedContentColor(Neutral500),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.appName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = app.packageName,
                fontSize = 11.sp,
                color = liquidGlassMutedContentColor(Neutral500),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (app.isSystemApp) {
            Text(
                stringResource(R.string.common_system),
                fontSize = 10.sp,
                color = liquidGlassMutedContentColor(Neutral500)
            )
        }
    }
}

@Composable
fun AppIconSmall(
    packageName: String,
    loadIcon: suspend (String) -> Bitmap?
) {
    val icon = rememberAppIcon(packageName, loadIcon)
    val appIcon = remember(icon) { icon?.asImageBitmap() }

    if (appIcon != null) {
        Image(
            bitmap = appIcon,
            contentDescription = null,
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(32.dp)
                .routingIconPanel(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Apps,
                contentDescription = null,
                tint = liquidGlassMutedContentColor(Neutral500),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod", "CognitiveComplexMethod")
fun AppGroupCard(
    group: AppGroup,
    outboundText: String,
    loadIcon: suspend (String) -> Bitmap?,
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

    StandardCard(onClick = onClick) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .routingGroupIconPanel(color.copy(alpha = 0.15f)),
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
                        text = buildString {
                            append("${stringResource(mode.displayNameRes)} ·$outboundText ·")
                            append(stringResource(R.string.import_count_items, group.apps.size))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(32.dp)
                        .liquidGlassIconButtonPanel(shadowElevation = 3.dp)
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = group.enabled,
                    onCheckedChange = { onToggle() },
                    colors = liquidGlassSwitchColors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = liquidGlassMutedContentColor(Neutral500),
                        uncheckedTrackColor = Neutral700
                    )
                )
            }

            if (group.apps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(group.apps.take(8), key = { it.packageName }) { app ->
                        AppIconSmall(
                            packageName = app.packageName,
                            loadIcon = loadIcon
                        )
                    }
                    if (group.apps.size > 8) {
                        item {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .routingMoreCountPanel(),
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
fun SelectedAppChip(
    app: AppInfo,
    loadIcon: suspend (String) -> Bitmap?,
    onRemove: () -> Unit
) {
    val icon = rememberAppIcon(app.packageName, loadIcon)
    val appIcon = remember(icon) { icon?.asImageBitmap() }

    Row(
        modifier = Modifier
            .routingChipPanel(RoundedCornerShape(20.dp))
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (appIcon != null) {
            Image(bitmap = appIcon, contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape))
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = app.appName,
            fontSize = 12.sp,
            color = if (isLiquidGlassTheme()) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 80.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            Icons.Rounded.Close,
            contentDescription = stringResource(R.string.app_groups_remove),
            tint = liquidGlassMutedContentColor(Neutral500),
            modifier = Modifier
                .size(16.dp)
                .selectedAppRemovePressFeedback(onClick = onRemove)
        )
    }
}

@Suppress("LongMethod")
@Composable
fun SelectableAppItem(
    app: InstalledAppUi,
    isSelected: Boolean,
    loadIcon: suspend (String) -> Bitmap?,
    onClick: () -> Unit
) {
    val icon = rememberAppIcon(app.packageName, loadIcon)
    val appIcon = remember(icon) { icon?.asImageBitmap() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .routingSelectablePanel(isSelected)
            .routingItemPressFeedback(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClick() },
            modifier = Modifier.size(20.dp),
            colors = liquidGlassCheckboxColors(
                checkedColor = MaterialTheme.colorScheme.primary,
                uncheckedColor = liquidGlassMutedContentColor(Neutral500),
                checkmarkColor = MaterialTheme.colorScheme.onPrimary
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (appIcon != null) {
            Image(bitmap = appIcon, contentDescription = app.appName, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)))
        } else {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .routingIconPanel(RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Apps,
                    contentDescription = null,
                    tint = liquidGlassMutedContentColor(Neutral500),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.appName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = app.packageName,
                fontSize = 10.sp,
                color = liquidGlassMutedContentColor(Neutral500),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (app.isSystemApp) {
            Text(
                stringResource(R.string.common_system),
                fontSize = 9.sp,
                color = liquidGlassMutedContentColor(Neutral500)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun MultiAppSelectorDialog(
    installedApps: List<InstalledAppUi>,
    selectedApps: Set<AppInfo>,
    loadIcon: suspend (String) -> Bitmap?,
    onConfirm: (Set<AppInfo>) -> Unit,
    onDismiss: () -> Unit
) {
    val selectedEntries = remember(selectedApps) { selectedApps.toSavedValues() }
    var tempSelectedEntries by rememberSaveable(selectedEntries) {
        mutableStateOf(selectedEntries)
    }
    val tempSelected = remember(tempSelectedEntries) {
        tempSelectedEntries.toAppInfoSet()
    }
    val tempSelectedPackages = remember(tempSelected) {
        tempSelected.mapTo(mutableSetOf()) { it.packageName }
    }
    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    val filteredApps = remember(installedApps, searchQuery, showSystemApps, tempSelectedPackages) {
        val filtered = installedApps.filter { app ->
            val matchesSearch = searchQuery.isBlank() ||
                app.appName.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = showSystemApps || !app.isSystemApp
            matchesSearch && matchesFilter
        }
        filtered.sortedByDescending { it.packageName in tempSelectedPackages }
    }

    AlertDialog(
        modifier = Modifier.liquidGlassDialogPanel(RoundedCornerShape(20.dp)),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = liquidGlassDialogContainerColor(),
        title = null,
        text = {
            LiquidGlassDialogEffect()
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
                            .routingFilterTogglePanel(RoundedCornerShape(6.dp))
                            .liquidGlassPressFeedback(
                                label = "liquid_glass_routing_selected_system_filter_scale"
                            ) {
                                showSystemApps = !showSystemApps
                            }
                            .padding(4.dp)
                    ) {
                        Checkbox(
                            checked = showSystemApps,
                            onCheckedChange = { showSystemApps = it },
                            modifier = Modifier.size(18.dp),
                            colors = liquidGlassCheckboxColors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = liquidGlassMutedContentColor(Neutral500)
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.common_system), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val searchFieldShape = RoundedCornerShape(10.dp)
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlassTextFieldPanel(shape = searchFieldShape),
                    placeholder = {
                        Text(
                            stringResource(R.string.common_search),
                            color = liquidGlassMutedContentColor(Neutral500),
                            fontSize = 13.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = liquidGlassMutedContentColor(Neutral500),
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    singleLine = true,
                    shape = searchFieldShape,
                    colors = liquidGlassOutlinedTextFieldColors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = liquidGlassTextFieldBorderColor(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        ),
                        unfocusedBorderColor = liquidGlassTextFieldBorderColor(
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ),
                        focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                        unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val appInfo = AppInfo(app.packageName, app.appName)
                        val isSelected = app.packageName in tempSelectedPackages
                        SelectableAppItem(
                            app = app,
                            isSelected = isSelected,
                            loadIcon = loadIcon,
                            onClick = {
                                val savedAppInfo = appInfo.toSavedValue()
                                tempSelectedEntries = if (isSelected) {
                                    tempSelectedEntries.filterNot { it.toAppInfo().packageName == app.packageName }
                                } else {
                                    tempSelectedEntries + savedAppInfo
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(tempSelectedEntries.toAppInfoSet()) },
                modifier = Modifier.liquidGlassButtonPanel(shape = RoundedCornerShape(10.dp)),
                colors = liquidGlassButtonColors(
                    defaultContainerColor = MaterialTheme.colorScheme.primary,
                    defaultContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.common_ok) + " (${tempSelected.size})",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                )
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.liquidGlassTextButtonPanel(),
                colors = liquidGlassTextButtonColors(
                    contentColor = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ),
                onClick = onDismiss
            ) {
                Text(
                    stringResource(R.string.common_cancel),
                    color = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    fontSize = 13.sp
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
fun AppGroupEditorDialog(
    initialGroup: AppGroup? = null,
    installedApps: List<InstalledAppUi>,
    nodes: List<NodeUi>,
    nodesForSelection: List<NodeUi>? = null,
    profiles: List<ProfileUi>,
    loadIcon: suspend (String) -> Bitmap?,
    onDismiss: () -> Unit,
    onConfirm: (AppGroup) -> Unit
) {
    var groupName by rememberSaveable(initialGroup?.name) {
        mutableStateOf(initialGroup?.name ?: "")
    }
    var outboundMode by rememberSaveable(initialGroup?.outboundMode) {
        mutableStateOf(initialGroup?.outboundMode ?: RuleSetOutboundMode.DIRECT)
    }
    var outboundValue by rememberSaveable(initialGroup?.outboundValue) {
        mutableStateOf(initialGroup?.outboundValue)
    }
    var selectedAppEntries by rememberSaveable(initialGroup?.id) {
        mutableStateOf(initialGroup?.apps?.map { it.toSavedValue() }.orEmpty())
    }
    val selectedApps = remember(selectedAppEntries) {
        selectedAppEntries.map { it.toAppInfo() }
    }
    var showAppSelector by remember { mutableStateOf(false) }
    var showOutboundModeDialog by remember { mutableStateOf(false) }
    var showTargetSelectionDialog by remember { mutableStateOf(false) }
    var showNodeSelectionDialog by remember { mutableStateOf(false) }

    var targetSelectionTitle by remember { mutableStateOf("") }
    var targetOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val selectProfileTitle = stringResource(R.string.rulesets_select_profile)

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
            selectedApps = selectedApps.toSet(),
            loadIcon = loadIcon,
            onConfirm = { apps ->
                selectedAppEntries = apps.toSavedValues()
                showAppSelector = false
            },
            onDismiss = { showAppSelector = false }
        )
    }

    if (showOutboundModeDialog) {

        val appRoutingModes = RuleSetOutboundMode.entries
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
                    outboundValue = initialGroup.outboundValue
                }
                showOutboundModeDialog = false
                if (selectedMode == RuleSetOutboundMode.NODE ||
                    selectedMode == RuleSetOutboundMode.PROFILE) {
                    when (selectedMode) {
                        RuleSetOutboundMode.NODE -> {
                            showNodeSelectionDialog = true
                        }
                        RuleSetOutboundMode.PROFILE -> {
                            targetSelectionTitle = selectProfileTitle
                            targetOptions = profiles.map { it.name to it.id }
                        }
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
        modifier = Modifier.liquidGlassDialogPanel(RoundedCornerShape(24.dp)),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = liquidGlassDialogContainerColor(),
        title = {
            Text(
                text = if (initialGroup == null) stringResource(R.string.app_groups_create) else stringResource(R.string.app_groups_edit),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            LiquidGlassDialogEffect()
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StyledTextField(
                    label = stringResource(R.string.app_groups_name),
                    value = groupName,
                    onValueChange = { groupName = it },
                    placeholder = stringResource(R.string.app_groups_name_placeholder)
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
                                    targetSelectionTitle = selectProfileTitle
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
                        TextButton(
                            modifier = Modifier.liquidGlassTextButtonPanel(shadowElevation = 0.dp),
                            colors = liquidGlassTextButtonColors(
                                contentColor = liquidGlassTextButtonContentColor(MaterialTheme.colorScheme.primary)
                            ),
                            onClick = { showAppSelector = true }
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.app_groups_select_apps))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (selectedApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .routingEmptySelectionPanel(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.app_groups_click_to_add),
                                color = liquidGlassMutedContentColor(Neutral500),
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(selectedApps, key = { it.packageName }) { app ->
                                SelectedAppChip(
                                    app = app,
                                    loadIcon = loadIcon,
                                    onRemove = {
                                        selectedAppEntries = selectedAppEntries.filterNot {
                                            it.toAppInfo().packageName == app.packageName
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
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
                modifier = Modifier.liquidGlassButtonPanel(shape = RoundedCornerShape(12.dp)),
                colors = liquidGlassButtonColors(
                    defaultContainerColor = MaterialTheme.colorScheme.primary,
                    defaultContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.common_save),
                    fontWeight = FontWeight.Bold,
                    color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                )
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.liquidGlassTextButtonPanel(),
                colors = liquidGlassTextButtonColors(
                    contentColor = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ),
                onClick = onDismiss
            ) {
                Text(
                    stringResource(R.string.common_cancel),
                    color = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    )
}
