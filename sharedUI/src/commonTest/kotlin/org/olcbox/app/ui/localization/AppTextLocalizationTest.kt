package org.olcbox.app.ui.localization

import org.olcbox.app.ui.settings.AppLanguagePreference
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
        assertEquals("AmneziaWG подключён", localizeUiText("AmneziaWG connected", "ru"))
        assertEquals("Запуск VLESS...", localizeUiText("Starting VLESS...", "ru"))
        assertEquals("Переключение профиля...", localizeUiText("Switching profile...", "ru"))
        assertEquals(
            "Восстановление предыдущего профиля...",
            localizeUiText("Restoring previous profile...", "ru")
        )
        assertEquals("Остановка...", localizeUiText("Stopping...", "ru"))
        assertEquals(
            "Установка обновления отменена или завершилась ошибкой",
            localizeUiText("Update installation was canceled or failed", "ru")
        )
        assertEquals(
            "Ошибка установки: отказано",
            localizeUiText("Installation failed: отказано", "ru")
        )
        assertEquals("12 записей", localizeUiText("12 entries", "ru"))
    }

    @Test
    fun explicitLanguageChoiceOverridesSystemLanguage() {
        val forcedRussian = AppLanguagePreference.Russian.resolve("en")
        val forcedEnglish = AppLanguagePreference.English.resolve("ru")

        assertEquals("Настройки подключения", localizeUiText("Connection Settings", forcedRussian))
        assertEquals("Connection Settings", localizeUiText("Connection Settings", forcedEnglish))
        assertEquals("Внешний вид", localizeUiText("Appearance", forcedRussian))
        assertEquals("Как в системе", localizeUiText("System", forcedRussian))
        assertEquals(
            "URI подписки или профиля",
            localizeUiText("Subscription or location URI", forcedRussian)
        )
        assertEquals(
            "Ожидаемый отпечаток SSH",
            localizeUiText("Expected SSH fingerprint", forcedRussian)
        )
        assertEquals(
            "Расшифровать и импортировать",
            localizeUiText("Decrypt and import", forcedRussian)
        )
    }
}
