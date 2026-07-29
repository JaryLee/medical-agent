param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-backup-common.ps1')

Assert-LocalBackupTools
$backupDirectory = (Resolve-Path -LiteralPath $BackupDirectory).Path
$manifestPath = Join-Path $backupDirectory 'manifest.sha256.json'
$metadataPath = Join-Path $backupDirectory 'backup-metadata.json'
$databaseDump = Join-Path $backupDirectory 'database.dump'

foreach ($required in @($manifestPath, $metadataPath, $databaseDump)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required backup file is missing: $required"
    }
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 'medical-agent-backup-manifest/v1' -or
        $metadata.schemaVersion -ne 'medical-agent-backup/v1' -or
        $manifest.backupId -ne $metadata.backupId) {
    throw 'Backup metadata or manifest schema is invalid'
}

foreach ($entry in $manifest.files) {
    $candidate = Join-Path $backupDirectory ($entry.path.Replace('/', '\'))
    $fullPath = Assert-PathInsideDirectory -Path $candidate -Parent $backupDirectory
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "Manifest file is missing: $($entry.path)"
    }
    $file = Get-Item -LiteralPath $fullPath
    if ($file.Length -ne [long]$entry.bytes) {
        throw "Backup size mismatch: $($entry.path)"
    }
    $actualHash = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $entry.sha256) {
        throw "Backup checksum mismatch: $($entry.path)"
    }
}

& (Join-Path $script:PostgresBin 'pg_restore.exe') --list $databaseDump | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'PostgreSQL archive validation failed'
}

$objectsDirectory = Join-Path $backupDirectory 'objects'
$localStats = Get-LocalFileStats -Directory $objectsDirectory
if ($localStats.Count -ne [int]$metadata.objectStorage.objectCount -or
        $localStats.Bytes -ne [long]$metadata.objectStorage.totalBytes) {
    throw 'Object backup count or byte total does not match metadata'
}

[pscustomobject]@{
    Status = 'Valid'
    BackupId = $metadata.backupId
    DatabaseTables = @($metadata.database.tableCounts.psobject.Properties).Count
    Objects = $localStats.Count
    Bytes = (Get-ChildItem -LiteralPath $backupDirectory -File -Recurse |
        Measure-Object -Property Length -Sum).Sum
}
