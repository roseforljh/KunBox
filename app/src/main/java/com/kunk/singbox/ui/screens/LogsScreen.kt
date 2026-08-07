package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import com.kunk.singbox.ui.components.FloatingPageLayout
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kunk.singbox.ui.theme.liquidGlassOutlinedTextFieldColors
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.viewmodel.LogViewModel
import com.kunk.singbox.ui.theme.LiquidGlassFilterChip
import com.kunk.singbox.ui.theme.liquidGlassTopAppBarContainerColor
import com.kunk.singbox.ui.components.ConfirmDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(navController: NavController, viewModel: LogViewModel = viewModel()) {
    val logs by viewModel.filteredLogs.collectAsStateWithLifecycle()
    val searchKeyword by viewModel.searchKeyword.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showClearLogsConfirm by remember { mutableStateOf(false) }

    if (showClearLogsConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.logs_clear),
            message = stringResource(R.string.logs_clear_confirm),
            confirmText = stringResource(R.string.common_clear),
            isDestructive = true,
            onConfirm = {
                viewModel.clearLogs()
                showClearLogsConfirm = false
            },
            onDismiss = { showClearLogsConfirm = false }
        )
    }

    FloatingPageLayout(
        title = stringResource(R.string.logs_title),
        onBack = { navController.popBackStack() },
        actions = {
            val exportSubject = "KunBox " + stringResource(R.string.logs_title)
            val exportTitle = stringResource(R.string.logs_export)
            IconButton(
                onClick = {
                    viewModel.getLogsForExport { logsText ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, exportSubject)
                            putExtra(Intent.EXTRA_TEXT, logsText)
                        }
                        context.startActivity(
                            Intent.createChooser(shareIntent, exportTitle)
                        )
                    }
                }
            ) {
                Icon(
                    Icons.Rounded.Share,
                    contentDescription = stringResource(R.string.logs_export),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            IconButton(onClick = { showClearLogsConfirm = true }) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.logs_clear),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        circularAction = false
    ) { contentTopPadding ->
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = liquidGlassTopAppBarContainerColor(MaterialTheme.colorScheme.background)
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    top = contentTopPadding + 8.dp,
                    end = 12.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 12.dp
                )
            ) {
                item(key = "logs_search") {
                    LogsSearchField(
                        searchKeyword = searchKeyword,
                        onSearchKeywordChange = { viewModel.setSearchKeyword(it) }
                    )
                }
                item(key = "logs_categories") {
                    LogsCategoryRow(
                        categories = viewModel.categories,
                        selectedCategory = selectedCategory,
                        onCategorySelected = { viewModel.setCategory(it) }
                    )
                }
                items(logs.asReversed()) { log ->
                    Text(
                        text = log,
                        color = when {
                            log.contains("ERROR", ignoreCase = true) ||
                                log.contains("[ERR]") ->
                                MaterialTheme.colorScheme.error

                            log.contains("WARN", ignoreCase = true) ->
                                MaterialTheme.colorScheme.error.copy(alpha = 0.8f)

                            log.contains("dns:", ignoreCase = true) ->
                                MaterialTheme.colorScheme.tertiary

                            log.contains("[CONN]") ||
                                log.contains("outbound/") ||
                                log.contains("inbound/") ->
                                MaterialTheme.colorScheme.primary

                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LogsSearchField(
    searchKeyword: String,
    onSearchKeywordChange: (String) -> Unit
) {
    val searchShape = RoundedCornerShape(12.dp)
    OutlinedTextField(
        value = searchKeyword,
        onValueChange = onSearchKeywordChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .liquidGlassTextFieldPanel(shape = searchShape),
        placeholder = {
            Text(stringResource(R.string.logs_search_hint))
        },
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = null)
        },
        trailingIcon = {
            if (searchKeyword.isNotEmpty()) {
                IconButton(
                    modifier = Modifier.liquidGlassIconButtonPanel(shadowElevation = 3.dp),
                    onClick = { onSearchKeywordChange("") }
                ) {
                    Icon(Icons.Rounded.Clear, contentDescription = null)
                }
            }
        },
        singleLine = true,
        shape = searchShape,
        colors = liquidGlassOutlinedTextFieldColors(
            focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
            unfocusedBorderColor = liquidGlassTextFieldBorderColor(
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            ),
            focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
            unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent)
        )
    )
}

@Composable
private fun LogsCategoryRow(
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { category ->
            LogCategoryChip(
                label = category,
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) }
            )
        }
    }
}

@Composable
private fun LogCategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    LiquidGlassFilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    )
}
