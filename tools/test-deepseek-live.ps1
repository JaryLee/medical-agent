param(
    [string]$TokenFile = 'D:\develop\AIWorkspace\MEDICAL_AGENT\deepseek_token.txt'
)

$ErrorActionPreference = 'Stop'

$workspace = 'D:\develop\AIWorkspace\MEDICAL_AGENT'
$backendDirectory = Join-Path $workspace 'backend'
$frontendDirectory = Join-Path $workspace 'frontend'
$maven = 'D:\develop\environment\apache-maven-3.9.11\bin\mvn.cmd'
$pom = Join-Path $backendDirectory 'pom.xml'
$java = 'D:\develop\jdk21\bin\java.exe'
$testArtifactName = 'medical-agent-deepseek-live'
$jar = Join-Path $backendDirectory "target\$testArtifactName.jar"
$logDirectory = Join-Path $workspace 'artifacts\runtime'
$backendProcess = $null
$testEnvironment = @(
    'DEEPSEEK_TOKEN_FILE',
    'MEDICAL_MODEL_API_KEY_FILE',
    'MEDICAL_MODEL_API_KEY',
    'BOOTSTRAP_ADMIN_USERNAME',
    'BOOTSTRAP_ADMIN_PASSWORD',
    'RUN_DEEPSEEK_LIVE_TEST',
    'DEEPSEEK_E2E',
    'DEEPSEEK_E2E_ADMIN_USERNAME',
    'DEEPSEEK_E2E_ADMIN_INITIAL_PASSWORD',
    'DEEPSEEK_E2E_ADMIN_CHANGED_PASSWORD',
    'DEEPSEEK_E2E_DOCTOR_USERNAME',
    'DEEPSEEK_E2E_DOCTOR_INITIAL_PASSWORD',
    'DEEPSEEK_E2E_DOCTOR_CHANGED_PASSWORD'
)
$originalEnvironment = @{}

foreach ($required in @($maven, $java, $TokenFile)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required DeepSeek test dependency is missing: $required"
    }
}
if ([string]::IsNullOrWhiteSpace([System.IO.File]::ReadAllText($TokenFile))) {
    throw 'DeepSeek token file is empty'
}
foreach ($command in @('npm.cmd', 'npx.cmd')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "Required DeepSeek test command is missing: $command"
    }
}
foreach ($port in @(18080, 4174)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "DeepSeek isolated test port is already in use: $port"
    }
}

New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
foreach ($name in $testEnvironment) {
    $current = Get-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    $originalEnvironment[$name] = if ($current) { $current.Value } else { $null }
}
$env:DEEPSEEK_TOKEN_FILE = $TokenFile
$env:MEDICAL_MODEL_API_KEY_FILE = $TokenFile
$env:MEDICAL_MODEL_API_KEY = ''

