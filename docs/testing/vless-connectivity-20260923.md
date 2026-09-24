# VLESS Connectivity Diagnostics

## English

Date: 2026-09-23. Unified VPN 0.0.14 Preview, build `2026092201`.

### Verified Configuration

- The active server runs Xray `26.2.6` under `x-ui`. The saved client profile
  matches all checked server fields, including UUID, XHTTP path, REALITY server
  name and short ID; its public key also matches the server key
  cryptographically. The hostname
  resolves to one IPv4 address, matching the server reached over SSH.
- The inspected server iptables rules allow the ingress port, with no
  port-specific DROP rule found. This does not exclude a provider firewall,
  other filtering layers or ISP filtering.
- The final packaged client metadata is `0.0.14 / 2026092201`, with Xray
  `26.3.27`. Four offline checks against its packaged classes passed: short and
  long parameter aliases, each with pinned and unpinned endpoints. They preserve
  XHTTP/REALITY, the endpoint port, path, `mode=auto`, absent/empty flow, server
  name, fingerprint, public key and short ID.
- The tested packaged native core matches SHA256
  `15c2d007954ac53ba69b80ec91242786b3c0b71d52649165b4ca1d5cc96ef8f1`.

### Traffic Results

- A direct TCP control connection to an independent public HTTPS endpoint
  succeeded in about 33 ms. Direct TCP connections to the VLESS endpoint by
  hostname and by its resolved address both timed out after 6 seconds.
- The existing local HTTP proxy returned `CONNECT 200` for the VLESS endpoint.
  Using that explicit OpenFlux proxy as the alternate upstream path, the same
  VLESS profile and exact packaged native core passed authenticated local SOCKS
  and strict TLS/hostname verification: Instagram HTTP 200 in about 937 ms and
  Wikipedia HTTP 200 in about 467 ms.
  OpenFlux exits on the same server as Xray. This comparison verifies the
  profile and Xray traffic path, not external reachability of the public
  ingress port; it is not a second independent external network vantage point.
- With the same core and profile on the direct upstream path, local SOCKS
  authentication succeeded, but both HTTPS probes ended in an inner TLS
  connection reset after about 16 seconds, with no HTTP response.
- These were isolated native-core tests, without launching the application.
  Only the owned test processes were stopped; the existing OpenFlux process
  was not stopped. No product source, version or active server configuration
  was changed by these diagnostics.

### Conclusion And Pending Work

The client and server can exchange VLESS traffic over the alternate path.
This is not a direct-VLESS fix or a full-application, TUN or mobile pass. The
user reports failures on both Wi-Fi and mobile networks; those two paths have
not been independently verified here.

A coordinated 32.59-second server capture observed zero TCP headers for the
VLESS ingress during the 23.13-second window containing three direct TCP
attempts, each timing out after 7 seconds. Idle controls also observed zero.
The proxy-path positive control succeeded in 0.14 seconds, with SYN, SYN-ACK
and ACK visible; capture on all interfaces produced duplicate observations.
There were no kernel capture drops or capture errors. No payloads or pcap
files were retained.

The direct-path failure occurs before the server's tcpdump observation point.
This does not distinguish the client, upstream network, transit, provider,
hypervisor or early XDP processing, and does not identify an ISP. No
configuration reset or workaround was installed. Direct VLESS remains
unresolved; no full-application, TUN or mobile pass is claimed.

### Next Check

The user-supplied hosting panel status shows an enabled interface and traffic
usage below the included allowance. It does not establish public ingress
reachability or the absence of upstream filtering. No panel changes were made.

Ask the hosting provider to inspect ingress routing and any provider-side
filtering for the affected server and port, using the private probe timestamps.
The packet capture narrows the investigation but does not attribute fault to
the provider. Do not reset working keys, disable the firewall, change the
primary interface or restart the server on the basis of these results.

### Post-Reboot VLESS And AWG Checks

A separate post-reboot check on the same date used the working olcRTC proxy
for SSH and the VLESS positive control. It did not restart that connection or
the user's working OpenFlux client. The earlier OpenFlux-path results above
are a different test window.

- The direct Windows route selected an active physical interface and its
  default gateway for both VPN endpoints, not a stale TUN route. The reviewed
  enabled outbound Windows Firewall block rules did not match the target for
  the approved diagnostic process. Actual physical NIC egress was not captured,
  so other client-side filtering is not ruled out.
- Two direct TCP connections to the VLESS ingress timed out after 6 seconds
  each, with no SYN, SYN-ACK or RST observed during their server capture phases.
  Direct SSH and HTTP controls also timed out without matching SYNs. The HTTPS
  port-only capture included unrelated traffic and is inconclusive. Capture
  ran for 38.204 seconds with zero kernel drops; no payload or pcap was retained.
