package org.olcbox.app.ui.localization

import org.olcbox.app.ui.settings.AppLanguagePreference
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTextLocalizationTest {
    @Test
    fun openFluxTransportChoicesAndUnsupportedTransportAreLocalized() {
        assertEquals("Yandex Docs (classic)", localizeUiText("Yandex Docs (classic)", "en"))
        assertEquals("Yandex Docs (Volga)", localizeUiText("Yandex Docs (Volga)", "en"))
        assertEquals("Яндекс Документы (классические)", localizeUiText("Yandex Docs (classic)", "ru"))
        assertEquals("Яндекс Документы (Volga)", localizeUiText("Yandex Docs (Volga)", "ru"))
        assertEquals("Неподдерживаемый транспорт OpenFlux", localizeUiText("Unsupported OpenFlux transport", "ru"))
    }

    @Test
    fun englishTextIsNotChanged() {
        assertEquals("Connection Settings", localizeUiText("Connection Settings", "en"))
    }

    @Test
    fun notificationReconnectIsLocalized() {
        assertEquals("Reconnect", localizeUiText("Reconnect", "en"))
        assertEquals("Переподключить", localizeUiText("Reconnect", "ru"))
    }

    @Test
    fun openFluxShareWarningsAreLocalized() {
        assertEquals("Поделиться профилем OpenFlux", localizeUiText("Share OpenFlux profile", "ru"))
        assertEquals("Профиль OpenFlux", localizeUiText("OpenFlux profile", "ru"))
        assertEquals(
            "Ссылка содержит ключ шифрования. Передавайте её только тем, кому доверяете.",
            localizeUiText("This link contains the encryption key. Share it only with trusted people.", "ru")
        )
        assertEquals(
            "Этот документ OpenFlux поддерживает только одно активное устройство. Второе подключение может отключить первое.",
            localizeUiText("Only one device can use this OpenFlux document at a time. A second connection may disconnect the first.", "ru")
        )
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
    fun openFluxAddActionIsLocalized() {
        assertEquals("Add OpenFlux", localizeUiText("Add OpenFlux", "en"))
        assertEquals("Добавить OpenFlux", localizeUiText("Add OpenFlux", "ru"))
        assertEquals(
            "Document URL and encryption key",
            localizeUiText("Document URL and encryption key", "en")
        )
        assertEquals(
            "Ссылка на документ и ключ шифрования",
            localizeUiText("Document URL and encryption key", "ru")
        )
    }

    @Test
    fun openFluxEditorLabelsAndErrorsAreLocalized() {
        assertEquals("Yandex document URL", localizeUiText("Yandex document URL", "en"))
        assertEquals("Ссылка на Яндекс Документ", localizeUiText("Yandex document URL", "ru"))
        assertEquals(
            "Ссылка на документ не может быть пустой",
            localizeUiText("Document URL cannot be empty", "ru")
        )
        assertEquals(
            "Укажите HTTPS-ссылку на документ Яндекс Документов или Яндекс Диска",
            localizeUiText("Use an HTTPS Yandex Docs or Yandex Disk document URL", "ru")
        )
        assertEquals("OpenFlux подключён", localizeUiText("OpenFlux Connected", "ru"))
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
