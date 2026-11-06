# VPN ingress failover

This worker changes an explicit allowlist of DNS A records after verified data-plane failure. It retains the selected ingress while it remains healthy. Identity synchronization is a separate prerequisite. The worker never promotes a control database writer.

## Decision modes

`regional` requires explicit fresh failures from at least two Russian probe ASNs for three independent rounds. The target needs two successful regional rounds, a matching applied identity manifest and authenticated checks for every configured protocol. Pending, missing, malformed and API-error responses are unknown. A positive source observation prevents a regional failure vote.

`regional_or_peer_dataplane` additionally allows the off-DNS publisher to select itself after three rounds of both source TCP443 and SSH22 being unreachable. A refused connection proves that the host responded and blocks this network-failure criterion. The publisher needs authenticated HTTPS reachability to two independently configured internet providers, a successful registrar read matching both authoritative nameservers, and all configured local data-plane checks. A fresh positive regional source observation blocks this criterion.

The peer criterion observes two protocols from one network vantage. An isolated inter-node routing failure can resemble source loss. This is an explicitly accepted availability trade-off for passive data planes. Keep the control writer fixed. Each deployed publisher may only write a failover to its own node. Both must use the same managed names and source identity manifests. There is no atomic distributed DNS lock.

A separate service-failure criterion accepts a fresh protected producer receipt confirming a loaded required service inactive, a required loopback listener explicitly refusing connections, or a required Docker container stopped. The corresponding authenticated protocol probe must also fail. Receipts bind to the current identity-manifest digest and each receipt counts once. A generic exception or failed third-party dependency provides no confirmed service-failure receipt.

All modes require three failure rounds, a healthy target, a 15-minute minimum dwell after a verified switch and an authoritative/registrar state comparison before publication. Recovery alone does not trigger failback. The verification deployment retained a 3,600-second positive DNS TTL and used a 300-second negative TTL. Cached positive answers can retain the previous IP for an hour. Client endpoint caches also determine when reconnecting clients discover the changed address.

## Probe APIs

