"""Public regional TCP probes and direct authoritative DNS observations."""
import ipaddress
import errno
import http.client
import json
import math
import re
import subprocess
import socket
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


class ProbeUnavailable(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


class CheckHost:
    BASE = "https://check-host.net"

    def __init__(self, *, opener=None, sleep=time.sleep, clock=time.time, timeout=15):
        self.opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.sleep = sleep
        self.clock = clock
        self.timeout = timeout
        self.last_request = 0
        self.request_lock = threading.Lock()

    def request(self, path, parameters=()):
        with self.request_lock:
            return self.request_serial(path, parameters)

    def request_serial(self, path, parameters=()):
        # No probe credentials, capability links or request secrets enter this API.
        wait = 1 - (self.clock() - self.last_request)
        if wait > 0:
            self.sleep(wait)
        query = urllib.parse.urlencode(parameters)
        url = self.BASE + path + ("?" + query if query else "")
        request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "VPN-ingress-monitor/1"})
        self.last_request = self.clock()
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                body = response.read(1024 * 1024 + 1)
            if len(body) > 1024 * 1024:
                raise ValueError("Oversized probe response")
            result = json.loads(body)
            if not isinstance(result, dict):
                raise ValueError("Unexpected probe response")
            return result
        except (OSError, ValueError, http.client.HTTPException) as error:
            raise ProbeUnavailable("Regional probe API unavailable") from None

    def russian_nodes(self, count=3):
        result = self.request("/nodes/hosts")
        candidates = []
        nodes = result.get("nodes")
        if not isinstance(nodes, dict):
            raise ProbeUnavailable("Unexpected regional node list")
        for name, value in nodes.items():
            if not isinstance(value, dict):
                continue
            location = value.get("location", [])
            if isinstance(location, list) and location and location[0] == "ru" and isinstance(name, str) and re.fullmatch(r"[a-z0-9.-]+\.node\.check-host\.net", name):
                candidates.append((name, value))
        selected = {}
        asns = set()
        for name, value in sorted(candidates):
            asn = value.get("asn")
            if not isinstance(asn, str) or not asn or asn in asns:
                continue
            selected[name] = {"country": "ru", "asn": asn}
            asns.add(asn)
            if len(selected) >= count:
                break
        if len(selected) < 2:
            raise ProbeUnavailable("Insufficient independent Russian probe nodes")
        return selected

    def tcp(self, address, nodes, *, port=443, polls=10, poll_seconds=5):
        ipaddress.IPv4Address(address)
        parameters = [("host", f"{address}:{port}")]
        parameters.extend(("node", name) for name in nodes)
        started = self.request("/check-tcp", parameters)
        request_id = started.get("request_id")
        if started.get("ok") != 1 or not isinstance(request_id, str) or not re.fullmatch(r"[a-zA-Z0-9-]+", request_id):
            raise ProbeUnavailable("Regional probe was not accepted")
        returned = started.get("nodes", {})
        if not isinstance(returned, dict):
            raise ProbeUnavailable("Unexpected regional probe node list")
        trusted = {
            name: metadata for name, metadata in nodes.items()
            if isinstance(returned.get(name), list) and len(returned[name]) >= 5
            and returned[name][0] == "ru" and returned[name][4] == metadata["asn"]
        }
        result = {}
        for _ in range(polls):
            self.sleep(max(5, poll_seconds))
            extended = self.request("/check-result-extended/" + request_id)
            host = extended.get("host")
            separate_port = extended.get("port")
            matching_host = (
                host in {f"{address}:{port}", f"tcp://{address}:{port}"}
                and separate_port in (None, port, str(port))
            ) or (host == address and separate_port in (port, str(port)))
            if extended.get("command") != "tcp" or not matching_host:
                raise ProbeUnavailable("Regional probe target does not match its request")
            created = extended.get("created")
            if not isinstance(created, (int, float)) or not -30 <= self.clock() - created <= 120:
                raise ProbeUnavailable("Regional probe result is stale")
            result = extended.get("results")
            if not isinstance(result, dict):
                raise ProbeUnavailable("Unexpected regional probe result")
            if all(result.get(name) is not None for name in trusted):
                break
        now = self.clock()
        return [
            {"node": name, **metadata, "checked_at": now, "request_id": request_id, "status": tcp_status(result.get(name), address)}
            for name, metadata in trusted.items()
        ]


