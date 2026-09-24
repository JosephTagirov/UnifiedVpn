"""Run read-only server inspection with a separately verified SSH host key.

Connection settings, identity and reports stay in the ignored private directory.
No password authentication, SSH user config, agent or forwarding is used.
"""

import argparse
import base64
from contextlib import nullcontext
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import socket
import socketserver
import subprocess
import tempfile
import threading


HOST_ALIAS = "unifiedvpn-openflux-inspection"
SCHEMA = "unifiedvpn-openflux-server-inspect-v1"


class InspectionError(Exception):
    pass


class SocksRelay:
    """A temporary, loopback-only TCP relay to one fixed SSH endpoint.

    PySocks handles SOCKS5. OpenSSH still authenticates and encrypts the SSH
    connection end to end; the proxy has no access to the SSH private key.
    """

    def __init__(self, access, proxy_port, http=False, io_timeout=15):
        try:
            import socks
        except ImportError:
            raise InspectionError("PySocks is required for the optional local-proxy route.") from None
        if type(proxy_port) is not int or not 1 <= proxy_port <= 65535:
            raise InspectionError("Invalid local SOCKS port.")
        if type(io_timeout) is not int or not 15 <= io_timeout <= 120:
            raise InspectionError("Invalid bounded relay I/O timeout.")
        self.access = access
        self.proxy_port = proxy_port
        self.socks = socks
        self.proxy_type = socks.HTTP if http else socks.SOCKS5
        self.failure = None
        self.stopping = threading.Event()
        self.lock = threading.Lock()
        self.connections = set()
        self.slots = threading.BoundedSemaphore(4)
        owner = self

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                if not owner.slots.acquire(blocking=False):
                    return
                outgoing = None
                try:
                    outgoing = owner.socks.create_connection(
                        (owner.access["host"], owner.access["port"]), timeout=15,
                        proxy_type=owner.proxy_type, proxy_addr="127.0.0.1",
                        proxy_port=owner.proxy_port, proxy_rdns=True)
                    outgoing.settimeout(io_timeout)
                    self.request.settimeout(io_timeout)
                    with owner.lock:
                        owner.connections.update((self.request, outgoing))
                    with selectors.DefaultSelector() as selector:
                        selector.register(self.request, selectors.EVENT_READ, outgoing)
                        selector.register(outgoing, selectors.EVENT_READ, self.request)
                        while selector.get_map() and not owner.stopping.is_set():
                            for key, _ in selector.select(0.5):
                                packet = key.fileobj.recv(65536)
                                if packet:
                                    key.data.sendall(packet)
                                else:
                                    selector.unregister(key.fileobj)
                                    key.data.shutdown(socket.SHUT_WR)
                except OSError as error:
                    if isinstance(error, owner.socks.SOCKS5AuthError):
                        owner.failure = "proxy_authentication_required"
                    elif isinstance(error, owner.socks.SOCKS5Error):
                        code = re.match(r"0x[0-9a-fA-F]{2}", str(error))
                        owner.failure = "proxy_rejected_" + (code.group() if code else "unknown")
                    elif isinstance(error, owner.socks.HTTPError):
                        owner.failure = "http_connect_rejected"
                    elif isinstance(error, TimeoutError):
                        owner.failure = "proxy_connection_timeout"
                    elif isinstance(error, ConnectionRefusedError):
                        owner.failure = "local_proxy_connection_refused"
                    else:
                        owner.failure = "proxy_connection_failed"
                finally:
                    with owner.lock:
                        owner.connections.discard(self.request)
                        owner.connections.discard(outgoing)
                    if outgoing is not None:
                        outgoing.close()
                    owner.slots.release()

        class Server(socketserver.ThreadingTCPServer):
            daemon_threads = True
            allow_reuse_address = False

            def handle_error(self, request, client_address):
                # Never print destination/proxy errors or addresses to the console.
                pass

        try:
            self.server = Server(("127.0.0.1", 0), Handler)
        except OSError:
            raise InspectionError("Cannot allocate a separate loopback SSH relay.") from None
        self.thread = threading.Thread(target=self.server.serve_forever,
                                       kwargs={"poll_interval": 0.1}, daemon=True)

    def __enter__(self):
        self.thread.start()
        return dict(self.access, host="127.0.0.1", port=self.server.server_address[1])

    def __exit__(self, *_):
        self.stopping.set()
        self.server.shutdown()
        with self.lock:
            for connection in self.connections:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                connection.close()
        self.server.server_close()
        self.thread.join(timeout=2)


