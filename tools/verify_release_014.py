"""Verify immutable, already-signed 0.1.14 assets; never receives a signing key."""
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
TAG = "v0.1.14"
MARKER = "<!-- unifiedvpn-publisher:014-20261115 -->"
CERT = "0a8547df6c99acfc4cbf9ca0916fbf7301c648bf4aecc655f7904d43c4bbeaa0"
PROOF = "verification-build-2026111502-android.json"
PROOF_SHA = "acee4f8e0ecb0d517ab59d184f58828bf6f411d6405d7116e7b7215536fad87d"
MANIFEST = "SHA256-build-2026111502.json"
SOURCE_PROOF = "source-provenance-build-2026111502.json"
SOURCE_PROOF_SHA = "582d12eead816035a3e97647c5a59d55cb35e9eb6e41f39a811eae9e5ae8a39d"
SOURCE_PATCH = "UnifiedVPN-0.1.14-routing-failover.patch"
SOURCE_PATCH_SHA = "4ac56839cf502d0dbb996098f7790e34094ebf1eb496a8dd81c7e0cfe31ac4ec"
PUBLICATION_METADATA = {
    ".github/workflows/publish-0.1.14.yml", "tools/verify_release_014.py",
    "docs/releases/0.1.14-stable-notes.txt",
    "docs/releases/verification-build-2026111502-android.json",
    "docs/releases/source-provenance-build-2026111502.json",
    "docs/releases/UnifiedVPN-0.1.14-routing-failover.patch",
}

def metadata_assets():
    return {PROOF, MANIFEST, SOURCE_PROOF, SOURCE_PATCH}

