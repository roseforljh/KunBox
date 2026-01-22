package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.model.RuleType
import com.kunk.singbox.ui.components.ClickableDropdownField
import com.kunk.singbox.ui.components.ConfirmDialog
import com.kunk.singbox.ui.components.SingleSelectDialog
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.components.StyledTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kunk.singbox.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRulesScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel()
) {
    val settings by settingsViewModel.settings.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<CustomRule?>(null) }

    if (showAddDialog) {
        CustomRuleEditorDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { rule ->
                settingsViewModel.addCustomRule(rule)
                showAddDialog = false
            }
        )
    }

    if (editingRule != null) {
        CustomRuleEditorDialog(
            initialRule = editingRule,
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
                title = { Text(stringResource(R.string.custom_rules_title), color = MaterialTheme.colorScheme.onBackground) },
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
            if (settings.customRules.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.custom_rules_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            } else {
                items(settings.customRules) { rule ->
                    CustomRuleItem(
                        rule = rule,
                        onClick = { editingRule = rule }
                    )
                }
            }
        }
    }
}

@Composable
fun CustomRuleItem(
    rule: CustomRule,
    onClick: () -> Unit
) {
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
                    text = "-> ${stringResource(rule.outbound.displayNameRes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = stringResource(R.string.common_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CustomRuleEditorDialog(
    initialRule: CustomRule? = null,
    onDismiss: () -> Unit,
    onConfirm: (CustomRule) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var name by remember { mutableStateOf(initialRule?.name ?: "") }
    var type by remember { mutableStateOf(initialRule?.type ?: RuleType.DOMAIN_SUFFIX) }
    var value by remember { mutableStateOf(initialRule?.value ?: "") }
    var outbound by remember { mutableStateOf(initialRule?.outbound ?: OutboundTag.PROXY) }

    var showTypeDialog by remember { mutableStateOf(false) }
    var showOutboundDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showTypeDialog) {
        val options = RuleType.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.custom_rules_type),
            options = options,
            selectedIndex = RuleType.entries.indexOf(type),
            onSelect = { index ->
                type = RuleType.entries[index]
                showTypeDialog = false
            },
            onDismiss = { showTypeDialog = false }
        )
    }

    if (showOutboundDialog) {
        val options = OutboundTag.entries.map { stringResource(it.displayNameRes) }
        SingleSelectDialog(
            title = stringResource(R.string.common_outbound),
            options = options,
            selectedIndex = OutboundTag.entries.indexOf(outbound),
            onSelect = { index ->
                outbound = OutboundTag.entries[index]
                showOutboundDialog = false
            },
            onDismiss = { showOutboundDialog = false }
        )
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.custom_rules_delete_title),
            message = stringResource(R.string.custom_rules_delete_confirm, name),
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
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = if (initialRule == null) stringResource(R.string.custom_rules_add) else stringResource(R.string.custom_rules_edit),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                StyledTextField(
                    label = stringResource(R.string.custom_rules_name),
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.custom_rules_name_hint)
                )

                ClickableDropdownField(
                    label = stringResource(R.string.custom_rules_type),
                    value = stringResource(type.displayNameRes),
                    onClick = { showTypeDialog = true }
                )

                StyledTextField(
                    label = stringResource(R.string.custom_rules_content),
                    value = value,
                    onValueChange = { value = it },
                    placeholder = when (type) {
                        RuleType.DOMAIN -> "example.com"
                        RuleType.DOMAIN_SUFFIX -> "example.com"
                        RuleType.IP_CIDR -> "192.168.0.0/16"
                        else -> stringResource(R.string.custom_rules_content_hint)
                    }
                )

                ClickableDropdownField(
                    label = stringResource(R.string.common_outbound),
                    value = stringResource(outbound.displayNameRes),
                    onClick = { showOutboundDialog = true }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newRule = initialRule?.copy(
                        name = name,
                        type = type,
                        value = value,
                        outbound = outbound
                    ) ?: CustomRule(
                        name = name,
                        type = type,
                        value = value,
                        outbound = outbound
                    )
                    onConfirm(newRule)
                },
                enabled = name.isNotBlank() && value.isNotBlank()
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            Row {
                if (initialRule != null && onDelete != null) {
                    TextButton(onClick = { showDeleteConfirm = true }) {
                        Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    )
}