- The existing-proxy TCP control succeeded in 0.102 seconds. The exact bundled
  Xray core then passed strict TLS and received HTTP 200 from Instagram and
  Wikipedia in 1.045 and 0.702 seconds respectively. This proxy also exits on
  the same server: it proves the application path, not public ingress.
- Both AWG containers and their interfaces were up. The selected client peer,
  server public key, decoded PSK, endpoint publication and checked obfuscation
  values matched. Live inspection of I1 was unsupported, not a demonstrated
  mismatch. No peer had completed a handshake since the server reboot.
- One isolated attempt used the exact bundled AWG core, SHA256
  `640eba8c0c69b7d14fcc96e05a8acd84af2be8d42db1049d77733a89ff6aa786`.
  It logged four handshake initiations and zero responses. Both HTTPS requests
  failed at TLS after about 10 seconds, despite successful local SOCKS
  authentication and CONNECT responses. Local readiness is not a remote pass.
- During the coordinated AWG observation, the physical-uplink UDP ingress and
  egress counts were zero, the selected peer's handshake and transfer counters
  stayed zero, and the matching DNAT/ACCEPT counters did not increase. Capture
  reported zero kernel drops. No payload or pcap was retained.
- An independent review found no deterministic filter/snapshot bug in that
  AWG observer. Its scope was the IPv4 default-route Ethernet-type interface,
  not all interfaces. The original observer did not retain child exit codes
  or all warning categories, so its zero counts alone cannot exclude every
  capture failure. Client initiation logs also do not prove physical NIC egress.
- One final, 10.19-second control used exact assigned server addresses. The
  Windows send API accepted two small non-secret UDP datagrams to each of the
  AWG and unused UDP 443 ports; TCP 443 timed out after 5.009 seconds. All six
  service-port observers were ready throughout and exited normally, with zero
  counts, drops, parse errors or unknown stderr lines. A standard UDP DNS query
  passed on both Windows and the server. However, the capture saw the DNS reply
  but not the outbound query and discarded one unclassified stderr line from
  each DNS observer. The full bidirectional capture-positive control therefore
  did not pass. These results are not proof of a particular external blocker;
  no port change or firewall workaround was applied.
- After an additional user report, read-only inspection found MTProto running
  with its published TCP port and matching host/container listeners. One direct
  connection to that port timed out after 6.022 seconds, while CONNECT through
  the same-server proxy returned HTTP 200 in 0.111 seconds. This was only a TCP
  listener check: no MTProto secret or protocol payload was sent, and no
  successful Telegram session is claimed. The reported web proxy has not yet
  been identified; Caddy being active does not establish that it is that proxy.
- A later read-only check at 18:16 UTC found `tproxy-server.service`,
  `mtproxy.service` and Caddy active and running. The WEB relay listens on
  loopback, separately from the previously inspected MTProto container.
  This confirms an installed WEB server component, not its association with
  the user's browser connection or successful WEB authentication. No service
  configurations or logs were read, and no configuration was changed.
- The owned native process and observers stopped; the test listener closed.
  Host proxy, route and DNS settings were unchanged, and protected existing
  processes were preserved. No product code, server configuration, credentials,
  firewall rule, service or container was changed by these checks.

Direct VLESS and AWG remain unresolved. This does not identify which operator,
transit network or hosting component drops the traffic. No fresh full-app,
Android or TUN success is claimed. A private EN/RU provider ticket includes
correlated timestamps and requests an independent external-origin check without
rebooting or resetting the server.

## Русский

Дата: 2026-09-23. Unified VPN 0.0.14 Preview, сборка `2026092201`.

### Проверенная Конфигурация

- Активный сервер использует Xray `26.2.6` под управлением `x-ui`. Сохранённый
  профиль клиента совпадает со всеми проверенными полями активной конфигурации
  сервера, включая UUID, путь XHTTP, имя сервера REALITY и short ID;
  соответствие открытого ключа ключу
  сервера также проверено криптографически. Имя узла разрешается в один адрес
  IPv4, совпадающий с адресом сервера, доступного по SSH.
- Проверенные правила iptables разрешают входящий порт; отдельное правило
  DROP для этого порта не найдено. Это не исключает межсетевой экран
  провайдера, другие уровни фильтрации или фильтрацию со стороны оператора.
- Метаданные итогового пакета клиента: `0.0.14 / 2026092201`, Xray `26.3.27`.
  Четыре автономные проверки классов из пакета прошли: короткие и полные
  имена параметров, каждое сочетание с фиксацией адреса сервера и без неё.
  Сохраняются XHTTP/REALITY, порт, путь, `mode=auto`, отсутствие или пустое
  значение flow, имя сервера, fingerprint, открытый ключ и short ID.