try {
    Write-Host '[1/7] Backend deterministic regression tests'
    & $maven -f $pom test -DskipTests=false
    if ($LASTEXITCODE -ne 0) { throw 'Backend regression tests failed' }

    Write-Host '[2/7] Real DeepSeek Java contract test'
    $env:RUN_DEEPSEEK_LIVE_TEST = 'true'
    try {
        & $maven -f $pom -Dtest=DeepSeekApiLiveTest test
        if ($LASTEXITCODE -ne 0) { throw 'Real DeepSeek contract test failed' }
    } finally {
        Remove-Item Env:RUN_DEEPSEEK_LIVE_TEST -ErrorAction SilentlyContinue
    }

    Write-Host '[3/7] Backend package'
    & $maven -f $pom package -DskipTests "-Dmedical.build.final-name=$testArtifactName"
    if ($LASTEXITCODE -ne 0) { throw 'Backend package failed' }

    Write-Host '[4/7] Start isolated DeepSeek backend on 18080'
    $credentialNonce = [Guid]::NewGuid().ToString('N')
    $env:DEEPSEEK_E2E_ADMIN_USERNAME = 'deepseek-platform-admin'
    $env:DEEPSEEK_E2E_ADMIN_INITIAL_PASSWORD = "DsAdmin1a$($credentialNonce.Substring(0, 12))"
    $env:DEEPSEEK_E2E_ADMIN_CHANGED_PASSWORD = "DsAdmin2b$($credentialNonce.Substring(12, 12))"
    $env:DEEPSEEK_E2E_DOCTOR_USERNAME = 'deepseek-doctor'
    $env:DEEPSEEK_E2E_DOCTOR_INITIAL_PASSWORD = "DsDoctor1a$($credentialNonce.Substring(4, 12))"
    $env:DEEPSEEK_E2E_DOCTOR_CHANGED_PASSWORD = "DsDoctor2b$($credentialNonce.Substring(16, 12))"
    $env:BOOTSTRAP_ADMIN_USERNAME = $env:DEEPSEEK_E2E_ADMIN_USERNAME
    $env:BOOTSTRAP_ADMIN_PASSWORD = $env:DEEPSEEK_E2E_ADMIN_INITIAL_PASSWORD
    $arguments = @(
        '-jar'
        $jar
        '--server.port=18080'
        '--spring.profiles.active=memory'
        '--medical.model.mode=deepseek'
        '--medical.model.external-enabled=true'
        '--medical.model.name=deepseek-v4-flash'
        "--medical.model.api-key-file=$TokenFile"
        '--medical.agent.worker-delay=500'
        '--medical.agent.worker-initial-delay=500'
        '--medical.file-scan.mode=basic'
    )
    $backendProcess = Start-Process `
        -FilePath $java `
        -ArgumentList $arguments `
        -WorkingDirectory $backendDirectory `
        -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDirectory 'deepseek-e2e-backend.out.log') `
        -RedirectStandardError (Join-Path $logDirectory 'deepseek-e2e-backend.err.log') `
        -PassThru

    $deadline = (Get-Date).AddSeconds(60)
    $healthy = $false
    do {
        Start-Sleep -Seconds 1
        if ($backendProcess.HasExited) {
            throw "Isolated DeepSeek backend exited with code $($backendProcess.ExitCode)"
        }
        try {
            $response = Invoke-WebRequest `
                -Uri 'http://127.0.0.1:18080/actuator/health' `
                -UseBasicParsing `
                -TimeoutSec 5
            $content = $response.Content
            if ($content -is [byte[]]) {
                $content = [System.Text.Encoding]::UTF8.GetString($content)
            }
            $healthy = ($response.StatusCode -eq 200 -and
                (($content | ConvertFrom-Json).status -eq 'UP'))
        } catch {
            $healthy = $false
        }
    } while (-not $healthy -and (Get-Date) -lt $deadline)
    if (-not $healthy) { throw 'Isolated DeepSeek backend did not become healthy' }

    Write-Host '[5/7] Frontend lint, typecheck, unit test and build'
    & npm.cmd run lint --prefix $frontendDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Frontend lint failed' }
    & npm.cmd run typecheck --prefix $frontendDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Frontend typecheck failed' }
    & npm.cmd run test --prefix $frontendDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Frontend unit tests failed' }
    & npm.cmd run build --prefix $frontendDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }

    Write-Host '[6/7] Playwright real DeepSeek authenticated workflow'
    $env:DEEPSEEK_E2E = 'true'
    Push-Location $frontendDirectory
    try {
        & npx.cmd playwright test --config=playwright.deepseek.config.ts
        if ($LASTEXITCODE -ne 0) { throw 'Playwright real DeepSeek workflow failed' }
    } finally {
        Pop-Location
        Remove-Item Env:DEEPSEEK_E2E -ErrorAction SilentlyContinue
    }

    Write-Host '[7/7] Test summary'
    [pscustomobject]@{
        Status = 'PASS'
        Provider = 'deepseek'
        JavaContract = 'PASS'
        PlaywrightWorkflow = 'STEP01 -> STEP03 -> STEP04 -> STEP05 -> STEP06 -> STEP07'
        MockFallback = 'DISABLED'
        TestData = 'SYNTHETIC_ANONYMOUS'
    } | Format-List
} finally {
    if ($backendProcess -and -not $backendProcess.HasExited) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($backendProcess.Id)"
        if ($process.Name -eq 'java.exe' -and
                $process.CommandLine -like '*--server.port=18080*') {
            Stop-Process -Id $backendProcess.Id
            [void]$backendProcess.WaitForExit(15000)
        }
    }
    foreach ($name in $testEnvironment) {
        $value = $originalEnvironment[$name]
        if ($null -eq $value) {
            Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        } else {
            Set-Item -LiteralPath "Env:$name" -Value $value
        }
    }
}
