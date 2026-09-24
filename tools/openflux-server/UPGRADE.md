# Staged Legacy-To-L4 Upgrade

The foreground `upgrade.py` helper described below has no independent recovery.
For the separately tested systemd/browser path, see
[Managed Browser Lifecycle](BROWSER_LIFECYCLE.md) and the
[runtime validation report](../../docs/testing/openflux-runtime-2026092405.md).
The two controllers must not manage the same trial simultaneously.

The helper does not use SSH, install dependencies, issue host
firewall/DNS/route commands, create a bridge, or change unrelated services. Docker
may still update its own endpoint bookkeeping/rules when attaching containers;
reusing a bridge is not a zero-impact guarantee. Keep the
provider console available. Do not run a trial while an affected OpenFlux
profile is in use, and do not interrupt the user's working Jitsi/VPN path.

The target is wrapper 4 at upstream
`d34dc8caa70ca059cd80d8f5753499361052dabc`, with L4 TCP forwarding, bounded
native buffers, mandatory directional AES-GCM, authenticated peer control and
legacy LZ4. The server JSON schema and exact document/key bytes remain unchanged.
No batching, key rotation, profile re-export, or automatic protocol fallback is
part of this upgrade.

## Preservation And Scope

`upgrade.py` requires an explicit `--instance`, including `--instance default`.
It accepts only root-owned legacy installations at the original fixed `/opt`
paths, pinned to `4f1bdb554c262f3ae9adbfe317a092c6b929ba7d` and wrapper 1, 2 or 3.
Original source, binary, provenance, helper, private configuration and state are
hashed. Container ID, image ID, ownership labels, private bind mount, bridge ID,
subnet and attached endpoints must match. Names alone never authorize mutation.
All CLI mutations hold a nonblocking lock for the **original instance**, even
when using different receipt directories. Locks are root-owned mode-0600 regular
files in `/run/unifiedvpn-openflux-upgrade-locks` (mode 0700), with symlinks and
hard links refused. Read-only `check` does not create or acquire this lock.

Preparation creates a new random browser-prefixed **package and image only**.
Its server JSON is byte-identical to the original. A private mode-0600 receipt
in a new mode-0700 directory records the original running state, restart policy,
hashes and exact IDs. Existing files, images, containers and bridge are retained.
The image build uses only public files, an already-present immutable runtime,
`--pull=false` and `--network=none`. No Yandex request occurs during preparation.

New L4 containers drop all capabilities, publish no ports, use a read-only root,
no-new-privileges, the existing private config mount and the existing owned
bridge. There is no iptables command or raw-socket capability. The old raw-mode
container retains its original image and startup behavior for rollback.

Ordinary new `manage.py run` refuses a staged upgrade. Keep the old installed
manager with the old package, but do not invoke either manager's `stop` or
`remove` during an upgrade: those commands remove resources, while this helper
retains the original container and shared bridge for exact-ID rollback.

## Prepare And Inspect

Place the reviewed new `upgrade.py` and matching `manage.py`, Dockerfile and
entrypoint together in a root-owned helper directory. The existing SSH upload
controller deliberately does not adopt or overwrite an old installation;
transfer of this separate upgrade helper/package needs separate authorization.

```sh
sudo python3 upgrade.py prepare --instance phone2 \
  --receipt-directory /root/openflux-upgrade-phone2-REVIEWED_RUN --apply \
  --binary /root/new-openflux/openflux-linux-amd64 \
  --binary-sha256 REVIEWED_BINARY_SHA256 \
  --source-archive /root/new-openflux/openflux-source.tar.gz \
  --source-sha256 REVIEWED_SOURCE_SHA256 \
  --licenses-dir /root/new-openflux/licenses \
  --runtime-image sha256:REVIEWED_LOCAL_RUNTIME_ID

sudo python3 upgrade.py check --instance phone2 \
  --receipt-directory /root/openflux-upgrade-phone2-REVIEWED_RUN
```

`check` only inspects local files and Docker metadata. It does not execute the
binary or claim transport readiness. Any original file/identity change requires
review, not overwriting the receipt or blindly retrying a mutation.

