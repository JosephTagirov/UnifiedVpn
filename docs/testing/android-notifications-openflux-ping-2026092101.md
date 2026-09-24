# Android Notifications And OpenFlux Ping

## English

Local version `0.0.14`, build/versionCode `2026092101`, validated on 2026-09-21.
No GitHub publication, server deployment or working VPN/profile changes were performed.

### Changes

- Android Activity, notification chooser and VPN service share one application-context profile repository, mutation mutex and change stream. Foreground refresh, initial collection and stale-read guards keep both main-screen models synchronized.
- The notification chooser's 48dp app icon opens the own-package launcher Activity. Existing keyguard, secure-window and obscured-touch protections remain. UI model cleanup does not stop the VPN service.
- OpenFlux ping measures an HTTPS `generate_204` response through the matching connected tunnel's authenticated SOCKS endpoint. The probe uses remote DNS, standard TLS verification, no redirects, a bounded timeout and no direct fallback. It never creates another document client. Inactive or mismatched profiles display a connect-first message.
- Changing a connection configuration or removing a profile invalidates pending probes and cached results. Job identity checks prevent a cancelled old probe from affecting its replacement. Renaming preserves a valid measurement.
- The private upstream bot adds OpenFlux, olcRTC and the actual AWG core repository. Existing notification state, secrets and schedule remain compatible. See [bot instructions](../../tools/upstream-notifier/README.md).

### Verification

| Check | Result |
| --- | --- |
| Shared JVM tests | 266 reported cases, zero failures/errors |
| Android host tests | 165 cases, zero failures/errors |
| Notifier offline tests | 19 passed |
| Debug and signed/minified release APK builds | Passed |
| Release lint and native/binding packaging verification | Passed |
| APK signature | v1 and v2 verified |
| APK metadata | `app.unifiedvpn.local`, `0.0.14`, `2026092101` |
| Packaged ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| Fresh Android 35 x86_64 emulator | All seven checks below passed |

The emulator ran only synthetic profiles with guest-loopback VLESS endpoints and an unused synthetic OpenFlux document reference:

1. Initial UI selection and fixture TUN establishment.
2. Notification Next while the app is backgrounded; icon opens the app showing the new selection.
3. Chooser list selection updates the main screen.
4. Notification Previous wraps selection; the app process remains the same.
5. Chooser Stop releases the native core.
6. Inactive OpenFlux ping displays connect-first without starting a native core.
7. Installing the signed release over the debug build preserves profiles; the minified app launches and displays the new ping state.

The test app was uninstalled and the exact owned emulator processes were stopped. Private documents, credentials, live VPN sessions, host proxy/DNS/routes and server services were not changed. Live VPN integration test gates were disabled; the JVM test count is not evidence of live connectivity. The new HTTPS ping was tested with synthetic SOCKS/authentication fixtures, not the user's active OpenFlux server. Physical-device and current real-server confirmation remain separate. No new Windows installer was produced in this task.

### Artifacts

Local directory: `.downloads/local-artifacts/0.0.14/`.

| File | SHA-256 |
| --- | --- |
| `UnifiedVPN-0.0.14-build.2026092101-android-universal.apk` | `c6d3d837e3300ea11459253c455ce35ba212da11406c9fd73a881bb849a96940` |
| `UnifiedVPN-upstream-notifier-2026092101.zip` | `e4b073ea9511a9fb4f66a31529ba8f8a658de94cdc1d94b6d98dd6e496bd5cdb` |

The notifier archive contains only explicitly selected program, unit, documentation and test files, with no environment file or state. It has not been installed on the server.

The emulator's debug APK SHA-256 was `9c1eabb4d6632590c583b45e174a9286f938f1ddf02d8290fae4d1a2f6fe6b29`.
Local synthetic UI receipt: `.downloads/android-notification-20260921-061850/result.json`.

### Reproduction

Disable private live-test environment gates first. Use the already pinned olcRTC checkout:

```powershell
$olcRtcSource = (Resolve-Path '.downloads/olcrtc-f616-source').Path -replace '\\', '/'
.\gradlew.bat "-POLCRTC_REPO=$olcRtcSource" `
  :sharedUI:jvmTest :sharedUI:testAndroidHostTest :androidApp:assembleDebug `
  :androidApp:packageReleaseUpdateApk --offline --max-workers=2 --no-parallel `
  --no-daemon --no-configuration-cache --console=plain
python -B -m unittest discover -s tools/upstream-notifier/tests -v
```

`scripts/Test-AndroidNotificationProfileSync.ps1` requires a fresh owned emulator record, checks ownership before installing anything, and cleans up only that guest. It accepts `-FreshOwnershipRecord`, `-TestApk` and optional `-TestReleaseApk`. Do not point it at a real phone or an existing user AVD.

## Русский

Проверена локальная `0.0.14`, сборка `2026092101`. На GitHub ничего не опубликовано, сервер и рабочие VPN не изменялись.

Исправлена рассинхронизация профиля между Android-шторкой и главным экраном: используется общее хранилище и поток изменений, добавлены перечитывание при возврате и защита от запоздалого чтения. Значок в окне из уведомления открывает приложение, сохраняя защиту экрана и не останавливая VPN-службу.

Пинг OpenFlux измеряет HTTPS-ответ через соответствующий уже подключённый зашифрованный туннель. DNS идёт через SOCKS, проверка TLS включена, перенаправления и прямой обход запрещены. Второй клиент документа не запускается. Для неактивного профиля выводится просьба сначала подключиться. Изменение документа, ключа или типа профиля отменяет старые проверки и удаляет неактуальный результат; переименование его сохраняет.

Пройдены 266 JVM-тестов, 165 Android host-тестов и 19 тестов бота. Сборка, release lint, native-состав, подпись v1/v2 и метаданные APK проверены. В отдельном эмуляторе Android 35 прошли семь перечисленных выше сценариев: переключение из уведомления, выбор из списка, переход по значку, остановка, состояние пинга неактивного OpenFlux и установка подписанной release-сборки поверх тестовой с сохранением профилей.

Это проверки на синтетических профилях. Реальные документы и серверные подключения не использовались; сетевые интеграционные тесты с приватными профилями были отключены. Настоящий пинг через ваш сервер и физический телефон в этом прогоне не проверялись. Тестовая установка удалена, принадлежащий тесту эмулятор остановлен. Windows-установщик в этой задаче не собирался.

APK и архив бота находятся в `.downloads/local-artifacts/0.0.14/`, контрольные суммы приведены выше. Бот теперь отслеживает OpenFlux, olcRTC и фактически используемое ядро AWG, сохраняя проверки оригинальных olcbox и Amnezia VPN. Архив не содержит токена, ID чата или состояния; обновлённую программу бота ещё нужно установить на сервер по [инструкции](../../tools/upstream-notifier/README.md). Автоматического обновления VPN-компонентов нет.
