"""Complete missing TLS export options for WS profiles behind a TLS frontdoor.

The upstream subscription service remains authoritative. This adapter owns no
profiles or credentials and never changes Xray configuration.
"""
import argparse
import base64
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import socketserver
import threading
import time
import urllib.parse


HOP_HEADERS = {'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
               'te', 'trailer', 'transfer-encoding', 'upgrade'}
MAXIMUM = 1024 * 1024


def complete_uri(line, allowed_hosts):
    if not line.startswith('vless://') or line != line.strip() or len(line) > 16384:
        return line
    try:
        value = urllib.parse.urlsplit(line)
        if value.hostname not in allowed_hosts or value.port not in (None, 443):
            return line
        query = urllib.parse.parse_qsl(value.query, keep_blank_values=True, max_num_fields=128)
        fields = dict(query)
        if fields.get('type') != 'ws' or fields.get('security') != 'tls':
            return line
        additions = [('sni', value.hostname), ('fp', 'chrome'), ('alpn', 'http/1.1')]
        additions = [(key, item) for key, item in additions if key not in fields]
        if not additions:
            return line
        separator = '' if not value.query or value.query.endswith('&') else '&'
        return urllib.parse.urlunsplit(value._replace(query=value.query + separator + urllib.parse.urlencode(additions)))
    except (ValueError, UnicodeError):
        return line


def complete_payload(body, allowed_hosts):
    if len(body) > MAXIMUM:
        raise ValueError('Subscription exceeds its bounded response size')
    encoded = False
    try:
        text = body.decode('utf-8')
        if not any(line.startswith('vless://') for line in text.splitlines()):
            compact = b''.join(body.split())
            compact += b'=' * (-len(compact) % 4)
            text = base64.b64decode(compact, altchars=b'-_', validate=True).decode('utf-8')
            encoded = True
    except (ValueError, UnicodeError):
        return body, False
    result = []
    for line in text.splitlines(keepends=True):
        uri = line.rstrip('\r\n')
        result.append(complete_uri(uri, allowed_hosts) + line[len(uri):])
    completed = ''.join(result)
    if completed == text:
        return body, False
    data = completed.encode('utf-8')
    return (base64.b64encode(data) if encoded else data), True


def filtered_headers(headers, extra_exclusions=()):
    excluded = HOP_HEADERS | set(extra_exclusions)
    for name, value in headers:
        if name.lower() == 'connection':
            excluded.update(item.strip().lower() for item in value.split(','))
    return [(name, value) for name, value in headers if name.lower() not in excluded]


def close_socket(connection):
    try:
        connection.shutdown(socket.SHUT_RDWR)
    except OSError:
        pass


