$ErrorActionPreference = 'Stop'

$workspace = 'D:\develop\AIWorkspace\MEDICAL_AGENT'
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

$logDirectory = Join-Path $workspace 'artifacts\runtime'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null

$arguments = @(
    '-jar'
    (Join-Path $workspace 'backend\target\medical-agent-0.1.0-SNAPSHOT.jar')
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

[pscustomobject]@{
    Pid = $process.Id
    Started = $process.StartTime
}
