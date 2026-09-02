# CottenDNS feasibility report for Unified VPN 0.0.11+

Date: 2026-08-30

Status: diagnostic only. No CottenDNS implementation is included in 0.0.10.

## Executive summary

Adding CottenDNS to Unified VPN is feasible. The Android application already has
a reusable external-engine boundary: VLESS and AmneziaWG start a native process,
wait for its local SOCKS5 listener, and pass that listener to the common Android
TUN/tun2socks layer. CottenDNS exposes the same kind of local SOCKS5 endpoint, so
the VPN routing layer does not need to be rewritten.

The change is still medium-to-large because a reliable release needs more than
starting one process. It needs a new profile type, safe configuration handling,
Android and Windows packaging, resolver scan progress and cancellation, profile
sharing, redacted logs, lifecycle and hot-switch handling, and real-server tests.

Recommended delivery:

- 0.0.11: manual CottenDNS profiles on Android and Windows, including resolver
  scanning, start/stop, split tunneling, sharing, diagnostics, and tests.
- 0.0.12: optional self-hosted setup assistant and DNS delegation validation.
- 0.0.13: automatic transport selection/failover only after field data proves
  that CottenDNS health can be measured without causing connection loops.

## Upstream assessment

Reviewed upstream repository: <https://github.com/WhiteDNS/CottenDns>

Local diagnostic checkout:

- Current reviewed checkout: `0da2f9ea360d5aaa0473f32982c5488156d6fa26`.
- A release must pin a reviewed full commit SHA, never `main`.
- CottenDNS engine license: MIT. Its engine can be integrated with attribution.
- The separate WhiteDNS Android application's code and UI must not be copied or
  repackaged unless its own license explicitly permits that. This proposal uses
  only the CottenDNS engine contract.

Relevant upstream contract:

- Android output is an executable named `libcottendns_client.so`, not an AAR,
  JNI API, or gomobile library.
