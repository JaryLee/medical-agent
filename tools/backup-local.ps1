param(
    [string]$DatabaseName = 'medical_agent',
    [string]$SourceBucket = 'medical-agent-files',
    [string]$BackupRoot = 'D:\develop\AIWorkspace\MEDICAL_AGENT\artifacts\backups',
    [string]$Label = 'local',
    [switch]$AllowOnline
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-backup-common.ps1')

Assert-LocalBackupTools
if ($DatabaseName -notmatch '^[a-zA-Z0-9_]+$') {
    throw "Unsafe database name: $DatabaseName"
}
if ($SourceBucket -notmatch '^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$') {
    throw "Unsafe bucket name: $SourceBucket"
}
if ($Label -notmatch '^[a-zA-Z0-9_-]{1,32}$') {
    throw "Unsafe backup label: $Label"
}

$backendListener = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($backendListener -and -not $AllowOnline) {
    throw 'Backend is running. Stop it first for a coordinated database/object backup, or explicitly use -AllowOnline.'
}

New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null
$backupId = '{0}-{1}-{2}' -f `
    (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'), `
    $Label, `
    ([guid]::NewGuid().ToString('N').Substring(0, 8))
$backupDirectory = Join-Path $BackupRoot $backupId
if (Test-Path -LiteralPath $backupDirectory) {
    throw "Backup directory already exists: $backupDirectory"
}

New-Item -ItemType Directory -Path $backupDirectory | Out-Null
$objectsDirectory = Join-Path $backupDirectory 'objects'
New-Item -ItemType Directory -Path $objectsDirectory | Out-Null
$databaseDump = Join-Path $backupDirectory 'database.dump'

try {
    $tableCounts = Get-DatabaseTableCounts -Database $DatabaseName
    & (Join-Path $script:PostgresBin 'pg_dump.exe') `
        -h 127.0.0.1 `
        -U medical_agent `
        -d $DatabaseName `
        --format=custom `
        --compress=6 `
        --no-owner `
        --no-privileges `
        --file=$databaseDump
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump failed with exit code $LASTEXITCODE"
    }

    Enable-LocalMinioEnvironment
    try {
        $sourceStats = Get-MinIoObjectStats -Bucket $SourceBucket
        Invoke-LocalMinio -Arguments @(
            'mirror', '--overwrite', "$script:MinioAlias/$SourceBucket", $objectsDirectory
        ) | Out-Null
    } finally {
        Disable-LocalMinioEnvironment
    }

    $localStats = Get-LocalFileStats -Directory $objectsDirectory
    if ($sourceStats.Count -ne $localStats.Count -or $sourceStats.Bytes -ne $localStats.Bytes) {
        throw 'MinIO mirror count or byte total does not match the source bucket'
    }

    $metadata = [ordered]@{
        schemaVersion = 'medical-agent-backup/v1'
        backupId = $backupId
        createdAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        coordinatedOffline = (-not [bool]$backendListener)
        database = [ordered]@{
            logicalName = $DatabaseName
            format = 'PostgreSQL custom archive'
            tableCounts = $tableCounts
        }
        objectStorage = [ordered]@{
            logicalBucket = $SourceBucket
            objectCount = $sourceStats.Count
            totalBytes = $sourceStats.Bytes
        }
    }
    $metadataPath = Join-Path $backupDirectory 'backup-metadata.json'
    $metadata | ConvertTo-Json -Depth 8 |
        Set-Content -LiteralPath $metadataPath -Encoding UTF8

    $manifestEntries = @()
    foreach ($file in Get-ChildItem -LiteralPath $backupDirectory -File -Recurse |
            Sort-Object FullName) {
        $relativePath = $file.FullName.Substring($backupDirectory.Length + 1).Replace('\', '/')
        $manifestEntries += [ordered]@{
            path = $relativePath
            bytes = $file.Length
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    $manifest = [ordered]@{
        schemaVersion = 'medical-agent-backup-manifest/v1'
        backupId = $backupId
        files = $manifestEntries
    }
    $manifest | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath (Join-Path $backupDirectory 'manifest.sha256.json') -Encoding UTF8

    [pscustomobject]@{
        Status = 'Created'
        BackupId = $backupId
        Directory = $backupDirectory
        DatabaseTables = $tableCounts.Count
        Objects = $sourceStats.Count
        Bytes = (Get-ChildItem -LiteralPath $backupDirectory -File -Recurse |
            Measure-Object -Property Length -Sum).Sum
    }
} catch {
    throw "Backup failed; incomplete directory retained for inspection: $backupDirectory. $($_.Exception.Message)"
}
