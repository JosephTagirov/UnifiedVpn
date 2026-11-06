# Adaptive VPN cascade

This Linux IPv4 component selects an extra encrypted hop for new VPN connections according to the resources and HTTPS reachability of two existing nodes. It uses a private WireGuard link between foreign servers. Existing connections retain their conntrack decision. After an exit failure, newly created connections leave through the entry server. Applications must reconnect interrupted connections.

The controller scopes traffic to named VPN service cgroups and the two managed AWG containers. Telegram bots, SSH, the web control service and the default host route retain their existing routing. RTC signaling/media UDP and VPN handshake source ports retain their ingress bindings. The implementation requires cgroup v2, xt_cgroup, ipset, iptables, WireGuard, iproute2 and Docker inspection access.

Install `unifiedvpn-cascade-modules.conf` under `/etc/modules-load.d` so `nf_conntrack` loads before systemd applies the node's configured connection-tracking limit. The controller initializes its classifier and readiness set before its first health decision, including when it starts during the first minute after boot.

## Two-node installation

Place the four Python modules together in `/opt/unifiedvpn-cascade`. Generate one WireGuard private key on each host. Keep `/etc/unifiedvpn-cascade/wireguard.conf` root-owned with mode 0600. Exchange public keys over pinned SSH. Configure an interface listen port and one peer. The primary peer's AllowedIPs is `0.0.0.0/0`, the exit peer's AllowedIPs is the primary private IPv4 `/32`. Route installation is performed by link.py, so wg-quick automatic route replacement must be disabled.

Each node needs a root-owned `/etc/unifiedvpn-cascade/node.json` with `role` (`primary` or `exit`), `public`, `peerPublic`, `private`, `peerPrivate`, `port` and `wan`. Use a dedicated unused `/30`, UDP port and routing priorities. Check for route, mark and firewall conflicts before deployment. Forwarding must already be enabled. The current implementation reserves interface `uvpn-hop`, table 51889, priorities 15189/15190 and the upper-byte marks 0x51/0x52.

Run `python3 link.py up` on each node. Run health.py on the exit and controller.py on the primary. The accompanying unit templates preserve kernel leases, restart the workers after failures and constrain their CPU and memory. The health endpoint binds to the private authenticated interface. The UDP link accepts only the configured peer public address.

The primary polls every 15 seconds and enables the extra hop after three healthy samples. Its ipset lease expires after 45 seconds without refresh. Memory, disk and CPU reserves protect the existing services. Only the extra interserver interface is capped at 120, 72 or 32 Mbit/s. These values are configured caps, not measured throughput guarantees. A runtime test deadline in `/run/unifiedvpn-cascade/health-test-until` forces conservative direct mode until that Unix timestamp.

Connection tracking occupancy at or above 80% also disables selection of new cascaded flows. Check actual table capacity on both hosts against available memory and expected traffic. The deployment reserves 131072 entries on the smaller node and 262144 on the larger node. Increasing a limit does not flush existing sessions. The HTB class uses an explicit 15140-byte quantum to avoid oversized scheduler bursts and repeated kernel warnings.

New connections to Telegram's published IPv4 networks use the entry's direct exit. This applies to traffic inside the VPN and to the managed Telegram proxies. Existing marked connections keep their original path until they close. The network list is sourced from https://core.telegram.org/resources/cidr.txt and must be reviewed when Telegram changes it. Exit-local probe ages avoid dependence on synchronized wall clocks. The controller's runtime directory is preserved across worker restarts so a maintenance deadline remains effective.

New Xray connections use direct entry egress as a conservative latency policy. Three unstable legacy RAW/REALITY profiles were moved to the existing HTTPS/WebSocket inbound, preserving their UUIDs and subscription IDs. The exact cause of the old transport's stalled slow transfers remains unresolved. A Splice environment test and a temporary PROXY-header relay did not resolve it and were removed. AWG and the other selected VPN workers retain the extra hop. Existing Xray flows keep their original marks until closed. Subscription clients need to refresh a migrated profile before reconnecting. The primary Xray core was updated to official v26.10.31 with its release asset digest verified.

