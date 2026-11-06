# Native subscription TLS export compatibility

The native subscription generator can omit SNI, fingerprint and ALPN when its
WebSocket listener receives plain HTTP behind a TLS frontdoor. In 3x-ui 2.8.11,
TLS fields are collected before `externalProxy.forceTls` changes the exported
security value. [Upstream generator](https://github.com/MHSanaei/3x-ui/blob/v2.8.11/sub/subService.go).

This adapter forwards a native subscription request to its existing loopback
service. For VLESS WebSocket/TLS links on an explicit hostname allowlist and
port443, it appends only missing `sni`, `fp=chrome` and `alpn=http/1.1` options.
Existing query bytes and identity fields remain intact. XHTTP and other
transports pass through. Native generators may independently randomize valid
Reality short IDs, server names and spider paths between separate requests;
that variation is separate from this transformation.

Plain and Base64 subscriptions are supported, including unpadded Base64.
Unknown formats and compressed upstream responses pass through unchanged.
Quota, expiry, update interval and device/HWID headers are forwarded. Changed
representations discard upstream validators and use `private, no-store`.

The adapter binds IPv4 loopback only, serves `/sub/<capability>` and a local
health endpoint, and keeps capability paths and headers out of logs. Four
workers, a 1MiB upstream limit, a twelve-second upstream deadline and a
twenty-second connection deadline bound resource use. The completed output
can be larger than its input because TLS options are appended. The supplied
unit uses a dynamic unprivileged user and permits only loopback IP access.

## Deploy

Copy the Python module to `/opt/unifiedvpn-subscription-compat`. Put a
root-controlled, world-readable configuration without secrets under
`/etc/unifiedvpn-subscription-compat/config.json`, based on the example. Install
the unit, run the regressions, enable the adapter and verify its loopback health
endpoint before changing the frontdoor's existing `/sub` upstream from the
native service to the adapter. No Xray restart or identity edit is required.

```sh
python3 -m unittest discover -s . -p 'test_*.py'
systemctl enable --now unifiedvpn-subscription-compat.service
curl --fail http://127.0.0.1:2097/health
```

Retain a before-copy of the frontdoor configuration. Rollback points `/sub`
directly at the original native service. Validate and gracefully reload the
frontdoor after either change. Keep the original subscription capability and
ask clients to refresh it and reconnect. This does not diagnose a client's
operator blocking, DNS cache or local application state.
