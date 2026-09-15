#!/usr/bin/env python3
"""Read-only metadata inspection, suitable for `python3 -` over pinned SSH."""

import argparse
import hashlib
import ipaddress
import json
import os
import platform
import re
import shutil
import subprocess
import sys


SCHEMA = "unifiedvpn-openflux-server-inspect-v1"
DOCKER = ["docker", "--host", "unix:///var/run/docker.sock"]
MAX_OUTPUT_BYTES = 2 * 1024 * 1024
MAX_RESOURCES = 256
SERVICE_NAMES = (
    "olcrtc.service", "olcRTC.service", "olcrtc-server.service", "x-ui.service",
    "xray.service", "amnezia.service", "docker.service", "containerd.service",
    "ssh.service", "sshd.service",
)
SERVICE_PATTERNS = ("olcrtc@*.service",)
OLCRTC_INSTANCE = re.compile(r"olcrtc@[A-Za-z0-9][A-Za-z0-9_.-]{0,63}\.service\Z")
VERSION_FORMAT = (
    '{"client_version":{{json .Client.Version}},"client_api_version":{{json .Client.APIVersion}},'
    '"server_version":{{json .Server.Version}},"server_api_version":{{json .Server.APIVersion}},'
    '"server_min_api_version":{{json .Server.MinAPIVersion}},"os":{{json .Server.Os}},"architecture":{{json .Server.Arch}}}'
)
CONTAINER_FORMAT = '{"id":{{json .ID}},"name":{{json .Names}},"image":{{json .Image}},"state":{{json .State}}}'
NETWORK_FORMAT = (
    '{"id":{{json .Id}},"name":{{json .Name}},"driver":{{json .Driver}},"scope":{{json .Scope}},'
    '"internal":{{json .Internal}},"ipv6":{{json .EnableIPv6}},"ipam":{{json .IPAM.Config}},"containers_count":{{len .Containers}}}'
)
IMAGE_FORMAT = '{"id":{{json .Id}},"tags":{{json .RepoTags}},"digests":{{json .RepoDigests}},"os":{{json .Os}},"architecture":{{json .Architecture}}}'


def text(value, limit=256):
    if not isinstance(value, str) or len(value) > limit or any(ord(char) < 32 or ord(char) == 127 for char in value):
        return None
    return value


def version(value):
    value = text(value, 96)
    return value if value and re.fullmatch(r"[A-Za-z0-9._+() -]+", value) else None


def integer(value):
    return value if type(value) is int and value >= 0 else None


def host_metadata():
    system = platform.system()
    distribution = {}
    if hasattr(platform, "freedesktop_os_release"):
        try:
            # OS identity is public metadata, not a VPN or account configuration.
            release = platform.freedesktop_os_release()
            distribution = {key: version(release.get(key)) for key in ("ID", "VERSION_ID")}
        except OSError:
            pass
    resources = {"cpu_count": os.cpu_count(), "memory_total_bytes": None, "memory_free_bytes": None,
                 "root_disk_total_bytes": None, "root_disk_free_bytes": None, "load_average": None}
    try:
        page_size = os.sysconf("SC_PAGE_SIZE")
        resources["memory_total_bytes"] = os.sysconf("SC_PHYS_PAGES") * page_size
        resources["memory_free_bytes"] = os.sysconf("SC_AVPHYS_PAGES") * page_size
    except (AttributeError, OSError, ValueError):
        pass
    try:
        disk = shutil.disk_usage("/")
        resources.update(root_disk_total_bytes=disk.total, root_disk_free_bytes=disk.free)
        resources["load_average"] = [round(value, 3) for value in os.getloadavg()]
    except (AttributeError, OSError):
        pass
    return {"os": version(system), "distribution": distribution, "kernel": version(platform.release()),
            "architecture": version(platform.machine()), "python_version": version(platform.python_version()),
            "uid": os.geteuid() if hasattr(os, "geteuid") else None, "resources": resources}


def run_query(argv, timeout=10):
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("DOCKER_", "BUILDX_", "BUILDKIT_"))}
    try:
        process = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                 stderr=subprocess.DEVNULL, timeout=timeout, check=False, env=environment)
    except FileNotFoundError:
        return {"status": "unavailable", "data": b""}
    except subprocess.TimeoutExpired:
        return {"status": "timeout", "data": b""}
    except OSError:
        return {"status": "failed", "data": b""}
    if len(process.stdout) > MAX_OUTPUT_BYTES:
        return {"status": "output_limit", "data": b""}
    if process.returncode:
        # Missing units can make a batched property-only query partially fail.
        if argv[:2] == ["systemctl", "show"] and process.stdout:
            return {"status": "partial", "data": process.stdout}
        return {"status": "failed", "data": b""}
    return {"status": "ok", "data": process.stdout}


