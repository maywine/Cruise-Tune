#!/usr/bin/env python3
"""Preview or publish versioned notes without rebuilding or replacing release assets."""
import argparse
import json
import os
import subprocess
import sys

from release import ROOT, VERSION_PATTERN, ReleaseError, command, release_notes


def github(args, payload=None):
    result = subprocess.run(['gh', 'api', *args], capture_output=True, text=True,
                            input=json.dumps(payload) if payload is not None else None)
    if result.returncode:
        raise ReleaseError('GitHub release notes request failed: ' + result.stderr.strip())
    return json.loads(result.stdout)


def plan_updates(releases, root=ROOT):
    updates = []
    for item in releases:
        tag = item['tag_name']
        if item['draft'] or not tag.startswith('v') or not VERSION_PATTERN.fullmatch(tag[1:]):
            continue
        if not (root / 'docs/release-notes' / f'{tag[1:]}.md').is_file():
            continue
        commit = command(['git', 'rev-parse', f'refs/tags/{tag}^{{commit}}'], root)
        marker = f'<!-- cruise-tune-release:{tag}:{commit} -->'
        if marker not in (item.get('body') or ''):
            raise ReleaseError(f'{tag}: published notes do not match the release tag ownership')
        body = release_notes(tag[1:], commit, root)
        if item['body'].replace('\r\n', '\n').strip() != body.strip():
            updates.append({'id': item['id'], 'tag': tag, 'body': body})
    return updates


def publish(repo, updates):
    for update in updates:
        # Send only the body field; tags, assets and publication status are immutable here.
        github(['--method', 'PATCH', f'repos/{repo}/releases/{update["id"]}', '--input', '-'],
               {'body': update['body']})
        print(f'Updated {update["tag"]}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default=os.environ.get('GITHUB_REPOSITORY'), required=not os.environ.get('GITHUB_REPOSITORY'))
    parser.add_argument('--write', action='store_true', help='Apply the previewed body changes')
    args = parser.parse_args()
    try:
        pages = github(['--paginate', '--slurp', f'repos/{args.repo}/releases?per_page=100'])
        updates = plan_updates([item for page in pages for item in page])
        if args.write:
            publish(args.repo, updates)
        else:
            for update in updates:
                print(f'Would update {update["tag"]}')
        print(f'{len(updates)} release note bodies {"updated" if args.write else "pending"}.')
    except ReleaseError as exc:
        print(f'Release notes update failed: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
