# Fixed DIRECT authority replication

Each configured identity has one fixed source node, device ID, key hash, listener port and attested runtime resource. Configure the receiver separately. The coordinator transfers permission metadata only. Credential provisioning is a reviewed bootstrap operation.

The source database supplies enabled state, protocol membership, deletion and deadline. Local lifecycle timers enforce source permissions as well as receiver permissions. This covers a source cabinet running read-only. A finite source deadline cannot become an unlimited receiver grant.

Snapshots use a fixed epoch and monotonically increasing revisions. Binding, content hash and generation checks reject a different authority, regression and changed content at an existing revision. Permission intent is persisted before a lifecycle transition. Both endpoints must attest and enforce the same generation before publishing their paired acknowledgement.

Unknown source state produces no fresh acknowledgement. Cached receiver permission cannot start an inactive service. A newly discovered active profile holds grants and requires explicit registry review. Known revocations remain actionable. The fixed registry provides no automatic provisioning of future profiles.

Both nodes persist a boot identity, monotonic anchor and effective clock high-water mark. During the same boot, elapsed monotonic time advances the effective clock even when the wall clock rolls back. Expired generations receive durable local tombstones. Replaying that generation after clock rollback or reboot cannot restore access. Renewal requires a newer source revision with a future finite deadline. During a source partition, the receiver enforces its last known finite deadline locally. An unlimited grant remains cached until a verified revocation arrives. Partition-time revocation is consequently delayed.

Install an instance-specific privileged `ExecStartPre` call to the worker's `gate` action. A source startup checks its current database permission and deadline. A receiver startup requires a short-lived, single-use ticket from a fresh coordinator application for that exact generation. Direct receiver boot autostart consequently holds until the coordinator verifies its source. Startup gates preserve known expiry tombstones. An installation may pin the explicitly verified current boot in `initial_boot_id`. A different boot, lost clock state or regressed monotonic anchor requires `timedatectl` to report synchronized time before finite grants can run. Finite permissions fail closed while this trust is unknown. Absolute downtime is not inferred from an unsynchronized clock.

The observer pins the service definition, persistent and effective configuration, key hash, process binary, configuration argument and listener ownership. Inspection failure holds the operation. Verify real scheduled invocations under the final sandbox, including access to DynamicUser process credentials. The root observer requires narrowly bounded process-inspection capabilities. The data-plane service remains a separate non-root process without capabilities or TUN access.

Use the lifecycle timer on both nodes and the coordinator timer only on the configured writer. Store configuration and worker state in root-owned directories with modes 0700 and 0600. Keep root authority state outside service-writable DynamicUser directories. Back up source databases consistently, configuration, root state, unit definitions, attested executable and any resolved private state directory used by the data service.

The current DIRECT core serves one peer synchronously per listener. Continuous authentication probes on owner listeners can contend with an owner connection. DIRECT permission replication therefore operates outside the continuous authenticated failover cohort. Use bounded idle-slot checks for reviewed diagnostics. Dedicated isolated QA instrumentation requires a separate deployment review.

`openflux_direct_endpoint.py` supplies a narrow export integration. Call `rewrite(session, device_id)` after constructing a session. A protected registry matches machine, device, key hash and original port, and changes only the single DIRECT Dial endpoint. Context, credentials and other transports retain their existing export behavior.

The same helper supplies `reserved_ports()` for the existing allocator. Add the returned set to its used ports before selecting a free listener. A root-private machine-bound reservation file keeps replica ports reserved while a replica is stopped or revoked, including when its source device has no local database row.

## Integration

The cabinet callsites are intentionally narrow.

```python
import openflux_direct_endpoint

# Immediately after constructing the existing session dictionary.
session = openflux_direct_endpoint.rewrite(session, device["id"])

# In the existing allocator, after reading database-owned ports.
used.update(openflux_direct_endpoint.reserved_ports())
```

Use an instance-specific service override with its fixed configured origin.

```ini
[Service]
ExecStartPre=+/usr/bin/python3 /opt/unifiedvpn-failover/openflux_authority.py gate --config /etc/unifiedvpn-openflux-ha/authority.json --origin configured-origin
```

The privileged prefix lets the startup gate read protected authority state even when the data service uses DynamicUser. Keep the gate executable, configuration and state root-owned. Pin the complete service override set in each resource specification. The gate checks permission and performs no network authentication.

## Recovery

Retain permission generations, expiry tombstones and clock state during code rollback. Restore a matching reviewed worker and pinned resource configuration, then require a fresh paired acknowledgement. An older permission snapshot must never replace a newer revocation. Keep service startup gates installed until the authority path is healthy.

For a replica-only rollback, stop and disable that exact owned replica service, retain its protected recovery files and leave the original source process intact. Reverting an endpoint export hook restores export behavior without changing an existing client key or server configuration. Validate the original source, receiver ownership and consistent private backups before enabling the coordinator again.
