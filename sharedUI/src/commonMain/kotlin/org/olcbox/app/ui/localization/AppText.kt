package org.olcbox.app.ui.localization

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current
) {
    val language = Locale.current.language
    val localizedText = remember(text, language) {
        localizeUiText(text, language)
    }
    MaterialText(
        text = localizedText,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style
    )
}

fun localizeUiText(text: String, language: String): String {
    if (!language.equals("ru", ignoreCase = true)) return text
    russianExactText[text]?.let { return it }

    return when {
        text.startsWith("Version ") -> "Версия ${text.removePrefix("Version ")}"
        text.startsWith("Connected ") && text.endsWith("ms") ->
            "Подключено за ${text.removePrefix("Connected ").removeSuffix("ms")} мс"
        text.endsWith(" Connected") -> "${text.removeSuffix(" Connected")} подключён"
        text.startsWith("Updated ") -> "Обновлено ${text.removePrefix("Updated ")}"
        text.startsWith("Checked ") -> "Проверено ${text.removePrefix("Checked ")}"
        text.startsWith("Downloading ") -> "Загрузка ${text.removePrefix("Downloading ")}"
        text.startsWith("Installing ") -> "Установка ${text.removePrefix("Installing ")}"
        text.startsWith("Refresh ") -> "Обновить ${text.removePrefix("Refresh ")}"
        text.startsWith("Unified VPN · every ") ->
            "Unified VPN · каждые ${text.removePrefix("Unified VPN · every ").replaceHourSuffix()}"
        text.startsWith("IP ") -> text
        text.endsWith(" entries") -> "${text.removeSuffix(" entries")} записей"
        text.endsWith(" locations") -> "${text.removeSuffix(" locations")} профилей"
        text.endsWith(" subscriptions") -> "${text.removeSuffix(" subscriptions")} подписок"
        text.endsWith(" apps") -> "${text.removeSuffix(" apps")} приложений"
        text.endsWith(" available") -> "${text.removeSuffix(" available")} доступно"
        text.endsWith(" used") -> "${text.removeSuffix(" used")} использовано"
        text.endsWith(" ago") -> "${text.removeSuffix(" ago")} назад"
        text.endsWith(" ms") -> "${text.removeSuffix(" ms")} мс"
        text.startsWith("Every ") && text.endsWith(" minutes") ->
            "Каждые ${text.removePrefix("Every ").removeSuffix(" minutes")} мин"
        text.startsWith("Every ") && text.endsWith(" hours") ->
            "Каждые ${text.removePrefix("Every ").removeSuffix(" hours")} ч"
        text.startsWith("Every ") && text.endsWith(" days") ->
            "Каждые ${text.removePrefix("Every ").removeSuffix(" days")} дн"
        text.startsWith("Subscription deleted") ->
            text.replace("Subscription deleted", "Подписка удалена")
                .replace("locations removed", "профилей удалено")
        text.startsWith("Subscriptions updated: ") ->
            "Подписок обновлено: ${text.removePrefix("Subscriptions updated: ")}"
        text.startsWith("Download failed: ") ->
            "Ошибка загрузки: ${text.removePrefix("Download failed: ")}"
        text.startsWith("Unified VPN update available: ") ->
            "Доступно обновление Unified VPN: ${text.removePrefix("Unified VPN update available: ")}"
        text.startsWith("Unified VPN ") && text.endsWith(" is already downloaded") ->
            "Unified VPN ${text.removePrefix("Unified VPN ").removeSuffix(" is already downloaded")} уже загружен"
        text.startsWith("Auto uses ") ->
            "Авто использует ${text.removePrefix("Auto uses ")}"
        text.startsWith("Only ") && text.endsWith(" use Unified VPN") ->
            "Только ${text.removePrefix("Only ").removeSuffix(" use Unified VPN")} используют Unified VPN"
        text.contains(" used · ") && text.endsWith(" available") ->
            text.replace(" used · ", " использовано · ").replace(" available", " доступно")
        else -> text
    }
}

