"""Verify immutable, already-signed 0.1.9 assets; never receives a signing key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request

REPO = "JosephTagirov/UnifiedVpn"
TAG = "v0.1.9"
MARKER = "<!-- unifiedvpn-publisher:019-20261113 -->"
CERT = "0a8547df6c99acfc4cbf9ca0916fbf7301c648bf4aecc655f7904d43c4bbeaa0"
PROOF = "verification-build-2026111301-android.json"
PROOF_SHA = "e7ccd4e53a2699be54c8f8090b394ef3ea1b11bc40a97684c561263d7759df69"
MANIFEST = "SHA256-build-2026111301.json"
BINARIES = {
    "UnifiedVPN-0.1.9-build.2026111301-android-universal.apk": (
        "ad53833cdce4701aa1e6eeee52f1e60e0e799d8b6ae74d574e3e936f1d148e85", 205794899),
    "UnifiedVPN-0.1.9-build.2026111301-windows-amd64-installer.exe": (
        "2e26cfd4d46353f6d1e51d948625fe4f4ef560a92f188d28920f16ea23ba0a73", 197019725),
    "UnifiedVPN-0.1.9-build.2026111301-portable-manual.zip": (
        "3402bbae0edbdf2f72e3cdbe7f5df486f0bdea6dd196d8595c8b68ca416c61a3", 195283606),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def run_tool(command):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace", timeout=120)
    require(result.returncode == 0, "Verification tool failed:\n" + result.stdout[-6000:])
    return result.stdout


def verify_apk(path, sdk):
    apk_sha, apk_size = BINARIES[path.name]
    require(path.is_file() and not path.is_symlink(), "APK is missing or symlinked")
    require(path.stat().st_size == apk_size and sha256(path) == apk_sha, "Signed APK hash/size mismatch")
    suffix = ".exe" if os.name == "nt" else ""
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java" + suffix)
    signature = run_tool([str(java), "-Duser.language=en", "-Duser.country=US", "-jar",
                          str(sdk / "lib/apksigner.jar"), "verify", "--verbose", "--print-certs", str(path)])
    certs = re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$", signature)
    require(len(certs) == 1 and certs[0].replace(":", "").lower() == CERT,
            "APK does not have the original 0.1.8 signer")
    for scheme in (1, 2, 3):
        require(re.search(rf"(?m)^Verified using v{scheme} scheme.*:\s*true\s*$", signature),
                f"APK v{scheme} signature is missing")
    metadata = run_tool([str(sdk / ("aapt" + suffix)), "dump", "badging", str(path)])
    package = re.search(r"(?m)^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", metadata)
    require(package and package.groups() == ("app.unifiedvpn.local", "2026111301", "0.1.9"),
            "APK package/version mismatch")
    require("application-debuggable" not in metadata, "Release APK is debuggable")
    run_tool([str(sdk / ("zipalign" + suffix)), "-c", "-P", "16", "4", str(path)])


def verify_assets(directory, sdk):
    allowed = set(BINARIES) | {PROOF, MANIFEST}
    require(directory.is_dir(), "Asset directory is missing")
    require(all(p.is_file() and not p.is_symlink() and p.name in allowed for p in directory.iterdir()),
            "Unexpected asset directory contents")
    for name, (digest, size) in BINARIES.items():
        path = directory / name
        require(path.is_file() and path.stat().st_size == size and sha256(path) == digest,
                "Binary hash/size mismatch: " + name)
    proof_path = directory / PROOF
    require(proof_path.is_file(), "Android verification record is missing")
    proof_bytes = proof_path.read_bytes().replace(b"\r\n", b"\n")
    require(hashlib.sha256(proof_bytes).hexdigest() == PROOF_SHA, "Android verification record mismatch")
    proof = json.loads(proof_bytes)
    require(proof["sha256"] == BINARIES[proof["artifact"]][0] and proof["sizeBytes"] == 205794899,
            "Android record identifies a different artifact")
    require(proof["signing"]["certificateSha256"] == CERT and proof["previousCertificateSha256"] == CERT
            and proof["signing"]["signerCount"] == 1, "Android certificate record mismatch")
    require(proof["packageManagerUpdateRequirementsSatisfied"] is True
            and proof["privateSigningKeyIncluded"] is False
            and proof["physicalDeviceUpgradeTestPerformed"] is False, "Unexpected Android verification flags")
    verify_apk(directory / proof["artifact"], sdk)
    proof_path.write_bytes(proof_bytes)
    manifest = {name: item[0] for name, item in sorted(BINARIES.items())}
    (directory / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"verified": True, "binaryCount": 3, "privateSigningKeyReceived": False}))


def api(endpoint):
    require(os.environ.get("GITHUB_REPOSITORY") == REPO, "Unexpected publication repository")
    token = os.environ.get("GH_TOKEN")
    require(bool(token), "GitHub publication token is missing")
    request = urllib.request.Request("https://api.github.com/repos/" + REPO + endpoint,
        headers={"Accept": "application/vnd.github+json", "Authorization": "Bearer " + token,
                 "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "UnifiedVPN-019-publisher"})
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise RuntimeError("GitHub metadata request failed with HTTP " + str(error.code)) from None


def target_sha():
    value = os.environ.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[0-9a-f]{40}", value), "Invalid publication commit")
    return value


def release_by_tag():
    release = api("/releases/tags/" + TAG)
    if release:
        return release
    # Authenticated listing also covers a draft whose tag is not materialized yet.
    releases = api("/releases?per_page=100")
    require(isinstance(releases, list), "GitHub release listing failed")
    matches = [item for item in releases if item["tag_name"] == TAG]
    require(len(matches) <= 1, "Multiple releases share the target tag")
    require(matches or len(releases) < 100, "Release listing is ambiguous; refusing publication")
    return matches[0] if matches else None


def owned_draft(release):
    require(release["draft"] is True, "Stable v0.1.9 already exists; refusing to overwrite it")
    require(release["target_commitish"] == target_sha() and MARKER in (release.get("body") or ""),
            "Existing draft belongs to another publication; refusing to modify it")


def preflight():
    release = release_by_tag()
    ref = api("/git/ref/tags/" + TAG)
    if release:
        owned_draft(release)
    else:
        require(ref is None, "Tag v0.1.9 already exists without an owned draft")
    if ref:
        require(ref["object"]["type"] == "commit" and ref["object"]["sha"] == target_sha(),
                "Existing v0.1.9 tag points to another commit")
    print(json.dumps({"draft_exists": bool(release)}))


def missing_assets(release, expected):
    seen = set()
    for asset in release["assets"]:
        name = asset["name"]
        require(name in expected and name not in seen, "Unexpected or duplicate remote asset")
        seen.add(name)
        digest, size = expected[name]
        require(asset["state"] == "uploaded" and asset["size"] == size
                and asset.get("digest") == "sha256:" + digest, "Remote asset mismatch: " + name)
    return sorted(set(expected) - seen)


def verify_remote(directory, complete, published):
    release = release_by_tag()
    require(release is not None, "Release draft is missing")
    if published:
        require(release["draft"] is False and release["prerelease"] is False,
                "Release was not published as stable")
        require(release["target_commitish"] == target_sha(), "Published release commit changed")
        latest = api("/releases/latest")
        require(latest and latest["tag_name"] == TAG, "v0.1.9 is not the latest stable release")
        ref = api("/git/ref/tags/" + TAG)
        require(ref and ref["object"]["type"] == "commit" and ref["object"]["sha"] == target_sha(),
                "Published tag does not match the verified source commit")
    else:
        owned_draft(release)
    expected = {name: (sha256(directory / name), (directory / name).stat().st_size)
                for name in set(BINARIES) | {MANIFEST, PROOF}}
    missing = missing_assets(release, expected)
    if complete or published:
        require(not missing, "Release assets are incomplete")
        print(json.dumps({"verified": True, "url": release["html_url"], "assetCount": len(expected),
                          "published": published, "tag": TAG}))
    else:
        print("\n".join(missing))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("preflight", "verify", "remote"))
    parser.add_argument("--directory", type=Path, default=Path("dist/release-019"))
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--complete", action="store_true")
    parser.add_argument("--published", action="store_true")
    args = parser.parse_args()
    if args.command == "preflight":
        preflight()
    elif args.command == "verify":
        require(args.sdk is not None, "Android build-tools directory is required")
        verify_assets(args.directory, args.sdk)
    else:
        verify_remote(args.directory, args.complete, args.published)


if __name__ == "__main__":
    main()
