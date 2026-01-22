package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.model.RuleType
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.ProfileNodeSelectDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import com.kunk.singbox.viewmodel.NodesViewModel
import com.kunk.singbox.viewmodel.ProfilesViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel

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
                .clickable(onClick = onClick)
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
                contentDescription = "编辑",
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
    val settings by settingsViewModel.settings.collectAsState()
    val allNodes by nodesViewModel.allNodes.collectAsState()
    val nodesForSelection by nodesViewModel.filteredAllNodes.collectAsState()
    val profiles by profilesViewModel.profiles.collectAsState()

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
        DomainRuleEditorDialog(
            initialRule = editingRule,
            nodes = allNodes,
            nodesForSelection = nodesForSelection,
            profiles = profiles,
            onDismiss = { editingRule = null },
            onConfirm = { rule ->
                settingsViewModel.updateCustomRule(rule)
                editingRule = null
            },
            onDelete = {
                settingsViewModel.deleteCustomRule(editingRule!!.id)
                editingRule = null
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.domain_rules_title), color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.common_add), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (domainRules.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
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
                    DomainRuleItem(rule = rule, outboundText = "${stringResource(mode.displayNameRes)} → $outboundText", onClick = { editingRule = rule })
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
    var value by remember { mutableStateOf(initialRule?.value ?: "") }

    fun generateRuleNameFromValue(raw: String): String {
        val first = raw
            .split("\n", "\r", ",", "，")
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
            trimmed.startsWith("=") -> "精确匹配"
            trimmed.contains("*") -> "关键字匹配"
            trimmed.isNotEmpty() -> "后缀匹配 (含子域名)"
            else -> ""
        }
    }

    var outboundMode by remember { mutableStateOf(initialRule?.outboundMode ?: legacyMode(initialRule?.outbound)) }
    var outboundValue by remember { mutableStateOf(initialRule?.outboundValue) }

    var showOutboundDialog by remember { mutableStateOf(false) }
    var showTargetSelectionDialog by remember { mutableStateOf(false) }
    var showNodeSelectionDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var targetSelectionTitle by remember { mutableStateOf("") }
    var targetOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val context = LocalContext.current

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
            onDismiss = { showOutboundDialog = false }
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
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (initialRule == null) stringResource(R.string.domain_rules_add) else stringResource(R.string.domain_rules_edit),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StyledTextField(
                    label = stringResource(R.string.custom_rules_content),
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "google.com / =exact.com / *keyword*"
                )

                val smartHint = getSmartTypeHint(value)
                if (smartHint.isNotEmpty()) {
                    Text(
                        text = "识别为: $smartHint",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showOutboundDialog = true }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.common_outbound), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(outboundMode.displayNameRes), color = MaterialTheme.colorScheme.onSurface)
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
                    } ?: "点击选择..."
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
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
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.app_rules_select_target), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(targetName, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                if (onDelete != null) {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text(stringResource(R.string.common_delete))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val rawValue = value.trim()
                    if (rawValue.isEmpty()) return@TextButton

                    val smartType = parseSmartDomainType(rawValue)
                    val finalValue = cleanDomainValue(rawValue)

                    val finalName = generateRuleNameFromValue(finalValue)
                    if (finalName.isEmpty()) return@TextButton

                    if ((outboundMode == RuleSetOutboundMode.NODE ||
                            outboundMode == RuleSetOutboundMode.PROFILE) && outboundValue.isNullOrBlank()) {
                        return@TextButton
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
                    onConfirm(rule)
                }
            ) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