private val russianExactText = mapOf(
    "Active" to "Активно",
    "Add configuration" to "Добавить подключение",
    "Add connection" to "Добавить подключение",
    "Add olcRTC location" to "Добавить профиль olcRTC",
    "Add subscription" to "Добавить подписку",
    "Add VPN profile" to "Добавить VPN-профиль",
    "All Apps" to "Все приложения",
    "All apps" to "Все приложения",
    "All apps use Unified VPN" to "Все приложения используют Unified VPN",
    "Administrator rights" to "Права администратора",
    "Allow insecure requests" to "Разрешить небезопасные запросы",
    "App List" to "Список приложений",
    "Application Logs" to "Журнал приложения",
    "Application Settings" to "Настройки приложения",
    "Application settings" to "Настройки приложения",
    "Applies when settings closes" to "Применится после закрытия настроек",
    "Apps Using Unified VPN" to "Приложения через Unified VPN",
    "Auto" to "Авто",
    "Auto-detection may be inaccurate." to "Автоопределение может быть неточным.",
    "Automatic" to "Автоматически",
    "Back" to "Назад",
    "Backup & export" to "Резервная копия и экспорт",
    "Batch" to "Пакет",
    "Bypass RU apps" to "Обходить российские приложения",
    "Bypass RU apps on" to "Обход российских приложений включён",
    "Bypass Selected" to "Обходить выбранные",
    "Bypass selected apps" to "Обходить выбранные приложения",
    "Bypassed Apps" to "Приложения в обходе",
    "Camera permission denied" to "Нет разрешения на камеру",
    "Camera unavailable" to "Камера недоступна",
    "Cancel" to "Отмена",
    "Check Interval" to "Интервал проверки",
    "Check now" to "Проверить сейчас",
    "Check server" to "Проверить сервер",
    "Checking..." to "Проверка...",
    "Connecting..." to "Подключение...",
    "Checking SSH credentials" to "Проверка данных SSH",
    "Checking Unified VPN updates..." to "Проверка обновлений Unified VPN...",
    "Clipboard is empty or invalid" to "Буфер обмена пуст или содержит неверные данные",
    "Choose apps that bypass Unified VPN" to "Выберите приложения, работающие в обход Unified VPN",
    "Choose apps that use Unified VPN" to "Выберите приложения, использующие Unified VPN",
    "Choose how often this subscription should be checked." to "Выберите частоту проверки этой подписки.",
    "Clear" to "Очистить",
    "Click To Verify Reachability" to "Нажмите, чтобы проверить доступность",
    "Close" to "Закрыть",
    "Close logs" to "Закрыть журнал",
    "Close scanner" to "Закрыть сканер",
    "Configuration imported" to "Конфигурация импортирована",
    "Config copied" to "Конфигурация скопирована",
    "Config copied to clipboard" to "Конфигурация скопирована в буфер обмена",
    "Config is too large for the clipboard" to "Конфигурация слишком велика для буфера обмена",
    "Connect apps through Unified VPN" to "Подключить приложения через Unified VPN",
    "Connect by SSH and install AmneziaWG" to "Подключиться по SSH и установить AmneziaWG",
    "Connected" to "Подключено",
    "Connection Mode" to "Режим подключения",
    "Connection Settings" to "Настройки подключения",
    "Connection mode saved" to "Режим подключения сохранён",
    "Connection settings" to "Настройки подключения",
    "Connection type" to "Тип подключения",
    "Configure the operating system proxy automatically" to "Автоматически настроить системный прокси",
    "Connectivity Check" to "Проверка подключения",
    "Copied" to "Скопировано",
    "Copied to clipboard" to "Скопировано в буфер обмена",
    "Could not encrypt friend package" to "Не удалось зашифровать пакет для друга",
    "Copy" to "Копировать",
    "Copy all locations to clipboard" to "Скопировать все профили в буфер обмена",
    "Copy settings" to "Копировать настройки",
    "Create custom location" to "Создать свой профиль",
    "Create olcRTC location" to "Создать профиль olcRTC",
    "Credentials" to "Учётные данные",
    "Custom interval" to "Свой интервал",
    "Custom schedule" to "Своё расписание",
    "DNS server (optional)" to "DNS-сервер (необязательно)",
    "Decrypting package" to "Расшифровка пакета",
    "Delete" to "Удалить",
    "Delete subscription" to "Удалить подписку",
    "Delete subscription?" to "Удалить подписку?",
    "Diagnostics and export" to "Диагностика и экспорт",
    "Disconnected" to "Отключено",
    "Download" to "Скачать",
    "Dynamic Theme" to "Динамическая тема",
    "Encrypted friend package" to "Зашифрованный пакет для друга",
    "Encryption key" to "Ключ шифрования",
    "Endpoint" to "Адрес подключения",
    "Enter olcRTC room, key, provider, and transport" to "Введите комнату olcRTC, ключ, сервис и транспорт",
    "Enter room, key, provider, and transport" to "Введите комнату, ключ, сервис и транспорт",
    "Every app follows the same TUN route" to "Все приложения используют единый маршрут TUN",
    "Every app uses Unified VPN" to "Все приложения используют Unified VPN",
    "Every day" to "Каждый день",
    "Every hour" to "Каждый час",
    "Every minute" to "Каждую минуту",
    "Expose SOCKS5 without changing system routing" to "Открыть SOCKS5 без изменения системной маршрутизации",
    "Export full configuration" to "Экспортировать полную конфигурацию",
    "Fine-tune stream performance" to "Точная настройка производительности потока",
    "Friend package is damaged or unsupported" to "Пакет повреждён или не поддерживается",
    "Friend package, subscription, AWG .conf, or JSON" to "Пакет для друга, подписка, AWG .conf или JSON",
    "Full tunnel" to "Полный туннель",
    "Generated password" to "Созданный пароль",
    "Got it" to "Понятно",
    "History" to "История",
    "Hold and drag to reorder" to "Зажмите и перетащите для изменения порядка",
    "HTTP, HTTPS, or olcrtc URI" to "HTTP, HTTPS или URI olcrtc",
    "Import" to "Импортировать",
    "Import Unified VPN Config" to "Импорт конфигурации Unified VPN",
    "Import from file" to "Импортировать из файла",
    "Import link or URI" to "Импортировать ссылку или URI",
    "Inactive" to "Неактивно",
    "IP address or domain" to "IP-адрес или домен",
    "Last check" to "Последняя проверка",
    "Later" to "Позже",
    "Listen address" to "Адрес прослушивания",
    "Listen address is required" to "Укажите адрес прослушивания",
    "Local SOCKS endpoint" to "Локальный адрес SOCKS",
    "Local SOCKS only" to "Только локальный SOCKS",
    "Local SOCKS host (optional)" to "Локальный хост SOCKS (необязательно)",
    "Local SOCKS port (optional)" to "Локальный порт SOCKS (необязательно)",
    "Local SOCKS5 proxy" to "Локальный прокси SOCKS5",
    "Location name" to "Название профиля",
    "Location settings" to "Настройки профиля",
    "Locations" to "Профили",
    "Mode" to "Режим",
    "Mode, SOCKS5 proxy, and app routing" to "Режим, прокси SOCKS5 и маршрутизация приложений",
    "Name" to "Название",
    "Next" to "Далее",
    "No app list needed" to "Список приложений не требуется",
    "No clipboard data found" to "В буфере обмена нет данных",
    "No apps bypass Unified VPN" to "Нет приложений в обходе Unified VPN",
    "No apps found" to "Приложения не найдены",
    "No apps selected" to "Приложения не выбраны",
    "No bypassed apps" to "Нет приложений в обходе",
    "No entries" to "Нет записей",
    "No matching apps" to "Подходящие приложения не найдены",
    "No matching installed apps" to "Подходящие установленные приложения не найдены",
    "No profile selected" to "Профиль не выбран",
    "No RU apps selected" to "Российские приложения не выбраны",
    "No subscriptions" to "Нет подписок",
    "No subscriptions to update" to "Нет подписок для обновления",
    "Not checked yet" to "Ещё не проверялось",
    "Not updated yet" to "Ещё не обновлялось",
    "Not yet" to "Ещё нет",
    "Offline" to "Недоступно",
    "On app start" to "При запуске приложения",
    "Open GitHub" to "Открыть GitHub",
    "Open Unified VPN window" to "Открыть окно Unified VPN",
    "Optional password" to "Пароль (необязательно)",
    "Optional username" to "Имя пользователя (необязательно)",
    "Overview" to "Обзор",
    "Package for a friend" to "Пакет для друга",
    "Package password" to "Пароль пакета",
    "Package password (12+ characters)" to "Пароль пакета (от 12 символов)",
    "Password" to "Пароль",
    "Password is required" to "Укажите пароль",
    "Password regenerated" to "Пароль создан заново",
    "Paste clipboard" to "Вставить из буфера обмена",
    "Paste link, URI, or friend package" to "Вставить ссылку, URI или пакет для друга",
    "Paste the complete profile configuration" to "Вставьте полную конфигурацию профиля",
    "Port" to "Порт",
    "Port is required" to "Укажите порт",
    "Ping" to "Пинг",
    "Preparing AmneziaWG" to "Подготовка AmneziaWG",
    "Preparing server" to "Подготовка сервера",
    "Protecting your connection" to "Защита подключения",
    "Profile type" to "Тип профиля",
    "Profile URI (optional)" to "URI профиля (необязательно)",
    "Profiles" to "Профили",
    "Proxy" to "Прокси",
    "Proxy -> VPN" to "Прокси -> VPN",
    "Proxy · Local SOCKS5" to "Прокси · локальный SOCKS5",
    "QR code" to "QR-код",
    "QR imported" to "QR-код импортирован",
    "Quit Unified VPN" to "Выйти из Unified VPN",
    "Raw configuration (optional)" to "Исходная конфигурация (необязательно)",
    "Ready to scan" to "Готово к сканированию",
    "Refresh imported subscription locations" to "Обновить профили из подписок",
    "Refresh now" to "Обновить сейчас",
    "Refresh schedule" to "Расписание обновлений",
    "Refreshing…" to "Обновление…",
    "Regenerate password" to "Создать новый пароль",
    "Reordering profile" to "Перемещение профиля",
    "Repeat package password" to "Повторите пароль пакета",
    "Requested when TUN (VPN) starts" to "Запрашиваются при запуске TUN (VPN)",
    "Required" to "Обязательно",
    "Room ID" to "ID комнаты",
    "Room URL" to "URL комнаты",
    "Route all traffic through a virtual adapter; Windows asks for administrator rights" to
        "Направить весь трафик через виртуальный адаптер; Windows запросит права администратора",
    "Routing" to "Маршрутизация",
    "Routing Behavior" to "Правила маршрутизации",
    "RU bypass on" to "Обход российских приложений включён",
    "Save" to "Сохранить",
    "Save Unified VPN Logs" to "Сохранить журнал Unified VPN",
    "Saved for TUN mode" to "Сохранено для режима TUN",
    "Saving restarts the active connection" to "Сохранение перезапустит активное подключение",
    "Scan QR" to "Сканировать QR-код",
    "Scan QR code" to "Сканировать QR-код",
    "Scan QR or copy the link" to "Отсканируйте QR-код или скопируйте ссылку",
    "Scan QR, paste URI, or import config file" to "Сканируйте QR-код, вставьте URI или импортируйте файл",
    "Search apps" to "Поиск приложений",
    "Selected Apps Only" to "Только выбранные приложения",
    "Selected apps only" to "Только выбранные приложения",
    "Selected location" to "Выбранный профиль",
    "Self-hosted AmneziaWG" to "Свой сервер AmneziaWG",
    "Self-hosted AmneziaWG is ready" to "Свой сервер AmneziaWG готов",
    "Server IP or domain" to "IP-адрес или домен сервера",
    "Service" to "Сервис",
    "Set up and connect" to "Настроить и подключить",
    "Set up self-hosted server" to "Настроить свой сервер",
    "SETUP" to "НАСТРОИТЬ",
    "Settings" to "Настройки",
    "Share" to "Поделиться",
    "Share location" to "Поделиться профилем",
    "Share subscription" to "Поделиться подпиской",
    "SOCKS5 Proxy" to "Прокси SOCKS5",
    "SOCKS5 -> Proxy" to "SOCKS5 -> прокси",
    "SOCKS proxy saved" to "Настройки прокси SOCKS сохранены",
    "Source" to "Источник",
    "Split Tunneling" to "Раздельное туннелирование",
    "Split tunneling" to "Раздельное туннелирование",
    "SSH login" to "Логин SSH",
    "SSH password" to "Пароль SSH",
    "SSH port" to "Порт SSH",
    "Start relay" to "Запустить подключение",
    "START" to "ПУСК",
    "Stop" to "Остановить",
    "Stop relay" to "Остановить подключение",
    "STOP" to "СТОП",
    "Subscription" to "Подписка",
    "Subscription link" to "Ссылка подписки",
    "Subscription not updated" to "Подписка не обновлена",
    "Subscription QR" to "QR-код подписки",
    "Subscription refresh rate" to "Частота обновления подписки",
    "Subscription refresh rate saved" to "Частота обновления подписки сохранена",
    "Subscription refresh set to Auto" to "Автообновление подписки включено",
    "Subscription source" to "Источник подписки",
    "Subscription updated" to "Подписка обновлена",
    "Subscriptions" to "Подписки",
    "Subscriptions & Sharing" to "Подписки и общий доступ",
    "System proxy" to "Системный прокси",
    "System VPN interface" to "Системный VPN-интерфейс",
    "TUN (VPN)" to "TUN (VPN)",
    "TUN mode routing rule" to "Правило маршрутизации TUN",
    "TUN · Full tunnel" to "TUN · полный туннель",
    "The latest version of Unified VPN is already installed" to "Уже установлена самая новая версия Unified VPN",
    "This cannot be undone." to "Это действие нельзя отменить.",
    "Transport" to "Транспорт",
    "Trust and install" to "Доверять и установить",
    "Unable to open GitHub" to "Не удалось открыть GitHub",
    "Unified VPN update available" to "Доступно обновление Unified VPN",
    "Unified VPN update check failed" to "Не удалось проверить обновление Unified VPN",
    "Unsaved change" to "Несохранённое изменение",
    "Update Settings" to "Настройки обновлений",
    "Update service unavailable" to "Сервис обновлений недоступен",
    "Update subscriptions" to "Обновить подписки",
    "Updated" to "Обновлено",
    "Updates" to "Обновления",
    "Unified VPN Updates" to "Обновления Unified VPN",
    "Application releases" to "Выпуски приложения",
    "Username" to "Имя пользователя",
    "Username is required" to "Укажите имя пользователя",
    "Uses the subscription schedule" to "Использует расписание подписки",
    "Use local SOCKS for olcRTC and the recommended mode for other profiles" to
        "Для olcRTC использовать локальный SOCKS, для остальных профилей — рекомендуемый режим",
    "Using Android system colors" to "Используются системные цвета Android",
    "Using Unified VPN colors" to "Используются цвета Unified VPN",
    "Verify and create" to "Проверить и создать",
    "Verify SSH server" to "Проверить SSH-сервер",
    "Verifying SSH server" to "Проверка SSH-сервера",
    "VPN Active" to "VPN активен",
    "VPN Inactive" to "VPN неактивен",
    "VPN -> Proxy" to "VPN -> прокси",
    "VPN -> SOCKS5" to "VPN -> SOCKS5",
    "olcRTC, VLESS, and AmneziaWG are ready" to "olcRTC, VLESS и AmneziaWG готовы"
)

private fun String.replaceHourSuffix(): String =
    if (endsWith("h")) "${dropLast(1)} ч" else this
