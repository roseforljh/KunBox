package com.kunk.singbox.ui.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun liquidGlassButtonColors(
    defaultContainerColor: Color,
    defaultContentColor: Color,
    liquidContentColor: Color = MaterialTheme.colorScheme.primary
): ButtonColors {
    return if (isLiquidGlassTheme()) {
        ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = liquidContentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.54f)
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = defaultContainerColor,
            contentColor = defaultContentColor
        )
    }
}

@Composable
fun liquidGlassOutlinedButtonColors(
    defaultContainerColor: Color,
    defaultContentColor: Color,
    liquidContentColor: Color = MaterialTheme.colorScheme.primary
): ButtonColors {
    return if (isLiquidGlassTheme()) {
        ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = liquidContentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
        )
    } else {
        ButtonDefaults.outlinedButtonColors(
            containerColor = defaultContainerColor,
            contentColor = defaultContentColor
        )
    }
}
