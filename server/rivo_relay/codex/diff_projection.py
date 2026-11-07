"""Bounded, revision-bound review projection adapted from QiGuang CodexReviewManager.

Git still runs through the selected native daemon. This module only parses its
returned patch; it neither reads the Relay host nor applies any file changes.
"""
from __future__ import annotations

import hashlib
import re

from .connection import CodexError

HUNK = re.compile(r'^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(?: .*)?$')


def _path(value, *, strip_prefix=False):
    value = value.split('\t', 1)[0]
    if value.startswith('"') and value.endswith('"'):
        # Git's quotePath uses C escapes, including octal UTF-8 bytes.
        raw = bytearray()
        i = 1
        while i < len(value) - 1:
            if value[i] != '\\':
                raw.extend(value[i].encode('utf-8'))
                i += 1
                continue
            i += 1
            octal = re.match(r'[0-7]{1,3}', value[i:-1])
            if octal:
                number = int(octal.group(), 8)
                if number > 255: return value
                raw.append(number); i += len(octal.group())
            else:
                raw.extend({'n': b'\n', 't': b'\t', 'r': b'\r', '\\': b'\\', '"': b'"'}.get(value[i], value[i].encode()))
                i += 1
        value = raw.decode('utf-8', errors='replace')
    return value[2:] if strip_prefix and value.startswith(('a/', 'b/')) else value


def structured_diff(diff):
    """Return file/hunk/line records without inventing lines past the byte cap."""
    files = []
    current = hunk = None
    old_line = new_line = 0
    for line in diff.get('patch', '').splitlines():
        if line.startswith('diff --git '):
            # ---/+++ and rename metadata below resolve quoted/spaced paths.
            path = _path(line.rsplit(' b/', 1)[-1])
            if line.startswith('diff --git "'):
                quoted = re.findall(r'"(?:\\.|[^"\\])*"', line)
                if len(quoted) == 2:
                    path = _path(quoted[1], strip_prefix=True)
            current = {'id': str(len(files)), 'path': path, 'oldPath': path, 'newPath': path,
                       'status': 'modified', 'binary': False, 'added': 0, 'removed': 0,
                       'truncated': False, 'malformed': False, 'hunks': []}
            files.append(current)
            hunk = None
        elif current is not None:
            match = HUNK.match(line)
            if match:
                old_line, new_line = int(match[1]), int(match[3])
                hunk = {'header': line, 'oldStart': old_line, 'newStart': new_line,
                        'oldCount': int(match[2] or 1), 'newCount': int(match[4] or 1), 'lines': []}
                current['hunks'].append(hunk)
            elif hunk is not None and line.startswith((' ', '+', '-', '\\')):
                kind = {'+': 'addition', '-': 'deletion', ' ': 'context', '\\': 'notice'}[line[0]]
                hunk['lines'].append({'kind': kind, 'text': line[1:] if kind != 'notice' else line,
                    'oldLine': old_line if kind in ('context', 'deletion') else None,
                    'newLine': new_line if kind in ('context', 'addition') else None})
                if kind in ('context', 'deletion'): old_line += 1
                if kind in ('context', 'addition'): new_line += 1
                if kind == 'addition': current['added'] += 1
                if kind == 'deletion': current['removed'] += 1
            elif line.startswith('@@'):
                current['malformed'] = True
            elif line.startswith('--- '):
                current['oldPath'] = _path(line[4:], strip_prefix=True)
                if current['oldPath'] == '/dev/null': current['status'] = 'added'
            elif line.startswith('+++ '):
                current['newPath'] = _path(line[4:], strip_prefix=True)
                if current['newPath'] == '/dev/null':
                    current['status'] = 'deleted'; current['path'] = current['oldPath']
                else: current['path'] = current['newPath']
            elif line.startswith('rename from '):
                current['status'] = 'renamed'; current['oldPath'] = _path(line[12:])
            elif line.startswith('rename to '):
                current['newPath'] = _path(line[10:]); current['path'] = current['newPath']
            elif line.startswith('new file mode '): current['status'] = 'added'
            elif line.startswith('deleted file mode '): current['status'] = 'deleted'
            elif line.startswith(('Binary files ', 'GIT binary patch')): current['binary'] = True
    if files and diff.get('truncated'):
        files[-1]['truncated'] = True
    for file in files:
        for chunk in file['hunks']:
            old_count = sum(line['kind'] in ('context', 'deletion') for line in chunk['lines'])
            new_count = sum(line['kind'] in ('context', 'addition') for line in chunk['lines'])
            chunk['complete'] = old_count == chunk['oldCount'] and new_count == chunk['newCount']
            if not chunk['complete']:
                file['malformed'] = True
        file['id'] = hashlib.sha256((file['oldPath'] + '\0' + file['newPath']).encode()).hexdigest()[:24]
    return {**diff, 'files': files, 'schemaVersion': 1, 'structured': True}


def diff_page(diff, *, revision=None, file_id=None, offset=0, limit=400):
    """Every page is checked against the current workspace snapshot."""
    if revision is not None and revision != diff['revision']:
        raise CodexError('文件差异已更新，请重新读取', code='diff_revision_changed', http_status=409)
    if (not isinstance(offset, int) or isinstance(offset, bool) or offset < 0 or
            not isinstance(limit, int) or isinstance(limit, bool) or not 1 <= limit <= 1000):
        raise CodexError('差异分页参数无效', code='invalid_diff_page', http_status=400)
    result = structured_diff(diff)
    files = result['files']
    if file_id is not None and not any(file['id'] == file_id for file in files):
        raise CodexError('文件差异已更新，请重新读取', code='diff_file_changed', http_status=409)
    lines = []
    for file in files:
        if file_id is not None and file['id'] != file_id: continue
        common = {'fileId': file['id'], 'path': file['path']}
        lines.append({**common, 'kind': 'file', 'text': file['path'], 'oldLine': None, 'newLine': None})
        if file['malformed']:
            lines.append({**common, 'kind': 'notice', 'text': '节点返回的差异不完整，行号和统计仅供参考', 'oldLine': None, 'newLine': None})
        if not file['hunks']:
            message = '二进制文件只显示元数据' if file['binary'] else '重命名或类型变化，没有文本差异'
            if file['truncated']: message = '内容达到读取上限，文本差异不完整'
            lines.append({**common, 'kind': 'notice', 'text': message, 'oldLine': None, 'newLine': None})
        for hunk in file['hunks']:
            lines.append({**common, 'kind': 'hunk', 'text': hunk['header'], 'oldLine': None, 'newLine': None})
            lines.extend({**common, **line} for line in hunk['lines'])
    if offset > len(lines):
        raise CodexError('差异分页位置已过期', code='diff_page_changed', http_status=409)
    result['files'] = [{k: v for k, v in file.items() if k != 'hunks'} for file in files]
    result.update(lines=lines[offset:offset + limit], offset=offset,
                  nextOffset=offset + limit if offset + limit < len(lines) else None,
                  totalLines=len(lines))
    # Legacy clients still consume patch. Page requests don't repeat the payload.
    if revision is not None: result.pop('patch', None)
    return result
