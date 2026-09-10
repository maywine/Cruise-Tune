#!/usr/bin/env bash
set -euo pipefail

python3 tools/release.py verify-dist --tag "$RELEASE_TAG"
version="${RELEASE_TAG#v}"
metadata="$RUNNER_TEMP/cruise-existing-release.json"

if gh release view "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --json isDraft,body,assets > "$metadata" 2>/dev/null; then
  # Resume only this workflow's matching draft. Published releases stay immutable.
  python3 - "$metadata" <<'PY'
import json, pathlib, sys
release = json.loads(pathlib.Path(sys.argv[1]).read_text())
notes = pathlib.Path('dist/RELEASE_NOTES.md').read_text()
manifest = json.loads(pathlib.Path('dist/release-manifest.json').read_text())
allowed = {manifest['apk'], 'SHA256SUMS', 'release-manifest.json'}
if not release['isDraft'] or release['body'].strip() != notes.strip():
    raise SystemExit('Release already exists and is published or belongs to a different draft; refusing to overwrite.')
if any(asset['name'] not in allowed for asset in release.get('assets', [])):
    raise SystemExit('Draft contains attachments not owned by this workflow; refusing to publish.')
PY
else
  # Authentication/network errors also make this command fail; no existing release is removed.
  gh release create "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --verify-tag \
    --draft --title "Cruise Tune $version" --notes-file dist/RELEASE_NOTES.md
fi

gh release upload "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" \
  "dist/CruiseTune-$version.apk" dist/SHA256SUMS dist/release-manifest.json --clobber

prerelease=false
if [[ "$version" == *-* ]]; then prerelease=true; fi
gh release edit "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" \
  --draft=false --prerelease="$prerelease"
