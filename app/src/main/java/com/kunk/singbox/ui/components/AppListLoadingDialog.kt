package com.kunk.singbox.ui.components

import com.kunk.singbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kunk.singbox.repository.InstalledAppsRepository
import com.kunk.singbox.ui.theme.Divider
import com.kunk.singbox.ui.theme.PureWhite
import com.kunk.singbox.ui.theme.SurfaceCard
import com.kunk.singbox.ui.theme.TextPrimary
import com.kunk.singbox.ui.theme.TextSecondary
import com.kunk.singbox.ui.theme.isLiquidGlassTheme
import com.kunk.singbox.ui.theme.liquidGlassMutedContentColor
import com.kunk.singbox.ui.theme.liquidGlassProgressColor
import com.kunk.singbox.ui.theme.liquidGlassProgressTrackColor
import com.kunk.singbox.ui.theme.liquidGlassStrongContentColor
import com.kunk.singbox.ui.theme.LiquidGlassDialogEffect

import com.kunk.singbox.ui.theme.liquidGlassDialogPanel

@Composable
private fun Modifier.loadingDialogPanel(shape: RoundedCornerShape = RoundedCornerShape(28.dp)): Modifier {
    return this.liquidGlassDialogPanel(shape = shape, shadowElevation = 24.dp)
        .then(if (!isLiquidGlassTheme()) Modifier.background(SurfaceCard, shape) else Modifier)
}

/**
 */
@Composable
fun AppListLoadingDialog(
    loadingState: InstalledAppsRepository.LoadingState
) {

    if (loadingState !is InstalledAppsRepository.LoadingState.Loading) return

    Dialog(
        onDismissRequest = { /* 加载中，禁止关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        LiquidGlassDialogEffect()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .loadingDialogPanel()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            CircularProgressIndicator(
                progress = { loadingState.progress },
                modifier = Modifier.size(72.dp),
                color = liquidGlassProgressColor(PureWhite),
                strokeWidth = 6.dp,
                trackColor = liquidGlassProgressTrackColor(Divider)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.app_list_loading),
                style = MaterialTheme.typography.titleMedium,
                color = liquidGlassStrongContentColor(TextPrimary)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.app_list_loaded, loadingState.current, loadingState.total),
                style = MaterialTheme.typography.bodyMedium,
                color = liquidGlassMutedContentColor(TextSecondary)
            )

            Spacer(modifier = Modifier.height(20.dp))

            LinearProgressIndicator(
                progress = { loadingState.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = liquidGlassProgressColor(PureWhite),
                trackColor = liquidGlassProgressTrackColor(Divider)
            )
        }
    }
}

/**
 */
@Composable
fun SimpleLoadingDialog(
    show: Boolean,
    message: String = stringResource(R.string.common_loading)
) {
    if (!show) return

    Dialog(
        onDismissRequest = { /* 加载中，禁止关闭 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        LiquidGlassDialogEffect()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .loadingDialogPanel()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                color = liquidGlassProgressColor(PureWhite),
                strokeWidth = 5.dp,
                trackColor = liquidGlassProgressTrackColor(Color.Transparent)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = liquidGlassStrongContentColor(TextPrimary)
            )
        }
    }
}
