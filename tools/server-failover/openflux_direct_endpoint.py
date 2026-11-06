"""Rewrite only an explicitly owned DIRECT export's dial endpoint."""
import hashlib
import json
import os
from pathlib import Path
import re


def select(session, device_id, settings, machine_id):
    if settings["machine_id"] != machine_id:
        raise ValueError("DIRECT endpoint registry belongs to another node")
    secret = session.get("secret")
    if not isinstance(secret, str):
        raise ValueError("DIRECT export has no identity")
    key_hash = hashlib.sha256(secret.strip().lower().encode()).hexdigest()
    matches = [item for item in settings["profiles"] if item["device_id"] == device_id and item["canonical_key_sha256"] == key_hash]
    if not matches:
        return session
    if len(matches) != 1:
        raise ValueError("DIRECT endpoint registry is ambiguous")
    profile = matches[0]
    transports = session.get("transports")
    if not isinstance(transports, list) or len(transports) != 1 or transports[0].get("type") != "direct":
        raise ValueError("Owned DIRECT export contains another transport")
    dial = transports[0].get("dial", "")
    if not dial.rsplit(":", 1)[-1].isdecimal() or int(dial.rsplit(":", 1)[-1]) != profile["port"]:
        raise ValueError("DIRECT export port differs from its fixed identity")
    host = profile["host"]
    if (not isinstance(host, str) or len(host) > 253 or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", host)
            or type(profile["port"]) is not int or not 1024 <= profile["port"] <= 65535):
        raise ValueError("DIRECT export endpoint is invalid")
    updated = dict(session)
    updated["transports"] = [dict(transports[0], dial=host + ":" + str(profile["port"]))]
    return updated


def private_settings(configured):
    path = Path(configured)
    info = path.lstat()
    if path.is_symlink() or not path.is_file() or info.st_uid != 0 or info.st_mode & 0o077 or info.st_size > 65536:
        raise ValueError("DIRECT endpoint registry is not root-private")
    return json.loads(path.read_text())


def rewrite(session, device_id):
    configured = os.environ.get("UNIFIEDVPN_OPENFLUX_ENDPOINTS_FILE")
    if not configured:
        return session
    return select(session, device_id, private_settings(configured), Path("/etc/machine-id").read_text().strip())


def reservation_set(settings, machine_id):
    if settings["machine_id"] != machine_id:
        raise ValueError("DIRECT port reservation belongs to another node")
    ports = settings["reserved_ports"]
    if not isinstance(ports, list) or any(type(port) is not int or not 50010 <= port < 50110 for port in ports) or len(set(ports)) != len(ports):
        raise ValueError("DIRECT port reservation is invalid")
    return set(ports)


def reserved_ports():
    configured = os.environ.get("UNIFIEDVPN_OPENFLUX_RESERVED_PORTS_FILE")
    if not configured:
        return set()
    return reservation_set(private_settings(configured), Path("/etc/machine-id").read_text().strip())
