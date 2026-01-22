package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.HubRuleSet
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.viewmodel.RuleSetViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSetHubScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    ruleSetViewModel: RuleSetViewModel = viewModel()
) {
    // 强制使用 Activity 的 ViewModel 实例，避免重新创建
    // 注意：在实际项目中最好通过 Hilt 注入或在 MainActivity 中传递，这里简化处理
    val activityRuleSetViewModel: RuleSetViewModel = viewModel(
        viewModelStoreOwner = (navController.context as? androidx.activity.ComponentActivity)
            ?: throw IllegalStateException("Context is not a ComponentActivity")
    )

    var searchQuery by remember { mutableStateOf("") }
    val ruleSets by activityRuleSetViewModel.ruleSets.collectAsState()
    val isLoading by activityRuleSetViewModel.isLoading.collectAsState()
    val error by activityRuleSetViewModel.error.collectAsState()
    val downloadingRuleSets by settingsViewModel.downloadingRuleSets.collectAsState()
    // 收集 settings 以便在规则集列表变化时自动更新 UI（如删除规则集后）
    val ruleSetSettings by activityRuleSetViewModel.settings.collectAsState()

    // 创建一个 Set 用于快速查找已添加的规则集
    val addedRuleSetTags = remember(ruleSetSettings.ruleSets) {
        ruleSetSettings.ruleSets.map { it.tag }.toSet()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val filteredRuleSets = remember(searchQuery, ruleSets) {
        if (searchQuery.isBlank()) ruleSets
        else ruleSets.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.ruleset_hub_title), color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            text = stringResource(R.string.import_count_items, filteredRuleSets.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { activityRuleSetViewModel.fetchRuleSets() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.common_refresh), tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            StandardCard(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.common_search), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.common_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.onSurface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (error != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = error!!, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { activityRuleSetViewModel.fetchRuleSets() }) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                }
            } else {
                // Grid Content
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredRuleSets) { ruleSet ->
                        HubRuleSetItem(
                            ruleSet = ruleSet,
                            isDownloading = downloadingRuleSets.contains(ruleSet.name),
                            isDownloaded = addedRuleSetTags.contains(ruleSet.name),
                            onAddSource = {
                                settingsViewModel.addRuleSet(
                                    RuleSet(
                                        tag = ruleSet.name,
                                        type = RuleSetType.REMOTE,
                                        format = "source",
                                        url = ruleSet.sourceUrl
                                    )
                                ) { _, message ->
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                }
                            },
                            onAddBinary = {
                                settingsViewModel.addRuleSet(
                                    RuleSet(
                                        tag = ruleSet.name,
                                        type = RuleSetType.REMOTE,
                                        format = "binary",
                                        url = ruleSet.binaryUrl
                                    )
                                ) { _, message ->
                                    scope.launch { snackbarHostState.showSnackbar(message) }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HubRuleSetItem(
    ruleSet: HubRuleSet,
    isDownloading: Boolean = false,
    onAddSource: () -> Unit,
    onAddBinary: () -> Unit,
    isDownloaded: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ruleSet.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isDownloading) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (isDownloaded) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = Color(0xFF2E7D32),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.common_downloaded),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ruleSet.tags.forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondary,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Text(
                                text = tag,
                                color = MaterialTheme.colorScheme.onSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.ruleset_hub_rule_count, ruleSet.ruleCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Icon(
                    imageVector = Icons.Rounded.Visibility,
                    contentDescription = stringResource(R.string.common_view),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onAddSource,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(stringResource(R.string.common_add) + " Source", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }

                TextButton(
                    onClick = onAddBinary,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(stringResource(R.string.common_add) + " Binary", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
