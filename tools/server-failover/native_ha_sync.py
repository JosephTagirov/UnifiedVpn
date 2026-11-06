"""Single-authority native AWG synchronization over existing strictly pinned SSH."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time


def private_json(path):
    path = Path(path)
    metadata = path.stat()
    if path.is_symlink() or metadata.st_uid != 0 or metadata.st_mode & 0o077 or metadata.st_size > 2 * 1024 * 1024:
        raise RuntimeError("Native synchronization configuration is not root-private")
    return json.loads(path.read_text())


def save(path, value):
    path = Path(path)
    descriptor, temporary = tempfile.mkstemp(prefix=".native-sync-", dir=path.parent)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            json.dump(value, stream, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def run(arguments, data=None):
    result = subprocess.run(arguments, input=data, capture_output=True, timeout=120)
    if result.returncode or len(result.stdout) > 2 * 1024 * 1024:
        raise RuntimeError("Native synchronization command was rejected")
    return result.stdout


def synchronize(config):
    origin = config["authority"]["origin"]
    if not re.fullmatch(r"[a-z][a-z0-9-]*", origin):
        raise RuntimeError("Invalid native synchronization origin")
    host = str(ipaddress.IPv4Address(config["peer_host"]))
    peer = "root@" + host
    options = ["-i", config["identity_file"], "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
               "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=" + config["known_hosts_file"],
               "-o", "HostKeyAlias=" + config["host_key_alias"], "-o", "ConnectTimeout=6"]
    worker = config["worker"]
    request = config["request"]
    pulling = config.get("direction", "push") == "pull"

    def worker_command(action, remote):
        if remote:
            command = "python3 " + shlex.quote(worker) + " " + shlex.quote(action) + " " + shlex.quote(request)
            return ["ssh", *options, peer, command]
        return ["python3", worker, action, request]

    snapshot = json.loads(run(worker_command("snapshot", pulling)))
    target = json.loads(run(worker_command("status", not pulling)))
    applied = target["manifest"]
    if (snapshot["origin"] != origin or snapshot["sourceId"] != config["authority"]["sourceId"]
            or snapshot["serverPublicKeySha256"] != config["authority"]["serverPublicKeySha256"]):
        raise RuntimeError("Native snapshot changed its configured source authority")
    if snapshot["epoch"] != applied["epoch"] or snapshot["revision"] < applied["revision"]:
        raise RuntimeError("Native snapshot would regress the applied generation")
    if snapshot["imageId"] != applied["imageId"]:
        raise RuntimeError("Native image update requires explicit maintenance")
    if snapshot["revision"] == applied["revision"]:
        if snapshot["payloadSha256"] != applied["payloadSha256"]:
            raise RuntimeError("Native same-revision payload changed")
    else:
        source = Path(snapshot["directory"])
        root = Path(config["data_root"]).resolve()
        if not source.is_absolute() or ".." in source.parts or not source.is_relative_to(root / "sources" / origin):
            raise RuntimeError("Native source snapshot is outside its owned directory")
        incoming = str(root / "incoming" / origin)
        names = [str(source / filename) for filename in snapshot["files"]]
        names.append(str(source / "manifest.json"))
        if pulling:
            Path(incoming).mkdir(mode=0o700, parents=True, exist_ok=True)
            run(["scp", "-q", *options, *[peer + ":" + name for name in names], incoming + "/"])
            for name in snapshot["files"]:
                (Path(incoming) / name).chmod(0o600)
            (Path(incoming) / "manifest.json").chmod(0o600)
        else:
            run(["ssh", *options, peer, "install -d -m 700 " + shlex.quote(incoming)])
            run(["scp", "-q", *options, *names, peer + ":" + incoming + "/"])
            run(["ssh", *options, peer, "chmod 600 " + " ".join(shlex.quote(incoming + "/" + Path(name).name) for name in names)])
        target = json.loads(run(worker_command("apply", not pulling)))
    manifest = target["manifest"]
    if manifest["revision"] != snapshot["revision"] or manifest["payloadSha256"] != snapshot["payloadSha256"]:
        raise RuntimeError("Native destination did not acknowledge the current generation")
    acknowledged_at = time.time()
    desired = {"scope": manifest["scope"], "epoch": manifest["epoch"], "revision": manifest["revision"],
               "sha256": manifest["scopeSha256"], "synchronized_at": acknowledged_at}
    if not re.fullmatch(r"[a-z][a-z0-9-]*", desired["scope"]):
        raise RuntimeError("Invalid native desired identity scope")
    desired_root = Path(config.get("desired_root", "/var/lib/unifiedvpn-failover/desired-scopes"))
    desired_root.mkdir(mode=0o700, parents=True, exist_ok=True)
    desired_path = desired_root / (desired["scope"] + ".json")
    remote_save = (
        "import os,sys,json,tempfile;from pathlib import Path;os.umask(0o077);"
        "v=json.loads(sys.stdin.buffer.read(4096));p=Path(" + repr(str(desired_path)) + ");"
        "p.parent.mkdir(mode=0o700,parents=True,exist_ok=True);"
        "f,n=tempfile.mkstemp(prefix='.native-ack-',dir=p.parent);os.fchmod(f,0o600);"
        "s=os.fdopen(f,'w');json.dump(v,s,sort_keys=True);s.flush();os.fsync(s.fileno());s.close();os.replace(n,p)"
    )
    run(["ssh", *options, peer, "python3 -c " + shlex.quote(remote_save)], json.dumps(desired).encode())
    save(desired_path, desired)
    return {"origin": origin, "epoch": manifest["epoch"], "revision": manifest["revision"],
            "scope": manifest["scope"], "scopeSha256": manifest["scopeSha256"],
            "identitySha256": manifest["identitySha256"], "peerCount": manifest["peerCount"],
            "targetName": manifest["targetName"], "targetId": manifest["targetId"], "synchronized_at": acknowledged_at}


def main():
    import fcntl
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    arguments = parser.parse_args()
    os.umask(0o077)
    config = private_json(arguments.config)
    state_path = Path(config["status"])
    state_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (state_path.parent / "native-sync.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        previous = private_json(state_path) if state_path.exists() else {}
        status = {**previous, "attemptedAt": time.time(), "healthy": False}
        try:
            results = {job["authority"]["origin"]: synchronize({**config, **job}) for job in config.get("jobs", [config])}
            status.update(jobs=results, healthy=True, verifiedAt=time.time(), reason="source_and_replica_generation_verified")
        except Exception as error:
            status.update(reason="native_sync_held", errorType=type(error).__name__)
            save(state_path, status)
            print(json.dumps({"healthy": False, "reason": "native_sync_held", "errorType": type(error).__name__}))
            raise SystemExit(1) from None
        save(state_path, status)
        print(json.dumps(status))


if __name__ == "__main__":
    main()
