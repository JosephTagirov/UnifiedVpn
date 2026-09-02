# Unified VPN engines

This project uses one Android `VpnService` and switches the active transport by profile type.

## Implemented paths

- `olcrtc://` and existing Olcbox JSON profiles use the original olcRTC mobile engine plus `hev-socks5-tunnel`.
- `vless://` profiles are imported and use `sing-box` for its supported transports or Xray for XHTTP/SplitHTTP.
- Amnezia WireGuard-style profiles are imported and can be started through the same `sing-box` + `tun2socks` path.

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
