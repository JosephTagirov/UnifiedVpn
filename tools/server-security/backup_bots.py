"""Consistent private snapshots of the three bot data stores and their code."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import sys
import tarfile
import tempfile

root = Path('/var/lib/unifiedvpn-bot-backups')
root.mkdir(mode=0o700, exist_ok=True)
root.chmod(0o700)
target = root / 'current.tar.gz'
databases = {
    'friendbot.sqlite3': Path('/var/lib/friendbot/bot.sqlite3'),
    'birthdays.sqlite3': Path('/var/lib/birthday-reminder-bot/birthdays.sqlite3'),
    'onwatch.sqlite3': Path('/root/.onwatch/onwatch.db'),
}
checks = {}
with tempfile.TemporaryDirectory(dir=root) as temporary:
    temporary = Path(temporary)
    for name, path in databases.items():
        with sqlite3.connect('file:' + str(path) + '?mode=ro', uri=True, timeout=15) as live, sqlite3.connect(temporary / name) as backup:
            live.backup(backup)
            assert backup.execute('pragma integrity_check').fetchone()[0] == 'ok'
        (temporary / name).chmod(0o600)
        checks[name] = 'ok'
    metadata = temporary / 'snapshot.json'
    metadata.write_text(json.dumps({'databaseIntegrity': checks, 'containsPrivateConfiguration': True}))
    pending = target.with_suffix('.new')
    with tarfile.open(pending, 'w:gz', compresslevel=3) as archive:
        for path in temporary.iterdir():
            archive.add(path, arcname='consistent/' + path.name)
        for folder in ['/opt/friendbot', '/opt/friendbot-media', '/opt/onwatch-telegram', '/usr/local/lib/birthday-reminder-bot']:
            path = Path(folder)
            if path.is_dir():
                for pattern in ['*.py', '*.txt', '*.toml', '*.json']:
                    for file in path.glob(pattern):
                        if file.is_file() and file.stat().st_size < 2 * 1024 * 1024:
                            archive.add(file, arcname=file.relative_to('/').as_posix())
        for name in ['friendbot', 'friendbot-media', 'onwatch', 'onwatch-telegram', 'birthday-reminder-bot']:
            path = Path('/etc/systemd/system') / (name + '.service')
            if path.is_file():
                archive.add(path, arcname=path.relative_to('/').as_posix())
        for name in ['friendbot', 'friendbot-media', 'birthday-reminder-bot']:
            path = Path('/etc') / (name + '.env')
            if path.is_file():
                archive.add(path, arcname=path.relative_to('/').as_posix())
        for path in Path('/root/.onwatch').glob('*.json'):
            if path.is_file() and path.stat().st_size < 2 * 1024 * 1024:
                archive.add(path, arcname=path.relative_to('/').as_posix())
    pending.chmod(0o600)
    if target.exists():
        os.replace(target, root / 'previous.tar.gz')
    os.replace(pending, target)
for path in [Path('/root/.onwatch'), *Path('/root/.onwatch').glob('onwatch.db*')]:
    if path.is_dir():
        path.chmod(0o700)
    elif path.is_file():
        path.chmod(0o600)
if '--stdout' in sys.argv:
    with target.open('rb') as stream:
        shutil.copyfileobj(stream, sys.stdout.buffer, 1024 * 1024)
else:
    print(json.dumps({'path': str(target), 'bytes': target.stat().st_size, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(), 'threeDatabaseIntegrityChecks': checks, 'onwatchPermissionsHardened': True}))
