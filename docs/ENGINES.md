# Unified VPN engines

This project uses one Android `VpnService` and switches the active transport by profile type.

## Implemented paths

- `olcrtc://` and existing Olcbox JSON profiles use the original olcRTC mobile engine plus `hev-socks5-tunnel`.
- `vless://` profiles are imported and use `sing-box` for its supported transports or Xray for XHTTP/SplitHTTP.
- Amnezia WireGuard-style profiles are imported and can be started through the same `sing-box` + `tun2socks` path.

## Experimental OpenFlux

The 0.0.13 preview integrates [OpenFlux](https://github.com/p1neappleXpress/OpenFlux)
at `4f1bdb554c262f3ae9adbfe317a092c6b929ba7d` through Yandex Documents.
Use `+ -> Add OpenFlux` for manual document URL/key entry, or import URI/JSON.
The legacy editor and a matching `unified-openflux-aesgcm-v1` server are required.
Only one active client per document/key/server instance is supported.

AES-256-GCM is mandatory, and readiness requires a fresh authenticated server
response. There is no plaintext fallback. This is not a security-audit or forward
secrecy claim. The transport carries TCP/IPv4, not general UDP or IPv6; destination
DNS uses encrypted TCP, while the Yandex transport needs the underlying network.

Android routes OpenFlux through its existing VPN bridge and **shares the olcRTC
per-app split-tunneling rules**. The original lists are preserved; VLESS/AWG rules
remain separate. These per-app rules apply in Android VPN mode, not proxy mode.
Windows provides Local SOCKS and System proxy; Windows TUN remains unavailable.
Linux OpenFlux output is a server binary, not a tested desktop client integration.

Windows encrypted SOCKS/HTTPS and Android 35 emulator TUN/HTTPS passed on
2026-09-15, including process-liveness and shutdown checks. Earlier Android
failures were traced to missing VPN preparation in the debug test entry point.
See the [current test report](testing/openflux-2026091401.md), including limits.
Native Ready or compilation alone is not proof that traffic works.
[Build instructions, corresponding source, and limits](../tools/openflux/README.md).

### OpenFlux На Русском

Предварительная 0.0.13 включает OpenFlux указанного выше коммита через Яндекс
Документы. `+ -> Добавить OpenFlux` открывает ручной ввод ссылки и ключа; есть
импорт URI/JSON. Нужны старый редактор и сервер с нашей обёрткой
`unified-openflux-aesgcm-v1`. На документ/ключ/экземпляр разрешён один активный клиент.

AES-256-GCM обязателен, готовность требует свежего аутентифицированного ответа
сервера; передачи без шифрования нет. Это не подтверждение аудита безопасности
или прямой секретности. Поддерживается TCP/IPv4, но не произвольный UDP и IPv6.
DNS назначения идёт внутри зашифрованного TCP-туннеля, самому Яндексу нужна
исходная сеть.

На Android OpenFlux использует существующий VPN-мост и **общие с olcRTC правила
раздельного туннелирования приложений**. Прежние списки сохраняются, VLESS/AWG
остаются отдельной группой. Эти правила действуют в VPN-режиме, не в прокси.
Windows поддерживает Local SOCKS и системный прокси; TUN остаётся недоступным.
Linux-бинарник OpenFlux предназначен для сервера, не подтверждает desktop-интеграцию.

2026-09-15 Windows прошёл зашифрованный SOCKS/HTTPS, а Android 35 на эмуляторе
прошёл TUN/HTTPS, включая контроль процессов и остановку. Прежние ошибки Android
были связаны с пропущенной подготовкой VPN в тестовом входе. См.
[текущий отчёт](testing/openflux-2026091401.md) и его ограничения.
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

## Amnezia

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
