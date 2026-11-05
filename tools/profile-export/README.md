# Stable server endpoints

These modules integrate with the existing UnifiedVPN control service. Exports retain client credentials, peer ports, AWG parameters and VLESS TLS identities while using a stable connection hostname.

Set `UNIFIEDVPN_PUBLIC_HOST` in the control service environment to the DNS name of the primary server. The default is `panel.meesharr.space`.

Imported AWG configurations can belong to another server. `UNIFIEDVPN_AWG_HOSTS` optionally maps the original server public key to that server's DNS name. For a systemd environment file, use the following form with the actual public key and hostname.

```ini
UNIFIEDVPN_PUBLIC_HOST=vpn.example.net
UNIFIEDVPN_AWG_HOSTS='{"original-server-public-key":"legacy.example.net"}'
```

The public key identifies the server even when its numeric IP and local container name change. Exporting a saved configuration preserves its original file. The export replaces only the Endpoint hostname. The mapping is deployment configuration and is kept outside source control.

## Moving a server

Preserve the VPN server keys, client accounts, ports, TLS certificates and subscription identifiers. Update the corresponding A record and any relevant AAAA record to the new server address. Update the private inter-node link and its IP allowlists separately when the node address changes. Confirm that both authoritative DNS servers return the intended records.

Users with an old numeric endpoint must refresh their subscription or reimport the current configuration once. Afterwards the same DNS-based profile can reach the server at its new IP after DNS caches expire and the client reconnects. Active WireGuard/AWG peers may retain the resolved numeric endpoint until reconnection. Client-specific recovery and DNS caching determine the actual interruption duration.

Ingress failover and automatic DNS publication require separate coordination with synchronized accounts and the DNS provider. These exporter modules provide stable profile addressing.

## Tests

Run with Python 3 and `cryptography` installed in an isolated environment.

```sh
python -m unittest -v test_existing test_endpoint_portability
```

The tests cover existing profile operations, read-only saved AWG export, IPv4 and IPv6 source endpoints, unchanged peer ports and credentials, legacy server selection by public key, and managed VLESS/OpenFlux hostnames.