Inspect `/var/lib/unifiedvpn-cascade/status.json` locally as root. `python3 link.py disable` disables selection of new cascaded flows while retaining the link for established ones. Stop the controller first when maintaining these rules. Removing routes or the interface also interrupts existing cascaded connections. Back up the configuration, keys, sources and units privately.

Run the policy regression checks with `python3 -m unittest discover -s . -p 'test_*.py'`.

## Owner metrics integration

The integrations directory contains the collector and React view used by the existing owner-only control panel. The collector exposes an allowlist of operational fields and marks stale controller data. Connect it through the control panel's authenticated owner API. The view expects the existing control-api module. These sources describe that integration and accompany the installed panel update.

## Prepared two-by-two expansion

The adjacent topology template describes two future Russian ingress nodes, each also providing Russian egress, and two European exits. The selected RU ingress makes a destination-specific decision for each new client connection. Russian services use that RU node's direct internet connection. Foreign services use one selected European exit over the authenticated cross-border transport. Two domestic paths and four RU-to-European paths are represented in the template. A second RU node and second European node provide alternative healthy paths.

```text
Client -> RU-A or RU-B -> Russian resources, with a Russian source IP
                     -> EU-A or EU-B -> Foreign resources, with a European source IP
```

The `futureDestinationRouting` section is a disabled design specification. The running two-foreign-node controller does not interpret or activate it. The two existing nodes cannot provide a Russian egress address. One RU ingress and one European exit are used by a foreign connection. A domestic connection ends at its selected RU egress.

Classification starts with owner domain overrides, then explicit foreign-service exceptions, Russian-service domains, Russian IP ranges and the foreign default. The template lists Google, YouTube and Telegram as foreign exceptions ahead of the broader Russian domain category. Review service/CDN dependencies and the actual contents of the chosen geosite and geoip assets before activation. A domain suffix alone cannot classify every service correctly. Unknown destinations use the European pool. If both European exits are unavailable, foreign connections remain unavailable until a European route recovers. Existing flows keep their selected path.

DNS resolution must follow the destination policy. Resolve Russian services through the RU side and foreign services through the selected European side, with explicit routing for the resolver connections. Account for managed client DNS, encrypted application DNS and shared CDN addresses. Xray can use domain/IP routing rules for VLESS. AWG carries IP packets and needs a transparent TCP/UDP gateway plus DNS/domain association at the RU ingress. Decoded RTC/OpenFlux client traffic needs the same policy. IPv6 requires equivalent classification and egress handling before enabling it.

The RU nodes remain disabled until actual addresses, pinned SSH identities, certificates and authenticated transport credentials are supplied. Their cross-border transport must be tested on the relevant home/mobile networks. The foreign-only WireGuard link does not establish suitability of WireGuard across a Russian network border. For the RU-to-foreign segment the template calls for authenticated TLS over TCP443 and a verified existing VLESS transport.

Provision one RU ingress first. Verify Russian and foreign source IPs for representative services, DNS/CDN behavior, AWG TCP/UDP and the IPv6 policy. Measure HTTPS payload success, median/p95 RTT and loss to both foreign exits, and then enable the second RU ingress on a different provider/ASN. Prefer the best healthy path and switch only new connections. Authenticated direct foreign profiles remain a separate selectable option. Client-side automatic ingress selection, account/quota synchronization and publication of new subscription endpoints require implementation and testing when those hosts exist. The present controller implements the current two-node cascade.

Routing and DNS implementation references are the [Xray routing documentation](https://xtls.github.io/en/config/routing.html), [Xray DNS documentation](https://xtls.github.io/en/config/dns.html) and the upstream [Russian service category](https://github.com/v2fly/domain-list-community/blob/master/data/category-ru). Xray rules are evaluated in order. An implementation using IP fallback before an explicit default rule needs a suitable resolution strategy, such as `IPOnDemand`.
