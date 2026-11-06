"""Adaptive cascade for VPN data only, with a kernel-expiring availability lease."""

import ipaddress, json, pathlib, re, subprocess, time, urllib.request
from health import resources
from policy import decide
from link import rule, chain, command, COMMENT, MARK, IFACE

ROOT = pathlib.Path("/var/lib/unifiedvpn-cascade")
READY = "uvpn_cascade_ready"
MASK = "0xff000000"
DIRECT = "0x52000000/0xff000000"
FLOW = "UVPN_HOP_FLOW"
PICK = "UVPN_HOP_PICK"
OUT = "UVPN_HOP_OUT"
PRE = "UVPN_HOP_PRE"
XRAY_FLOW = "UVPN_HOP_XFLOW"
XRAY_PICK = "UVPN_HOP_XPICK"
WANTED = re.compile(
    r"^(?:x-ui|mtg|mtproxy|mtproxy-public|tproxy-server|unifiedvpn-vkcalls-[\w-]+|unifiedvpn-(?:jitsi|rtc|openflux)@[\w-]+)\.service$"
)
EXCLUDED = [
    "0.0.0.0/8",
    "10.0.0.0/8",
    "100.64.0.0/10",
    "127.0.0.0/8",
    "169.254.0.0/16",
    "172.16.0.0/12",
    "192.168.0.0/16",
    "224.0.0.0/4",
    "240.0.0.0/4",
]
# Published by Telegram at https://core.telegram.org/resources/cidr.txt.
# Applied only when a NEW flow is selected, after existing marks were restored.
TELEGRAM_DIRECT = [
    "91.108.56.0/22",
    "91.108.4.0/22",
    "91.108.8.0/22",
    "91.108.16.0/22",
    "91.108.12.0/22",
    "149.154.160.0/20",
    "91.105.192.0/23",
    "91.108.20.0/22",
    "185.76.151.0/24",
]
DIRECT_SERVICES = {"x-ui.service"}


def lease(enabled):
    if enabled:
        for half in ["0.0.0.0/1", "128.0.0.0/1"]:
            command("ipset", "add", READY, half, "timeout", "45", "-exist")
    else:
        command("ipset", "flush", READY)


def scopes():
    units = command(
        "systemctl",
        "list-units",
        "--type=service",
        "--state=running",
        "--no-legend",
        "--no-pager",
        "--plain",
    ).stdout
    groups = []
    for line in units.splitlines():
        unit = line.split()[0]
        if not WANTED.fullmatch(unit):
            continue
        group = (
            command("systemctl", "show", unit, "--property=ControlGroup", "--value")
            .stdout.strip()
            .lstrip("/")
        )
        if (
            group
            and re.fullmatch(r"[A-Za-z0-9_./@:-]+", group)
            and (pathlib.Path("/sys/fs/cgroup") / group).is_dir()
        ):
            groups.append((unit, group))
    containers = []
    for name in ["amnezia-awg2", "unifiedvpn-awg3"]:
        result = command("docker", "inspect", name, check=False)
        if result.returncode:
            continue
        info = json.loads(result.stdout)[0]
        if not info["State"]["Running"]:
            continue
        ports = [
            key.split("/")[0]
            for key in info["NetworkSettings"]["Ports"]
            if key.endswith("/udp")
        ]
        for network, desc in info["NetworkSettings"]["Networks"].items():
            address = desc.get("IPAddress")
            if not address:
                continue
            ipaddress.IPv4Address(address)
            net = json.loads(command("docker", "network", "inspect", network).stdout)[0]
            bridge = net.get("Options", {}).get("com.docker.network.bridge.name") or (
                "docker0" if network == "bridge" else "br-" + net["Id"][:12]
            )
            assert re.fullmatch(r"[A-Za-z0-9_.:-]+", bridge)
            containers.append((name, bridge, address, ports))
    return groups, containers


