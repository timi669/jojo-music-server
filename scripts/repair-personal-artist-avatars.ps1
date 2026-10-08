param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot '..\src\main\resources\application-personal.yml'),
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$mysqlHome = 'D:\MySQL\MySQL Server 8.0\bin'
$mysql = Join-Path $mysqlHome 'mysql.exe'
if (-not (Test-Path -LiteralPath $mysql)) {
    throw 'MySQL command-line client was not found in the configured installation directory.'
}

$config = Get-Content -LiteralPath $ConfigPath -Raw
$databaseUrl = [regex]::Match($config, 'url:\s*["'']?jdbc:mysql://([^:/]+):(\d+)/([^?"'']+)')
$username = [regex]::Match($config, '(?m)^\s{4}username:\s*["'']?([^"''\r\n]+)["'']?\s*$')
$password = [regex]::Match($config, '(?m)^\s{4}password:\s*["'']([^"''\r\n]+)["'']\s*$')
$minioEndpoint = [regex]::Match($config, '(?m)^\s{2}endpoint:\s*["'']?([^"''\r\n]+)["'']?\s*$')
$bucket = [regex]::Match($config, '(?m)^\s{2}bucket:\s*([^\s#]+)')
if (-not ($databaseUrl.Success -and $username.Success -and $password.Success -and $minioEndpoint.Success -and $bucket.Success)) {
    throw 'Required personal MySQL or MinIO settings could not be read from the profile.'
}

$targetDatabase = $databaseUrl.Groups[3].Value
if ($targetDatabase -ne 'vibe_music_personal') {
    throw "Refusing to repair unexpected database '$targetDatabase'. Expected vibe_music_personal."
}
$referenceDatabase = 'vibe_music'
if ($referenceDatabase -notmatch '^[A-Za-z0-9_]+$') {
    throw 'Reference database name is invalid.'
}
$mediaRoot = $minioEndpoint.Groups[1].Value.TrimEnd('/') + '/' + $bucket.Groups[1].Value
$mappedAvatar = "CASE WHEN source_artist.id IS NULL OR source_artist.avatar IS NULL OR source_artist.avatar = '' THEN NULL ELSE CONCAT('$mediaRoot/artists/', SUBSTRING_INDEX(source_artist.avatar, '/', -1)) END"

$optionsPath = Join-Path $env:TEMP ('.mysql-artist-repair-' + [guid]::NewGuid().ToString('N') + '.cnf')
$escapedPassword = $password.Groups[1].Value.Replace('\', '\\').Replace('"', '\"')
$optionsContent = "[client]`r`nuser=$($username.Groups[1].Value.Trim())`r`npassword=`"$escapedPassword`"`r`nhost=$($databaseUrl.Groups[1].Value)`r`nport=$($databaseUrl.Groups[2].Value)`r`nprotocol=tcp`r`n"
Set-Content -LiteralPath $optionsPath -Value $optionsContent -Encoding ASCII

function Invoke-MySql([string[]]$Arguments) {
    $output = & $mysql "--defaults-extra-file=$optionsPath" --default-character-set=utf8mb4 @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed with exit code $LASTEXITCODE. $($output -join ' ')"
    }
    return $output
}

try {
    $auditSql = @"
SELECT
    COUNT(*) AS personal_artists,
    SUM(source_artist.id IS NOT NULL AND source_artist.avatar IS NOT NULL AND source_artist.avatar <> '') AS restorable_avatars,
    SUM(source_artist.id IS NULL OR source_artist.avatar IS NULL OR source_artist.avatar = '') AS placeholder_avatars,
    SUM(NOT (personal_artist.avatar <=> $mappedAvatar)) AS rows_to_update
FROM $targetDatabase.tb_artist AS personal_artist
LEFT JOIN $referenceDatabase.tb_artist AS source_artist
    ON BINARY personal_artist.name = BINARY source_artist.name;
"@
    $audit = Invoke-MySql @('--batch', '--skip-column-names', '--execute', $auditSql)
    Write-Output "Target database: $targetDatabase"
    Write-Output "Reference database: $referenceDatabase"
    Write-Output "Avatar audit (artists, restorable, placeholder, changes): $($audit -join ' ')"

    if (-not $Apply) {
        Write-Output 'Dry run only. Re-run with -Apply to back up and update avatar URLs.'
        return
    }

    $backupSql = "SELECT CONCAT('UPDATE tb_artist SET avatar = ', IF(avatar IS NULL, 'NULL', QUOTE(avatar)), ' WHERE id = ', id, ';') FROM $targetDatabase.tb_artist ORDER BY id;"
    $backupLines = @(Invoke-MySql @('--batch', '--skip-column-names', '--execute', $backupSql))
    if ($backupLines.Count -eq 0) {
        throw 'Artist avatar backup is empty; refusing to update the database.'
    }

    $backupDirectory = Join-Path $PSScriptRoot '..\target\backups'
    New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
    $backupPath = Join-Path $backupDirectory ('personal-artist-avatars-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.sql')
    [System.IO.File]::WriteAllLines($backupPath, [string[]]$backupLines, [System.Text.UTF8Encoding]::new($false))

    $applySql = @"
START TRANSACTION;
UPDATE $targetDatabase.tb_artist AS personal_artist
LEFT JOIN $referenceDatabase.tb_artist AS source_artist
    ON BINARY personal_artist.name = BINARY source_artist.name
SET personal_artist.avatar = $mappedAvatar
WHERE NOT (personal_artist.avatar <=> $mappedAvatar);
COMMIT;
"@
    Invoke-MySql @('--execute', $applySql) | Out-Null

    $verifySql = @"
SELECT 'mapped_avatar_mismatches', COUNT(*)
FROM $targetDatabase.tb_artist AS personal_artist
INNER JOIN $referenceDatabase.tb_artist AS source_artist
    ON BINARY personal_artist.name = BINARY source_artist.name
WHERE NOT (personal_artist.avatar <=> $mappedAvatar)
UNION ALL
SELECT 'unmapped_artists_with_avatar', COUNT(*)
FROM $targetDatabase.tb_artist AS personal_artist
LEFT JOIN $referenceDatabase.tb_artist AS source_artist
    ON BINARY personal_artist.name = BINARY source_artist.name
WHERE (source_artist.id IS NULL OR source_artist.avatar IS NULL OR source_artist.avatar = '')
  AND personal_artist.avatar IS NOT NULL;
"@
    $verification = Invoke-MySql @('--batch', '--skip-column-names', '--execute', $verifySql)
    Write-Output "Backup: $([System.IO.Path]::GetFullPath($backupPath))"
    Write-Output 'Post-update verification:'
    $verification
} finally {
    Remove-Item -LiteralPath $optionsPath -Force -ErrorAction SilentlyContinue
}