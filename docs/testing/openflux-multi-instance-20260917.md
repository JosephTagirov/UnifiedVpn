# OpenFlux Multi-Instance Validation

Date: 2026-09-17. Server tooling only; no application release or GitHub upload.
The existing OpenFlux binary and encrypted protocol were reused unchanged.
No private document URLs, keys, profiles, host addresses or receipts are included.

## English

- Added a second isolated server instance with a separate legacy-editor Yandex
  document, random 32-byte key, protected config directory, Docker bridge and
  container. The document probe confirmed edit access and required legacy fields.
- All 112 server-tool tests passed, including two simultaneous mock instances,
  cross-instance refusal, legacy compatibility, ownership checks, cleanup
  isolation, and size/hash/permission validation for reused public artifacts.
- Remote preflight, installation, run and ownership verification passed using a
  pinned SSH host key. The verified existing runtime image was reused; the
  isolated application image was built without network access.
- Windows `DesktopNativeProfileIntegrationTest` passed through the actual
  `DesktopVpnManager` with isolated application data and authenticated LocalSocks.
  Instagram and Telegram returned HTTP 200 over certificate-verified HTTPS.
  A second Telegram request after the 25-second liveness window also returned
  200. The child process stopped, the listener closed, and runtime config was
  removed. Test duration: 32.743 seconds.
- Android 35 in a fresh isolated emulator passed authenticated OpenFlux startup,
  real VPN TUN creation, Instagram and Wikipedia HTTPS (both HTTP 200), stable
  process checks and clean stop. No warning codes were reported. The test app
  data was cleared and the owned emulator was stopped.
- Android testing used the existing debug APK with SHA256
  `4cc01be2521c9e6c20fbf82a5811ff7d0c79afde3290c42f41bd66469aba6dd5`.
  This is an emulator result, not a new physical-phone or release-APK test.
- Before/after snapshots confirmed that the original server container retained
  its ID, image, start time and restart count; its config, state and manager
  checksums remained identical. Existing VPN services were not restarted.

The Windows and Android tests used the **new** profile sequentially. They did
not attach a test client to the original document. Each document still permits
only one active client; this change does not introduce multi-client sharing of
a single document or prove simultaneous throughput on two physical devices.
Host proxy, DNS and routing settings were not changed by the connection tests.

Two large SSH uploads did not complete. Their partial protected staging was
retained, not retried in place or globally pruned. The successful upload copied
only two public artifacts from the original installation with exact SHA256 and
size checks; neither its private config nor its state was used as input.

## Русский

Создан второй изолированный экземпляр сервера: отдельный документ старого
редактора Яндекса, случайный ключ 32 байта, защищённая конфигурация, контейнер
и сеть Docker. Первый профиль не заменялся.

- Пройдены 112 тестов серверных инструментов, включая изоляцию двух экземпляров,
  отказ при подмене экземпляра, совместимость старой установки и проверку SHA256
  повторно используемых публичных файлов.
- Windows проверен через настоящий `DesktopVpnManager` и локальный SOCKS
  с аутентификацией: Instagram и Telegram вернули HTTP 200. Повторный запрос
  после 25 секунд также успешен. Остановка закрыла процесс и порт, временный
  конфиг удалён.
- В отдельном эмуляторе Android 35 проверены зашифрованное соединение, настоящий
  TUN и HTTPS: Instagram и Wikipedia вернули HTTP 200. Процессы стабильны,
  остановка успешна, предупреждений нет. Тестовые данные очищены, эмулятор закрыт.
- Контрольные снимки первой установки совпали: ID и образ контейнера, время
  запуска, число перезапусков, хеши конфигурации, состояния и manager. Существующие
  VPN-сервисы не перезапускались.

Проверки Windows и Android выполнялись последовательно на новом профиле,
без подключения тестового клиента к первому документу. Для одновременной работы
ноутбука и телефона нужно оставить первый профиль на одном устройстве, а второй
импортировать на другом. Один документ по-прежнему обслуживает одного клиента.
Скорость двух физических устройств одновременно не измерялась. Android проверен
в эмуляторе с существующим debug APK, не на физическом телефоне.

Системные прокси, DNS и маршруты ноутбука не менялись. Новая версия приложения
не собиралась и на GitHub ничего не публиковалось. Приватные файлы импорта и
квитанции остаются вне репозитория в защищённой локальной папке.
