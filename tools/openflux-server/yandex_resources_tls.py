#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Diagnostic-only TLS supplement for two anonymous Yandex resource hosts.

Use one loader in a fresh, nonpersistent browser context without storage_state,
HTTP credentials or imported cookies. Known authentication cookies are rejected,
but that denylist cannot establish that an arbitrary context is anonymous.
The required constructor flag is the caller's attestation, not context inspection.
Keep browser TLS verification enabled, block service workers, and apply the outer
origin allowlist before handle(). Call close() in the caller's finally block.

An independent supervisor MUST enforce a hard deadline and terminate the whole
disposable container. Socket timeouts are inactivity limits, not total deadlines;
DNS and slow-drip responses can outlive them. Cancelling handle() or calling
close() does not stop running thread/socket I/O. Do not wire this diagnostic
helper into document/tunnel traffic or a persistent browser.
"""

import asyncio
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
from http.cookies import CookieError, SimpleCookie
import re
import ssl
from urllib.parse import urlsplit


HOSTS = frozenset(('hdrc.yandex.net', 'mdd.yandex.net'))
ROOT_DER_SHA256 = 'd26d2d0231b7c39f92cc738512ba54103519e4405d68b5bd703e9788ca8ecf31'
MAX_RESPONSE = 2 * 1024 * 1024
MAX_REQUEST = 65536
MAX_WORKERS = 2
AUTH_COOKIE_NAMES = frozenset(('session_id', 'sessionid', 'sessionid2', 'yandex_login'))
HEADER_NAME = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]+\Z")
HOP_HEADERS = frozenset(('connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
                         'proxy-connection', 'te', 'trailer', 'transfer-encoding', 'upgrade'))


def checked_context(root_pem):
    if not isinstance(root_pem, str) or root_pem.count('-----BEGIN CERTIFICATE-----') != 1 or len(root_pem) > 8192:
        raise ValueError('resource_ca_invalid')
    der = ssl.PEM_cert_to_DER_cert(root_pem)
    if hashlib.sha256(der).hexdigest() != ROOT_DER_SHA256:
        raise ValueError('resource_ca_changed')
    context = ssl.create_default_context()
    context.load_verify_locations(cadata=root_pem)
    return context


def checked_target(url):
    if not isinstance(url, str) or len(url) > 8192 or any(ord(c) <= 32 or ord(c) == 127 for c in url):
        raise ValueError('resource_url_invalid')
    parts = urlsplit(url)
    if (parts.scheme != 'https' or parts.hostname not in HOSTS or parts.port not in (None, 443)
            or parts.username is not None or parts.password is not None or parts.fragment):
        raise ValueError('resource_origin_not_allowed')
    return parts.hostname, (parts.path or '/') + ('?' + parts.query if parts.query else '')


def checked_headers(items, error):
    result, hop_headers = [], set(HOP_HEADERS)
    for name, value in items:
        if (not isinstance(name, str) or not HEADER_NAME.fullmatch(name) or not isinstance(value, str)
                or any(ord(c) < 32 and c != '\t' or ord(c) == 127 for c in value)):
            raise ValueError(error)
        name = name.lower()
        if name == 'connection':
            for option in value.split(','):
                option = option.strip()
                if not HEADER_NAME.fullmatch(option):
                    raise ValueError(error)
                hop_headers.add(option.lower())
        result.append((name, value))
    return result, hop_headers


def checked_cookies(value, *, response=False):
    if not value.strip():
        return
    # Request Cookie contains pairs; Set-Cookie contains one pair plus attributes.
    pairs = [value.split(';', 1)[0]] if response else value.split(';')
    for pair in pairs:
        cookies = SimpleCookie()
        try:
            cookies.load(pair.strip())
        except CookieError:
            raise ValueError('resource_cookie_invalid') from None
        if len(cookies) != 1:
            raise ValueError('resource_cookie_invalid')
        name = next(iter(cookies)).lower()
        for prefix in ('__host-', '__secure-'):
            name = name.removeprefix(prefix)
        if name in AUTH_COOKIE_NAMES:
            raise ValueError('anonymous_resource_only')


def fetch_resource(url, method, headers, body, root_pem, connection_factory=http.client.HTTPSConnection):
    host, target = checked_target(url)
    if method not in ('GET', 'HEAD', 'POST') or body is not None and (not isinstance(body, bytes) or len(body) > MAX_REQUEST):
        raise ValueError('resource_request_invalid')
    request_headers, hop_headers = checked_headers(headers.items(), 'resource_header_invalid')
    outbound = {}
    for name, value in request_headers:
        if name in ('authorization', 'proxy-authorization'):
            raise ValueError('anonymous_resource_only')
        if name == 'cookie':
            checked_cookies(value)
        if name not in hop_headers | {'host', 'content-length', 'accept-encoding'}:
            outbound[name] = value
    outbound['Accept-Encoding'] = 'identity'
    connection = connection_factory(host, 443, timeout=15, context=checked_context(root_pem))
    try:
        connection.request(method, target, body=body, headers=outbound)
        response = connection.getresponse()
        # Playwright does not reroute every redirect hop through the allowlist.
        if 300 <= response.status < 400:
            raise ValueError('resource_redirect_refused')
        expected_length = response.length
        if expected_length is not None and expected_length > MAX_RESPONSE:
            raise ValueError('resource_response_too_large')
        data = response.read(MAX_RESPONSE + 1)
        if len(data) > MAX_RESPONSE:
            raise ValueError('resource_response_too_large')
        if expected_length is not None and len(data) != expected_length:
            raise http.client.IncompleteRead(data, expected_length - len(data))
        response_headers, hop_headers = checked_headers(response.getheaders(), 'resource_response_header_invalid')
        result_headers = {}
        for name, value in response_headers:
            if name == 'set-cookie':
                checked_cookies(value, response=True)
            if name in hop_headers | {'content-length'}:
                continue
            separator = '\n' if name == 'set-cookie' else ', '
            result_headers[name] = result_headers[name] + separator + value if name in result_headers else value
        # An explicit denial prevents Playwright fulfill() from inventing CORS permission.
        result_headers.setdefault('access-control-allow-origin', '')
        return response.status, result_headers, data
    finally:
        connection.close()


class ScopedResourceLoader:
    def __init__(self, root_pem, *, fresh_anonymous_context):
        if fresh_anonymous_context is not True:
            raise ValueError('fresh_anonymous_context_required')
        checked_context(root_pem)
        self.root_pem = root_pem
        self.slots = asyncio.Semaphore(MAX_WORKERS)
        self._executor = ThreadPoolExecutor(max_workers=MAX_WORKERS, thread_name_prefix='yandex-resource-tls')
        self._closed = False

    def close(self):
        """Reject new work; running I/O still requires the independent hard deadline."""
        self._closed = True
        self._executor.shutdown(wait=False, cancel_futures=True)

    async def _fetch(self, request):
        if self._closed:
            raise ValueError('resource_loader_closed')
        await self.slots.acquire()
        loop = asyncio.get_running_loop()
        try:
            if self._closed:
                raise ValueError('resource_loader_closed')
            headers = await request.all_headers()
            if self._closed:
                raise ValueError('resource_loader_closed')
            future = self._executor.submit(fetch_resource, request.url, request.method,
                                           headers, request.post_data_buffer, self.root_pem)
        except BaseException:
            self.slots.release()
            raise

        def release_slot(_):
            try:
                loop.call_soon_threadsafe(self.slots.release)
            except RuntimeError:
                # A closed event loop cannot accept callbacks; the supervisor owns teardown.
                pass

        # Cancellation of the asyncio wrapper cannot release a still-running I/O slot.
        future.add_done_callback(release_slot)
        return await asyncio.wrap_future(future)

    async def handle(self, route):
        request = route.request
        try:
            if urlsplit(request.url).hostname not in HOSTS:
                return False
            checked_target(request.url)
            if request.is_navigation_request() or request.resource_type == 'document':
                raise ValueError('resource_navigation_refused')
            status, headers, body = await self._fetch(request)
            await route.fulfill(status=status, headers=headers, body=body)
        except (OSError, ValueError, http.client.HTTPException):
            await route.abort('failed')
        return True
