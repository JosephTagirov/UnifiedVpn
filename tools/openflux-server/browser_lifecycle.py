#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Receipt-bound browser service and independent recovery for staged upgrades."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import threading
import time

HERE = Path(__file__).resolve().parent
HELPERS = ('browser_lifecycle.py', 'browser_supervisor.py', 'browser_worker.py', 'upgrade.py', 'manage.py')
SCHEMA = 'unifiedvpn-openflux-browser-lifecycle-v1'
GUARD_SCHEMA = 'unifiedvpn-openflux-recovery-v1'
ENV = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LANG': 'C', 'HOME': '/root'}
DOCKER = ['docker', '--host', 'unix:///var/run/docker.sock']


def load(name, filename):
    path = HERE / filename
    if __name__ == '__main__':
        for source in (Path(__file__).absolute(), path):
            for directory in (source.parent, *source.parent.parents):
                info = directory.lstat()
                if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
                    raise RuntimeError('unsafe_helper_directory')
            info = source.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1 or info.st_mode & 0o022:
                raise RuntimeError('unsafe_helper_source')
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    data = path.read_bytes()
    if len(data) > 262144:
        raise RuntimeError('helper_size_limit')
    exec(compile(data, str(path), 'exec', dont_inherit=True), module.__dict__)
    return module


upgrade = load('lifecycle_upgrade', 'upgrade.py')
browser = load('lifecycle_browser', 'browser_supervisor.py')
manage = upgrade.manage


def sync_directory(path):
    if os.name == 'posix':
        descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def atomic_json(path, value, *, new=False):
    upgrade.private_directory(path.parent)
    if path.exists() or path.is_symlink():
        manage.require_secure_file(path, private=True)
        if new:
            manage.fail('Existing lifecycle metadata was not overwritten.')
    temporary = path.with_name(path.name + '.new')
    data = json.dumps(value, sort_keys=True, indent=2).encode('ascii') + b'\n'
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)
    sync_directory(path.parent)


def boot_id():
    value = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
    if re.fullmatch('[0-9a-f-]{36}', value) is None:
        manage.fail('The boot identity could not be verified.')
    return value


def process_start(pid):
    if type(pid) is not int or pid <= 1:
        manage.fail('Invalid controller identity.')
    try:
        data = (Path('/proc') / str(pid) / 'stat').read_text()
    except FileNotFoundError:
        return None
    return int(data.rsplit(')', 1)[1].split()[19])


def needs_recovery(guard, *, current_boot, now_ns, controller_start):
    if guard['state'] != 'armed':
        return False
    return (guard['boot_id'] != current_boot or controller_start != guard['controller_start']
            or now_ns >= guard['deadline_ns'])


def unit_contents(instance, directory, candidate_root):
    manage.instance_name(instance)
    for path in (directory, candidate_root):
        if not path.is_absolute() or path.resolve(strict=False) != path or re.fullmatch(r'/[A-Za-z0-9_./-]+', str(path)) is None:
            manage.fail('Systemd integration requires canonical ASCII paths without unit specifiers.')
    prefix = 'unifiedvpn-openflux-managed-' + instance
    runtime = prefix + '.service'
    recovery = prefix + '-recovery.service'
    timer = prefix + '-recovery.timer'
    command = f'/usr/bin/python3 -B {directory}/runtime/browser_lifecycle.py'
    args = f' --instance {instance} --receipt-directory {directory} --apply'
    hardening = (
        'NoNewPrivileges=yes\nCapabilityBoundingSet=\nPrivateTmp=yes\n'
        'NetworkNamespacePath=/proc/1/ns/net\n'
        'ProtectSystem=strict\nProtectHome=read-only\nProtectKernelTunables=yes\n'
        'ProtectKernelModules=yes\nProtectControlGroups=yes\nRestrictSUIDSGID=yes\n'
        'LockPersonality=yes\nRestrictAddressFamilies=AF_UNIX AF_NETLINK\n'
        f'ReadWritePaths={directory} {candidate_root} /run\n'
        'UMask=0077\nMemoryMax=256M\nTasksMax=64\nCPUQuota=25%\n'
        'StandardOutput=journal\nStandardError=journal\n')
    return {
        runtime: (
            '[Unit]\nDescription=Unified VPN OpenFlux browser companion\n'
            f'Requires=docker.service {recovery}\nAfter=docker.service {recovery}\n'
            'StartLimitIntervalSec=300\nStartLimitBurst=3\n'
            '[Service]\nType=exec\n'
            f'ExecStart={command} serve{args}\nExecStopPost={command} stop-runtime{args}\n'
            'Restart=on-failure\nRestartSec=15\nTimeoutStopSec=45\nKillMode=control-group\n'
            + hardening + '[Install]\nWantedBy=multi-user.target\n'),
        recovery: (
            '[Unit]\nDescription=Recover an interrupted Unified VPN OpenFlux upgrade\n'
            f'Requires=docker.service\nAfter=docker.service\nBefore={runtime}\n'
            '[Service]\nType=oneshot\n'
            f'ExecStart={command} recover{args}\nTimeoutStartSec=180\n'
            + hardening + '[Install]\nWantedBy=multi-user.target\n'),
        timer: (
            '[Unit]\nDescription=Bounded Unified VPN OpenFlux trial recovery\n'
            '[Timer]\nOnBootSec=10\nOnUnitInactiveSec=10\nAccuracySec=1\n'
            f'Unit={recovery}\n[Install]\nWantedBy=timers.target\n'),
    }


