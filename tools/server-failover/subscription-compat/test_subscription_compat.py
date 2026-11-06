import base64
from contextlib import contextmanager
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import threading
import unittest
from urllib.parse import parse_qs, urlsplit

from subscription_compat import BoundedServer, complete_payload, complete_uri, filtered_headers, handler_for


HOSTS = ['vpn.example.net']
WS = 'vless://example-client@vpn.example.net:443?type=ws&security=tls&path=%2Fsocket#Example'
XHTTP = 'vless://other-client@vpn.example.net:443?type=xhttp&security=tls&path=%2Fsplit#Other'


@contextmanager
def running(server):
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield server.server_address[1]
    finally:
        server.shutdown()
        server.server_close()
        worker.join(3)


class ExportTests(unittest.TestCase):
    def test_external_tls_ws_gets_missing_options_without_identity_change(self):
        before, after = urlsplit(WS), urlsplit(complete_uri(WS, HOSTS))
        self.assertEqual((before.scheme, before.netloc, before.fragment), (after.scheme, after.netloc, after.fragment))
        self.assertEqual(parse_qs(after.query), {'type':['ws'], 'security':['tls'], 'path':['/socket'],
                         'sni':HOSTS, 'fp':['chrome'], 'alpn':['http/1.1']})

    def test_explicit_user_options_remain_exact(self):
        explicit = WS.partition('#')[0] + '&sni=custom.example.net&fp=firefox&alpn=h2#Example'
        self.assertEqual(complete_uri(explicit, HOSTS), explicit)

    def test_existing_query_encoding_is_preserved(self):
        original = WS.replace('%2Fsocket','/literal%20path')
        self.assertTrue(urlsplit(complete_uri(original, HOSTS)).query.startswith(urlsplit(original).query+'&'))

    def test_unpadded_native_base64(self):
        incoming = base64.b64encode(WS.encode()).rstrip(b'=')
        body, changed = complete_payload(incoming, HOSTS)
        self.assertTrue(changed)
        self.assertIn(b'alpn=http%2F1.1',base64.b64decode(body))

    def test_other_transports_hosts_ports_are_untouched(self):
        for uri in [XHTTP, WS.replace('security=tls', 'security=none'),
                    WS.replace('vpn.example.net', 'other.example.net'), WS.replace(':443?', ':8443?')]:
            self.assertEqual(complete_uri(uri, HOSTS), uri)

    def test_native_base64_mixed_subscription_preserves_xhttp_and_line_endings(self):
        incoming = base64.b64encode((WS+'\r\n'+XHTTP+'\r\n').encode())
        body, changed = complete_payload(incoming, HOSTS)
        text = base64.b64decode(body).decode()
        self.assertTrue(changed)
        self.assertEqual(text.splitlines(keepends=True)[1], XHTTP+'\r\n')
        self.assertIn('alpn=http%2F1.1', text)
        self.assertTrue(text.splitlines(keepends=True)[0].endswith('\r\n'))

    def test_plain_uri_and_unrelated_response_formats(self):
        self.assertTrue(complete_payload(WS.encode(), HOSTS)[1])
        for data in [b'{"outbounds":[]}', b'not a subscription', b'\xff\0', base64.b64encode(XHTTP.encode())]:
            self.assertEqual(complete_payload(data, HOSTS), (data, False))

    def test_size_limit(self):
        with self.assertRaises(ValueError):
            complete_payload(b'x'*(1024*1024+1), HOSTS)

    def test_connection_specific_headers_not_forwarded(self):
        incoming = [('Connection','keep-alive, X-Hop'),('X-Hop','private'),('X-Hwid','example-device'),('User-Agent','Example')]
        self.assertEqual(filtered_headers(incoming), [('X-Hwid','example-device'),('User-Agent','Example')])

    def test_real_http_metadata_status_and_head(self):
        requests = []
        raw = base64.b64encode(WS.encode())
        class Upstream(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                requests.append({'path':self.path,'hwid':self.headers.get('X-Hwid'),
                                 'ua':self.headers.get('User-Agent'),'encoding':self.headers.get('Accept-Encoding'),'host':self.headers.get('Host')})
                self.send_response(200)
                self.send_header('Content-Type','text/plain; charset=utf-8')
                self.send_header('Subscription-Userinfo','upload=1; download=2; total=3')
                self.send_header('Profile-Update-Interval','12')
                self.send_header('ETag','native-representation')
                self.send_header('Content-Length',str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
        with running(HTTPServer(('127.0.0.1',0),Upstream)) as backend:
            config={'upstream_host':'127.0.0.1','upstream_port':backend,'public_host':HOSTS[0],'allowed_hosts':HOSTS+['www.example.net']}
            with running(BoundedServer(('127.0.0.1',0),handler_for(config))) as port:
                connection=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
                connection.request('GET','/sub/exampleCapability?client=happ',headers={'X-Hwid':'example-device','User-Agent':'Happ-example'})
                response=connection.getresponse();body=response.read()
                self.assertEqual(response.status,200)
                self.assertEqual(response.getheader('Subscription-Userinfo'),'upload=1; download=2; total=3')
                self.assertEqual(response.getheader('Profile-Update-Interval'),'12')
                self.assertIsNone(response.getheader('ETag'))
                self.assertEqual(response.getheader('Cache-Control'),'private, no-store')
                self.assertIn(b'alpn=http%2F1.1',base64.b64decode(body))
                self.assertEqual(requests[-1],{'path':'/sub/exampleCapability?client=happ','hwid':'example-device','ua':'Happ-example','encoding':'identity','host':HOSTS[0]})
                connection.close()
                connection=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
                connection.request('HEAD','/sub/exampleCapability')
                response=connection.getresponse()
                self.assertEqual(response.status,200)
                self.assertEqual(int(response.getheader('Content-Length')),len(body))
                self.assertEqual(response.read(),b'')
                connection.close()
                for incoming,expected in [('www.example.net','www.example.net'),('untrusted.example.net',HOSTS[0])]:
                    connection=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
                    connection.request('GET','/sub/exampleCapability',headers={'Host':incoming})
                    response=connection.getresponse();response.read();connection.close()
                    self.assertEqual(requests[-1]['host'],expected)
                prior=len(requests)
                connection=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
                connection.request('GET','/admin')
                response=connection.getresponse();response.read()
                self.assertEqual(response.status,404)
                self.assertEqual(len(requests),prior)
                connection.close()

    def test_absolute_upstream_deadline_breaks_a_stalled_body(self):
        release=threading.Event()
        class Stalled(BaseHTTPRequestHandler):
            def log_message(self,*args):
                pass
            def do_GET(self):
                self.send_response(200)
                self.send_header('Content-Length','100')
                self.end_headers()
                self.wfile.write(b'x');self.wfile.flush()
                release.wait(4)
        try:
            with running(HTTPServer(('127.0.0.1',0),Stalled)) as backend:
                config={'upstream_host':'127.0.0.1','upstream_port':backend,'public_host':HOSTS[0],'allowed_hosts':HOSTS}
                with running(BoundedServer(('127.0.0.1',0),handler_for(config,upstream_deadline_seconds=.1))) as port:
                    connection=http.client.HTTPConnection('127.0.0.1',port,timeout=2)
                    connection.request('GET','/sub/exampleCapability')
                    response=connection.getresponse();response.read()
                    self.assertEqual(response.status,502)
                    self.assertFalse(release.is_set())
                    connection.close();release.set()
        finally:
            release.set()


if __name__=='__main__':
    unittest.main()
