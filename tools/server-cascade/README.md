# Adaptive VPN cascade

This Linux IPv4 component selects an extra encrypted hop for new VPN connections according to the resources and HTTPS reachability of two existing nodes. It uses a private WireGuard link between foreign servers. Existing connections retain their conntrack decision. After an exit failure, newly created connections leave through the entry server. Applications must reconnect interrupted connections.

The controller scopes traffic to named VPN service cgroups and the two managed AWG containers. Telegram bots, SSH, the web control service and the default host route retain their existing routing. RTC signaling/media UDP and VPN handshake source ports retain their ingress bindings. The implementation requires cgroup v2, xt_cgroup, ipset, iptables, WireGuard, iproute2 and Docker inspection access.

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

## Prepared two-by-two expansion

The adjacent topology template describes two future Russian ingress nodes and two foreign exits, including direct foreign access. A connection uses one chosen ingress and one chosen exit. The four possible RU-to-foreign paths are alternatives. A second RU node adds provider and address redundancy.

The RU nodes remain disabled until actual addresses, pinned SSH identities, certificates and authenticated transport credentials are supplied. Their cross-border transport must be tested on the relevant home/mobile networks. The foreign-only WireGuard link does not establish suitability of WireGuard across a Russian network border. For the RU-to-foreign segment the template calls for authenticated TLS over TCP443 and a verified existing VLESS transport.

Provision one RU ingress first, measure HTTPS payload success, median/p95 RTT and loss to both foreign exits, and then enable the second RU ingress on a different provider/ASN. Prefer the best healthy path and switch only new connections. Keep authenticated direct foreign profiles as a separate selectable option. Client-side automatic ingress selection, account/quota synchronization and publication of new subscription endpoints require explicit implementation and testing when those hosts exist. The present controller implements the current two-node cascade.