The first separately authorized network check can stop after HTTPS validation:

```sh
sudo python3 upgrade.py check-document --instance phone2 \
  --receipt-directory /root/openflux-upgrade-phone2-REVIEWED_RUN --apply
```

This creates, runs and removes only a temporary owned document-check container.
It cannot stop/update the original or start the replacement exit process.
Success returns the receipt to `prepared`; `switch` rechecks document access
fresh instead of treating this earlier result as permanent permission.

## Document Gate And Traffic Trial

Before stopping the original, `switch` starts one separately owned, bounded
document-check container using the new binary's `--check-document` mode. That
mode performs HTTPS document validation only; it does not join the WebSocket
room or send tunnel traffic. Exit code zero and matching stopped-container
state are required. The probe is removed by its verified ID.

**If JavaScript verification is required, this foreground gate fails before
stopping or changing the original.** No checkbox overrides the result. The
standalone browser supervisor cannot be attached to a staged upgrade. The
separate `browser_lifecycle.py` controller provides a receipt-bound integration;
host-reboot behavior still needs a real maintenance-window test.

Only after document validation does the helper create the stopped candidate
on the original bridge, save the switch phase, set the old restart policy to
`no`, stop the exact old container and verify that it is stopped. It then starts
the replacement, preventing two exit processes from sharing one document/key.

```sh
sudo python3 upgrade.py switch --instance phone2 \
  --receipt-directory /root/openflux-upgrade-phone2-REVIEWED_RUN \
  --trial-seconds 180 --apply
```

The foreground trial is bounded to at most 180 seconds while the Python
controller remains alive. Ten seconds of stable process state are checked, but
uptime is not proof. A trusted test coordinator must run real client tests and
atomically write `traffic-proof.json` in the private receipt directory, mode
0600/root-owned, only after successful authenticated peer, tunneled HTTPS and
tunneled DNS checks. Never fabricate that file from container status.

The exact proof schema has these fields: `schema` equal to
`unifiedvpn-openflux-upgrade-traffic-v1`; `upgrade_id` and `container_id` matching
the private receipt; `protocol` equal to `unified-openflux-aesgcm-v1`;
`authenticated_peer: true`; integer `https_successes` from 1 through 100;
`dns_over_tunnel: true`; and `tested_at_ns` inside the recorded trial window.
No document URL, key, hostname, response body or other private traffic belongs
in that proof. Pre-existing, stale, mismatched or malformed proof is refused.

After proof and another health check, the candidate inherits the saved restart
policy. If the original was stopped before preparation, the successfully tested
candidate is also stopped instead of silently enabling a previously idle
profile. Existing legacy clients keep their original profiles.

## Rollback And Limits

Timeouts, ordinary exceptions, startup failure, Ctrl-C, SIGTERM and SIGHUP attempt
owned rollback: disable/stop the candidate, verify it is stopped, then restore
the original restart policy and prior running state. The new package and stopped
candidate remain for inspection; neither old nor new images are deleted.

```sh
sudo python3 upgrade.py rollback --instance phone2 \
  --receipt-directory /root/openflux-upgrade-phone2-REVIEWED_RUN --apply
```

**SIGKILL, controller loss, host crash or reboot cannot guarantee automatic
rollback.** The old and new restart policies can both be `no` during the trial.
An independently supervised, exact-ID rollback watchdog must be reviewed and
tested before a production switch; this helper does not install one. A working
console and an SSH keepalive alone do not cover host failure. Preparation and
read-only inspection remain usable without that watchdog.

Interrupted phases cannot repeat `switch`. Inspect the private receipt and both
exact container IDs first; use explicit rollback only when their ownership and
original files still match. An interrupted create without a recorded ID is not
adopted by name. Filesystem failures attempt restoration using already-verified
IDs, but the receipt can still require manual filesystem recovery. Replaced
resources or changed original private files fail closed rather than starting an
unverified container. Recheck other VPN services and actual original-profile
traffic after rollback; process state alone is not recovery proof.
