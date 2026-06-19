package com.kunk.singbox.ui.components

import androidx.compose.ui.res.stringResource
import com.kunk.singbox.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel

@Composable
private fun Modifier.clickableDropdownPressFeedback(
    useLiquidGlass: Boolean,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (useLiquidGlass && isPressed) 0.98f else 1f,
        animationSpec = spring(stiffness = 520f, dampingRatio = 0.72f),
        label = "liquid_glass_clickable_dropdown_scale"
    )
    val clickModifier = if (useLiquidGlass) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier.clickable(onClick = onClick)
    }

    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.then(clickModifier)
}

/**
 * A dropdown-style field that clearly indicates it is clickable.
 * Used for selecting from a list of options in dialogs.
 */
@Composable
fun ClickableDropdownField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val useLiquidGlass = isLiquidGlassTheme()
    val fieldShape = RoundedCornerShape(16.dp)
    val fieldSurfaceModifier = if (useLiquidGlass) {
        Modifier.liquidGlassPanel(shape = fieldShape, shadowElevation = 8.dp)
    } else {
        Modifier
            .clip(fieldShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = fieldShape
            )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(fieldSurfaceModifier)
                .clickableDropdownPressFeedback(useLiquidGlass = useLiquidGlass, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Normal
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.app_rules_click_to_select),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * A styled text input field with consistent rounded corners.
 */
@Composable
fun StyledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true
) {
    val fieldShape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )

        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassTextFieldPanel(shape = fieldShape),
            placeholder = {
                if (placeholder.isNotEmpty()) {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            },
            singleLine = singleLine,
            shape = fieldShape,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = liquidGlassTextFieldBorderColor(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                ),
                unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                focusedContainerColor = liquidGlassTextFieldContainerColor(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                unfocusedContainerColor = liquidGlassTextFieldContainerColor(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                cursorColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}
