<p align="center">
  <img src="docs/assets/hero.svg" alt="UnifiedVPN — ваши подключения, одно приложение. Android и Windows; olcRTC, VLESS, AmneziaWG и OpenFlux." width="100%">
</p>

<h1 align="center">UnifiedVPN</h1>

<p align="center">
  VPN-клиент для <strong>Android и Windows</strong>.<br>
  Добавьте подписку или готовый профиль — и управляйте подключениями в одном месте.
</p>

<p align="center">
  <a href="#download"><strong>Скачать</strong></a> ·
  <a href="#start">Быстрый старт</a> ·
  <a href="#features">Возможности</a> ·
  <a href="#docs">Документация</a> ·
  <a href="#english">English</a>
</p>

<p align="center">
  <a href="https://github.com/JosephTagirov/UnifiedVpn/releases/tag/v0.1.15">v0.1.15 · build 2026112101</a> &nbsp; / &nbsp;
  Alpha &nbsp; / &nbsp;
  <a href="LICENSE">MIT License</a>
</p>

---

<a id="download"></a>

## Скачать приложение

<p align="center">
  <a href="https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.15/UnifiedVPN-0.1.15-build.2026112101-android-universal.apk"><img src="docs/assets/download-android.svg" alt="Скачать UnifiedVPN для Android — APK, Android 6 и новее" width="300" height="84"></a>
  <a href="https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.15/UnifiedVPN-0.1.15-build.2026112101-windows-amd64-installer.exe"><img src="docs/assets/download-windows.svg" alt="Скачать UnifiedVPN для Windows — установщик EXE, x64" width="300" height="84"></a>
</p>

| Android 6+ | Windows x64 |
| :--- | :--- |
| [**Скачать APK**](https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.15/UnifiedVPN-0.1.15-build.2026112101-android-universal.apk) | [**Скачать установщик EXE**](https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.15/UnifiedVPN-0.1.15-build.2026112101-windows-amd64-installer.exe) |
| Откройте APK на телефоне. При обновлении устанавливайте поверх прежней версии, без удаления приложения. | Запустите установщик для обновления установленной копии с сохранением настроек. Открытая переносная копия в другой папке не мешает обновлению. [Portable ZIP](https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.15/UnifiedVPN-0.1.15-build.2026112101-windows-amd64-portable-manual.zip) можно распаковать в отдельную папку. |

<p align="center">
  <a href="https://github.com/JosephTagirov/UnifiedVpn/releases/tag/v0.1.15">Что нового и ограничения</a> ·
  <a href="https://github.com/JosephTagirov/UnifiedVpn/releases">Все версии</a> ·
  <a href="https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.15/verification-build-2026112101.json">Контрольные суммы SHA-256</a>
</p>

> **Альфа-версия.** Скорость и доступность зависят от сети. Проверка Яндекса в OpenFlux пока нестабильна.

