param(
    [switch]$NoFail
)

$ErrorActionPreference = 'Stop'

function Get-HttpResult {
    param([string]$Uri)
    try {
        $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 5
        $content = $response.Content
        if ($content -is [byte[]]) {
            $content = [System.Text.Encoding]::UTF8.GetString($content)
        }
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Content = $content }
    } catch {
        $response = $_.Exception.Response
        if ($null -ne $response) {
            return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Content = '' }
        }
        return [pscustomobject]@{ StatusCode = 0; Content = '' }
    }
}

function Get-ListenerPid {
    param([int]$Port)
    $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) { return $listener.OwningProcess }
    return $null
}

function Test-ClamAvPing {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $client.ConnectAsync('127.0.0.1', 3310)
        if (-not $connect.Wait(3000)) { return $false }
        $stream = $client.GetStream()
        $stream.ReadTimeout = 3000
        $command = [System.Text.Encoding]::ASCII.GetBytes("zPING`0")
        $stream.Write($command, 0, $command.Length)
        $stream.Flush()
        $buffer = New-Object byte[] 32
        $length = $stream.Read($buffer, 0, $buffer.Length)
        $reply = [System.Text.Encoding]::ASCII.GetString($buffer, 0, $length).Trim([char]0).Trim()
        return $reply -eq 'PONG'
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

$backend = Get-HttpResult 'http://127.0.0.1:8080/actuator/health'
$backendHealthy = $false
if ($backend.StatusCode -eq 200) {
    try {
        $backendHealthy = (($backend.Content | ConvertFrom-Json).status -eq 'UP')
    } catch {
        $backendHealthy = $false
    }
}

$frontend = Get-HttpResult 'http://127.0.0.1:5173/'
$minio = Get-HttpResult 'http://127.0.0.1:9000/minio/health/live'
$clamAvHealthy = Test-ClamAvPing

$pgIsReady = 'D:\develop\postgresql18\bin\pg_isready.exe'
$postgresHealthy = $false
if (Test-Path -LiteralPath $pgIsReady) {
    & $pgIsReady -h 127.0.0.1 -p 5432 -d medical_agent -U medical_agent 2>$null | Out-Null
    $postgresHealthy = ($LASTEXITCODE -eq 0)
}

$results = @(
    [pscustomobject]@{
        Service = 'Frontend'
        Status = $(if ($frontend.StatusCode -eq 200) { 'UP' } else { 'DOWN' })
        Endpoint = 'http://127.0.0.1:5173'
        Pid = Get-ListenerPid 5173
    }
    [pscustomobject]@{
        Service = 'Backend'
        Status = $(if ($backendHealthy) { 'UP' } else { 'DOWN' })
        Endpoint = 'http://127.0.0.1:8080/actuator/health'
        Pid = Get-ListenerPid 8080
    }
    [pscustomobject]@{
        Service = 'PostgreSQL'
        Status = $(if ($postgresHealthy) { 'UP' } else { 'DOWN' })
        Endpoint = '127.0.0.1:5432'
        Pid = Get-ListenerPid 5432
    }
    [pscustomobject]@{
        Service = 'MinIO'
        Status = $(if ($minio.StatusCode -eq 200) { 'UP' } else { 'DOWN' })
        Endpoint = 'http://127.0.0.1:9000/minio/health/live'
        Pid = Get-ListenerPid 9000
    }
    [pscustomobject]@{
        Service = 'ClamAV'
        Status = $(if ($clamAvHealthy) { 'UP' } else { 'DOWN' })
        Endpoint = '127.0.0.1:3310'
        Pid = Get-ListenerPid 3310
    }
)

$results | Format-Table -AutoSize

if (-not $NoFail -and ($results | Where-Object { $_.Status -ne 'UP' })) {
    exit 1
}
