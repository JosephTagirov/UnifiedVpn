import base64
import json
import unittest
import zlib
from types import SimpleNamespace
from unittest.mock import patch

import adapters


class ManagedEndpointTests(unittest.TestCase):
    def test_exports_use_stable_host_without_changing_credentials(self):
        device = {
            'name': 'Test device',
            'awg_private': 'original-awg-private',
            'awg_ip': '10.8.0.2',
            'awg_psk': 'original-awg-psk',
            'vless_id': 'original-vless-id',
            'openflux_key': 'original-openflux-key',
            'openflux_port': 50023,
            'vkcalls_key': 'original-vk-key',
        }
        stream = {'wsSettings': {'path': '/original-path'}}
        with patch.object(adapters, 'PUBLIC_HOST', 'vpn.example.net'), \
             patch.object(adapters, 'awg_parameters', return_value=('', {'Jc': '4'})), \
             patch.object(adapters, 'awg', return_value=SimpleNamespace(stdout=b'original-server-public\n')), \
             patch.object(adapters.sqlite3, 'connect'), \
             patch.object(adapters, 'inbound', return_value=({}, stream)), \
             patch.object(adapters.pathlib.Path, 'read_text', return_value='{"room":{"id":"test-room"}}'):
            profiles = adapters.profiles(device)

        self.assertIn('Endpoint = vpn.example.net:8443\n', profiles['awg'])
        self.assertIn('PrivateKey = original-awg-private\n', profiles['awg'])
        self.assertIn('PresharedKey = original-awg-psk\n', profiles['awg'])
        self.assertIn('vless://original-vless-id@vpn.example.net:443?', profiles['vless'])
        self.assertIn('sni=panel.meesharr.space', profiles['vless'])
        payload = profiles['openflux'].split('/')[-1]
        session = json.loads(zlib.decompress(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)), -15))
        self.assertEqual(session['transports'][0]['dial'], 'vpn.example.net:50023')
        self.assertEqual(session['context'], 'tcp://vpn.example.net:50023')
        self.assertEqual(session['secret'], device['openflux_key'])


if __name__ == '__main__':
    unittest.main()