def classifier():
    cfg = json.loads(pathlib.Path("/etc/unifiedvpn-cascade/node.json").read_text())
    peers = [
        str(ipaddress.IPv4Address(cfg[key])) + "/32" for key in ["public", "peerPublic"]
    ]
    owned_chains = [FLOW, PICK, OUT, PRE, XRAY_FLOW, XRAY_PICK]
    for name in owned_chains:
        chain("mangle", name)
    if command("ipset", "list", READY, check=False).returncode:
        command("ipset", "create", READY, "hash:net", "family", "inet", "timeout", "45")
    groups, containers = scopes()
    lines = ["*mangle"] + ["-F " + name for name in owned_chains]

    def add(name, *args):
        lines.append("-A " + name + " " + " ".join(args))

    for flow, selection in [(FLOW, PICK), (XRAY_FLOW, XRAY_PICK)]:
        add(flow, "-m", "conntrack", "--ctdir", "REPLY", "-j", "RETURN")
        for network in EXCLUDED + peers:
            add(flow, "-d", network, "-j", "RETURN")
        add(
            flow, "-j", "CONNMARK", "--restore-mark", "--nfmask", MASK, "--ctmask", MASK
        )
        for mark in [MARK, DIRECT]:
            add(flow, "-m", "mark", "--mark", mark, "-j", "RETURN")
        add(flow, "-m", "conntrack", "!", "--ctstate", "NEW", "-j", "RETURN")
        add(flow, "-j", selection)
    # Keep new Xray flows direct and retain the marks of existing flows.
    add(XRAY_PICK, "-j", "MARK", "--set-xmark", DIRECT)
    add(XRAY_PICK, "-j", "CONNMARK", "--save-mark", "--nfmask", MASK, "--ctmask", MASK)
    add(PICK, "-j", "MARK", "--set-xmark", DIRECT)
    add(
        PICK,
        "-m",
        "set",
        "--match-set",
        READY,
        "dst",
        "-j",
        "MARK",
        "--set-xmark",
        MARK,
    )
    for network in TELEGRAM_DIRECT:
        add(PICK, "-d", network, "-j", "MARK", "--set-xmark", DIRECT)
    add(PICK, "-j", "CONNMARK", "--save-mark", "--nfmask", MASK, "--ctmask", MASK)
    for unit, group in groups:
        match = ["-m", "cgroup", "--path", group]
        # Keep STUN/ICE/DTLS and TURN UDP bindings at their existing ingress IP.
        if unit.startswith(
            (
                "unifiedvpn-vkcalls",
                "unifiedvpn-jitsi",
                "unifiedvpn-rtc",
                "unifiedvpn-openflux",
            )
        ):
            add(OUT, *match, "-p", "udp", "-j", "RETURN")
        add(OUT, *match, "-j", XRAY_FLOW if unit in DIRECT_SERVICES else FLOW)
    add(PRE, "-i", IFACE, "-j", "RETURN")
    for name, bridge, address, ports in containers:
        match = ["-i", bridge, "-s", address + "/32"]
        for port in ports:
            add(PRE, *match, "-p", "udp", "--sport", port, "-j", "RETURN")
        add(PRE, *match, "-j", FLOW)
    lines += ["COMMIT", ""]
    data = "\n".join(lines)
    result = subprocess.run(
        ["iptables-restore", "-w", "5", "--noflush"],
        input=data,
        capture_output=True,
        text=True,
        timeout=20,
    )
    if result.returncode:
        raise RuntimeError("Scoped classifier refresh failed")
    for hook, owned in [("OUTPUT", OUT), ("PREROUTING", PRE)]:
        rule("mangle", hook, "-m", "comment", "--comment", COMMENT, "-j", owned)
    return {
        "services": [unit for unit, _ in groups],
        "directServices": [unit for unit, _ in groups if unit in DIRECT_SERVICES],
        "containers": [name for name, _, _, _ in containers],
    }


def rate(value):
    current = command("tc", "-j", "qdisc", "show", "dev", IFACE).stdout
    if not any(entry.get("kind") == "htb" for entry in json.loads(current)):
        command(
            "tc",
            "qdisc",
            "replace",
            "dev",
            IFACE,
            "root",
            "handle",
            "1:",
            "htb",
            "default",
            "10",
        )
        command(
            "tc",
            "class",
            "add",
            "dev",
            IFACE,
            "parent",
            "1:",
            "classid",
            "1:10",
            "htb",
            "rate",
            str(value) + "mbit",
            "ceil",
            str(value) + "mbit",
            "quantum",
            "15140",
        )
        command(
            "tc",
            "qdisc",
            "add",
            "dev",
            IFACE,
            "parent",
            "1:10",
            "handle",
            "10:",
            "fq_codel",
        )
    else:
        command(
            "tc",
            "class",
            "change",
            "dev",
            IFACE,
            "parent",
            "1:",
            "classid",
            "1:10",
            "htb",
            "rate",
            str(value) + "mbit",
            "ceil",
            str(value) + "mbit",
            "quantum",
            "15140",
        )


def write(state):
    target = ROOT / "status.json"
    temporary = ROOT / "status.new"
    temporary.write_text(json.dumps(state, indent=2))
    temporary.chmod(0o600)
    temporary.replace(target)


def main():
    ROOT.mkdir(mode=0o700, exist_ok=True)
    cfg = json.loads(pathlib.Path("/etc/unifiedvpn-cascade/node.json").read_text())
    assert cfg["role"] == "primary"
    enabled = False
    good = 0
    last_rate = 0
    scope = classifier()
    last_scope = time.monotonic()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while True:
        local = resources()
        remote = None
        reachable = False
        delay = None
        try:
            start = time.monotonic()
            with opener.open(
                "http://" + cfg["peerPrivate"] + ":18443/health", timeout=4
            ) as response:
                remote = json.load(response)
            delay = round((time.monotonic() - start) * 1000, 1)
            # The response is generated for this private request. Probe age is
            # computed on the exit so small clock skew cannot disable routing.
            reachable = remote.get("nodeRole") == "exit"
        except Exception:
            pass
        forced = False
        try:
            forced = (
                float(
                    pathlib.Path(
                        "/run/unifiedvpn-cascade/health-test-until"
                    ).read_text()
                )
                > time.time()
            )
        except (OSError, ValueError):
            pass
        decision = decide(local, remote, reachable=reachable, forced_failure=forced)
        good = good + 1 if decision.enabled else 0
        enabled = decision.enabled and (enabled or good >= 3)
        if time.monotonic() - last_scope > 60:
            scope = classifier()
            last_scope = time.monotonic()
        if last_rate != decision.rate_mbit:
            rate(decision.rate_mbit)
            last_rate = decision.rate_mbit
        lease(enabled)
        write(
            {
                "checkedAt": int(time.time()),
                "entry": cfg["public"],
                "exit": cfg["peerPublic"],
                "mode": "cascade" if enabled else "direct-entry",
                "cascadeEnabled": enabled,
                "reason": (
                    decision.reason
                    if not decision.enabled or enabled
                    else "waiting for three healthy checks"
                ),
                "privateRttMs": delay,
                "rateLimitMbit": last_rate,
                "leaseSeconds": 45,
                "newFlowOnly": True,
                "local": local,
                "remote": remote,
                "scope": scope,
                "automaticIngressFailover": False,
            }
        )
        time.sleep(15)


if __name__ == "__main__":
    main()
