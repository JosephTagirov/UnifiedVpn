import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import call, patch
import urllib.error


SPEC = importlib.util.spec_from_file_location(
    "test_upstream_notifier_module",
    Path(__file__).resolve().parents[1] / "upstream_notifier.py",
)
notifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(notifier)

FAKE_TOKEN = "100000:synthetic-test-token"
FAKE_CHAT_ID = "1234567"
PROJECTS = {project["id"]: project for project in notifier.PROJECTS}


def application_asset(platform="android", build="2026092101", asset_id=10):
    suffix = "universal.apk" if platform == "android" else "amd64-installer.exe"
    return {
        "id": asset_id,
        "name": f"UnifiedVPN-0.0.14-build.{build}-{platform}-{suffix}",
        "size": 1024,
        "state": "uploaded",
        "digest": "sha256:" + "a" * 64,
    }


def application_release(assets=None):
    return {
        "id": 88,
        "tag_name": "v0.0.14",
        "html_url": "https://github.com/JosephTagirov/UnifiedVpn/releases/tag/v0.0.14",
        "published_at": "2026-09-21T00:00:00Z",
        "draft": False,
        "prerelease": False,
        "assets": [application_asset()] if assets is None else assets,
    }


def version_for(project, revision=1):
    if project.get("tracking") == "commit":
        identity = hashlib.sha256(f"{project['id']}:{revision}".encode()).hexdigest()[:40]
        return {
            "identity": f"commit:{identity}",
            "version": identity[:12],
            "source": "commit",
            "url": f"https://github.com/{project['repository']}/commit/{identity}",
            "published_at": "2026-09-21T00:00:00Z",
        }
    return {
        "identity": f"release:{revision}",
        "version": f"v{revision}.0.0",
        "source": "release",
        "url": f"https://github.com/{project['repository']}/releases/tag/v{revision}.0.0",
        "published_at": "2026-09-21T00:00:00Z",
    }


class NotifierTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.state_path = Path(temporary.name) / "state.json"
        network = patch.object(
            notifier.urllib.request,
            "urlopen",
            side_effect=AssertionError("Live network requests are forbidden in notifier tests"),
        )
        network.start()
        self.addCleanup(network.stop)

    def state_for(self, ids=None):
        return {
            "schema": 1,
            "projects": {
                project_id: version_for(PROJECTS[project_id])
                for project_id in (PROJECTS if ids is None else ids)
            },
        }

    def run_notifier(self, *, latest=None, send=None, secrets=True):
        stdout, stderr = io.StringIO(), io.StringIO()
        environment = {"STATE_FILE": str(self.state_path)}
        if secrets:
            environment.update(TELEGRAM_BOT_TOKEN=FAKE_TOKEN, TELEGRAM_CHAT_ID=FAKE_CHAT_ID)
        with (
            patch.dict(os.environ, environment, clear=True),
            patch.object(notifier, "latest_project_version", side_effect=latest or version_for) as lookup,
            patch.object(notifier, "send_bot_message", side_effect=send) as sender,
            contextlib.redirect_stdout(stdout),
            contextlib.redirect_stderr(stderr),
        ):
            result = notifier.main()
        output = stdout.getvalue() + stderr.getvalue()
        self.assertNotIn(FAKE_TOKEN, output)
        self.assertNotIn(FAKE_CHAT_ID, output)
        return result, lookup, sender, output

    def test_registry_preserves_apps_and_tracks_the_actual_pinned_cores(self):
        self.assertEqual(len(PROJECTS), len(notifier.PROJECTS))
        self.assertEqual(
            {project_id: project["repository"] for project_id, project in PROJECTS.items()},
            {
                "olcbox": "alananisimov/olcbox",
                "amnezia": "amnezia-vpn/amnezia-client",
                "olcrtc": "openlibrecommunity/olcrtc",
                "awg": "Throneproj/sing-box",
                "openflux": "p1neappleXpress/OpenFlux",
                "unifiedvpn": "JosephTagirov/UnifiedVpn",
            },
        )
        for project_id in ("olcbox", "amnezia"):
            self.assertEqual("release", PROJECTS[project_id]["tracking"])
        for project_id in ("olcrtc", "awg", "openflux"):
            self.assertEqual("commit", PROJECTS[project_id]["tracking"])
        self.assertEqual("release-build", PROJECTS["unifiedvpn"]["tracking"])
        self.assertEqual(
            {project_id: project["ref"] for project_id, project in PROJECTS.items() if "ref" in project},
            {"awg": "wip/1.14.0"},
        )

    def unified_version(self, release):
        with patch.object(notifier, "request_json", return_value=(200, release)) as request:
            latest = notifier.latest_project_version(PROJECTS["unifiedvpn"])
        request.assert_called_once_with("https://api.github.com/repos/JosephTagirov/UnifiedVpn/releases/latest")
        return latest

    def test_unifiedvpn_uses_stable_release_and_reports_platform_builds(self):
        latest = self.unified_version(application_release([
            application_asset(build="2026092102"),
            application_asset("windows", "2026092101", 11),
            application_asset(build="2026092101", asset_id=12),
        ]))
        self.assertEqual("release", latest["source"])
        self.assertEqual("v0.0.14", latest["version"])
        self.assertEqual("Android: 2026092102, Windows: 2026092101", latest["builds"])
        message = notifier.notification_text(PROJECTS["unifiedvpn"], latest, first_run=False)
        self.assertIn("Unified VPN", message)
        self.assertIn(latest["version"], message)
        self.assertIn(latest["builds"], message)
        self.assertIn(latest["url"], message)

    def test_same_unifiedvpn_release_id_and_version_detect_new_build(self):
        old = self.unified_version(application_release())
        new = self.unified_version(application_release([application_asset(build="2026092102", asset_id=11)]))
        self.assertEqual(old["version"], new["version"])
        self.assertNotEqual(old["identity"], new["identity"])
        self.assertEqual("Android: 2026092102", new["builds"])

    def test_unifiedvpn_replaced_asset_is_detected_without_build_bump(self):
        old = self.unified_version(application_release())
        for changes in ({"id": 99}, {"digest": "sha256:" + "b" * 64}, {"size": 2048}):
            with self.subTest(changes=changes):
                asset = dict(application_asset(), **changes)
                new = self.unified_version(application_release([asset]))
                self.assertNotEqual(old["identity"], new["identity"])

    def test_unifiedvpn_ignores_notes_download_counts_asset_order_and_non_app_files(self):
        assets = [application_asset(), application_asset("windows", asset_id=11)]
        old = self.unified_version(application_release(assets))
        edited_assets = [dict(asset, download_count=20, updated_at="2026-09-22T00:00:00Z") for asset in reversed(assets)]
        edited_assets.extend([
            dict(application_asset(), name="UnifiedVPN-upstream-notifier-2026092102.zip"),
            dict(application_asset(), name="UnifiedVPN-0.0.14-build.2026092101-android-universal.apk.sha256"),
        ])
        edited = dict(application_release(edited_assets), body="Changed release notes", name="New heading")
        self.assertEqual(old, self.unified_version(edited))

    def test_unifiedvpn_legacy_assets_without_build_number_still_work(self):
        release = application_release([dict(application_asset(), name="UnifiedVPN-0.0.8-android-universal.apk")])
        latest = self.unified_version(release)
        self.assertEqual("", latest["builds"])
        self.assertEqual("release", latest["source"])

    def test_unifiedvpn_recognizes_windows_and_linux_package_extensions(self):
        for platform, label, suffix in (
            ("windows", "Windows", ".exe"),
            ("windows", "Windows", ".msi"),
            ("windows", "Windows", ".zip"),
            ("linux", "Linux", ".AppImage"),
            ("linux", "Linux", ".deb"),
            ("linux", "Linux", ".rpm"),
            ("linux", "Linux", ".tar.gz"),
        ):
            with self.subTest(platform=platform, suffix=suffix):
                name = f"UnifiedVPN-0.0.14-build.2026092102-{platform}-amd64{suffix}"
                latest = self.unified_version(application_release([dict(application_asset(), name=name)]))
                self.assertEqual(f"{label}: 2026092102", latest["builds"])

    def test_unifiedvpn_missing_stable_release_does_not_fall_back_to_unpublished_commit(self):
        with patch.object(notifier, "request_json", return_value=(404, {})) as request:
            with self.assertRaisesRegex(RuntimeError, "did not return a stable release"):
                notifier.latest_project_version(PROJECTS["unifiedvpn"])
        self.assertEqual(1, request.call_count)

    def test_unifiedvpn_rejects_previews_drafts_and_releases_without_uploaded_apps(self):
        for release in (
            dict(application_release(), draft=True),
            dict(application_release(), prerelease=True),
            application_release([]),
            application_release([dict(application_asset(), state="starter")]),
            application_release([dict(application_asset(), size=0)]),
            application_release([dict(application_asset(), name="checksums.txt")]),
        ):
            with self.subTest(release=release):
                with self.assertRaises(RuntimeError):
                    self.unified_version(release)

    def test_unifiedvpn_build_parser_matches_app_variants_and_ignores_overlong_values(self):
        for separator in (".", "-", "_", ""):
            name = f"UnifiedVPN-0.0.14-BUILD{separator}2026092101-windows-amd64.zip"
            latest = self.unified_version(application_release([dict(application_asset(), name=name)]))
            self.assertEqual("Windows: 2026092101", latest["builds"])
        latest = self.unified_version(application_release([application_asset(build="9" * 19)]))
        self.assertEqual("", latest["builds"])

    def test_unifiedvpn_build_notification_is_saved_and_not_resent(self):
        state = self.state_for()
        state["projects"]["unifiedvpn"] = self.unified_version(application_release())
        notifier.save_state(self.state_path, state)
        new = self.unified_version(application_release([application_asset(build="2026092102", asset_id=11)]))

        def latest(project):
            return new if project["id"] == "unifiedvpn" else version_for(project)

        result, _, sender, _ = self.run_notifier(latest=latest)
        self.assertEqual(0, result)
        sender.assert_called_once()
        self.assertIn("2026092102", sender.call_args.args[2])
        self.assertEqual(new, notifier.load_state(self.state_path)["projects"]["unifiedvpn"])
        result, _, sender, _ = self.run_notifier(latest=latest)
        self.assertEqual(0, result)
        sender.assert_not_called()

    def test_existing_five_project_install_only_receives_unifiedvpn_baseline(self):
        existing = self.state_for(project_id for project_id in PROJECTS if project_id != "unifiedvpn")
        notifier.save_state(self.state_path, existing)
        result, _, sender, _ = self.run_notifier()
        self.assertEqual(0, result)
        sender.assert_called_once()
        self.assertIn("Unified VPN", sender.call_args.args[2])
        saved = notifier.load_state(self.state_path)["projects"]
        for project_id, previous in existing["projects"].items():
            self.assertEqual(previous, saved[project_id])

    def test_unifiedvpn_release_without_ready_assets_retains_notification_state(self):
        state = self.state_for()
        state["projects"]["unifiedvpn"] = self.unified_version(application_release())
        notifier.save_state(self.state_path, state)

        def incomplete(project):
            if project["id"] == "unifiedvpn":
                return self.unified_version(application_release([]))
            return version_for(project)

        result, _, sender, output = self.run_notifier(latest=incomplete)
        self.assertEqual(1, result)
        sender.assert_not_called()
        self.assertIn("unifiedvpn", output)
        self.assertEqual(state, notifier.load_state(self.state_path))

    def test_application_release_behavior_is_unchanged(self):
        project = PROJECTS["olcbox"]
        release = {
            "id": 55,
            "tag_name": "v1.2.3",
            "html_url": "https://github.com/alananisimov/olcbox/releases/tag/v1.2.3",
            "published_at": "2026-09-21T00:00:00Z",
        }
        with patch.object(notifier, "request_json", return_value=(200, release)) as request:
            latest = notifier.latest_project_version(project)
        request.assert_called_once_with("https://api.github.com/repos/alananisimov/olcbox/releases/latest")
        self.assertEqual("release:55", latest["identity"])
        self.assertEqual("v1.2.3", latest["version"])
        self.assertEqual("release", latest["source"])

    def test_project_without_tracking_option_retains_release_first_policy(self):
        project = {"id": "old", "name": "Old config", "repository": "example/project"}
        with patch.object(notifier, "request_json", return_value=(200, {"id": 1, "tag_name": "v1"})) as request:
            latest = notifier.latest_project_version(project)
        request.assert_called_once_with("https://api.github.com/repos/example/project/releases/latest")
        self.assertEqual("release", latest["source"])

    def test_application_without_stable_release_falls_back_to_commit(self):
        sha = "a" * 40
        project = PROJECTS["amnezia"]
        root = f"https://api.github.com/repos/{project['repository']}"
        with patch.object(
            notifier, "request_json", side_effect=[(404, {}), (200, {"sha": sha})]
        ) as request:
            latest = notifier.latest_project_version(project)
        self.assertEqual([call(f"{root}/releases/latest"), call(f"{root}/commits/HEAD")], request.call_args_list)
        self.assertEqual(f"commit:{sha}", latest["identity"])
        self.assertEqual(sha[:12], latest["version"])

    def test_cores_read_default_branch_without_waiting_for_releases(self):
        sha = "b" * 40
        for project_id in ("olcrtc", "openflux"):
            with self.subTest(project=project_id):
                project = PROJECTS[project_id]
                commit = {"sha": sha, "commit": {"committer": {"date": "2026-09-21T12:00:00Z"}}}
                with patch.object(notifier, "request_json", return_value=(200, commit)) as request:
                    latest = notifier.latest_project_version(project)
                request.assert_called_once_with(f"https://api.github.com/repos/{project['repository']}/commits/HEAD")
                self.assertEqual(f"commit:{sha}", latest["identity"])
                self.assertEqual("commit", latest["source"])
                self.assertEqual("2026-09-21T12:00:00Z", latest["published_at"])
                self.assertEqual(f"https://github.com/{project['repository']}/commit/{sha}", latest["url"])

    def test_awg_reads_explicit_development_branch_without_default_branch_or_release_lookup(self):
        sha = "c" * 40
        with patch.object(notifier, "request_json", return_value=(200, {"sha": sha})) as request:
            latest = notifier.latest_project_version(PROJECTS["awg"])
        request.assert_called_once_with(
            "https://api.github.com/repos/Throneproj/sing-box/commits/wip%2F1.14.0"
        )
        self.assertEqual(f"commit:{sha}", latest["identity"])
        self.assertEqual(sha[:12], latest["version"])
        self.assertEqual(f"https://github.com/Throneproj/sing-box/commit/{sha}", latest["url"])

    def test_commit_ref_is_encoded_as_a_single_url_path_component(self):
        sha = "d" * 40
        for ref, encoded in (
            ("refs/heads/topic+fix#1%done", "refs%2Fheads%2Ftopic%2Bfix%231%25done"),
            ("release?since=2026", "release%3Fsince%3D2026"),
            (sha, sha),
        ):
            with self.subTest(ref=ref):
                project = dict(PROJECTS["openflux"], ref=ref)
                with patch.object(notifier, "request_json", return_value=(200, {"sha": sha})) as request:
                    latest = notifier.latest_project_version(project)
                request.assert_called_once_with(
                    f"https://api.github.com/repos/{project['repository']}/commits/{encoded}"
                )
                self.assertEqual(f"commit:{sha}", latest["identity"])

    def test_invalid_explicit_ref_is_not_silently_replaced_with_head(self):
        for ref in ("", " ", " branch", "branch ", None, 42):
            with self.subTest(ref=ref):
                with patch.object(notifier, "request_json") as request:
                    with self.assertRaisesRegex(RuntimeError, "Invalid commit ref"):
                        notifier.latest_project_version(dict(PROJECTS["awg"], ref=ref))
                request.assert_not_called()

    def test_application_commit_fallback_uses_explicit_ref_without_changing_release_priority(self):
        project = dict(PROJECTS["amnezia"], ref="release/next")
        root = f"https://api.github.com/repos/{project['repository']}"
        with patch.object(notifier, "request_json", return_value=(200, {"id": 1, "tag_name": "v1"})) as request:
            latest = notifier.latest_project_version(project)
        request.assert_called_once_with(f"{root}/releases/latest")
        self.assertEqual("release", latest["source"])
        with patch.object(notifier, "request_json", side_effect=[(404, {}), (200, {"sha": "e" * 40})]) as request:
            latest = notifier.latest_project_version(project)
        self.assertEqual(
            [call(f"{root}/releases/latest"), call(f"{root}/commits/release%2Fnext")],
            request.call_args_list,
        )
        self.assertEqual("commit", latest["source"])

    def test_missing_explicit_awg_branch_does_not_fall_back_or_replace_state(self):
        old = self.state_for()
        notifier.save_state(self.state_path, old)
        original_lookup = notifier.latest_project_version

        def latest(project):
            if project["id"] == "awg":
                return original_lookup(project)
            return version_for(project)

        with patch.object(notifier, "request_json", return_value=(404, {})) as request:
            result, _, sender, _ = self.run_notifier(latest=latest)
        request.assert_called_once_with(
            "https://api.github.com/repos/Throneproj/sing-box/commits/wip%2F1.14.0"
        )
        self.assertEqual(1, result)
        sender.assert_not_called()
        self.assertEqual(old, notifier.load_state(self.state_path))

    def test_switching_awg_branch_notifies_new_sha_once_without_resetting_other_projects(self):
        old = self.state_for()
        notifier.save_state(self.state_path, old)

        def latest(project):
            return version_for(project, 2 if project["id"] == "awg" else 1)

        result, _, sender, _ = self.run_notifier(latest=latest)
        self.assertEqual(0, result)
        sender.assert_called_once()
        self.assertIn(PROJECTS["awg"]["name"], sender.call_args.args[2])
        saved = notifier.load_state(self.state_path)["projects"]
        self.assertEqual(latest(PROJECTS["awg"]), saved["awg"])
        for project_id in PROJECTS:
            if project_id != "awg":
                self.assertEqual(old["projects"][project_id], saved[project_id])
        result, _, sender, _ = self.run_notifier(latest=latest)
        self.assertEqual(0, result)
        sender.assert_not_called()

    def test_unknown_tracking_mode_is_rejected_before_network_request(self):
        with patch.object(notifier, "request_json") as request:
            with self.assertRaisesRegex(RuntimeError, "Unknown update tracking mode"):
                notifier.latest_project_version(dict(PROJECTS["openflux"], tracking="invalid"))
        request.assert_not_called()

    def test_release_api_failure_does_not_masquerade_as_new_commit(self):
        with patch.object(notifier, "request_json", side_effect=RuntimeError("GitHub API returned HTTP 403")) as request:
            with self.assertRaisesRegex(RuntimeError, "HTTP 403"):
                notifier.latest_project_version(PROJECTS["olcbox"])
        self.assertEqual(1, request.call_count)

    def test_missing_commit_is_rejected(self):
        with patch.object(notifier, "request_json", return_value=(404, {})):
            with self.assertRaisesRegex(RuntimeError, "did not return a commit"):
                notifier.latest_project_version(PROJECTS["openflux"])

    def test_first_run_sends_one_baseline_for_each_project(self):
        result, _, sender, _ = self.run_notifier()
        self.assertEqual(0, result)
        self.assertEqual(len(PROJECTS), sender.call_count)
        self.assertEqual(self.state_for(), notifier.load_state(self.state_path))
        for sent in sender.call_args_list:
            self.assertEqual((FAKE_TOKEN, FAKE_CHAT_ID), sent.args[:2])
            self.assertIn("\u041c\u043e\u043d\u0438\u0442\u043e\u0440\u0438\u043d\u0433", sent.args[2])

    def test_existing_install_keeps_old_entries_and_only_adds_new_baselines(self):
        existing = self.state_for(("olcbox", "amnezia"))
        existing["projects"]["unrelated"] = {"identity": "kept"}
        notifier.save_state(self.state_path, existing)

        result, _, sender, _ = self.run_notifier()

        self.assertEqual(0, result)
        self.assertEqual(4, sender.call_count)
        messages = [sent.args[2] for sent in sender.call_args_list]
        for project_id, message in zip(("olcrtc", "awg", "openflux", "unifiedvpn"), messages):
            self.assertIn(PROJECTS[project_id]["name"], message)
        saved = notifier.load_state(self.state_path)
        for project_id, old in existing["projects"].items():
            self.assertEqual(old, saved["projects"][project_id])
        self.assertIn("openflux", saved["projects"])

    def test_unchanged_revisions_do_not_resend_or_rewrite_state(self):
        notifier.save_state(self.state_path, self.state_for())
        with patch.object(notifier, "save_state") as save:
            result, _, sender, _ = self.run_notifier()
        self.assertEqual(0, result)
        sender.assert_not_called()
        save.assert_not_called()

    def test_changed_openflux_commit_is_sent_once_and_saved(self):
        notifier.save_state(self.state_path, self.state_for())

        def newer(project):
            return version_for(project, 2 if project["id"] == "openflux" else 1)

        result, _, sender, _ = self.run_notifier(latest=newer)
        self.assertEqual(0, result)
        sender.assert_called_once()
        message = sender.call_args.args[2]
        self.assertIn("OpenFlux", message)
        self.assertIn("GitHub", message)
        self.assertIn(newer(PROJECTS["openflux"])["version"], message)
        self.assertIn(newer(PROJECTS["openflux"])["url"], message)
        self.assertIn("\u0432\u0440\u0443\u0447\u043d\u0443\u044e", message)

        result, _, sender, _ = self.run_notifier(latest=newer)
        self.assertEqual(0, result)
        sender.assert_not_called()

    def test_failed_openflux_delivery_keeps_last_notified_commit_for_retry(self):
        old = self.state_for()
        notifier.save_state(self.state_path, old)

        def newer(project):
            return version_for(project, 2 if project["id"] == "openflux" else 1)

        result, _, sender, output = self.run_notifier(
            latest=newer, send=RuntimeError("Bot API returned HTTP 429")
        )
        self.assertEqual(1, result)
        sender.assert_called_once()
        self.assertIn("openflux", output)
        self.assertEqual(old, notifier.load_state(self.state_path))

        result, _, sender, _ = self.run_notifier(latest=newer)
        self.assertEqual(0, result)
        sender.assert_called_once()

    def test_one_project_api_failure_does_not_block_other_projects(self):
        def partially_failing(project):
            if project["id"] == "olcrtc":
                raise RuntimeError("GitHub API returned HTTP 503")
            return version_for(project)

        result, _, sender, output = self.run_notifier(latest=partially_failing)
        self.assertEqual(1, result)
        self.assertEqual(len(PROJECTS) - 1, sender.call_count)
        saved = notifier.load_state(self.state_path)["projects"]
        self.assertNotIn("olcrtc", saved)
        self.assertIn("openflux", saved)
        self.assertIn("olcrtc", output)

    def test_unconfigured_notifier_does_not_check_or_send(self):
        result, lookup, sender, _ = self.run_notifier(secrets=False)
        self.assertEqual(2, result)
        lookup.assert_not_called()
        sender.assert_not_called()
        self.assertFalse(self.state_path.exists())

    def test_github_404_is_the_supported_release_fallback(self):
        url = "https://api.github.com/repos/example/project/releases/latest"
        error = urllib.error.HTTPError(url, 404, "Not Found", {}, None)
        with patch.object(notifier.urllib.request, "urlopen", side_effect=error):
            self.assertEqual((404, {}), notifier.request_json(url))

    def test_bot_http_error_does_not_expose_token_or_chat(self):
        error = urllib.error.HTTPError(
            f"https://api.telegram.org/bot{FAKE_TOKEN}/sendMessage", 401, "Unauthorized", {}, None
        )
        with patch.object(notifier.urllib.request, "urlopen", side_effect=error):
            with self.assertRaisesRegex(RuntimeError, "Bot API returned HTTP 401") as caught:
                notifier.send_bot_message(FAKE_TOKEN, FAKE_CHAT_ID, "Synthetic message")
        self.assertNotIn(FAKE_TOKEN, str(caught.exception))
        self.assertNotIn(FAKE_CHAT_ID, str(caught.exception))

    def test_invalid_state_is_not_silently_reset(self):
        with patch.object(Path, "is_file", return_value=True), patch.object(Path, "read_text", return_value="not-json"):
            with self.assertRaisesRegex(RuntimeError, "Cannot read notifier state"):
                notifier.load_state(self.state_path)

    def test_state_writes_are_atomic_and_do_not_store_bot_credentials(self):
        state = self.state_for()
        notifier.save_state(self.state_path, state)
        self.assertEqual(state, json.loads(self.state_path.read_text(encoding="utf-8")))
        self.assertEqual([self.state_path], list(self.state_path.parent.iterdir()))
        contents = self.state_path.read_text(encoding="utf-8")
        self.assertNotIn(FAKE_TOKEN, contents)
        self.assertNotIn(FAKE_CHAT_ID, contents)
        if os.name != "nt":
            self.assertEqual(0o600, self.state_path.stat().st_mode & 0o777)


if __name__ == "__main__":
    unittest.main()
