# Server maintenance and security helpers

These opt-in Linux tools support the existing two-node deployment. Review ports, paths, certificate names and service ownership before installing. They do not restart Docker, change the default route or configure ingress failover.

- `firewall.py`: idempotent INPUT rules for IPv4 and IPv6. Management ports remain reachable through loopback SSH tunnels and local reverse proxies. Existing UFW and FORWARD rules remain separate; this is not protection for arbitrary Docker-published ports. Only an IPv4 inter-node WireGuard link is configured, so its IPv6 UDP listener is blocked.
- `standby_tls.py`: validates root-owned checkpoints, certificate hostnames, CA chains, expiry and matching private keys; publishes immutable revisions through atomic symlinks. Panel and Telegram certificates remain separate. Caddy reload is verified against the actual local TLS leaf certificate; failed changes restore the previous revision. Only a changed panel pair requests an x-ui restart.
- `backup_bots.py`: consistent SQLite snapshots of the three bot databases, with integrity checks, protected configuration files and current/previous rotation. `--stdout` emits only the gzip archive, suitable for a pinned SSH transfer. Archives contain credentials and must never enter the web download directory or Git.

Install scripts as root-owned files under `/opt/unifiedvpn-security`. Keep the TLS JSON configuration mode 0600. The Caddy service must use its file-based configuration without `--resume` or API configuration changes. SIGUSR1 reload requires Caddy 2.11 or later. Add `ExecReload=/bin/kill -USR1 $MAINPID` only after checking those conditions. Keep the Caddy admin API disabled if it is not otherwise needed.

The hourly TLS timer reads an already replicated checkpoint. The primary checkpoint job pulls bot snapshots every six hours through its existing pinned SSH identity. Its transfer limits are 128 MiB compressed, 512 MiB expanded and 5,000 tar members; it verifies the three database entries and integrity manifest, keeps the previous copy on failure, and reports the failed job without skipping VPN backups. No private node addresses or SSH credentials are distributed here.

Use a systemd sshd jail (`maxretry=6`, `findtime=600`, `bantime=3600`) after validating the journal backend. Add only verified node addresses to the allowlist. Do not disable password access until a separate pinned key login and recovery path have been tested.

A bot polling wrapper must translate socket timeouts, TLS/OS errors, HTTP protocol errors and invalid JSON into its existing retryable exception while retaining update offsets. Diagnostic calls should use `getMe` and `getWebhookInfo`; do not consume updates or send messages during maintenance checks.

Verification: `python3 -m unittest -v test_standby_tls`. Live deployment checks also cover external management-port closure, HTTPS/SNI certificate validation, bot API authentication and real HTTPS/Telegram traffic through VPN transports. Passing these checks is not a guarantee against all vulnerabilities or future provider failures.

The optional `tproxy-gateway.conf.example` enables HTTP/2 for a WEB proxy behind the existing stream router. It expects PROXY protocol on loopback TCP10444 and forwards HTTP requests to the local relay on TCP18088. Its failure-only diagnostic log records fixed endpoint classes, HTTP status and timings. Nginx log rotation must cover that file. Validate the configuration, reload nginx and wait for the new worker generation before checking public ALPN and authenticated carrier traffic.
