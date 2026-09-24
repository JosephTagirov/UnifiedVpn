#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Explicit, separate SSH deployment phases; never changes host VPN services."""

import argparse
from contextlib import nullcontext
from datetime import datetime, timezone
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import shlex
import stat
import sys
import tempfile


HERE = Path(__file__).resolve().parent


def load_module(name, filename):
    specification = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


ssh = load_module("openflux_deployment_ssh_primitives", "inspect_ssh.py")
remote = load_module("openflux_deployment_remote", "deploy_remote.py")
manager = load_module("openflux_deployment_validation", "manage.py")
BOOTSTRAP = ("import sys; n=int(sys.stdin.buffer.readline(12)); "
             "sys.exit(2) if not 0<n<=131072 else None; "
             "exec(compile(sys.stdin.buffer.read(n),'<openflux-deployment>','exec'))")
RECEIPT_SCHEMA = "unifiedvpn-openflux-deployment-receipt-v1"
TIMEOUTS = {"upload": 360, "preflight": 360, "runtime-pull": 900, "runtime-build": 900,
            "install": 1080, "run": 360, "check": 360, "stop": 360}


class DeploymentError(Exception):
    pass


def fail(code):
    raise DeploymentError(code)


def local_file(path, maximum, private=False):
    try:
        info = path.lstat()
        reparse = getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or reparse or not 0 < info.st_size <= maximum:
            fail("unsafe_local_input")
        if private and os.name != "nt" and (info.st_mode & 0o077 or info.st_uid not in (0, os.geteuid())):
            fail("unsafe_private_permissions")
        with path.open("rb") as handle:
            value = handle.read(maximum + 1)
        if len(value) > maximum:
            fail("input_too_large")
        return value
    except OSError:
        fail("local_input_unavailable")


def private_output(path, access_path, new=False):
    try:
        root = access_path.parent.resolve(strict=True)
        parent = path.absolute().parent.resolve(strict=True)
        if parent != root and root not in parent.parents:
            fail("private_output_outside_access_directory")
        if path.is_symlink() or path.absolute().parent != parent:
            fail("unsafe_private_output")
        if new and (path.exists() or path.is_symlink()):
            fail("receipt_already_exists")
        return parent / path.name
    except OSError:
        fail("private_output_unavailable")


def collect_bundle(bundle, config_path, instance="default"):
    remote.validate_instance(instance)
    sources = {"bundle/" + name: HERE / name for name in ("manage.py", "Dockerfile", "runtime.Dockerfile", "entrypoint.sh")}
    sources.update({"bundle/" + name: bundle / name for name in ("openflux-linux-amd64", "openflux-source.tar.gz", "manifest.json")})
    sources.update({"bundle/licenses/" + name: bundle / "licenses" / name for name in ("LICENSE", "NOTICE", "COPYRIGHT")})
    sources["private/server.json"] = config_path
    contents = {name: local_file(sources[name], limit, private=name.startswith("private/")) for name, limit in remote.FILES.items()}
    try:
        manager.validate_config(manager.decode_json(contents["private/server.json"]))
        manager.validate_elf(bundle / "openflux-linux-amd64")
    except manager.DeploymentError:
        fail("invalid_server_config_or_binary")
    artifact = remote.decode_json(contents["bundle/manifest.json"])
    if not isinstance(artifact, dict) or type(artifact.get("schema")) is not int or artifact.get("schema") != 1 or artifact.get("upstream") != remote.UPSTREAM:
        fail("artifact_provenance_mismatch")
    if artifact.get("protocol") != remote.PROTOCOL or artifact.get("version_text") != remote.VERSION:
        fail("artifact_provenance_mismatch")
    hashes = artifact.get("files")
    if not isinstance(hashes, dict):
        fail("artifact_provenance_mismatch")
    for name in ("openflux-linux-amd64", "openflux-source.tar.gz"):
        if hashes.get(name) != remote.hash_bytes(contents["bundle/" + name]):
            fail("artifact_hash_mismatch")
    if not contents["bundle/openflux-source.tar.gz"].startswith(b"\x1f\x8b"):
        fail("invalid_source_archive")
    manifest = {"schema": remote.SCHEMA, "upstream": remote.UPSTREAM,
                "files": {name: {"size": len(data), "sha256": remote.hash_bytes(data)} for name, data in contents.items()}}
    if instance != "default":
        manifest["instance"] = instance
    remote.validate_manifest(manifest)
    return manifest, contents


def target_hash(access):
    return remote.hash_bytes(remote.json_bytes(access))


