# New Server Connectivity, 2026-09-24

## English

This follows the [earlier build-2026092405 trial](openflux-runtime-2026092405.md).
The application binaries remain **0.0.14 / 2026092405**. This work changes the
main server deployment and creates one private AWG client export, not a new
application release. No GitHub publication was performed.

### AWG

- Added one dedicated Windows peer to the existing AWG2 instance. The original
  nine peers were retained; the container was not restarted. The server
  configuration and client table were backed up before the change.
- Exported connection-only `vpn://`, Unified VPN `awg://`, and native `.conf`
  files. All three represent **the same device peer**, not three independent
  credentials. They contain no SSH credentials or server private key.
- The actual packaged Unified VPN decoder accepted the `vpn://` export and its
  configuration builder produced the tested native configuration.
- Two isolated Windows SOCKS5 tests reached Instagram and Telegram with HTTP
  200 and strict TLS verification. The second test used that packaged builder's
  output. Server handshake/transfer counters also confirmed real AWG traffic.
- The tests stopped their own native processes and left the Windows proxy,
  routes and DNS unchanged. They did **not** exercise system TUN, the official
  Amnezia GUI importer, an Android phone or another operator's network.

The import files and receipts are in the ignored, ACL-restricted private test
directory. They are intentionally not embedded in this report or committed.

### OpenFlux

The legacy server received a browser-verification page instead of editable
document configuration, even though HTTPS returned 200. The main instance was
upgraded to the existing `d34dc8c` / wrapper-4 candidate with an isolated,
sandboxed browser companion. Its document URL, encryption key, AES-256-GCM and
legacy LZ4 framing were preserved. The old container, image and configuration
remain available for rollback and must not run concurrently on the same room.

The receipt-bound managed systemd service is enabled and running. Autostart is
configured, but a real VPS reboot has **not** been tested. The separate phone
instance was not upgraded or occupied in these tests.

| New-server check | Result |
| --- | --- |
| Pinned SSH identity and public-key access | PASS |
| Isolated server Chromium sandbox and document handoff | PASS |
| First packaged Windows trial against the new server | PASS: authenticated Ready, Instagram 200, Telegram 200, additional HTTPS after 25 seconds, stop/cleanup |
| Post-commit packaged Windows repeat | FAIL: initial Instagram/Telegram 200, then peer deadline expiry and closed SOCKS listener |
| Fresh Android API 35 x86_64 guest, exact build-2405 APK | FAIL before TUN: browser-verification timeout; no authenticated Ready or HTTPS proof |
| Physical Android 13, system TUN on Windows, sustained load, VPS reboot | Not tested |

The deployment was committed after the first complete Windows traffic proof.
That proof is a point-in-time result, **not a stability guarantee**. The later
failure occurred without a managed-server restart. A running process does not
prove a usable VPN. The new main service remains in place; reverting to the
original browser-blocked server is not assumed to restore connectivity.

The first counters-only Windows diagnostic stopped before authenticated Ready
because its harness exceeded a CIM-query deadline. The harness was corrected;
the user subsequently authorized repeated tests. Later diagnostics established
real HTTPS traffic, but did not establish repeatable stability: one run passed
seven HTTPS probes over 150 seconds, while others lost peer traffic even as
WebSocket ping/pong continued. One run received document `disconnectReason`
4007 before reconnecting. That is evidence of a document-session drop, not
proof of its cause. A client-only coauthoring acknowledgement experiment did
not resolve the failure.

Local coauthoring acknowledgement source changes passed focused Go tests but
have not been packaged or deployed. Diagnostic processes stopped, private
runtime configurations were removed and host network settings were preserved.
Neither the diagnostic binaries nor that source change are part of the
installed server or build 2026092405.

### Volga / vyandex Feasibility

