import hashlib
import unittest

from rivo_relay.codex.connection import CodexError
from rivo_relay.codex.diff_projection import diff_page, structured_diff


def snapshot(patch, truncated=False):
    return {'patch': patch, 'revision': hashlib.sha256(patch.encode()).hexdigest(), 'truncated': truncated}


PATCH = '''diff --git a/a/file name.kt b/a/file name.kt
index 111..222 100644
--- a/a/file name.kt
+++ b/a/file name.kt
@@ -2,2 +2,3 @@
 unchanged
-before
+after
+another
\\ No newline at end of file
diff --git a/old b/new
similarity index 100%
rename from old
rename to new
diff --git a/image.png b/image.png
Binary files a/image.png and b/image.png differ
'''


class DiffProjectionTests(unittest.TestCase):
    def test_file_hunk_lines_and_metadata_are_attributed(self):
        files = structured_diff(snapshot(PATCH))['files']
        self.assertEqual(files[0]['path'], 'a/file name.kt')
        self.assertEqual((files[0]['added'], files[0]['removed']), (2, 1))
        lines = files[0]['hunks'][0]['lines']
        self.assertEqual([(line['oldLine'], line['newLine']) for line in lines],
                         [(2, 2), (3, None), (None, 3), (None, 4), (None, None)])
        self.assertEqual((files[1]['status'], files[1]['oldPath'], files[1]['path']), ('renamed', 'old', 'new'))
        self.assertTrue(files[2]['binary'])

    def test_pagination_is_revision_bound_and_lossless(self):
        diff = snapshot(PATCH)
        first = diff_page(diff, limit=3)
        lines, page = list(first['lines']), first
        while page['nextOffset'] is not None:
            page = diff_page(diff, revision=diff['revision'], offset=page['nextOffset'], limit=3)
            self.assertNotIn('patch', page)
            lines.extend(page['lines'])
        self.assertEqual(lines, diff_page(diff, limit=1000)['lines'])
        changed = snapshot(PATCH.replace('after', 'changed'))
        with self.assertRaises(CodexError) as error:
            diff_page(changed, revision=diff['revision'], offset=3)
        self.assertEqual(error.exception.http_status, 409)

    def test_file_page_truncation_empty_and_invalid_selection(self):
        diff = snapshot(PATCH, truncated=True)
        page = diff_page(diff)
        file_id = page['files'][1]['id']
        selected = diff_page(diff, revision=diff['revision'], file_id=file_id)
        self.assertEqual({line['path'] for line in selected['lines']}, {'new'})
        self.assertTrue(page['files'][-1]['truncated'])
        self.assertEqual(diff_page(snapshot(''))['lines'], [])
        for params in ({'file_id': 'gone'}, {'offset': 9999}, {'limit': 0}, {'offset': -1}):
            with self.assertRaises(CodexError): diff_page(diff, **params)

    def test_quoted_unicode_and_deleted_paths(self):
        patch = 'diff --git "a/\\344\\270\\255.txt" "b/\\344\\270\\255.txt"\n--- "a/\\344\\270\\255.txt"\n+++ /dev/null\n@@ -1 +0,0 @@\n-old\n'
        file = structured_diff(snapshot(patch))['files'][0]
        self.assertEqual(file['path'], '中.txt')
        self.assertEqual(file['status'], 'deleted')
        self.assertEqual(file['removed'], 1)

    def test_malformed_hunk_cannot_supply_authoritative_statistics(self):
        for suffix in ('+extra\n', '@@ malformed\n'):
            patch = 'diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-old\n+new\n' + suffix
            result = structured_diff(snapshot(patch))
            self.assertTrue(result['files'][0]['malformed'])


if __name__ == '__main__': unittest.main()
