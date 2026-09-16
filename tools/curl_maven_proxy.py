"""Loopback-only transport adapter for environments where Java TLS to Google resets.

Only public artifacts from Google's fixed Maven origin are fetched, using curl's
normal HTTPS certificate verification. This is a build helper, never part of the APK.
"""
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import hashlib, subprocess, tempfile, threading, urllib.parse

def create_server(cache: Path):
    cache.mkdir(parents=True, exist_ok=True)
    locks = {}
    guard = threading.Lock()
    class Handler(BaseHTTPRequestHandler):
        def do_HEAD(self): self.fetch(False)
        def do_GET(self): self.fetch(True)
        def log_message(self, *args): pass
        def fetch(self, body):
            path = urllib.parse.urlsplit(self.path).path
            if '..' in urllib.parse.unquote(path).split('/'):
                self.send_error(400); return
            dest = cache / hashlib.sha256(path.encode()).hexdigest()
            with guard: lock = locks.setdefault(path, threading.Lock())
            with lock:
                if not dest.exists():
                    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temp: part = Path(temp.name)
                    result = subprocess.run(['curl', '-L', '--silent', '--show-error', '--connect-timeout', '20', '--max-time', '240', '--retry', '1', '-w', '%{http_code}', 'https://dl.google.com/dl/android/maven2' + path, '-o', str(part)], capture_output=True, text=True)
                    if result.returncode or result.stdout.strip() != '200':
                        part.unlink(missing_ok=True)
                        self.send_error(404 if result.stdout.strip() == '404' else 502); return
                    part.replace(dest)
            self.send_response(200)
            self.send_header('Content-Length', str(dest.stat().st_size))
            self.send_header('Content-Type', 'application/octet-stream')
            self.end_headers()
            if body:
                try:
                    with dest.open('rb') as source:
                        while chunk := source.read(1024 * 1024): self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError): pass
    return ThreadingHTTPServer(('127.0.0.1', 0), Handler)
