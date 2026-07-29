param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory,
    [Parameter(Mandatory = $true)]
    [string]$TargetDatabase,
    [Parameter(Mandatory = $true)]
    [string]$TargetBucket
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-backup-common.ps1')

Assert-LocalBackupTools
if ($TargetDatabase -notmatch '^medical_agent_restore_[a-z0-9_]{4,48}$') {
    throw 'Target database must be a new name beginning with medical_agent_restore_.'
}
if ($TargetBucket -notmatch '^medical-agent-restore-[a-z0-9-]{4,48}$') {
    throw 'Target bucket must be a new name beginning with medical-agent-restore-.'
}

& powershell.exe -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $PSScriptRoot 'verify-local-backup.ps1') `
    -BackupDirectory $BackupDirectory | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Backup verification failed; restore was not started'
}

$backupDirectory = (Resolve-Path -LiteralPath $BackupDirectory).Path
$metadata = Get-Content -LiteralPath (Join-Path $backupDirectory 'backup-metadata.json') -Raw |
    ConvertFrom-Json
$psql = Join-Path $script:PostgresBin 'psql.exe'
$databaseExists = & $psql -h 127.0.0.1 -U postgres -d postgres -Atc `
    "select 1 from pg_database where datname='$TargetDatabase';"
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to check target database'
}
if ($databaseExists -eq '1') {
    throw "Refusing to overwrite existing database: $TargetDatabase"
}

Enable-LocalMinioEnvironment
try {
    $bucketCheck = Invoke-LocalMinio -Arguments @('stat', "$script:MinioAlias/$TargetBucket") -AllowFailure
    if ($bucketCheck.ExitCode -eq 0) {
        throw "Refusing to overwrite existing bucket: $TargetBucket"
    }

    & (Join-Path $script:PostgresBin 'createdb.exe') `
        -h 127.0.0.1 -U postgres -O medical_agent $TargetDatabase
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to create restore target database'
    }
    & $psql -h 127.0.0.1 -U postgres -d $TargetDatabase `
        -v ON_ERROR_STOP=1 -c 'create extension if not exists vector;' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to install pgvector in restore target'
    }

    $restoreList = Join-Path $script:MedicalAgentWorkspace `
        "artifacts\runtime\restore-list-$TargetDatabase.txt"
    if (Test-Path -LiteralPath $restoreList) {
        throw "Restore list already exists: $restoreList"
    }
    try {
        $restoreEntries = @(& (Join-Path $script:PostgresBin 'pg_restore.exe') `
            --list (Join-Path $backupDirectory 'database.dump') |
            Where-Object {
                $_ -notmatch '^\d+;.* EXTENSION - vector\s*$' -and
                $_ -notmatch '^\d+;.* COMMENT - EXTENSION vector\s*$'
            })
        if ($LASTEXITCODE -ne 0) {
            throw 'Unable to prepare PostgreSQL restore list'
        }
        [System.IO.File]::WriteAllLines(
            $restoreList,
            [string[]]$restoreEntries,
            [System.Text.UTF8Encoding]::new($false)
        )

        & (Join-Path $script:PostgresBin 'pg_restore.exe') `
            -h 127.0.0.1 `
            -U medical_agent `
            -d $TargetDatabase `
            --no-owner `
            --no-privileges `
            --exit-on-error `
            --use-list=$restoreList `
            (Join-Path $backupDirectory 'database.dump')
        if ($LASTEXITCODE -ne 0) {
            throw 'PostgreSQL restore failed'
        }
    } finally {
        if (Test-Path -LiteralPath $restoreList) {
            [System.IO.File]::Delete($restoreList)
        }
    }

    Invoke-LocalMinio -Arguments @('mb', "$script:MinioAlias/$TargetBucket") | Out-Null
    $objectsDirectory = Join-Path $backupDirectory 'objects'
    $localStats = Get-LocalFileStats -Directory $objectsDirectory
    if ($localStats.Count -gt 0) {
        Invoke-LocalMinio -Arguments @(
            'mirror', '--overwrite', $objectsDirectory, "$script:MinioAlias/$TargetBucket"
        ) | Out-Null
    }

    $restoredCounts = Get-DatabaseTableCounts -Database $TargetDatabase
    $expectedProperties = @($metadata.database.tableCounts.psobject.Properties)
    if ($restoredCounts.Count -ne $expectedProperties.Count) {
        throw 'Restored database table count does not match backup metadata'
    }
    foreach ($property in $expectedProperties) {
        if (-not $restoredCounts.Contains($property.Name) -or
                $restoredCounts[$property.Name] -ne [long]$property.Value) {
            throw "Restored row count mismatch for table $($property.Name)"
        }
    }

    $remoteStats = Get-MinIoObjectStats -Bucket $TargetBucket
    if ($remoteStats.Count -ne [int]$metadata.objectStorage.objectCount -or
            $remoteStats.Bytes -ne [long]$metadata.objectStorage.totalBytes) {
        throw 'Restored object count or byte total does not match backup metadata'
    }

    $verificationDirectory = Join-Path $script:MedicalAgentWorkspace `
        "artifacts\runtime\restore-verification-$TargetBucket"
    if (Test-Path -LiteralPath $verificationDirectory) {
        throw "Verification directory already exists: $verificationDirectory"
    }
    New-Item -ItemType Directory -Path $verificationDirectory | Out-Null
    try {
        if ($remoteStats.Count -gt 0) {
            Invoke-LocalMinio -Arguments @(
                'mirror', '--overwrite', "$script:MinioAlias/$TargetBucket", $verificationDirectory
            ) | Out-Null
        }
        $sourceFiles = @(Get-ChildItem -LiteralPath $objectsDirectory -File -Recurse)
        foreach ($sourceFile in $sourceFiles) {
            $relative = $sourceFile.FullName.Substring($objectsDirectory.Length + 1)
            $restoredFile = Assert-PathInsideDirectory `
                -Path (Join-Path $verificationDirectory $relative) `
                -Parent $verificationDirectory
            if (-not (Test-Path -LiteralPath $restoredFile -PathType Leaf)) {
                throw "Restored object is missing: $relative"
            }
            $sourceHash = (Get-FileHash -LiteralPath $sourceFile.FullName -Algorithm SHA256).Hash
            $restoredHash = (Get-FileHash -LiteralPath $restoredFile -Algorithm SHA256).Hash
            if ($sourceHash -ne $restoredHash) {
                throw "Restored object checksum mismatch: $relative"
            }
        }
    } finally {
        Remove-VerifiedRuntimeDirectory -Path $verificationDirectory
    }

    [pscustomobject]@{
        Status = 'RestoredAndVerified'
        BackupId = $metadata.backupId
        TargetDatabase = $TargetDatabase
        TargetBucket = $TargetBucket
        DatabaseTables = $restoredCounts.Count
        Objects = $remoteStats.Count
    }
} finally {
    Disable-LocalMinioEnvironment
}
