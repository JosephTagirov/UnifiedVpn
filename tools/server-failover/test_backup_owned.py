import tarfile
import unittest

from backup_owned import member_filter


class BackupFilterTests(unittest.TestCase):
    def test_excluded_large_browser_files_do_not_remove_small_identity_metadata(self):
        excluded = {"opt/unifiedvpn-openflux-browser-example/openflux", "opt/unifiedvpn-openflux-browser-example/source.tar.gz"}
        for name in excluded:
            self.assertIsNone(member_filter(tarfile.TarInfo(name), excluded))
        metadata = tarfile.TarInfo("opt/unifiedvpn-openflux-browser-example/server.json")
        self.assertIs(member_filter(metadata, excluded), metadata)

    def test_existing_native_and_consistent_database_coverage_survives_filtering(self):
        for name in ["etc/unifiedvpn-native-ha/config.json", "consistent/control.db", "opt/unifiedvpn-control/openflux-exit"]:
            member = tarfile.TarInfo(name)
            self.assertIs(member_filter(member), member)
        self.assertIsNone(member_filter(tarfile.TarInfo("var/lib/unifiedvpn-control/control.db-wal")))


if __name__ == "__main__":
    unittest.main()
