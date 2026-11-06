"""Bootstrap one explicitly authorized, fixed-identity OpenFlux Direct replica."""
import argparse
import configparser
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

from controller import atomic_json, trusted_json


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def sha_file(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def command(arguments):
    result = subprocess.run(arguments, capture_output=True, timeout=45)
    if result.returncode or len(result.stdout) > 1024 * 1024:
        raise RuntimeError("Owned Direct bootstrap operation failed")
    return result.stdout


def source_configuration(raw, port):
    if len(raw) > 65536:
        raise ValueError("Direct source configuration exceeds its bound")
    parsed = configparser.ConfigParser(interpolation=None, strict=True)
    parsed.optionxform = str
    parsed.read_string(raw.decode())
    if set(parsed.sections()) != {"Interface", 'Transport "direct"'} or parsed.defaults():
        raise ValueError("Direct replica must have exactly one Direct transport")
    interface = parsed["Interface"]
    if any(interface.get(key) != value for key, value in {"Role": "exit", "Mode": "l4", "Codec": "batched"}.items()):
        raise ValueError("Direct replica role, mode or codec changed")
    if not interface.get("SessionContext") or not interface.get("EncryptionKeyFile") or not interface.get("CookieStore"):
        raise ValueError("Direct source credential context is incomplete")
    if interface.get("Debug") != "0" or interface.get("Sensitive", "").lower() != "false":
        raise ValueError("Direct source sensitive logging must be disabled")
    transport = parsed['Transport "direct"']
    if transport.get("Type") != "direct" or transport.get("Listen") != "0.0.0.0:" + str(port):
        raise ValueError("Direct replica public listener changed")
    return parsed


def rewritten_configuration(raw, unit, state_root, port):
    before = source_configuration(raw, port)
    replacements = {"EncryptionKeyFile": "/run/credentials/" + unit + "/key",
                    "CookieStore": str(state_root / "cookies.json")}
    text = raw.decode()
    for key, value in replacements.items():
        pattern = r"(?m)^([ \t]*" + re.escape(key) + r"[ \t]*=[ \t]*)[^\r\n]*(?=\r?$)"
        text, count = re.subn(pattern, lambda match: match[1] + value, text)
        if count != 1:
            raise ValueError("Direct owned path rewrite is ambiguous")
    after = source_configuration(text.encode(), port)
    for section in before.sections():
        for key, value in before[section].items():
            if key not in replacements and after[section].get(key) != value:
                raise ValueError("Direct bootstrap changed a source transport parameter")
    return text.encode()


def unit_text(config):
    origin = config["origin"]
    root = Path("/etc/unifiedvpn-openflux-ha/origins") / origin
    return f"""[Unit]
Description=Owned OpenFlux Direct replica {origin}
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
DynamicUser=yes
StateDirectory=unifiedvpn-openflux-ha/{origin}
StateDirectoryMode=0700
LoadCredential=peer.conf:{root}/peer.conf
LoadCredential=key:{root}/key
ExecStart={config['binary']} --config /run/credentials/unifiedvpn-openflux-ha-{origin}.service/peer.conf --negotiate
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true
CapabilityBoundingSet=
AmbientCapabilities=
PrivateDevices=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
RestrictNamespaces=true
LockPersonality=true
MemoryMax=128M
Environment=GOMEMLIMIT=64MiB GOMAXPROCS=1
CPUQuota=50%
TasksMax=64
LimitNOFILE=4096
StandardOutput=null
StandardError=journal

[Install]
WantedBy=multi-user.target
"""


def prepared(config):
    origin = config["origin"]
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", origin):
        raise ValueError("Direct replica origin is invalid")
    if config["unit"] != "unifiedvpn-openflux-ha-" + origin + ".service":
        raise ValueError("Direct replica unit is outside its owned origin")
    if config.get("baseline_verified") is not True:
        raise ValueError("Direct bootstrap requires the completed source baseline release")
    if Path("/etc/machine-id").read_text().strip() != config["target_machine_id"]:
        raise ValueError("Direct bootstrap target machine changed")
    binary = Path(config["binary"])
    info = binary.stat()
    if not binary.is_absolute() or not binary.is_file() or info.st_uid != 0 or info.st_mode & 0o022 or sha_file(binary) != config["binary_sha256"]:
        raise ValueError("Direct bootstrap binary does not match its trusted source")
    port = config["port"]
    if type(port) is not int or not 1024 <= port <= 65535:
        raise ValueError("Direct replica port is invalid")
    files = {}
    for name in ["key", "peer.conf"]:
        source = Path(config["source_files"][name])
        metadata = source.lstat()
        if source.is_symlink() or not source.is_file() or metadata.st_uid != 0 or metadata.st_mode & 0o077 or metadata.st_size > 65536:
            raise ValueError("Direct bootstrap input is not root-private")
        files[name] = source.read_bytes()
        if sha(files[name]) != config["source_sha256"][name]:
            raise ValueError("Direct bootstrap source identity changed")
    if not re.fullmatch(rb"[a-fA-F0-9]{64}", files["key"].strip()):
        raise ValueError("Direct replica key encoding is invalid")
    if sha(files["key"].strip().lower()) != config["canonical_key_sha256"]:
        raise ValueError("Direct canonical key differs from the source attestation")
    root = Path("/etc/unifiedvpn-openflux-ha/origins") / origin
    state_root = Path("/var/lib/unifiedvpn-openflux-ha") / origin
    source = source_configuration(files["peer.conf"], port)
    if sha(source["Interface"]["SessionContext"].encode()) != config["session_context_value_sha256"]:
        raise ValueError("Direct SessionContext differs from the source attestation")
    files["peer.conf"] = rewritten_configuration(files["peer.conf"], config["unit"], state_root, port)
    unit = unit_text(config).encode()
    claim = {key: config[key] for key in ["origin", "unit", "target_machine_id", "source_machine_id", "source_device_id",
                                          "source_unit", "source_sha256", "binary", "binary_sha256", "port", "baseline_evidence_sha256",
                                          "canonical_key_sha256", "session_context_value_sha256", "source_attestation_evidence_sha256"]}
    claim.update(context_sha256=sha(source["Interface"]["SessionContext"].encode()),
                 target_sha256={name: sha(data) for name, data in files.items()}, unit_sha256=sha(unit), state_root=str(state_root))
    return root, files, unit, claim


def process_status(config):
    unit = config["unit"]
    raw = command(["systemctl", "show", unit, "-p", "MainPID,ActiveState,SubState,DynamicUser,MemoryMax,NoNewPrivileges,PrivateDevices,CapabilityBoundingSet,AmbientCapabilities,NRestarts"]).decode()
    fields = dict(line.split("=", 1) for line in raw.splitlines() if "=" in line)
    if fields.get("ActiveState") != "active" or fields.get("DynamicUser") != "yes" or fields.get("MemoryMax") != str(128 * 1024 * 1024):
        raise RuntimeError("Direct replica is not running within its resource boundary")
    if fields.get("CapabilityBoundingSet") or fields.get("AmbientCapabilities") or fields.get("PrivateDevices") != "yes" or fields.get("NoNewPrivileges") != "yes":
        raise RuntimeError("Direct replica capability boundary changed")
    pid = int(fields["MainPID"])
    status = dict(line.split(":", 1) for line in Path("/proc", str(pid), "status").read_text().splitlines() if ":" in line)
    if int(status["Uid"].split()[1]) == 0 or int(status["CapEff"].strip(), 16) != 0 or status["NoNewPrivs"].strip() != "1":
        raise RuntimeError("Direct replica is not an unprivileged process")
    if Path("/proc", str(pid), "exe").resolve() != Path(config["binary"]).resolve() or sha_file(Path("/proc", str(pid), "exe")) != config["binary_sha256"]:
        raise RuntimeError("Direct replica process is not the attested binary")
    if Path("/proc", str(pid), "root/dev/net/tun").exists():
        raise RuntimeError("Direct replica unexpectedly has a tunnel device")
    listeners = command(["ss", "-H", "-ltnp", "sport = :" + str(config["port"])]).decode().splitlines()
    if len(listeners) != 1 or "pid=" + str(pid) + "," not in listeners[0]:
        raise RuntimeError("Direct replica listener ownership is ambiguous")
    return {"pid": pid, "nonroot": True, "effective_capabilities": 0, "private_devices": True,
            "memory_limit_bytes": 128 * 1024 * 1024, "restart_count": int(fields["NRestarts"]), "listener_port": config["port"]}


def apply(config):
    root, files, unit, claim = prepared(config)
    unit_path = Path("/etc/systemd/system") / config["unit"]
    marker = root / "bootstrap.json"
    if any(path.is_symlink() for path in [root, root.parent, root.parent.parent]) or unit_path.is_symlink():
        raise RuntimeError("Direct bootstrap encountered an indirect owned path")
    if marker.exists():
        if trusted_json(marker, private=True) != claim:
            raise RuntimeError("Direct replica has a different bootstrap authority")
        if any((root / name).exists() and (not (root / name).is_file() or (root / name).is_symlink()
               or sha((root / name).read_bytes()) != claim["target_sha256"][name]) for name in files):
            raise RuntimeError("Direct replica credentials independently changed")
        if unit_path.exists() and sha(unit_path.read_bytes()) != claim["unit_sha256"]:
            raise RuntimeError("Direct replica unit independently changed")
    else:
        if unit_path.exists() or root.exists() and any(root.iterdir()):
            raise RuntimeError("Direct bootstrap encountered an unowned target resource")
        if command(["ss", "-H", "-ltn", "sport = :" + str(config["port"])]).strip():
            raise RuntimeError("Direct replica port already has a listener")
        root.mkdir(mode=0o700, parents=True, exist_ok=True)
        root.chmod(0o700)
        atomic_json(marker, claim)
    for name, data in files.items():
        destination = root / name
        if destination.exists():
            if destination.is_symlink() or sha(destination.read_bytes()) != sha(data):
                raise RuntimeError("Direct staging credential independently changed")
        else:
            descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
    if not unit_path.exists():
        unit_path.write_bytes(unit)
        unit_path.chmod(0o644)
    command(["systemd-analyze", "verify", str(unit_path)])
    command(["systemctl", "daemon-reload"])
    try:
        command(["systemctl", "start", config["unit"]])
        deadline = time.monotonic() + 12
        while True:
            try:
                status = process_status(config)
                break
            except (OSError, ValueError, RuntimeError):
                if time.monotonic() >= deadline:
                    raise RuntimeError("Direct replica did not become ready") from None
                time.sleep(.2)
        command(["systemctl", "enable", config["unit"]])
    except Exception:
        if unit_path.exists() and sha(unit_path.read_bytes()) == claim["unit_sha256"]:
            command(["systemctl", "stop", config["unit"]])
        raise
    return {"created": True, "unit": config["unit"], "origin": config["origin"], "root": str(root),
            "state_root": claim["state_root"], "binary_sha256": config["binary_sha256"],
            "source_sha256": config["source_sha256"], "target_sha256": claim["target_sha256"],
            "context_sha256": claim["context_sha256"], "unit_sha256": claim["unit_sha256"],
            "process": status, "authenticated_traffic_test_performed": False, "checked_at": time.time()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        config = trusted_json(args.config, private=True)
        if args.apply:
            result = apply(config)
        else:
            _, _, _, claim = prepared(config)
            result = {"prepared": True, "origin": config["origin"], "claim_sha256": sha(json.dumps(claim, sort_keys=True).encode())}
        print(json.dumps(result))
    except Exception as error:
        print(json.dumps({"created": False, "reason": "direct_bootstrap_held", "error_type": type(error).__name__}))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
