#!/usr/bin/env python3
"""Isolated Quark web QR + read-only media probe. Cookies/tickets stay in memory.

Adapted from this project's QuarkQrAuth protocol; playback fields are referenced
from OpenList, download compatibility from QuarkPanTool. Never imports their code.
No upload, sharing, file changes, token export, or player configuration changes.
"""
import argparse
import http.cookiejar
import html
import json
import os
from pathlib import Path
import re
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BROWSER = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
           '(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36')
PC = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) quark-cloud-drive/2.5.56 Chrome/100.0.4896.160 '
      'Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch')
OPENLIST_PC = PC.replace('2.5.56', '2.5.20').replace('18.3.5.12-a038f7b798', '18.3.5.4-b478491100')

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None

class Client:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPCookieProcessor(self.jar),
            urllib.request.HTTPSHandler(context=ssl.create_default_context()))

    def open(self, url, query=None, body=None, ua=BROWSER, headers=None):
        parsed = urllib.parse.urlsplit(url)
        if parsed.scheme != 'https' or not (parsed.hostname == 'quark.cn' or parsed.hostname.endswith('.quark.cn')):
            raise ValueError('unexpected service host')
        if parsed.username or parsed.password or parsed.port not in (None, 443):
            raise ValueError('unexpected service address')
        if query:
            url += ('&' if parsed.query else '?') + urllib.parse.urlencode(query)
        req = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None,
            headers={'User-Agent': ua, 'Referer': 'https://pan.quark.cn/', 'Accept': 'application/json',
                     'Content-Type': 'application/json', **(headers or {})})
        try:
            return self.opener.open(req, timeout=25)
        except urllib.error.HTTPError as e:
            return e

    def json(self, url, **kwargs):
        with self.open(url, **kwargs) as response:
            raw = response.read(2 * 1024 * 1024 + 1)
            if len(raw) > 2 * 1024 * 1024:
                raise ValueError('response too large')
            return response.code, json.loads(raw)

    def drive(self, path, *, host='drive-pc.quark.cn', query=None, **kwargs):
        return self.json(f'https://{host}/1/clouddrive/{path}', query={'pr': 'ucpro', 'fr': 'pc', **(query or {})}, **kwargs)

def numbers(http, data):
    result = {'httpStatus': http}
    for key in ('status', 'code', 'errno'):
        if isinstance(data.get(key), (int, float)):
            result[key] = data[key]
    # Export only short natural-language error text; never raw response data.
    for key in ('message', 'error_info'):
        value = data.get(key, '')
        if isinstance(value, str) and re.fullmatch(r'[a-zA-Z_ ,.:()\u4e00-\u9fff，。]{1,160}', value) and not re.search(r'[a-zA-Z_]{24,}', value):
            result['serviceMessage'] = value
    return result

