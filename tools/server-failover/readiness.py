"""Authenticated data-plane checks bound to literal ingress addresses.

Only designated health-client configurations belong in this worker. Reusing a
person's active AWG peer can move that peer's endpoint and disrupt their tunnel.
"""
import argparse
import base64
import concurrent.futures
import copy
import hashlib
import hmac
import http.client
import importlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import socket
import ssl
import struct
import subprocess
import sys
import tempfile
import time

from controller import atomic_json, exclusive_state, load_json, trusted_json
from policy import fresh
from scope_verify import digest, verify


def receive(sock, count):
    result = bytearray()
    while len(result) < count:
        block = sock.recv(count - len(result))
        if not block:
            raise ConnectionError("Health tunnel closed")
        result.extend(block)
    return bytes(result)


def socks(port, host, target_port=443):
    connection = socket.create_connection(("127.0.0.1", port), timeout=10)
    try:
        connection.settimeout(10)
        connection.sendall(b"\x05\x01\x00")
        if receive(connection, 2) != b"\x05\x00":
            raise ConnectionError("Health SOCKS negotiation failed")
        encoded = host.encode("ascii")
        if len(encoded) > 253:
            raise ValueError("Health destination is too long")
        connection.sendall(b"\x05\x01\x00\x03" + bytes([len(encoded)]) + encoded + struct.pack(">H", target_port))
        reply = receive(connection, 4)
        if reply[:3] != b"\x05\x00\x00":
            raise ConnectionError("Health SOCKS connection failed")
        size = {1: 4, 4: 16}.get(reply[3])
        if size is None:
            if reply[3] != 3:
                raise ConnectionError("Health SOCKS reply is malformed")
            size = receive(connection, 1)[0]
        receive(connection, size + 2)
        return connection
    except Exception:
        connection.close()
        raise


