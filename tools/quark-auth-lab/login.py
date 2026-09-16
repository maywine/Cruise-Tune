#!/usr/bin/env python3
"""Standalone experimental reproduction of Quark CLI 1.0.18 browser login.
Reads bundled client parameters from a local reference, never imports/executes JS.
No player credentials, telemetry, file access API, or persisted user tokens.
"""
import argparse, hashlib, json, os, re, ssl, time, urllib.parse, urllib.request, uuid
from pathlib import Path

API = 'https://open-api-drive.quark.cn'
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs): return None

def private_json(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as f: json.dump(data, f, ensure_ascii=False, indent=2)

def client_from_source(path):
    text = path.read_text()
    section = text.split('Ft(Gh, { WILD_DEFAULT_CLIENT_INFO:', 1)[1]
    block = re.search(r'ZE\s*=\s*\{(.*?)\}', section, re.S).group(1)
    return {key: json.loads(re.search(r'\b'+key+r':\s*("[^"\n]+")', block).group(1)) for key in ('clientId','signKey')}

class Client:
    def __init__(self, info):
        self.info = info
        self.opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=ssl.create_default_context()))
    def request(self, method, path, query=None, body=None):
        stamp = str(int(time.time()*1000))
        signature = hashlib.sha256(f'{method}&{path}&{stamp}&{self.info["signKey"]}'.encode()).hexdigest()
        headers = {'x-pan-client-id':self.info['clientId'], 'x-pan-tm':stamp, 'x-pan-token':signature, 'Content-Type':'application/json', 'Accept':'application/json'}
        url = API+path+'?'+urllib.parse.urlencode(dict(query or {}, req_id=str(uuid.uuid4())))
        req = urllib.request.Request(url, data=json.dumps(body).encode() if body is not None else None, method=method, headers=headers)
        with self.opener.open(req, timeout=20) as response:
            raw=response.read(2*1024*1024+1)
        if len(raw)>2*1024*1024: raise ValueError('response too large')
        return json.loads(raw)

def run(args):
    args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(args.output,0o700)
    api=Client(client_from_source(args.source))
    identity={'client_device_id':str(uuid.uuid4()), 'device_name':'Cruise Tune Python Login Test', 'agent_id':'cruise-tune-lab', 'work_dir':'cruise-tune-auth-lab'}
    reply=api.request('POST','/agent/v1/get_authorize_page_url',body=dict(identity, client_id=api.info['clientId'], is_cloud_agent='false', is_unsure_agent='true'))
    data=reply.get('data') or {}
    if reply.get('status')!=0 or not data.get('authorize_page_url') or not data.get('page_code'):
        private_json(args.output/'result.json',{'stage':'create','status':reply.get('status'),'errno':reply.get('errno')}); print('Authorization page rejected; see sanitized result.',flush=True); return 1
    parsed=urllib.parse.urlsplit(data['authorize_page_url'])
    if parsed.scheme!='https' or parsed.hostname!='pan.quark.cn' or parsed.username or parsed.password:
        raise ValueError('unexpected authorization host')
    query=dict(urllib.parse.parse_qsl(parsed.query,keep_blank_values=True))
    query.update(client_id=api.info['clientId'],scope='clouddrive.base`clouddrive.netdisk',redirect_uri='',client_device_id=identity['client_device_id'],device_name=identity['device_name'],agent_id=identity['agent_id'])
    if data.get('device_id') and 'device_id' not in query: query['device_id']=data['device_id']
    url=urllib.parse.urlunsplit(parsed._replace(query=urllib.parse.urlencode(query)))
    private_json(args.output/'pending.json',{'authorizeUrl':url})
    print('AUTH_PAGE_READY: pending.json; waiting for user scan and confirmation.',flush=True)
    deadline=time.monotonic()+args.timeout
    while time.monotonic()<deadline:
        reply=api.request('GET','/agent/v1/oauth/get_aac_by_pagecode',{'page_code':data['page_code']})
        poll=reply.get('data') or {}
        if reply.get('status')==0 and poll.get('status')=='success' and poll.get('agent_auth_code'):
            result=api.request('GET','/agent/v1/oauth/agent_auth_code',dict(identity,agent_auth_code=poll['agent_auth_code']))
            token=result.get('data') or {}
            success=result.get('status')==0 and bool(token.get('access_token')) and bool(token.get('user_id'))
            private_json(args.output/'result.json',{'stage':'exchange','success':success,'status':result.get('status'),'errno':result.get('errno'),'authorizationStatus':token.get('status'),'hasAccessToken':bool(token.get('access_token')),'hasRefreshToken':bool(token.get('refresh_token'))})
            print('LOGIN_SUCCESS; tokens kept only in memory and now discarded.' if success else 'EXCHANGE_FAILED; see sanitized result.',flush=True)
            return 0 if success else 1
        if poll.get('status') in ('expired','cancelled','denied'):
            private_json(args.output/'result.json',{'stage':'poll','status':poll['status']});print('Authorization expired or cancelled.',flush=True);return 1
        time.sleep(2)
    private_json(args.output/'result.json',{'stage':'poll','status':'timeout'})
    print('Waiting timed out. Rerun to generate a fresh page.',flush=True);return 2

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--timeout',type=int,default=300);args=p.parse_args()
    try: raise SystemExit(run(args))
    except Exception as e:
        # Exceptions can contain URLs with credentials; do not print exception text.
        print('Test failed: '+type(e).__name__,flush=True);raise SystemExit(1)