def normalize_access(data):
    if not isinstance(data, dict) or set(data) != {"host", "port", "user", "host_key_sha256"}:
        raise InspectionError("Private access JSON has unexpected or missing fields.")
    if not all(isinstance(data[k], str) for k in ("host", "user", "host_key_sha256")):
        raise InspectionError("Private SSH access fields have invalid types.")
    host, user = data["host"].strip(), data["user"].strip()
    if not host or len(host) > 253:
        raise InspectionError("Private SSH host is invalid.")
    try:
        ipaddress.ip_address(host)
        if "%" in host:
            raise ValueError("zone not allowed")
    except ValueError:
        if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?", host):
            raise InspectionError("Private SSH host is invalid.") from None
        if any(not label or len(label) > 63 or label.startswith("-") or label.endswith("-")
               for label in host.split(".")):
            raise InspectionError("Private SSH host is invalid.")
    if not re.fullmatch(r"[a-z_][a-z0-9_-]*[$]?", user):
        raise InspectionError("Private SSH user is invalid.")
    port = data["port"]
    if type(port) is not int or not 1 <= port <= 65535:
        raise InspectionError("Private SSH port is invalid.")
    fingerprint = data["host_key_sha256"].strip()
    if re.fullmatch(r"[A-Za-z0-9+/]{43}=?", fingerprint):
        fingerprint = "SHA256:" + fingerprint
    if not re.fullmatch(r"SHA256:[A-Za-z0-9+/]{43}=?", fingerprint):
        raise InspectionError("Expected a SHA256 SSH host fingerprint from the server console.")
    return {"host": host, "user": user, "port": port,
            "host_key_sha256": fingerprint.rstrip("=")}


def verified_host_line(scan, fingerprint):
    candidates = set()
    for line in scan.splitlines():
        fields = line.split()
        if len(fields) != 3 or fields[1] != "ssh-ed25519":
            continue
        try:
            blob = base64.b64decode(fields[2], validate=True)
        except ValueError:
            continue
        actual = "SHA256:" + base64.b64encode(hashlib.sha256(blob).digest()).decode("ascii").rstrip("=")
        if actual == fingerprint:
            candidates.add(f"{HOST_ALIAS} ssh-ed25519 {fields[2]}\n")
    if len(candidates) != 1:
        raise InspectionError("SSH HOST KEY MISMATCH: authentication was not attempted.")
    return candidates.pop()


def ssh_arguments(ssh, access, identity, known_hosts):
    return [str(ssh), "-F", "none", "-T", "-a", "-x",
            "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
            "-o", "IdentityAgent=none", "-o", "PreferredAuthentications=publickey",
            "-o", "PasswordAuthentication=no", "-o", "KbdInteractiveAuthentication=no",
            "-o", "StrictHostKeyChecking=yes", "-o", "HostKeyAlgorithms=ssh-ed25519",
            "-o", f"HostKeyAlias={HOST_ALIAS}", "-o", "GlobalKnownHostsFile=none",
            "-o", f'UserKnownHostsFile="{known_hosts.as_posix()}"',
            "-o", "ClearAllForwardings=yes", "-o", "ConnectTimeout=15",
            "-o", "ConnectionAttempts=1", "-o", "ServerAliveInterval=15",
            "-o", "ServerAliveCountMax=2", "-i", str(identity),
            "-p", str(access["port"]), "-l", access["user"],
            "--", access["host"], "python3 -"]


def executable(name):
    if os.name == "nt":
        if name == "ssh-keyscan":
            # Windows OpenSSH 9.5 keyscan rejects its own advertised sntrup KEX.
            # Git's newer scanner avoids changing either server or SSH settings.
            scanner = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "Git" / "usr" / "bin" / "ssh-keyscan.exe"
            if scanner.is_file():
                return scanner
        candidate = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "OpenSSH" / (name + ".exe")
        if candidate.is_file():
            return candidate
    candidate = shutil.which(name)
    if not candidate:
        raise InspectionError("Required OpenSSH executable is unavailable.")
    return Path(candidate)