- It is built for `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `x86` and launched
  as a child process.
- Default local proxy is SOCKS5 on `127.0.0.1:18000`; Unified VPN should allocate
  a free port rather than assume the default is available.
- `--config`, `--resolvers`, `--scan-only`, and `--version` are available.
- Machine-readable progress is emitted as `WD_PROGRESS`, `WD_RESOLVERS`, and
  `WD_SCAN`.
- `FAST_CONNECT` can release startup after a safe resolver pool is available.
- The Android linker flags include 16 KiB page compatibility.
- Current `go.mod` requires Go 1.25 or newer.
- The upstream Android script expects NDK `29.0.14206865` and defaults to Android
  API 26.

Infrastructure is required outside the client application: a delegated DNS
subdomain, a CottenDNS server on a VPS, and reachable DNS transport (normally
UDP/TCP port 53, with optional DoT/DoH modes). A client feature cannot create
that infrastructure by itself.

## Fit with the current application

Android already provides the useful integration points:

- `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/SocksBackedVpnEngine.kt`
  defines the common external SOCKS engine contract and implementations for
  VLESS and Amnezia/AmneziaWG.
- `sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OlcboxVpnService.kt`
  owns generation-safe start/stop, TUN creation, tun2socks, network rebinding,
  split tunneling, and hot switching.
- The application package is excluded from the Android VPN route where needed,
  reducing the risk that the CottenDNS process tunnels its own DNS packets back
  into itself.
- `sharedUI/src/jvmAndAndroidMain/kotlin/org/olcbox/app/vpn/VpnProfileReachability.kt`
  is the existing profile reachability boundary.

Shared profile and UI work is required in:

- `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/model/LocationConfig.kt`
- `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/datasource/LocationsDatasource.kt`
- `sharedUI/src/commonMain/kotlin/org/olcbox/app/data/share/FriendAccessPackage.kt`
- `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/features/locations/LocationViewModel.kt`
- `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/features/locations/LocationSettingsScreen.kt`
- `sharedUI/src/commonMain/kotlin/org/olcbox/app/ui/features/locations/components/LocationRow.kt`

Windows work is required in:

- `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/DesktopVpnManager.kt`
- `sharedUI/src/jvmMain/kotlin/org/olcbox/app/vpn/desktop/DesktopNativeAssets.kt`
- a new desktop CottenDNS config/process adapter alongside the current Xray,
  sing-box, and olcRTC adapters
- `desktopApp/build.gradle.kts` for native binary and package verification

Build metadata and documentation work is required in:

- `androidApp/build.gradle.kts`
- `desktopApp/build.gradle.kts`
- `gradle.properties`
- `docs/ENGINES.md`
- `CHANGELOG.md` only when implementation is actually accepted for release

## Proposed 0.0.11 scope

The first implementation should expose a small safe subset of the very large
upstream TOML schema instead of reproducing all upstream settings in the UI:

- profile name
- server domain/delegated zone
- authentication/encryption key or secret
- resolver source/list and scan mode
- transport mode (`auto`, UDP, TCP, and advanced DoT/DoH only when validated)
- local SOCKS port set to automatic by default
- fast-connect toggle
- advanced raw TOML import/export behind an explicit advanced section

Required behavior:

1. Validate and normalize the profile without logging secrets.
2. Allocate an unused local port and write a private temporary config.
3. Start the pinned CottenDNS binary with stdin closed.
4. Parse machine progress, support cancel, and wait for both a live process and
   a ready authenticated SOCKS endpoint.
5. Start the existing Android TUN or selected Windows connection mode.
6. Stop and delete temporary material on cancel, failure, profile switch, app
   shutdown, and service destruction.
7. Keep CottenDNS split-tunneling settings independent from olcRTC and from the
   existing VLESS/AWG group if the UI is intended to expose three policies.
8. Support explicit encrypted/secret-bearing profile sharing, with warnings and
   no automatic upload or public publication.

The client currently waits for external engines to expose SOCKS. CottenDNS may
wait for Enter after some startup errors, so Unified VPN must close child stdin,
enforce a bounded timeout, and destroy the process tree on cancellation.

## Build commands

Android upstream engine build, preferably in Linux CI or WSL with a pinned SHA:

```bash
git clone https://github.com/WhiteDNS/CottenDns.git .engine/CottenDns
git -C .engine/CottenDns checkout <reviewed-full-commit-sha>
cd .engine/CottenDns
NDK_ROOT="$ANDROID_HOME/ndk/29.0.14206865" \
  OUTPUT_DIR="$GITHUB_WORKSPACE/androidApp/src/main/jniLibs" \
  bash scripts/build-android-client.sh all
