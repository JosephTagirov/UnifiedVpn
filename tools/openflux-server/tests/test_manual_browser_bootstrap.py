import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import stat
import struct
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


HERE = Path(__file__).resolve().parents[1]
specification = importlib.util.spec_from_file_location("manual_browser_supervisor_tests", HERE / "browser_supervisor.py")
supervisor = importlib.util.module_from_spec(specification)
specification.loader.exec_module(supervisor)
worker, manage = supervisor.worker, supervisor.manage
REQUEST_ID = "a" * 32
DOCUMENT = "https://docs.yandex.ru/docs/view?public-test-placeholder"
STATE = {"owner": "a" * 64, "container_id": "b" * 64, "image_id": "sha256:" + "c" * 64}
INSTANCE = "browsertest9"


def cookie(**changes):
    return dict({"name": "anonymous_check", "value": "public-placeholder", "domain": ".yandex.ru",
                 "path": "/", "secure": True, "expires": 0}, **changes)


def native(**changes):
    return dict({"Id": STATE["container_id"], "Name": "/unifiedvpn-openflux-" + INSTANCE,
                 "Image": STATE["image_id"], "State": {"Running": True},
                 "Config": {"Labels": {manage.LABEL + ".owner": STATE["owner"], manage.LABEL + ".managed": "1"}}},
                **changes)


def instance(clock=lambda: 0, **options):
    manager = SimpleNamespace(instance=INSTANCE, name="unifiedvpn-openflux-" + INSTANCE,
                              docker=Mock(return_value=SimpleNamespace(stdout=json.dumps([native()]))))
    return supervisor.Supervisor(manager, "sha256:" + "d" * 64, "/root/seccomp", "e" * 64,
                                 clock=clock, **options)


class Connection:
    def __init__(self, *, uid=0, change=None, trailing=b"", chunks=None):
        self.uid = uid
        self.change = change or (lambda value: value)
        self.trailing = trailing
        self.chunks = chunks
        self.sent = []
        self.closed = False
        self.timeouts = []

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.closed = True

    def getsockopt(self, *_):
        return struct.pack("3i", 123, self.uid, 0)

    def settimeout(self, timeout):
        self.timeouts.append(timeout)

    def sendall(self, data):
        value = worker.decode_message(data)
        self.sent.append(value)
        if "challenge_token" in value and self.chunks is None:
            reply = self.change({"binding": copy.deepcopy(value), "cookies": [cookie()]})
            self.chunks = [worker.encode_message(reply) + self.trailing, b""]

    def recv(self, size):
        chunk = self.chunks.pop(0)
        if len(chunk) > size:
            self.chunks.insert(0, chunk[size:])
        return chunk[:size]


@contextlib.contextmanager
def sockets(connection, *, exists=False, changed=False, mode=0o600, inspect_failure=False):
    descriptor = 91
    listener = Mock()
    listener.accept.return_value = (connection, None)
    info = SimpleNamespace(st_mode=stat.S_IFSOCK | mode, st_uid=0, st_nlink=1, st_dev=7, st_ino=8)
    replacement = SimpleNamespace(**vars(info))
    replacement.st_ino = 9
    statuses = [info if exists else FileNotFoundError(), info, info, info, replacement if changed else info]
    if inspect_failure:
        statuses[1] = OSError("Private details must not escape")
    with contextlib.ExitStack() as stack:
        stack.enter_context(patch.object(supervisor, "manual_directory", return_value=descriptor))
        stack.enter_context(patch.object(supervisor.socket, "SO_PEERCRED", 17, create=True))
        stack.enter_context(patch.object(supervisor.socket, "AF_UNIX", 1, create=True))
        socket_factory = stack.enter_context(patch.object(supervisor.socket, "socket", return_value=listener))
        stack.enter_context(patch.object(supervisor.os, "stat", side_effect=statuses))
        chmod = stack.enter_context(patch.object(supervisor.os, "chmod"))
        unlink = stack.enter_context(patch.object(supervisor.os, "unlink"))
        close = stack.enter_context(patch.object(supervisor.os, "close"))
        yield SimpleNamespace(listener=listener, factory=socket_factory, chmod=chmod, unlink=unlink, close=close)