def read_receipt(path, access):
    receipt = remote.decode_json(local_file(path, remote.MAX_METADATA, private=True))
    fields = {"schema", "stage", "manifest_sha256", "target_sha256"}
    if not isinstance(receipt, dict) or set(receipt) not in (fields, fields | {"instance"}) or receipt["schema"] != RECEIPT_SCHEMA:
        fail("invalid_receipt")
    remote.validate_instance(receipt.get("instance", "default"))
    remote.stage_path(receipt["stage"])
    if not remote.valid_hash(receipt["manifest_sha256"]) or receipt["target_sha256"] != target_hash(access):
        fail("receipt_target_mismatch")
    return receipt


def request_for(args, receipt, manifest=None):
    request = {"schema": remote.SCHEMA, "phase": args.phase, "stage": receipt["stage"],
               "manifest_sha256": receipt["manifest_sha256"], "apply": args.apply}
    if "instance" in receipt:
        request["instance"] = receipt["instance"]
    if args.phase == "upload":
        request["manifest"] = manifest
        if getattr(args, "reuse_installed_artifacts", False):
            request["reuse_installed_artifacts"] = True
    if args.phase in ("preflight", "install"):
        request.update(runtime_image=args.runtime_image, subnet=args.subnet)
    if args.phase in ("runtime-pull", "runtime-build"):
        request["alpine_image"] = args.alpine_image
        if args.phase == "runtime-pull":
            request["allow_network"] = args.allow_network
        else:
            request["allow_build_network"] = args.allow_build_network
    return remote.validate_request(request)


def framed_payload(request, contents=None):
    remote.validate_request(request)
    source = local_file(HERE / "deploy_remote.py", 131072)
    metadata = remote.json_bytes(request)
    if len(metadata) > remote.MAX_METADATA:
        fail("metadata_too_large")
    stream = io.BytesIO()
    stream.write(str(len(source)).encode("ascii") + b"\n" + source)
    stream.write(str(len(metadata)).encode("ascii") + b"\n" + metadata)
    if contents is not None:
        for name in remote.FILES:
            if request.get("reuse_installed_artifacts") is True and name in remote.REUSABLE_ARTIFACTS:
                continue
            stream.write(contents[name])
    return stream.getvalue()


def safe_response(stdout, expected_phase):
    if not isinstance(stdout, bytes) or not 0 < len(stdout) <= remote.MAX_METADATA:
        fail("unexpected_remote_response")
    response = remote.decode_json(stdout)
    if not isinstance(response, dict) or response.get("schema") != remote.SCHEMA or response.get("phase") != expected_phase:
        fail("unexpected_remote_response")
    if response.get("status") not in ("completed", "failed"):
        fail("unexpected_remote_response")
    if (response["status"] == "failed") != ("error" in response):
        fail("unexpected_remote_response")
    allowed = {"schema", "phase", "status", "error", "encrypted_peer_verified", "files_uploaded",
               "configuration_only", "runtime_image_id", "pinned_base_available"}
    if set(response) - allowed:
        fail("unexpected_remote_response")
    if response.get("error") is not None and response["error"] not in remote.ERRORS:
        fail("unexpected_remote_response")
    for flag in ("configuration_only", "pinned_base_available"):
        if flag in response and type(response[flag]) is not bool:
            fail("unexpected_remote_response")
    if "encrypted_peer_verified" in response and response["encrypted_peer_verified"] is not False:
        fail("unexpected_remote_response")
    if "files_uploaded" in response and (type(response["files_uploaded"]) is not int or response["files_uploaded"] != len(remote.FILES)):
        fail("unexpected_remote_response")
    if "runtime_image_id" in response:
        remote.validate_runtime(response["runtime_image_id"])
        if expected_phase != "runtime-build":
            fail("unexpected_remote_response")
    return response


def execute_ssh(access_path, access, endpoint, request, contents=None):
    try:
        scanner = ssh.executable("ssh-keyscan")
        scan = ssh.native([str(scanner), "-T", "15", "-p", str(endpoint["port"]), "-t", "ed25519", endpoint["host"]])
        if scan.returncode or not scan.stdout.strip() or len(scan.stdout) > remote.MAX_METADATA:
            fail("host_key_scan_failed")
        pinned = ssh.verified_host_line(scan.stdout.decode("utf-8", errors="replace"), access["host_key_sha256"])
    except ssh.InspectionError:
        fail("host_key_not_verified")
    identity = access_path.parent / "openflux-server-access"
    local_file(identity, 16384, private=True)
    payload = framed_payload(request, contents)
    try:
        with tempfile.TemporaryDirectory(prefix="openflux-deploy-ssh-", dir=access_path.parent) as directory:
            known_hosts = Path(directory) / "known_hosts"
            ssh.write_private(known_hosts, pinned.encode("ascii"))
            arguments = ssh.ssh_arguments(ssh.executable("ssh"), endpoint, identity, known_hosts)
            arguments[-1] = "python3 -c " + shlex.quote(BOOTSTRAP)
            result = ssh.native(arguments, stdin=payload, timeout=TIMEOUTS[request["phase"]])
    except ssh.InspectionError:
        # The inspection primitive's timeout text is inappropriate after a mutation.
        fail("ssh_transport_failed_remote_state_unknown")
    response = safe_response(result.stdout, request["phase"])
    if (result.returncode == 0) != (response["status"] == "completed"):
        fail("ssh_exit_status_mismatch_remote_state_unknown")
    return response


