import hashlib
import json
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from unittest.mock import patch
from service import Broker, CALLBACK, ROTATE_PATH, ServiceError, handler_for


def config():
    return dict(approvedForAndroid=True, refreshRouteConfirmed=True, clientId='test-player', clientSecret='test-secret', signKey='test-signing', deviceId='test-device', serviceToken='test-connection-token-1234567890')

class BrokerTests(unittest.TestCase):
    def test_requires_confirmed_independent_identity(self):
        with self.assertRaises(ServiceError): Broker(dict(config(), approvedForAndroid=False))
        with self.assertRaises(ServiceError): Broker(dict(config(), clientId='third_party_agent'))

    def test_signs_only_read_routes_and_never_returns_key(self):
        b = Broker(config(), clock=lambda: 1000)
        h = b.dispatch('sign', dict(method='POST', path='/open/v1/file/list'))
        expected = hashlib.sha256(b'POST&/open/v1/file/list&1000000&test-signing').hexdigest()
        self.assertEqual(expected, h['x-pan-token'])
        self.assertNotIn('test-signing', json.dumps(h))
        for path in ['/open/v1/file/delete', '/open/v1/file/rename/batch', '/open/v1/file/list?extra=1']:
            with self.assertRaises(ServiceError): b.dispatch('sign', dict(method='POST', path=path))

    def test_authorization_binds_callback_and_preserves_state(self):
        b = Broker(config())
        value = b.dispatch('authorize', dict(state='a&b', redirectUri=CALLBACK))
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(value['authorizeUrl']).query)
        self.assertEqual(['a&b'], query['state'])
        self.assertEqual(['test-player'], query['client_id'])
        with self.assertRaises(ServiceError): b.dispatch('authorize', dict(state='state', redirectUri='https://evil.example'))

    def test_exchange_gets_verified_user_id_and_omits_app_secrets_from_result(self):
        calls = []
        def upstream(method, path, **kw):
            calls.append((method, path, kw))
            if path == '/open/v1/oauth/token': return dict(access_token='test-access', refresh_token='test-refresh', expires_in=3600)
            return dict(user_id='user-1')
        b = Broker(config(), upstream, clock=lambda: 1000)
        result = b.dispatch('exchange', dict(code='code', redirectUri=CALLBACK))
        self.assertEqual('user-1', result['userId'])
        self.assertEqual(4600000, result['expiresAtMs'])
        self.assertEqual('test-secret', calls[0][2]['query']['client_secret'])
        self.assertNotIn('test-secret', json.dumps(result))
        self.assertNotIn('test-signing', json.dumps(result))

    def test_rotate_is_explicit_and_bound_to_configured_device(self):
        calls = []
        def upstream(method, path, **kw):
            calls.append((method, path, kw))
            return dict(access_token='a', refresh_token='r', expires_in=100)
        b = Broker(config(), upstream)
        with self.assertRaises(ServiceError): b.dispatch('rotate', dict(refreshToken='r', deviceId='other'))
        b.dispatch('rotate', dict(refreshToken='r', deviceId='test-device'))
        self.assertEqual(ROTATE_PATH, calls[0][1])
        with self.assertRaises(ServiceError): Broker(dict(config(), refreshRouteConfirmed=False), upstream).dispatch('rotate', dict(refreshToken='r', deviceId='test-device'))

    def test_expiration_seconds_milliseconds_and_unknown(self):
        b = Broker(config(), clock=lambda: 10)
        self.assertEqual(1788920000000, b.expiry(dict(access_token_expired_at=1788920000)))
        self.assertEqual(1788920000000, b.expiry(dict(access_token_expired_at=1788920000000)))
        self.assertEqual(0, b.expiry({}))

    def test_http_authentication_and_no_store_response(self):
        b = Broker(config())
        server = ThreadingHTTPServer(('127.0.0.1', 0), handler_for(b))
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            url = f'http://127.0.0.1:{server.server_port}/v1/sign'
            data = json.dumps(dict(method='POST', path='/open/v1/file/list')).encode()
            with self.assertRaises(urllib.error.HTTPError) as caught: urllib.request.urlopen(urllib.request.Request(url, data=data), timeout=5)
            self.assertEqual(401, caught.exception.code)
            req = urllib.request.Request(url, data=data, headers={'Authorization': 'Bearer ' + config()['serviceToken']})
            with urllib.request.urlopen(req, timeout=5) as response:
                self.assertEqual('no-store', response.headers['Cache-Control'])
                self.assertTrue(json.load(response)['ok'])
        finally: server.shutdown(); server.server_close()

    def test_upstream_redirect_is_not_followed(self):
        hits = []
        class Target(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self): hits.append(self.path); self.send_response(200); self.end_headers()
        target = ThreadingHTTPServer(('127.0.0.1', 0), Target)
        class Redirect(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                self.send_response(302); self.send_header('Location', f'http://127.0.0.1:{target.server_port}/unexpected'); self.end_headers()
        origin = ThreadingHTTPServer(('127.0.0.1', 0), Redirect)
        for server in [target, origin]: threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with patch('service.API', f'http://127.0.0.1:{origin.server_port}'):
                with self.assertRaises(ServiceError): Broker(config()).request_upstream('GET', '/open/v1/user/info', query={'access_token': 'test-token'})
            self.assertEqual([], hits)
        finally:
            for server in [origin, target]: server.shutdown(); server.server_close()

if __name__ == '__main__': unittest.main()