def native(args, *, stdin=None, timeout=30):
    env = os.environ.copy()
    env["SSH_ASKPASS_REQUIRE"] = "never"
    try:
        return subprocess.run(args, input=stdin, capture_output=True, timeout=timeout,
                              env=env, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    except subprocess.TimeoutExpired:
        raise InspectionError("SSH inspection timed out; no server changes were requested.") from None
    except OSError:
        raise InspectionError("Cannot launch the local SSH client.") from None


def write_private(path, data):
    with path.open("xb") as handle:
        if os.name != "nt":
            os.fchmod(handle.fileno(), 0o600)
        handle.write(data)


def inspect(access_path, verify_only=False, socks_port=None, http_proxy_port=None):
    try:
        access_path = access_path.resolve(strict=True)
        access = normalize_access(json.loads(access_path.read_text(encoding="utf-8-sig")))
    except (OSError, UnicodeError, json.JSONDecodeError):
        raise InspectionError("Cannot read valid private SSH access JSON.") from None
    if socks_port is not None and http_proxy_port is not None:
        raise InspectionError("Choose only one local proxy route.")
    proxy_port = socks_port if socks_port is not None else http_proxy_port
    route = SocksRelay(access, proxy_port, http=http_proxy_port is not None) if proxy_port is not None else nullcontext(access)
    with route as endpoint:
        try:
            return inspect_endpoint(access_path, access, endpoint, verify_only)
        except InspectionError as error:
            if isinstance(route, SocksRelay) and route.failure:
                raise InspectionError(str(error) + " Route status: " + route.failure) from None
            raise


def inspect_endpoint(access_path, access, endpoint, verify_only):
    scan = native([str(executable("ssh-keyscan")), "-T", "15", "-p", str(endpoint["port"]),
                   "-t", "ed25519", endpoint["host"]])
    if scan.returncode != 0 or not scan.stdout.strip():
        raise InspectionError("SSH host-key scan failed; authentication was not attempted.")
    pinned = verified_host_line(scan.stdout.decode("utf-8", errors="replace"), access["host_key_sha256"])
    if verify_only:
        return {"host_key_verified": True, "authentication_attempted": False}
    identity = access_path.parent / "openflux-server-access"
    if identity.is_symlink() or not identity.is_file():
        raise InspectionError("Dedicated temporary SSH identity is unavailable.")
    remote = Path(__file__).with_name("inspect_remote.py")
    if not remote.is_file():
        raise InspectionError("Read-only remote inspector is unavailable.")
    with tempfile.TemporaryDirectory(prefix="openflux-ssh-", dir=access_path.parent) as tmp:
        known_hosts = Path(tmp) / "known_hosts"
        write_private(known_hosts, pinned.encode("ascii"))
        result = native(ssh_arguments(executable("ssh"), endpoint, identity, known_hosts),
                        stdin=remote.read_bytes(), timeout=180)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    if result.returncode != 0:
        error_path = access_path.parent / f"openflux-ssh-error-{stamp}.txt"
        write_private(error_path, result.stderr[:65536])
        raise InspectionError("SSH inspection failed. Details were retained only in the private directory.")
    try:
        report = json.loads(result.stdout)
        if not isinstance(report, dict) or report.get("schema") != SCHEMA or not isinstance(report.get("summary"), dict):
            raise ValueError("unexpected response")
    except (ValueError, UnicodeError):
        raise InspectionError("SSH returned an unexpected inspection response.") from None
    report_path = access_path.parent / f"openflux-server-inspection-{stamp}.json"
    write_private(report_path, json.dumps(report, indent=2, ensure_ascii=True).encode("ascii") + b"\n")
    # Detailed IPAM/routes remain private. Only documented aggregate fields are displayed.
    allowed = {"os", "architecture", "docker_version", "docker_available", "container_count",
               "running_container_count", "network_count", "image_count", "cpu_count",
               "memory_total_mib", "memory_available_mib", "disk_free_gib", "root",
               "service_count", "python_version", "iproute2_available", "kernel", "uid",
               "docker_api_version", "resources"}
    summary = {k: v for k, v in report["summary"].items() if k in allowed}
    return {"host_key_verified": True, "read_only_inspection_complete": True,
            "private_report": report_path.name, "summary": summary}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--access-file", type=Path, required=True)
    parser.add_argument("--verify-only", action="store_true")
    proxies = parser.add_mutually_exclusive_group()
    proxies.add_argument("--socks-port", type=int, help="Use an existing SOCKS5 proxy on 127.0.0.1 without reconfiguring it")
    proxies.add_argument("--http-proxy-port", type=int, help="Use an existing HTTP CONNECT proxy on 127.0.0.1")
    args = parser.parse_args()
    try:
        print(json.dumps(inspect(args.access_file, args.verify_only, args.socks_port, args.http_proxy_port), indent=2))
        return 0
    except InspectionError as error:
        print("[openflux-inspect] " + str(error))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