def publish(out, report, stage):
    report['stage'] = stage
    path = out / 'result.json'
    fd = os.open(path, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(stage, flush=True)

def qr_login(client, out, report):
    import qrcode
    def params():
        return {'client_id': '532', 'v': '1.2', 'request_id': str(uuid.uuid4())}
    base = 'https://uop.quark.cn/cas/ajax/'
    http, reply = client.json(base + 'getTokenForQrcodeLogin', query=params())
    if reply.get('status') != 2000000:
        report['loginResponse'] = numbers(http, reply)
        return False
    token = reply['data']['members']['token']
    url = 'https://su.quark.cn/4_eMHBJ?' + urllib.parse.urlencode({
        'token': token, 'client_id': '532', 'ssb': 'weblogin', 'uc_param_str': '',
        'uc_biz_str': 'S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0'})
    qrcode.make(url, box_size=9, border=4).save(out / 'login-qr.png')
    publish(out, report, 'QR_READY')
    until = time.monotonic() + 300
    failures = 0
    while time.monotonic() < until:
        time.sleep(2)
        try:
            http, reply = client.json(base + 'getServiceTicketByQrcodeToken', query={**params(), 'token': token})
            failures = 0
        except (OSError, ValueError):
            failures += 1
            if failures >= 3:
                raise RuntimeError('QR poll network failure') from None
            continue
        if reply.get('status') in (50004002, 50004003, 50004004):
            publish(out, report, 'QR_EXPIRED')
            return False
        ticket = reply.get('data', {}).get('members', {}).get('service_ticket')
        if reply.get('status') == 2000000 and ticket:
            client.json('https://pan.quark.cn/account/info', query={'st': ticket, 'lw': 'scan'})
            client.drive('config', host='drive.quark.cn')
            client.drive('config')
            publish(out, report, 'LOGIN_CONFIRMED')
            return True
    publish(out, report, 'QR_EXPIRED')
    return False

def inspect_media(client, url, ua=BROWSER):
    with client.open(url, ua=ua, headers={'Range': 'bytes=0-65535'}) as response:
        data = response.read(65536)
        content = response.headers.get('Content-Type', '').split(';')[0]
        kind = 'flac' if data.startswith(b'fLaC') else 'hls' if data.startswith(b'#EXTM3U') else 'other'
        return {'httpStatus': response.code, 'bytesRead': len(data), 'format': kind,
                'contentType': content if re.fullmatch(r'[a-zA-Z0-9.+/-]{1,80}', content) else 'unknown'}

def media_urls(node):
    urls = []
    if isinstance(node, dict):
        for key, value in node.items():
            if key in ('url', 'play_url', 'download_url') and isinstance(value, str) and value.startswith('https://'):
                urls.append(value)
            elif isinstance(value, (dict, list)):
                urls.extend(media_urls(value))
    elif isinstance(node, list):
        for item in node:
            urls.extend(media_urls(item))
    return list(dict.fromkeys(urls))

def run(args):
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    client = Client()
    report = {'authentication': 'isolated_web_cookie', 'changesPlayerState': False}
    try:
        if not qr_login(client, args.output, report):
            publish(args.output, report, report.get('stage', 'LOGIN_FAILED'))
            return
        http, data = client.drive('file/search', query={'q': args.keyword, '_page': '1', '_size': '100', '_fetch_total': '1', '_is_hl': '0'})
        report['search'] = numbers(http, data)
        candidates = []
        for f in data.get('data', {}).get('list', []):
            name = html.unescape(re.sub('<[^>]*>', '', f.get('file_name', '')))
            if args.keyword in name and name.lower().endswith('.flac') and f.get('size') == args.size:
                candidates.append(f)
        report['matchingFiles'] = len(candidates)
        if len(candidates) != 1:
            publish(args.output, report, 'FILE_SELECTION_REQUIRED')
            return
        file = candidates[0]
        report['fileSizeBytes'] = file['size']
        http, data = client.drive('file/v2/play/project', host='drive.quark.cn', ua=OPENLIST_PC,
            body={'fid': file['fid'], 'resolutions': 'low,normal,high,super,2k,4k', 'supports': 'fmp4_av,m3u8,dolby_vision'})
        report['playbackApi'] = numbers(http, data)
        urls = media_urls(data.get('data', {}))
        report['playbackApi']['candidateMediaUrls'] = len(urls)
        if urls:
            report['playbackApi']['firstRead'] = inspect_media(client, urls[0], OPENLIST_PC)
        publish(args.output, report, 'PLAYBACK_PROBED')
        report['downloadApi'] = []
        for label, ua in [('browser', BROWSER), ('pc_client', PC)]:
            http, data = client.drive('file/download', ua=ua, body={'fids': [file['fid']]},
                query={'sys': 'win32', 've': '2.5.56', 'ut': '', 'guid': ''})
            result = {'requestProfile': label, **numbers(http, data)}
            rows = data.get('data')
            if isinstance(rows, list) and rows and rows[0].get('download_url'):
                result['firstRead'] = inspect_media(client, rows[0]['download_url'], ua)
            report['downloadApi'].append(result)
        publish(args.output, report, 'PROBE_COMPLETE')
    except Exception as e:
        report['errorClass'] = type(e).__name__
        publish(args.output, report, 'PROBE_FAILED')
    finally:
        client.jar.clear()
        (args.output / 'login-qr.png').unlink(missing_ok=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--keyword', required=True)
    parser.add_argument('--size', type=int, required=True)
    run(parser.parse_args())