class ManualBootstrapTests(unittest.TestCase):
    @unittest.skipUnless(hasattr(socket, "AF_UNIX") and hasattr(socket, "SO_PEERCRED")
                         and hasattr(os, "geteuid") and os.geteuid() == 0,
                         "The real Unix socket/peer credentials regression requires Linux root")
    def test_real_unix_socket_peer_credentials_proc_fd_eof_and_owned_cleanup(self):
        for valid in (True, False):
            with self.subTest(valid_binding=valid), tempfile.TemporaryDirectory(prefix="manual-bootstrap-test-") as temporary:
                parent = os.open(temporary, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                path = Path(temporary) / supervisor.MANUAL_SOCKET
                subject = instance(clock=time.monotonic, manual_bootstrap=True)
                observed, errors = {}, []
                def collector():
                    try:
                        deadline = time.monotonic() + 5
                        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                            client.settimeout(5)
                            while True:
                                try:
                                    client.connect(str(path))
                                    break
                                except (FileNotFoundError, ConnectionRefusedError):
                                    if time.monotonic() >= deadline:
                                        raise TimeoutError("The private test socket was unavailable")
                                    time.sleep(0.005)
                            observed["mode"] = stat.S_IMODE(path.lstat().st_mode)
                            observed["uid"] = struct.unpack("3i", client.getsockopt(
                                socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i")))[1]
                            challenge = bytearray()
                            while not challenge.endswith(b"\n"):
                                part = client.recv(4096)
                                if not part or len(challenge) + len(part) > worker.MAX_MESSAGE + 1:
                                    raise AssertionError("Invalid private test challenge frame")
                                challenge.extend(part)
                            binding = worker.decode_message(bytes(challenge))
                            if not valid:
                                binding["challenge_token"] = "f" * 64
                            client.sendall(worker.encode_message({"binding": binding, "cookies": [cookie()]}))
                            client.shutdown(socket.SHUT_WR)
                            reply = bytearray()
                            while True:
                                part = client.recv(1024)
                                if not part:
                                    break
                                reply.extend(part)
                                if len(reply) > 1024:
                                    raise AssertionError("Oversized private test acknowledgement")
                            observed["reply"] = bytes(reply)
                    except BaseException as error:
                        errors.append(error)
                thread = threading.Thread(target=collector, daemon=True)
                try:
                    with patch.object(supervisor, "manual_directory", side_effect=lambda _: os.dup(parent)):
                        thread.start()
                        result = subject.verify(STATE, DOCUMENT, REQUEST_ID)
                    thread.join(timeout=6)
                    self.assertFalse(thread.is_alive(), "Private collector must exit after socket EOF")
                    self.assertEqual(errors, [])
                    self.assertEqual(observed.get("mode"), 0o600)
                    self.assertEqual(observed.get("uid"), 0)
                    self.assertFalse(path.exists())
                    self.assertFalse(subject.cleanup_failed)
                    if valid:
                        self.assertEqual(result, {"id": REQUEST_ID, "cookies": [cookie()]})
                        self.assertEqual(worker.decode_message(observed["reply"]), {"accepted": True})
                    else:
                        self.assertEqual(result, supervisor.failure(REQUEST_ID))
                        self.assertEqual(observed["reply"], b"")
                finally:
                    os.close(parent)
                    thread.join(timeout=6)

    def test_runtime_path_is_fixed_and_rejects_unowned_names(self):
        self.assertEqual(supervisor.manual_runtime_directory(INSTANCE).as_posix(),
                         "/run/unifiedvpn-openflux-bootstrap-" + INSTANCE)
        for value in ("default", "phone2", "browser../x", "browser", "browser" + "x" * 10, None):
            with self.subTest(value=value), self.assertRaises(manage.DeploymentError):
                supervisor.manual_runtime_directory(value)

    def test_challenge_is_fresh_bound_and_contains_no_document_or_key(self):
        first = supervisor.manual_challenge(STATE, INSTANCE, REQUEST_ID, DOCUMENT)
        second = supervisor.manual_challenge(STATE, INSTANCE, REQUEST_ID, DOCUMENT)
        self.assertNotEqual(first["challenge_token"], second["challenge_token"])
        self.assertEqual(set(first), {"version", "id", "challenge_token", "instance", "owner",
                                      "native_container_id", "document_sha256"})
        self.assertNotIn(DOCUMENT, json.dumps(first))
        for field in ("document_sha256", "challenge_token"):
            self.assertRegex(first[field], "^[0-9a-f]{64}$")
        for request_id in ("A" * 32, "a" * 31, None):
            with self.assertRaises(worker.VerificationError):
                supervisor.manual_challenge(STATE, INSTANCE, request_id, DOCUMENT)
        with self.assertRaises(worker.VerificationError):
            supervisor.manual_challenge(dict(STATE, owner="bad"), INSTANCE, REQUEST_ID, DOCUMENT)

    def test_reply_exact_binding_and_types_reject_replay_or_other_instance(self):
        challenge = supervisor.manual_challenge(STATE, INSTANCE, REQUEST_ID, DOCUMENT)
        for field in challenge:
            binding = dict(challenge)
            binding[field] = True if field == "version" else "other"
            with self.subTest(field=field), self.assertRaises(worker.VerificationError):
                supervisor.manual_response({"binding": binding, "cookies": [cookie()]}, challenge, DOCUMENT)
        for reply in ({"binding": challenge, "cookies": [cookie()], "extra": "PRIVATE"},
                      {"binding": dict(challenge, extra=1), "cookies": [cookie()]},
                      {"binding": challenge, "cookies": []}):
            with self.assertRaises(worker.VerificationError):
                supervisor.manual_response(reply, challenge, DOCUMENT)

    def test_reply_rejects_all_auth_cookie_names_with_repeated_prefixes(self):
        challenge = supervisor.manual_challenge(STATE, INSTANCE, REQUEST_ID, DOCUMENT)
        for name in ("sessionid", "Session_id", "SESSIONID2", "yandex_login", "__Secure-Session_id",
                     "__Host-yandex_login", "__HOST-__secure-__Host-sessionid2", "__Secure-SESSIONID"):
            with self.subTest(name=name), self.assertRaises(worker.VerificationError):
                supervisor.manual_response({"binding": challenge, "cookies": [cookie(name=name)]}, challenge, DOCUMENT)
        for change in ({"domain": ".example.org"}, {"secure": False}, {"path": "/account"}, {"expires": True}):
            with self.assertRaises(worker.VerificationError):
                supervisor.manual_response({"binding": challenge, "cookies": [cookie(**change)]}, challenge, DOCUMENT)

    def test_reply_preserves_valid_user_agent_with_exact_binding(self):
        challenge = supervisor.manual_challenge(STATE, INSTANCE, REQUEST_ID, DOCUMENT)
        value = {"binding": challenge, "cookies": [cookie()], "user_agent": "SyntheticBrowser/1.0"}
        self.assertEqual(supervisor.manual_response(value, challenge, DOCUMENT),
                         {"id": REQUEST_ID, "cookies": [cookie()], "user_agent": "SyntheticBrowser/1.0"})
        for user_agent in (None, False, "", "bad\r\nvalue", "bad\tvalue", "bad\u0080value", "x" * 1025):
            with self.assertRaises(worker.VerificationError):
                supervisor.manual_response(dict(value, user_agent=user_agent), challenge, DOCUMENT)
        with self.assertRaises(worker.VerificationError):
            supervisor.manual_response(dict(value, binding=dict(challenge, id="b" * 32)), challenge, DOCUMENT)

    def test_success_one_root_peer_private_socket_bounded_checks_and_cleanup(self):
        subject, connection = instance(manual_bootstrap=True), Connection()
        with sockets(connection) as resource:
            result = subject.verify(STATE, DOCUMENT, REQUEST_ID)
        self.assertEqual(result, {"id": REQUEST_ID, "cookies": [cookie()]})
        self.assertEqual(connection.sent[-1], {"accepted": True})
        self.assertTrue(connection.closed)
        resource.listener.bind.assert_called_once_with("/proc/self/fd/91/browser.sock")
        resource.listener.accept.assert_called_once()
        resource.listener.close.assert_called_once()
        resource.chmod.assert_called_once_with("browser.sock", 0o600, dir_fd=91, follow_symlinks=False)
        resource.unlink.assert_called_once_with("browser.sock", dir_fd=91)
        resource.close.assert_called_once_with(91)
        self.assertEqual(subject.manager.docker.call_count, 2)
        for call in subject.manager.docker.call_args_list:
            self.assertEqual(call.args, ("container", "inspect", STATE["container_id"]))
            self.assertLessEqual(call.kwargs["timeout"], 3)

    def test_existing_socket_never_adopted_chmodded_or_unlinked(self):
        subject = instance(manual_bootstrap=True)
        with sockets(Connection(), exists=True) as resource:
            self.assertEqual(subject.verify(STATE, DOCUMENT, REQUEST_ID), supervisor.failure(REQUEST_ID))
        resource.factory.assert_not_called()
        resource.chmod.assert_not_called()
        resource.unlink.assert_not_called()
        resource.close.assert_called_once_with(91)

    def test_nonroot_peer_receives_no_challenge_and_no_cookie_reads(self):
        subject, connection = instance(manual_bootstrap=True), Connection(uid=1000)
        with sockets(connection) as resource:
            self.assertEqual(subject.verify(STATE, DOCUMENT, REQUEST_ID), supervisor.failure(REQUEST_ID))
        self.assertEqual(connection.sent, [])
        self.assertTrue(connection.closed)
        resource.unlink.assert_called_once()

    def test_socket_replacement_prevents_cleanup_and_success(self):
        subject = instance(manual_bootstrap=True)
        with sockets(Connection(), changed=True) as resource, contextlib.redirect_stdout(io.StringIO()) as visible:
            self.assertEqual(subject.verify(STATE, DOCUMENT, REQUEST_ID), supervisor.failure(REQUEST_ID))
        self.assertTrue(subject.cleanup_failed)
        resource.unlink.assert_not_called()
        self.assertEqual(visible.getvalue(), "OPENFLUX_BROWSER_CLEANUP_REQUIRED\n")

    def test_bind_followed_by_failed_identity_read_requires_recovery_not_unsafe_unlink(self):
        subject = instance(manual_bootstrap=True)
        with sockets(Connection(), inspect_failure=True) as resource, contextlib.redirect_stdout(io.StringIO()) as visible:
            self.assertEqual(subject.verify(STATE, DOCUMENT, REQUEST_ID), supervisor.failure(REQUEST_ID))
        self.assertTrue(subject.cleanup_failed)
        resource.unlink.assert_not_called()
        resource.close.assert_called_once_with(91)
        resource.listener.close.assert_called_once()
        self.assertEqual(visible.getvalue(), "OPENFLUX_BROWSER_CLEANUP_REQUIRED\n")

    def test_cancellation_propagates_and_closes_owned_resources(self):
        for operation in ("accept", "recv"):
            subject, connection = instance(manual_bootstrap=True), Connection()
            with sockets(connection) as resource:
                if operation == "accept":
                    resource.listener.accept.side_effect = KeyboardInterrupt()
                else:
                    connection.recv = Mock(side_effect=KeyboardInterrupt())
                with self.assertRaises(KeyboardInterrupt):
                    subject.verify(STATE, DOCUMENT, REQUEST_ID)
            resource.listener.close.assert_called_once()
            resource.unlink.assert_called_once_with("browser.sock", dir_fd=91)
            resource.close.assert_called_once_with(91)
            self.assertEqual(subject.manager.docker.call_count, 1)

    def test_late_reply_has_no_ack_or_native_cookie_success(self):
        now = [0]
        subject = instance(clock=lambda: now[0], manual_bootstrap=True)
        connection = Connection()
        def expired_reply(value):
            now[0] = supervisor.MANUAL_TIMEOUT
            return value
        connection.change = expired_reply
        with sockets(connection) as resource:
            self.assertEqual(subject.verify(STATE, DOCUMENT, REQUEST_ID), supervisor.failure(REQUEST_ID))
        self.assertEqual(len(connection.sent), 1)
        self.assertNotIn({"accepted": True}, connection.sent)
        resource.unlink.assert_called_once()

    def test_native_replacement_or_stop_before_or_after_reply_fails_closed(self):
        changed_states = (native(Id="f" * 64), native(Image="sha256:" + "f" * 64),
                          native(Name="/someone-else"), native(State={"Running": False}), native(Config={}))
        for when in (0, 1):
            for changed in changed_states:
                subject = instance(manual_bootstrap=True)
                replies = [native(), native()]
                replies[when] = changed
                subject.manager.docker.side_effect = [SimpleNamespace(stdout=json.dumps([value])) for value in replies]
                with sockets(Connection()) as resource:
                    self.assertEqual(subject.verify(STATE, DOCUMENT, REQUEST_ID), supervisor.failure(REQUEST_ID))
                if when == 0:
                    resource.factory.assert_not_called()

    def test_single_frame_requires_eof_no_trailing_duplicate_or_oversized_input(self):
        subject = instance()
        for raw in (b'{"x":1}\n{}\n', b'{"x":1}\n ', b'{"x":1}', b'{"x":1,"x":2}\n',
                    b"x" * (worker.MAX_MESSAGE + 2)):
            with self.subTest(length=len(raw)), self.assertRaises(worker.VerificationError):
                subject.manual_read(Connection(chunks=[raw, b""]), 40)
        self.assertEqual(subject.manual_read(Connection(chunks=[b'{"x":', b"1}\n", b""]), 40), {"x": 1})

    def test_slow_chunks_consume_one_deadline_not_fresh_per_read(self):
        times = iter((0, 10, 25, 40))
        subject = instance(clock=lambda: next(times))
        connection = Connection(chunks=[b"{", b" ", b" ", b" "])
        with self.assertRaises(TimeoutError):
            subject.manual_read(connection, 40)
        self.assertEqual(connection.timeouts, [40, 30, 15])

    def test_manual_slot_used_once_then_auto_path_without_same_nonce_fallback(self):
        subject = instance(manual_bootstrap=True)
        subject.verify_manual = Mock(return_value=supervisor.failure(REQUEST_ID))
        subject.companion = Mock(side_effect=manage.DeploymentError("fixed"))
        subject.cleanup_browser = Mock()
        self.assertEqual(subject.verify(STATE, DOCUMENT, REQUEST_ID), supervisor.failure(REQUEST_ID))
        subject.companion.assert_not_called()
        subject.verify(STATE, DOCUMENT, "b" * 32)
        subject.verify_manual.assert_called_once()
        subject.companion.assert_called_once()
        subject.verify(STATE, DOCUMENT, REQUEST_ID)
        subject.verify_manual.assert_called_once()

    def test_default_auto_never_opens_manual_socket_and_invalid_nonce_is_rejected(self):
        subject = instance()
        subject.verify_manual = Mock()
        subject.companion = Mock(side_effect=manage.DeploymentError("fixed"))
        subject.cleanup_browser = Mock()
        subject.verify(STATE, DOCUMENT, REQUEST_ID)
        subject.verify_manual.assert_not_called()
        subject.companion.assert_called_once()
        for value in (None, "bad", "A" * 32):
            self.assertEqual(subject.verify(STATE, DOCUMENT, value), supervisor.failure(value))
        with self.assertRaises(ValueError):
            instance(manual_bootstrap="true")

    def test_private_runtime_directory_rejects_symlink_owner_mode_or_inode_change(self):
        root = SimpleNamespace(st_mode=stat.S_IFDIR | 0o755, st_uid=0, st_dev=1, st_ino=2)
        private = SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_uid=0, st_dev=1, st_ino=3)
        for change in ({"st_mode": stat.S_IFLNK | 0o777}, {"st_mode": stat.S_IFDIR | 0o755},
                       {"st_uid": 1000}, {"st_mode": stat.S_IFDIR | 0o722}):
            unsafe = SimpleNamespace(**dict(vars(private), **change))
            with patch.object(Path, "lstat", side_effect=[root, root, unsafe]), patch.object(supervisor.os, "open") as opened:
                with self.assertRaises(manage.DeploymentError):
                    supervisor.manual_directory(INSTANCE)
                opened.assert_not_called()
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(Path, "lstat", side_effect=[root, root, private]))
            stack.enter_context(patch.object(supervisor.os, "O_DIRECTORY", 1, create=True))
            stack.enter_context(patch.object(supervisor.os, "O_NOFOLLOW", 2, create=True))
            stack.enter_context(patch.object(supervisor.os, "O_CLOEXEC", 4, create=True))
            stack.enter_context(patch.object(supervisor.os, "open", return_value=91))
            stack.enter_context(patch.object(supervisor.os, "fstat", return_value=root))
            closed = stack.enter_context(patch.object(supervisor.os, "close"))
            with self.assertRaises(manage.DeploymentError):
                supervisor.manual_directory(INSTANCE)
            closed.assert_called_once_with(91)

    def test_manual_bridge_uses_single_bounded_writer_and_no_raw_logs(self):
        subject = instance(manual_bootstrap=True)
        subject.verify = Mock(return_value=supervisor.failure(REQUEST_ID))
        subject.write_manual_reply = Mock()
        destination = Mock()
        source = io.BytesIO(b"PRIVATE_NATIVE_TEXT\nOPENFLUX_BROWSER_VERIFY " + REQUEST_ID.encode() + b"\n")
        with contextlib.redirect_stdout(io.StringIO()) as visible:
            subject.bridge(source, destination, STATE, DOCUMENT)
        subject.write_manual_reply.assert_called_once_with(destination, supervisor.failure(REQUEST_ID))
        destination.write.assert_not_called()
        destination.flush.assert_not_called()
        self.assertEqual(visible.getvalue(), "")

    def test_native_write_is_nonblocking_partial_bounded_and_restores_fd(self):
        subject = instance()
        destination = SimpleNamespace(fileno=lambda: 77)
        sent = []
        def write(fd, data):
            self.assertEqual(fd, 77)
            sent.append(bytes(data[:3]))
            return len(sent[-1])
        with patch.object(supervisor.os, "get_blocking", return_value=True), \
                patch.object(supervisor.os, "set_blocking") as mode, \
                patch.object(supervisor.select, "select", return_value=([], [77], [])), \
                patch.object(supervisor.os, "write", side_effect=write):
            subject.write_manual_reply(destination, supervisor.failure(REQUEST_ID))
        self.assertEqual(b"".join(sent), worker.encode_message(supervisor.failure(REQUEST_ID)))
        self.assertEqual(mode.call_args_list[0].args, (77, False))
        self.assertEqual(mode.call_args_list[-1].args, (77, True))
        with patch.object(supervisor.os, "get_blocking", return_value=True), \
                patch.object(supervisor.os, "set_blocking") as mode, \
                patch.object(supervisor.select, "select", return_value=([], [], [])), \
                patch.object(supervisor.os, "write") as write:
            with self.assertRaises(TimeoutError):
                subject.write_manual_reply(destination, supervisor.failure(REQUEST_ID))
        write.assert_not_called()
        self.assertEqual(mode.call_args_list[-1].args, (77, True))


if __name__ == "__main__":
    unittest.main(verbosity=2)
