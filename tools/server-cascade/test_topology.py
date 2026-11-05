import json
from pathlib import Path
import unittest


class DestinationTopologyTests(unittest.TestCase):
    def setUp(self):
        self.topology = json.loads(Path(__file__).with_name('topology.example.json').read_text())

    def test_two_domestic_paths_and_four_foreign_paths(self):
        domestic = self.topology['futureDomesticPaths']
        foreign = self.topology['futurePaths']
        self.assertEqual({(path['entry'], path['exit']) for path in domestic}, {('ru-a', 'ru-a'), ('ru-b', 'ru-b')})
        self.assertTrue(all(path['destinationClass'] == 'russian' and path['transport'] == 'direct' for path in domestic))
        self.assertEqual({(path['entry'], path['exit']) for path in foreign}, {(ru, eu) for ru in ['ru-a', 'ru-b'] for eu in ['foreign-a', 'foreign-b']})
        self.assertTrue(all(path['destinationClass'] == 'foreign' for path in foreign))

    def test_destination_policy_retains_the_requested_egress_region(self):
        policy = self.topology['futureDestinationRouting']
        self.assertEqual(policy['russianEgress'], 'active-ingress')
        self.assertEqual(policy['foreignEgressPool'], ['foreign-a', 'foreign-b'])
        self.assertEqual(policy['defaultDestinationClass'], 'foreign')
        self.assertEqual(policy['foreignExitUnavailable'], 'unavailable')
        self.assertEqual(policy['dns']['russianServicesVia'], 'active-ingress')
        self.assertEqual(policy['dns']['foreignServicesVia'], 'selected-foreign-exit')
        self.assertLess(policy['classification']['order'].index('foreign-service-domains'), policy['classification']['order'].index('russian-service-domains'))

    def test_future_topology_requires_explicit_provisioning(self):
        policy = self.topology['futureDestinationRouting']
        self.assertFalse(policy['enabled'])
        self.assertEqual(policy['implementationState'], 'design-only')
        self.assertTrue(all(not path['enabled'] for path in self.topology['futurePaths'] + self.topology['futureDomesticPaths']))
        ru_nodes = [node for node in self.topology['nodes'] if node['role'] == 'ru-ingress']
        self.assertEqual(len(ru_nodes), 2)
        self.assertTrue(all(not node['enabled'] and node['publicAddress'] is None and 'russian-egress' in node['capabilities'] for node in ru_nodes))


if __name__ == '__main__':
    unittest.main()