class Lifecycle:
    def __init__(self, instance, directory, *, command=manage.run_command, unit_dir=Path('/etc/systemd/system'),
                 install_parent=Path('/opt'), clock=time.monotonic, sleep=time.sleep):
        self.up = upgrade.Upgrade(instance, directory, command=command, install_parent=install_parent, clock=clock, sleep=sleep)
        self.directory, self.instance = self.up.directory, instance
        self.command, self.unit_dir = command, Path(unit_dir)
        self.binding_path = self.directory / 'browser-runtime.json'
        self.guard_path = self.directory / 'recovery.json'
        self.clock, self.sleep = clock, sleep

    def systemctl(self, *args, allow_failure=False):
        return self.up.bounded_command(['systemctl', '--no-pager', *args], timeout=45, allow_failure=allow_failure)

    def units(self, record):
        return unit_contents(self.instance, self.directory, self.up.candidate(record).root)

    def unit(self, suffix):
        return 'unifiedvpn-openflux-managed-' + self.instance + suffix

    def load_binding(self, *, check_units=True):
        record = self.up.load()
        value = upgrade.read_json(self.binding_path)
        fields = {'schema', 'instance', 'upgrade_id', 'candidate_owner', 'browser_image', 'seccomp_sha256', 'helper_hashes', 'unit_hashes', 'network_namespace'}
        if (set(value) != fields or value['schema'] != SCHEMA or value['instance'] != self.instance
                or value['upgrade_id'] != record['upgrade_id'] or value['candidate_owner'] != record['candidate_owner']
                or not manage.IMAGE_RE.fullmatch(str(value['browser_image']))
                or not manage.HASH_RE.fullmatch(str(value['seccomp_sha256']))
                or re.fullmatch(r'net:\[[0-9]+\]',str(value['network_namespace'])) is None
                or set(value['helper_hashes']) != set(HELPERS)):
            manage.fail('Browser lifecycle ownership metadata is invalid.')
        root = self.directory / 'runtime'
        for name, digest in value['helper_hashes'].items():
            manage.require_secure_file(root / name)
            if not manage.HASH_RE.fullmatch(str(digest)) or manage.sha256_file(root / name) != digest:
                manage.fail('An installed browser lifecycle helper changed.')
        manage.require_secure_file(root / 'browser-seccomp.json')
        if manage.sha256_file(root / 'browser-seccomp.json') != value['seccomp_sha256']:
            manage.fail('The installed browser sandbox policy changed.')
        contents = self.units(record)
        if set(value['unit_hashes']) != set(contents):
            manage.fail('Lifecycle unit names do not match the original instance.')
        for name, content in contents.items():
            expected = hashlib.sha256(content.encode('ascii')).hexdigest()
            if value['unit_hashes'][name] != expected:
                manage.fail('Lifecycle unit content does not match the binding.')
            if check_units:
                manage.require_secure_file(self.unit_dir / name)
                if manage.sha256_file(self.unit_dir / name) != expected:
                    manage.fail('An installed lifecycle unit changed.')
        return record, value

    def validate_browser(self, candidate, image_id, seccomp, seccomp_hash):
        image = candidate.inspect('image', image_id)
        config = (image or {}).get('Config') or {}
        if (not image or image.get('Id') != image_id or image.get('Os') != 'linux' or image.get('Architecture') != 'amd64'
                or not image.get('RepoTags') or config.get('User') != '10001:10001' or config.get('OnBuild') or config.get('Volumes')
                or config.get('Labels', {}).get(manage.LABEL + '.browser-contract') != '2'):
            manage.fail('The pinned browser image is missing or its isolation contract changed.')
        manage.require_secure_file(seccomp)
        if seccomp.stat().st_size > 262144 or manage.sha256_file(seccomp) != seccomp_hash:
            manage.fail('The reviewed browser sandbox policy hash does not match.')
        policy = manage.decode_json(seccomp.read_bytes())
        if policy.get('defaultAction') != 'SCMP_ACT_ERRNO' or not policy.get('syscalls'):
            manage.fail('A complete default-deny browser sandbox policy is required.')

    def install(self, image_id, seccomp, seccomp_hash):
        self.up.original.preflight()
        network_namespace = os.readlink('/proc/1/ns/net')
        if re.fullmatch(r'net:\[[0-9]+\]',network_namespace) is None:
            manage.fail('The initial host network namespace could not be recorded.')
        record = self.up.load()
        candidate, state, current = self.up.verify(record)
        if (record['phase'] not in ('prepared', 'document_failed') or record['candidate_id'] or record['probe_id']
                or current != record['original'] or self.binding_path.exists() or self.binding_path.is_symlink()):
            manage.fail('Only an unchanged, inactive staged upgrade can install browser supervision.')
        if not manage.IMAGE_RE.fullmatch(image_id) or not manage.HASH_RE.fullmatch(seccomp_hash):
            manage.fail('Pin the reviewed browser image and sandbox policy.')
        self.validate_browser(candidate, image_id, Path(seccomp), seccomp_hash)
        contents = self.units(record)
        manage.secure_directory(self.unit_dir)
        for name in contents:
            if (self.unit_dir / name).exists() or (self.unit_dir / name).is_symlink():
                manage.fail('An existing systemd unit was not overwritten.')
            result = self.systemctl('show', name, '--property=LoadState', '--value', allow_failure=True)
            if result.stdout.strip() not in ('', 'not-found'):
                manage.fail('A loaded lifecycle unit already exists.')
        root = self.directory / 'runtime'
        root.mkdir(mode=0o700)
        hashes = {}
        for name in HELPERS:
            manage.require_secure_file(HERE / name)
            data = (HERE / name).read_bytes()
            if len(data) > 262144:
                manage.fail('Oversized lifecycle helper.')
            with (root / name).open('xb') as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            hashes[name] = hashlib.sha256(data).hexdigest()
        with (root / 'browser-seccomp.json').open('xb') as stream:
            stream.write(Path(seccomp).read_bytes())
            stream.flush()
            os.fsync(stream.fileno())
        sync_directory(root)
        binding = {'schema':SCHEMA, 'instance':self.instance, 'upgrade_id':record['upgrade_id'],
                   'candidate_owner':record['candidate_owner'], 'browser_image':image_id, 'seccomp_sha256':seccomp_hash,
                   'network_namespace':network_namespace,
                   'helper_hashes':hashes, 'unit_hashes':{name:hashlib.sha256(data.encode('ascii')).hexdigest() for name,data in contents.items()}}
        atomic_json(self.binding_path,binding,new=True)
        for name, content in contents.items():
            with (self.unit_dir / name).open('xb') as stream:
                stream.write(content.encode('ascii'))
                stream.flush()
                os.fsync(stream.fileno())
        sync_directory(self.unit_dir)
        self.systemctl('daemon-reload')
        # Recovery is enabled before any switch; the native runtime stays disabled.
        self.systemctl('enable',self.unit('-recovery.service'),self.unit('-recovery.timer'))
        self.systemctl('start',self.unit('-recovery.timer'))
        self.load_binding()

    def supervisor(self, record, binding, *, probe=False, check_browser=True):
        candidate, state, _ = self.up.verify(record)
        if probe:
            state = dict(state,container_id=record['probe_id'])
            candidate.name += '-document-check'
        if check_browser:
            self.validate_browser(candidate,binding['browser_image'],self.directory / 'runtime/browser-seccomp.json',binding['seccomp_sha256'])
        return candidate, state, browser.Supervisor(candidate,binding['browser_image'],
            self.directory / 'runtime/browser-seccomp.json',binding['seccomp_sha256'])

    def resource(self, record, candidate, state, *, probe=False):
        identity = record['probe_id'] if probe else record['candidate_id']
        return self.up.resource(candidate,state,candidate.name,identity,record['original']['state']['network_id'])

    def stop_runtime(self, *, probe=False):
        record, binding = self.load_binding()
        identity = record['probe_id'] if probe else record['candidate_id']
        if identity is None:
            return
        candidate, state, supervisor = self.supervisor(record,binding,probe=probe,check_browser=False)
        self.resource(record,candidate,state,probe=probe)
        cleanup_error = None
        try:
            supervisor.cleanup_browser(state)
        except BaseException as error:
            cleanup_error = error
        # A foreign browser identity never authorizes its removal, but must not
        # prevent stopping our separately verified native process.
        resource = self.resource(record,candidate,state,probe=probe)
        if resource['State']['Running']:
            candidate.docker('container','stop','--time','5',identity,timeout=15)
        resource = self.resource(record,candidate,state,probe=probe)
        if resource['State']['Running']:
            manage.fail('Owned native runtime did not stop.')
        if cleanup_error is not None:
            raise cleanup_error

    def run_pipe(self, record, binding, *, probe=False, timeout=None):
        candidate, state, supervisor = self.supervisor(record,binding,probe=probe)
        current = self.resource(record,candidate,state,probe=probe)
        if current['State']['Running']:
            manage.fail('A native runtime is already running; no second attachment was started.')
        config = manage.read_config(candidate.root / 'server.json')
        supervisor.cleanup_browser(state)
        identity = record['probe_id'] if probe else record['candidate_id']
        process = subprocess.Popen(DOCKER + ['container','start','--attach','--interactive',identity],
            stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,env=ENV,start_new_session=True)
        expired = threading.Event()
        timer = None
        if timeout is not None:
            def expire():
                expired.set()
                process.terminate()
            timer = threading.Timer(timeout,expire)
            timer.daemon = True
            timer.start()
        ok = False
        try:
            while True:
                line = process.stdout.readline(browser.worker.MAX_MESSAGE + 2)
                if not line:
                    break
                if len(line) > browser.worker.MAX_MESSAGE:
                    manage.fail('Oversized native protocol line; contents suppressed.')
                match = browser.REQUEST.fullmatch(line)
                if match:
                    response = supervisor.verify(state,config['document_url'],match[1].decode('ascii'))
                    process.stdin.write(browser.worker.encode_message(response))
                    process.stdin.flush()
                    del response
                elif line.rstrip(b'\r\n') == b'OPENFLUX_DOCUMENT_OK' and probe:
                    ok = True
                elif line.rstrip(b'\r\n') in browser.SAFE_STATUS:
                    print(line.decode('ascii').strip(),flush=True)
            process.wait(timeout=5)
            if expired.is_set() or process.returncode or probe and not ok:
                manage.fail('Native browser runtime failed; private diagnostics suppressed.')
        finally:
            if timer is not None:
                timer.cancel()
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
            self.stop_runtime(probe=probe)

    def check_document(self):
        record, binding = self.load_binding()
        candidate, state, current = self.up.verify(record)
        if (record['phase'] not in ('prepared','document_failed') or record['candidate_id'] or record['probe_id']
                or current != record['original']):
            manage.fail('Document validation requires an unchanged inactive candidate.')
        record.update(phase='checking_document',document_checked=False)
        self.up.save(record)
        name = candidate.name + '-document-check'
        if candidate.inspect('container',name) is not None:
            manage.fail('An earlier document probe requires inspection.')
        args = candidate.container_arguments(state,network_id=current['state']['network_id'],bootstrap_stdio=True)
        args[args.index('--name') + 1] = name
        args = args[:-1] + ['--entrypoint','/usr/local/bin/openflux',state['image_id'],'--config','/run/secrets/server.json','--check-document','--bootstrap-stdio']
        identity = candidate.docker(*args).stdout.strip()
        if not manage.HASH_RE.fullmatch(identity):
            manage.fail('Invalid probe container identity.')
        record['probe_id'] = identity
        self.up.save(record)
        try:
            self.run_pipe(record,binding,probe=True,timeout=85)
            record.update(document_checked=True,phase='prepared')
        except BaseException:
            record['phase'] = 'document_failed'
            raise
        finally:
            candidate.name = name
            resource = self.resource(record,candidate,state,probe=True)
            if resource['State']['Running']:
                manage.fail('Document probe is still running; removal refused.')
            candidate.docker('container','rm',identity)
            record['probe_id'] = None
            self.up.save(record)

    def guard(self):
        value = upgrade.read_json(self.guard_path)
        record,_ = self.load_binding()
        fields = {'schema','instance','upgrade_id','candidate_id','boot_id','controller_pid','controller_start','deadline_ns','state'}
        if (set(value) != fields or value['schema'] != GUARD_SCHEMA or value['instance'] != self.instance
                or value['upgrade_id'] != record['upgrade_id'] or value['candidate_id'] != record['candidate_id']
                or value['state'] not in ('armed','committed','rolled_back')
                or re.fullmatch('[0-9a-f-]{36}',str(value['boot_id'])) is None
                or any(type(value[k]) is not int or value[k] <= 0 for k in ('controller_pid','controller_start','deadline_ns'))):
            manage.fail('Recovery ownership metadata is invalid.')
        return value

    def arm(self, record, timeout):
        if self.systemctl('is-enabled',self.unit('-recovery.service')).stdout.strip() != 'enabled' or self.systemctl('is-active',self.unit('-recovery.timer')).stdout.strip() != 'active':
            manage.fail('Independent recovery must be enabled and active before switching.')
        guard = {'schema':GUARD_SCHEMA,'instance':self.instance,'upgrade_id':record['upgrade_id'],
                 'candidate_id':record['candidate_id'],'boot_id':boot_id(),'controller_pid':os.getpid(),
                 'controller_start':process_start(os.getpid()),'deadline_ns':time.time_ns() + (timeout + 90) * 1000000000,'state':'armed'}
        atomic_json(self.guard_path,guard,new=True)

    def recovery_due(self):
        record,_ = self.load_binding()
        if not self.guard_path.exists():
            return False
        guard = self.guard()
        if record['phase'] == 'rolled_back':
            return False
        if record['phase'] == 'committed' and record['traffic_verified'] and guard['state'] == 'committed':
            return False
        if guard['state'] != 'armed':
            return True
        return needs_recovery(guard,current_boot=boot_id(),now_ns=time.time_ns(),controller_start=process_start(guard['controller_pid']))

    def terminate_expired_controller(self):
        guard = self.guard()
        if (guard['state'] != 'armed' or guard['boot_id'] != boot_id() or time.time_ns() < guard['deadline_ns']
                or process_start(guard['controller_pid']) != guard['controller_start']):
            return
        pid = guard['controller_pid']
        root = Path('/proc') / str(pid)
        args = (root / 'cmdline').read_bytes().rstrip(b'\0').split(b'\0')
        prefix = [b'/usr/bin/python3',b'-B',str(self.directory / 'runtime/browser_lifecycle.py').encode('ascii')]
        suffix = [b'--instance',self.instance.encode('ascii'),b'--receipt-directory',str(self.directory).encode('ascii')]
        if (args[:3] != prefix or len(args) < 8 or args[3] not in (b'switch',b'rollback',b'recover')
                or args[4:8] != suffix or root.stat().st_uid != 0 or pid == os.getpid()):
            manage.fail('An expired controller identity requires manual inspection; no process was signaled.')
        if not hasattr(os,'pidfd_open') or not hasattr(signal,'pidfd_send_signal'):
            manage.fail('Exact-identity process signaling is unavailable.')
        descriptor = os.pidfd_open(pid)
        try:
            if process_start(pid) != guard['controller_start']:
                return
            signal.pidfd_send_signal(descriptor,signal.SIGTERM)
            deadline = self.clock() + 30
            while process_start(pid) == guard['controller_start'] and self.clock() < deadline:
                self.sleep(0.2)
            if process_start(pid) == guard['controller_start']:
                signal.pidfd_send_signal(descriptor,signal.SIGKILL)
                self.sleep(0.5)
        except ProcessLookupError:
            pass
        finally:
            os.close(descriptor)

    def serve(self):
        record,binding = self.load_binding()
        candidate,state,current = self.up.verify(record)
        if (record['phase'] not in ('switching','testing','committed') or not record['candidate_id'] or current['running']
                or current['restart_policy']['Name'] != 'no'):
            manage.fail('Native service is not authorized by this stopped-original upgrade phase.')
        guard = self.guard()
        if record['phase'] == 'committed':
            if not record['traffic_verified'] or guard['state'] != 'committed':
                manage.fail('Committed service is missing verified traffic or its recovery binding.')
        elif guard['state'] != 'armed' or needs_recovery(guard,current_boot=boot_id(),now_ns=time.time_ns(),controller_start=process_start(guard['controller_pid'])):
            manage.fail('The traffic trial no longer has an active controller.')
        self.stop_runtime()
        self.run_pipe(record,binding)
        # A long-running server ending successfully still needs supervised recovery.
        manage.fail('Native server exited; restart is required.')

    def rollback(self):
        self.up.deadline = None
        record,binding = self.load_binding()
        self.up.verify(record)
        if self.guard_path.exists():
            guard = self.guard()
            if guard['state'] != 'armed':
                guard.update(state='armed',boot_id=boot_id(),controller_pid=os.getpid(),
                             controller_start=process_start(os.getpid()),deadline_ns=time.time_ns() + 180 * 1000000000)
                atomic_json(self.guard_path,guard)
        self.systemctl('disable',self.unit('.service'),allow_failure=True)
        self.systemctl('stop',self.unit('.service'))
        self.stop_runtime()
        self.up.rollback(record,network_namespace=binding['network_namespace'])
        if self.guard_path.exists():
            guard = self.guard()
            guard['state'] = 'rolled_back'
            atomic_json(self.guard_path,guard)
        self.systemctl('stop',self.unit('-recovery.timer'))

    def recover(self):
        if not self.recovery_due():
            return
        self.rollback()
        print('OPENFLUX_ORIGINAL_RESTORED',flush=True)

    def switch(self, timeout=300):
        if type(timeout) is not int or not 30 <= timeout <= 600:
            manage.fail('The browser traffic trial must be bounded to 30 through 600 seconds.')
        record,_ = self.load_binding()
        if record['phase'] not in ('prepared','document_failed') or self.up.proof_path.exists() or self.guard_path.exists():
            manage.fail('The browser trial requires an unused, inactive upgrade receipt.')
        self.check_document()
        record,binding = self.load_binding()
        candidate,state,current = self.up.verify(record)
        if current != record['original'] or not record['document_checked']:
            manage.fail('Original state changed during document validation.')
        record['phase'] = 'creating_candidate'
        self.up.save(record)
        identity = candidate.docker(*candidate.container_arguments(state,network_id=current['state']['network_id'],bootstrap_stdio=True)).stdout.strip()
        if not manage.HASH_RE.fullmatch(identity):
            manage.fail('Invalid native candidate identity.')
        record['candidate_id'] = state['container_id'] = identity
        candidate.write_state(state)
        with (candidate.root / 'state.json').open('r+b') as stream:
            os.fsync(stream.fileno())
        sync_directory(candidate.root)
        record['phase'] = 'switching'
        self.up.save(record)
        self.arm(record,timeout)
        try:
            self.up.verify(record)
            original_id = current['state']['container_id']
            self.up.original.docker('container','update','--restart','no',original_id)
            if current['running']:
                self.up.original.docker('container','stop','--time','15',original_id)
            stopped = self.up.snapshot_original([identity])
            if stopped['running'] or stopped['restart_policy']['Name'] != 'no':
                manage.fail('Original process did not stop; candidate was not started.')
            record['trial_started_ns'] = time.time_ns()
            record['trial_deadline_ns'] = record['trial_started_ns'] + timeout * 1000000000
            record['phase'] = 'testing'
            self.up.save(record)
            deadline = self.clock() + timeout
            self.up.deadline = deadline
            self.systemctl('start',self.unit('.service'))
            print('OPENFLUX_UPGRADE_AWAITING_TRAFFIC',flush=True)
            while not self.up.traffic_proof(record):
                if self.clock() >= deadline:
                    manage.fail('Real authenticated traffic proof timed out.')
                _,_,status = self.up.verify(record)
                if status['running']:
                    manage.fail('The original unexpectedly restarted during the trial.')
                self.sleep(1)
            self.up.healthy(record,candidate,state,duration=10)
            self.up.verify(record)
            if self.systemctl('is-active',self.unit('.service')).stdout.strip() != 'active':
                manage.fail('The browser service is not active after traffic verification.')
            if not current['running']:
                self.systemctl('stop',self.unit('.service'))
            else:
                self.systemctl('enable',self.unit('.service'))
            record.update(phase='committed',traffic_verified=True)
            self.up.save(record)
            guard = self.guard()
            guard['state'] = 'committed'
            atomic_json(self.guard_path,guard)
            self.systemctl('stop',self.unit('-recovery.timer'))
        except BaseException:
            self.rollback()
            raise
        finally:
            self.up.deadline = None


