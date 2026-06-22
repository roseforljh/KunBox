package com.kunk.singbox.ui.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun liquidGlassRadioButtonColors(
    selectedColor: Color = MaterialTheme.colorScheme.primary,
    unselectedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
): RadioButtonColors {
    return RadioButtonDefaults.colors(
        selectedColor = selectedColor,
        unselectedColor = if (isLiquidGlassTheme()) {
            unselectedColor.copy(alpha = 0.64f)
        } else {
            unselectedColor
        }
    )
}

@Composable
fun liquidGlassCheckboxColors(
    checkedColor: Color = MaterialTheme.colorScheme.primary,
    uncheckedColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    checkmarkColor: Color = MaterialTheme.colorScheme.onPrimary
): CheckboxColors {
    return CheckboxDefaults.colors(
        checkedColor = if (isLiquidGlassTheme()) {
            checkedColor.copy(alpha = 0.38f)
        } else {
            checkedColor
        },
        uncheckedColor = if (isLiquidGlassTheme()) {
            uncheckedColor.copy(alpha = 0.64f)
        } else {
            uncheckedColor
        },
        checkmarkColor = checkmarkColor
    )
}

@Suppress("UnusedParameter")
@Composable
fun liquidGlassSwitchColors(
    checkedThumbColor: Color = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor: Color = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor: Color = MaterialTheme.colorScheme.outline,
    uncheckedBorderColor: Color = Color.Transparent
): SwitchColors {
    if (!isLiquidGlassTheme()) {
        return SwitchDefaults.colors(
            checkedThumbColor = checkedThumbColor,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = uncheckedThumbColor,
            uncheckedTrackColor = uncheckedTrackColor,
            uncheckedBorderColor = uncheckedBorderColor
        )
    }
    return SwitchDefaults.colors(
        checkedThumbColor = checkedTrackColor,
        checkedTrackColor = checkedTrackColor.copy(alpha = 0.24f),
        uncheckedThumbColor = uncheckedThumbColor.copy(alpha = 0.82f),
        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    )
}

@Composable
fun liquidGlassProgressColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        MaterialTheme.colorScheme.primary
    } else {
        defaultColor
    }
}

@Composable
fun liquidGlassProgressTrackColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f)
    } else {
        defaultColor
    }
}

@Composable
fun liquidGlassDividerColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        Color.White.copy(alpha = 0.34f)
    } else {
        defaultColor
    }
}

@Composable
fun Modifier.liquidGlassTabRowPanel(
    shape: Shape = RoundedCornerShape(20.dp),
    shadowElevation: Dp = 8.dp
): Modifier {
    return if (isLiquidGlassTheme()) {
        padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .liquidGlassPanel(shape = shape, shadowElevation = shadowElevation)
    } else {
        this
    }
}

@Composable
fun liquidGlassTabIndicatorColor(defaultColor: Color): Color {
    return if (isLiquidGlassTheme()) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.86f)
    } else {
        defaultColor
    }
}
