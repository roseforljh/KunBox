package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kunk.singbox.ui.theme.Destructive
import androidx.navigation.NavController
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.model.RuleType
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.ClickableSelectionButton
import com.kunk.singbox.ui.components.FloatingPageLayout
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import com.kunk.singbox.ui.components.rememberLocalNetworkPermissionRequest
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassButtonColors
import com.kunk.singbox.ui.theme.liquidGlassButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassDialogContainerColor
import com.kunk.singbox.ui.theme.liquidGlassDialogPanel
import com.kunk.singbox.ui.theme.LiquidGlassDialogEffect
import com.kunk.singbox.ui.theme.liquidGlassEmptyStatePanel
import com.kunk.singbox.ui.theme.liquidGlassOutlinedButtonBorder
import com.kunk.singbox.ui.theme.liquidGlassOutlinedButtonColors
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassPressFeedback
import com.kunk.singbox.ui.theme.liquidGlassTextButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassTextButtonColors
import com.kunk.singbox.ui.theme.liquidGlassTextButtonPanel
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor
import com.kunk.singbox.utils.LocalNetworkPermission

@Composable
private fun Modifier.domainRuleSelectorPanel(): Modifier {
    val shape = RoundedCornerShape(8.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 6.dp)
    } else {
        background(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = shape
        )
    }
}

@Composable
private fun Modifier.domainRuleItemPressFeedback(onClick: () -> Unit): Modifier =
    liquidGlassPressFeedback(
        label = "liquid_glass_domain_rule_item_scale",
        onClick = onClick
    )

@Composable
private fun resolveOutboundText(
    mode: RuleSetOutboundMode,
    value: String?,
    nodes: List<com.kunk.singbox.model.NodeUi>,
    profiles: List<com.kunk.singbox.model.ProfileUi>
): String {
    return when (mode) {
        RuleSetOutboundMode.DIRECT -> stringResource(R.string.outbound_tag_direct)
        RuleSetOutboundMode.BLOCK -> stringResource(R.string.outbound_tag_block)
        RuleSetOutboundMode.PROXY -> stringResource(R.string.outbound_tag_proxy)
        RuleSetOutboundMode.NODE -> {
            if (value.isNullOrBlank()) return stringResource(R.string.app_rules_not_selected)
            val parts = value.split("::", limit = 2)
            val node = if (parts.size == 2) {
                val profileId = parts[0]
                val name = parts[1]
                nodes.find { it.sourceProfileId == profileId && it.name == name }
            } else {
                nodes.find { it.id == value } ?: nodes.find { it.name == value }
            }
            val profileName = profiles.find { p -> p.id == node?.sourceProfileId }?.name
            if (node != null && profileName != null) "${node.name} ($profileName)" else stringResource(R.string.app_rules_not_selected)
        }
        RuleSetOutboundMode.PROFILE -> profiles.find { it.id == value }?.name ?: stringResource(R.string.app_rules_unknown_profile)
    }
}

