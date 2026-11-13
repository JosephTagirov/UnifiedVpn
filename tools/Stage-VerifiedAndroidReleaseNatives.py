#!/usr/bin/env python3
"""Stage unchanged Android Go engines from the SHA-256-pinned official 0.1.8 APK.

This reuses nine released executables and their notices, not Java/Kotlin, DEX,
the olcRTC AAR, or the two C/JNI tunnel libraries. Gradle must still build the
current app/AAR and the C/JNI libraries with the pinned Android NDK and run its
normal artifact and APK verification tasks. No build task should be excluded.

The manifests describe verified artifact reuse, not a fresh native build.
Native-input fingerprints match release-source commit 65c35fe6b2624e0e47f47cbf0634a5f06b0e79fe.
The APK's recorded project revision is older and is not used as native proof.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import struct
import sys
import urllib.request
import uuid
import zipfile


APK_NAME = "UnifiedVPN-0.1.8-build.2026110305-android-universal.apk"
APK_URL = "https://github.com/JosephTagirov/UnifiedVpn/releases/download/v0.1.8/" + APK_NAME
APK_SIZE = 205798006
APK_SHA256 = "23d872cdbbdcc132c8f926325d70d47dd651234a5398a0632fb59821bcf99932"
BASELINE_CERT_SHA256 = "0a8547df6c99acfc4cbf9ca0916fbf7301c648bf4aecc655f7904d43c4bbeaa0"
SOURCE_SNAPSHOT = "65c35fe6b2624e0e47f47cbf0634a5f06b0e79fe"
PINS = {
    "olcbox.olcrtcSha": "5b1e6172a9f620d477624c6575c392e70449d333",
    "olcbox.awgCoreSha": "b4be66275d73b339647736d5276aa6b729d399d3",
    "olcbox.awgWireguardSha": "f60e4eda0d0839dcee8350a186f622912cd4d93e",
    "olcbox.xrayVersion": "26.10.31",
    "olcbox.xraySha": "a4a3dff5b20c2faae567b742c172c7b95e75c083",
    "olcbox.openFluxSha": "d34dc8caa70ca059cd80d8f5753499361052dabc",
}
NATIVE_SOURCE_HASHES = {
    "tools/openflux": (36, "b8e33f276e9ca0cfb0e62b9b6f4973ea526c8faaf6a7f9852ddecf5c7fb4ce7e"),
    "tools/openflux-session": (11, "33542ef074c56dce38b772e053a5390cb785ca719502b4de4bbd1c0f81d13517"),
    "tools/vkcalls": (1, "dd2212b3050c5807ac8569a3b12cc855a81024476c49c57874988a1b3583b2d8"),
}
SESSION_UPSTREAM = "f492a1a33b7581ad8f88118f97e0b866adda3cdc"
ABIS = {"armeabi-v7a": (1, 40, "arm"), "arm64-v8a": (2, 183, "arm64"), "x86_64": (2, 62, "amd64")}
NATIVE_HASHES = {
    "armeabi-v7a/libopenflux.so": "fa9ae55d29ab3caaac6adb2fce53e6b97fa8d405012c65469d1e8d252c0ea893",
    "armeabi-v7a/libopenflux_session.so": "85d8858df99231f40d692e87a1bda4b146717d02d857a94f77482ca19bc78f7f",
    "armeabi-v7a/libvkcalls.so": "0f1f67e56ac4afbd6d38d0c143bfcc998dce7a75da1f1cbe3f362e95db35efb6",
    "arm64-v8a/libopenflux.so": "70f0f4117a4715b54d479414c31d566c35f0ba8c3929d8b1144e7c93bd5e884f",
    "arm64-v8a/libopenflux_session.so": "3695d8bd1387b4c67e0cd2b578d4a43e6afd6856d9c8673e32b9e8e95e367038",
    "arm64-v8a/libvkcalls.so": "783348fa3a8837285196d1d1e2809e7ce226f859f017719f8a7c9eadef9c3ba6",
    "x86_64/libopenflux.so": "bfe175343da8a2c0ec82af2a281e46a9fb1489406a8e49535dcf739e94ddadfe",
    "x86_64/libopenflux_session.so": "81ddb2e3d6bbed0099755c982f223078207fb5e0c879ad3eb2bb47a3d84aa4ad",
    "x86_64/libvkcalls.so": "57489c66c04281add586fc2eaf6f47a88929f8c30b09d4576668fd10c00edc07",
}
NOTICE_HASHES = {
    "LICENSE": "0ddd54d7c41176f692863282671e549c0cf4e1a564d0a4dc38c153af1a085696",
    "NOTICE": "7650228b163f2c8cc261e506f8f386db440b9c631bb7f129d39e5369f328bf8a",
}
ARTIFACT_DIRS = {
    "openflux": ".downloads/openflux/artifacts-wrapper9-20261103-stop",
    "session": ".downloads/openflux/session-app-20261103-stop",
    "vkcalls": ".downloads/vkcalls/artifacts-5b1e617",
}


def require(value: bool, message: str) -> None:
    if not value:
        raise RuntimeError(message)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def no_link(path: Path) -> None:
    require(not path.is_symlink(), f"Symbolic link rejected: {path}")
    if hasattr(path, "is_junction"):
        require(not path.is_junction(), f"Junction rejected: {path}")


def inside_downloads(project: Path, path: Path) -> Path:
    downloads = project / ".downloads"
    for ancestor in (project, downloads, *path.parents):
        if ancestor == project or project in ancestor.parents:
            no_link(ancestor)
    no_link(path)
    resolved = path.resolve()
    require(resolved != downloads.resolve() and downloads.resolve() in resolved.parents,
            "Artifact output escaped project .downloads")
    return resolved


def check_sources(project: Path) -> None:
    values: dict[str, list[str]] = {}
    for line in (project / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values.setdefault(key, []).append(value)
    for key, expected in PINS.items():
        require(values.get(key) == [expected], f"Native pin changed: {key}; build natives normally")
    for relative, (expected_count, expected_hash) in NATIVE_SOURCE_HASHES.items():
        digest = hashlib.sha256()
        count = 0
        for path in sorted((project / relative).rglob("*")):
            if path.is_file() and path.suffix in {".go", ".ps1", ".mod", ".sum", ".patch"}:
                no_link(path)
                data = path.read_bytes().replace(b"\r\n", b"\n")
                name = path.relative_to(project).as_posix().encode("utf-8")
                digest.update(name + b"\0" + str(len(data)).encode("ascii") + b"\0" + data)
                count += 1
        require((count, digest.hexdigest()) == (expected_count, expected_hash),
                f"Native wrapper inputs changed: {relative}; build natives normally")
    build = (project / "androidApp/build.gradle.kts").read_text(encoding="utf-8")
    for marker in (*ARTIFACT_DIRS.values(), 'val expectedOpenFluxWrapper = 9',
                   f'val expectedOpenFluxSessionUpstream = "{SESSION_UPSTREAM}"',
                   'keepDebugSymbols += "**/libopenflux.so"',
                   'keepDebugSymbols += "**/libopenflux_session.so"',
                   'keepDebugSymbols += "**/libvkcalls.so"'):
        require(marker in build, "Android native packaging contract changed; review artifact reuse")


def fetch_apk(project: Path, apk: Path, verify_only: bool) -> None:
    if not apk.is_file():
        require(not verify_only, "Pinned APK is missing in verify-only mode")
        inside_downloads(project, apk)
        apk.parent.mkdir(parents=True, exist_ok=True)
        partial = apk.with_name(apk.name + "." + uuid.uuid4().hex + ".partial")
        try:
            with urllib.request.urlopen(APK_URL, timeout=30) as response, partial.open("xb") as output:
                total = 0
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    require(total <= APK_SIZE, "Release APK exceeds its pinned size")
                    output.write(chunk)
            require(sha256_file(partial) == APK_SHA256, "Downloaded APK SHA-256 mismatch")
            partial.replace(apk)
        finally:
            if partial.is_file():
                partial.unlink()
    no_link(apk)
    require(apk.stat().st_size == APK_SIZE and sha256_file(apk) == APK_SHA256,
            "Official 0.1.8 APK does not match its pinned size/SHA-256")


def archive_bytes(archive: zipfile.ZipFile, name: str) -> bytes:
    matches = [entry for entry in archive.infolist() if entry.filename == name]
    require(len(matches) == 1, f"Missing or duplicate release entry: {name}")
    entry = matches[0]
    require(0 < entry.file_size <= 128 * 1024 * 1024 and
            stat.S_IFMT(entry.external_attr >> 16) != stat.S_IFLNK,
            f"Invalid release entry: {name}")
    return archive.read(entry)


def check_native(relative: str, data: bytes) -> None:
    require(hashlib.sha256(data).hexdigest() == NATIVE_HASHES[relative],
            f"Released native checksum mismatch: {relative}")
    abi, name = relative.split("/")
    elf_class, machine, arch = ABIS[abi]
    require(len(data) >= 20 and data[:4] == b"\x7fELF" and data[4] == elf_class and data[5] == 1
            and struct.unpack_from("<H", data, 18)[0] == machine,
            f"Wrong ELF architecture: {relative}")
    markers = [b"GOOS=android", b"GOARCH=" + arch.encode("ascii"), b"-trimpath=true"]
    if name == "libvkcalls.so":
        markers += [b"vcs.revision=" + PINS["olcbox.olcrtcSha"].encode("ascii"), b"vcs.modified=false"]
    elif name == "libopenflux.so":
        markers += [b"unified-openflux 9", PINS["olcbox.openFluxSha"].encode("ascii"),
                    b"unified-openflux-aesgcm-v1"]
    else:
        markers += [b"unified-openflux-session 4", SESSION_UPSTREAM.encode("ascii"),
                    b"upstream-openflux-session"]
    require(all(marker in data for marker in markers), f"Native version/build metadata mismatch: {relative}")


def stage(project: Path, apk: Path, verify_only: bool) -> dict:
    check_sources(project)
    fetch_apk(project, apk, verify_only)
    resources: dict[str, dict[str, bytes]] = {group: {} for group in ARTIFACT_DIRS}
    with zipfile.ZipFile(apk) as archive:
        for relative in NATIVE_HASHES:
            data = archive_bytes(archive, "lib/" + relative)
            check_native(relative, data)
            group = "vkcalls" if relative.endswith("/libvkcalls.so") else (
                "session" if relative.endswith("/libopenflux_session.so") else "openflux")
            resources[group]["android/" + relative] = data
        for name, expected in NOTICE_HASHES.items():
            data = archive_bytes(archive, "assets/vkcalls/" + name)
            require(hashlib.sha256(data).hexdigest() == expected, f"Native notice mismatch: {name}")
            resources["vkcalls"][name] = data
    provenance = {"kind": "verified-release-artifact-reuse", "release": "v0.1.8/build.2026110305",
                  "apk_sha256": APK_SHA256, "reviewed_native_source_snapshot": SOURCE_SNAPSHOT}
    manifests = {
        "openflux": {"schema": 1, "upstream": PINS["olcbox.openFluxSha"],
                     "protocol": "unified-openflux-aesgcm-v1",
                     "version_text": f"unified-openflux 9 upstream={PINS['olcbox.openFluxSha']} protocol=unified-openflux-aesgcm-v1"},
        "session": {"schema": 1, "upstream": SESSION_UPSTREAM, "protocol": "upstream-openflux-session",
                    "version_text": f"unified-openflux-session 4 upstream={SESSION_UPSTREAM} protocol=upstream-openflux-session"},
        "vkcalls": {"schema": 1, "revision": PINS["olcbox.olcrtcSha"]},
    }
    for group, files in resources.items():
        manifests[group]["files"] = {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}
        manifests[group]["artifact_origin"] = provenance
    if not verify_only:
        scratch = inside_downloads(project, project / ".downloads" / (".android-native-stage-" + uuid.uuid4().hex))
        scratch.mkdir(parents=True)
        installed: list[tuple[Path, Path | None]] = []
        try:
            for group, files in resources.items():
                directory = scratch / group
                for relative, data in files.items():
                    destination = directory / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(data)
                (directory / "manifest.json").write_text(json.dumps(manifests[group], indent=2) + "\n", encoding="utf-8")
            for group, relative in ARTIFACT_DIRS.items():
                destination = inside_downloads(project, project / relative)
                destination.parent.mkdir(parents=True, exist_ok=True)
                backup = None
                if destination.exists():
                    backup = inside_downloads(project, destination.with_name(destination.name + ".previous-" + uuid.uuid4().hex))
                    destination.replace(backup)
                try:
                    (scratch / group).replace(destination)
                except BaseException:
                    if backup is not None:
                        backup.replace(destination)
                    raise
                installed.append((destination, backup))
        except BaseException:
            for destination, backup in reversed(installed):
                moved = inside_downloads(project, scratch / ("failed-" + destination.name))
                destination.replace(moved)
                if backup is not None:
                    backup.replace(destination)
            raise
        finally:
            resolved = inside_downloads(project, scratch)
            require(resolved.parent == (project / ".downloads").resolve()
                    and resolved.name.startswith(".android-native-stage-"), "Unsafe temporary cleanup target")
            shutil.rmtree(resolved)
    return {"verified": True, "staged": not verify_only, "native_executables": len(NATIVE_HASHES),
            "notices": len(NOTICE_HASHES), "artifact_directories": ARTIFACT_DIRS,
            "apk_sha256": APK_SHA256, "baseline_signing_certificate_sha256": BASELINE_CERT_SHA256,
            "origin": provenance, "excluded_gradle_tasks": []}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="Existing official 0.1.8 APK (default: ignored release-baseline cache)")
    parser.add_argument("--verify-only", action="store_true", help="Verify an existing APK without writing files")
    args = parser.parse_args()
    project = Path(__file__).resolve().parent.parent
    apk = args.apk.resolve() if args.apk else project / ".downloads/release-baseline" / APK_NAME
    print(json.dumps(stage(project, apk, args.verify_only), sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"Android native staging failed: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
