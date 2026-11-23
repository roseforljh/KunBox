package com.kunk.singbox.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kunk.singbox.R
import com.kunk.singbox.repository.MascotCrop
import com.kunk.singbox.repository.MascotPhoto
import com.kunk.singbox.repository.drawMascotCrop
import com.kunk.singbox.ui.theme.liquidGlassDialogContainerColor
import com.kunk.singbox.ui.theme.liquidGlassDialogPanel

private val mascotCropSaver = listSaver<MascotCrop, Float>(
    save = { listOf(it.left, it.top, it.side) },
    restore = { MascotCrop(it[0], it[1], it[2]) }
)

@Composable
internal fun DashboardMascotPreviewDialog(
    photo: MascotPhoto,
    isSaving: Boolean,
    onConfirm: (MascotCrop?) -> Unit,
    onDismiss: () -> Unit
) {
    val bitmap = photo.bitmap
    val imageSize = IntSize(bitmap.width, bitmap.height)
    var crop by rememberSaveable(bitmap, stateSaver = mascotCropSaver) {
        mutableStateOf(MascotCrop.centered(imageSize))
    }
    val title = stringResource(R.string.settings_dashboard_mascot_preview)
    AlertDialog(
        modifier = Modifier.liquidGlassDialogPanel(),
        containerColor = liquidGlassDialogContainerColor(),
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val frame = Modifier.widthIn(max = 232.dp).fillMaxWidth().aspectRatio(1f)
                if (photo.transparentBackground) {
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(bitmap = image, contentDescription = title, modifier = frame)
                } else {
                    Canvas(
                        frame.semantics { contentDescription = title }.pointerInput(bitmap) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val edge = size.width.toFloat()
                                crop = crop.transform(imageSize, centroid / edge, pan / edge, zoom)
                            }
                        }
                    ) {
                        drawIntoCanvas { it.nativeCanvas.drawMascotCrop(bitmap, crop, size.width) }
                    }
                }
                val hint = if (photo.transparentBackground) R.string.settings_dashboard_mascot_transparent_hint
                else R.string.settings_dashboard_mascot_crop_hint
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !isSaving, onClick = { onConfirm(crop.takeUnless { photo.transparentBackground }) }) {
                Text(stringResource(if (isSaving) R.string.common_loading else R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
