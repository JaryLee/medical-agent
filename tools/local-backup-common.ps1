$script:MedicalAgentWorkspace = 'D:\develop\AIWorkspace\MEDICAL_AGENT'
$script:PostgresBin = 'D:\develop\postgresql18\bin'
$script:MinioClient = 'D:\develop\minio\mc.exe'
$script:MinioCredential = 'D:\develop\minio\.medical-agent-credential.xml'
$script:MinioAlias = 'medical'

function Assert-LocalBackupTools {
    $required = @(
        (Join-Path $script:PostgresBin 'pg_dump.exe'),
        (Join-Path $script:PostgresBin 'pg_restore.exe'),
        (Join-Path $script:PostgresBin 'psql.exe'),
        (Join-Path $script:PostgresBin 'createdb.exe'),
        (Join-Path $script:PostgresBin 'dropdb.exe'),
        $script:MinioClient,
        $script:MinioCredential
    )
    foreach ($path in $required) {
        if (-not (Test-Path -LiteralPath $path)) {
            throw "Required local backup dependency not found: $path"
        }
    }
}

function Enable-LocalMinioEnvironment {
    $credential = Import-Clixml -LiteralPath $script:MinioCredential
    $secret = [System.Net.NetworkCredential]::new(
        [string]::Empty,
        $credential.Password
    ).Password
    $encodedUser = [uri]::EscapeDataString($credential.UserName)
    $encodedSecret = [uri]::EscapeDataString($secret)
    $env:MC_HOST_medical = "http://${encodedUser}:${encodedSecret}@127.0.0.1:9000"
    $secret = $null
}

function Disable-LocalMinioEnvironment {
    Remove-Item Env:MC_HOST_medical -ErrorAction SilentlyContinue
}

function Invoke-LocalMinio {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$AllowFailure
    )

    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $script:MinioClient @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorPreference
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        $safeMessage = ($output | Select-Object -First 3) -join ' '
        throw "MinIO client operation failed with exit code ${exitCode}: $safeMessage"
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = $output
    }
}

function Get-MinIoObjectStats {
    param([Parameter(Mandatory = $true)][string]$Bucket)

    $result = Invoke-LocalMinio -Arguments @(
        '--json', 'ls', '--recursive', "$script:MinioAlias/$Bucket"
    )
    $count = 0
    $bytes = 0L
    foreach ($line in $result.Output) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        $record = $line | ConvertFrom-Json
        if ($record.status -eq 'success' -and $record.type -eq 'file') {
            $count++
            $bytes += [long]$record.size
        }
    }
    return [pscustomobject]@{ Count = $count; Bytes = $bytes }
}

function Get-LocalFileStats {
    param([Parameter(Mandatory = $true)][string]$Directory)

    $files = @(Get-ChildItem -LiteralPath $Directory -File -Recurse)
    $bytes = ($files | Measure-Object -Property Length -Sum).Sum
    if ($null -eq $bytes) {
        $bytes = 0L
    }
    return [pscustomobject]@{ Count = $files.Count; Bytes = [long]$bytes }
}

function Get-DatabaseTableCounts {
    param(
        [Parameter(Mandatory = $true)][string]$Database,
        [string]$User = 'medical_agent'
    )

    if ($Database -notmatch '^[a-zA-Z0-9_]+$') {
        throw "Unsafe database name: $Database"
    }
    $psql = Join-Path $script:PostgresBin 'psql.exe'
    $tables = @(& $psql -h 127.0.0.1 -U $User -d $Database -Atc `
        "select tablename from pg_tables where schemaname='public' order by tablename;")
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to enumerate public tables in database $Database"
    }

    $counts = [ordered]@{}
    foreach ($table in $tables) {
        if ($table -notmatch '^[a-zA-Z0-9_]+$') {
            throw "Unsafe table name returned by PostgreSQL: $table"
        }
        $count = & $psql -h 127.0.0.1 -U $User -d $Database -Atc `
            "select count(*) from public.`"$table`";"
        if ($LASTEXITCODE -ne 0) {
            throw "Unable to count table $table"
        }
        $counts[$table] = [long]$count
    }
    return $counts
}

function Assert-PathInsideDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Parent
    )

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $fullParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    if (-not $fullPath.StartsWith($fullParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escapes the permitted directory: $Path"
    }
    return $fullPath
}

function Remove-VerifiedRuntimeDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)

    $runtimeRoot = Join-Path $script:MedicalAgentWorkspace 'artifacts\runtime'
    $fullPath = Assert-PathInsideDirectory -Path $Path -Parent $runtimeRoot
    if (Test-Path -LiteralPath $fullPath) {
        [System.IO.Directory]::Delete($fullPath, $true)
    }
}
