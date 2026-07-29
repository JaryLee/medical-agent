$ErrorActionPreference = 'Stop'

$clamAvDirectory = 'D:\develop\clamav'
$clamdExecutable = Join-Path $clamAvDirectory 'clamd.exe'
$clamdConfig = Join-Path $clamAvDirectory 'clamd.conf'
$logDirectory = 'D:\develop\AIWorkspace\MEDICAL_AGENT\artifacts\runtime'

if (-not (Test-Path -LiteralPath $clamdExecutable)) {
    throw "ClamAV executable not found: $clamdExecutable"
}
if (-not (Test-Path -LiteralPath $clamdConfig)) {
    throw "ClamAV configuration not found: $clamdConfig"
}

$listener = Get-NetTCPConnection -LocalPort 3310 -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalAddress -in @('127.0.0.1', '::1') } |
    Select-Object -First 1
if ($listener) {
    $listenerProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
    if ($listenerProcess.ExecutablePath -ne $clamdExecutable) {
        throw "Port 3310 is owned by an unexpected process: $($listenerProcess.ExecutablePath)"
    }
    [pscustomobject]@{
        Pid = $listener.OwningProcess
        Status = 'AlreadyListening'
        Endpoint = "$($listener.LocalAddress):3310"
    }
    return
}

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$process = Start-Process `
    -FilePath $clamdExecutable `
    -ArgumentList @("--config-file=$clamdConfig") `
    -WorkingDirectory $clamAvDirectory `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDirectory 'clamd.out.log') `
    -RedirectStandardError (Join-Path $logDirectory 'clamd.err.log') `
    -PassThru

[pscustomobject]@{
    Pid = $process.Id
    Status = 'Starting'
    Endpoint = '127.0.0.1:3310'
}
