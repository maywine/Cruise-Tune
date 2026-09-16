import base64
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('release', Path(__file__).resolve().parents[1] / 'release.py')
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / 'android-app').mkdir()
        (self.root / 'docs/release-notes').mkdir(parents=True)
        (self.root / 'docs/release-notes/0.5.2.md').write_text('本版修复缓存状态。\n')
        self.write_version('0.5.2', 11)
        (self.root / 'android-app/release-signing.properties').write_text('CERTIFICATE_SHA256=' + 'a' * 64 + '\n')
        self.env = dict(ANDROID_KEYSTORE_BASE64=base64.b64encode(b'test-keystore').decode(),
                        ANDROID_KEYSTORE_PASSWORD='test-store-password', ANDROID_KEY_ALIAS='test-key',
                        ANDROID_KEY_PASSWORD='test-key-password',
                        QUARK_CLIENT_CONFIG_JSON=json.dumps({'clientId': 'test-client', 'signKey': 'test-sign'}),
                        RUNNER_TEMP=str(self.root / 'runner'), GITHUB_ENV=str(self.root / 'env'))
        (self.root / 'runner').mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def write_version(self, name, code):
        (self.root / 'android-app/version.properties').write_text(f'VERSION_NAME={name}\nVERSION_CODE={code}\n')

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.DEVNULL).decode().strip()

    def commit_version(self, tag):
        self.git('add', 'android-app/version.properties')
        self.git('-c', 'user.name=Release Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', tag)
        self.git('tag', tag)

    def fixture_apk(self):
        apk = self.root / 'android-app/app/build/outputs/apk/release/app-release.apk'
        apk.parent.mkdir(parents=True)
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('assets/quark_cli_client.json', self.env['QUARK_CLIENT_CONFIG_JSON'])
        return apk

    def package(self, badging=None, fingerprint=None):
        self.fixture_apk()
        output = [f'Signer #1 certificate SHA-256 digest: {fingerprint or "a" * 64}',
                  badging or "package: name='com.cruisetune.player' versionCode='11' versionName='0.5.2'",
                  'b' * 40]
        with patch.object(release, 'command', side_effect=output):
            return release.package('v0.5.2', self.root, {'ANDROID_HOME': '/test-sdk'})

    def test_stable_and_numbered_prerelease_versions(self):
        for name in ['0.5.2', '1.0.0-alpha.1', '1.0.0-beta.2', '1.0.0-rc.3']:
            self.write_version(name, 12)
            self.assertEqual(name, release.validate('v' + name, root=self.root)['name'])

    def test_rejects_ambiguous_versions_and_codes(self):
        for name, code in [('01.2.3', 1), ('1.2', 1), ('1.2.3-rc.0', 1), ('1.2.3+meta', 1),
                           ('1.2.3', 0), ('1.2.3', '01'), ('1.2.3', 2100000001)]:
            with self.assertRaises(release.ReleaseError):
                release.version(f'VERSION_NAME={name}\nVERSION_CODE={code}')
        with self.assertRaises(release.ReleaseError):
            release.version('VERSION_NAME=1.2.3\nVERSION_CODE=1\nVERSION_CODE=2')

    def test_tag_must_match_code_version(self):
        with self.assertRaises(release.ReleaseError):
            release.validate('v0.5.3', root=self.root)

    def test_android_code_must_increase_across_tags(self):
        self.git('init', '-q')
        self.commit_version('v0.5.2')
        self.write_version('0.5.3-rc.1', 12)
        self.commit_version('v0.5.3-rc.1')
        self.assertEqual(12, release.validate('v0.5.3-rc.1', True, self.root)['code'])
        self.write_version('0.5.3', 12)
        self.commit_version('v0.5.3')
        with self.assertRaises(release.ReleaseError):
            release.validate('v0.5.3', True, self.root)

    def test_tag_must_point_to_checkout(self):
        self.git('init', '-q')
        self.commit_version('v0.5.2')
        self.write_version('0.5.3', 12)
        self.commit_version('v0.5.3')
        # Matching worktree version is insufficient if the tag points elsewhere.
        self.git('tag', '-f', 'v0.5.3', 'v0.5.2')
        with self.assertRaises(release.ReleaseError):
            release.validate('v0.5.3', True, self.root)

    def test_missing_inputs_fail_without_echoing_secret_values(self):
        env = dict(self.env)
        del env['ANDROID_KEY_PASSWORD']
        with self.assertRaises(release.ReleaseError) as context:
            release.signing_inputs(env)
        self.assertIn('ANDROID_KEY_PASSWORD', str(context.exception))
        self.assertNotIn('test-store-password', str(context.exception))

    def test_rejects_user_tokens_in_client_config(self):
        env = dict(self.env, QUARK_CLIENT_CONFIG_JSON=json.dumps({'clientId': 'test-client', 'signKey': 'test-sign', 'access_token': 'test-token'}))
        with self.assertRaises(release.ReleaseError):
            release.signing_inputs(env)
        with self.assertRaises(release.ReleaseError):
            release.signing_inputs(dict(self.env, ANDROID_KEYSTORE_BASE64='%%%'))

    def test_prepare_and_cleanup_own_only_temporary_files(self):
        release.prepare(self.root, self.env)
        key = self.root / 'runner/cruise-release-signing/release.keystore'
        client = self.root / 'android-app/app/src/main/assets/quark_cli_client.json'
        self.assertEqual(0o600, key.stat().st_mode & 0o777)
        self.assertEqual(0o600, client.stat().st_mode & 0o777)
        self.assertNotIn('test-sign', (self.root / 'env').read_text())
        release.cleanup(self.root, self.env)
        self.assertFalse(client.exists())
        self.assertFalse(key.exists())

    def test_never_overwrites_or_cleans_existing_local_config(self):
        client = self.root / 'android-app/app/src/main/assets/quark_cli_client.json'
        client.parent.mkdir(parents=True)
        client.write_text('existing-local-data')
        with self.assertRaises(release.ReleaseError):
            release.prepare(self.root, self.env)
        release.cleanup(self.root, self.env)
        self.assertEqual('existing-local-data', client.read_text())

    def test_package_and_verify_dist(self):
        data = self.package()
        verified = release.verify_dist('v0.5.2', 'b' * 40, self.root)
        self.assertEqual(data, verified)
        self.assertNotIn('test-sign', (self.root / 'dist/release-manifest.json').read_text())

    def test_rejects_changed_signing_certificate(self):
        with self.assertRaises(release.ReleaseError):
            self.package(fingerprint='c' * 64)

    def test_packages_notes_for_the_exact_version_and_keeps_ownership_marker(self):
        notes = self.root / 'docs/release-notes'
        (notes / '0.5.3.md').write_text('Future version notes')
        self.package()
        body = (self.root / 'dist/RELEASE_NOTES.md').read_text()
        self.assertIn('本版修复缓存状态。', body)
        self.assertNotIn('Future version notes', body)
        self.assertEqual('# Cruise Tune 0.5.2\n\n本版修复缓存状态。\n\n'
                         '<!-- cruise-tune-release:v0.5.2:' + 'b' * 40 + ' -->\n', body)
        release.verify_dist('v0.5.2', 'b' * 40, self.root)

    def test_requires_nonempty_version_notes_before_creating_artifacts(self):
        notes = self.root / 'docs/release-notes/0.5.2.md'
        notes.write_text(' \n')
        with self.assertRaisesRegex(release.ReleaseError, 'Missing release notes'):
            self.package()
        self.assertFalse((self.root / 'dist').exists())
        notes.unlink()
        with self.assertRaisesRegex(release.ReleaseError, 'Missing release notes'):
            release.release_notes('0.5.2', 'b' * 40, self.root)

    def test_rejects_debuggable_and_wrong_version_apks(self):
        for badging in ["package: name='com.cruisetune.player' versionCode='11' versionName='0.5.2'\napplication-debuggable",
                        "package: name='com.cruisetune.player' versionCode='11' versionName='0.5.3'"]:
            with self.subTest(badging=badging):
                with patch.object(release, 'command', side_effect=['Signer #1 certificate SHA-256 digest: ' + 'a' * 64, badging]):
                    with self.assertRaises(release.ReleaseError):
                        release.package('v0.5.2', self.root, {'ANDROID_HOME': '/test-sdk'})

    def test_rejects_tampered_or_wrong_commit_artifacts(self):
        data = self.package()
        with self.assertRaises(release.ReleaseError):
            release.verify_dist('v0.5.2', 'c' * 40, self.root)
        (self.root / 'dist' / data['apk']).write_bytes(b'tampered')
        with self.assertRaises(release.ReleaseError):
            release.verify_dist('v0.5.2', 'b' * 40, self.root)

    def test_publish_recovers_own_draft_and_preserves_published_or_foreign_releases(self):
        self.package()
        (self.root / 'tools').mkdir()
        shutil.copy2(release.ROOT / 'tools/release.py', self.root / 'tools/release.py')
        binaries = self.root / 'bin'
        binaries.mkdir()
        (binaries / 'git').write_text("#!/usr/bin/env python3\nprint('b' * 40)\n")
        (binaries / 'gh').write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