def telegram_socks(port):
    nonce = secrets.token_bytes(16)
    body = struct.pack("<I", 0xBE7E8EF1) + nonce
    message = struct.pack("<QQI", 0, (int(time.time() * 2**32) // 4) * 4, len(body)) + body
    with socks(port, "149.154.167.51") as connection:
        connection.sendall(b"\xef" + bytes([len(message) // 4]) + message)
        size = receive(connection, 1)[0]
        if size == 127:
            size = int.from_bytes(receive(connection, 3), "little")
        if not 14 <= size <= 1024:
            raise ValueError("Telegram health response length is invalid")
        result = receive(connection, size * 4)
    return len(result) >= 56 and result[:8] == b"\0" * 8 and result[20:24] == struct.pack("<I", 0x05162463) and result[24:40] == nonce


def engine_probe(address, fixture):
    kind = fixture["engine"]
    config = copy.deepcopy(fixture["config"])
    binary = Path(fixture["binary"])
    if not binary.is_absolute() or not binary.is_file():
        raise ValueError("Health engine binary is unavailable")
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    if kind == "xray":
        if len(config.get("inbounds", [])) != 1 or config["inbounds"][0].get("protocol") != "socks":
            raise ValueError("Health engine must expose exactly one loopback SOCKS listener")
        config["inbounds"][0].update(listen="127.0.0.1", port=port)
        peers = config["outbounds"][0]["settings"]["vnext"]
        if len(peers) != 1 or config["outbounds"][0].get("protocol") != "vless":
            raise ValueError("Health VLESS configuration must have one explicit remote")
        peers[0]["address"] = address
        config["log"] = {"loglevel": "none"}
        arguments = [str(binary), "run", "-c"]
    elif kind == "sing-box":
        if fixture.get("designated_health_peer") is not True:
            raise ValueError("AWG checks require an explicitly designated health peer")
        if len(config.get("inbounds", [])) != 1 or config["inbounds"][0].get("type") != "socks":
            raise ValueError("Health engine must expose exactly one loopback SOCKS listener")
        if len(config.get("endpoints", [])) != 1:
            raise ValueError("Health AWG configuration must have one userspace endpoint")
        endpoint = config["endpoints"][0]
        if endpoint.get("system") or endpoint.get("system_interface") or len(endpoint.get("peers", [])) != 1:
            raise ValueError("Health AWG configuration must remain isolated in userspace")
        endpoint["peers"][0]["address"] = address
        config["inbounds"][0].update(listen="127.0.0.1", listen_port=port)
        config["log"] = {"disabled": True}
        arguments = [str(binary), "run", "-c"]
    else:
        raise ValueError("Unsupported health engine")
    environment = {key: value for key, value in os.environ.items() if key.lower() not in {"http_proxy", "https_proxy", "all_proxy", "no_proxy"}}
    with tempfile.TemporaryDirectory(prefix="vpn-health-") as directory:
        path = Path(directory, "client.private.json")
        atomic_json(path, config)
        options = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
        process = subprocess.Popen([*arguments, str(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=environment, **options)
        try:
            deadline = time.monotonic() + 10
            while process.poll() is None and time.monotonic() < deadline:
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=.2):
                        break
                except OSError:
                    time.sleep(.1)
            connection = http.client.HTTPSConnection("www.gstatic.com", timeout=12, context=ssl.create_default_context())
            connection._create_connection = lambda destination, timeout=None, source_address=None: socks(port, destination[0], destination[1])
            try:
                connection.request("GET", "/generate_204")
                response = connection.getresponse()
                body = response.read(4097)
                https_ok = response.status == 204 and not body
            finally:
                connection.close()
            return https_ok and telegram_socks(port)
        finally:
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(4)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(4)


def https_request(address, hostname, method, path, *, headers=None, body=None, maximum=1024 * 1024):
    connection = http.client.HTTPSConnection(hostname, timeout=12, context=ssl.create_default_context())
    connection._create_connection = lambda destination, timeout=None, source_address=None: socket.create_connection((address, destination[1]), timeout=timeout)
    try:
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        payload = response.read(maximum + 1)
        if len(payload) > maximum:
            raise ValueError("Health HTTPS response exceeds its size limit")
        return response.status, {key.lower(): value for key, value in response.getheaders()}, payload
    finally:
        connection.close()


def https_probe(address, fixture):
    status, _, body = https_request(address, fixture["hostname"], "GET", fixture["path"], headers=fixture.get("headers"))
    if status != fixture.get("expect_status", 200):
        return False
    if fixture.get("decode_base64"):
        body = base64.b64decode(b"".join(body.split()), validate=True)
    expected = fixture.get("contains", [])
    if not expected:
        raise ValueError("HTTP health checks require expected content identity")
    return all(value.encode("utf-8") in body for value in expected)


def control_modules(directory):
    source = Path(directory)
    if not source.is_absolute() or source.is_symlink():
        raise ValueError("Protocol helper source is invalid")
    if os.name == "posix" and (source.stat().st_uid != os.geteuid() or source.stat().st_mode & 0o022):
        raise ValueError("Protocol helper source is not protected")
    if str(source) not in sys.path:
        sys.path.insert(0, str(source))
    return importlib.import_module("telegram_proxy"), importlib.import_module("mtproto_faketls")


def web_probe(address, fixture):
    proxy, _ = control_modules(fixture.get("module_directory", "/opt/unifiedvpn-control"))
    hostname = fixture["hostname"]
    secret = fixture["secret"]
    capability = base64.urlsafe_b64encode(hmac.new(bytes.fromhex(secret), ("tdesktop-web-proxy-bridge-v1\n" + hostname).encode(), hashlib.sha256).digest()).decode().rstrip("=")
    token = None
    def request(method, path, body=None, extra=None, auth=None):
        headers = {"Origin": "https://" + hostname, **(extra or {})}
        if body:
            headers["Content-Type"] = "application/octet-stream"
        if auth:
            headers["Authorization"] = "Bearer " + auth
        return https_request(address, hostname, method, path, headers=headers, body=body)
    try:
        status, _, page = request("GET", "/?bridge=" + capability)
        bootstrap = re.search(rb'bootstrap="([A-Za-z0-9_-]{43})"', page)
        if status != 200 or not bootstrap:
            return False
        status, headers, body = request("POST", "/api/v1/session", proxy.frame(0x10, 0, b"\x01"), auth=bootstrap[1].decode())
        token = headers.get("x-session-token")
        if status != 200 or not token or proxy.parse_frames(body) != [(0x11, 0, b"")]:
            return False
        if headers.get("x-carrier-mode", "https") != "https":
            raise ValueError("WEB health currently supports the deployed shared HTTPS carrier")
        streams = {}
        frames = b""
        for dc in range(1, 6):
            material, decrypt, nonce = proxy.request_material(secret, dc)
            streams[dc] = {"decrypt": decrypt, "nonce": nonce, "body": b"", "ok": False}
            frames += proxy.frame(1, dc) + proxy.frame(2, dc, material)
        status, _, _ = request("POST", "/api/v1/up", frames, {"X-Up-Seq": "1"}, token)
        if status != 204:
            return False
        cursor = headers.get("x-down-cursor", "0")
        deadline = time.monotonic() + 40
        while not all(stream["ok"] for stream in streams.values()) and time.monotonic() < deadline:
            status, headers, body = request("POST", "/api/v1/down", b"", {"X-Down-Cursor": cursor}, token)
            if status not in (200, 204):
                return False
            cursor = headers.get("x-down-cursor", cursor)
            for kind, stream_id, payload in proxy.parse_frames(body):
                stream = streams.get(stream_id)
                if stream is None or stream["ok"]:
                    continue
                if kind == 3:
                    return False
                if kind == 2:
                    stream["body"] += stream["decrypt"].update(payload)
                    if len(stream["body"]) >= 4:
                        length = struct.unpack_from("<I", stream["body"])[0]
                        if not 40 <= length <= 4096:
                            return False
                        if len(stream["body"]) >= length + 4:
                            proxy.validate_response(stream["body"][4:4 + length], stream["nonce"])
                            stream["ok"] = True
        return all(stream["ok"] for stream in streams.values())
    finally:
        if token:
            try:
                request("DELETE", "/api/v1/session", auth=token)
            except (OSError, ValueError, http.client.HTTPException):
                pass


def probe(address, fixture):
    ipaddress.IPv4Address(address)
    kind = fixture["kind"]
    if kind == "engine":
        return engine_probe(address, fixture)
    if kind == "https":
        return https_probe(address, fixture)
    if kind == "telegram-web":
        return web_probe(address, fixture)
    if kind == "telegram-faketls":
        _, faketls = control_modules(fixture.get("module_directory", "/opt/unifiedvpn-control"))
        return faketls.probe_datacenters(address, fixture.get("port", 443), fixture["secret"])["ok"]
    raise ValueError("Unsupported authenticated health protocol")


def node_scopes(node):
    if node.get("local") is True:
        return verify(trusted_json(node["scope_config_file"], private=True))
    if "snapshot_file" in node:
        return trusted_json(node["snapshot_file"])
    ssh = node["ssh"]
    host = ssh["host"]
    ipaddress.IPv4Address(host)
    path = node["scope_config_file"]
    if not path.startswith("/etc/unifiedvpn-failover/") or ".." in Path(path).parts:
        raise ValueError("Remote identity scope path is outside its allowlist")
    arguments = [
        "ssh", "-i", ssh["identity_file"], "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
        "-o", "UserKnownHostsFile=" + ssh["known_hosts_file"], "-o", "HostKeyAlias=" + ssh["host_key_alias"],
        "-o", "ConnectTimeout=5", "-o", "ConnectionAttempts=1", "root@" + host,
        "test ! -d /run/archiso && python3 /opt/unifiedvpn-failover/scope_verify.py --config " + shlex.quote(path),
    ]
    started = time.monotonic()
    result = subprocess.run(arguments, capture_output=True, timeout=45)
    elapsed = time.monotonic() - started
    if result.returncode or len(result.stdout) > 128 * 1024:
        diagnostic = Path("/var/lib/unifiedvpn-failover/scope-peer-error.private.log")
        descriptor = os.open(diagnostic, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(result.stderr[:65536])
        raise ValueError("Pinned identity scope check failed")
    value = json.loads(result.stdout)
    if not isinstance(value, dict):
        raise ValueError("Pinned identity scope response is invalid")
    localize_observation(value, time.time(), elapsed)
    return value


def localize_observation(value, received_at, elapsed):
    """Preserve peer-measured ages without depending on synchronized wall clocks."""
    peer_now = value.get("observed_at")
    if not isinstance(peer_now, (int, float)):
        raise ValueError("Pinned scope response has no remote age anchor")
    for section in ["scopes", "local_services", "dataplane"]:
        for result in value.get(section, {}).values():
            for key in ["checked_at", "failure_checked_at"]:
                if key not in result:
                    continue
                timestamp = result[key]
                age = peer_now - timestamp if isinstance(timestamp, (int, float)) else -1
                result[key] = received_at - age - elapsed if age >= 0 else None
    value["observed_locally_at"] = received_at


def publish_scopes(config, observations):
    for publication in config.get("publish_local_scopes", []):
        value = observations.get(publication["node"])
        if value is None:
            continue
        destination = publication["path"]
        if not destination.startswith("/var/lib/unifiedvpn-failover/") or ".." in Path(destination).parts:
            raise ValueError("Scope publication path leaves the owned runtime directory")
        ssh = publication["ssh"]
        ipaddress.IPv4Address(ssh["host"])
        script = (
            "import json,sys;sys.path.insert(0,'/opt/unifiedvpn-failover');from controller import atomic_json;"
            "data=sys.stdin.buffer.read(131073);assert len(data)<=131072;"
            "atomic_json(" + repr(destination) + ",json.loads(data))"
        )
        arguments = [
            "ssh", "-i", ssh["identity_file"], "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "UserKnownHostsFile=" + ssh["known_hosts_file"], "-o", "HostKeyAlias=" + ssh["host_key_alias"],
            "-o", "ConnectTimeout=5", "-o", "ConnectionAttempts=1", "root@" + ssh["host"],
            "test ! -d /run/archiso && python3 -c " + shlex.quote(script),
        ]
        try:
            subprocess.run(arguments, input=json.dumps(value).encode(), capture_output=True, timeout=12, check=True)
        except (OSError, subprocess.SubprocessError):
            pass


def produce(config, *, clock=time.time):
    expected = copy.deepcopy(config["scopes"])
    sync_times = copy.deepcopy(config.get("synchronized_at", {}))
    desired_files = {}
    for scope, path in config.get("scope_manifests", {}).items():
        desired = trusted_json(path)
        if desired.get("scope") != scope:
            raise ValueError("Desired identity scope changed")
        expected[scope] = {key: desired[key] for key in ["epoch", "revision", "sha256"]}
        sync_times[scope] = desired.get("synchronized_at", 0)
        desired_files[scope] = desired
    manifest = {
        "epoch": config["epoch"] + sum(value["epoch"] for value in expected.values()),
        "revision": config["revision"] + sum(value["revision"] for value in expected.values()),
        "sha256": digest(expected), "scopes": expected,
    }
    readiness = {}
    observations = {}
    def one_case(node_id, protocol, fixture):
        address = config["nodes"][node_id]["address"]
        result = {"address": address, "authenticated": False, "healthy": False, "checked_at": clock()}
        try:
            healthy = probe(address, fixture)
            result.update(authenticated=(healthy is True), healthy=(healthy is True), checked_at=clock())
        except (OSError, ValueError, TypeError, KeyError, ImportError, http.client.HTTPException, subprocess.SubprocessError):
            result["reason"] = "authenticated_probe_unverified"
        service = observations.get(node_id, {}).get("local_services", {}).get(protocol, {})
        if result["healthy"] is not True and service.get("status") == "confirmed_failed":
            result.update(
                confirmed_failed=True, failure_class=service["failure_class"],
                failure_receipt=service["receipt_id"], failure_checked_at=service["checked_at"],
                manifest_sha256=manifest["sha256"],
            )
        return node_id, protocol, result
    for node_id, node in config["nodes"].items():
        applied = False
        checked_at = clock()
        scope_error = None
        try:
            observed = node_scopes(node)
            if observed.get("address") == node["address"]:
                observations[node_id] = observed
            applied = observed.get("address") == node["address"] and all(
                observed.get("scopes", {}).get(scope, {}).get("applied") is True
                and all(observed["scopes"][scope].get(key) == spec[key] for key in ["epoch", "revision", "sha256"])
                and fresh(observed["scopes"][scope].get("checked_at"), clock(), 120)
                for scope, spec in expected.items()
            )
        except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError) as error:
            scope_error = type(error).__name__
        inherited = observations.get(node_id, {}).get("dataplane", {}) if node.get("local") is not True else {}
        readiness[node_id] = {"address": node["address"], "identity_sync": {**manifest, "applied": applied, "checked_at": 0}, "dataplane": inherited}
        readiness[node_id]["scope_checks"] = {
            scope: {
                "applied": observations.get(node_id, {}).get("scopes", {}).get(scope, {}).get("applied"),
                "fresh": fresh(observations.get(node_id, {}).get("scopes", {}).get(scope, {}).get("checked_at"), clock(), 120),
                "matches": all(observations.get(node_id, {}).get("scopes", {}).get(scope, {}).get(key) == spec[key] for key in ["epoch", "revision", "sha256"]),
            } for scope, spec in expected.items()
        }
        if scope_error:
            readiness[node_id]["scope_error_type"] = scope_error
    for scope in config.get("comparison_ack_scopes", []):
        matched = all(
            observations.get(node, {}).get("scopes", {}).get(scope, {}).get("applied") is True
            and observations[node]["scopes"][scope].get("sha256") == expected[scope]["sha256"]
            and fresh(observations[node]["scopes"][scope].get("checked_at"), clock(), 120)
            for node in config["nodes"]
        )
        if matched:
            desired_files[scope]["synchronized_at"] = clock()
            atomic_json(config["scope_manifests"][scope], desired_files[scope])
            sync_times[scope] = desired_files[scope]["synchronized_at"]
    known_authority_drift = any(
        isinstance(observations.get(authority, {}).get("scopes", {}).get(scope, {}).get("sha256"), str)
        and fresh(observations[authority]["scopes"][scope].get("checked_at"), clock(), 120)
        and observations[authority]["scopes"][scope]["sha256"] != expected[scope]["sha256"]
        for scope, authority in config.get("scope_authorities", {}).items()
    )
    for value in readiness.values():
        value["identity_sync"]["checked_at"] = min((sync_times.get(scope, 0) for scope in expected), default=0)
        value["identity_sync"]["applied"] = value["identity_sync"]["applied"] and not known_authority_drift
    publish_scopes(config, observations)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        jobs = [
            pool.submit(one_case, node_id, protocol, fixture)
            for node_id, node in config["nodes"].items() for protocol, fixture in node.get("probes", {}).items()
        ]
        for job in concurrent.futures.as_completed(jobs):
            node_id, protocol, result = job.result()
            readiness[node_id]["dataplane"][protocol] = result
    return manifest, readiness


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--manifest-output", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        with exclusive_state(args.output):
            config = trusted_json(args.config, private=True)
            manifest, readiness = produce(config)
            if Path(args.manifest_output).exists():
                previous = load_json(args.manifest_output)
                old_version = (previous["epoch"], previous["revision"])
                new_version = (manifest["epoch"], manifest["revision"])
                if new_version < old_version or (new_version == old_version and manifest["sha256"] != previous["sha256"]):
                    raise ValueError("Identity manifest revision was reused or moved backward")
                for scope, old_scope in previous.get("scopes", {}).items():
                    current = manifest["scopes"].get(scope)
                    if current is None or (current["epoch"], current["revision"]) < (old_scope["epoch"], old_scope["revision"]):
                        raise ValueError("An identity scope was removed or moved backward")
            atomic_json(args.manifest_output, manifest)
            atomic_json(args.output, readiness)
            print(json.dumps({"checked_nodes": len(readiness), "synchronized_nodes": sum(item["identity_sync"]["applied"] for item in readiness.values()), "authenticated_probes": sum(probe["healthy"] for node in readiness.values() for probe in node["dataplane"].values())}))
    except (OSError, ValueError, TypeError, KeyError, BlockingIOError):
        print(json.dumps({"reason": "readiness_unverified"}))


if __name__ == "__main__":
    main()