def deploy(args):
    if args.phase in remote.MUTATING and not args.apply:
        fail("apply_required")
    if args.phase == "runtime-pull" and not args.allow_network:
        fail("network_consent_required")
    if args.phase == "runtime-build" and not args.allow_build_network:
        fail("build_network_consent_required")
    try:
        access_path = args.access_file.absolute()
        access = ssh.normalize_access(remote.decode_json(local_file(access_path, 16384, private=True)))
    except ssh.InspectionError:
        fail("invalid_private_access")
    if access["user"] != "root":
        fail("dedicated_root_access_required")
    receipt_path = private_output(args.receipt_file, access_path, new=args.phase == "upload")
    manifest, contents = None, None
    if args.phase == "upload":
        manifest, contents = collect_bundle(args.bundle_dir, args.server_config, args.instance)
        receipt = {"schema": RECEIPT_SCHEMA, "stage": secrets.token_hex(16),
                   "manifest_sha256": remote.hash_bytes(remote.json_bytes(manifest)), "target_sha256": target_hash(access)}
        if args.instance != "default":
            receipt["instance"] = args.instance
        request = request_for(args, receipt, manifest)
        # Retain the target/stage binding even when an interrupted upload leaves partial files.
        ssh.write_private(receipt_path, remote.json_bytes(receipt) + b"\n")
    else:
        receipt = read_receipt(receipt_path, access)
        request = request_for(args, receipt)
    if args.socks_port is not None and args.http_proxy_port is not None:
        fail("choose_one_proxy_route")
    port = args.socks_port if args.socks_port is not None else args.http_proxy_port
    try:
        route = ssh.SocksRelay(access, port, http=args.http_proxy_port is not None) if port is not None else nullcontext(access)
        with route as endpoint:
            response = execute_ssh(access_path, access, endpoint, request, contents)
    except ssh.InspectionError:
        fail("ssh_proxy_failed_remote_state_unknown")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    report_path = access_path.parent / ("openflux-deployment-" + args.phase + "-" + stamp + ".json")
    ssh.write_private(report_path, remote.json_bytes(response) + b"\n")
    summary = {name: response[name] for name in ("phase", "status", "error", "files_uploaded", "configuration_only", "pinned_base_available") if name in response}
    summary.update(host_key_verified=True, encrypted_peer_verified=False, private_report=report_path.name)
    if "runtime_image_id" in response:
        summary["runtime_image_recorded_privately"] = True
    return summary


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        fail("invalid_arguments")


def parser():
    result = SafeArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="phase", required=True)
    for phase in remote.PHASES:
        current = commands.add_parser(phase)
        current.add_argument("--access-file", type=Path, required=True)
        current.add_argument("--receipt-file", type=Path, required=True)
        current.add_argument("--apply", action="store_true")
        proxies = current.add_mutually_exclusive_group()
        proxies.add_argument("--socks-port", type=int)
        proxies.add_argument("--http-proxy-port", type=int)
        if phase == "upload":
            current.add_argument("--instance", default="default")
            current.add_argument("--reuse-installed-artifacts", action="store_true",
                                 help="Copy matching public artifacts from the original server installation; named instances only")
            current.add_argument("--bundle-dir", type=Path, required=True)
            current.add_argument("--server-config", type=Path, required=True)
        if phase in ("preflight", "install"):
            current.add_argument("--runtime-image", required=True)
            current.add_argument("--subnet", required=True)
        if phase in ("runtime-pull", "runtime-build"):
            current.add_argument("--alpine-image", required=True)
        if phase == "runtime-pull":
            current.add_argument("--allow-network", action="store_true")
        if phase == "runtime-build":
            current.add_argument("--allow-build-network", action="store_true")
    return result


def main(argv=None):
    try:
        summary = deploy(parser().parse_args(argv))
        print(json.dumps(summary, sort_keys=True))
        return 0 if summary["status"] == "completed" else 1
    except (DeploymentError, remote.DeploymentError) as error:
        print(json.dumps({"status": "failed", "error": str(error), "remote_state_may_have_changed": True}))
    except (OSError, ValueError, EOFError, KeyboardInterrupt):
        print(json.dumps({"status": "failed", "error": "local_operation_failed", "remote_state_may_have_changed": True}))
    except Exception:
        print(json.dumps({"status": "failed", "error": "unexpected_failure", "remote_state_may_have_changed": True}))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
