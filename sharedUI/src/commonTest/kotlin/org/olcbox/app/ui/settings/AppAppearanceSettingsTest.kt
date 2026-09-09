package org.olcbox.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppAppearanceSettingsTest {
    @Test
    fun languagePreferenceResolvesSystemAndExplicitLanguages() {
        assertEquals("de", AppLanguagePreference.System.resolve("de"))
        assertEquals("ru", AppLanguagePreference.Russian.resolve("de"))
        assertEquals("en", AppLanguagePreference.English.resolve("ru"))
    }

    @Test
    fun languagePreferenceReadsCurrentAndLegacyValues() {
        assertEquals(AppLanguagePreference.System, AppLanguagePreference.fromStored(null))
        assertEquals(AppLanguagePreference.Russian, AppLanguagePreference.fromStored("Russian"))
        assertEquals(AppLanguagePreference.Russian, AppLanguagePreference.fromStored("ru"))
        assertEquals(AppLanguagePreference.English, AppLanguagePreference.fromStored("EN"))
        assertEquals(AppLanguagePreference.System, AppLanguagePreference.fromStored("unsupported"))
    }

    @Test
    fun themePreferenceHonorsSystemOrExplicitChoice() {
        assertTrue(AppThemePreference.System.resolve(systemIsDark = true))
        assertFalse(AppThemePreference.System.resolve(systemIsDark = false))
        assertFalse(AppThemePreference.Light.resolve(systemIsDark = true))
        assertTrue(AppThemePreference.Dark.resolve(systemIsDark = false))
        assertEquals(AppThemePreference.System, AppThemePreference.fromStored("unsupported"))
    }
}
