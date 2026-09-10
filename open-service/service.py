#!/usr/bin/env python3
"""Private Quark Open signing/OAuth service for an explicitly approved Android client.

No default Skill credentials, account storage, telemetry, or file mutation routes.
Run behind HTTPS or use --tls-cert/--tls-key. Import only the exported connection
file into Android; the client secret and signing key remain on this host.
"""
import argparse
import hashlib
import hmac
import json
import os
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

CALLBACK = 'cruisetune://quark-open/callback'
API = 'https://open-api-drive.quark.cn'
READ_PATHS = {('POST', '/open/v1/file/list'), ('POST', '/open/v1/file/get_download_url'), ('GET', '/open/v1/user/info')}
ROTATE_PATH = '/agent/v1/oauth/access_token/rotate'

class ServiceError(Exception):
    def __init__(self, code='upstream_failed'):
        self.code = code
        super().__init__(code)

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None

def require_string(data, field):
    value = data.get(field)
    if not isinstance(value, str) or not value or len(value) > 16384 or '\r' in value or '\n' in value:
        raise ServiceError('invalid_request')
    return value

class Broker:
    def __init__(self, config, upstream=None, clock=time.time):
        if config.get('approvedForAndroid') is not True:
            raise ServiceError('android_client_not_confirmed')
        for key in ['clientId', 'clientSecret', 'signKey', 'deviceId', 'serviceToken']:
            require_string(config, key)
        if config['clientId'] == 'third_party_agent' or len(config['serviceToken']) < 24:
            raise ServiceError('independent_client_required')
        self.config = config
        self.clock = clock
        self.upstream = upstream or self.request_upstream

    def headers(self, method, path):
        timestamp = str(int(self.clock() * 1000))
        raw = f"{method.upper()}&{path}&{timestamp}&{self.config['signKey']}"
        return {'x-pan-client-id': self.config['clientId'], 'x-pan-tm': timestamp,
                'x-pan-token': hashlib.sha256(raw.encode()).hexdigest(), 'Content-Type': 'application/json'}

    def request_upstream(self, method, path, query=None, body=None, signed=True):
        query = dict(query or {}, req_id=str(uuid.uuid4()))
        url = API + path + '?' + urllib.parse.urlencode(query)
        headers = self.headers(method, path) if signed else {'Accept': 'application/json'}
        request = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
        # Never log the URL: the reference OAuth API carries its client secret in the query.
        try:
            opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
            with opener.open(request, timeout=30) as response:
                raw = response.read(2 * 1024 * 1024 + 1)
                if len(raw) > 2 * 1024 * 1024: raise ServiceError()
                value = json.loads(raw)
        except urllib.error.HTTPError as error:
            raise ServiceError('reauthorize' if error.code == 401 else 'upstream_failed') from None
        except (OSError, ValueError):
            raise ServiceError() from None
        if value.get('status') != 0 or not isinstance(value.get('data'), dict):
            raise ServiceError('reauthorize' if value.get('errno') in [11000, 11001] else 'upstream_failed')
        return value['data']

    def expiry(self, data):
        raw = data.get('access_token_expired_at', data.get('access_token_expires_at'))
        try:
            if raw is not None:
                value = int(raw)
                return value if value >= 10**12 else value * 1000
            duration = int(data.get('expires_in', 0))
            return int((self.clock() + duration) * 1000) if duration > 0 else 0
        except (ValueError, TypeError, OverflowError):
            raise ServiceError('invalid_token_result') from None

    def token_result(self, data):
        return {'accessToken': require_string(data, 'access_token'),
                'refreshToken': require_string(data, 'refresh_token'),
                'expiresAtMs': self.expiry(data)}

    def dispatch(self, action, body):
        if action == 'authorize':
            state = require_string(body, 'state')
            if body.get('redirectUri') != CALLBACK: raise ServiceError('invalid_callback')
            query = {'client_id': self.config['clientId'], 'response_type': 'code', 'redirect_uri': CALLBACK,
                     'scope': self.config.get('scope', 'clouddrive.base`clouddrive.netdisk'), 'state': state}
            return {'authorizeUrl': 'https://pan.quark.cn/open/v1/oauth/authorize?' + urllib.parse.urlencode(query)}
        if action == 'exchange':
            if body.get('redirectUri') != CALLBACK: raise ServiceError('invalid_callback')
            code = require_string(body, 'code')
            tokens = self.upstream('GET', '/open/v1/oauth/token', query={
                'grant_type': 'authorization_code', 'code': code,
                'client_id': self.config['clientId'], 'client_secret': self.config['clientSecret'],
                'scope': self.config.get('scope', 'clouddrive.base`clouddrive.netdisk'),
                'device_id': self.config['deviceId'], 'platform': 'android'}, signed=False)
            result = self.token_result(tokens)
            user = self.upstream('GET', '/open/v1/user/info', query={'access_token': result['accessToken'], 'device_id': self.config['deviceId'], 'platform': 'android'})
            result.update(userId=require_string(user, 'user_id'), deviceId=self.config['deviceId'])
            return result
        if action == 'rotate':
            if self.config.get('refreshRouteConfirmed') is not True: raise ServiceError('refresh_not_configured')
            if body.get('deviceId') != self.config['deviceId']: raise ServiceError('device_mismatch')
            tokens = self.upstream('POST', ROTATE_PATH, body={'refresh_token': require_string(body, 'refreshToken'), 'device_id': self.config['deviceId']})
            return self.token_result(tokens)
        if action == 'sign':
            method = body.get('method'); path = body.get('path')
            if (method, path) not in READ_PATHS: raise ServiceError('route_not_allowed')
            return self.headers(method, path)
        raise ServiceError('route_not_allowed')

