@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.ui.screens

import com.kunk.singbox.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.kunk.singbox.ui.theme.LiquidGlassDialogEffect
import com.kunk.singbox.utils.dns.DnsResolver
import com.kunk.singbox.utils.parser.NodeLinkParser
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kunk.singbox.repository.*
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.StandardCard
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassButtonColors
import com.kunk.singbox.ui.theme.liquidGlassButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassProgressColor
import com.kunk.singbox.ui.theme.liquidGlassProgressTrackColor
import com.kunk.singbox.ui.theme.liquidGlassSwitchColors
import com.kunk.singbox.ui.theme.liquidGlassOutlinedTextFieldColors
import com.kunk.singbox.ui.theme.liquidGlassTextFieldBorderColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldContainerColor
import com.kunk.singbox.ui.theme.liquidGlassTextFieldPanel
import com.kunk.singbox.ui.theme.liquidGlassTextButtonContentColor
import com.kunk.singbox.ui.theme.liquidGlassTextButtonColors
import com.kunk.singbox.ui.theme.liquidGlassTextButtonPanel

@Composable
internal fun ImportSelectionDialog(
    onDismiss: () -> Unit,
    onTypeSelected: (ProfileImportType) -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        LiquidGlassDialogEffect()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .profileDialogPanel(RoundedCornerShape(28.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.profiles_add_config),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ImportOptionCard(
                    icon = Icons.Rounded.Link,
                    title = stringResource(R.string.profiles_subscription_link),
                    subtitle = stringResource(R.string.common_import),
                    onClick = { onTypeSelected(ProfileImportType.Subscription) }
                )
                ImportOptionCard(
                    icon = Icons.Rounded.Description,
                    title = stringResource(R.string.profiles_local_file),
                    subtitle = stringResource(R.string.profiles_local_file_subtitle),
                    onClick = { onTypeSelected(ProfileImportType.File) }
                )
                ImportOptionCard(
                    icon = Icons.Rounded.ContentPaste,
                    title = stringResource(R.string.profiles_clipboard),
                    subtitle = stringResource(R.string.profiles_clipboard_subtitle),
                    onClick = { onTypeSelected(ProfileImportType.Clipboard) }
                )
                ImportOptionCard(
                    icon = Icons.Rounded.QrCodeScanner,
                    title = stringResource(R.string.profiles_scan_qrcode),
                    subtitle = stringResource(R.string.profiles_scan_qrcode_subtitle),
                    onClick = { onTypeSelected(ProfileImportType.QRCode) }
                )
                ImportOptionCard(
                    icon = Icons.Rounded.DashboardCustomize,
                    title = stringResource(R.string.profiles_custom_config),
                    subtitle = stringResource(R.string.profiles_custom_config_subtitle),
                    onClick = { onTypeSelected(ProfileImportType.Custom) }
                )
            }
        }
    }
}

@Composable
internal fun ImportLoadingProgress(progress: Float?) {
    if (progress != null) {
        androidx.compose.material3.LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = liquidGlassProgressColor(MaterialTheme.colorScheme.primary),
            trackColor = liquidGlassProgressTrackColor(MaterialTheme.colorScheme.outline),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    } else {
        androidx.compose.material3.CircularProgressIndicator(
            color = liquidGlassProgressColor(MaterialTheme.colorScheme.primary),
            trackColor = liquidGlassProgressTrackColor(Color.Transparent)
        )
    }
}

@Composable
internal fun ImportLoadingDialog(message: String, onCancel: () -> Unit = {}) {
    val progress = remember(message) { importLoadingProgress(message) }

    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        LiquidGlassDialogEffect()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .profileDialogPanel(RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ImportLoadingProgress(progress = progress)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(
                onClick = onCancel,
                colors = liquidGlassTextButtonColors(
                    contentColor = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.error,
                        liquidColor = MaterialTheme.colorScheme.error
                    )
                ),
                modifier = Modifier
                    .align(Alignment.End)
                    .liquidGlassTextButtonPanel()
            ) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    color = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.error,
                        liquidColor = MaterialTheme.colorScheme.error
                    )
                )
            }
        }
    }
}

internal fun importLoadingProgress(message: String): Float? {
    val regex = Regex(".*?\\((\\d+)/(\\d+)\\).*")
    val match = regex.find(message) ?: return null
    val (current, total) = match.destructured
    val totalFloat = total.toFloat()
    return if (totalFloat > 0) current.toFloat() / totalFloat else null
}

