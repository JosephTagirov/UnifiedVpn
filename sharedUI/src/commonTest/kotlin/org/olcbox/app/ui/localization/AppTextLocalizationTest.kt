package org.olcbox.app.ui.localization

import kotlin.test.Test
import kotlin.test.assertEquals

class AppTextLocalizationTest {
    @Test
    fun englishTextIsNotChanged() {
        assertEquals("Connection Settings", localizeUiText("Connection Settings", "en"))
    }

    @Test
    fun russianLocaleUsesExactTranslation() {
        assertEquals("Настройки подключения", localizeUiText("Connection Settings", "ru"))
        assertEquals(
            "Уже установлена самая новая версия Unified VPN",
            localizeUiText("The latest version of Unified VPN is already installed", "ru")
        )
    }

    @Test
    fun russianLocaleTranslatesDynamicStatus() {
        assertEquals(
            "Доступно обновление Unified VPN: 0.0.11 build 2026090202",
            localizeUiText("Unified VPN update available: 0.0.11 build 2026090202", "ru")
        )
        assertEquals("VLESS подключён", localizeUiText("VLESS Connected", "ru"))
        assertEquals("12 записей", localizeUiText("12 entries", "ru"))
    }
}