def handler_for(broker):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self):
            expected = 'Bearer ' + broker.config['serviceToken']
            actual = self.headers.get('Authorization', '')
            if not hmac.compare_digest(actual.encode(), expected.encode()):
                self.reply(401, {'ok': False, 'error': 'unauthorized'}); return
            action = self.path.removeprefix('/v1/') if self.path.startswith('/v1/') else ''
            try:
                count = int(self.headers.get('Content-Length', '0'))
                if count < 1 or count > 65536: raise ServiceError('invalid_request')
                payload = json.loads(self.rfile.read(count))
                if not isinstance(payload, dict): raise ServiceError('invalid_request')
                result = broker.dispatch(action, payload)
                self.reply(200, {'ok': True, 'data': result})
            except ServiceError as error: self.reply(200, {'ok': False, 'error': error.code})
            except Exception: self.reply(200, {'ok': False, 'error': 'invalid_request'})
        def reply(self, code, value):
            raw = json.dumps(value, separators=(',', ':')).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            try: self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError): pass
    return Handler

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8787)
    parser.add_argument('--tls-cert')
    parser.add_argument('--tls-key')
    parser.add_argument('--export-connection', type=Path)
    args = parser.parse_args()
    try:
        config = json.loads(args.config.read_text())
        broker = Broker(config)
        if args.export_connection:
            url = require_string(config, 'externalServiceUrl')
            parsed = urllib.parse.urlsplit(url)
            if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
                raise ServiceError('https_service_url_required')
            data = {'clientId': config['clientId'], 'serviceUrl': url, 'serviceToken': config['serviceToken']}
            fd = os.open(args.export_connection, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as file: json.dump(data, file, indent=2)
            print('Connection file written. No application signing keys were exported.')
            return
        if bool(args.tls_cert) != bool(args.tls_key): raise ServiceError('tls_pair_required')
        if args.host not in ['127.0.0.1', 'localhost', '::1'] and not args.tls_cert: raise ServiceError('public_bind_requires_tls')
        server = ThreadingHTTPServer((args.host, args.port), handler_for(broker))
        if args.tls_cert:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(args.tls_cert, args.tls_key)
            server.socket = context.wrap_socket(server.socket, server_side=True)
        print(f'Quark Open connection service listening on {args.host}:{args.port}; access logs disabled.')
        server.serve_forever()
    except (ServiceError, OSError, ValueError) as error:
        parser.exit(1, 'Service configuration/start failed: ' + (error.code if isinstance(error, ServiceError) else type(error).__name__) + '\n')

if __name__ == '__main__': main()