def main(argv=None):
    parser = upgrade.PrivateParser(description=__doc__)
    parser.add_argument('command',choices=('install','check','check-document','switch','serve','stop-runtime','recover','rollback'))
    parser.add_argument('--instance',required=True)
    parser.add_argument('--receipt-directory',type=Path,required=True)
    parser.add_argument('--browser-image')
    parser.add_argument('--seccomp',type=Path)
    parser.add_argument('--seccomp-sha256')
    parser.add_argument('--trial-seconds',type=int,default=300)
    parser.add_argument('--apply',action='store_true')
    args = parser.parse_args(argv)
    os.umask(0o077)
    if args.command != 'check' and not args.apply:
        parser.error('Mutations require --apply.')
    try:
        for signum in (signal.SIGTERM,signal.SIGINT):
            signal.signal(signum,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
        lifecycle = Lifecycle(args.instance,args.receipt_directory)
        if args.command in ('serve','stop-runtime','recover'):
            # PID 1 pins this unit to its own network namespace before dropping
            # capabilities. Reading PID 1's /proc entry would require ptrace
            # rights; compare our namespace with the root-verified binding.
            _,binding = lifecycle.load_binding()
            lifecycle.up.original.preflight(network_namespace=binding['network_namespace'])
        else:
            lifecycle.up.original.preflight()
        if args.command in ('serve','stop-runtime'):
            getattr(lifecycle,args.command.replace('-','_'))()
        elif args.command == 'recover':
            # Runtime start orders after this oneshot. A live trial already owns
            # the mutation lock, so a no-op recovery must not contend with it.
            if lifecycle.recovery_due():
                lifecycle.terminate_expired_controller()
                with upgrade.mutation_lock(args.instance):
                    lifecycle.recover()
        elif args.command == 'check':
            record,_ = lifecycle.load_binding()
            lifecycle.up.verify(record)
            print(json.dumps({'phase':record['phase'],'browser_binding_valid':True,'traffic_verified':record['traffic_verified']}))
        else:
            with upgrade.mutation_lock(args.instance):
                if args.command == 'install':
                    if not all((args.browser_image,args.seccomp,args.seccomp_sha256)):
                        parser.error('Install requires the pinned image and sandbox policy.')
                    lifecycle.install(args.browser_image,args.seccomp,args.seccomp_sha256)
                elif args.command == 'switch':
                    lifecycle.switch(args.trial_seconds)
                else:
                    getattr(lifecycle,args.command.replace('-','_'))()
    except KeyboardInterrupt:
        # systemctl stop has already unwound run_pipe's owned cleanup. It is
        # not a failed runtime and must not trigger an on-failure restart.
        if args.command == 'serve':
            print('OPENFLUX_RUNTIME_STOPPED',flush=True)
            return 0
        print('OPENFLUX_LIFECYCLE_FAILED: operation interrupted; inspect owned state.',file=sys.stderr)
        return 1
    except Exception:
        print('OPENFLUX_LIFECYCLE_FAILED: inspect owned state; private details suppressed.',file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
