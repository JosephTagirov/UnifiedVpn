"""Publish validated TLS pairs from the root-owned pinned offsite checkpoint."""
import argparse
import grp
import hashlib
import json
import os
from pathlib import Path
import posixpath
import shutil
import socket
import ssl
import stat
import subprocess
import tarfile
import time


def command(args, **kwargs):
    result = subprocess.run(args, capture_output=True, timeout=30, **kwargs)
    if result.returncode:
        raise RuntimeError('TLS replica validation failed in ' + args[0])
    return result.stdout


def read_member(archive, name):
    for _ in range(8):
        member = archive.getmember(name)
        if member.issym():
            name = posixpath.normpath(posixpath.join(posixpath.dirname(name), member.linkname))
        elif member.islnk():
            name = posixpath.normpath(member.linkname)
        else:
            if not member.isfile() or not 0 < member.size <= 128 * 1024:
                raise ValueError('Invalid TLS archive member')
            with archive.extractfile(member) as stream:
                return stream.read()
        if name.startswith(('/', '../')):
            raise ValueError('TLS archive link leaves the archive root')
    raise ValueError('TLS archive link depth exceeded')


def verify_pair(certificate, key, hostname):
    command(['openssl', 'x509', '-in', str(certificate), '-checkend', '604800', '-checkhost', hostname, '-noout'])
    command(['openssl', 'verify', '-CApath', '/etc/ssl/certs', '-untrusted', str(certificate), str(certificate)])
    public_certificate = command(['openssl', 'x509', '-in', str(certificate), '-pubkey', '-noout'])
    public_key = command(['openssl', 'pkey', '-in', str(key), '-pubout'])
    if public_certificate != public_key:
        raise ValueError('TLS certificate and key do not match')


def caddy_environment():
    pid = command(['systemctl', 'show', 'caddy', '--property=MainPID', '--value']).decode().strip()
    values = dict(item.split('=', 1) for item in Path('/proc', pid, 'environ').read_text().split('\0') if '=' in item)
    return {**os.environ, **{name: values[name] for name in ['ACME_EMAIL', 'TPROXY_HOSTNAME'] if name in values}}


def reload_services(restart_xui):
    command(['runuser', '-u', 'caddy', '--', shutil.which('caddy'), 'validate', '--adapter', 'caddyfile', '--config', '/etc/caddy/Caddyfile'], env=caddy_environment())
    command(['systemctl', 'reload', 'caddy'])
    if restart_xui:
        command(['systemctl', 'try-restart', 'x-ui'])


def verify_live(config):
    expected = {}
    for pair in config['certificates']:
        certificate = Path(config['directory'], pair['name'], 'current/fullchain.pem')
        encoded = command(['openssl', 'x509', '-in', str(certificate), '-outform', 'DER'])
        expected[pair['hostname']] = hashlib.sha256(encoded).hexdigest()
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        try:
            for hostname, digest in expected.items():
                with socket.create_connection(('127.0.0.1', 443), timeout=3) as raw, ssl.create_default_context().wrap_socket(raw, server_hostname=hostname) as tls:
                    if hashlib.sha256(tls.getpeercert(binary_form=True)).hexdigest() != digest:
                        raise ValueError('Caddy has not loaded the validated TLS revision')
            return
        except (OSError, ValueError):
            time.sleep(.25)
    raise RuntimeError('Live TLS verification failed after Caddy reload')


def publish(config, reload):
    archive_path = Path(config['archive'])
    info = archive_path.stat()
    if archive_path.is_symlink() or not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) & 0o022:
        raise ValueError('TLS source must be a protected root-owned regular file')
    root = Path(config['directory'])
    group = grp.getgrnam(config['group']).gr_gid
    pending = []
    with tarfile.open(archive_path) as archive:
        for pair in config['certificates']:
            certificate = read_member(archive, pair['certificateMember'])
            key = read_member(archive, pair['keyMember'])
            digest = hashlib.sha256(certificate + key).hexdigest()
            folder = root / pair['name']
            revision = folder / digest
            revision.mkdir(parents=True, exist_ok=True, mode=0o750)
            for directory in [root.parent, root, folder, revision]:
                current_permissions = directory.stat()
                if stat.S_IMODE(current_permissions.st_mode) != 0o750:
                    directory.chmod(0o750)
                if (current_permissions.st_uid, current_permissions.st_gid) != (0, group):
                    os.chown(directory, 0, group)
            for name, data in [('fullchain.pem', certificate), ('privkey.pem', key)]:
                path = revision / name
                if path.exists() and path.read_bytes() != data:
                    raise ValueError('TLS revision data differs from its digest')
                if not path.exists():
                    staged = path.with_name(name + '.new')
                    with staged.open('wb') as stream:
                        stream.write(data)
                        stream.flush()
                        os.fsync(stream.fileno())
                    staged.chmod(0o640)
                    os.chown(staged, 0, group)
                    os.replace(staged, path)
            verify_pair(revision / 'fullchain.pem', revision / 'privkey.pem', pair['hostname'])
            current = folder / 'current'
            previous = os.readlink(current) if current.is_symlink() else None
            if current.exists() and previous is None:
                raise ValueError('TLS current path must be an owned symlink')
            pending.append((current, previous, digest))
    changed = any(previous != digest for _, previous, digest in pending)
    panel_changed = any(current.parent.name == 'panel' and previous != digest for current, previous, digest in pending)
    try:
        for current, previous, digest in pending:
            if previous == digest:
                continue
            staged = current.with_name('current.new')
            if staged.is_symlink():
                staged.unlink()
            staged.symlink_to(digest, target_is_directory=True)
            os.replace(staged, current)
        if changed and reload:
            reload_services(panel_changed)
            verify_live(config)
    except Exception:
        for current, previous, _ in pending:
            if previous is None:
                current.unlink(missing_ok=True)
            else:
                rollback = current.with_name('current.rollback')
                if rollback.is_symlink():
                    rollback.unlink()
                rollback.symlink_to(previous, target_is_directory=True)
                os.replace(rollback, current)
        if reload and all(previous is not None for _, previous, _ in pending):
            reload_services(panel_changed)
        raise
    report = {'checkedAt': int(time.time()), 'archiveModifiedAt': int(info.st_mtime), 'changed': changed, 'validatedHostnames': [pair['hostname'] for pair in config['certificates']]}
    status = root / 'status.json'
    status.write_text(json.dumps(report))
    status.chmod(0o600)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', default='/etc/unifiedvpn-security/tls-replica.json')
    parser.add_argument('--reload', action='store_true')
    args = parser.parse_args()
    print(json.dumps(publish(json.loads(Path(args.config).read_text()), args.reload)))