def tcp_status(value, expected_address):
    """Missing/pending/API errors are unknown. Only explicit network failures vote."""
    if not isinstance(value, list) or len(value) != 1 or not isinstance(value[0], dict):
        return "unknown"
    item = value[0]
    if "address" in item and item["address"] != expected_address:
        return "unknown"
    elapsed = item.get("time")
    if item.get("address") == expected_address and type(elapsed) in (int, float) and math.isfinite(elapsed) and elapsed >= 0 and "error" not in item:
        return "healthy"
    error = item.get("error", "")
    failures = {"Connection timed out", "Connection refused", "No route to host", "Network is unreachable", "Host is down"}
    return "failed" if error in failures else "unknown"


def authoritative_dns(nameservers, records, *, command=subprocess.run):
    if len(nameservers) < 2 or len(set(nameservers)) != len(nameservers):
        raise ProbeUnavailable("Two distinct authoritative nameservers are required")
    observations = {}
    for nameserver in nameservers:
        addresses = {}
        for name in records:
            values = []
            for kind in ["A", "AAAA"]:
                result = command(
                    ["dig", "+tcp", "+norecurse", "+time=3", "+tries=1", "+noall", "+comments", "+answer", "@" + nameserver, name, kind],
                    capture_output=True, text=True, timeout=8,
                )
                text = result.stdout
                flags = re.search(r"flags:\s*([^;]+);", text)
                if result.returncode or "status: NOERROR" not in text or not flags or "aa" not in flags[1].split():
                    raise ProbeUnavailable("Authoritative DNS query failed")
                for line in text.splitlines():
                    parts = line.split()
                    if len(parts) < 5 or parts[0].rstrip(".").lower() != name.rstrip(".").lower():
                        continue
                    if parts[3] == "CNAME" or (kind == "AAAA" and parts[3] == "AAAA"):
                        raise ProbeUnavailable("Managed DNS has an uncovered CNAME or IPv6 route")
                    if kind == "A" and parts[3] == "A":
                        values.append(str(ipaddress.IPv4Address(parts[4])))
            addresses[name] = sorted(set(values))
        observations[nameserver] = addresses
    return observations


def peer_ports(address, observer, round_id, *, connect=socket.create_connection, clock=time.time):
    """Two protocols from one publisher vantage. A refused port is responsive."""
    ipaddress.IPv4Address(address)
    values = {}
    for port in (22, 443):
        try:
            with connect((address, port), timeout=4):
                values[str(port)] = "responsive"
        except ConnectionRefusedError:
            values[str(port)] = "responsive"
        except OSError as error:
            values[str(port)] = "unreachable" if isinstance(error, TimeoutError) or error.errno in {errno.ETIMEDOUT, errno.ENETUNREACH, errno.EHOSTUNREACH} else "unknown"
    return {"address": address, "observer": observer, "round_id": round_id, "ports": values, "checked_at": clock()}


def internet_origins(origins, *, clock=time.time):
    results = []
    for origin in origins:
        value = {"provider": origin["provider"], "authenticated": False, "healthy": False, "checked_at": clock()}
        connection = http.client.HTTPSConnection(origin["hostname"], timeout=6, context=ssl.create_default_context())
        try:
            connection.request("GET", origin["path"])
            response = connection.getresponse()
            body = response.read(65537)
            marker = origin.get("contains")
            valid_body = marker.encode() in body if marker else not body
            healthy = response.status == origin["expect_status"] and len(body) <= 65536 and valid_body
            value.update(authenticated=healthy, healthy=healthy, checked_at=clock())
        except (OSError, ValueError, http.client.HTTPException):
            pass
        finally:
            connection.close()
        results.append(value)
    return results
