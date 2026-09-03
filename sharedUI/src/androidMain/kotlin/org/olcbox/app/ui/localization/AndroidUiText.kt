package org.olcbox.app.ui.localization

import android.content.Context

fun Context.androidUiText(text: String): String {
    val language = resources.configuration.locales.get(0)?.language.orEmpty()
    return localizeUiText(text, language)
}
