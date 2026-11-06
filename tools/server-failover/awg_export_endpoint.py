"""Select an owned AWG endpoint by local machine, container and server key."""
import hashlib
import json
import os
from pathlib import Path
import re


def target(container, server_public_key):
    configured = os.environ.get("UNIFIEDVPN_AWG_ENDPOINTS_FILE")
    if not configured:
        return None
    path = Path(configured)
    info = path.lstat()
    if path.is_symlink() or not path.is_file() or info.st_uid != 0 or info.st_mode & 0o077 or info.st_size > 65536:
        raise ValueError("AWG endpoint configuration is not root-private")
    settings = json.loads(path.read_text())
    if settings["machine_id"] != Path("/etc/machine-id").read_text().strip():
        raise ValueError("AWG endpoint configuration belongs to another source node")
    public_hash = hashlib.sha256(server_public_key.encode()).hexdigest()
    matches = [item for item in settings["endpoints"]
               if item["container"] == container and item["server_public_key_sha256"] == public_hash]
    if not matches:
        return None
    if len(matches) != 1:
        raise ValueError("AWG endpoint origin is ambiguous")
    item = matches[0]
    if (not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", item["host"])
            or type(item["port"]) is not int
            or item["port"] != {"primary": 18443, "old": 28443, "classic-old": 55424}.get(item["origin"])):
        raise ValueError("AWG endpoint violates its fixed origin mapping")
    return item["host"] + ":" + str(item["port"])


def endpoint(container, server_public_key, default):
    return target(container, server_public_key) or default


def portable(text, container):
    if not container:
        return text
    peers = re.split(r"(?im)^\s*\[Peer\]\s*$", text)
    if len(peers) != 2:
        return text
    public = re.findall(r"(?im)^\s*PublicKey\s*=\s*([^\s#]+)", peers[1])
    if len(public) != 1:
        return text
    selected = target(container, public[0])
    if selected is None:
        return text
    pattern = r"(?im)^([ \t]*Endpoint[ \t]*=[ \t]*)(?:\[[^\]\r\n]+\]|[^\s:#]+):[0-9]{1,5}([ \t]*(?:#[^\r\n]*)?)(?=\r?$)"
    updated, count = re.subn(pattern, lambda match: match[1] + selected + match[2], text)
    if count != 1:
        raise ValueError("Owned AWG export must have exactly one endpoint")
    return updated