def verify_source():
    require(os.environ.get("GITHUB_REPOSITORY") == REPO, "Unexpected source repository")
    def git(*args):
        r = subprocess.run(["git", *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        require(r.returncode == 0, "Source Git check failed")
        return r.stdout
    require(git("rev-parse", "HEAD").decode().strip() == target_sha(), "Checkout differs from publication source commit")
    source_path = Path("docs/releases") / SOURCE_PROOF
    require(source_path.is_file() and not source_path.is_symlink() and sha256(source_path) == SOURCE_PROOF_SHA, "Source provenance changed")
    source = json.loads(source_path.read_text(encoding="utf-8"))
    base = source["baseCommit"]
    require(base == "18ee5043a643c43d6377088e5388665660dc95c0", "Unexpected source base")
    git("merge-base", "--is-ancestor", base, "HEAD")
    entries = {}
    for row in git("ls-tree", "-rz", "HEAD").split(b"\0"):
        if not row:
            continue
        meta, path = row.split(b"\t", 1)
        mode, kind, oid = meta.decode().split()
        entries[path.decode()] = (mode, kind, oid)
    expected = source["inputs"]
    require(len(expected) == 70 and len({x["path"] for x in expected}) == 70, "Source inventory changed")
    for item in expected:
        require(entries.get(item["path"]) == (item["mode"], "blob", item["gitBlob"]), "Publication source input differs: " + item["path"])
    resource = source["unchangedResource"]
    require(entries.get(resource["path"], (None, None, None))[2] == resource["gitBlob"] and sha256(Path(resource["path"])) == resource["sha256"], "Pinned packaged resource changed")
    changed = {x.decode() for x in git("diff", "--name-only", "-z", base, "HEAD").split(b"\0") if x}
    require(changed <= {x["path"] for x in expected} | PUBLICATION_METADATA, "Unreviewed additional source changes")
    patch_path = Path("docs/releases") / SOURCE_PATCH
    require(patch_path.is_file() and not patch_path.is_symlink() and sha256(patch_path) == SOURCE_PATCH_SHA, "Original source patch changed")
    print(json.dumps({"sourceVerified": True, "inputCount": 70, "baseCommit": base, "publicationCommit": target_sha(), "prebuiltOnly": True}))

BINARIES = {
    "UnifiedVPN-0.1.14-build.2026111502-android-universal.apk": (
        "0c1c536b69b8de887e25f0218c3e4322073461316b7aef03cba6508d8f8027b5", 205831763),
    "UnifiedVPN-0.1.14-build.2026111502-windows-amd64-installer.exe": (
        "4d596759b2e87186ce7bb917edc1151b4d59e8744c365318ec21138146ca1e59", 197191764),
    "UnifiedVPN-0.1.14-build.2026111502-windows-amd64-portable-manual.zip": (
        "4b2dfaf50a780945adfd499fee60b7e853015f6e599acaf37c7204ccf1883ef3", 195448111),
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
    require(package and package.groups() == ("app.unifiedvpn.local", "2026111502", "0.1.14"),
            "APK package/version mismatch")
    require("application-debuggable" not in metadata, "Release APK is debuggable")
    run_tool([str(sdk / ("zipalign" + suffix)), "-c", "-P", "16", "4", str(path)])


def verify_assets(directory, sdk):
    allowed = set(BINARIES) | metadata_assets()
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
    require(proof["sha256"] == BINARIES[proof["artifact"]][0] and proof["sizeBytes"] == 205831763,
            "Android record identifies a different artifact")
    require(proof["signing"]["certificateSha256"] == CERT and proof["previousCertificateSha256"] == CERT
            and proof["signing"]["signerCount"] == 1, "Android certificate record mismatch")
    require(proof["packageManagerUpdateRequirementsSatisfied"] is True
            and proof["privateSigningKeyIncluded"] is False
            and proof["physicalDeviceUpgradeTestPerformed"] is False, "Unexpected Android verification flags")
    for name, digest in ((SOURCE_PROOF, SOURCE_PROOF_SHA), (SOURCE_PATCH, SOURCE_PATCH_SHA)):
        path = directory / name
        require(path.is_file() and not path.is_symlink() and sha256(path) == digest, "Source attachment mismatch: " + name)
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
                 "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "UnifiedVPN-014-publisher"})
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
    require(release["draft"] is True, "Stable v0.1.14 already exists; refusing to overwrite it")
    require(release["target_commitish"] == target_sha() and MARKER in (release.get("body") or ""),
            "Existing draft belongs to another publication; refusing to modify it")


def preflight():
    release = release_by_tag()
    ref = api("/git/ref/tags/" + TAG)
    if release:
        owned_draft(release)
    else:
        require(ref is None, "Tag v0.1.14 already exists without an owned draft")
    if ref:
        require(ref["object"]["type"] == "commit" and ref["object"]["sha"] == target_sha(),
                "Existing v0.1.14 tag points to another commit")
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
        require(latest and latest["tag_name"] == TAG, "v0.1.14 is not the latest stable release")
        ref = api("/git/ref/tags/" + TAG)
        require(ref and ref["object"]["type"] == "commit" and ref["object"]["sha"] == target_sha(),
                "Published tag does not match the verified source commit")
    else:
        owned_draft(release)
        ref = api("/git/ref/tags/" + TAG)
        require(ref is None or (ref["object"]["type"] == "commit" and ref["object"]["sha"] == target_sha()),
                "Draft tag does not match the verified source commit; refusing publication")
    expected = {name: (sha256(directory / name), (directory / name).stat().st_size)
                for name in set(BINARIES) | metadata_assets()}
    missing = missing_assets(release, expected)
    if complete or published:
        require(not missing, "Release assets are incomplete")
        print(json.dumps({"verified": True, "url": release["html_url"], "assetCount": len(expected),
                          "published": published, "tag": TAG}))
    else:
        print("\n".join(missing))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("preflight", "verify", "remote", "source"))
    parser.add_argument("--directory", type=Path, default=Path("dist/release-014"))
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--complete", action="store_true")
    parser.add_argument("--published", action="store_true")
    args = parser.parse_args()
    if args.command == "source":
        verify_source()
    elif args.command == "preflight":
        preflight()
    elif args.command == "verify":
        require(args.sdk is not None, "Android build-tools directory is required")
        verify_assets(args.directory, args.sdk)
    else:
        verify_remote(args.directory, args.complete, args.published)


if __name__ == "__main__":
    main()
