package com.kunk.singbox.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import com.kunk.singbox.model.AppThemeStyle
import com.kunk.singbox.repository.SettingsRepository
import kotlin.math.roundToInt

object AppNotificationManager {
    @Suppress("UnusedParameter")
    fun showMessage(
        context: Context,
        message: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        if (message.isBlank()) return

        Handler(Looper.getMainLooper()).post {
            if (isLiquidGlassTheme(context)) {
                showLiquidGlassToast(context.applicationContext, message, duration)
            } else {
                Toast.makeText(
                    context.applicationContext,
                    message,
                    toastDuration(duration)
                ).show()
            }
        }
    }

    private fun isLiquidGlassTheme(context: Context): Boolean {
        return SettingsRepository.getInstance(context).settings.value.appThemeStyle == AppThemeStyle.LIQUID_GLASS
    }

    @Suppress("DEPRECATION")
    private fun showLiquidGlassToast(
        context: Context,
        message: String,
        duration: SnackbarDuration
    ) {
        Toast(context).apply {
            view = liquidGlassToastView(context, message)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, context.dp(96))
            this.duration = toastDuration(duration)
        }.show()
    }

    private fun liquidGlassToastView(context: Context, message: String): LinearLayout {
        val horizontalPadding = context.dp(18)
        val verticalPadding = context.dp(11)

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = liquidGlassToastBackground(context)
            elevation = context.dp(12).toFloat()
            minimumHeight = context.dp(44)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            addView(liquidGlassToastText(context, message))
        }
    }

    private fun liquidGlassToastText(context: Context, message: String): TextView {
        return TextView(context).apply {
            text = message
            setTextColor(liquidGlassToastTextColor(context))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun liquidGlassToastBackground(context: Context): GradientDrawable {
        val darkTheme = context.isDarkTheme()
        val colors = if (darkTheme) {
            intArrayOf(
                Color.argb(56, 255, 255, 255),
                Color.argb(126, 32, 32, 36),
                Color.argb(92, 10, 10, 12)
            )
        } else {
            intArrayOf(
                Color.argb(172, 255, 255, 255),
                Color.argb(146, 245, 245, 247),
                Color.argb(118, 235, 238, 242)
            )
        }
        val strokeColor = if (darkTheme) {
            Color.argb(78, 255, 255, 255)
        } else {
            Color.argb(132, 255, 255, 255)
        }

        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = context.dp(22).toFloat()
            setStroke(context.dp(1), strokeColor)
        }
    }

    private fun liquidGlassToastTextColor(context: Context): Int {
        return if (context.isDarkTheme()) {
            Color.argb(238, 255, 255, 255)
        } else {
            Color.argb(238, 29, 29, 31)
        }
    }

    private fun Context.isDarkTheme(): Boolean {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun Context.dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    private fun toastDuration(duration: SnackbarDuration): Int {
        return if (duration == SnackbarDuration.Long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
    }
}
