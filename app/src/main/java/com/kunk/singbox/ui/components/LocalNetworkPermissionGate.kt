package com.kunk.singbox.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kunk.singbox.R
import com.kunk.singbox.utils.LocalNetworkPermission

@Composable
fun rememberLocalNetworkPermissionRequest(): ((() -> Unit) -> Unit) {
    val context = LocalContext.current
    val deniedMessage = stringResource(R.string.connection_settings_local_network_permission_denied)
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val action = pendingAction
        pendingAction = null
        if (LocalNetworkPermission.hasPermission(context)) {
            action?.invoke()
        } else {
            AppNotificationManager.showMessage(context, deniedMessage, SnackbarDuration.Long)
        }
    }

    return { action ->
        if (LocalNetworkPermission.hasPermission(context)) {
            action()
        } else {
            pendingAction = action
            launcher.launch(LocalNetworkPermission.requiredPermissions())
        }
    }
}
