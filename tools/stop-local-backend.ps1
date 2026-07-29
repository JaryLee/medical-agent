$ErrorActionPreference = 'Stop'

$listener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $listener) {
    [pscustomobject]@{
        Status = 'AlreadyStopped'
        Port = 8080
    }
    return
}

$process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
$expectedJar = 'medical-agent-0.1.0-SNAPSHOT.jar'
if ($process.Name -ne 'java.exe' -or $process.CommandLine -notlike "*$expectedJar*") {
    throw "Refusing to stop unexpected process on port 8080: PID $($listener.OwningProcess), $($process.Name)"
}

Stop-Process -Id $listener.OwningProcess
$deadline = (Get-Date).AddSeconds(15)
do {
    Start-Sleep -Milliseconds 250
    $stillRunning = Get-Process -Id $listener.OwningProcess -ErrorAction SilentlyContinue
} while ($stillRunning -and (Get-Date) -lt $deadline)

if ($stillRunning) {
    throw "Backend process did not stop within 15 seconds: PID $($listener.OwningProcess)"
}

[pscustomobject]@{
    Status = 'Stopped'
    Pid = $listener.OwningProcess
    Port = 8080
}
