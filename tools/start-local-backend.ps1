$ErrorActionPreference = 'Stop'

$workspace = 'D:\develop\AIWorkspace\MEDICAL_AGENT'
$jarPath = Join-Path $workspace 'backend\target\medical-agent-0.1.0-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Backend JAR not found. Build it first: $jarPath"
}

$existingListener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($existingListener) {
    $existingProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($existingListener.OwningProcess)"
    if ($existingProcess.Name -ne 'java.exe' -or
            $existingProcess.CommandLine -notlike '*medical-agent-0.1.0-SNAPSHOT.jar*') {
        throw "Port 8080 is owned by an unexpected process: PID $($existingListener.OwningProcess), $($existingProcess.Name)"
    }
    [pscustomobject]@{
        Pid = $existingListener.OwningProcess
        Status = 'AlreadyListening'
        Endpoint = 'http://127.0.0.1:8080'
    }
    return
}

$clamAvStarter = Join-Path $workspace 'tools\start-local-clamav.ps1'
& $clamAvStarter | Out-Null
$clamAvDeadline = (Get-Date).AddSeconds(60)
do {
    $clamAvListener = Get-NetTCPConnection -LocalPort 3310 -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalAddress -in @('127.0.0.1', '::1') } |
        Select-Object -First 1
    if ($clamAvListener) {
        break
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $clamAvDeadline)
if (-not $clamAvListener) {
    throw 'ClamAV did not become ready on a loopback address at port 3310'
}

$credential = Import-Clixml 'D:\develop\minio\.medical-agent-credential.xml'
$env:MINIO_ACCESS_KEY = $credential.UserName
$env:MINIO_SECRET_KEY = [System.Net.NetworkCredential]::new(
    [string]::Empty,
    $credential.Password
).Password
$env:MINIO_ENABLED = 'true'
$env:MINIO_ENDPOINT = 'http://127.0.0.1:9000'
$env:MINIO_BUCKET = 'medical-agent-files'
$env:SPRING_PROFILES_ACTIVE = 'postgres'
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/medical_agent'
$env:DATABASE_USERNAME = 'medical_agent'
$env:DATABASE_PASSWORD = ''
$env:FILE_SCAN_MODE = 'clamav'
$env:CLAMAV_HOST = '127.0.0.1'
$env:CLAMAV_PORT = '3310'
$env:CLAMAV_TIMEOUT = '10s'
$modelTokenFile = Join-Path $workspace 'deepseek_token.txt'
if (-not (Test-Path -LiteralPath $modelTokenFile -PathType Leaf) -or
        (Get-Item -LiteralPath $modelTokenFile).Length -eq 0) {
    throw "DeepSeek token file is missing or empty: $modelTokenFile"
}
$env:MEDICAL_MODEL_MODE = 'deepseek'
$env:MEDICAL_MODEL_EXTERNAL_ENABLED = 'true'
$env:MEDICAL_MODEL_NAME = 'deepseek-v4-flash'
$env:MEDICAL_MODEL_API_KEY = ''
$env:MEDICAL_MODEL_API_KEY_FILE = $modelTokenFile

$logDirectory = Join-Path $workspace 'artifacts\runtime'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

$arguments = @(
    '-jar'
    $jarPath
    '--medical.security.secure-cookie=false'
)
$process = Start-Process `
    -FilePath 'D:\develop\jdk21\bin\java.exe' `
    -ArgumentList $arguments `
    -WorkingDirectory (Join-Path $workspace 'backend') `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $logDirectory 'backend.out.log') `
    -RedirectStandardError (Join-Path $logDirectory 'backend.err.log') `
    -PassThru

$healthDeadline = (Get-Date).AddSeconds(60)
$healthy = $false
do {
    Start-Sleep -Seconds 1
    if ($process.HasExited) {
        throw "Backend exited during startup with code $($process.ExitCode). Check artifacts\runtime\backend.err.log"
    }
    try {
        $health = Invoke-WebRequest `
            -Uri 'http://127.0.0.1:8080/actuator/health' `
            -UseBasicParsing `
            -TimeoutSec 5
        $healthContent = $health.Content
        if ($healthContent -is [byte[]]) {
            $healthContent = [System.Text.Encoding]::UTF8.GetString($healthContent)
        }
        $healthy = ($health.StatusCode -eq 200 -and
                (($healthContent | ConvertFrom-Json).status -eq 'UP'))
    } catch {
        $healthy = $false
    }
} while (-not $healthy -and (Get-Date) -lt $healthDeadline)

if (-not $healthy) {
    throw "Backend did not become healthy within 60 seconds. Check artifacts\runtime\backend.out.log"
}

[pscustomobject]@{
    Pid = $process.Id
    Started = $process.StartTime
    Status = 'Healthy'
    Endpoint = 'http://127.0.0.1:8080/actuator/health'
}
