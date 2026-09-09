package org.olcbox.app.ui.localization

import android.content.Context
import org.olcbox.app.ui.settings.AppLanguagePreference
import org.olcbox.app.ui.settings.readStoredAppLanguagePreference

fun Context.androidUiText(
    text: String,
    languagePreference: AppLanguagePreference? = null
): String {
    val systemLanguage = resources.configuration.locales.get(0)?.language.orEmpty()
    val language = (languagePreference ?: readStoredAppLanguagePreference()).resolve(systemLanguage)
    return localizeUiText(text, language)
}
