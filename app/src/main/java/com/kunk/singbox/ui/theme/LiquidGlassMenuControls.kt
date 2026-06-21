package com.kunk.singbox.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

@Composable
fun LiquidGlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (isLiquidGlassTheme()) {
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val menuShape = RoundedCornerShape(16.dp)
        // 半透明但足以衬托文字的毛玻璃背景
        val bgColor = if (isDark) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        } else {
            Color.White.copy(alpha = 0.92f)
        }
        val borderColor = if (isDark) {
            Color.White.copy(alpha = 0.15f)
        } else {
            Color.Black.copy(alpha = 0.08f)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier.border(
                width = 1.dp,
                color = borderColor,
                shape = menuShape
            ),
            containerColor = bgColor,
            shape = menuShape,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            content = content
        )
    } else {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            content = content
        )
    }
}

@Composable
fun liquidGlassDropdownMenuItemColors(): MenuItemColors {
    return if (isLiquidGlassTheme()) {
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        MenuDefaults.itemColors(
            textColor = if (isDark) Color.White.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.85f),
            leadingIconColor = if (isDark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.7f),
            trailingIconColor = if (isDark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.7f),
            disabledTextColor = if (isDark) Color.White.copy(alpha = 0.38f) else Color.Black.copy(alpha = 0.38f),
            disabledLeadingIconColor = if (isDark) Color.White.copy(alpha = 0.38f) else Color.Black.copy(alpha = 0.38f),
            disabledTrailingIconColor = if (isDark) Color.White.copy(alpha = 0.38f) else Color.Black.copy(alpha = 0.38f)
        )
    } else {
        MenuDefaults.itemColors()
    }
}
