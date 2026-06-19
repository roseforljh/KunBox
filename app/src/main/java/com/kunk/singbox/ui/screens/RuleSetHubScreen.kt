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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.HubRuleSet
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassButtonContainerColor
import com.kunk.singbox.ui.theme.liquidGlassButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel
import com.kunk.singbox.viewmodel.RuleSetViewModel
import com.kunk.singbox.viewmodel.SettingsViewModel
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor

@Composable
private fun RuleSetBadge(
    text: String,
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .then(
                if (isLiquidGlassTheme()) {
                    Modifier.liquidGlassPanel(shape = shape, selected = true, shadowElevation = 4.dp)
                } else {
                    Modifier.background(backgroundColor, shape)
                }
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSetHubScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
    ruleSetViewModel: RuleSetViewModel = viewModel()
) {

    val activityRuleSetViewModel: RuleSetViewModel = viewModel(
        viewModelStoreOwner = (navController.context as? androidx.activity.ComponentActivity)
            ?: throw IllegalStateException("Context is not a ComponentActivity")
    )

    var searchQuery by remember { mutableStateOf("") }
    val ruleSets by activityRuleSetViewModel.ruleSets.collectAsState()
    val isLoading by activityRuleSetViewModel.isLoading.collectAsState()
    val error by activityRuleSetViewModel.error.collectAsState()
    val downloadingRuleSets by settingsViewModel.downloadingRuleSets.collectAsState()

    val ruleSetSettings by activityRuleSetViewModel.settings.collectAsState()

    val addedRuleSetTags = remember(ruleSetSettings.ruleSets) {
        ruleSetSettings.ruleSets.map { it.tag }.toSet()
    }

    val context = LocalContext.current

    val filteredRuleSets = remember(searchQuery, ruleSets) {
        if (searchQuery.isBlank()) ruleSets
        else ruleSets.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background),
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = liquidGlassTopAppBarContainerColor(
                        MaterialTheme.colorScheme.background
                    )
                )
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
                RuleSetHubSearchField(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it }
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (error != null) {
                val errorMessage = error.orEmpty()
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
                        Button(
                            onClick = { activityRuleSetViewModel.fetchRuleSets() },
                            modifier = Modifier.liquidGlassButtonPanel(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = liquidGlassButtonContainerColor(MaterialTheme.colorScheme.primary),
                                contentColor = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.common_retry),
                                color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                            )
                        }
                    }
                }
            } else {
                // Grid Content
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    ),
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
                                    AppNotificationManager.showMessage(context, message)
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
                                    AppNotificationManager.showMessage(context, message)
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
private fun RuleSetHubSearchField(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit
) {
    val searchFieldShape = RoundedCornerShape(12.dp)
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        placeholder = {
            Text(stringResource(R.string.common_search), color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        leadingIcon = {
            Icon(
                Icons.Rounded.Search,
                contentDescription = stringResource(R.string.common_search),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .liquidGlassTextFieldPanel(shape = searchFieldShape),
        singleLine = true,
        shape = searchFieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
            unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
            focusedBorderColor = liquidGlassTextFieldBorderColor(Color.Transparent),
            unfocusedBorderColor = liquidGlassTextFieldBorderColor(Color.Transparent),
            cursorColor = MaterialTheme.colorScheme.onSurface,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
fun HubRuleSetItem(
    ruleSet: HubRuleSet,
    isDownloading: Boolean = false,
    onAddSource: () -> Unit,
    onAddBinary: () -> Unit,
    isDownloaded: Boolean
) {
    val shape = RoundedCornerShape(12.dp)
    val useLiquidGlass = isLiquidGlassTheme()
    val cardModifier = if (useLiquidGlass) {
        Modifier
            .fillMaxWidth()
            .liquidGlassPanel(shape = shape, shadowElevation = 8.dp)
    } else {
        Modifier.fillMaxWidth()
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (useLiquidGlass) {
                Color.Transparent
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = shape,
        modifier = cardModifier
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
                        RuleSetBadge(
                            text = stringResource(R.string.common_downloaded),
                            backgroundColor = Color(0xFF2E7D32),
                            contentColor = Color.White
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ruleSet.tags.forEach { tag ->
                        RuleSetBadge(
                            text = tag,
                            backgroundColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