```

For a native Windows-hosted Android build, the script also needs
`NDK_HOST=windows-x86_64` and a Bash environment. The output must be checked with
`file`/`readelf`, `--version`, ABI inventory, SHA metadata, and an Android launch
smoke test before Gradle packaging.

Unified VPN verification after implementation:

```powershell
.\gradlew.bat :sharedUI:jvmTest :androidApp:assembleDebug --no-configuration-cache
.\gradlew.bat :desktopApp:packageReleaseDistributionForCurrentOS --no-configuration-cache
```

The existing project-specific release verification and native-asset smoke tests
must remain mandatory. No GitHub release should run until the resulting local
Windows and Android packages pass manual connection checks.

## Compatibility and release risks

### Android API level

Unified VPN currently supports Android API 23, but the upstream CottenDNS script
defaults to API 26. Before release, one of these must be proven:

- CottenDNS builds and runs correctly with `ANDROID_API=23`; or
- CottenDNS profiles are feature-gated to Android 8.0/API 26+; or
- the whole application's minimum Android version is raised, which is not
  recommended merely to add one transport.

### Toolchain

This workstation currently has Go `1.26.4` (sufficient) but only Android NDK
`28.2.13676358`. NDK `29.0.14206865` must be installed for a reproducible build,
or a different NDK must be deliberately qualified and recorded.

### Artifact size

Four additional Go/CGO binaries will materially grow the universal APK. The
current 0.0.10 universal debug APK is already about 203 MB. Per-ABI APKs (at
least arm64-v8a for normal phone testing) and/or release stripping are likely
required; GitHub's per-file release limit must be checked before publication.

### Network and lifecycle

- Resolver discovery can take time and must have visible progress and cancel.
- UDP/53 may be blocked or modified; TCP fallback and resolver loss need tests.
- Incorrect VPN bypass rules can create a DNS routing loop.
- A listening SOCKS port alone is not sufficient proof of an operational DNS
  tunnel; readiness must include an authenticated external request.
- Process, config, and port cleanup must survive repeated start/stop and hot
  switches between every pair of olcRTC, VLESS, AWG, and CottenDNS.

### Security

- Never commit, upload, print, or attach real server domains, credentials, keys,
  private resolver lists, or generated friend bundles.
- Redact both structured fields and raw upstream process output.
- Store temporary configs in private application storage with restrictive file
  permissions and delete them after stop/failure.
- Sharing must always be an explicit user action. Update checks and diagnostics
  must never transmit profiles.
- Prefer authenticated encryption modes supported and recommended by upstream.

## Verification matrix

Engine and unit tests:

- pin SHA and verify recorded build metadata
- `go test ./...` in the exact upstream checkout
- build and inspect every shipped Android ABI plus Windows amd64
- parse/validate TOML, redaction, bundle import/export, malformed input, occupied
  port allocation, timeout, cancellation, and process-tree cleanup

Android tests:

- x86_64 emulator plus a real arm64 phone
- Android API 26 and current Android; API 23 only if claimed supported
- exact Connected state followed by external TCP, DNS, and SOCKS5 UDP checks
- all split modes and confirmation that the engine traffic bypasses its own TUN
- repeated start/stop, process death, network change, sleep/wake, resolver loss
- hot switch both directions with olcRTC, VLESS, and AWG; application PID and
  Android crash buffer must remain clean
- profile editor scrolling, keyboard resize, reorder affordance, import of a
  large bundle, progress/cancel, and secret visibility controls

Windows tests:

- Local SOCKS, System Proxy, and Windows TUN modes
- automatic free-port selection and authenticated external SOCKS traffic
- admin elevation/relaunch, Close exits, repeated start/stop, process cleanup
- EXE installer and portable ZIP JVM/native-asset/version smoke tests
- hot switching with olcRTC, VLESS, and AWG in both directions

Real infrastructure gate:

- use a disposable private CottenDNS server and delegated test domain matching
  the pinned client commit
- wait for exact Connected/readiness; do not accept only a spawned process or an
  open local port
- test multiple resolver paths, delayed first success, UDP failure/TCP fallback,
  server restart, invalid key, and unreachable server
- inspect all exported logs and bundles for leaked secrets before release

## Estimated work and token budget

These are implementation/review model-token estimates, not build-log size. The
uncertainty is approximately 30-40% until a real server and profile are tested.

| Workstream | Estimated tokens |
| --- | ---: |
| Pin/build engine, provenance, Gradle packaging | 3k-5k |
| Profile model, import/export, editor, redaction | 5k-8k |
| Android process/config/lifecycle/progress integration | 8k-14k |
| Windows process/config/native packaging integration | 7k-12k |
| Automated and real-network test matrix, fixes | 8k-15k |
| Documentation, attribution, changelog, release audit | 2k-4k |
| **Robust 0.0.11 manual-profile total** | **35k-58k** |

Additional scope:

- self-hosted server setup wizard, DNS delegation checks, and guided validation:
  approximately 15k-25k tokens
- mature automatic failover, health policy, telemetry-free diagnostics, and field
  hardening: approximately 20k-35k tokens
- complete 0.0.11+ program with both additions: approximately 70k-110k tokens

In engineering effort, the manual-profile MVP is roughly 5-8 focused working
days when test infrastructure is ready. A hardened Android+Windows release with
field testing is more realistically 2-3 weeks. Network behavior, not the basic
SOCKS adapter, is the largest uncertainty.

## Recommendation

Proceed only after obtaining disposable private CottenDNS test infrastructure.
Implement the manual-profile transport first, pin one reviewed upstream commit,
and keep provisioning and automatic failover out of 0.0.11. Release only after
the same local packages have passed real Connected plus external-traffic tests
on both Windows and Android and the user has manually approved them.
