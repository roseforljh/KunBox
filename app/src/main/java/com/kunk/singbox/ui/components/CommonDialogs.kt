package com.kunk.singbox.ui.components

import androidx.compose.ui.res.stringResource
import com.kunk.singbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import com.kunk.singbox.ui.theme.Destructive
import com.kunk.singbox.ui.theme.Neutral500
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassButtonContainerColor
import com.kunk.singbox.ui.theme.liquidGlassButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel

@Composable
private fun Modifier.dialogPanel(shape: RoundedCornerShape = RoundedCornerShape(28.dp)): Modifier {
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(shape = shape, shadowElevation = 22.dp)
    } else {
        background(MaterialTheme.colorScheme.surface, shape)
    }
}

@Composable
private fun Modifier.dialogOptionPanel(isSelected: Boolean): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return if (isLiquidGlassTheme() && isSelected) {
        liquidGlassPanel(shape = shape, selected = true, shadowElevation = 4.dp)
    } else {
        background(
            if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
            } else {
                Color.Transparent
            },
            shape
        )
    }
}

@Composable
private fun confirmDialogActionButton(
    confirmText: String,
    isDestructive: Boolean,
    onConfirm: () -> Unit
) {
    val defaultContainerColor = if (isDestructive) Destructive else MaterialTheme.colorScheme.primary
    val defaultContentColor = if (isDestructive) Color.White else MaterialTheme.colorScheme.onPrimary
    val liquidContentColor = if (isDestructive) Destructive else MaterialTheme.colorScheme.primary
    val contentColor = liquidGlassButtonContentColor(
        defaultColor = defaultContentColor,
        liquidColor = liquidContentColor
    )

    Button(
        onClick = onConfirm,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .liquidGlassButtonPanel(shape = RoundedCornerShape(25.dp)),
        colors = ButtonDefaults.buttonColors(
            containerColor = liquidGlassButtonContainerColor(defaultContainerColor),
            contentColor = contentColor
        ),
        shape = RoundedCornerShape(25.dp)
    ) {
        Text(
            text = confirmText,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = stringResource(R.string.common_confirm),
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .dialogPanel()
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            confirmDialogActionButton(
                confirmText = confirmText,
                isDestructive = isDestructive,
                onConfirm = onConfirm
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Neutral500)
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputDialog(
    title: String,
    initialValue: String = "",
    placeholder: String = "",
    confirmText: String = stringResource(R.string.common_confirm),
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    val interactionSource = remember { MutableInteractionSource() }
    val scrollState = rememberScrollState()
    val textFieldShape = RoundedCornerShape(16.dp)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .dialogPanel()
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassTextFieldPanel(shape = textFieldShape)
                    .then(if (singleLine) Modifier.horizontalScroll(scrollState) else Modifier),
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = interactionSource,
                decorationBox = { innerTextField ->
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = text,
                        innerTextField = innerTextField,
                        enabled = true,
                        singleLine = singleLine,
                        visualTransformation = VisualTransformation.None,
                        interactionSource = interactionSource,
                        placeholder = {
                            Text(
                                text = placeholder,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                            unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                            focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        container = {
                            OutlinedTextFieldDefaults.Container(
                                enabled = true,
                                isError = false,
                                interactionSource = interactionSource,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = liquidGlassTextFieldBorderColor(
                                        MaterialTheme.colorScheme.primary
                                    ),
                                    unfocusedBorderColor = liquidGlassTextFieldBorderColor(
                                        MaterialTheme.colorScheme.outline
                                    ),
                                    focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                                    unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent)
                                ),
                                shape = textFieldShape
                            )
                        }
                    )
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onConfirm(text) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = liquidGlassButtonContainerColor(MaterialTheme.colorScheme.primary),
                    contentColor = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                ),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text(
                    text = confirmText,
                    fontWeight = FontWeight.Bold,
                    color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Neutral500)
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

internal fun resolveVisibleSelectedPackages(
    selectedPackages: Set<String>,
    visiblePackages: Set<String>,
    originalSelectedPackages: Set<String> = emptySet()
): List<String> {
    val invisibleKept = originalSelectedPackages.filter { it !in visiblePackages }
    val visibleKept = selectedPackages.filter { it in visiblePackages }
    return (invisibleKept + visibleKept).sorted()
}

@Composable
fun SingleSelectDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    optionsHeight: androidx.compose.ui.unit.Dp? = null,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Use selectedIndex as the initial value, but update it when selectedIndex changes
    var tempSelectedIndex by remember(selectedIndex) { mutableStateOf(selectedIndex) }
    val canConfirm = tempSelectedIndex in options.indices

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .dialogPanel()
                .padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .then(
                        if (optionsHeight != null) {
                            Modifier.height(optionsHeight)
                        } else {
                            Modifier.weight(weight = 1f, fill = false) // Allow flexible height but constrained by screen
                        }
                    )
                    .verticalScroll(rememberScrollState())
            ) {
                options.forEachIndexed { index, option ->
                    val isSelected = index == tempSelectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .dialogOptionPanel(isSelected)
                            .clickable { tempSelectedIndex = index }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { if (canConfirm) onSelect(tempSelectedIndex) },
                enabled = canConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = liquidGlassButtonContainerColor(MaterialTheme.colorScheme.primary),
                    contentColor = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                ),
                shape = RoundedCornerShape(25.dp)
            ) {
                Text(
                    text = stringResource(R.string.common_ok),
                    fontWeight = FontWeight.Bold,
                    color = liquidGlassButtonContentColor(MaterialTheme.colorScheme.onPrimary)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}
