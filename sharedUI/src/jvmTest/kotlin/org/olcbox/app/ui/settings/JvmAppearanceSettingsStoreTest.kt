package org.olcbox.app.ui.settings

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmAppearanceSettingsStoreTest {
    @Test
    fun settingsSurviveRoundTrip() = runTest {
        val directory = Files.createTempDirectory("unified-vpn-appearance-test")
        try {
            val file = directory.resolve("appearance.json")
            val store = JvmAppearanceSettingsStore(file)
            val expected = AppAppearanceSettings(
                language = AppLanguagePreference.Russian,
                theme = AppThemePreference.Dark
            )

            store.save(expected)

            assertEquals(expected, store.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingOrDamagedFileUsesSystemDefaults() = runTest {
        val directory = Files.createTempDirectory("unified-vpn-appearance-invalid-test")
        try {
            val file = directory.resolve("appearance.json")
            val store = JvmAppearanceSettingsStore(file)

            assertEquals(AppAppearanceSettings(), store.load())

            Files.writeString(file, "not valid json")
            assertEquals(AppAppearanceSettings(), store.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
