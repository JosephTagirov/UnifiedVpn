import json
import subprocess
import unittest
from unittest.mock import Mock, call, patch

import controller


class StopController(Exception):
    pass


class ControllerStartupTests(unittest.TestCase):
    def setUp(self):
        self.ready_exists = False
        self.fail_create = False
        self.config = {
            "role": "primary",
            "public": "198.51.100.10",
            "peerPublic": "203.0.113.20",
            "peerPrivate": "10.203.84.2",
        }
        self.local = {
            "availableMiB": 1000,
            "freeDiskMiB": 4000,
            "cpuBusyPct": 20,
        }
        self.opener = Mock()
        self.opener.open.side_effect = OSError("peer unavailable during boot")
        self.start_patch("ROOT", Mock())
        self.start_patch("resources", return_value=self.local)
        self.start_patch("pathlib.Path.read_text", autospec=True, side_effect=self.read_text)
        self.start_patch("urllib.request.build_opener", return_value=self.opener)
        self.start_patch("time.monotonic", return_value=10.0)
        self.start_patch("time.sleep", side_effect=StopController)
        self.command = self.start_patch("command", side_effect=self.kernel_command)
        self.start_patch("chain")
        self.start_patch("rule")
        self.start_patch("scopes", return_value=([], []))
        self.start_patch(
            "subprocess.run",
            return_value=subprocess.CompletedProcess([], 0, "", ""),
        )
        self.start_patch("rate")
        self.write = self.start_patch("write")
        self.classifier = self.start_patch("classifier", wraps=controller.classifier)
        self.lease = self.start_patch("lease", wraps=controller.lease)

    def start_patch(self, target, *args, **kwargs):
        patcher = patch("controller." + target, *args, **kwargs)
        value = patcher.start()
        self.addCleanup(patcher.stop)
        return value

    def read_text(self, path, *args, **kwargs):
        if path.name == "node.json":
            return json.dumps(self.config)
        raise FileNotFoundError(str(path))

    def kernel_command(self, *args, **kwargs):
        operation = args[:3]
        if operation == ("ipset", "list", controller.READY):
            return subprocess.CompletedProcess(args, int(not self.ready_exists), "", "")
        if operation == ("ipset", "create", controller.READY):
            if self.fail_create:
                raise RuntimeError("readiness set creation failed")
            self.ready_exists = True
        elif operation == ("ipset", "flush", controller.READY):
            if not self.ready_exists:
                raise RuntimeError("readiness set does not exist")
        else:
            self.fail("Unexpected kernel command " + repr(args))
        return subprocess.CompletedProcess(args, 0, "", "")

    def test_early_boot_initializes_readiness_set_before_first_disabled_lease(self):
        with self.assertRaises(StopController):
            controller.main()

        self.classifier.assert_called_once_with()
        self.lease.assert_called_once_with(False)
        self.command.assert_has_calls(
            [
                call("ipset", "list", controller.READY, check=False),
                call(
                    "ipset", "create", controller.READY,
                    "hash:net", "family", "inet", "timeout", "45",
                ),
                call("ipset", "flush", controller.READY),
            ]
        )
        self.assertFalse(self.write.call_args.args[0]["cascadeEnabled"])

    def test_initial_classifier_failure_prevents_lease_update(self):
        self.fail_create = True

        with self.assertRaisesRegex(RuntimeError, "readiness set creation failed"):
            controller.main()

        self.classifier.assert_called_once_with()
        self.lease.assert_not_called()
        self.write.assert_not_called()


if __name__ == "__main__":
    unittest.main()
