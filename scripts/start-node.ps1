# Windows always starts a hidden, noninteractive background task.
# Pass the installer's named parameters, including -CodexExecutable.
& "$PSScriptRoot\install-windows-node.ps1" @args
