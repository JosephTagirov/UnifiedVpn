# Unified VPN engines

This project uses one Android `VpnService` and switches the active transport by profile type.

## Implemented paths

- `olcrtc://` and existing Olcbox JSON profiles use the original olcRTC mobile engine plus `hev-socks5-tunnel`.
- `vless://` profiles are imported and can be started through the `sing-box` adapter.
- Amnezia WireGuard-style profiles are imported and can be started through the same `sing-box` + `tun2socks` path.

## VLESS core packaging

Preferred Android packaging:

```text
androidApp/src/main/jniLibs/arm64-v8a/libsing-box.so
androidApp/src/main/jniLibs/armeabi-v7a/libsing-box.so
androidApp/src/main/jniLibs/x86_64/libsing-box.so
```

The adapter executes `libsing-box.so run -c <generated-config>`, opens a local SOCKS inbound, and routes the system TUN through the existing `tun2socks` bridge.

The VLESS adapter supports upstream `sing-box` V2Ray transports: `tcp`/`raw`, `ws`, `grpc`, `http`/`h2`, `httpupgrade`, and `quic`. Xray XHTTP links (`type=xhttp`/`type=splithttp`) require an XHTTP-capable `sing-box` fork or another core; stock upstream `sing-box` rejects them as an unknown transport type.

Debug fallback packaging is also supported:

```text
androidApp/src/main/assets/bin/arm64-v8a/sing-box
androidApp/src/main/assets/bin/armeabi-v7a/sing-box
androidApp/src/main/assets/bin/x86_64/sing-box
```

Native-library packaging is preferred on modern Android because executing files copied from writable app storage can be blocked.

## Amnezia

The app imports AmneziaWG `.conf`, `awg://`, and Amnezia `vpn://` profiles and stores them as selectable profiles.

Standard self-hosted Amnezia profiles that contain a regular WireGuard config run through the packaged `sing-box` executable. The adapter creates a local SOCKS inbound and routes the Android `VpnService` TUN through the existing `tun2socks` bridge.

When an imported AmneziaWG config includes enabled obfuscation fields (`Jc`, `Jmin`, `Jmax`, `S1`-`S4`, `H1`-`H4`, `I1`-`I5`), the generated `wireguard` endpoint preserves those fields for an AWG-capable `sing-box`-compatible core. Stock upstream `sing-box` supports regular WireGuard configs; true AmneziaWG obfuscation still requires an AWG-capable binary or a native AmneziaWG backend.
