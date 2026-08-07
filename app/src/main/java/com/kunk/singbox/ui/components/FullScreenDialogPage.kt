package com.kunk.singbox.ui.components

import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.kunk.singbox.R
import com.kunk.singbox.ui.theme.liquidGlassBackdrop
import com.kunk.singbox.ui.theme.liquidGlassIconButtonPanel
import com.kunk.singbox.ui.theme.liquidGlassScreenContainerColor

@Composable
fun floatingPageHeaderContentPadding(): Dp {
    return WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding() + 72.dp
}

@Composable
private fun floatingMainPageHeaderContentPadding(): Dp {
    return WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding() + 64.dp
}

@Composable
fun FloatingMainPageLayout(
    title: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    supportingContentHeight: Dp = 0.dp,
    supportingContent: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.(contentTopPadding: Dp) -> Unit
) {
    val headerContentPadding = floatingMainPageHeaderContentPadding()
    val supportingHeight = if (supportingContent == null) 0.dp else supportingContentHeight
    val contentTopPadding = headerContentPadding + supportingHeight

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            content(contentTopPadding)
        }
        FloatingPageTopGradient(
            height = contentTopPadding + 32.dp,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        Column(modifier = Modifier.align(Alignment.TopCenter)) {
            FloatingMainPageHeader(
                title = title,
                actions = actions
            )
            if (supportingContent != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(supportingContentHeight),
                    contentAlignment = Alignment.Center
                ) {
                    supportingContent()
                }
            }
        }
    }
}

@Composable
private fun FloatingMainPageHeader(
    title: String,
    actions: (@Composable RowScope.() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (actions != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

@Composable
fun FloatingPageLayout(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    circularAction: Boolean = true,
    supportingContentHeight: Dp = 0.dp,
    supportingContent: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.(contentTopPadding: Dp) -> Unit
) {
    FloatingPageLayers(
        title = title,
        onBack = onBack,
        modifier = modifier,
        actions = actions,
        circularAction = circularAction,
        supportingContentHeight = supportingContentHeight,
        supportingContent = supportingContent,
        content = content
    )
}

@Composable
fun FullScreenDialogPage(
    title: String,
    onDismiss: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null,
    supportingContentHeight: Dp = 0.dp,
    supportingContent: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.(contentTopPadding: Dp) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        FullScreenDialogWindowEffect()
        FloatingPageLayers(
            title = title,
            onBack = onDismiss,
            actions = actions,
            circularAction = actions != null,
            supportingContentHeight = supportingContentHeight,
            supportingContent = supportingContent,
            modifier = Modifier
                .fillMaxSize()
                .liquidGlassBackdrop()
                .background(
                    liquidGlassScreenContainerColor(MaterialTheme.colorScheme.background)
                ),
            content = content
        )
    }
}

@Composable
fun TopOnlySupportingContent(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxSize(),
        enter = EnterTransition.None,
        exit = ExitTransition.None
    ) {
        val contentAlpha by transition.animateFloat(
            transitionSpec = {
                tween(durationMillis = if (targetState == EnterExitState.Visible) 240 else 180)
            },
            label = "top_only_supporting_content_alpha"
        ) { state ->
            if (state == EnterExitState.Visible) 1f else 0f
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = contentAlpha
                    // 逐绘制调制透明度，避免离屏图层裁掉控件边界外的模糊阴影。
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun FloatingPageLayers(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    circularAction: Boolean,
    supportingContentHeight: Dp = 0.dp,
    supportingContent: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.(contentTopPadding: Dp) -> Unit
) {
    val headerContentPadding = floatingPageHeaderContentPadding()
    val supportingHeight = if (supportingContent == null) 0.dp else supportingContentHeight
    val contentTopPadding = headerContentPadding + supportingHeight
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            content(contentTopPadding)
        }
        FloatingPageTopGradient(
            height = contentTopPadding + 32.dp,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        Column(modifier = Modifier.align(Alignment.TopCenter)) {
            FloatingPageHeader(
                title = title,
                onBack = onBack,
                actions = actions,
                circularAction = circularAction
            )
            if (supportingContent != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(supportingContentHeight),
                    contentAlignment = Alignment.Center
                ) {
                    supportingContent()
                }
            }
        }
    }
}

@Composable
private fun FloatingPageTopGradient(
    height: Dp,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.background
    val endY = with(LocalDensity.current) { height.toPx() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to background,
                        0.46f to background.copy(alpha = 0.68f),
                        0.76f to background.copy(alpha = 0.20f),
                        1f to background.copy(alpha = 0f)
                    ),
                    endY = endY
                )
            )
    )
}

@Composable
@Suppress("LongMethod")
fun FloatingPageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    circularAction: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            modifier = Modifier
                .size(48.dp)
                .liquidGlassIconButtonPanel(shadowElevation = 6.dp),
            onClick = onBack
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .widthIn(min = 96.dp, max = 220.dp)
                    .liquidGlassIconButtonPanel(
                        shape = RoundedCornerShape(percent = 50),
                        shadowElevation = 6.dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    modifier = Modifier.padding(horizontal = 14.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (actions != null) {
            Spacer(modifier = Modifier.width(8.dp))
            val actionShape = if (circularAction) CircleShape else RoundedCornerShape(percent = 50)
            Row(
                modifier = Modifier
                    .then(
                        if (circularAction) {
                            Modifier.size(48.dp)
                        } else {
                            Modifier.height(48.dp)
                        }
                    )
                    .liquidGlassIconButtonPanel(
                        shape = actionShape,
                        shadowElevation = 6.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        } else {
            Spacer(modifier = Modifier.width(56.dp))
        }
    }
}

@Composable
fun FullScreenDialogWindowEffect() {
    val view = LocalView.current
    val useLightSystemBars = MaterialTheme.colorScheme.background.luminance() > 0.5f

    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = useLightSystemBars
            isAppearanceLightNavigationBars = useLightSystemBars
        }
    }
}