class Inspector:
    def __init__(self, query=run_query, metadata=host_metadata):
        self.query = query
        self.metadata = metadata
        self.checks = {}

    def raw(self, name, argv):
        result = self.query(argv, timeout=10)
        self.checks[name] = result["status"]
        return result["data"] if result["status"] in ("ok", "partial") else None

    def records(self, name, argv, lines=False):
        data = self.raw(name, argv)
        if data is None:
            return [] if lines else None
        try:
            if lines:
                return [json.loads(line) for line in data.splitlines() if line.strip()]
            return json.loads(data)
        except (ValueError, UnicodeError):
            self.checks[name] = "invalid_data"
            return [] if lines else None

    def services(self):
        data = self.raw("services", ["systemctl", "show", "--no-pager",
                                    "--property=Id,LoadState,ActiveState,SubState,UnitFileState", *SERVICE_NAMES, *SERVICE_PATTERNS])
        if data is None:
            return []
        names = {"Id": "name", "LoadState": "load_state", "ActiveState": "active_state",
                 "SubState": "sub_state", "UnitFileState": "unit_file_state"}
        result = []
        current = {}
        for line in data.decode("utf-8", errors="replace").splitlines() + [""]:
            if not line:
                name = current.get("name", "")
                if name in SERVICE_NAMES or OLCRTC_INSTANCE.fullmatch(name):
                    result.append(current)
                current = {}
                continue
            field, separator, value = line.partition("=")
            if separator and field in names and re.fullmatch(r"[A-Za-z0-9_.@-]*", value):
                current[names[field]] = value[:128]
        return result

    def docker_version(self):
        value = self.records("docker_version", DOCKER + ["version", "--format", VERSION_FORMAT])
        if not isinstance(value, dict):
            return {}
        return {key: version(value.get(key)) for key in ("client_version", "client_api_version", "server_version", "server_api_version",
                                                         "server_min_api_version", "os", "architecture")}

    def containers(self):
        records = self.records("containers", DOCKER + ["container", "ls", "--all", "--no-trunc", "--format", CONTAINER_FORMAT], lines=True)
        result = []
        for value in records[:MAX_RESOURCES]:
            if not isinstance(value, dict) or not re.fullmatch(r"[0-9a-f]{64}", str(value.get("id", ""))):
                continue
            result.append({key: text(value.get(key)) for key in ("id", "name", "image", "state")})
        if len(records) > MAX_RESOURCES:
            self.checks["containers"] = "truncated"
        return result

    def identities(self, kind):
        data = self.raw(kind + "_list", DOCKER + [kind, "ls", "--quiet", "--no-trunc"])
        if data is None:
            return []
        expression = rb"sha256:[0-9a-f]{64}" if kind == "image" else rb"[0-9a-f]{64}"
        values = sorted(set(data.split()))
        if any(not re.fullmatch(expression, item) for item in values):
            self.checks[kind + "_list"] = "invalid_data"
            return []
        if len(values) > MAX_RESOURCES:
            self.checks[kind + "_list"] = "truncated"
        return [item.decode("ascii") for item in values[:MAX_RESOURCES]]

    def networks(self):
        ids = self.identities("network")
        if not ids:
            return []
        records = self.records("networks", DOCKER + ["network", "inspect", "--format", NETWORK_FORMAT, *ids], lines=True)
        result = []
        for value in records[:MAX_RESOURCES]:
            if not isinstance(value, dict) or value.get("id") not in ids:
                continue
            item = {key: text(value.get(key)) for key in ("id", "name", "driver", "scope")}
            item.update(internal=value.get("internal") is True, ipv6=value.get("ipv6") is True,
                        containers_count=integer(value.get("containers_count")), ipam=[])
            for config in value.get("ipam") or []:
                if not isinstance(config, dict):
                    continue
                addresses = {}
                for key in ("Subnet", "IPRange", "Gateway"):
                    if config.get(key):
                        try:
                            ipaddress.ip_interface(config[key])
                            addresses[key] = config[key]
                        except ValueError:
                            pass
                if addresses:
                    item["ipam"].append(addresses)
            result.append(item)
        return result

    def images(self):
        ids = self.identities("image")
        if not ids:
            return []
        records = self.records("images", DOCKER + ["image", "inspect", "--format", IMAGE_FORMAT, *ids], lines=True)
        result = []
        for value in records[:MAX_RESOURCES]:
            if not isinstance(value, dict) or value.get("id") not in ids:
                continue
            item = {key: text(value.get(key)) for key in ("id", "os", "architecture")}
            for key in ("tags", "digests"):
                item[key] = [name for name in (text(candidate) for candidate in value.get(key) or []) if name][:MAX_RESOURCES]
            result.append(item)
        return result

    def routes(self):
        records = self.records("ipv4_routes", ["ip", "-j", "-4", "route", "show", "table", "all"])
        if not isinstance(records, list):
            return []
        result = []
        fields = ("type", "dst", "gateway", "dev", "table", "protocol", "scope", "prefsrc", "metric", "mtu", "onlink")
        for value in records[:8192]:
            if not isinstance(value, dict):
                continue
            item = {key: entry for key in fields if (entry := value.get(key)) is not None
                    and (type(entry) in (int, bool) or text(entry) is not None)}
            if isinstance(value.get("nexthops"), list):
                item["nexthops"] = [{key: entry for key in ("gateway", "dev", "weight") if (entry := hop.get(key)) is not None
                                    and (type(entry) is int or text(entry) is not None)}
                                   for hop in value["nexthops"] if isinstance(hop, dict)][:MAX_RESOURCES]
            if item:
                result.append(item)
        if len(records) > 8192:
            self.checks["ipv4_routes"] = "truncated"
        return result

    def firewall(self):
        result = {}
        for utility in ("iptables-save", "ip6tables-save"):
            data = self.raw(utility, [utility])
            if data is None:
                result[utility] = {"status": self.checks[utility]}
                continue
            # Ignore generated timestamps and live counters for repeatable checks.
            lines = [re.sub(rb"\[[0-9]+:[0-9]+\]", b"[0:0]", line)
                     for line in data.splitlines() if line.strip() and not line.startswith(b"#")]
            normalized = b"\n".join(lines) + b"\n"
            result[utility] = {"status": "ok", "sha256": hashlib.sha256(normalized).hexdigest(),
                               "rule_count": sum(line.startswith(b"-A ") for line in lines), "line_count": len(lines)}
        data = self.raw("nft", ["nft", "--json", "list", "ruleset"])
        if data is None:
            result["nft"] = {"status": self.checks["nft"]}
        else:
            try:
                ruleset = json.loads(data)
                entries = ruleset["nftables"]
                if not isinstance(entries, list):
                    raise ValueError()
                def without_counters(value):
                    if isinstance(value, list):
                        return [without_counters(item) for item in value]
                    if isinstance(value, dict):
                        return {key: without_counters(item) for key, item in value.items()
                                if key not in ("packets", "bytes", "expires")}
                    return value
                entries = [without_counters(item) for item in entries if isinstance(item, dict) and "metainfo" not in item]
                normalized = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode("utf-8")
                result["nft"] = {"status": "ok", "sha256": hashlib.sha256(normalized).hexdigest(),
                                 "rule_count": sum("rule" in item for item in entries), "object_count": len(entries)}
            except (ValueError, UnicodeError, KeyError, TypeError):
                result["nft"] = {"status": "invalid_data"}
                self.checks["nft"] = "invalid_data"
        return result

    def inspect(self, firewall_hashes=False):
        self.checks = {}
        host = self.metadata()
        docker = self.docker_version()
        available = bool(docker.get("server_version"))
        services = self.services()
        containers = self.containers() if available else []
        networks = self.networks() if available else []
        images = self.images() if available else []
        summary = {key: host.get(key) for key in ("os", "kernel", "architecture", "python_version", "uid", "resources")}
        container_count = len(containers) if available and self.checks.get("containers") == "ok" else None
        network_count = len(networks) if available and self.checks.get("network_list") == "ok" and self.checks.get("networks", "ok") == "ok" else None
        image_count = len(images) if available and self.checks.get("image_list") == "ok" and self.checks.get("images", "ok") == "ok" else None
        summary.update(docker_available=available, docker_version=docker.get("server_version"),
                       docker_api_version=docker.get("server_api_version"),
                       container_count=container_count,
                       running_container_count=sum(item.get("state") == "running" for item in containers) if container_count is not None else None,
                       network_count=network_count, image_count=image_count)
        return {"schema": SCHEMA, "summary": summary, "host": host, "docker": docker, "services": services,
                "containers": containers, "networks": networks, "images": images, "ipv4_routes": self.routes(),
                "firewall": self.firewall() if firewall_hashes else {"status": "not_requested"},
                "checks": self.checks, "scope": "Read-only local metadata; not a connectivity or end-to-end validation."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--firewall-hashes", action="store_true", help="Include normalized firewall hashes and counts, never rules")
    args = parser.parse_args(argv)
    try:
        report = Inspector().inspect(firewall_hashes=args.firewall_hashes)
    except (OSError, ValueError, TypeError, KeyError):
        report = {"schema": SCHEMA, "summary": {"inspection_available": False}, "checks": {"inspection": "failed"}}
    json.dump(report, sys.stdout, sort_keys=True, ensure_ascii=True)
    sys.stdout.write("\n")
    return 0 if report.get("checks", {}).get("inspection") != "failed" else 1


if __name__ == "__main__":
    sys.exit(main())
