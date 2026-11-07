#!/usr/bin/env python3
"""Reauthorize the independent local Codex daemon, without replaying tasks.

Run with the Relay virtualenv and PYTHONPATH=server. Uses the CLI's normal
credential store; never copies tokens. Restart is refused while a loaded thread
is active or its state cannot be verified. Desktop is never stopped.
"""
import argparse
import subprocess

from rivo_relay.codex.connection import CodexError, NativeConnection


def require_idle(connection):
    page = connection.request('thread/loaded/list', {})
    seen = set()
    while True:
        if not isinstance(page.get('data'), list):
            raise RuntimeError('无法核实后台会话列表，不会重启。')
        for thread_id in page['data']:
            thread = connection.request('thread/read', {
                'threadId': thread_id, 'includeTurns': True})['thread']
            if thread.get('status', {}).get('type') != 'idle' or any(
                turn.get('status') in ('inProgress', 'notStarted')
                for turn in thread.get('turns', [])):
                raise RuntimeError('后台仍有活动会话；请等待任务结束后重试，不会中断任务。')
        cursor = page.get('nextCursor')
        if not cursor:
            return
        if cursor in seen:
            raise RuntimeError('会话分页异常，不会重启。')
        seen.add(cursor)
        page = connection.request('thread/loaded/list', {'cursor': cursor})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--codex', default='codex')
    args = parser.parse_args()
    subprocess.run([args.codex, 'login', '--device-auth'], check=True)
    connection = NativeConnection()
    try:
        connection.start()
        try:
            connection.request('account/read', {'refreshToken': True})
            connection.request('account/rateLimits/read', {})
            print('授权成功，后台认证已通过，无需重启。')
            return
        except CodexError as error:
            if error.code != 'native_auth_required':
                raise
            # A successful login can leave the long-running daemon with old auth.
            require_idle(connection)
        subprocess.run([args.codex, 'app-server', 'daemon', 'restart'], check=True)
    finally:
        connection.close()
    connection = NativeConnection()
    try:
        connection.start()
        connection.request('account/rateLimits/read', {})
        print('授权成功，独立后台已重启并通过认证检查。未重新提交任何任务。')
    finally:
        connection.close()


if __name__ == '__main__':
    main()
