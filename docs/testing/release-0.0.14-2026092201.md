# Android Reconnect And Release Checks

Build date: 2026-09-22; final validation continued on 2026-09-23.
Unified VPN 0.0.14 Preview, build `2026092201`.
This replaces local candidate `2026092103` before publication. Stable remains
`0.0.12`. The additional changes implement notification-window retry, OpenFlux
profile sharing and an offline-first server profile helper. The olcRTC, AWG,
Xray and OpenFlux native revisions are unchanged
from the [upstream refresh](upstream-refresh-2026092103.md).

## Behaviour

The Android notification profile chooser no longer closes itself after a
connection result or Stop. Close, Back and the app icon remain explicit ways
to leave it; leaving the activity or locking the device still closes the
protected window. No overlay or lock-screen bypass is added.

A separate Reconnect button targets the profile that failed. If a failed switch
restores the previous working profile, retry still targets the failed profile,
not the restored one. Local pending state and service-side guards prevent
duplicate starts. The result must belong to a new connection generation,
not an old Error or Connected state. Existing serialized native shutdown and
split-tunneling settings remain in use. No raw server error or profile secret
is added to the chooser.

OpenFlux sharing exports only the version, transport, document URL and shared
encryption key, with the profile name in the URI fragment. A warning precedes
QR/link export; Android also exposes the existing system share action and
sensitive clipboard with a 60-second clear timer. Export does not connect to
the document. The link is a secret, not an encrypted access-control invitation.
It does not make two simultaneous clients on one document possible.

The server helper prepares a fresh private key/config/import URI using verified
public artifacts of an existing managed installation. It defaults to no Docker
or network operations. Explicit apply/owned rollback only targets a new named
instance. The user must create a separate legacy-editor document. Docker bridge
creation changes host routes/NAT; production deployment was not performed.

## Verification Status

- Full offline build passed: 328 JVM and 204 Android host tests, release lint,
  Android native/binding checks and Windows packaged-JVM/native-asset checks.
  Installer metadata confirms build `2026092201` and same-version replacement.
- OpenFlux server tools passed 138 synthetic tests in the repository and again
  from the standalone ZIP; 37 notifier tests passed. The ZIP contains exactly
  18 reviewed source, documentation, test and license files, normalized to LF.
- A fresh API 35 x86-64 guest passed all 11 notification/profile/retry/share
  scenarios, including a signed/minified release upgrade without profile loss.
  Debug APK SHA256: `d537a92e11971ad1152c419b591612617b235c8b604ecdbb3dd05ff656f53cdb`.
  The tested release hash is recorded below. The owned guest was stopped.
- Two preceding attempts stopped on test-harness SystemUI readiness/hierarchy
  reads. Bounded rendering waits and read-only hierarchy retries were added;
  taps are not retried automatically. These aborted attempts are not passes.
- Release APK v1/v2 signatures use the existing signing certificate
  `0a8547df6c99acfc4cbf9ca0916fbf7301c648bf4aecc655f7904d43c4bbeaa0`.
  It is not debuggable. All 30 native entries match the previously verified
  2103 APK byte for byte; all three ABIs remain present.
- The Windows portable ZIP contains 251 files and 105 launcher classpath JARs.
  No profile, key, keystore or log files were found in its entry manifest.

Final Windows Local SOCKS checks passed for olcRTC and AWG: application-level
Connected, authenticated SOCKS5, both TLS-verified HTTPS targets (Instagram and
Wikipedia), and clean stop. olcRTC also passed an external TCP probe. Native
executables and olcRTC dictionaries matched the final desktop JAR. The first
olcRTC harness attempt lacked the dictionary source environment; correcting
only that isolated test environment allowed the actual connection test to run.

**VLESS did not pass the final traffic check.** It reached Connected and an
authenticated SOCKS CONNECT response, but two complete test runs encountered
inner HTTPS TLS EOF/reset errors. The last run reached one of two HTTPS targets;
it is not a full pass. This was not a certificate-validation bypass or timeout;
the outer transport/root cause for those runs remains undetermined. TLS
verification was not weakened. The Xray native revision is unchanged.
Each Windows run verified unchanged host proxy/DNS/routes, preserved existing
VPN processes, no remaining owned native children and closed test SOCKS ports.
HTTP proxy environment was cleared only inside the offline test child process.