Check-Host requests use a literal public IP and public TCP port. Capability URLs and credentials stay outside its API. `/nodes/hosts` supplies location and ASN metadata. Explicit repeated `node` parameters select Russian nodes. Extended results bind observations to request identity, target IP, port and creation time. Requests are paced and node discovery is cached. Current Russian data-center probes do not establish reachability through every user operator. [Official Check-Host API](https://check-host.net/about/api)

REG.API v2 requests use HTTPS POST with form fields `input_format=json`, `output_format=json` and `input_data` containing JSON. Credentials stay in that body. Redirects are rejected. Every nested domain result must succeed and match the configured zone. The ordinary customer methods are `zone/get_resource_records`, `zone/add_alias` and `zone/remove_record`. `remove_record` always includes the exact former A-record content. `zone/update_records` is reserved for partners. The official authenticated-browser specification was reviewed for these field shapes. [Official API reference](https://www.reg.ru/reseller/api2doc)

REG.API requires an explicit source-IP allowlist and limits requests to 1,200 per hour for both account and IP. This client spaces calls by at least four seconds. Invalid credentials and rate-limit errors hold publication. [Official API restrictions](https://help.reg.ru/support/partneram/reg-api/kakiye-ogranicheniya-yest-pri-rabote-s-reg-api)

## Runtime paths

Install the runtime Python modules in `/opt/unifiedvpn-failover`. They use Python 3.10 or newer. Direct authoritative queries also require `dig`. Telegram probes reuse the existing protected `telegram_proxy.py` and `mtproto_faketls.py` helpers and their installed cryptography dependency.

Keep the following configuration under `/etc/unifiedvpn-failover`, owned by root with mode0600.

- `config.json` holds monitoring policy, public addresses, managed DNS names and runtime paths. Begin from `config.example.json` and keep `enabled` false through dry-run verification.
- `registrar.private.json` contains only the API `username` and dedicated `password`. Deploy no account credentials in source or artifacts.
- `readiness.private.json` holds desired scope versions, per-node pinned SSH access metadata and designated health-client fixtures. Keep private client keys and subscription capabilities here.
- `scopes.private.json` holds local read-only scope verifiers and the local service checks associated with protected protocols.

Create `/var/lib/unifiedvpn-failover` as root0700. Runtime status, readiness and identity manifests use atomic owner-only files. Each worker has its own nonblocking lock. Pending DNS intent is durable before the first write. DNS additions are verified before exact former values are removed. A lost response resumes by rereading actual state. Foreign record values and uncovered AAAA/CNAME routes stop reconciliation.

`health_file` receives a credential-free observation summary after each live controller round, including dry runs. `failover_status.py` combines that evidence with actual configuration and enabled, active timers. The owner API and checkpoint integration distinguish policy activation from `healthyBackupAvailable` and `regionalCoverageDegraded`. A locally working backup with failed Russian probes is ineligible for regional failover. Stale observations never establish backup health. Set `control_writer` explicitly and retain the single-writer guard independently of ingress selection.

## Scope and readiness contracts

The desired global manifest has integer `epoch`, monotonic integer `revision` and a SHA256 over the expected per-scope map. Reusing a version for a different digest or moving backward stops the producer. Each scope contains its own epoch, revision and digest.

`scope_verify.py` rechecks actual local state on each run. VLESS scopes preserve explicit primary client IDs, current inbounds and complete identity metadata while ignoring old-only identities and local usage counters. The source authority includes all current identities so a newly added account changes its digest immediately. AWG scopes include interface identity, AWG2/3.1 parameters, PSKs and allowed addresses. The persisted and runtime AWG identities must match. File scopes support logical file labels, with an optional `canonical_json` list for semantic JSON comparison. Scope output contains hashes and status only.

Native and VLESS synchronization workers publish monotonic per-scope manifests with a real `synchronized_at` acknowledgment after source and replica comparison. Runtime self-checks do not refresh that acknowledgment. Stable Telegram file scopes refresh their acknowledgment only after both protected node observations match. Remote measurements carry a peer-local age anchor, which the pinned SSH reader conservatively converts to local age. Small clock offsets cannot turn a fresh peer observation into an invalid future timestamp.

The readiness producer connects to literal ingress addresses while retaining certificate hostname/SNI verification. Each publisher tests its own ingress and reads the other node's protected fresh receipts. This avoids simultaneous monitors moving a shared AWG health peer between source endpoints. VLESS and AWG checks launch isolated loopback SOCKS clients and require HTTPS plus Telegram nonce replies. MTG and WEB require replies from all five Telegram DCs. The public-download check requests the first four bytes of an explicitly verified user-edition artifact and requires a 206 response with the ZIP signature. The subscription check validates an existing identity. No test sends Telegram messages.

Use dedicated AWG health peers. Reusing a person's active peer can move its endpoint. Native identities require their own approved health-client keys. The producer leaves missing and unverified protocols unhealthy. RTC room identities need a separate per-node profile design. OpenFlux probes must preserve authenticated session context when changing the dial address.

## Verification and activation

Run offline regressions before deployment.

```powershell
python -m unittest discover -s . -p 'test_*.py'
```

Run protected local scope verification and readiness production first. Confirm that every configured node and protocol has fresh actual evidence.

```sh
python3 /opt/unifiedvpn-failover/scope_verify.py --config /etc/unifiedvpn-failover/scopes.private.json
python3 /opt/unifiedvpn-failover/readiness.py --config /etc/unifiedvpn-failover/readiness.private.json --manifest-output /var/lib/unifiedvpn-failover/identity-manifest.json --output /var/lib/unifiedvpn-failover/readiness.json
python3 /opt/unifiedvpn-failover/controller.py --config /etc/unifiedvpn-failover/config.json --state /var/lib/unifiedvpn-failover/status.json
```

The final command collects live evidence and persists decisions without registrar mutations. Offline `--fixture` mode marks its state as simulated. A simulated state file cannot later become a live state file. Use a separate state path for fixtures.

After reviewed live evidence, set `enabled` true and enable the supplied readiness and failover timers. The failover service additionally passes `--apply`. These are two independent write gates. Keep a before-copy of the managed DNS records and service/configuration files.

Test failure conditions with offline fixtures and scoped data-plane maintenance first. A whole-host outage drill needs a maintenance window and access recovery. Disabling the failover timer stops new DNS publication. Keep identity replicas and existing data planes intact while investigating an incident. Restore a prior DNS target only after its identities and authenticated probes pass again.

## Protocol coverage

List deployed coverage from the actual `protocols` array and passing readiness output. A shared DNS name cannot repair different AWG server keys. Native replicas retain each source server identity and require a one-time import of stable identity-specific DNS endpoints. Historical numeric imports retain their numeric endpoint until refreshed. Existing WEB sessions are host-local and reconnect as fresh sessions after ingress changes. TCP/UDP application state is not transferred between nodes.

## Private checkpoints

The application checkpoint must retain `/etc`, `/opt` and `/var/lib` state for both failover and native synchronization, the existing pinned cluster SSH configuration, dynamic desired manifests and restricted readiness access material. The primary gateway configuration includes its management relay. The standby configuration includes its separate frontdoor binary/configuration and Caddy writer fence. `backup_owned.py` captures those standby paths, both consistent SQLite databases, native container definitions and current server configuration. It also captures the renamed legacy WireGuard configuration and its exact image. Other native image payloads are retained in the primary's protected native cache and main application archive. The bounded standby configuration snapshot is nested inside the private application checkpoint. These archives contain credentials and stay root-private.
