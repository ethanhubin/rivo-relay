#!/usr/bin/env python3
"""One-time install of a stable auto-update agent for an already configured node.

Stop the old Relay and connector services first, after their tasks finish. This
installer refuses to take over a running node. It never touches Codex services.
"""
import argparse
import json
import os
from pathlib import Path
import plistlib
import shutil
import socket
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--local-port', type=int, default=18765)
    parser.add_argument('--codex-runtime', choices=['auto', 'desktop', 'app_server', 'stdio'])
    parser.add_argument('--codex-executable', type=Path)
    parser.add_argument('--codex-home', type=Path)
    parser.add_argument('--native-socket', type=Path)
    phases = parser.add_mutually_exclusive_group()
    phases.add_argument('--prepare-only', action='store_true', help='Install bootstrap without stopping or registering services')
    phases.add_argument('--activate', action='store_true', help='Register a prepared bootstrap after old services stop')
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    path = workspace / 'gateway-client.json'
    config = json.loads(path.read_text())
    if not config.get('key') or config.get('mode') == 'direct':
        parser.error('Initialize this node with its VPS credential before installing the agent')
    if not args.prepare_only:
        with socket.socket() as probe:
            probe.settimeout(2)
            if probe.connect_ex(('127.0.0.1', args.local_port)) == 0:
                parser.error('Local port is in use. Wait for tasks to finish and stop the old Relay and connector services first')
    bootstrap = workspace / 'auto-update/bootstrap'
    venv = bootstrap / 'venv'
    python = venv / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
    if args.activate:
        if not (bootstrap / 'prepared.json').is_file():
            parser.error('No prepared bootstrap; run --prepare-only first')
    else:
        if bootstrap.exists():
            parser.error('Bootstrap already exists; inspect it instead of overwriting a managed service')
        bootstrap.mkdir(parents=True, mode=0o700)
        subprocess.run([sys.executable, '-m', 'venv', str(venv)], check=True)
        subprocess.run([str(python), '-m', 'pip', 'install', '--no-cache-dir', str(ROOT / 'server') + '[gateway]'], check=True)
        subprocess.run([str(python), '-c', 'import rivo_relay.gateway.updater, aiohttp'], check=True)
    # The stable bootstrap is an installed wheel, never an editable checkout.
    arguments = ['managed-node', '--workspace', str(workspace), '--local-port', str(args.local_port), '--no-pair']
    for option in ('codex_runtime', 'codex_executable', 'codex_home', 'native_socket'):
        value = getattr(args, option)
        if value is not None: arguments += ['--' + option.replace('_', '-'), str(value)]
    if args.activate:
        if json.loads((bootstrap / 'prepared.json').read_text()) != arguments:
            parser.error('Activation arguments differ from prepared runtime; use the same workspace and runtime flags')
    else:
        (bootstrap / 'prepared.json').write_text(json.dumps(arguments))
    if args.prepare_only:
        print('Bootstrap prepared; no services changed. Stop old services when idle, then rerun with --activate.')
        return
    import hashlib
    identifier = 'rivo-managed-' + hashlib.sha256(str(workspace).encode()).hexdigest()[:12]
    if os.name == 'nt':
        shutil.copyfile(ROOT / 'scripts/windows-node-background.py', bootstrap / 'background.py')
        (bootstrap / 'background.json').write_text(json.dumps({'repository': str(bootstrap), 'module': 'rivo_relay.gateway', 'arguments': arguments}))
        # Supply values as environment variables, not interpolated PowerShell code.
        script = r'''
$ErrorActionPreference = 'Stop'
if (Get-ScheduledTask -TaskName $env:RIVO_AGENT_NAME -ErrorAction SilentlyContinue) { throw 'Managed task already exists' }
$action = New-ScheduledTaskAction -Execute "$env:RIVO_AGENT_ROOT\venv\Scripts\pythonw.exe" -Argument ('"' + $env:RIVO_AGENT_ROOT + '\background.py" "' + $env:RIVO_AGENT_ROOT + '\background.json"') -WorkingDirectory $env:RIVO_AGENT_ROOT
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) -LogonType S4U -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -Hidden -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $env:RIVO_AGENT_NAME -Action $action -Principal $principal -Settings $settings -Trigger (New-ScheduledTaskTrigger -AtStartup) | Out-Null
Start-ScheduledTask -TaskName $env:RIVO_AGENT_NAME
'''
        subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script], check=True,
                       env=dict(os.environ, RIVO_AGENT_NAME=identifier, RIVO_AGENT_ROOT=str(bootstrap)))
    elif sys.platform == 'darwin':
        label = 'local.' + identifier
        target = Path.home() / 'Library/LaunchAgents' / (label + '.plist')
        if target.exists(): raise ValueError('Managed LaunchAgent already exists')
        target.parent.mkdir(parents=True, exist_ok=True)
        value = {'Label': label, 'ProgramArguments': [str(python), '-m', 'rivo_relay.gateway', *arguments],
                 'WorkingDirectory': str(bootstrap), 'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 30,
                 'EnvironmentVariables': {'PATH': os.environ.get('PATH', '/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin')}}
        target.write_bytes(plistlib.dumps(value))
        subprocess.run(['launchctl', 'bootstrap', f'gui/{os.getuid()}', str(target)], check=True)
    else:
        def quoted(value):
            return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%') + '"'
        target = Path.home() / '.config/systemd/user' / (identifier + '.service')
        if target.exists(): raise ValueError('Managed systemd unit already exists')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('[Unit]\nDescription=Rivo managed node\nAfter=network-online.target\n[Service]\nExecStart=' +
                          ' '.join(quoted(x) for x in [python, '-m', 'rivo_relay.gateway', *arguments]) +
                          '\nRestart=on-failure\nRestartSec=30\nTimeoutStopSec=360\n[Install]\nWantedBy=default.target\n')
        subprocess.run(['systemctl', '--user', 'daemon-reload'], check=True)
        subprocess.run(['systemctl', '--user', 'enable', '--now', identifier + '.service'], check=True)
    print('Agent service registered:', identifier)
    print('Verify readiness and subsequent updates in', workspace / 'auto-update/agent.log')


if __name__ == '__main__': main()
