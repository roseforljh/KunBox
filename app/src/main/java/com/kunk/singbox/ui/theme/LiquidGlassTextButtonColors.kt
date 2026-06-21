package com.kunk.singbox.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun liquidGlassTextButtonColors(contentColor: Color): ButtonColors {
    return if (isLiquidGlassTheme()) {
        ButtonDefaults.textButtonColors(
            contentColor = contentColor,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
        )
    } else {
        ButtonDefaults.textButtonColors(contentColor = contentColor)
    }
}