@Composable
private fun DomainRuleItem(rule: CustomRule, outboundText: String, onClick: () -> Unit) {
    StandardCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .domainRuleItemPressFeedback(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${stringResource(rule.type.displayNameRes)}: ${rule.value}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = outboundText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "Edit",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainRulesScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    nodesViewModel: NodesViewModel = viewModel(),
    profilesViewModel: ProfilesViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val allNodes by nodesViewModel.allNodes.collectAsStateWithLifecycle()
    val nodesForSelection by nodesViewModel.filteredAllNodes.collectAsStateWithLifecycle()
    val profiles by profilesViewModel.profiles.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        nodesViewModel.setAllNodesUiActive(true)
        onDispose {
            nodesViewModel.setAllNodesUiActive(false)
        }
    }

    val domainRules = remember(settings.customRules) {
        settings.customRules.filter {
            it.type == RuleType.DOMAIN || it.type == RuleType.DOMAIN_SUFFIX || it.type == RuleType.DOMAIN_KEYWORD
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<CustomRule?>(null) }

    if (showAddDialog) {
        DomainRuleEditorDialog(
            nodes = allNodes,
            nodesForSelection = nodesForSelection,
            profiles = profiles,
            onDismiss = { showAddDialog = false },
            onConfirm = { rule ->
                settingsViewModel.addCustomRule(rule)
                showAddDialog = false
            }
        )
    }

    if (editingRule != null) {
        val currentRule = checkNotNull(editingRule)
        DomainRuleEditorDialog(
            initialRule = currentRule,
            nodes = allNodes,
            nodesForSelection = nodesForSelection,
            profiles = profiles,
            onDismiss = { editingRule = null },
            onConfirm = { rule ->
                settingsViewModel.updateCustomRule(rule)
                editingRule = null
            },
            onDelete = {
                settingsViewModel.deleteCustomRule(currentRule.id)
                editingRule = null
            }
        )
    }

    FloatingPageLayout(
        title = stringResource(R.string.domain_rules_title),
        onBack = { navController.popBackStack() },
        actions = {
            IconButton(onClick = { showAddDialog = true }) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.common_add),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    ) { contentTopPadding ->
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background)
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = contentTopPadding + 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (domainRules.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlassEmptyStatePanel()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.domain_rules_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                } else {
                    items(domainRules) { rule ->
                        val mode = rule.outboundMode ?: when (rule.outbound) {
                            OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
                            OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
                            OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
                        }
                        val outboundText = resolveOutboundText(mode, rule.outboundValue, allNodes, profiles)
                        DomainRuleItem(
                            rule = rule,
                            outboundText = "${stringResource(mode.displayNameRes)} - $outboundText",
                            onClick = { editingRule = rule }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DomainRuleEditorDialog(
    initialRule: CustomRule? = null,
    nodes: List<com.kunk.singbox.model.NodeUi>,
    nodesForSelection: List<com.kunk.singbox.model.NodeUi>? = null,
    profiles: List<com.kunk.singbox.model.ProfileUi>,
    onDismiss: () -> Unit,
    onConfirm: (CustomRule) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var value by rememberSaveable(initialRule?.value) {
        mutableStateOf(initialRule?.value ?: "")
    }
    val exactTypeHint = stringResource(R.string.domain_rules_type_exact)
    val keywordTypeHint = stringResource(R.string.domain_rules_type_keyword)
    val suffixTypeHint = stringResource(R.string.domain_rules_type_suffix)
    val selectProfileTitle = stringResource(R.string.rulesets_select_profile)

    fun generateRuleNameFromValue(raw: String): String {
        val first = raw
            .split("\n", "\r", ",", ";")
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        return (first ?: raw.trim()).trim().take(120)
    }
    fun legacyMode(outbound: OutboundTag?): RuleSetOutboundMode {
        return when (outbound ?: OutboundTag.PROXY) {
            OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
            OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
            OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
        }
    }

    fun parseSmartDomainType(input: String): RuleType {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("=") -> RuleType.DOMAIN
            trimmed.contains("*") -> RuleType.DOMAIN_KEYWORD
            else -> RuleType.DOMAIN_SUFFIX
        }
    }

    fun cleanDomainValue(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("=") -> trimmed.removePrefix("=").trim()
            trimmed.contains("*") -> trimmed.replace("*", "").trim()
            else -> trimmed
        }
    }

    fun getSmartTypeHint(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("=") -> exactTypeHint
            trimmed.contains("*") -> keywordTypeHint
            trimmed.isNotEmpty() -> suffixTypeHint
            else -> ""
        }
    }

    var outboundMode by rememberSaveable(initialRule?.outboundMode, initialRule?.outbound) {
        mutableStateOf(initialRule?.outboundMode ?: legacyMode(initialRule?.outbound))
    }
    var outboundValue by rememberSaveable(initialRule?.outboundValue) {
        mutableStateOf(initialRule?.outboundValue)
    }

    var showOutboundDialog by remember { mutableStateOf(false) }
    var showTargetSelectionDialog by remember { mutableStateOf(false) }
    var showNodeSelectionDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val requestLocalNetworkPermission = rememberLocalNetworkPermissionRequest()

    var targetSelectionTitle by remember { mutableStateOf("") }
    var targetOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val selectionNodes = nodesForSelection ?: nodes

    fun resolveNodeByStoredValue(value: String?): com.kunk.singbox.model.NodeUi? {
        if (value.isNullOrBlank()) return null
        val parts = value.split("::", limit = 2)
        if (parts.size == 2) {
            val profileId = parts[0]
            val name = parts[1]
            return nodes.find { it.sourceProfileId == profileId && it.name == name }
        }
        return nodes.find { it.id == value } ?: nodes.find { it.name == value }
    }

    fun toNodeRef(node: com.kunk.singbox.model.NodeUi): String = "${node.sourceProfileId}::${node.name}"

    if (showOutboundDialog) {
        val options = RuleSetOutboundMode.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.common_outbound),
            options = options,
            selectedIndex = RuleSetOutboundMode.entries.indexOf(outboundMode),
            onSelect = { index ->
                val selectedMode = RuleSetOutboundMode.entries[index]
                outboundMode = selectedMode
                if (selectedMode != initialRule?.outboundMode) {
                    outboundValue = null
                }
                showOutboundDialog = false

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
            onDismiss = { showOutboundDialog = false }
        )
    }

    if (showNodeSelectionDialog) {
        val selectedNode = resolveNodeByStoredValue(outboundValue)
        NodePickerPage(
            title = stringResource(R.string.rulesets_select_node),
            profiles = profiles,
            allNodes = nodes,
            displayedNodes = selectionNodes,
            selectedNodeId = selectedNode?.id,
            onSelectNode = { node -> outboundValue = toNodeRef(node) },
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

    if (showDeleteConfirm) {
        val displayName = initialRule?.name?.takeIf { it.isNotBlank() } ?: generateRuleNameFromValue(value)
        ConfirmDialog(
            title = stringResource(R.string.domain_rules_delete_title),
            message = stringResource(R.string.domain_rules_delete_confirm, displayName),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                onDelete?.invoke()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    AlertDialog(
        modifier = Modifier.liquidGlassDialogPanel(),
        onDismissRequest = onDismiss,
        containerColor = liquidGlassDialogContainerColor(),
        title = {
            Text(
                text = if (initialRule == null) stringResource(R.string.domain_rules_add) else stringResource(R.string.domain_rules_edit),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            LiquidGlassDialogEffect()
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StyledTextField(
                    label = stringResource(R.string.custom_rules_content),
                    value = value,
                    onValueChange = { value = it },
                    placeholder = stringResource(R.string.domain_rules_placeholder)
                )

                val smartHint = getSmartTypeHint(value)
                if (smartHint.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.domain_rules_detected_type, smartHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .domainRuleSelectorPanel()
                        .liquidGlassPressFeedback(
                            label = "liquid_glass_domain_rule_outbound_selector_scale"
                        ) {
                            showOutboundDialog = true
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.common_outbound), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(outboundMode.displayNameRes), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }

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
                    } ?: stringResource(R.string.rulesets_tap_to_select)
                    val onTargetClick = {
                        when (outboundMode) {
                            RuleSetOutboundMode.NODE -> showNodeSelectionDialog = true
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
                    if (outboundMode == RuleSetOutboundMode.NODE) {
                        ClickableSelectionButton(
                            label = stringResource(R.string.app_rules_select_target),
                            value = targetName,
                            onClick = onTargetClick
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .domainRuleSelectorPanel()
                                .liquidGlassPressFeedback(
                                    label = "liquid_glass_domain_rule_target_selector_scale",
                                    onClick = onTargetClick
                                )
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.app_rules_select_target),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val isTapToSelect = targetName == stringResource(R.string.rulesets_tap_to_select)
                            Text(
                                targetName,
                                color = if (isTapToSelect) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                fontWeight = if (isTapToSelect) FontWeight.Normal else FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (onDelete != null) {
                    TextButton(
                        modifier = Modifier.liquidGlassTextButtonPanel(),
                        onClick = { showDeleteConfirm = true },
                        colors = liquidGlassTextButtonColors(
                            contentColor = liquidGlassTextButtonContentColor(
                                defaultColor = Destructive,
                                liquidColor = Destructive
                            )
                        )
                    ) {
                        Text(stringResource(R.string.common_delete))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val rawValue = value.trim()
                    if (rawValue.isEmpty()) return@Button

                    val smartType = parseSmartDomainType(rawValue)
                    val finalValue = cleanDomainValue(rawValue)

                    val finalName = generateRuleNameFromValue(finalValue)
                    if (finalName.isEmpty()) return@Button

                    if ((outboundMode == RuleSetOutboundMode.NODE ||
                            outboundMode == RuleSetOutboundMode.PROFILE) && outboundValue.isNullOrBlank()) {
                        return@Button
                    }

                    val legacyOutbound = when (outboundMode) {
                        RuleSetOutboundMode.DIRECT -> OutboundTag.DIRECT
                        RuleSetOutboundMode.BLOCK -> OutboundTag.BLOCK
                        else -> OutboundTag.PROXY
                    }

                    val rule = CustomRule(
                        id = initialRule?.id ?: java.util.UUID.randomUUID().toString(),
                        name = finalName,
                        type = smartType,
                        value = finalValue,
                        outbound = legacyOutbound,
                        outboundMode = outboundMode,
                        outboundValue = outboundValue,
                        enabled = initialRule?.enabled ?: true
                    )
                    if (LocalNetworkPermission.requiresLocalNetworkAccess(
                            AppSettings(bypassLan = false, customRules = listOf(rule))
                        )
                    ) {
                        requestLocalNetworkPermission { onConfirm(rule) }
                    } else {
                        onConfirm(rule)
                    }
                },
                modifier = Modifier.liquidGlassButtonPanel(),
                colors = liquidGlassButtonColors(
                    defaultContainerColor = MaterialTheme.colorScheme.primary,
                    defaultContentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = stringResource(R.string.common_ok),
                    color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.liquidGlassButtonPanel(shape = RoundedCornerShape(20.dp)),
                border = liquidGlassOutlinedButtonBorder(ButtonDefaults.outlinedButtonBorder(enabled = true)),
                colors = liquidGlassOutlinedButtonColors(
                    defaultContainerColor = Color.Transparent,
                    defaultContentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.primary)
                )
            }
        }
    )
}
