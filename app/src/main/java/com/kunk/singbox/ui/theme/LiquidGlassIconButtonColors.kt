package com.kunk.singbox.ui.theme

import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun liquidGlassIconButtonColors(
    defaultContainerColor: Color,
    defaultContentColor: Color,
    liquidContentColor: Color = defaultContentColor
): IconButtonColors {
    return if (isLiquidGlassTheme()) {
        IconButtonDefaults.iconButtonColors(
            containerColor = Color.Transparent,
            contentColor = liquidContentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
        )
    } else {
        IconButtonDefaults.iconButtonColors(
            containerColor = defaultContainerColor,
            contentColor = defaultContentColor
        )
    }
}
