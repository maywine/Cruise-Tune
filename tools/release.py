#!/usr/bin/env python3
"""Version, signing-input and APK checks for tag-triggered releases (stdlib only)."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent.parent
VERSION_PATTERN = re.compile(r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)\.[1-9][0-9]*)?')
REQUIRED_SECRETS = ('ANDROID_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD', 'QUARK_CLIENT_CONFIG_JSON')


class ReleaseError(Exception):
    pass


def properties(text):
    result = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(('#', '!')):
            continue
        key, separator, value = line.partition('=')
        key, value = key.strip(), value.strip()
        if not separator or key in result:
            raise ReleaseError('Invalid or duplicate version/signing property')
        result[key] = value
    return result


def version(text):
    values = properties(text)
    name, code = values.get('VERSION_NAME', ''), values.get('VERSION_CODE', '')
    if set(values) != {'VERSION_NAME', 'VERSION_CODE'} or not VERSION_PATTERN.fullmatch(name):
        raise ReleaseError('Use VERSION_NAME=X.Y.Z or X.Y.Z-alpha.N/beta.N/rc.N')
    if not re.fullmatch(r'[1-9][0-9]*', code) or not 1 <= int(code) <= 2100000000:
        raise ReleaseError('VERSION_CODE must be a positive Android version code')
    return {'name': name, 'code': int(code)}


def command(args, cwd=ROOT):
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    if result.returncode:
        raise ReleaseError(f'{Path(args[0]).name} failed; inspect the relevant build step')
    return result.stdout.strip()


def validate(tag, history=False, root=ROOT):
    current = version((root / 'android-app/version.properties').read_text())
    if tag != 'v' + current['name']:
        raise ReleaseError('Tag must exactly match v + VERSION_NAME')
    if history:
        head = command(['git', 'rev-parse', 'HEAD'], root)
        tagged = command(['git', 'rev-parse', f'refs/tags/{tag}^{{commit}}'], root)
        if tagged != head:
            raise ReleaseError('Checked out commit does not match the release tag')
        for previous in command(['git', 'tag', '--list', 'v*'], root).splitlines():
            if previous == tag or not VERSION_PATTERN.fullmatch(previous[1:]):
                continue
            old = command(['git', 'show', f'refs/tags/{previous}:android-app/version.properties'], root)
            if version(old)['code'] >= current['code']:
                raise ReleaseError('VERSION_CODE must exceed every earlier version tag, including prereleases')
    return current


def signing_inputs(environ):
    missing = [name for name in REQUIRED_SECRETS if not environ.get(name)]
    if missing:
        raise ReleaseError('Missing repository Secrets: ' + ', '.join(missing))
    try:
        keystore = base64.b64decode(''.join(environ['ANDROID_KEYSTORE_BASE64'].split()), validate=True)
        config = json.loads(environ['QUARK_CLIENT_CONFIG_JSON'])
    except (ValueError, TypeError):
        raise ReleaseError('Invalid keystore encoding or client JSON (values withheld)') from None
    if not keystore or len(keystore) > 1048576:
        raise ReleaseError('Keystore is empty or too large')
    if (not isinstance(config, dict) or set(config) != {'clientId', 'signKey'}
            or any(not isinstance(v, str) or not v.strip() for v in config.values())):
        raise ReleaseError('Client JSON must contain only nonempty clientId and signKey; no user credentials')
    return keystore, config


def private_write(path, data):
    with path.open('xb') as file:
        os.chmod(path, 0o600)
        file.write(data)


def prepare(root=ROOT, environ=os.environ):
    keystore, config = signing_inputs(environ)
    target = root / 'android-app/app/src/main/assets/quark_cli_client.json'
    if target.exists():
        raise ReleaseError('Refusing to overwrite an existing local client configuration')
    temporary = Path(environ['RUNNER_TEMP']) / 'cruise-release-signing'
    temporary.mkdir(mode=0o700)
    key = temporary / 'release.keystore'
    private_write(temporary / 'owned.json', json.dumps({'clientFile': str(target)}).encode())
    private_write(key, keystore)
    target.parent.mkdir(parents=True, exist_ok=True)
    private_write(target, json.dumps(config).encode())
    with Path(environ['GITHUB_ENV']).open('a') as output:
        output.write(f'CRUISE_KEYSTORE_PATH={key}\n')


def cleanup(root=ROOT, environ=os.environ):
    temporary = Path(environ['RUNNER_TEMP']) / 'cruise-release-signing'
    marker = temporary / 'owned.json'
    if marker.is_file():
        target = root / 'android-app/app/src/main/assets/quark_cli_client.json'
        if json.loads(marker.read_text()).get('clientFile') != str(target):
            raise ReleaseError('Cleanup ownership does not match this checkout')
        target.unlink(missing_ok=True)
        shutil.rmtree(temporary)


def package(tag, root=ROOT, environ=os.environ):
    current = validate(tag, root=root)
    sdk = Path(environ.get('ANDROID_HOME') or environ.get('ANDROID_SDK_ROOT') or '') / 'build-tools/35.0.0'
    apk = root / 'android-app/app/build/outputs/apk/release/app-release.apk'
    signed = command([str(sdk / 'apksigner'), 'verify', '--print-certs', str(apk)], root)
    fingerprints = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', signed, re.M)
    expected = properties((root / 'android-app/release-signing.properties').read_text()).get('CERTIFICATE_SHA256', '').lower()
    if not re.fullmatch(r'[0-9a-f]{64}', expected) or [x.lower() for x in fingerprints] != [expected]:
        raise ReleaseError('APK certificate does not match release-signing.properties; refusing an incompatible update')
    badging = command([str(sdk / 'aapt'), 'dump', 'badging', str(apk)], root)
    header = badging.splitlines()[0]
    for field, value in {'name': 'com.cruisetune.player', 'versionCode': current['code'], 'versionName': current['name']}.items():
        if f"{field}='{value}'" not in header:
            raise ReleaseError('APK package or version differs from release metadata')
    if 'application-debuggable' in badging:
        raise ReleaseError('Refusing to publish a debuggable APK')
    with zipfile.ZipFile(apk) as archive:
        try:
            config = json.loads(archive.read('assets/quark_cli_client.json'))
        except (KeyError, ValueError):
            raise ReleaseError('Release APK lacks a valid Quark client configuration') from None
        if set(config) != {'clientId', 'signKey'} or any(not isinstance(v, str) or not v.strip() for v in config.values()):
            raise ReleaseError('Release client configuration is incomplete or contains unexpected fields')
    commit = command(['git', 'rev-parse', 'HEAD'], root)
    out = root / 'dist'
    out.mkdir(exist_ok=True)
    if any(out.iterdir()):
        raise ReleaseError('dist must be empty before packaging')
    filename = f'CruiseTune-{current["name"]}.apk'
    shutil.copy2(apk, out / filename)
    checksum = hashlib.sha256(apk.read_bytes()).hexdigest()
    manifest = {'versionName': current['name'], 'versionCode': current['code'], 'tag': tag, 'commit': commit,
                'apk': filename, 'sha256': checksum, 'certificateSha256': expected, 'quarkClientConfigured': True}
    (out / 'release-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    (out / 'SHA256SUMS').write_text(f'{checksum}  {filename}\n')
    (out / 'RELEASE_NOTES.md').write_text(
        f'# Cruise Tune {current["name"]}\n\n'
        f'Android API 23+ · versionCode {current["code"]}\n\n'
        '包含本地目录播放、夸克扫码接入、播放进度恢复、后 3 首自动缓存和熄屏暂停。\n\n'
        '下载下方 APK 安装；已有版本须使用相同签名才能保留数据覆盖更新。\n'
        '客户端配置用于授权协议，不包含任何预登录账号或用户令牌。\n\n'
        f'Source commit: `{commit}`\n\n<!-- cruise-tune-release:{tag}:{commit} -->\n')
    return manifest


def verify_dist(tag, commit, root=ROOT):
    current = validate(tag, root=root)
    out = root / 'dist'
    data = json.loads((out / 'release-manifest.json').read_text())
    expected_name = f'CruiseTune-{current["name"]}.apk'
    expected_files = {expected_name, 'release-manifest.json', 'SHA256SUMS', 'RELEASE_NOTES.md'}
    if {p.name for p in out.iterdir()} != expected_files or any(p.is_symlink() for p in out.iterdir()):
        raise ReleaseError('Unexpected release artifact files')
    if (data.get('tag') != tag or data.get('commit') != commit or data.get('apk') != expected_name
            or data.get('versionCode') != current['code'] or data.get('versionName') != current['name']):
        raise ReleaseError('Release artifacts do not belong to this tag and commit')
    digest = hashlib.sha256((out / expected_name).read_bytes()).hexdigest()
    if digest != data.get('sha256') or (out / 'SHA256SUMS').read_text() != f'{digest}  {expected_name}\n':
        raise ReleaseError('Release APK checksum mismatch')
    marker = f'<!-- cruise-tune-release:{tag}:{commit} -->'
    if marker not in (out / 'RELEASE_NOTES.md').read_text():
        raise ReleaseError('Missing release ownership marker')
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['validate', 'check-inputs', 'prepare', 'cleanup', 'package', 'verify-dist'])
    parser.add_argument('--tag', default=os.environ.get('RELEASE_TAG'))
    parser.add_argument('--check-history', action='store_true')
    args = parser.parse_args()
    try:
        if args.action == 'validate':
            current = validate(args.tag, args.check_history)
            print(json.dumps(current))
        elif args.action == 'check-inputs':
            signing_inputs(os.environ)
            print('Required release inputs are present and structurally valid (values withheld).')
        elif args.action == 'prepare':
            prepare()
        elif args.action == 'cleanup':
            cleanup()
        elif args.action == 'package':
            print(json.dumps(package(args.tag)))
        else:
            print(json.dumps(verify_dist(args.tag, command(['git', 'rev-parse', 'HEAD']))))
    except ReleaseError as exc:
        print(f'Release check failed: {exc}', file=sys.stderr)
        return 1
    except Exception:
        # Parsing / filesystem errors can contain sensitive input; never echo them.
        print('Release step failed; check paths and input formatting (details withheld).', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
