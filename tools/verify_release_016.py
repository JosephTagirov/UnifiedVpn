"""Verify the exact UnifiedVPN 0.1.16 packages and GitHub release assets."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path


REPO = 'JosephTagirov/UnifiedVpn'
VERSION = '0.1.16'
TAG = f'v{VERSION}'
BUILD = 2026112201
APK_CERT = '0a8547df6c99acfc4cbf9ca0916fbf7301c648bf4aecc655f7904d43c4bbeaa0'
PREFIX = f'UnifiedVPN-{VERSION}-build.{BUILD}'
APK = f'{PREFIX}-android-universal.apk'
INSTALLER = f'{PREFIX}-windows-amd64-installer.exe'
PORTABLE = f'{PREFIX}-windows-amd64-portable-manual.zip'
MANIFEST = Path(f'docs/releases/verification-build-{BUILD}.json')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(*command):
    result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8',
                            errors='replace', timeout=180)
    require(not result.returncode, f'{Path(command[0]).name} verification failed')
    return result.stdout


def expected_assets():
    source = dict(line.split('=', 1) for line in Path('gradle.properties').read_text().splitlines()
                  if line.startswith('olcbox.') and '=' in line)
    require((source['olcbox.version'], source['olcbox.build'], source['olcbox.versionCode']) ==
            (VERSION, str(BUILD), str(BUILD)), 'Source package version mismatch')
    manifest = json.loads(MANIFEST.read_text(encoding='utf-8'))
    require((manifest['version'], manifest['build'], manifest['androidSigningCertificate']) ==
            (VERSION, BUILD, APK_CERT), 'Release manifest identity mismatch')
    assets = manifest['assets']
    require(set(assets) == {APK, INSTALLER, PORTABLE}, 'Unexpected release package names')
    for name, asset in assets.items():
        require(isinstance(asset['bytes'], int) and asset['bytes'] > 0 and
                re.fullmatch('[0-9a-f]{64}', asset['sha256']), f'Invalid package descriptor: {name}')
    return {**assets, MANIFEST.name: {'bytes': MANIFEST.stat().st_size, 'sha256': digest(MANIFEST)}}


def verify_local(expected, directory, sdk):
    for name in (APK, INSTALLER, PORTABLE):
        path = directory / name
        require(path.stat().st_size == expected[name]['bytes'] and digest(path) == expected[name]['sha256'],
                f'Downloaded package differs: {name}')
    apk = str(directory / APK)
    suffix = '.exe' if os.name == 'nt' else ''
    metadata = run(str(sdk / f'aapt{suffix}'), 'dump', 'badging', apk)
    require(f"package: name='app.unifiedvpn.local' versionCode='{BUILD}' versionName='{VERSION}'" in metadata
            and 'application-debuggable' not in metadata, 'Android package identity mismatch')
    signer = run('java', '-jar', str(sdk / 'lib/apksigner.jar'), 'verify', '--print-certs', apk)
    require(re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]+)', signer) == [APK_CERT],
            'Android signing certificate mismatch')
    run(str(sdk / f'zipalign{suffix}'), '-c', '-P', '16', '4', apk)
    with (directory / INSTALLER).open('rb') as installer:
        require(installer.read(2) == b'MZ', 'Windows installer is not a PE executable')
    with zipfile.ZipFile(directory / PORTABLE) as archive:
        require(archive.testzip() is None and any(Path(n).name == 'UnifiedVPN.exe' for n in archive.namelist()),
                'Windows portable package is corrupt or lacks the launcher')


def get_release():
    require(os.environ.get('GITHUB_REPOSITORY') == REPO, 'Unexpected GitHub repository')
    revision = os.environ.get('GITHUB_SHA', '')
    require(re.fullmatch('[a-f0-9]{40}', revision), 'Missing release source commit')
    pages = json.loads(run('gh', 'api', '--paginate', '--slurp', f'repos/{REPO}/releases?per_page=100'))
    matches = [release for page in pages for release in page if release['tag_name'] == TAG]
    require(len(matches) <= 1, 'Multiple releases found for the same tag')
    if not matches:
        ref_result = subprocess.run(['git', 'ls-remote', '--exit-code', 'origin', f'refs/tags/{TAG}'],
                                    capture_output=True, timeout=20)
        require(ref_result.returncode == 2, 'Tag already exists or the remote could not be checked')
        return None
    release = matches[0]
    require(release['target_commitish'] == revision and release['prerelease'] is False,
            'Release exists with a different source commit or release class')
    return release


def verify_remote(expected, release, published=False):
    require(release is not None and release['draft'] is not published, 'Unexpected GitHub release state')
    found = set()
    for asset in release['assets']:
        name = asset['name']
        require(name in expected and name not in found and asset['state'] == 'uploaded',
                f'Unexpected GitHub asset: {name}')
        require(asset['size'] == expected[name]['bytes'] and asset['digest'] == 'sha256:' + expected[name]['sha256'],
                f'GitHub asset differs: {name}')
        found.add(name)
    return sorted(set(expected) - found)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['local', 'draft', 'missing', 'complete', 'published'])
    parser.add_argument('--sdk', type=Path)
    parser.add_argument('--directory', type=Path, default=Path('dist/release-016'))
    args = parser.parse_args()
    expected = expected_assets()
    if args.mode == 'local':
        require(args.sdk is not None, 'Specify Android SDK build tools with --sdk')
        verify_local(expected, args.directory, args.sdk)
        return

    release = get_release()
    if args.mode == 'draft':
        print('missing' if release is None else 'draft' if release['draft'] else 'published')
        return
    missing = verify_remote(expected, release, args.mode == 'published')
    if args.mode == 'missing':
        for name in missing:
            print(name)
    else:
        require(not missing, f'GitHub release is missing {len(missing)} verified assets')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(error, file=sys.stderr)
        raise SystemExit(1)