Follow-up diagnostics on 2026-09-23 checked the same packaged native Xray core
outside the application. The saved profile matched the running server, and
both TLS-verified HTTPS targets returned 200 through an explicit existing
OpenFlux proxy, but failed on the direct path. Coordinated direct TCP attempts
produced no headers at the server capture point; the proxy positive control
was observed, with zero capture drops. This narrows the current failure to a
path before that observation point, not a particular ISP or firewall. It is
not a direct-VLESS fix or a full-app traffic pass. See the
[separate diagnostic report](vless-connectivity-20260923.md).

Final Android real-traffic checks on 2026-09-23 used fresh owned API 35 x86-64
guests and the exact final debug APK hash recorded above, verified both locally
and after installation. These are not live-traffic tests of the signed release APK.

| Profile / run | Guest TUN HTTPS | Explicit local SOCKS HTTPS |
| --- | --- | --- |
| olcRTC, existing Jitsi test profile, initial run | DNS resolution failed for Instagram and Wikipedia | Both failed: timeouts, TLS error and CONNECT rejection |
| olcRTC, one fresh repeat | Instagram 200; Wikipedia 200 | Wikipedia 200; Instagram CONNECT rejected twice |
| AWG | Instagram 200; Wikipedia 200 | Instagram 200 on retry; Wikipedia TLS closure, then timeout |

Every run reached application-level Connected and guest VPN, stopped cleanly
with the app process surviving, and left no owned guest running. **The strict
Android SOCKS + TUN traffic matrix did not fully pass for either protocol.**
TLS verification remained enabled, with no direct fallback. The harness's
`socks_authenticated` flag records configured credentials, not a completed
authentication exchange. Native `GoLog` was not captured; readiness and stable
process IDs alone do not establish uninterrupted transport or working traffic.

Fresh final-build OpenFlux live testing remains pending. Earlier core, packaging
and real-traffic results are recorded separately in the
[2103 candidate report](release-0.0.14-2026092103.md); they are not automatically
claimed as fresh tests of this APK.

The synthetic Android harness includes failed initial connection, retry of the
same profile, failed switch with rollback, retry of the failed target after
rollback, window persistence and clean Stop. Invalid VLESS transport fixtures
fail locally and contain only guest-loopback destinations. OpenFlux export uses
a synthetic inactive profile and never starts a document client. Chooser
screenshots are black because FLAG_SECURE remains enabled; UI hierarchy and
service state, not an unprotected screenshot, provide the assertions. These
cases do not prove internet traffic.

## Final Artifact SHA256

| Artifact | SHA256 |
| --- | --- |
| Android APK, build 2026092201 | `8d842a08faf26f9a0b84ef9bf3e4aa5e0146d3c38b8dbaab2ded8a1a2818474a` |
| Windows installer, build 2026092201 | `ef45b455c83ae71763d12f2c97b46a2000c8e2bee6fca4d60d556b0e6e807f2e` |
| Windows portable, build 2026092201 | `d2a4c92b4b5f3eb55800cb68c111f54e266ea05cc69760efc65b0a1794160495` |
| Server profile tools ZIP, 2026092201 | `e92e576e3dabc3af79211e310a7b28a7308036782da2d07590f7a4d3754f4fd0` |

`SHA256SUMS` also includes the unchanged notifier ZIP and corresponding OpenFlux,
AWG core and backend source archives. Exact filenames are in the release notes.

## Unchanged Limits

Windows TUN remains experimental; full TUN traffic, actual UAC interaction,
crash recovery, DNS/IPv6 leaks, sleep and network transitions still require
isolated Windows VM validation. Windows olcRTC/OpenFlux TUN is unavailable.
There is no persistent kill-switch or leak-proof routing claim. Android
olcRTC/OpenFlux retain their shared per-app split rules. OpenFlux carries
TCP/IPv4, not general UDP or IPv6. The user's active OpenFlux session is not
interrupted for testing. No physical-phone or new Linux release result is
claimed. The Windows installer is not Authenticode-signed.

Only explicitly reviewed public source files and release artifacts may be
uploaded. Private profiles, document links, raw logs, credentials, bot state and
unrelated work remain local. Installing a client does not upgrade server cores.

# Переподключение Android И Проверка Выпуска