- SHA256 проверенного нативного ядра из пакета:
  `15c2d007954ac53ba69b80ec91242786b3c0b71d52649165b4ca1d5cc96ef8f1`.

### Результаты Передачи Данных

- Прямое контрольное TCP-соединение с независимым публичным HTTPS-узлом
  установлено примерно за 33 мс. Прямые TCP-соединения с узлом VLESS по имени
  и по полученному адресу завершились тайм-аутом через 6 секунд каждое.
- Действующий локальный HTTP-прокси вернул `CONNECT 200` для узла VLESS.
  Через этот явно заданный прокси OpenFlux тот же профиль VLESS и точное
  нативное ядро из пакета прошли аутентификацию локального SOCKS и строгую
  проверку TLS с проверкой имени узла: Instagram HTTP 200 примерно за 937 мс,
  Wikipedia HTTP 200 примерно за 467 мс.
  OpenFlux выходит на том же сервере, где работает Xray. Сравнение проверяет
  профиль и передачу трафика через Xray, но не доступность публичного входящего
  порта извне; это не вторая независимая точка проверки во внешней сети.
- При прямом подключении того же ядра с тем же профилем аутентификация
  локального SOCKS прошла, но обе HTTPS-проверки завершились сбросом
  соединения на этапе внутреннего TLS примерно через 16 секунд, без
  HTTP-ответа.
- Проверки выполнялись изолированным нативным ядром без запуска приложения.
  Остановлены только собственные тестовые процессы; действующий процесс
  OpenFlux не останавливался. Эти проверки не меняли исходный код продукта,
  версию или активную конфигурацию сервера.

### Вывод И Ожидаемая Проверка

Клиент и сервер способны передавать трафик VLESS по альтернативному пути.
Это не означает исправление прямого подключения VLESS или успешную проверку
полного приложения, TUN либо мобильного клиента. Пользователь сообщает о
сбоях как через Wi-Fi, так и через мобильную сеть; эти два пути здесь
независимо не проверены.

Согласованный захват на сервере длился 32,59 секунды. За окно 23,13 секунды
с тремя прямыми TCP-попытками, каждая из которых завершилась тайм-аутом через
7 секунд, фильтр входящего VLESS не зафиксировал ни одного TCP-заголовка.
Контрольные интервалы без запросов также дали ноль. Положительная проверка
через прокси прошла за 0,14 секунды: наблюдались SYN, SYN-ACK и ACK; захват
на всех интерфейсах дал повторные наблюдения. Потерь захвата в ядре и ошибок
захвата не было. Полезная нагрузка и файлы pcap не сохранялись.

Сбой прямого пути возникает до точки наблюдения tcpdump на сервере. Это не
позволяет различить проблему клиента, вышестоящей сети, транзита, провайдера,
гипервизора или ранней обработки XDP и не указывает на конкретного оператора.
Сброс конфигурации и обходные решения не применялись. Прямое подключение
VLESS остаётся неисправленным; успешная проверка полного приложения, TUN или
мобильного клиента не заявляется.

### Следующая Проверка

Предоставленный пользователем статус панели хостинга показывает включённый
интерфейс и расход трафика ниже включённого лимита. Это не подтверждает
доступность входящих подключений извне или отсутствие внешней фильтрации.
Настройки панели не менялись.

Следующий шаг: запросить у хостинга проверку входящей маршрутизации и фильтрации
для затронутого сервера и порта, приложив время проб из приватного отчёта.
Захват пакетов сужает область поиска, но не доказывает вину хостинга. Эти
результаты не дают оснований сбрасывать рабочие ключи, выключать межсетевой
экран, менять основной интерфейс или перезапускать сервер.

### Проверки VLESS И AWG После Перезагрузки

Отдельная проверка после перезагрузки в тот же день использовала работающий
прокси olcRTC для SSH и положительного контроля VLESS. Это подключение и
работающий клиент OpenFlux пользователя не перезапускались. Приведённые выше
результаты через OpenFlux относятся к другому интервалу проверки.

- Для обоих узлов VPN Windows выбрала активный физический интерфейс и его
  шлюз по умолчанию, а не оставшийся маршрут TUN. Проверенные включённые
  блокирующие правила исходящего Windows Firewall не совпали с назначением
  для разрешённого диагностического процесса. Фактический выход пакетов через
  физический интерфейс не записывался; другая фильтрация на клиенте не исключена.
- Два прямых TCP-подключения к входящему VLESS завершились тайм-аутом через
  6 секунд каждое. В соответствующих фазах сервер не наблюдал SYN, SYN-ACK
  или RST. Прямые проверки SSH и HTTP также завершились тайм-аутом без
  соответствующих SYN. Захват HTTPS только по номеру порта содержал посторонний
  трафик и не даёт определённого вывода. Захват длился 38,204 секунды без потерь
  в ядре; полезная нагрузка и pcap не сохранялись.
