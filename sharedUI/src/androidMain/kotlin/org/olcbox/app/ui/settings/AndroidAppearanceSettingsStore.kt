package org.olcbox.app.ui.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidAppearanceSettingsStore(context: Context) : AppAppearanceSettingsStore {
    private val saveMutex = Mutex()
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override suspend fun load(): AppAppearanceSettings {
        return loadNow()
    }

    fun loadNow(): AppAppearanceSettings {
        return AppAppearanceSettings(
            language = AppLanguagePreference.fromStored(preferences.getString(KEY_LANGUAGE, null)),
            theme = AppThemePreference.fromStored(preferences.getString(KEY_THEME, null))
        )
    }

    override suspend fun save(settings: AppAppearanceSettings) {
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                preferences.edit()
                    .putString(KEY_LANGUAGE, settings.language.name)
                    .putString(KEY_THEME, settings.theme.name)
                    .commit()
            }
        }
    }

}

internal fun Context.readStoredAppLanguagePreference(): AppLanguagePreference {
    return AppLanguagePreference.fromStored(
        applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
    )
}

private const val PREFERENCES_NAME = "unified_vpn_appearance_settings"
private const val KEY_LANGUAGE = "language"
private const val KEY_THEME = "theme"
