import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import tarfile
import tempfile
import unittest
from unittest.mock import Mock
import zipfile

SOURCE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('prepare_browser_image', SOURCE / 'prepare_browser_image.py')
image = importlib.util.module_from_spec(spec)
spec.loader.exec_module(image)
probe_spec = importlib.util.spec_from_file_location('browser_sandbox_probe', SOURCE / 'browser_sandbox_probe.py')
probe = importlib.util.module_from_spec(probe_spec)
probe_spec.loader.exec_module(probe)


def wheel(items):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as archive:
        for name, data, mode in items:
            info = zipfile.ZipInfo(name)
            info.filename = name
            info.external_attr = mode << 16
            archive.writestr(info, data)
    return output.getvalue()


class BrowserImageTests(unittest.TestCase):
    def test_chromium_153_namespace_status_and_missing_field_fail_closed(self):
        fields = {'Layer 1 Sandbox':'Namespace', 'PID namespaces':'Yes',
                  'Network namespaces':'Yes', 'Seccomp-BPF sandbox':'Yes'}
        self.assertTrue(all(probe.sandbox_checks(fields).values()))
        for key in fields:
            self.assertFalse(all(probe.sandbox_checks({name:value for name,value in fields.items() if name != key}).values()))
        self.assertFalse(probe.sandbox_checks(dict(fields, **{'Layer 1 Sandbox':'None'}))['Namespace sandbox'])
    def test_rejects_paths_links_devices_and_data_install_hooks(self):
        for name, mode in [('x/../../outside', 0o100644), ('/outside', 0o100644),
                           ('C:/outside', 0o100644), ('x\\outside', 0o100644),
                           ('x//outside', 0o100644), ('x/./outside', 0o100644),
                           ('link', stat.S_IFLNK | 0o777), ('device', stat.S_IFCHR),
                           ('package.data/scripts/install', 0o100644)]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                list(image.wheel_entries(wheel([(name, b'payload', mode)])))

    def test_only_node_executable_and_no_write_outside_archive(self):
        result = list(image.wheel_entries(wheel([
            ('playwright/driver/node', b'node', 0o100755),
            ('package.py', b'data', 0o100777)])))
        self.assertEqual(result, [('python/playwright/driver/node', b'node', 0o755),
                                  ('python/package.py', b'data', 0o644)])

    def test_c_headers_preserved_as_inert_data(self):
        result = list(image.wheel_entries(wheel([('greenlet.data/headers/greenlet.h', b'header', 0o100755)])))
        self.assertEqual(result, [('python/greenlet.data/headers/greenlet.h', b'header', 0o644)])

    def test_duplicate_files_across_wheels_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(ValueError):
                image.write_context(Path(temp) / 'context.tar', [('python/x', b'a', 0o644)] * 2)

    def test_context_reproducible_and_existing_file_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            first, second = Path(temp) / 'first.tar', Path(temp) / 'second.tar'
            entries = [('python/x', b'a', 0o644), ('worker.py', b'b', 0o644)]
            image.write_context(first, entries)
            image.write_context(second, entries)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with self.assertRaises(FileExistsError):
                image.write_context(first, [])
            with tarfile.open(first) as archive:
                self.assertTrue(all(x.uid == 0 and x.gid == 0 and x.mtime == 0 for x in archive))

    def test_hash_mismatch_refused(self):
        response = Mock(url='https://files.pythonhosted.org/test', read=Mock(return_value=b'wrong'))
        context = Mock()
        context.__enter__ = Mock(return_value=response)
        context.__exit__ = Mock(return_value=False)
        entry = {'url': response.url, 'bytes': 5, 'sha256': hashlib.sha256(b'right').hexdigest()}
        with self.assertRaises(ValueError):
            image.download(entry, Mock(open=Mock(return_value=context)))

    def test_locked_python_dependencies_and_no_build_code_execution(self):
        lock = json.loads((SOURCE / 'browser-image-lock.json').read_text())
        self.assertEqual({x['package'] for x in lock['wheels']}, {'playwright', 'pyee', 'greenlet', 'typing_extensions'})
        self.assertEqual(next(x['version'] for x in lock['wheels'] if x['package'] == 'pyee'), '13.0.1')
        dockerfile = (SOURCE / 'browser.Dockerfile').read_text()
        self.assertFalse(any(line.startswith(('RUN ', 'ADD ')) for line in dockerfile.splitlines()))
        self.assertIn('USER 10001:10001', dockerfile)
        self.assertIn('COPY python/', dockerfile)

    def test_seccomp_preserves_original_guards_and_adds_only_sandbox_calls(self):
        original = {'defaultAction': 'SCMP_ACT_ERRNO', 'syscalls': [
            {'names': ['read'], 'action': 'SCMP_ACT_ALLOW'} for _ in range(10)]}
        patched = json.loads(image.chromium_seccomp(json.dumps(original).encode('ascii')))
        self.assertEqual(patched['defaultAction'], original['defaultAction'])
        self.assertEqual(patched['syscalls'][:-1], original['syscalls'])
        self.assertEqual(patched['syscalls'][-1]['names'], ['clone', 'setns', 'unshare', 'chroot'])
        original['defaultAction'] = 'SCMP_ACT_ALLOW'
        with self.assertRaises(ValueError):
            image.chromium_seccomp(json.dumps(original).encode('ascii'))


if __name__ == '__main__':
    unittest.main()
