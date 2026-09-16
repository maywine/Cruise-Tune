import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location('update_notes', TOOLS / 'update-release-notes.py')
update_notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update_notes)


class UpdateReleaseNotesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        notes = self.root / 'docs/release-notes'
        notes.mkdir(parents=True)
        (notes / '0.5.31.md').write_text('- 修复窗口遮挡。\n')
        self.commit = 'a' * 40
        self.item = {'id': 31, 'tag_name': 'v0.5.31', 'draft': False,
                     'body': 'Old notes\n<!-- cruise-tune-release:v0.5.31:' + self.commit + ' -->'}

    def test_updates_only_owned_published_versions_with_source_notes(self):
        items = [dict(self.item, draft=True), dict(self.item, tag_name='v0.5.32'), self.item]
        with patch.object(update_notes, 'command', return_value=self.commit) as git:
            updates = update_notes.plan_updates(items, self.root)
        self.assertEqual(1, len(updates))
        git.assert_called_once_with(['git', 'rev-parse', 'refs/tags/v0.5.31^{commit}'], self.root)
        self.assertIn('- 修复窗口遮挡。', updates[0]['body'])
        self.assertIn(self.commit, updates[0]['body'])
        with patch.object(update_notes, 'command', return_value=self.commit):
            self.assertEqual([], update_notes.plan_updates([dict(self.item, body=updates[0]['body'].replace('\n', '\r\n'))], self.root))

    def test_rejects_missing_or_wrong_source_ownership_before_publishing(self):
        for body in ('Manual release', self.item['body'].replace(self.commit, 'b' * 40)):
            with self.subTest(body=body), patch.object(update_notes, 'command', return_value=self.commit):
                with self.assertRaises(update_notes.ReleaseError):
                    update_notes.plan_updates([dict(self.item, body=body)], self.root)

    def test_patch_payload_contains_body_only(self):
        with patch.object(update_notes.subprocess, 'run') as run:
            run.return_value.returncode = 0
            run.return_value.stdout = '{}'
            update_notes.publish('example/repo', [{'id': 31, 'tag': 'v0.5.31', 'body': 'New notes'}])
        args, kwargs = run.call_args
        self.assertEqual(['gh', 'api', '--method', 'PATCH', 'repos/example/repo/releases/31', '--input', '-'], args[0])
        self.assertEqual({'body': 'New notes'}, json.loads(kwargs['input']))


if __name__ == '__main__':
    unittest.main()