@Composable
internal fun ImportOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    StandardCard(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
@Composable
internal fun SubscriptionInputDialog(
    initialName: String = "",
    initialUrl: String = "",
    initialAutoUpdateInterval: Int = 0,
    initialDnsPreResolve: Boolean = false,
    initialDnsServer: String? = null,
    initialDnsOverride: String? = null,
    title: String = stringResource(R.string.profiles_add_subscription),
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        url: String,
        autoUpdateInterval: Int,
        dnsPreResolve: Boolean,
        dnsServer: String?,
        dnsOverride: String?
    ) -> Unit
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var autoUpdateEnabled by rememberSaveable(initialAutoUpdateInterval) {
        mutableStateOf(initialAutoUpdateInterval > 0)
    }
    var autoUpdateMinutes by rememberSaveable(initialAutoUpdateInterval) {
        mutableStateOf(
            if (initialAutoUpdateInterval > 0) {
                initialAutoUpdateInterval.toString()
            } else {
                "60"
            }
        )
    }
    var dnsPreResolve by rememberSaveable(initialDnsPreResolve) {
        mutableStateOf(initialDnsPreResolve)
    }
    var dnsServer by rememberSaveable(initialDnsServer) {
        mutableStateOf(initialDnsServer ?: DnsResolver.DOH_CLOUDFLARE)
    }
    var dnsOverrideText by rememberSaveable(initialDnsOverride) {
        mutableStateOf(initialDnsOverride ?: "")
    }
    var showDnsOverride by rememberSaveable(initialDnsOverride) {
        mutableStateOf(!initialDnsOverride.isNullOrBlank())
    }

    val useLiquidGlass = isLiquidGlassTheme()
    val nameLabel = stringResource(R.string.profiles_name_label)
    val urlLabel = stringResource(R.string.profiles_url_label)
    val autoUpdateIntervalLabel = stringResource(R.string.profiles_auto_update_interval)
    val dnsOverrideLabel = "DNS JSON"

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        LiquidGlassDialogEffect()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .profileDialogPanel(RoundedCornerShape(28.dp))
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))

            val nameFieldShape = RoundedCornerShape(16.dp)
            ProfileLiquidGlassTextFieldLabel(text = nameLabel, useLiquidGlass = useLiquidGlass)
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = liquidGlassAwareTextFieldLabel(useLiquidGlass, nameLabel),
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassTextFieldPanel(shape = nameFieldShape),
                singleLine = true,
                shape = nameFieldShape,
                colors = liquidGlassOutlinedTextFieldColors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                    unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                    focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            val urlFieldShape = RoundedCornerShape(16.dp)
            ProfileLiquidGlassTextFieldLabel(text = urlLabel, useLiquidGlass = useLiquidGlass)
            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = liquidGlassAwareTextFieldLabel(useLiquidGlass, urlLabel),
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassTextFieldPanel(shape = urlFieldShape),
                singleLine = true,
                shape = urlFieldShape,
                colors = liquidGlassOutlinedTextFieldColors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                    unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                    focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.profiles_auto_update),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                androidx.compose.material3.Switch(
                    checked = autoUpdateEnabled,
                    onCheckedChange = { autoUpdateEnabled = it },
                    colors = liquidGlassSwitchColors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            AnimatedVisibility(
                visible = autoUpdateEnabled,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 300)
                ),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 300)
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 300)
                )
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    val autoUpdateFieldShape = RoundedCornerShape(16.dp)
                    ProfileLiquidGlassTextFieldLabel(
                        text = autoUpdateIntervalLabel,
                        useLiquidGlass = useLiquidGlass
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = autoUpdateMinutes,
                        onValueChange = { newValue ->

                            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                autoUpdateMinutes = newValue
                            }
                        },
                        label = liquidGlassAwareTextFieldLabel(useLiquidGlass, autoUpdateIntervalLabel),
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlassTextFieldPanel(shape = autoUpdateFieldShape),
                        shape = autoUpdateFieldShape,
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        supportingText = {
                            Text(
                                text = stringResource(R.string.profiles_auto_update_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = liquidGlassOutlinedTextFieldColors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                            unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                            focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.profiles_dns_preresolve),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.profiles_dns_preresolve_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = dnsPreResolve,
                    onCheckedChange = { dnsPreResolve = it },
                    colors = liquidGlassSwitchColors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            AnimatedVisibility(visible = dnsPreResolve) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.profiles_dns_server),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val dnsOptions = listOf(
                        DnsResolver.DOH_CLOUDFLARE to R.string.profiles_dns_server_cloudflare,
                        DnsResolver.DOH_GOOGLE to R.string.profiles_dns_server_google,
                        DnsResolver.DOH_ALIDNS to R.string.profiles_dns_server_alidns
                    )
                    dnsOptions.forEach { (server, labelRes) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .profileGroupPressFeedback(enabled = true) { dnsServer = server }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = dnsServer == server,
                                onClick = { dnsServer = server }
                            )
                            Text(
                                text = stringResource(labelRes),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.profiles_dns_override),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                androidx.compose.material3.Switch(
                    checked = showDnsOverride,
                    onCheckedChange = { showDnsOverride = it },
                    colors = liquidGlassSwitchColors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            androidx.compose.animation.AnimatedVisibility(visible = showDnsOverride) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.profiles_dns_override_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val dnsOverrideFieldShape = RoundedCornerShape(16.dp)
                    ProfileLiquidGlassTextFieldLabel(text = dnsOverrideLabel, useLiquidGlass = useLiquidGlass)
                    androidx.compose.material3.OutlinedTextField(
                        value = dnsOverrideText,
                        onValueChange = { dnsOverrideText = it },
                        label = liquidGlassAwareTextFieldLabel(useLiquidGlass, dnsOverrideLabel),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .liquidGlassTextFieldPanel(shape = dnsOverrideFieldShape),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        shape = dnsOverrideFieldShape,
                        colors = liquidGlassOutlinedTextFieldColors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.primary),
                            unfocusedBorderColor = liquidGlassTextFieldBorderColor(MaterialTheme.colorScheme.outline),
                            focusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            unfocusedContainerColor = liquidGlassTextFieldContainerColor(Color.Transparent),
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            val context = LocalContext.current
            val subscriptionNodeWarningMessage = stringResource(R.string.profiles_subscription_node_warning)
            val invalidNameMessage = stringResource(R.string.profiles_name_invalid)
            val updateIntervalMinMessage = stringResource(R.string.settings_update_interval_min)

            Button(
                onClick = {

                    val isNodeLink = NodeLinkParser.isSupportedLink(url)

                    if (isNodeLink) {
                        AppNotificationManager.showMessage(
                            context = context,
                            message = subscriptionNodeWarningMessage,
                            duration = androidx.compose.material3.SnackbarDuration.Long
                        )
                        return@Button
                    }

                    if (name.contains("://")) {
                        AppNotificationManager.showMessage(context, invalidNameMessage)
                        return@Button
                    }

                    val finalInterval = if (autoUpdateEnabled) {
                        val minutes = autoUpdateMinutes.toIntOrNull() ?: 0
                        if (minutes < 15) {
                            AppNotificationManager.showMessage(
                                context,
                                updateIntervalMinMessage
                            )
                            return@Button
                        }
                        minutes
                    } else {
                        0
                    }

                    val finalDnsOverride = if (showDnsOverride && dnsOverrideText.isNotBlank()) {
                        dnsOverrideText
                    } else {
                        null
                    }
                    ConfigRepository.buildDnsOverrideCompatibilityWarning(finalDnsOverride)?.let { warning ->
                        AppNotificationManager.showMessage(
                            context = context,
                            message = warning,
                            duration = androidx.compose.material3.SnackbarDuration.Long
                        )
                    }

                    onConfirm(
                        name,
                        url,
                        finalInterval,
                        dnsPreResolve,
                        dnsServer.takeIf { dnsPreResolve },
                        finalDnsOverride
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = liquidGlassButtonColors(
                    defaultContainerColor = MaterialTheme.colorScheme.primary,
                    defaultContentColor = MaterialTheme.colorScheme.onPrimary
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

            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .liquidGlassTextButtonPanel(shape = RoundedCornerShape(25.dp)),
                colors = liquidGlassTextButtonColors(
                    contentColor = liquidGlassTextButtonContentColor(
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        liquidColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}
