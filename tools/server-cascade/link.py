"""Private inter-node link. Own routes/chains only; never replace the main route."""

import ipaddress, json, pathlib, subprocess, sys

ROOT = pathlib.Path("/etc/unifiedvpn-cascade")
IFACE = "uvpn-hop"
TABLE = "51889"
MARK = "0x51000000/0xff000000"
COMMENT = "unifiedvpn-cascade-v1"


def command(*args, check=True):
    result = subprocess.run(list(args), capture_output=True, text=True, timeout=20)
    if check and result.returncode:
        raise RuntimeError(
            "Cascade network operation failed: " + args[0] + " " + args[1]
        )
    return result


def rule(table, chain, *args):
    if command(
        "iptables", "-w", "5", "-t", table, "-C", chain, *args, check=False
    ).returncode:
        command("iptables", "-w", "5", "-t", table, "-I", chain, "1", *args)


def chain(table, name):
    if command("iptables", "-w", "5", "-t", table, "-S", name, check=False).returncode:
        command("iptables", "-w", "5", "-t", table, "-N", name)


def up():
    cfg = json.loads((ROOT / "node.json").read_text())
    assert cfg["role"] in ("primary", "exit")
    for key in ["public", "peerPublic", "private", "peerPrivate"]:
        ipaddress.IPv4Address(cfg[key])
    assert cfg["private"] != cfg["peerPrivate"] and 1024 <= cfg["port"] <= 65535
    if command("ip", "link", "show", "dev", IFACE, check=False).returncode:
        command("ip", "link", "add", "dev", IFACE, "type", "wireguard")
    command("wg", "setconf", IFACE, str(ROOT / "wireguard.conf"))
    command("ip", "address", "replace", cfg["private"] + "/30", "dev", IFACE)
    command("ip", "link", "set", "dev", IFACE, "mtu", "1380", "up")
    command("sysctl", "-q", "-w", "net.ipv4.conf." + IFACE + ".rp_filter=2")
    assert (
        pathlib.Path("/proc/sys/net/ipv4/ip_forward").read_text().strip() == "1"
    ), "Existing VPN forwarding must already be enabled"
    for table, name in [
        ("filter", "UVPN_HOP_IN"),
        ("filter", "UVPN_HOP_FWD"),
        ("nat", "UVPN_HOP_NAT"),
    ]:
        chain(table, name)
    # Put the dedicated hook before generic provider/UFW chains, without changing them.
    for table, base, owned in [
        ("filter", "INPUT", "UVPN_HOP_IN"),
        ("filter", "FORWARD", "UVPN_HOP_FWD"),
        ("nat", "POSTROUTING", "UVPN_HOP_NAT"),
    ]:
        rule(table, base, "-m", "comment", "--comment", COMMENT, "-j", owned)
    for proto, tail in [
        ("udp", ["--dport", str(cfg["port"])]),
        ("tcp", ["--dport", "22"]),
        ("icmp", ["--icmp-type", "8"]),
    ]:
        rule(
            "filter",
            "UVPN_HOP_IN",
            "-s",
            cfg["peerPublic"],
            "-p",
            proto,
            *tail,
            "-j",
            "ACCEPT"
        )
    rule(
        "filter",
        "UVPN_HOP_IN",
        "-p",
        "udp",
        "--dport",
        str(cfg["port"]),
        "!",
        "-s",
        cfg["peerPublic"],
        "-j",
        "DROP",
    )
    rule(
        "filter",
        "UVPN_HOP_IN",
        "-i",
        IFACE,
        "-s",
        cfg["peerPrivate"],
        "-p",
        "icmp",
        "-j",
        "ACCEPT",
    )
    for port in ["22", "18443"]:
        rule(
            "filter",
            "UVPN_HOP_IN",
            "-i",
            IFACE,
            "-s",
            cfg["peerPrivate"],
            "-p",
            "tcp",
            "--dport",
            port,
            "-j",
            "ACCEPT",
        )
    rule(
        "filter",
        "UVPN_HOP_FWD",
        "-i",
        IFACE,
        "-m",
        "conntrack",
        "--ctstate",
        "ESTABLISHED,RELATED",
        "-j",
        "ACCEPT",
    )
    if cfg["role"] == "exit":
        rule(
            "filter",
            "UVPN_HOP_FWD",
            "-i",
            IFACE,
            "-s",
            cfg["peerPrivate"],
            "-o",
            cfg["wan"],
            "-j",
            "ACCEPT",
        )
        rule(
            "filter",
            "UVPN_HOP_FWD",
            "-o",
            IFACE,
            "-d",
            cfg["peerPrivate"],
            "-m",
            "conntrack",
            "--ctstate",
            "ESTABLISHED,RELATED",
            "-j",
            "ACCEPT",
        )
        rule(
            "nat",
            "UVPN_HOP_NAT",
            "-s",
            cfg["peerPrivate"],
            "-o",
            cfg["wan"],
            "-j",
            "MASQUERADE",
        )
    else:
        # This table is consulted only for our explicit mark or our test source IP.
        command("ip", "route", "replace", "default", "dev", IFACE, "table", TABLE)
        existing = json.loads(command("ip", "-j", "rule", "show").stdout)
        for priority, tail in [
            (15189, ["fwmark", MARK]),
            (15190, ["from", cfg["private"] + "/32"]),
        ]:
            matches = [entry for entry in existing if entry["priority"] == priority]
            if not matches:
                command(
                    "ip",
                    "rule",
                    "add",
                    "priority",
                    str(priority),
                    *tail,
                    "lookup",
                    TABLE
                )
            else:
                assert (
                    str(matches[0]["table"]) == TABLE
                ), "Routing table priority collision"
        rule(
            "nat",
            "UVPN_HOP_NAT",
            "-o",
            IFACE,
            "-m",
            "mark",
            "--mark",
            MARK,
            "-j",
            "SNAT",
            "--to-source",
            cfg["private"],
        )
        rule(
            "filter",
            "UVPN_HOP_FWD",
            "-o",
            IFACE,
            "-m",
            "mark",
            "--mark",
            MARK,
            "-j",
            "ACCEPT",
        )
        for hook in ["OUTPUT", "FORWARD"]:
            rule(
                "mangle",
                hook,
                "-m",
                "mark",
                "--mark",
                MARK,
                "-p",
                "tcp",
                "--tcp-flags",
                "SYN,RST",
                "SYN",
                "-m",
                "tcpmss",
                "--mss",
                "1341:65535",
                "-m",
                "comment",
                "--comment",
                COMMENT,
                "-j",
                "TCPMSS",
                "--set-mss",
                "1340",
            )
    print(
        json.dumps(
            {
                "role": cfg["role"],
                "interface": IFACE,
                "mainRouteReplaced": False,
                "privateLinkConfigured": True,
            }
        )
    )


def disable():
    # Existing selected flows keep their route; only new flows revert to the entry.
    command("ipset", "flush", "uvpn_cascade_ready", check=False)
    print(json.dumps({"newCascadeFlowsDisabled": True, "existingLinkRetained": True}))


if __name__ == "__main__":
    {"up": up, "disable": disable}[sys.argv[1]]()