- Upstream already includes `YandexVolgaTransport` in pinned commit `d34dc8c`.
  It uses HTTP relay plus WebSocket, separate from the older `yandex` channel.
  See the [pinned implementation](https://github.com/p1neappleXpress/OpenFlux/blob/d34dc8caa70ca059cd80d8f5753499361052dabc/transport/yandex/vyandex.go).
- Our wrapper and shared Android/Windows profile model still allow only
  `yandex`. Updating the upstream source alone did not expose all transports.
- An offline probe on the VPS used an exact SHA-256-verified temporary copy
  of the installed wrapper-4 binary: `yandex` returned `OPENFLUX_CONFIG_OK`;
  `vyandex` returned `unsupported OpenFlux version, mode, or transport`.
  No document request was made. Installed configuration and the observed
  OpenFlux/Jitsi service identities were unchanged.
- Integration is feasible through the existing transport interface while
  retaining mandatory AES-256-GCM. It needs explicit transport selection in
  the native wrapper, profile import/editor/export, server tools, and rebuilt
  Windows/Android/server binaries. Browser bootstrap and error handling are
  currently specific to `YandexDocsTransport`; Volga needs its own supported
  lifecycle, bounded resource defaults and tests.
- A separate, empty, anonymously editable Volga document is needed for a real
  paired test. Do not replace the current document or silently migrate profiles.
  Both peers must select the same transport, key, document and framing.
- The upstream [issue 89](https://github.com/p1neappleXpress/OpenFlux/issues/89)
  reports a Volga browser-challenge problem and a User-Agent workaround. This
  is an external report, not a fix verified on this server. The latest main
  source also contains changed challenge handling; it was inspected, not
  installed. A new editor is therefore a testable alternative, not a proven
  solution to our current failures. No live `vyandex` traffic was tested.

### Evidence And Limits

| Tested artifact | SHA-256 |
| --- | --- |
| Packaged AWG Windows native | `640eba8c0c69b7d14fcc96e05a8acd84af2be8d42db1049d77733a89ff6aa786` |
| Packaged native-resource JAR | `f0691f1934f0e1081ca814823a3ed40a6c86669e9dc56288fde76a342c5c9411` |
| OpenFlux Windows native | `97261770f82971f3cb181d9acea47390d3452e75a30dda0a5f8bf34a95d1852c` |
| Installed OpenFlux Linux native | `c6e3fabb09ce5105da424ec4b6c6f9f2185a8801d90716a8a1ddd6f068b4afa1` |
| Android debug APK | `c354d250846159318ea04319782379151c4ec134feb69cc87e5516eea9d5e41e` |

Private receipts retain the successful and failed runs. No document link,
cookie, client/server key, personal address or SSH credential is included here.
The working Jitsi service was not targeted by stop/restart commands. Its PID
changed independently during this work; uninterrupted server-process identity
is therefore not claimed. No host firewall reset was issued.

Remaining work: distinguish Yandex WebSocket delivery/reconnection loss from
peer response loss; separately establish why Android WebView verification
times out. Do not relax TLS, remove authenticated peer checks, broaden cookie
scope, or treat ICE/browser/process readiness as real-traffic success. The
OpenFlux release gate remains **not passed**.

## Русский

Это продолжение [предыдущего испытания сборки 2026092405](openflux-runtime-2026092405.md).
Бинарники приложения остаются **0.0.14 / 2026092405**. Изменены развёртывание
основного сервера и один приватный клиентский профиль AWG. Нового выпуска
приложения и публикации на GitHub не было.

### AWG

- В существующий AWG2 добавлен отдельный Windows-клиент. Прежние девять
  клиентов сохранены, контейнер не перезапускался. Конфигурация и таблица
  клиентов предварительно скопированы для восстановления.
- Подготовлены файлы `vpn://`, `awg://` для Unified VPN и `.conf`. Это три
  формата **одного профиля устройства**, а не независимые ключи для нескольких
  устройств. Данных SSH и приватного серверного ключа в них нет.
- Настоящий декодер собранного Unified VPN принял `vpn://`; построитель
  конфигурации приложения создал настройки для native-ядра.
- Два отдельных Windows-теста через SOCKS5 получили HTTP 200 от Instagram и
  Telegram с обычной проверкой TLS. Второй использовал именно конфигурацию,
  созданную собранным приложением. На сервере подтверждены handshake и трафик.
- Тестовые процессы остановлены; прокси, маршруты и DNS Windows не менялись.
  Эти проверки не подтверждают системный TUN, импорт через интерфейс
  официальной Amnezia, работу телефона Android или сети другого оператора.

Файлы импорта и результаты находятся только в игнорируемом приватном каталоге
с ограниченным ACL. Они намеренно не включены в этот отчёт и Git.

### OpenFlux

Старый сервер получал страницу браузерной проверки вместо настроек редактора,
несмотря на HTTP 200. Основной экземпляр переведён на подготовленное ядро
`d34dc8c` / wrapper 4 с отдельным браузером в sandbox. Документ, ключ,
AES-256-GCM и прежний LZ4 сохранены. Исходный контейнер, образ и конфигурация
оставлены для отката; одновременно запускать их с новым сервером в той же
комнате нельзя.

Управляемая systemd-служба работает и включена в автозапуск. Перезагрузка VPS
не проверялась. Отдельный телефонный экземпляр не обновлялся и не занимался
этими проверками.

| Проверка на новом сервере | Результат |
| --- | --- |
| Закреплённый SSH-ключ сервера и вход по публичному ключу | Успех |
| Sandbox Chromium и передача браузерной сессии ядру | Успех |
| Первый тест собранного Windows-клиента | Успех: зашифрованный Ready, Instagram 200, Telegram 200, HTTPS через 25 секунд, остановка и очистка |
| Повтор после фиксации обновления | Сбой: сначала Instagram/Telegram 200, затем тайм-аут ответа пира и закрытие SOCKS |
| Чистый Android API 35 x86_64 с точным APK сборки 2405 | Сбой браузерной проверки до TUN; Ready и HTTPS не подтверждены |
| Физический Android 13, Windows TUN, длительная нагрузка, перезагрузка VPS | Не проверялись |

Обновление сервера зафиксировано после первого полного Windows-теста. Этот
результат подтверждает работу в тот момент, **но не устойчивость**. Позднейший
сбой произошёл без перезапуска управляемой серверной службы. Живой процесс
сам по себе не доказывает работу VPN. Новый основной сервер оставлен запущенным:
возврат к прежнему серверу, заблокированному браузерной проверкой, не считается
гарантированным восстановлением.

Первый Windows-тест со счётчиками остановился из-за тайм-аута CIM в стенде.
Стенд исправлен, затем пользователь разрешил повторные проверки. В последующих
тестах был реальный HTTPS-трафик, но устойчивость не подтверждена: один прогон
прошёл семь HTTPS-проверок за 150 секунд, другие теряли обмен с пиром, хотя
WebSocket ping/pong продолжался. В одном прогоне получен `disconnectReason`
4007 перед переподключением. Это подтверждает сброс сессии документа, но не
его причину. Эксперимент с подтверждением совместного редактирования только
на клиенте проблему не решил.

Локальная правка подтверждения совместного редактирования прошла целевые
Go-тесты, но не включена в сборку и не развёрнута. Тестовые процессы остановлены,
временные конфигурации удалены, сетевые настройки сохранены. Установленное
серверное ядро и приложение 2026092405 не содержат диагностических изменений
или этой новой правки.

### Возможность Добавления Volga / vyandex

- В исходном OpenFlux на закреплённом коммите `d34dc8c` уже есть
  `YandexVolgaTransport`: HTTP relay и WebSocket вместо старого канала `yandex`.
  [Исходный код](https://github.com/p1neappleXpress/OpenFlux/blob/d34dc8caa70ca059cd80d8f5753499361052dabc/transport/yandex/vyandex.go).
- Наша обёртка и общая модель профилей Android/Windows пока разрешают только
  `yandex`. Обновление исходников само по себе не подключило остальные транспорты.
- На VPS выполнена offline-проверка точной временной копии установленного ядра
  wrapper 4 с проверкой SHA-256: `yandex` принят, `vyandex` отклонён с
  `unsupported OpenFlux version, mode, or transport`. Обращений к документу
  не было; конфигурация и наблюдаемые процессы служб OpenFlux/Jitsi не менялись.
- Интеграция возможна через существующий интерфейс транспорта с сохранением
  обязательного AES-256-GCM. Нужны выбор транспорта в ядре, импорт/редактор/экспорт
  профилей, серверные инструменты и пересборка Windows, Android и сервера.
  Браузерная проверка и обработка ошибок сейчас привязаны к старому транспорту;
  для Volga нужны адаптация, разумные ограничения ресурсов и тесты.
- Для парного испытания нужен отдельный пустой документ Volga с редактированием
  по ссылке без входа в аккаунт. Текущий документ и профили не заменять.
  Обе стороны должны использовать одинаковые транспорт, документ, ключ и формат
  пакетов.
- В upstream [issue 89](https://github.com/p1neappleXpress/OpenFlux/issues/89)
  описаны браузерная проверка Volga и обход через замену User-Agent. Это чужой
  результат, не проверенное исправление для нашего сервера. В актуальном main
  также есть изменённая обработка проверки; код изучен, но не установлен.
  Новый редактор пока является вариантом для испытания, а не доказанным решением.
  Реального соединения через `vyandex` в этой проверке не было.

Хеши проверенных артефактов приведены выше. В приватном каталоге сохранены и
успехи, и сбои. Здесь нет ссылок документов, cookies, ключей клиентов/сервера,
личных адресов или данных SSH. Команды остановки/перезапуска Jitsi не выполнялись;
его PID изменился независимо, поэтому неизменность всех серверных процессов
не утверждается. Команд сброса firewall не было.

Осталось установить причину потери обмена через WebSocket Яндекса или ответа
пира, а отдельно причину тайм-аута WebView на Android. Нельзя заменять это
ослаблением TLS, отключением проверки пира, расширением области cookies или
приравниванием готовности браузера/процесса к рабочему VPN. **Проверки,
необходимые для выпуска OpenFlux, пока не пройдены.**
