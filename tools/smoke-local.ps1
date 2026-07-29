$ErrorActionPreference = 'Stop'

function Invoke-SmokeRequest {
    param(
        [string]$Method,
        [string]$Uri,
        [string]$Body = '',
        [string]$ContentType = 'application/json'
    )
    try {
        $parameters = @{
            Method = $Method
            Uri = $Uri
            UseBasicParsing = $true
            TimeoutSec = 10
        }
        if ($Body) {
            $parameters.Body = $Body
            $parameters.ContentType = $ContentType
        }
        $response = Invoke-WebRequest @parameters
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
        return [System.Text.Encoding]::ASCII.GetString($buffer, 0, $length).Trim([char]0).Trim() -eq 'PONG'
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

$checks = [System.Collections.Generic.List[object]]::new()
function Add-Check {
    param([string]$Name, [bool]$Passed, [string]$Expected, [string]$Actual)
    $checks.Add([pscustomobject]@{
        Check = $Name
        Result = $(if ($Passed) { 'PASS' } else { 'FAIL' })
        Expected = $Expected
        Actual = $Actual
    })
}

$health = Invoke-SmokeRequest -Method GET -Uri 'http://127.0.0.1:8080/actuator/health'
$healthStatus = ''
$healthHasComponents = $true
if ($health.Content) {
    try {
        $healthDocument = $health.Content | ConvertFrom-Json
        $healthStatus = $healthDocument.status
        $healthHasComponents = $null -ne $healthDocument.components
    } catch {}
}
Add-Check 'Backend aggregate health' `
    ($health.StatusCode -eq 200 -and $healthStatus -eq 'UP') `
    'HTTP 200 / UP' `
    "HTTP $($health.StatusCode) / $healthStatus"
Add-Check 'Health response redaction' `
    (-not $healthHasComponents) `
    'No component details' `
    $(if ($healthHasComponents) { 'Component details exposed' } else { 'Redacted' })

foreach ($probe in @('liveness', 'readiness')) {
    $result = Invoke-SmokeRequest -Method GET -Uri "http://127.0.0.1:8080/actuator/health/$probe"
    Add-Check "Backend $probe probe" ($result.StatusCode -eq 200) 'HTTP 200' "HTTP $($result.StatusCode)"
}

$apiDocs = Invoke-SmokeRequest -Method GET -Uri 'http://127.0.0.1:8080/v3/api-docs'
Add-Check 'Local API documentation' ($apiDocs.StatusCode -eq 200) 'HTTP 200' "HTTP $($apiDocs.StatusCode)"

$protectedApi = Invoke-SmokeRequest `
    -Method POST `
    -Uri 'http://127.0.0.1:8080/api/prototype/ideas/analyze' `
    -Body '{"idea":"anonymous smoke test"}'
Add-Check 'Anonymous API protection' ($protectedApi.StatusCode -eq 403) 'HTTP 403' "HTTP $($protectedApi.StatusCode)"

$minio = Invoke-SmokeRequest -Method GET -Uri 'http://127.0.0.1:9000/minio/health/live'
Add-Check 'MinIO live endpoint' ($minio.StatusCode -eq 200) 'HTTP 200' "HTTP $($minio.StatusCode)"

$clamAvHealthy = Test-ClamAvPing
Add-Check 'ClamAV PING' $clamAvHealthy 'PONG' $(if ($clamAvHealthy) { 'PONG' } else { 'No valid response' })

$checks | Format-Table -AutoSize
if ($checks | Where-Object { $_.Result -ne 'PASS' }) {
    exit 1
}