Сборка от 22.09.2026; финальная проверка продолжена 23.09.2026.
Unified VPN 0.0.14 Preview, сборка `2026092201`. Она заменяет
локальную `2026092103` до публикации. Стабильным остаётся `0.0.12`. Добавлено
переподключение из окна уведомления, отправка OpenFlux-профиля и серверный
помощник с подготовкой без сети по умолчанию; версии нативных olcRTC, AWG,
Xray и OpenFlux не менялись относительно [обновления ядер](upstream-refresh-2026092103.md).

## Поведение

Окно профилей Android больше не закрывается само после результата подключения
и Stop. Крестик, Back и значок приложения позволяют выйти; уход в другую
Activity и блокировка устройства по-прежнему закрывают защищённое окно.
Показа поверх чужих окон или обхода блокировки экрана нет.

Отдельная кнопка «Переподключить» повторяет профиль, который не подключился.
Если после ошибки переключения восстановлен прежний рабочий профиль, retry
по-прежнему пробует неудавшийся, а не восстановленный. Состояние ожидания UI
и проверки службы не допускают повторных запусков. Результат должен относиться
к новому поколению подключения, а не прежнему Error/Connected. Существующие
последовательная остановка движков и настройки раздельного туннелирования
сохранены. Сырые серверные ошибки и секреты в окно не выводятся.

Отправка OpenFlux содержит только версию, транспорт, ссылку документа и общий
ключ, с именем профиля во фрагменте URI. Перед QR/ссылкой показывается
предупреждение; Android также использует системную отправку и конфиденциальный
буфер с очисткой через 60 секунд. Соединение с документом не запускается.
Ссылка является секретом, не защищённым персональным приглашением. Два клиента
одновременно на одном документе по-прежнему не поддерживаются.

Серверный помощник создаёт новый ключ, конфиг и ссылку на основе проверенных
публичных файлов существующей установки. По умолчанию Docker и сеть не
используются. Явные установка/откат ограничены новым именованным экземпляром.
Отдельный документ старого редактора создаёт пользователь. Docker bridge
изменяет маршруты/NAT хоста; на рабочем сервере помощник не запускался.

## Состояние Проверок

- Полная офлайн-сборка успешна: 328 JVM и 204 Android host-теста, release lint,
  проверки Android native/bindings и запуска готового Windows JVM-пакета.
  Установщик содержит билд `2026092201` и правила замены той же версии.
- Серверные инструменты OpenFlux прошли 138 тестов в репозитории и после
  распаковки отдельного ZIP; бот прошёл 37 тестов. В ZIP ровно 18 проверенных
  файлов исходников, документации, тестов и лицензии с переносами LF.
- Новый эмулятор API 35 x86-64 прошёл все 11 сценариев уведомлений, профилей,
  retry и отправки, включая обновление подписанным release с сохранением
  профилей. Хеши debug и release APK указаны выше. Созданный
  для теста гость остановлен.
- Две предыдущие попытки остановились на готовности SystemUI/чтении иерархии
  тестовым сценарием. Добавлены ограниченное ожидание отрисовки и повтор только
  чтения; нажатия автоматически не повторяются. Прерванные попытки не зачтены.
- Подписи APK v1/v2 и прежний сертификат проверены, debuggable выключен.
  Все 30 нативных файлов побайтно совпали с проверенной сборкой 2103; три ABI
  сохранены. Portable содержит 251 файл и 105 JAR в classpath; в списке нет
  файлов профилей, ключей, хранилищ сертификатов или логов.

Итоговая проверка Windows Local SOCKS успешна для olcRTC и AWG: Connected,
авторизованный SOCKS5, обе HTTPS-цели с проверкой TLS (Instagram/Wikipedia) и
корректная остановка. olcRTC также передал внешний TCP. Нативные файлы и словари
olcRTC совпали с финальным desktop JAR. Первая попытка тестового сценария olcRTC
не имела переменной пути к словарям; исправлена только изолированная тестовая
среда, после чего реальное соединение было проверено.

**VLESS не прошёл итоговую проверку трафика.** Были достигнуты Connected и
авторизованный SOCKS CONNECT, но два прогона встретили EOF/reset при внутреннем
HTTPS TLS-обмене. Последний получил ответ одной из двух целей; это не полный
успех. Обхода проверки сертификатов и таймаута не было; причина внешнего
транспорта для тех прогонов не установлена. Проверка TLS не ослаблялась.
Версия Xray не менялась. После обоих Windows-прогонов подтверждены неизменные
прокси/DNS/маршруты хоста, сохранность старых VPN-процессов, отсутствие своих
оставшихся движков и закрытие тестовых SOCKS-портов. Переменные HTTP-прокси
очищались только в дочернем офлайн-процессе теста.

