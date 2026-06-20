package com.kunk.singbox.ui.theme

import android.app.Activity
import android.graphics.Color
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.kunk.singbox.model.AppThemeMode
import com.kunk.singbox.model.AppThemeStyle

// Force Dark Theme for OLED Minimalist Look
private val OLEDColorScheme = darkColorScheme(
    primary = AccentWhite,
    onPrimary = AppBackground,
    secondary = Neutral500,
    onSecondary = PureWhite,
    tertiary = Neutral700,
    background = AppBackground,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceCardAlt,
    onSurfaceVariant = TextSecondary,
    outline = Divider,
    error = Destructive
)

// Light Theme
private val LightColorScheme = lightColorScheme(
    primary = LightTextPrimary,
    onPrimary = LightSurface,
    secondary = LightTextSecondary,
    onSecondary = LightTextPrimary,
    tertiary = LightTextSecondary,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightDivider,
    error = Destructive
)

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingBoxTheme(
    appTheme: AppThemeMode = AppThemeMode.SYSTEM,
    appThemeStyle: AppThemeStyle = AppThemeStyle.DEFAULT,
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val useDarkTheme = when (appTheme) {
        AppThemeMode.SYSTEM -> isSystemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    val colorScheme = if (useDarkTheme) OLEDColorScheme else LightColorScheme
    val rippleConfiguration = if (appThemeStyle == AppThemeStyle.LIQUID_GLASS) {
        null
    } else {
        RippleConfiguration()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window

            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            // 启用沉浸式布局，内容延伸到系统栏区域。
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !useDarkTheme
            insetsController.isAppearanceLightNavigationBars = !useDarkTheme
        }
    }

    CompositionLocalProvider(
        LocalAppThemeStyle provides appThemeStyle,
        LocalRippleConfiguration provides rippleConfiguration
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
