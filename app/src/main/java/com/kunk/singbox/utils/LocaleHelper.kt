package com.kunk.singbox.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.kunk.singbox.model.AppLanguage
import java.util.Locale

object LocaleHelper {

    /**
     * 根据 AppLanguage 设置应用语言
     */
    fun setLocale(context: Context, language: AppLanguage): Context {
        val locale = when (language) {
            AppLanguage.SYSTEM -> getSystemLocale()
            AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.ENGLISH -> Locale.ENGLISH
        }

        return updateResources(context, locale)
    }

    /**
     * 获取系统默认语言
     */
    private fun getSystemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList.getDefault().get(0)
        } else {
            @Suppress("DEPRECATION")
            Locale.getDefault()
        }
    }

    /**
     * 更新 Context 的资源配置
     */
    private fun updateResources(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(locale))
        }
        configuration.setLocale(locale)

        return context.createConfigurationContext(configuration)
    }

    /**
     * 获取当前 AppLanguage 对应的显示名称（本地化）
     */
    fun getLanguageDisplayName(language: AppLanguage): String {
        return when (language) {
            AppLanguage.SYSTEM -> "跟随系统"
            AppLanguage.CHINESE -> "简体中文"
            AppLanguage.ENGLISH -> "English"
        }
    }

    /**
     * 包装 Activity 的 Context
     * 在 Activity 的 attachBaseContext 中调用
     */
    fun wrap(context: Context, language: AppLanguage): Context {
        return setLocale(context, language)
    }
}
