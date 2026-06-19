package com.kunk.singbox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kunk.singbox.R
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.ui.theme.liquidGlassDialogContainerColor
import com.kunk.singbox.ui.theme.liquidGlassDialogPanel
import com.kunk.singbox.ui.theme.liquidGlassRadioButtonColors
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel
import com.kunk.singbox.ui.theme.liquidGlassTextButtonPanel

sealed class SelectProfileTarget {
    data class ExistingProfile(val profileId: String) : SelectProfileTarget()
    data class NewProfile(val profileName: String) : SelectProfileTarget()
}

@Suppress("CognitiveComplexMethod", "LongMethod")
@Composable
fun SelectProfileDialog(
    profiles: List<ProfileUi>,
    onConfirm: (target: SelectProfileTarget) -> Unit,
    onDismiss: () -> Unit
) {
    var isCreatingNew by rememberSaveable { mutableStateOf(false) }
    var newProfileName by rememberSaveable { mutableStateOf("") }
    var selectedProfileId by rememberSaveable { mutableStateOf(profiles.firstOrNull()?.id) }
    val textFieldShape = RoundedCornerShape(12.dp)

    LaunchedEffect(profiles) {
        if (profiles.isNotEmpty() && profiles.none { it.id == selectedProfileId }) {
            selectedProfileId = profiles.first().id
        }
    }

    val isValid = (isCreatingNew && newProfileName.isNotBlank()) ||
        (!isCreatingNew && selectedProfileId != null)

    AlertDialog(
        modifier = Modifier.liquidGlassDialogPanel(),
        containerColor = liquidGlassDialogContainerColor(),
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.nodes_add_to_profile),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 250.dp)
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    isCreatingNew = false
                                    selectedProfileId = profile.id
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = !isCreatingNew && selectedProfileId == profile.id,
                                onClick = {
                                    isCreatingNew = false
                                    selectedProfileId = profile.id
                                },
                                colors = liquidGlassRadioButtonColors(
                                    selectedColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isCreatingNew = true }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isCreatingNew,
                                onClick = { isCreatingNew = true },
                                colors = liquidGlassRadioButtonColors(
                                    selectedColor = MaterialTheme.colorScheme.primary
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.nodes_add_create_new_profile),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                if (isCreatingNew) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.nodes_add_new_profile_name_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlassTextFieldPanel(shape = textFieldShape),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                            unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                            focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent)
                        ),
                        shape = textFieldShape
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.liquidGlassTextButtonPanel(enabled = isValid),
                onClick = {
                    val target = if (isCreatingNew) {
                        SelectProfileTarget.NewProfile(newProfileName.trim())
                    } else {
                        val profileId = selectedProfileId ?: return@TextButton
                        SelectProfileTarget.ExistingProfile(profileId)
                    }
                    onConfirm(target)
                },
                enabled = isValid
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.liquidGlassTextButtonPanel(),
                onClick = onDismiss
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