Дополнительная диагностика 23.09.2026 проверила то же нативное ядро Xray из
пакета вне приложения. Сохранённый профиль совпал с работающим сервером;
обе HTTPS-цели вернули 200 с проверкой TLS через явно заданный действующий
OpenFlux-прокси, но напрямую не прошли. Прямые TCP-попытки не дали пакетов
в точке наблюдения на сервере; положительный контроль через прокси был виден,
потерь при захвате не было. Текущий сбой локализован до этой точки наблюдения,
но конкретный оператор или межсетевой экран не определён. Это не исправление
прямого VLESS и не успешная проверка трафика полного приложения. См.
[отдельный диагностический отчёт](vless-connectivity-20260923.md).

Реальный Android-трафик проверен 23.09.2026 в новых собственных гостях API 35
x86-64 с финальным debug APK: указанный выше SHA256 проверен локально и после
установки. Это не проверка реального трафика подписанного release APK.

| Профиль / прогон | HTTPS через TUN гостя | HTTPS через явный локальный SOCKS |
| --- | --- | --- |
| olcRTC, существующий тестовый профиль Jitsi, первый прогон | Ошибки DNS для Instagram и Wikipedia | Обе цели не пройдены: таймауты, ошибка TLS и отказ CONNECT |
| olcRTC, один повтор в новом госте | Instagram 200; Wikipedia 200 | Wikipedia 200; два отказа CONNECT для Instagram |
| AWG | Instagram 200; Wikipedia 200 | Instagram 200 при повторе; Wikipedia: закрытие TLS, затем таймаут |

Каждый прогон достиг Connected и VPN гостя, корректно остановился с сохранением
процесса приложения; работающих тестовых гостей не осталось. **Полная строгая
матрица Android SOCKS + TUN не пройдена ни одним протоколом.** Проверка TLS
сохранена, прямого обхода нет. Флаг сценария `socks_authenticated` отражает
настроенные реквизиты, а не завершённую авторизацию. Нативный `GoLog` не собирался;
готовность и неизменные PID сами по себе не доказывают непрерывность транспорта
или передачу трафика.

Свежая проверка реального OpenFlux итоговой сборки пока ожидается. Прежние
проверки ядер, упаковки и трафика находятся в
[отчёте 2103](release-0.0.14-2026092103.md) и не выдаются за новую проверку APK.

Синтетический сценарий Android включает ошибку первого соединения, повтор
того же профиля, неудачное переключение с откатом, повтор неудавшегося профиля
после отката, сохранение окна и Stop. Неподдерживаемый VLESS transport вызывает
локальный отказ; адреса только loopback гостя. Отправка OpenFlux использует
вымышленный неактивный профиль без запуска клиента. Скриншоты окна чёрные из-за
сохранённого FLAG_SECURE; проверки опираются на иерархию UI и состояние службы.
Такой тест сам по себе не подтверждает интернет-трафик. Итоговые SHA256 приведены
в общей таблице выше; `SHA256SUMS` также содержит неизменившиеся архивы бота и
соответствующих исходников OpenFlux/AWG.

## Прежние Ограничения

Windows TUN экспериментальный: реальные TUN/UAC, аварийная очистка, утечки
DNS/IPv6, сон и смена сети требуют отдельной Windows VM. Windows TUN
olcRTC/OpenFlux недоступен. Постоянного kill-switch нет; отсутствие утечек не
заявляется. Android сохраняет общую группу правил приложений olcRTC/OpenFlux.
OpenFlux передаёт TCP/IPv4, не произвольные UDP/IPv6. Рабочее подключение
OpenFlux пользователя для тестов не прерывается. Проверка физического телефона
или новый Linux-выпуск не заявляются. Windows EXE не подписан Authenticode.

Публикуются только проверенные публичные исходники и явно перечисленные
артефакты. Приватные профили, ссылки на документы, сырые логи, пароли, состояние
бота и посторонние работы остаются локально. Установка клиента не обновляет
серверные ядра.