- Контроль TCP через действующий прокси прошёл за 0,102 секунды. Затем точное
  ядро Xray из сборки получило HTTP 200 от Instagram и Wikipedia со строгой
  проверкой TLS за 1,045 и 0,702 секунды соответственно. Выход этого прокси
  также находится на том же сервере: это проверка прикладного пути, не внешнего
  входящего подключения.
- Оба контейнера AWG и их интерфейсы работали. Выбранный клиентский peer,
  открытый ключ сервера, декодированный PSK, публикация порта и проверенные
  параметры обфускации совпали. Чтение действующего I1 не поддерживалось;
  это не доказанное несовпадение. После перезагрузки сервера ни один peer
  не завершил рукопожатие.
- Одна изолированная попытка использовала точное ядро AWG из сборки, SHA256
  `640eba8c0c69b7d14fcc96e05a8acd84af2be8d42db1049d77733a89ff6aa786`.
  Журнал содержит четыре инициирования рукопожатия и ни одного ответа. Обе
  HTTPS-проверки завершились ошибкой TLS примерно через 10 секунд, хотя
  аутентификация локального SOCKS и ответы CONNECT прошли. Локальная готовность
  не означает успешную удалённую передачу данных.
- Во время согласованного наблюдения AWG входящие и исходящие UDP-счётчики
  физического интерфейса были нулевыми. Рукопожатие и счётчики передачи
  выбранного peer остались нулевыми; соответствующие счётчики DNAT/ACCEPT
  не выросли. Потерь захвата в ядре не было. Полезная нагрузка и pcap
  не сохранялись.
- Независимая проверка наблюдателя AWG не обнаружила определённой ошибки фильтра
  или размера снимка. Захват охватывал IPv4-интерфейс Ethernet-типа маршрута
  по умолчанию, а не все интерфейсы. Первый наблюдатель не сохранял коды выхода
  дочерних процессов и все категории предупреждений, поэтому одни нулевые
  счётчики не исключают любой сбой захвата. Записи об инициировании рукопожатия
  также не доказывают фактический выход пакета через интерфейс ноутбука.
- Один заключительный контроль длился 10,19 секунды и использовал точные
  назначенные серверу адреса. Windows приняла для отправки по две небольшие
  несекретные UDP-датаграммы на порт AWG и свободный UDP 443; TCP 443 завершился
  тайм-аутом через 5,009 секунды. Все шесть наблюдателей портов оставались
  готовыми и завершились штатно, с нулевыми счётчиками, потерями, ошибками
  разбора и неизвестными строками stderr. Обычный UDP-запрос DNS прошёл как
  с Windows, так и с сервера. Однако захват увидел ответ DNS, но не исходящий
  запрос; по одной неклассифицированной строке stderr каждого DNS-наблюдателя
  были отброшены. Полный двунаправленный положительный контроль захвата
  поэтому не прошёл. Эти результаты не доказывают конкретную внешнюю
  блокировку; порт и правила firewall не менялись.
- После дополнительного сообщения пользователя проверка без изменений
  подтвердила работу MTProto, публикацию TCP-порта и соответствующие слушатели
  на хосте и в контейнере. Одно прямое подключение к этому порту завершилось
  тайм-аутом через 6,022 секунды; CONNECT через прокси с выходом на том же
  сервере вернул HTTP 200 за 0,111 секунды. Это только проверка TCP-слушателя:
  секрет и прикладные данные MTProto не передавались, успешный сеанс Telegram
  не заявляется. Упомянутый веб-прокси пока не идентифицирован; работа Caddy
  не доказывает, что он является именно этим прокси.
- Более поздняя проверка только на чтение в 18:16 UTC обнаружила работающие
  `tproxy-server.service`, `mtproxy.service` и Caddy. WEB-реле слушает loopback
  отдельно от ранее проверенного контейнера MTProto. Это подтверждает наличие
  WEB-компонента на сервере, но не его связь с браузерным подключением
  пользователя и не успешную WEB-аутентификацию. Конфигурации и журналы служб
  не читались, настройки не менялись.
- Собственные тестовое ядро и наблюдатели остановлены, тестовый слушатель
  закрыт. Настройки прокси, маршрутов и DNS ноутбука не менялись, действующие
  защищаемые процессы сохранены. Эти проверки не меняли код продукта,
  конфигурацию сервера, учётные данные, firewall, службы или контейнеры.

Прямые VLESS и AWG остаются неисправленными. Конкретный оператор, транзитная
сеть или компонент хостинга, теряющий пакеты, не установлен. Успех нового теста
полного приложения, Android или TUN не заявляется. Подготовлено приватное
обращение в хостинг на английском и русском с интервалами проверок и запросом
независимой внешней проверки без перезагрузки или сброса сервера.
