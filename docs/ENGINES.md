# Unified VPN engines

This project uses one Android `VpnService` and switches the active transport by profile type.

## Implemented paths

- `olcrtc://` and existing Olcbox JSON profiles use the original olcRTC mobile engine plus `hev-socks5-tunnel`.
- `vless://` profiles are imported and use `sing-box` for its supported transports or Xray for XHTTP/SplitHTTP.
- Amnezia WireGuard-style profiles are imported and can be started through the same `sing-box` + `tun2socks` path.

## Experimental OpenFlux

The local 0.0.14 / 2026092406 candidate integrates [OpenFlux](https://github.com/p1neappleXpress/OpenFlux)
at `d34dc8caa70ca059cd80d8f5753499361052dabc` through Yandex Documents.
It preserves legacy encrypted framing and selects the L4 server backend.
Wrapper 5 is not published on GitHub. A separate Volga instance passed real traffic
checks and was enabled without replacing the existing OpenFlux instances; see the
[current validation report](testing/openflux-volga-2026092406.md).
Use `+ -> Add OpenFlux` for manual document URL/key entry, or import URI/JSON.
Select `yandex` for the legacy editor or `vyandex` for the new Volga text editor.
Both peers need the same transport and a matching `unified-openflux-aesgcm-v1`
wrapper; Volga requires wrapper 5. Existing profiles are not automatically migrated.
Only one active client per document/key/server instance is supported.

AES-256-GCM is mandatory, and readiness requires a fresh authenticated server
response. There is no plaintext fallback. This is not a security-audit or forward
secrecy claim. The transport carries TCP/IPv4, not general UDP or IPv6; destination
DNS uses encrypted TCP, while the Yandex transport needs the underlying network.

Android routes OpenFlux through its existing VPN bridge and **shares the olcRTC
per-app split-tunneling rules**. The original lists are preserved; VLESS/AWG rules
remain separate. These per-app rules apply in Android VPN mode, not proxy mode.
On Windows OpenFlux shares Local SOCKS/System proxy settings with olcRTC; TUN is
excluded for both. The experimental VLESS/AWG TUN helper is described in the
[Windows routing report](testing/windows-tun-2026092102.md).
Linux OpenFlux output is a server binary, not a tested desktop client integration.

Windows encrypted SOCKS/HTTPS and Android 35 emulator TUN/HTTPS passed on
2026-09-15, including process-liveness and shutdown checks. Earlier Android
failures were traced to missing VPN preparation in the debug test entry point.
These earlier-build results do not validate the new candidate. The separate
[Volga report](testing/openflux-volga-2026092406.md) records the exact new artifacts.
See the
[historical test report](testing/openflux-2026091401.md), including limits.
Native Ready or compilation alone is not proof that traffic works.
[Build instructions, corresponding source, and limits](../tools/openflux/README.md).

### OpenFlux На Русском

Локальный кандидат 0.0.14 / 2026092406 включает OpenFlux указанного выше коммита
через Яндекс Документы, сохраняя прежний зашифрованный формат и выбирая L4 на сервере.
Обёртка 5 не опубликована на GitHub. Отдельный экземпляр Volga прошёл проверку
трафика и включён без замены существующих OpenFlux; см.
[текущий отчёт](testing/openflux-volga-2026092406.md).
`+ -> Добавить OpenFlux` открывает ручной ввод ссылки и ключа; есть
импорт URI/JSON. Выберите `yandex` для старого редактора или `vyandex` для нового
текстового Volga. Клиенту и серверу нужны одинаковый транспорт и совместимая обёртка
`unified-openflux-aesgcm-v1`; Volga требует обёртку 5. Старые профили автоматически
не меняются. На документ/ключ/экземпляр разрешён один активный клиент.

AES-256-GCM обязателен, готовность требует свежего аутентифицированного ответа
сервера; передачи без шифрования нет. Это не подтверждение аудита безопасности
или прямой секретности. Поддерживается TCP/IPv4, но не произвольный UDP и IPv6.
DNS назначения идёт внутри зашифрованного TCP-туннеля, самому Яндексу нужна
исходная сеть.

На Android OpenFlux использует существующий VPN-мост и **общие с olcRTC правила
раздельного туннелирования приложений**. Прежние списки сохраняются, VLESS/AWG
остаются отдельной группой. Эти правила действуют в VPN-режиме, не в прокси.
На Windows OpenFlux использует общую с olcRTC настройку Local SOCKS/системного
прокси; TUN исключён для обоих. Экспериментальный компонент TUN для VLESS/AWG
описан в [отчёте Windows](testing/windows-tun-2026092102.md).
Linux-бинарник OpenFlux предназначен для сервера, не подтверждает desktop-интеграцию.

2026-09-15 Windows прошёл зашифрованный SOCKS/HTTPS, а Android 35 на эмуляторе
прошёл TUN/HTTPS, включая контроль процессов и остановку. Прежние ошибки Android
были связаны с пропущенной подготовкой VPN в тестовом входе. См.
[исторический отчёт](testing/openflux-2026091401.md) и его ограничения.
Результаты прежней сборки не подтверждают работоспособность нового кандидата;
его точные артефакты и проверки указаны в [отчёте Volga](testing/openflux-volga-2026092406.md).
Одних Native Ready и компиляции недостаточно для подтверждения трафика.

## VLESS core packaging

Preferred Android packaging:

```text
androidApp/src/main/jniLibs/arm64-v8a/libsing-box.so
androidApp/src/main/jniLibs/armeabi-v7a/libsing-box.so
androidApp/src/main/jniLibs/x86_64/libsing-box.so
androidApp/src/main/jniLibs/arm64-v8a/libxray.so
androidApp/src/main/jniLibs/armeabi-v7a/libxray.so
androidApp/src/main/jniLibs/x86_64/libxray.so
```

The adapter executes `libsing-box.so run -c <generated-config>` for supported sing-box transports. XHTTP/SplitHTTP profiles execute `libxray.so run -config <generated-config>`. Both paths open a local SOCKS inbound and route the system TUN through the existing `tun2socks` bridge.

In Android TUN mode, olcRTC and VLESS add a managed local sing-box bridge between `tun2socks` and the profile's raw SOCKS endpoint. The bridge hijacks DNS traffic to port `53` and performs DNS-over-HTTPS to `8.8.8.8:443` with TLS server name `dns.google`, detoured through that same raw SOCKS endpoint. It does not use the physical network as a DNS fallback. Non-DNS traffic continues to the profile upstream. Proxy mode exposes the raw profile SOCKS endpoint and does not start this bridge.

AWG does not use the extra bridge: its sing-box WireGuard endpoint already carries DNS and general UDP through the tunnel, so `tun2socks` connects to the AWG SOCKS inbound directly.

The VLESS adapter supports `tcp`/`raw`, `ws`, `grpc`, `http`/`h2`, `httpupgrade`, and `quic` through sing-box. XHTTP links (`type=xhttp`/`type=splithttp`) use the pinned official Xray `26.3.27` core because the packaged sing-box AWG core does not implement XHTTP.

Debug fallback packaging is also supported:

```text
androidApp/src/main/assets/bin/arm64-v8a/sing-box
androidApp/src/main/assets/bin/armeabi-v7a/sing-box
androidApp/src/main/assets/bin/x86_64/sing-box
```

Native-library packaging is preferred on modern Android because executing files copied from writable app storage can be blocked.

On Windows, Xray is bundled as `native/xray-windows-amd64.exe`. The release build copies the official binary selected by `XRAY_BINARY`, or the verified local file under `.downloads/xray/v<version>/windows-64/xray.exe`.

### Windows TUN, Local Build 2026092102

Windows VLESS uses the pinned Xray core for `tcp`/`raw` (also the default),
`ws`/`websocket`, `grpc`, `httpupgrade`, `xhttp`/`splithttp`. Unsupported transports
and TCP HTTP-header obfuscation are rejected. Local SOCKS, System proxy and
experimental TUN use the same configuration builder. When TUN pins the server
IP, the original TLS/Reality SNI and HTTP/gRPC authority are preserved.

AWG's TUN-mode SOCKS inbound intercepts DNS on port 53 and uses the existing
profile DNS resolvers over TCP through the WireGuard endpoint. The virtual
adapter's DNS address is not used as a direct physical-network fallback.
This does not prove Windows-wide DNS isolation: physical adapters and their
settings are deliberately not overwritten, and more-specific LAN routes remain.

The GUI stays unprivileged; its network helper requests UAC. The helper also
handles an already elevated broker, without changing the GUI launch policy.
Windows olcRTC/OpenFlux TUN remains disabled: protecting one static server IP is
not sufficient for their dynamic transport sockets, and they need DNS bridging
over TCP. Android's existing VPN path is unchanged.
See [verification and remaining tests](testing/windows-tun-2026092102.md).

### Windows TUN На Русском

В локальной сборке `2026092102` Windows VLESS использует закреплённый Xray для
`tcp`/`raw` (также по умолчанию), `ws`/`websocket`, `grpc`, `httpupgrade`,
`xhttp`/`splithttp`. Неподдерживаемые транспорты и HTTP-маскировка заголовка TCP
отклоняются. Конфигурация общая для Local SOCKS, системного прокси и
экспериментального TUN. При закреплении IP сохраняются исходные TLS/Reality SNI
и HTTP/gRPC authority.

В TUN-режиме AWG входящий SOCKS перехватывает DNS на порту 53 и использует
резолверы профиля по TCP через WireGuard. DNS-адрес виртуального адаптера не
служит прямым резервным выходом в физическую сеть. Это не доказывает изоляцию
всего Windows DNS: физические адаптеры и их настройки намеренно не изменяются,
более точные маршруты локальных сетей сохраняются.

GUI остаётся без повышенных прав; сетевой компонент запрашивает UAC. Компонент
также поддерживает уже повышенный broker, но политика запуска GUI не менялась.
Windows TUN для olcRTC/OpenFlux пока отключён: одного статического IP недостаточно
для динамических транспортных сокетов, нужен также DNS-мост поверх TCP.
Существующий VPN-путь Android не менялся.
См. [проверки и оставшиеся тесты](testing/windows-tun-2026092102.md).

## Amnezia

### Native Refresh In Build 2026092103

Unified VPN 0.0.14 Preview packages the AWG-capable Throne sing-box commit
`7745e9afd0a1f7a5b6216fd114e1ad4a64b974c3` with AmneziaWG backend
`b311c8ac53aed5637b0bd961b9c6ae1fc1eef093`. Both are pinned, built and verified
for Windows amd64 and three Android ABIs. This is the `wip/1.14.0` development
branch, not a stable upstream release. Android and Windows adapters support
`RandomTrailers`, `DisableCookies` and validated keepalive ranges.

olcRTC is updated to `d7a00da5242f72a48b505ffc4b6aa8246376bf25`. The newest
olcbox core was tested first; the preceding compatible revision was selected
because the new Jitsi message envelope is not understood by the existing
`f616` server. OLC2 is unchanged. No production server/container image is
upgraded by this client refresh. See the
[upstream decision](testing/upstream-refresh-2026092103.md) and
[client checks](testing/release-0.0.14-2026092103.md).

### Обновление Ядер В Сборке 2026092103

Unified VPN 0.0.14 Preview включает AWG-совместимый Throne sing-box `7745e9a`
с реализацией AmneziaWG `b311c8ac`. Полные commit приведены выше; проверены
Windows amd64 и три Android ABI. Используется ветка разработки `wip/1.14.0`,
не стабильный релиз. Адаптеры обеих платформ поддерживают `RandomTrailers`,
`DisableCookies` и проверяемые диапазоны keepalive.

olcRTC обновлён до `d7a00da`: сначала проверено свежее ядро olcbox, но его новый
формат сообщений Jitsi не понимается прежним сервером `f616`. Поэтому выбран
предшествующий совместимый commit. Шифрование OLC2 не менялось. Рабочие серверы
и образы контейнеров это обновление клиента не заменяет. См.
[обоснование версий](testing/upstream-refresh-2026092103.md) и
[проверки клиентов](testing/release-0.0.14-2026092103.md).

### Profile Import

The app imports AmneziaWG `.conf`, `awg://`, and Amnezia `vpn://` profiles and stores them as selectable profiles.

Standard self-hosted Amnezia profiles that contain a regular WireGuard config run through the packaged `sing-box` executable. The adapter creates a local SOCKS inbound and routes the Android `VpnService` TUN through the existing `tun2socks` bridge.

When an imported AmneziaWG config includes enabled obfuscation fields (`Jc`, `Jmin`, `Jmax`, `S1`-`S4`, `H1`-`H4`, `I1`-`I5`), the generated `wireguard` endpoint preserves those fields for an AWG-capable `sing-box`-compatible core. Stock upstream `sing-box` supports regular WireGuard configs; true AmneziaWG obfuscation still requires an AWG-capable binary or a native AmneziaWG backend.

Self-hosted provisioning pulls only
`amneziavpn/amnezia-wg@sha256:ea050861bd2012a6265817636ce7c0c15764ef955782d953cef42e05c1381250`.
The runtime container drops all capabilities before adding only `NET_ADMIN` and
`SYS_MODULE`, uses `no-new-privileges`, and mounts `/lib/modules` read-only.
Changing that digest requires a separate source review and release test.

## Amnezia на русском

Приложение импортирует профили AmneziaWG `.conf`, `awg://` и Amnezia `vpn://`
как отдельные выбираемые профили. Обычный WireGuard запускается через
встроенный `sing-box`; настоящий AmneziaWG с полями обфускации требует
AWG-совместимый core.

Self-hosted настройка загружает только образ
`amneziavpn/amnezia-wg@sha256:ea050861bd2012a6265817636ce7c0c15764ef955782d953cef42e05c1381250`.
Контейнер сначала сбрасывает все capabilities, затем получает только
`NET_ADMIN` и `SYS_MODULE`, использует `no-new-privileges` и подключает
`/lib/modules` только для чтения. Смена digest требует отдельной проверки
исходников и release-теста.
