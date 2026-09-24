#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate a deterministic, public-only Docker context; never deploy or run it."""

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile
import urllib.request
from urllib.parse import urlsplit
import zipfile

HERE = Path(__file__).resolve().parent
MAX_EXPANDED = 256 * 1024 * 1024


def chromium_seccomp(source):
    profile = json.loads(source)
    if profile['defaultAction'] != 'SCMP_ACT_ERRNO' or len(profile['syscalls']) < 10:
        raise ValueError('incomplete_seccomp_profile')
    # Chromium chroots inside its new user namespace. Outer container capabilities
    # stay empty; allowing this syscall does not grant CAP_SYS_CHROOT on the host.
    profile['syscalls'].append({
        'names': ['clone', 'setns', 'unshare', 'chroot'], 'action': 'SCMP_ACT_ALLOW',
        'comment': 'Chromium namespace sandbox in a cap-drop=ALL non-root container',
    })
    return (json.dumps(profile, sort_keys=True, indent=2) + '\n').encode('ascii')


def download(entry, opener):
    url = urlsplit(entry['url'])
    if (url.scheme != 'https' or url.hostname not in ('files.pythonhosted.org', 'raw.githubusercontent.com')
            or url.port not in (None, 443) or url.username or url.password
            or not re.fullmatch('[0-9a-f]{64}', entry['sha256'])
            or type(entry['bytes']) is not int or not 0 < entry['bytes'] <= 64 * 1024 * 1024):
        raise ValueError('invalid_public_dependency')
    with opener.open(entry['url'], timeout=60) as response:
        final = urlsplit(response.url)
        if final.scheme != 'https' or final.hostname != url.hostname:
            raise ValueError('unexpected_dependency_redirect')
        data = response.read(entry['bytes'] + 1)
    if len(data) != entry['bytes'] or hashlib.sha256(data).hexdigest() != entry['sha256']:
        raise ValueError('public_dependency_hash_mismatch')
    return data


def wheel_entries(data):
    with zipfile.ZipFile(io.BytesIO(data)) as wheel:
        entries = wheel.infolist()
        if len(entries) > 10000 or sum(item.file_size for item in entries) > MAX_EXPANDED:
            raise ValueError('oversized_wheel')
        seen = set()
        for item in entries:
            name = item.orig_filename
            path = PurePosixPath(name)
            mode = item.external_attr >> 16
            if (not name or '\\' in name or ':' in name or path.is_absolute()
                    or any(part in ('..', '.') for part in name.rstrip('/').split('/'))
                    or any(ord(c) < 32 for c in name) or path.as_posix() != name.rstrip('/')
                    or stat.S_ISLNK(mode) or stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                raise ValueError('unsafe_wheel_entry: ' + name)
            if item.is_dir():
                continue
            # Preserve C headers as inert wheel data, never install scripts/hooks.
            if any(part.endswith('.data') for part in path.parts) and not (
                    len(path.parts) == 3 and path.parts[0].endswith('.data')
                    and path.parts[1] == 'headers' and path.suffix == '.h'):
                raise ValueError('wheel_install_hook_unsupported')
            if name in seen:
                raise ValueError('duplicate_wheel_entry')
            seen.add(name)
            # Playwright's bundled Linux Node is the only required executable.
            yield 'python/' + name, wheel.read(item), 0o755 if name == 'playwright/driver/node' else 0o644


def write_context(target, entries):
    seen = set()
    total = 0
    with target.open('xb') as stream, tarfile.open(fileobj=stream, mode='w') as archive:
        for name, data, mode in entries:
            if name in seen:
                raise ValueError('duplicate_context_entry')
            seen.add(name)
            total += len(data)
            if total > MAX_EXPANDED:
                raise ValueError('oversized_context')
            info = tarfile.TarInfo(name)
            info.size, info.mode, info.mtime = len(data), mode, 0
            info.uid = info.gid = 0
            archive.addfile(info, io.BytesIO(data))
    return seen


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New public artifact directory')
    parser.add_argument('--proxy', help='Optional loopback HTTP proxy used only by this download process')
    args = parser.parse_args(argv)
    if args.proxy:
        proxy = urlsplit(args.proxy)
        if proxy.scheme != 'http' or proxy.hostname not in ('localhost', '127.0.0.1') or not proxy.port or proxy.username or proxy.password:
            parser.error('Expected a loopback HTTP proxy without credentials')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({'https': args.proxy} if args.proxy else {}))
    lock_raw = (HERE / 'browser-image-lock.json').read_bytes()
    lock = json.loads(lock_raw)
    if lock['schema'] != 'openflux-browser-image-lock-v1' or lock['platform'] != 'linux/amd64':
        raise ValueError('invalid_dependency_lock')
    args.output.mkdir(parents=True, exist_ok=False)
    seccomp = chromium_seccomp(download(lock['seccomp'], opener))
    license_data = download(lock['seccomp_license'], opener)
    (args.output / 'browser-seccomp.json').write_bytes(seccomp)
    (args.output / 'browser-seccomp.LICENSE').write_bytes(license_data)
    (args.output / 'browser-image-lock.json').write_bytes(lock_raw)
    def entries():
        yield 'Dockerfile', (HERE / 'browser.Dockerfile').read_bytes(), 0o644
        yield 'browser_worker.py', (HERE / 'browser_worker.py').read_bytes(), 0o644
        yield 'browser_sandbox_probe.py', (HERE / 'browser_sandbox_probe.py').read_bytes(), 0o644
        for entry in lock['wheels']:
            yield from wheel_entries(download(entry, opener))
    archive = args.output / 'browser-context.tar'
    names = write_context(archive, entries())
    if 'python/playwright/driver/node' not in names:
        raise ValueError('browser_driver_missing')
    with archive.open('rb') as stream:
        context_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
    receipt = {'schema': 'openflux-browser-context-v1', 'base_image': lock['base_image'],
               'base_image_id': lock['base_image_id'], 'lock_sha256': hashlib.sha256(lock_raw).hexdigest(),
               'context_sha256': context_hash,
               'context_bytes': archive.stat().st_size, 'file_count': len(names),
               'seccomp_source_sha256': lock['seccomp']['sha256'],
               'seccomp_sha256': hashlib.sha256(seccomp).hexdigest(),
               'dependencies': {entry['package']: entry['version'] for entry in lock['wheels']},
               'package_installers_executed': False, 'containers_started': False}
    (args.output / 'context-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='ascii')
    print(json.dumps(receipt, sort_keys=True))


if __name__ == '__main__':
    main()
