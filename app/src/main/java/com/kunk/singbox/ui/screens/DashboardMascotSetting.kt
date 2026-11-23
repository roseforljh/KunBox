package com.kunk.singbox.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kunk.singbox.R
import com.kunk.singbox.repository.DashboardMascotStore
import com.kunk.singbox.repository.MascotCrop
import com.kunk.singbox.repository.MascotPhoto
import com.kunk.singbox.ui.components.AppNotificationManager
import com.kunk.singbox.ui.components.SettingItem
import com.kunk.singbox.ui.theme.liquidGlassDialogContainerColor
import com.kunk.singbox.ui.theme.liquidGlassDialogPanel
import kotlinx.coroutines.launch

@Composable
internal fun DashboardMascotSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var pickedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var photo by remember { mutableStateOf<MascotPhoto?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    fun clearPreview() {
        pickedUri = null
        photo = null
    }

    LaunchedEffect(pickedUri) {
        val uri = pickedUri ?: return@LaunchedEffect
        photo = DashboardMascotStore.preview(context, uri)
        if (photo == null) {
            clearPreview()
            AppNotificationManager.showMessage(context, context.getString(R.string.settings_dashboard_mascot_failed))
        }
    }

    fun setMascot(selected: MascotPhoto? = null, crop: MascotCrop? = null) {
        if (isSaving) return
        isSaving = true
        scope.launch {
            try {
                if (saveMascot(context, selected, crop)) clearPreview()
            } finally {
                isSaving = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { pickedUri = it }
    }
    val isLoading = pickedUri != null || isSaving
    val subtitle = if (isLoading) R.string.common_loading else R.string.settings_dashboard_mascot_subtitle
    SettingItem(
        title = stringResource(R.string.settings_dashboard_mascot),
        subtitle = stringResource(subtitle),
        icon = Icons.Rounded.Photo,
        enabled = !isLoading,
        onClick = { showDialog = true }
    )
    if (showDialog) {
        DashboardMascotDialog(
            onChoose = {
                showDialog = false
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onRestore = {
                showDialog = false
                setMascot()
            },
            onDismiss = { showDialog = false }
        )
    }
    photo?.let { selected ->
        DashboardMascotPreviewDialog(
            photo = selected,
            isSaving = isSaving,
            onConfirm = { crop -> setMascot(selected, crop) },
            onDismiss = ::clearPreview
        )
    }
}

private suspend fun saveMascot(context: Context, photo: MascotPhoto?, crop: MascotCrop?): Boolean {
    val saved = DashboardMascotStore.set(context, photo, crop)
    val message = when {
        !saved -> R.string.settings_dashboard_mascot_failed
        photo == null -> R.string.settings_dashboard_mascot_restored
        else -> R.string.settings_dashboard_mascot_updated
    }
    AppNotificationManager.showMessage(context, context.getString(message))
    return saved
}

@Composable
private fun DashboardMascotDialog(onChoose: () -> Unit, onRestore: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.liquidGlassDialogPanel(),
        containerColor = liquidGlassDialogContainerColor(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_dashboard_mascot)) },
        text = { Text(stringResource(R.string.settings_dashboard_mascot_subtitle)) },
        confirmButton = {
            TextButton(onClick = onChoose) {
                Text(stringResource(R.string.settings_dashboard_mascot_choose))
            }
        },
        dismissButton = {
            TextButton(onClick = onRestore) {
                Text(stringResource(R.string.settings_dashboard_mascot_restore))
            }
        }
    )
}
