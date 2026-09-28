param(
    [Parameter(Mandatory=$true)][string]$Config,
    [string]$Python = 'python',
    [switch]$InstallAutostart,
    [switch]$AddFirewallRule
)
$ErrorActionPreference = 'Stop'
$configPath = (Resolve-Path -LiteralPath $Config).Path
$receiverPath = Join-Path $PSScriptRoot 'receiver.py'
$pythonPath = (Get-Command $Python -ErrorAction Stop).Source
try {
    $settings = Get-Content -Raw -Encoding UTF8 -LiteralPath $configPath | ConvertFrom-Json -ErrorAction Stop
} catch {
    throw 'Cannot read the private receiver configuration as UTF-8 JSON.'
}
if ($InstallAutostart) {
    $scriptPath = $MyInvocation.MyCommand.Path
    # Register only after this explicit switch; no credentials are placed in task arguments.
    $arguments = '-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}" -Config "{1}" -Python "{2}"' -f $scriptPath, $configPath, $pythonPath
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name)
    $options = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName 'CarrotHud-PC-Receiver' -Action $action -Trigger $trigger -Settings $options -Description 'Receive Carrot diagnostic records over the private Tailscale connection.' -Force | Out-Null
    Write-Output 'Receiver will start at the next Windows login. Sleep or shutdown pauses receipt.'
}
if ($AddFirewallRule) {
    # Requires administrator rights. Restrict both local and remote Tailscale addresses.
    if (-not (Get-NetFirewallRule -Name 'CarrotHud-PC-Receiver' -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -Name 'CarrotHud-PC-Receiver' -DisplayName 'Carrot HUD private record receiver' -Direction Inbound -Action Allow -Protocol TCP -LocalAddress $settings.bind -LocalPort $settings.port -RemoteAddress $settings.allowed_peers -Program $pythonPath -Profile Any | Out-Null
    }
}
if (-not $InstallAutostart -and -not $AddFirewallRule) {
    & $pythonPath -X utf8 $receiverPath --config $configPath
    exit $LASTEXITCODE
}
