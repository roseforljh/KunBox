package com.kunk.singbox.ui.theme

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

@Composable
fun LiquidGlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    if (isLiquidGlassTheme()) {
        val menuShape = RoundedCornerShape(16.dp)

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = Modifier
                // 关键：在外部加上 padding，这样 liquidGlassPanel 的阴影（hollowShadow）绘制在 padding 内部，
                // 就绝不会被 DropdownMenu 内部的 Surface 裁剪掉了！
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .liquidGlassPanel(shape = menuShape, shadowElevation = 12.dp)
                .then(modifier), // 这里的 modifier 控制具体大小（如 width(100.dp)）
            containerColor = Color.Transparent,
            shape = menuShape,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp, // 取消原生阴影，使用液态玻璃自带的阴影
        ) {
            val view = LocalView.current
            LaunchedEffect(view) {
                updatePopupBlur(view)
            }
            content()
        }
    } else {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            content = content
        )
    }
}

private fun updatePopupBlur(view: View) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

    val popupWindow = findPopupWindow(view) ?: return
    val params = popupWindow.layoutParams as? WindowManager.LayoutParams ?: return
    params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
    params.blurBehindRadius = 80

    val windowManager = view.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    windowManager.updateViewLayout(popupWindow, params)
}

private fun findPopupWindow(view: View): View? {
    var parent: View? = view
    while (parent != null) {
        if (parent.layoutParams is WindowManager.LayoutParams) {
            return parent
        }
        parent = parent.parent as? View
    }
    return null
}

@Composable
fun liquidGlassDropdownMenuItemColors(): MenuItemColors {
    return if (isLiquidGlassTheme()) {
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        MenuDefaults.itemColors(
            textColor = liquidGlassMenuColor(isDark, darkAlpha = 0.9f, lightAlpha = 0.85f),
            leadingIconColor = liquidGlassMenuColor(isDark, darkAlpha = 0.8f, lightAlpha = 0.7f),
            trailingIconColor = liquidGlassMenuColor(isDark, darkAlpha = 0.8f, lightAlpha = 0.7f),
            disabledTextColor = liquidGlassMenuColor(isDark, darkAlpha = 0.38f, lightAlpha = 0.38f),
            disabledLeadingIconColor = liquidGlassMenuColor(isDark, darkAlpha = 0.38f, lightAlpha = 0.38f),
            disabledTrailingIconColor = liquidGlassMenuColor(isDark, darkAlpha = 0.38f, lightAlpha = 0.38f)
        )
    } else {
        MenuDefaults.itemColors()
    }
}

private fun liquidGlassMenuColor(isDark: Boolean, darkAlpha: Float, lightAlpha: Float): Color {
    val baseColor = if (isDark) Color.White else Color.Black
    return baseColor.copy(alpha = if (isDark) darkAlpha else lightAlpha)
}
