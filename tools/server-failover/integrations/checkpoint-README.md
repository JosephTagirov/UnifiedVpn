# Existing checkpoint integration

The deployed checkpoint job extends an existing pinned-SSH application backup. Its private node addresses, SSH host-key aliases and credential configuration belong in the owner's protected deployment inventory. The following hooks describe the generic changes without copying that inventory.

## Operational status

Make `failover_status.py` importable by the checkpoint job and refresh its returned fields immediately before writing checkpoint status.

```python
from failover_status import snapshot as failover_snapshot

state.update(failover_snapshot())
```

The same module powers the owner metrics API. It derives policy activation from the actual configuration and enabled, active timers. It exposes protocol coverage, fixed control-writer ownership, regional degradation and healthy-backup availability separately. Write checkpoint status with an owner-only temporary file, fsync and atomic replacement.

## Application configuration paths

Extend the existing archive allowlist with the applicable owned directories.

```python
paths += [
    'etc/unifiedvpn-cluster',
    'etc/unifiedvpn-failover', 'opt/unifiedvpn-failover',
    'var/lib/unifiedvpn-failover',
    'etc/unifiedvpn-native-ha', 'opt/unifiedvpn-native-ha',
    'var/lib/unifiedvpn-native-ha',
    'etc/unifiedvpn-legacy-ha', 'var/lib/unifiedvpn-legacy-ha',
    'etc/unifiedvpn-classic-ha', 'var/lib/unifiedvpn-classic-ha',
    'etc/unifiedvpn-management-relay',
    'etc/unifiedvpn-frontdoor', 'opt/unifiedvpn-frontdoor',
    'etc/unifiedvpn-subscription-compat',
    'opt/unifiedvpn-subscription-compat',
    'etc/nginx', 'etc/caddy',
    'etc/chrony', 'etc/default/chrony', 'var/lib/chrony',
    'lib/systemd/system/chrony.service',
    'etc/systemd/system/chrony.service.d',
    'etc/systemd/system/chronyd.service',
    'etc/systemd/system/multi-user.target.wants/chrony.service',
    'srv/unifiedvpn-ha-source',
    'var/lib/unifiedvpn-cluster/standby-owned.current.tar.gz',
]
```

Retain owned systemd service/timer definitions and drop-ins, consistent control/x-ui database copies, native container definitions, source configurations and dedicated private image caches. Capture each installed replica's Docker inspection alongside its host-side state. A DynamicUser StateDirectory can point into `/var/lib/private`. Include the exact resolved owned origin directory in the private allowlist to preserve its cookie/state payload.

Finite authorization deadlines require synchronized time. Preserve chrony's source configuration, startup options, service definitions and frequency-drift state in the private archive. Restore the installed distribution package and review clock offset before enabling it. A deployment configured for slew-only correction must retain its absence of clock-step directives and startup flags. Verify actual source selection, convergence and synchronization after restoration.

## Standby configuration archive

Run the same `backup_owned.py --stdout` over the checkpoint job's existing strict, pinned SSH connection. Keep the rescue-OS guard. Bound compressed transfer to 128 MiB, decompressed content to 512 MiB and archive membership to 10,000 entries. The helper checks both consistent SQLite databases and emits `consistent/manifest.json` with scope `standby-owned-ha-v1`.

The receiver verifies gzip CRC, both required SQLite members and their manifest integrity results before rotating `standby-owned.current.tar.gz` to `standby-owned.previous.tar.gz`. Failed or interrupted transfers retain the previous verified archive and surface pending-backup status. Archive rotation uses a separate new file and atomic replacement. The main application checkpoint then includes the verified standby archive and replicates its own completed archive over the same pinned connection.

Treat the resulting archive as private credential material. Restore the required OS/runtime packages and excluded image payloads from their recorded provenance, then verify source/replica identities and actual authenticated traffic before enabling publication.
