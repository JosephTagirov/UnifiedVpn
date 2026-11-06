from pathlib import Path
import configparser
import unittest

import openflux_ha_bootstrap as bootstrap


SOURCE = b'''[Interface]
Role = exit
Mode = l4
Codec = batched
EncryptionKeyFile = /run/credentials/original.service/key
SessionContext = tcp://context.example:50011
CookieStore = /var/lib/original/cookies.json
Debug = 0
Sensitive = false

[Transport "direct"]
Type = direct
Priority = 50
Listen = 0.0.0.0:50011
'''


class BootstrapTests(unittest.TestCase):
    def test_rewrite_changes_only_owned_key_and_cookie_paths(self):
        state = Path('/var/lib/unifiedvpn-openflux-ha/example-origin')
        actual = bootstrap.rewritten_configuration(SOURCE, 'unifiedvpn-openflux-ha-example-origin.service', state, 50011)
        expected = SOURCE.replace(b'/run/credentials/original.service/key', b'/run/credentials/unifiedvpn-openflux-ha-example-origin.service/key')
        expected = expected.replace(b'/var/lib/original/cookies.json', str(state / 'cookies.json').encode())
        self.assertEqual(actual, expected)
        before = bootstrap.source_configuration(SOURCE, 50011)
        after = bootstrap.source_configuration(actual, 50011)
        self.assertEqual(before['Interface']['SessionContext'], after['Interface']['SessionContext'])

    def test_additional_transport_is_rejected(self):
        with self.assertRaises(ValueError):
            bootstrap.source_configuration(SOURCE + b'\n[Transport "rtc"]\nType = browser\n', 50011)

    def test_source_mode_and_listener_changes_are_rejected(self):
        for old, new in [(b'Role = exit', b'Role = client'), (b'Mode = l4', b'Mode = l3'),
                         (b'Type = direct', b'Type = tun'), (b'50011\n', b'50012\n')]:
            with self.subTest(new=new), self.assertRaises(ValueError):
                bootstrap.source_configuration(SOURCE.replace(old, new), 50011)

    def test_sensitive_logging_is_rejected(self):
        for old, new in [(b'Debug = 0', b'Debug = 1'), (b'Sensitive = false', b'Sensitive = true')]:
            with self.assertRaises(ValueError):
                bootstrap.source_configuration(SOURCE.replace(old, new), 50011)

    def test_duplicate_owned_paths_are_rejected(self):
        with self.assertRaises(configparser.DuplicateOptionError):
            bootstrap.rewritten_configuration(SOURCE.replace(b'CookieStore =', b'CookieStore = /unexpected\nCookieStore ='),
                                              'owned.service', Path('/var/lib/example'), 50011)

    def test_unit_has_unprivileged_resource_and_device_boundaries(self):
        unit = bootstrap.unit_text({'origin': 'example-origin', 'binary': '/opt/example/openflux-exit'})
        for line in ['DynamicUser=yes', 'NoNewPrivileges=true', 'CapabilityBoundingSet=\n', 'AmbientCapabilities=\n',
                     'PrivateDevices=true', 'PrivateTmp=true', 'ProtectSystem=strict', 'MemoryMax=128M',
                     'Environment=GOMEMLIMIT=64MiB GOMAXPROCS=1', 'CPUQuota=50%', 'TasksMax=64']:
            self.assertIn(line, unit)
        self.assertNotIn('/dev/net/tun', unit)
        self.assertIn('StateDirectory=unifiedvpn-openflux-ha/example-origin', unit)
        self.assertIn('--config /run/credentials/unifiedvpn-openflux-ha-example-origin.service/peer.conf', unit)
        self.assertNotIn('%d', unit)


if __name__ == '__main__':
    unittest.main()
