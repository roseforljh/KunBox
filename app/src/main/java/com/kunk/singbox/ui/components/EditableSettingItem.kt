package com.kunk.singbox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.kunk.singbox.R

@Composable
fun EditableTextItem(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null
) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        InputDialog(
            title = stringResource(R.string.common_edit_title, title),
            initialValue = value,
            confirmText = stringResource(R.string.common_ok),
            onConfirm = {
                onValueChange(it)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }

    SettingItem(
        title = title,
        value = value,
        subtitle = subtitle,
        icon = icon,
        onClick = { showDialog = true }
    )
}

@Composable
fun EditableSelectionItem(
    title: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    icon: ImageVector? = null,
    subtitle: String? = null
) {
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        SingleSelectDialog(
            title = stringResource(R.string.common_select_title, title),
            options = options,
            selectedIndex = options.indexOf(value).coerceAtLeast(0),
            onSelect = { index ->
                onValueChange(options[index])
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }

    SettingItem(
        title = title,
        value = value,
        subtitle = subtitle,
        icon = icon,
        onClick = { showDialog = true }
    )
}
