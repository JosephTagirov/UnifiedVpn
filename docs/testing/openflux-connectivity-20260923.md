# OpenFlux connectivity investigation, 2026-09-23

Status: failure reproduced; service not restored. No release or server configuration change.

## Finding

At 18:59 UTC, both configured production document URLs returned a Yandex
browser-verification page instead of the legacy editor bootstrap. This happened
on direct HTTPS requests from the Windows laptop and from the VPS. The response
had HTTP 200 after two redirects but contained a verification screen requiring
JavaScript, not `client-config` / `officeActionData` / editor connection fields.

The URLs read from the two server configurations exactly matched the corresponding
locally saved profiles. Changing the request User-Agent to a normal desktop
browser string did not restore the editor bootstrap in the local checks.

This is a demonstrated obstacle to new OpenFlux sessions, not evidence that the
home ISP alone blocks the transport. It does not establish whether Yandex is
applying this verification globally, per request, per address, or per document.
It is also not evidence that the documents were deleted or their keys changed.

## Server Observation

- Both production containers were running with `unless-stopped` restart policy.
- Neither showed an OOM termination or a restart since its 17:00 UTC start.
- Established sockets were present, but this alone does not prove peer readiness.
- The separate release-test container remained stopped and was not used.
- Persistent Docker logging is intentionally disabled by the deployment helper;
  unavailable `docker logs` output is not evidence of an empty error log.
- All SSH observations used the pinned server host key.

## Authorized Connection Test

The user disconnected OpenFlux on both devices and authorized one isolated test.
The test used the saved primary profile and the previously packaged Windows
native core, SHA-256:

`06f5466247771145ab1e9e9d7ddd391d376e81382270d506d1ede618e04c2076`

Between 18:54:32 and 18:55:49 UTC, the process reported AES-256-GCM initialization
but exited with a fatal error before `OPENFLUX_READY`. The configured handshake
deadline was 75 seconds. The narrow error classifier did not identify the exact
fatal message, so this receipt must not be presented as a confirmed handshake
error string. No application HTTPS traffic test could run without readiness.

This was a native-core / local-SOCKS diagnostic, not an Android test or a complete
DesktopVpnManager integration pass. Android failure is user-reported in this turn.

The owned test process stopped, its SOCKS listener closed, and its runtime config
was removed. Before/after snapshots confirmed unchanged system proxy, routes,
DNS settings, and protected proxy/SOCKS listener ownership. The working Jitsi
connection was not restarted or used as a competing client.

## Follow-up

1. Check whether the document opens in the user's normal browser after Yandex's
   verification. A browser success does not automatically transfer verification
   to the native client or to the VPS.
2. Investigate a supported bootstrap/session approach before changing the
   transport. Do not disable TLS verification, encryption, or expose browser
   account cookies in a profile or log.
3. Preserve the existing documents, keys, and running services while investigating.
   A blind container restart will not add JavaScript support to the native core.
4. After any actual fix, require encrypted peer readiness and external HTTPS
   traffic on Windows and Android before declaring recovery or publishing.

Only local diagnostic helpers and this report were added. Private receipts are
kept under the ignored, access-restricted `.downloads/private-test-profiles/`
directory. No document URLs, credentials, tokens, or server addresses are included
in this report. Nothing was uploaded to GitHub.

## Follow-up: Browser Session Feasibility, 19:16 UTC

The user confirmed that the document opens in normal Chrome after completing
Yandex's browser verification. A fresh HTTP request without browser state still
received the verification page afterward. Retaining only cookies collected by
an ordinary HTTP client across two requests did not recover the editor bootstrap.

An isolated, anonymous Chrome context then obtained the legacy editor parameters
without account login. It did not read the user's existing browser profile.
All WebSocket attempts were intercepted locally, so the experiment did not join
an OpenFlux transport session. The browser was closed at the end of every run.

The final experiment compared two requests using a small standalone Go `net/http`
diagnostic, with the same URL and ordinary `Mozilla/5.0` header:

| Request | Result |
| --- | --- |
| No cookies | HTTP 200, editor bootstrap absent |
| Temporary cookies from the fresh anonymous browser | HTTP 200, legacy editor fields present, editing permission enabled |

TLS verification remained enabled. Anonymous session cookies were passed to the
diagnostic process through stdin and were not saved in profiles, files, logs, or
receipts. Authentication-cookie guards were enabled. The diagnostic did not
receive the OpenFlux encryption key. No personal browser cookies were read.

The final browser/HTTP experiment took about eight seconds. This is not a VPN
connection-time measurement. Ten destination-filter fixtures and three offline
Go input/account-cookie guards passed. Before/after network snapshots again
confirmed unchanged proxy, routes, DNS, and protected listener ownership.

This establishes a feasible local bootstrap approach, not a repaired release:

- The Go diagnostic is not the packaged OpenFlux executable.
- Encrypted peer handshake, WebSocket authentication, and external tunneled HTTPS
  remain untested with browser-derived sessions.
- Android integration and an isolated server-side browser helper are not yet built.
- Cookie expiration, reconnect behavior, network changes, and repeated verification
  still need testing. Cookies from the laptop must not be assumed usable on the VPS.

Implementation should use a dedicated anonymous session on each participating
device or server, keep verification data outside shared profile exports, and
preserve TLS and mandatory transport encryption. Session loading must be
cancelable and report verification failures instead of a generic peer timeout.
Do not copy the user's main browser session to the server or request a Yandex
account password as a shortcut.

The application, native artifacts, production server configuration, documents,
and encryption keys were not modified in this follow-up. No release was published.
