param(
    [Parameter(Mandatory=$true)][string]$CodexExecutable,
    [string]$CodexHome = "$env:USERPROFILE\.codex",
    [string]$Workspace = 'workspace\windows-node',
    [string]$PublicUrl = 'http://127.0.0.1:18765',
    [int]$Port = 18765,
    [string]$TaskName = 'Rivo Relay',
    [switch]$Replace
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$CodexExecutable = (Resolve-Path -LiteralPath $CodexExecutable).Path
$CodexHome = (Resolve-Path -LiteralPath $CodexHome).Path
if (-not (Test-Path '.venv\Scripts\pythonw.exe')) {
    py -3 -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw 'Python 3.11 or newer is required' }
}
& .\.venv\Scripts\python.exe -m pip install -e './server'
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed' }
if (-not [IO.Path]::IsPathRooted($Workspace)) { $Workspace = Join-Path $root $Workspace }
$Workspace = [IO.Path]::GetFullPath($Workspace)
$argsList = @('serve', '--workspace', $Workspace, '--host', '127.0.0.1', '--port', "$Port", '--url', $PublicUrl,
    '--codex-runtime', 'stdio', '--codex-executable', $CodexExecutable, '--codex-home', $CodexHome)
# Validate before replacing an existing task. Refuse implicit takeover of another task.
& .\.venv\Scripts\python.exe -c "from pathlib import Path; import sys; from rivo_relay.config import Config; Config(Path(sys.argv[1]), port=int(sys.argv[2]), public_url=sys.argv[3], codex_runtime='stdio', codex_executable=Path(sys.argv[4])).validate()" $Workspace $Port $PublicUrl $CodexExecutable
if ($LASTEXITCODE -ne 0) { throw 'Invalid node configuration' }
$existing = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($existing -and -not $Replace) { throw 'Task exists; use -Replace after confirming the node is idle' }
if ($existing) { Stop-ScheduledTask -TaskName $TaskName }
New-Item -ItemType Directory -Force -Path $Workspace | Out-Null
$config = @{repository=$root; module='rivo_relay'; arguments=$argsList}
$configFile = Join-Path $Workspace 'background.json'
$config | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $configFile -Encoding UTF8
$action = New-ScheduledTaskAction -Execute "$root\.venv\Scripts\pythonw.exe" `
    -Argument "`"$root\scripts\windows-node-background.py`" `"$configFile`"" -WorkingDirectory $root
# S4U runs in a noninteractive session, including when the user is logged out.
# Use the current SID: environment USERDOMAIN can be incorrect over Windows SSH.
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value) -LogonType S4U -RunLevel Limited
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -Hidden -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $TaskName -Action $action -Principal $principal -Trigger $trigger -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Write-Output "Background task started: $TaskName; log: $Workspace\node.log"
