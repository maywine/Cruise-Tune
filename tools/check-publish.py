#!/usr/bin/env python3
"""Check the staged Git snapshot for local artifacts and likely credentials.
Prints file names and rule names only, never matched secret values.
Optional --private-config detects exact local signing/secret values without publishing them.
"""
import argparse
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(['git', *args])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private-config', type=Path)
    args = parser.parse_args()
    private_values = []
    if args.private_config:
        config = json.loads(args.private_config.read_text())
        private_values = [v.encode() for k, v in config.items()
                          if k in ('clientSecret', 'signKey', 'serviceToken')
                          and isinstance(v, str) and v]
    paths = [p.decode() for p in git('ls-files', '-z').split(b'\0') if p]
    forbidden_names = {'local.properties', 'quark_cli_client.json', 'server.local.json',
                       'pending.json', 'result.json', 'manual-result.json', '.DS_Store'}
    private_dirs = {'sources', 'outputs', 'dist', '.gradle', '.kotlin', 'build', '.venv', 'node_modules', '__pycache__'}
    patterns = {
        'private key': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
        'GitHub credential': re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b'),
        'live authorization code': re.compile(rb'\bAAC-[a-fA-F0-9]{32}\b'),
        'personal absolute path': re.compile(rb'(?:/Users|/home)/[A-Za-z0-9_.-]+/|/var/folders/[A-Za-z0-9_/.-]+'),
    }
    errors = []
    text_suffixes = {'.md', '.txt', '.json', '.xml', '.kt', '.kts', '.py', '.cjs', '.js', '.properties', '.yml', '.yaml', '.sh', '.bat'}
    for name in paths:
        p = PurePosixPath(name)
        if (any(part in private_dirs for part in p.parts) or p.name in forbidden_names
            or p.suffix in {'.apk', '.aab', '.jks', '.keystore', '.pem', '.key', '.log', '.db', '.sqlite', '.sqlite3'}
            or (p.name.startswith('.env') and p.name != '.env.example')):
            errors.append((name, 'private/generated artifact'))
        blob = git('show', ':' + name)
        if any(value in blob for value in private_values):
            errors.append((name, 'exact local secret value'))
        if p.suffix in text_suffixes or not p.suffix:
            for rule, pattern in patterns.items():
                if pattern.search(blob):
                    errors.append((name, rule))
            if p.suffix == '.md' and re.search(rb'[?&](?:access_token|refresh_token|page_code|device_id|agent_auth_code)=[A-Za-z0-9_-]{8,}', blob):
                errors.append((name, 'authorization URL in documentation'))
    for name, rule in errors:
        print(f'{name}: {rule}')
    if errors:
        print(f'Publication check failed: {len(errors)} finding(s).')
        return 1
    print(f'Publication check passed: {len(paths)} staged files; no matching private artifacts or credentials.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