p = pathlib.Path(os.environ['FAKE_RELEASE_STATE'])
args = sys.argv[1:]
action = args[1]
if action == 'view':
    if not p.exists(): sys.exit(1)
    print(p.read_text())
elif action == 'create':
    p.write_text(json.dumps({'isDraft': True, 'body': pathlib.Path('dist/RELEASE_NOTES.md').read_text()}))
elif action == 'upload':
    if os.environ.get('FAIL_UPLOAD') == '1': sys.exit(1)
    d = json.loads(p.read_text())
    d['assets'] = [{'name': pathlib.Path(x).name} for x in args if x.startswith('dist/')]
    p.write_text(json.dumps(d))
elif action == 'edit':
    d = json.loads(p.read_text())
    assert len(d['assets']) == 3
    d['isDraft'] = False
    p.write_text(json.dumps(d))
else: sys.exit(2)
''')
        for binary in binaries.iterdir():
            binary.chmod(0o755)
        state = self.root / 'fake-release.json'
        env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ['PATH'],
                   RELEASE_TAG='v0.5.2', GITHUB_REPOSITORY='example/test', RUNNER_TEMP=str(self.root / 'runner'),
                   FAKE_RELEASE_STATE=str(state))
        script = release.ROOT / '.github/scripts/publish-release.sh'
        def publish(extra=None):
            return subprocess.run(['bash', str(script)], cwd=self.root, env=dict(env, **(extra or {})), capture_output=True, text=True)
        self.assertNotEqual(0, publish({'FAIL_UPLOAD': '1'}).returncode)
        self.assertTrue(json.loads(state.read_text())['isDraft'])
        self.assertEqual(0, publish().returncode)
        self.assertFalse(json.loads(state.read_text())['isDraft'])
        saved = state.read_bytes()
        self.assertNotEqual(0, publish().returncode)
        self.assertEqual(saved, state.read_bytes())
        state.write_text(json.dumps({'isDraft': True, 'body': (self.root / 'dist/RELEASE_NOTES.md').read_text(),
                                     'assets': [{'name': 'unrelated-private-file.txt'}]}))
        saved = state.read_bytes()
        self.assertNotEqual(0, publish().returncode)
        self.assertEqual(saved, state.read_bytes())
        state.write_text(json.dumps({'isDraft': True, 'body': 'Unrelated manual draft'}))
        saved = state.read_bytes()
        self.assertNotEqual(0, publish().returncode)
        self.assertEqual(saved, state.read_bytes())


if __name__ == '__main__':
    unittest.main()