def handler_for(config, *, upstream_deadline_seconds=12):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *arguments):
            pass  # Subscription capability paths and device headers are private.

        def setup(self):
            super().setup()
            self.connection.settimeout(10)
            self.request_deadline = threading.Timer(20, close_socket, args=(self.connection,))
            self.request_deadline.daemon = True
            self.request_deadline.start()

        def finish(self):
            try:
                super().finish()
            finally:
                self.request_deadline.cancel()

        def do_HEAD(self):
            self.do_GET()

        def finish_reply(self, status, body=b'', headers=()):
            self.send_response(status)
            for name, value in headers:
                self.send_header(name, value)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Connection', 'close')
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(body)
            self.close_connection = True

        def do_GET(self):
            if self.path == '/health':
                self.finish_reply(200, b'{"service":"subscription-compat","schema":1}',
                                  [('Content-Type', 'application/json')])
                return
            if len(self.path) > 1024 or not re.fullmatch(r'/sub/[A-Za-z0-9_-]{1,128}(?:\?[^\r\n#]{0,700})?', self.path):
                self.finish_reply(404)
                return
            connection = http.client.HTTPConnection(config['upstream_host'], config['upstream_port'], timeout=8)
            absolute_deadline = time.monotonic() + upstream_deadline_seconds
            timer = None
            deadline_socket = None
            response = None
            try:
                connection.connect()
                # HTTP/1.0 responses can detach/close HTTPConnection.sock while
                # their buffered body still owns the descriptor. A duplicate
                # keeps shutdown available for the absolute deadline.
                deadline_socket = connection.sock.dup()
                timer = threading.Timer(max(.001, absolute_deadline-time.monotonic()), close_socket, args=(deadline_socket,))
                timer.daemon = True
                timer.start()
                # Preserve client/HWID metadata. Remove validators for the native
                # representation, because the exported representation may differ.
                headers = dict(filtered_headers(self.headers.items(),
                    {'host', 'accept-encoding', 'content-length', 'if-none-match', 'if-modified-since'}))
                public_host = config['public_host']
                requested_host = self.headers.get('Host', '')
                try:
                    host = urllib.parse.urlsplit('http://' + requested_host)
                    if (host.hostname in config['allowed_hosts'] and host.port in (None, 443)
                            and not host.username and not host.password and not host.path and not host.query and not host.fragment):
                        public_host = requested_host
                except ValueError:
                    pass
                headers.update({'Host': public_host, 'Accept-Encoding': 'identity'})
                connection.request('GET', self.path, headers=headers)
                response = connection.getresponse()
                declared = response.getheader('Content-Length')
                if declared is not None and (not declared.isdecimal() or int(declared) > MAXIMUM):
                    raise ValueError('Upstream subscription length is outside its limit')
                chunks, received = [], 0
                while received <= MAXIMUM and not response.isclosed():
                    remaining = absolute_deadline - time.monotonic()
                    if remaining <= 0:
                        raise TimeoutError('Subscription upstream deadline expired')
                    response.fp.raw._sock.settimeout(min(8, remaining))
                    block = response.read1(min(65536, MAXIMUM + 1 - received))
                    if not block:
                        break
                    chunks.append(block)
                    received += len(block)
                body = b''.join(chunks)
                if len(body) > MAXIMUM:
                    raise ValueError('Upstream subscription exceeds its limit')
                if declared is not None and len(body) != int(declared):
                    raise ValueError('Upstream subscription body is incomplete')
                changed = False
                if response.status == 200 and response.getheader('Content-Encoding', 'identity').lower() == 'identity':
                    body, changed = complete_payload(body, config['allowed_hosts'])
                excluded = {'content-length', 'server', 'date'}
                if changed:
                    excluded.update({'etag', 'last-modified', 'content-md5', 'digest', 'cache-control'})
                result_headers = filtered_headers(response.getheaders(), excluded)
                if changed:
                    result_headers.append(('Cache-Control', 'private, no-store'))
                result_status = response.status
            except (OSError, ValueError, http.client.HTTPException):
                result_status, body, result_headers = 502, b'', [('Retry-After', '5')]
            finally:
                if timer is not None:
                    timer.cancel()
                if response is not None:
                    response.close()
                if deadline_socket is not None:
                    deadline_socket.close()
                connection.close()
            self.finish_reply(result_status, body, result_headers)

    return Handler


class BoundedServer(socketserver.ThreadingMixIn, HTTPServer):
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, *args, **kwargs):
        self.slots = threading.BoundedSemaphore(4)
        super().__init__(*args, **kwargs)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            try:
                request.sendall(b'HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nRetry-After: 5\r\nConnection: close\r\n\r\n')
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, *args):
        try:
            super().process_request_thread(*args)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        pass  # Never log a request capability or its headers.


def load_config(path):
    source = Path(path)
    metadata = source.lstat()
    if source.is_symlink() or not source.is_file() or metadata.st_size > 4096:
        raise ValueError('Invalid subscription adapter configuration')
    if os.name == 'posix' and (metadata.st_uid != 0 or metadata.st_mode & 0o022):
        raise ValueError('Adapter configuration must be root-controlled')
    config = json.loads(source.read_text())
    for key in ['listen_host', 'upstream_host']:
        address = ipaddress.ip_address(config[key])
        if address.version != 4 or not address.is_loopback:
            raise ValueError('Subscription adapter must remain loopback-only')
    if not isinstance(config['allowed_hosts'], list) or not config['allowed_hosts']:
        raise ValueError('Explicit public host allowlist is required')
    if config['public_host'] not in config['allowed_hosts'] or any(
        not isinstance(host, str) or not re.fullmatch(r'[a-z0-9.-]{1,253}', host)
        for host in config['allowed_hosts']
    ):
        raise ValueError('Invalid subscription export host')
    for key in ['listen_port', 'upstream_port']:
        if type(config[key]) is not int or not 1024 <= config[key] <= 65535:
            raise ValueError('Invalid subscription adapter port')
    return config


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    args = parser.parse_args()
    config = load_config(args.config)
    server = BoundedServer((config['listen_host'], config['listen_port']), handler_for(config))
    server.serve_forever(poll_interval=.5)


if __name__ == '__main__':
    main()
