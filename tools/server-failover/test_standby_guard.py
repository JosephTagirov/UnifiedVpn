import os
import unittest
from unittest.mock import patch

import standby_guard


class StandbyGuardTests(unittest.TestCase):
    def test_flag_is_explicit_and_preserves_normal_primary_default(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertFalse(standby_guard.enabled())
        with patch.dict(os.environ, {"UVPN_CONTROL_READ_ONLY": "1"}):
            self.assertTrue(standby_guard.enabled())

    def test_session_authentication_and_existing_direct_exports_remain_available(self):
        for suffix in ["/auth/login", "/auth/logout", "/claim", "/api/telegram/check", "/api/telegram/export", "/api/telegram/web-export", "/api/devices/" + "a" * 32 + "/export", "/api/existing/" + "b" * 32 + "/export"]:
            self.assertTrue(standby_guard.allowed_post("/vpn-control" + suffix))

    def test_identity_mutations_passwords_shares_and_unknown_routes_are_denied(self):
        for suffix in ["/auth/password", "/api/devices", "/api/quick-issue", "/api/bundles", "/api/app-configs", "/api/clients", "/api/policy", "/api/carriers/jitsi", "/api/support/setup", "/api/notifications", "/api/unknown"]:
            self.assertFalse(standby_guard.allowed_post("/vpn-control" + suffix))
        for action in ["enable", "disable", "extend", "save", "share", "import", "repair", "delete", "invite", "term", "client", "bundle", "app-config"]:
            for group in ["devices", "existing"]:
                self.assertFalse(standby_guard.allowed_post("/vpn-control/api/" + group + "/" + "c" * 32 + "/" + action))


if __name__ == "__main__":
    unittest.main()
