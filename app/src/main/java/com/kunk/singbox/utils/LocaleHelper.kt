package com.kunk.singbox.utils

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.kunk.singbox.model.AppLanguage
import java.util.Locale

object LocaleHelper {

    private const val SETTINGS_PREFS = "settings"
    private const val LANGUAGE_CACHE_KEY = "app_language_cache"

    fun wrapFromCache(context: Context): Context {
        val languageName = context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getString(LANGUAGE_CACHE_KEY, null)
        val language = runCatching { AppLanguage.valueOf(languageName.orEmpty()) }
            .getOrDefault(AppLanguage.SYSTEM)
        return wrap(context, language)
    }

    fun setLocale(context: Context, language: AppLanguage): Context {
        val locale = when (language) {
            AppLanguage.SYSTEM -> getSystemLocale()
            AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.ENGLISH -> Locale.ENGLISH
        }

        return updateResources(context, locale)
    }

    private fun getSystemLocale(): Locale {
        return LocaleList.getDefault().get(0)
    }

    private fun updateResources(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)

        configuration.setLocales(LocaleList(locale))

        return context.createConfigurationContext(configuration)
    }

    fun getLanguageDisplayName(language: AppLanguage): String {
        return when (language) {
            AppLanguage.SYSTEM -> "System Default"
            AppLanguage.CHINESE -> "简体中文"
            AppLanguage.ENGLISH -> "English"
        }
    }

    fun wrap(context: Context, language: AppLanguage): Context {
        return setLocale(context, language)
    }
}
