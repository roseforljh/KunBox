package com.kunk.singbox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kunk.singbox.R
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.FloatingPageLayout
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.navigation.Screen
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassOutlinedTextFieldColors
import com.kunk.singbox.ui.theme.liquidGlassPanel
import com.kunk.singbox.ui.theme.liquidGlassPressFeedback
import com.kunk.singbox.ui.theme.liquidGlassRadioButtonColors
import com.kunk.singbox.ui.theme.liquidGlassScreenContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel
import com.kunk.singbox.viewmodel.NodesViewModel
import kotlinx.coroutines.flow.collectLatest

private sealed class AddNodeTarget {
    data class ExistingProfile(val profileId: String) : AddNodeTarget()
    data class NewProfile(val profileName: String) : AddNodeTarget()
}
private data class NodeProtocolOption(
    val name: String,
    val value: String
)

private val nodeProtocolOptions = listOf(
    NodeProtocolOption("VMess", "vmess"),
    NodeProtocolOption("VLESS", "vless"),
    NodeProtocolOption("Trojan", "trojan"),
    NodeProtocolOption("Shadowsocks", "shadowsocks"),
    NodeProtocolOption("Hysteria 2", "hysteria2"),
    NodeProtocolOption("Hysteria", "hysteria"),
    NodeProtocolOption("TUIC", "tuic"),
    NodeProtocolOption("Naive", "naive"),
    NodeProtocolOption("WireGuard", "wireguard"),
    NodeProtocolOption("SSH", "ssh"),
    NodeProtocolOption("AnyTLS", "anytls"),
    NodeProtocolOption("SOCKS", "socks"),
    NodeProtocolOption("HTTP", "http")
)

@Composable
private fun Modifier.addNodeTargetOptionPanel(isSelected: Boolean): Modifier {
    return if (isLiquidGlassTheme()) {
        if (isSelected) {
            liquidGlassPanel(
                shape = RoundedCornerShape(14.dp),
                selected = true,
                shadowElevation = 4.dp
            )
        } else {
            clip(RoundedCornerShape(14.dp))
        }
    } else {
        this
    }
}

@Composable
private fun Modifier.protocolIconPanel(): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return if (isLiquidGlassTheme()) {
        liquidGlassPanel(
            shape = shape,
            selected = true,
            shadowElevation = 4.dp
        )
    } else {
        background(MaterialTheme.colorScheme.surfaceVariant, shape)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
fun AddNodeScreen(
    navController: NavController,
    viewModel: NodesViewModel
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var nodeLink by rememberSaveable { mutableStateOf("") }
    var isCreatingNew by rememberSaveable { mutableStateOf(false) }
    var newProfileName by rememberSaveable { mutableStateOf("") }
    var selectedProfileId by rememberSaveable { mutableStateOf(profiles.firstOrNull()?.id) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isScreenActive by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose { isScreenActive = false }
    }

    LaunchedEffect(profiles) {
        if (profiles.isNotEmpty() && profiles.none { it.id == selectedProfileId }) {
            selectedProfileId = profiles.first().id
        }
    }

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collectLatest { message ->
            AppNotificationManager.showMessage(context, message)
        }
    }

    val isValid = nodeLink.isNotBlank() && (
        (isCreatingNew && newProfileName.isNotBlank()) ||
            (!isCreatingNew && selectedProfileId != null)
        ) && !isSubmitting
    val submitNode: () -> Unit = submit@{
        val target = if (isCreatingNew) {
            AddNodeTarget.NewProfile(newProfileName.trim())
        } else {
            val profileId = selectedProfileId ?: return@submit
            AddNodeTarget.ExistingProfile(profileId)
        }
        isSubmitting = true
        when (target) {
            is AddNodeTarget.ExistingProfile -> viewModel.addNode(
                content = nodeLink,
                targetProfileId = target.profileId
            ) { success ->
                if (isScreenActive) {
                    isSubmitting = false
                    if (success) navController.popBackStack()
                }
            }
            is AddNodeTarget.NewProfile -> viewModel.addNode(
                content = nodeLink,
                newProfileName = target.profileName
            ) { success ->
                if (isScreenActive) {
                    isSubmitting = false
                    if (success) navController.popBackStack()
                }
            }
        }
    }
    val navigationBarPadding = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    FloatingPageLayout(
        title = stringResource(R.string.nodes_add),
        onBack = navController::popBackStack,
        actions = {
            IconButton(
                modifier = Modifier.fillMaxSize(),
                onClick = submitNode,
                enabled = isValid
            ) {
                Text(
                    text = stringResource(R.string.common_add),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(
                        alpha = if (isValid) 1f else 0.38f
                    )
                )
            }
        }
    ) { headerContentPadding ->
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = liquidGlassScreenContainerColor(MaterialTheme.colorScheme.background)
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = headerContentPadding + 16.dp,
                    end = 16.dp,
                    bottom = 24.dp + navigationBarPadding
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.nodes_add_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val textFieldShape = RoundedCornerShape(16.dp)
                    OutlinedTextField(
                        value = nodeLink,
                        onValueChange = { nodeLink = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlassTextFieldPanel(shape = textFieldShape),
                        minLines = 4,
                        maxLines = 8,
                        colors = liquidGlassOutlinedTextFieldColors(
                            focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                            unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                            focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent)
                        ),
                        shape = textFieldShape
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.nodes_add_to_profile),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                items(profiles, key = { it.id }) { profile ->
                    AddNodeTargetRow(
                        profile = profile,
                        isSelected = !isCreatingNew && selectedProfileId == profile.id,
                        onClick = {
                            isCreatingNew = false
                            selectedProfileId = profile.id
                        }
                    )
                }

                item {
                    val isSelected = isCreatingNew
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .addNodeTargetOptionPanel(isSelected)
                            .liquidGlassPressFeedback(
                                label = "liquid_glass_add_node_new_profile_page_scale"
                            ) {
                                isCreatingNew = true
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
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

                if (isCreatingNew) {
                    item {
                        val textFieldShape = RoundedCornerShape(16.dp)
                        OutlinedTextField(
                            value = newProfileName,
                            onValueChange = { newProfileName = it },
                            placeholder = { Text(stringResource(R.string.nodes_add_new_profile_name_hint)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlassTextFieldPanel(shape = textFieldShape),
                            singleLine = true,
                            colors = liquidGlassOutlinedTextFieldColors(
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
                }
            }
        }
    }
}

@Composable
private fun AddNodeTargetRow(
    profile: ProfileUi,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .addNodeTargetOptionPanel(isSelected)
            .liquidGlassPressFeedback(
                label = "liquid_glass_add_node_target_page_scale",
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = liquidGlassRadioButtonColors(selectedColor = MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = profile.name, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun NodeProtocolSelectScreen(
    navController: NavController,
    onProtocolSelected: (String) -> Unit = {
        navController.navigate(Screen.NodeCreate.createRoute(it))
    }
) {
    val navigationBarPadding = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    FloatingPageLayout(
        title = stringResource(R.string.nodes_manual_create),
        onBack = navController::popBackStack
    ) { headerContentPadding ->
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = liquidGlassScreenContainerColor(MaterialTheme.colorScheme.background)
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = headerContentPadding + 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + navigationBarPadding
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.nodes_select_protocol),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(nodeProtocolOptions, key = { it.value }) { protocol ->
                    StandardCard(
                        onClick = {
                            onProtocolSelected(protocol.value)
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .protocolIconPanel(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Code,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = protocol.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = protocol.value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
