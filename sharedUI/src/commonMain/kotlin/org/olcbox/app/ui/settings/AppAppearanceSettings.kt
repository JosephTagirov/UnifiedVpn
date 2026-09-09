package org.olcbox.app.ui.settings

import kotlinx.serialization.Serializable

@Serializable
data class AppAppearanceSettings(
    val language: AppLanguagePreference = AppLanguagePreference.System,
    val theme: AppThemePreference = AppThemePreference.System
)

@Serializable
enum class AppLanguagePreference {
    System,
    Russian,
    English;

    fun resolve(systemLanguage: String): String = when (this) {
        System -> systemLanguage
        Russian -> "ru"
        English -> "en"
    }

    fun displayName(): String = when (this) {
        System -> "System"
        Russian -> "Russian"
        English -> "English"
    }

    companion object {
        fun fromStored(value: String?): AppLanguagePreference {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: when (value?.lowercase()) {
                    "ru" -> Russian
                    "en" -> English
                    else -> System
                }
        }
    }
}

@Serializable
enum class AppThemePreference {
    System,
    Light,
    Dark;

    fun resolve(systemIsDark: Boolean): Boolean = when (this) {
        System -> systemIsDark
        Light -> false
        Dark -> true
    }

    fun displayName(): String = when (this) {
        System -> "System"
        Light -> "Light"
        Dark -> "Dark"
    }

    companion object {
        fun fromStored(value: String?): AppThemePreference {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: System
        }
    }
}

interface AppAppearanceSettingsStore {
    suspend fun load(): AppAppearanceSettings

    suspend fun save(settings: AppAppearanceSettings)
}
