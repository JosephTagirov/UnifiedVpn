import copy
import contextlib
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import test_upgrade

SPEC = importlib.util.spec_from_file_location('browser_lifecycle_test',Path(__file__).resolve().parents[1] / 'browser_lifecycle.py')
lifecycle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lifecycle)


class CanonicalPosix(PurePosixPath):
    def resolve(self, strict=False):
        return self


class PolicyTests(unittest.TestCase):
    def test_units_are_instance_bound_and_do_not_start_runtime_at_install(self):
        units = lifecycle.unit_contents('phone2',CanonicalPosix('/root/upgrade-trial'),CanonicalPosix('/opt/unifiedvpn-openflux-browserabc'))
        self.assertEqual(len(units),3)
        service = units['unifiedvpn-openflux-managed-phone2.service']
        self.assertIn('After=docker.service unifiedvpn-openflux-managed-phone2-recovery.service',service)
        self.assertIn('ExecStopPost=',service)
        self.assertIn('Restart=on-failure',service)
        self.assertIn('CapabilityBoundingSet=\n',service)
        self.assertIn('NetworkNamespacePath=/proc/1/ns/net\n',service)
        self.assertNotIn('--no-sandbox',service)
        self.assertNotIn('PrivateNetwork=',service)
        for unit in units.values():
            self.assertNotIn('unifiedvpn-openflux-managed-default',unit)

    def test_unit_paths_reject_specifiers_spaces_and_shell_characters(self):
        for value in ('/root/a%b','/root/a b','/root/a;command','/root/a\nExecStart=bad'):
            with self.subTest(value=value), self.assertRaises(lifecycle.manage.DeploymentError):
                lifecycle.unit_contents('default',CanonicalPosix(value),CanonicalPosix('/opt/test'))

    def test_recovery_detects_reboot_controller_loss_and_deadline(self):
        guard = {'state':'armed','boot_id':'a','controller_start':123,'deadline_ns':1000}
        self.assertFalse(lifecycle.needs_recovery(guard,current_boot='a',now_ns=999,controller_start=123))
        self.assertTrue(lifecycle.needs_recovery(guard,current_boot='b',now_ns=1,controller_start=123))
        self.assertTrue(lifecycle.needs_recovery(guard,current_boot='a',now_ns=1,controller_start=None))
        self.assertTrue(lifecycle.needs_recovery(guard,current_boot='a',now_ns=1,controller_start=124))
        self.assertTrue(lifecycle.needs_recovery(guard,current_boot='a',now_ns=1000,controller_start=123))
        self.assertFalse(lifecycle.needs_recovery(dict(guard,state='committed'),current_boot='b',now_ns=10000,controller_start=None))


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.fixture = test_upgrade.UpgradeTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.prepare()
        self.up = self.fixture.up
        self.directory = self.up.directory
        self.unit_dir = self.fixture.directory / 'units'
        self.unit_dir.mkdir()
        self.enabled, self.active, self.systemd_calls = set(), set(), []
        def command(args,timeout=30,allow_failure=False):
            if args[0] != 'systemctl':
                return self.fixture.fake(args,timeout=timeout,allow_failure=allow_failure)
            self.systemd_calls.append(args[2:])
            action, names = args[2],args[3:]
            result = SimpleNamespace(returncode=0,stdout='',stderr='')
            if action == 'show': result.stdout = 'not-found\n'
            if action == 'enable': self.enabled.update(names)
            if action == 'disable': self.enabled.difference_update(names)
            if action == 'is-enabled': result.stdout = 'enabled\n' if names[0] in self.enabled else 'disabled\n'
            if action == 'is-active': result.stdout = 'active\n' if names[0] in self.active else 'inactive\n'
            if action in ('start','stop'):
                name = names[0]
                (self.active.add if action == 'start' else self.active.discard)(name)
                if name == self.life.unit('.service'):
                    record = self.up.load()
                    if record['candidate_id']:
                        self.fixture.fake(['docker','--host','unix:///var/run/docker.sock','container',action,record['candidate_id']])
            return result
        self.life = lifecycle.Lifecycle('default',self.directory,command=command,unit_dir=self.unit_dir,
            install_parent=self.fixture.parent,clock=self.fixture.clock,sleep=self.fixture.clock.sleep)
        self.life.up = self.up
        self.up.command = command
        self.life.command = command
        for guard in (patch.object(lifecycle,'manage',test_upgrade.manage), patch.object(lifecycle,'upgrade',test_upgrade.upgrade),
                      patch.object(lifecycle,'sync_directory'), patch.object(lifecycle,'boot_id',return_value='a'*8+'-'+'a'*4+'-'+'a'*4+'-'+'a'*4+'-'+'a'*12),
                      patch.object(lifecycle,'process_start',return_value=12345)):
            guard.start()
            self.addCleanup(guard.stop)
        namespace = patch.object(lifecycle.os,'readlink',return_value='net:[4026531840]')
        namespace.start()
        self.addCleanup(namespace.stop)
        self.life.units = lambda record: {self.life.unit('.service'):'test service\n',
            self.life.unit('-recovery.service'):'test recovery\n',self.life.unit('-recovery.timer'):'test timer\n'}
        self.image = 'sha256:' + '8'*64
        self.fixture.fake.images[self.image] = {'Id':self.image,'Os':'linux','Architecture':'amd64','RepoTags':['browser:test'],
            'Config':{'User':'10001:10001','Labels':{'org.unifiedvpn.openflux.browser-contract':'2'}}}
        self.fixture.fake.tags['browser:test'] = self.image
        self.seccomp = self.fixture.directory / 'seccomp.json'
        self.seccomp.write_text(json.dumps({'defaultAction':'SCMP_ACT_ERRNO','syscalls':[{'names':['read'],'action':'SCMP_ACT_ALLOW'}]}))
        self.life.install(self.image,self.seccomp,test_upgrade.manage.sha256_file(self.seccomp))

    def test_install_only_arms_recovery_services_not_native(self):
        self.assertIn(self.life.unit('-recovery.service'),self.enabled)
        self.assertIn(self.life.unit('-recovery.timer'),self.active)
        self.assertNotIn(self.life.unit('.service'),self.enabled)
        self.assertNotIn(self.life.unit('.service'),self.active)
        self.assertEqual(self.fixture.old_mutations(),[])
        self.fixture.assert_original_files()
        self.assertEqual(self.up.load()['phase'],'prepared')

    def test_existing_unit_or_binding_is_not_overwritten(self):
        before = (self.unit_dir / self.life.unit('.service')).read_bytes()
        with self.assertRaises(test_upgrade.manage.DeploymentError):
            self.life.install(self.image,self.seccomp,test_upgrade.manage.sha256_file(self.seccomp))
        self.assertEqual((self.unit_dir / self.life.unit('.service')).read_bytes(),before)

    def test_helper_or_unit_change_blocks_operation(self):
        source = self.directory / 'runtime/browser_worker.py'
        source.write_bytes(source.read_bytes() + b'\n# changed\n')
        with self.assertRaises(test_upgrade.manage.DeploymentError): self.life.load_binding()
        self.assertEqual(self.fixture.old_mutations(),[])

    def test_changed_unit_rejected(self):
        (self.unit_dir / self.life.unit('.service')).write_text('unexpected\n')
        with self.assertRaises(test_upgrade.manage.DeploymentError): self.life.load_binding()

    def test_missing_guard_does_not_require_mutation_or_restart(self):
        self.assertFalse(self.life.recovery_due())
        self.life.recover()
        self.assertEqual(self.fixture.old_mutations(),[])

    def test_document_failure_does_not_stop_original(self):
        with patch.object(self.life,'run_pipe',side_effect=RuntimeError('synthetic_failure')):
            with self.assertRaises(RuntimeError): self.life.check_document()
        self.assertEqual(self.up.load()['phase'],'document_failed')
        self.assertIsNone(self.up.load()['probe_id'])
        self.assertTrue(self.fixture.old['State']['Running'])
        self.assertEqual(self.fixture.old_mutations(),[])

    def fake_document(self):
        record = self.up.load()
        record.update(phase='prepared',document_checked=True)
        self.up.save(record)

    def switch_with_proof(self):
        with patch.object(self.life,'check_document',side_effect=self.fake_document), \
                patch.object(self.up,'traffic_proof',side_effect=lambda record:self.fixture.proof(record)):
            self.life.switch(30)

    def test_success_uses_systemd_not_docker_autorestart(self):
        self.switch_with_proof()
        record = self.up.load()
        self.assertEqual(record['phase'],'committed')
        self.assertTrue(record['traffic_verified'])
        self.assertEqual(self.life.guard()['state'],'committed')
        self.assertIn(self.life.unit('.service'),self.enabled)
        self.assertFalse(self.fixture.old['State']['Running'])
        candidate = self.fixture.fake.containers[self.up.candidate(record).name]
        self.assertEqual(candidate['HostConfig']['RestartPolicy']['Name'],'no')
        self.fixture.assert_original_files()

    def test_timeout_restores_exact_old_container_and_disables_new_service(self):
        with patch.object(self.life,'check_document',side_effect=self.fake_document),patch.object(self.life,'stop_runtime'):
            with self.assertRaises(test_upgrade.manage.DeploymentError): self.life.switch(30)
        self.assertEqual(self.up.load()['phase'],'rolled_back')
        self.assertTrue(self.fixture.old['State']['Running'])
        self.assertEqual(self.fixture.old['HostConfig']['RestartPolicy']['Name'],'unless-stopped')
        self.assertNotIn(self.life.unit('.service'),self.enabled)
        self.assertEqual(self.life.guard()['state'],'rolled_back')

    def test_recovery_requires_both_committed_receipts(self):
        self.switch_with_proof()
        with patch.object(lifecycle,'boot_id',return_value='b'*36),patch.object(lifecycle,'process_start',return_value=None):
            self.assertFalse(self.life.recovery_due())
            guard = self.life.guard()
            guard['state'] = 'armed'
            lifecycle.atomic_json(self.life.guard_path,guard)
            self.assertTrue(self.life.recovery_due())

    def test_loss_of_controller_recovers_without_traffic_proof(self):
        self.switch_with_proof()
        record = self.up.load()
        record.update(phase='testing',traffic_verified=False)
        self.up.save(record)
        guard = self.life.guard()
        guard['state'] = 'armed'
        lifecycle.atomic_json(self.life.guard_path,guard)
        with patch.object(lifecycle,'process_start',return_value=None),patch.object(self.life,'stop_runtime'):
            self.life.recover()
        self.assertEqual(self.up.load()['phase'],'rolled_back')
        self.assertTrue(self.fixture.old['State']['Running'])
        self.fixture.assert_original_files()

    def test_original_identity_change_blocks_recovery(self):
        self.switch_with_proof()
        self.fixture.old['Id'] = 'f'*64
        with self.assertRaises(test_upgrade.manage.DeploymentError): self.life.rollback()

    def test_nested_rollback_keeps_the_service_namespace_binding(self):
        self.switch_with_proof()
        with patch.object(self.life,'stop_runtime'), patch.object(self.up.original,'preflight') as preflight:
            self.life.rollback()
        preflight.assert_called_once_with(network_namespace='net:[4026531840]')
        self.assertTrue(self.fixture.old['State']['Running'])


class ShutdownTests(unittest.TestCase):
    def invoke(self, command, failure):
        instance = Mock()
        instance.load_binding.return_value = ({}, {'network_namespace':'net:[4026531840]'})
        if command == 'recover':
            instance.recovery_due.side_effect = failure
        else:
            getattr(instance,command.replace('-','_')).side_effect = failure
        with patch.object(lifecycle,'Lifecycle',return_value=instance), patch.object(lifecycle.signal,'signal'), \
                patch.object(lifecycle.os,'umask'), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return lifecycle.main([command,'--instance','default','--receipt-directory','unused','--apply'])

    def test_normal_runtime_stop_exits_successfully_after_cleanup(self):
        self.assertEqual(self.invoke('serve',KeyboardInterrupt()),0)

    def test_runtime_cleanup_error_still_exits_nonzero(self):
        self.assertEqual(self.invoke('serve',RuntimeError('private error')),1)

    def test_interrupted_recovery_is_not_success(self):
        self.assertEqual(self.invoke('recover',KeyboardInterrupt()),1)

    def test_interrupted_cleanup_is_not_success(self):
        self.assertEqual(self.invoke('stop-runtime',KeyboardInterrupt()),1)


if __name__ == '__main__':
    unittest.main()
