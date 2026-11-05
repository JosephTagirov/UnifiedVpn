import io
import tarfile
import unittest

from standby_tls import read_member


class ArchiveMemberTests(unittest.TestCase):
    def archive(self, links, files):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            for name, payload in files.items():
                info = tarfile.TarInfo(name)
                info.size = len(payload)
                archive.addfile(info, io.BytesIO(payload))
            for name, target in links.items():
                info = tarfile.TarInfo(name)
                info.type = tarfile.SYMTYPE
                info.linkname = target
                archive.addfile(info)
        buffer.seek(0)
        archive = tarfile.open(fileobj=buffer)
        self.addCleanup(archive.close)
        return archive

    def test_reads_regular_certificate_member(self):
        archive = self.archive({}, {'cert.pem': b'certificate'})
        self.assertEqual(read_member(archive, 'cert.pem'), b'certificate')

    def test_resolves_letsencrypt_relative_archive_link(self):
        archive = self.archive({'etc/letsencrypt/live/site/cert.pem': '../../archive/site/cert1.pem'}, {'etc/letsencrypt/archive/site/cert1.pem': b'certificate'})
        self.assertEqual(read_member(archive, 'etc/letsencrypt/live/site/cert.pem'), b'certificate')

    def test_rejects_archive_root_escape(self):
        for target in ['../../etc/shadow', '/etc/shadow']:
            with self.subTest(target=target):
                archive = self.archive({'link': target}, {})
                with self.assertRaises(ValueError):
                    read_member(archive, 'link')

    def test_rejects_link_cycle(self):
        archive = self.archive({'one': 'two', 'two': 'one'}, {})
        with self.assertRaises(ValueError):
            read_member(archive, 'one')

    def test_rejects_empty_or_oversized_material(self):
        for payload in [b'', b'x' * (128 * 1024 + 1)]:
            archive = self.archive({}, {'cert.pem': payload})
            with self.assertRaises(ValueError):
                read_member(archive, 'cert.pem')


if __name__ == '__main__':
    unittest.main()