Для профиля VK Calls на iPhone откройте ссылку из кабинета в Ghostlane. [Ghostlane в App Store](https://apps.apple.com/ru/app/ghostlane/id6795355210)

<a id="features"></a>

## Одно место для ваших подключений

| Профили и подписки | Управление соединением |
| :--- | :--- |
| **Четыре типа профилей.** olcRTC, VLESS, AmneziaWG и экспериментальный OpenFlux в одном списке. | **Подключение одним нажатием.** Выбор профиля, текущий статус и проверка доступности. |
| **Удобный импорт.** Ссылка подписки, URI, буфер обмена или файл конфигурации. | **Настройки под задачу.** VPN/TUN и прокси; доступность режимов зависит от платформы и движка. |
| **Обмен профилями.** Экспорт и защищённые паролем пакеты для друзей с отдельным AmneziaWG-профилем. | **Диагностика рядом.** Просмотр и экспорт журналов, обновление подписок из приложения. |

<a id="start"></a>

## От установки до подключения — три шага

1. **Установите приложение.** Выберите Android или Windows в блоке скачивания.
2. **Добавьте профиль.** Нажмите **«+»**, вставьте ссылку подписки или импортируйте готовую конфигурацию.
3. **Подключитесь.** Выберите профиль и нажмите **«Подключиться»**. Для загрузки изменений с сервера нажмите **«Обновить подписку»** на главном экране.

Приложение работает с вашими профилями и подписками. Для подключения нужна готовая конфигурация от администратора или вашего сервера.

### В выпуске 0.1.15

- Обновление подписки сохраняет порядок профилей и текущий выбор, пока выбранный профиль есть в подписке. Android проверяет срок обновления в фоне.
- Режим **«Авто по скорости»** сравнивает загрузку через профили. Он доступен на Android и в прокси-режимах Windows.
- Если фильтр рекламы не запустился, приложение пытается установить VPN на том же профиле и показывает предупреждение о недоступной блокировке. [Блокировка рекламы](docs/AD_BLOCKING.md)
- Яндекс Интернетометр и его серверы измерения используют прямой маршрут. Speedtest работает через VPN.

### Из предыдущего выпуска 0.1.4

Добавлен **Салют Jazz (DataChannel)** для Android и Windows: подключение, HTTPS-проверка пинга и реконнект. Реализована поддержка **МТС Линк (VP9)**, но она требует настроенной постоянной встречи; успешное подключение пока не подтверждено.

В некоторых сетях **VK Calls** использует вспомогательный OpenFlux-канал сигналинга. Работа в белых списках МегаФона, на физических Android 17 / One UI 9, Galaxy Fold и iPhone текущими проверками не подтверждена. Результаты и границы проверок перечислены в [описании релиза](https://github.com/JosephTagirov/UnifiedVpn/releases/tag/v0.1.4).

<details>
<summary><strong>Посмотреть скриншоты раннего интерфейса</strong></summary>

Снимки демонстрируют управление профилями и настройки ранней версии. Оформление актуальной версии отличается.

<p align="center">
  <img src="docs/screenshots/unified-vpn-home.png" alt="Ранняя версия: главный экран с демонстрационными профилями" width="240">
  <img src="docs/screenshots/unified-vpn-settings.png" alt="Ранняя версия: настройки приложения" width="240">
  <img src="docs/screenshots/unified-vpn-sharing.png" alt="Ранняя версия: подписки и обмен конфигурациями" width="240">
</p>

</details>

<a id="docs"></a>

## Документация и участие

| Что нужно | Куда перейти |
| :--- | :--- |
| Разобраться в движках и форматах | [Движки и протоколы](docs/ENGINES.md) |
| Собрать приложение из исходников | [Требования, команды и результаты сборки](docs/REFERENCE.md#requirements) · [на русском](docs/REFERENCE.md#требования) |
| Настроить защищённый обмен профилями | [Пакеты для друзей](docs/REFERENCE.md#безопасные-пакеты-для-друзей) |
| Посмотреть историю изменений | [Релизы на GitHub](https://github.com/JosephTagirov/UnifiedVpn/releases) · [CHANGELOG](CHANGELOG.md) |
| Сообщить об ошибке или предложить улучшение | [Issues](https://github.com/JosephTagirov/UnifiedVpn/issues) |

При описании ошибки укажите версию и сборку, платформу, тип профиля и шаги воспроизведения. Перед отправкой журнала удалите ключи, пароли и приватные ссылки.

<a id="english"></a>

<details>
<summary><strong>English — overview, downloads and getting started</strong></summary>

### Your connections. One application.

**UnifiedVPN** is an Android-first VPN client with **olcRTC, VLESS, AmneziaWG and experimental OpenFlux** profiles. Import a subscription or a configuration and manage your connections from one interface.

**Current release [v0.1.15 · build 2026112101](https://github.com/JosephTagirov/UnifiedVpn/releases/tag/v0.1.15).**

- Install the APK over the existing app on Android 6 or later. [Android APK](https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.15/UnifiedVPN-0.1.15-build.2026112101-android-universal.apk)
- The x64 installer upgrades the installed copy and preserves settings. A running portable copy in another folder does not block setup. [Windows installer](https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.15/UnifiedVPN-0.1.15-build.2026112101-windows-amd64-installer.exe) · [Portable ZIP](https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.15/UnifiedVPN-0.1.15-build.2026112101-windows-amd64-portable-manual.zip)
- [SHA-256 checksums](https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.15/verification-build-2026112101.json)

To get started, install the app, use **+** to import your subscription or profile, select a profile and connect. A configuration from your administrator or your own server is required.

Subscription refresh preserves the order and selection of profiles that are still in the feed. Android checks for scheduled updates in the background. The optional speed-based mode compares download throughput on Android and in Windows proxy modes. If the ad filter cannot start, the client tries the same VPN profile and shows a warning. [Release notes](https://github.com/JosephTagirov/UnifiedVpn/releases/tag/v0.1.15)

On iPhone, open the VK Calls profile from the account portal in Ghostlane. [Ghostlane on the App Store](https://apps.apple.com/ru/app/ghostlane/id6795355210)

[Technical reference](docs/REFERENCE.md#english) · [Engines](docs/ENGINES.md) · [Issues](https://github.com/JosephTagirov/UnifiedVpn/issues)

</details>

---

Основан на [**olcbox**](https://github.com/alananisimov/olcbox) Алана Анисимова. Спасибо автору оригинального приложения и разработчикам используемых движков. Лицензия — [MIT](LICENSE).

Проект предназначен для разработки, тестирования и исследований. Автор не гарантирует доступность подключения или совместимость с сервисами. Используйте программу в соответствии с применимым законодательством; она не предназначена для нарушения ограничений доступа. [Полное уведомление](docs/REFERENCE.md#юридическое-уведомление).
