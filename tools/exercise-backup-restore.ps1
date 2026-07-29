$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-backup-common.ps1')

Assert-LocalBackupTools
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$sourceBucket = "medical-agent-backup-source-$suffix"
$targetBucket = "medical-agent-restore-$suffix"
$targetDatabase = "medical_agent_restore_$suffix"
$runtimeDirectory = Join-Path $script:MedicalAgentWorkspace "artifacts\runtime\backup-exercise-$suffix"
$sampleFile = Join-Path $runtimeDirectory 'synthetic-backup-check.txt'
$backupDirectory = $null

if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Stop the backend before running the coordinated backup/restore exercise.'
}

New-Item -ItemType Directory -Path $runtimeDirectory | Out-Null
[System.IO.File]::WriteAllText(
    $sampleFile,
    "SYNTHETIC_ANONYMOUS BACKUP_RESTORE_CHECK $suffix",
    [System.Text.UTF8Encoding]::new($false)
)

Enable-LocalMinioEnvironment
try {
    Invoke-LocalMinio -Arguments @('mb', "$script:MinioAlias/$sourceBucket") | Out-Null
    Invoke-LocalMinio -Arguments @(
        'cp', $sampleFile, "$script:MinioAlias/$sourceBucket/verification/synthetic-backup-check.txt"
    ) | Out-Null
} finally {
    Disable-LocalMinioEnvironment
}

try {
    $backupResult = & (Join-Path $PSScriptRoot 'backup-local.ps1') `
        -SourceBucket $sourceBucket `
        -Label 'exercise'
    $backupDirectory = $backupResult.Directory

    & (Join-Path $PSScriptRoot 'restore-local-backup.ps1') `
        -BackupDirectory $backupDirectory `
        -TargetDatabase $targetDatabase `
        -TargetBucket $targetBucket | Out-Null

    [pscustomobject]@{
        Status = 'ExercisePassed'
        BackupDirectory = $backupDirectory
        RestoredDatabase = $targetDatabase
        RestoredBucket = $targetBucket
        SyntheticObjects = 1
    }
} finally {
    if ($targetDatabase -match '^medical_agent_restore_[a-z0-9_]{4,48}$') {
        $previousErrorPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & (Join-Path $script:PostgresBin 'dropdb.exe') `
                -h 127.0.0.1 -U postgres --if-exists --force $targetDatabase 2>$null
        } finally {
            $ErrorActionPreference = $previousErrorPreference
        }
    }
    Enable-LocalMinioEnvironment
    try {
        foreach ($bucket in @($sourceBucket, $targetBucket)) {
            if ($bucket -match '^(medical-agent-backup-source|medical-agent-restore)-[a-z0-9-]{4,48}$') {
                Invoke-LocalMinio -Arguments @('rb', '--force', "$script:MinioAlias/$bucket") `
                    -AllowFailure | Out-Null
            }
        }
    } finally {
        Disable-LocalMinioEnvironment
    }
    Remove-VerifiedRuntimeDirectory -Path $runtimeDirectory
}
