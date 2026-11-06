"""Old-control callsite bridge. The fixed-source worker validates and applies it."""
import json
from pathlib import Path
import sys


def apply(resource, enabled, command, config_path="/etc/unifiedvpn-classic-ha/node.json", worker_path="/opt/unifiedvpn-failover/classic_awg_ha.py"):
    source = Path(config_path)
    metadata = source.lstat()
    if (source.is_symlink() or not source.is_file() or metadata.st_size > 65536
            or metadata.st_uid != 0 or metadata.st_mode & 0o077):
        raise ValueError("Classic mutation configuration is not root-private")
    config = json.loads(source.read_text())
    payload = resource["payload"]
    if config["source"] is not True or payload["container"] != config["resource"]["container"] or type(enabled) is not bool:
        raise ValueError("Classic mutation does not match its fixed source")
    data = {"container": payload["container"], "public": payload["public"], "block": payload["block"], "enabled": enabled}
    command([sys.executable, worker_path, "control-peer", "--config", config_path], data=json.dumps(data).encode())
