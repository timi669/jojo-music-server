param(
    [string]$CoverPlanPath = (Join-Path $PSScriptRoot '..\target\personal-song-covers.json'),
    [string]$ManifestPath = (Join-Path $PSScriptRoot '..\target\personal-library-manifest.json'),
    [string]$ConfigPath = (Join-Path $PSScriptRoot '..\src\main\resources\application-personal.yml'),
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$mysqlHome = 'D:\MySQL\MySQL Server 8.0\bin'
$mysql = Join-Path $mysqlHome 'mysql.exe'
if (-not (Test-Path -LiteralPath $mysql)) {
    throw 'MySQL command-line client was not found in the configured installation directory.'
}

$coverPlanJson = [System.IO.File]::ReadAllText($CoverPlanPath, [System.Text.Encoding]::UTF8)
$manifestJson = [System.IO.File]::ReadAllText($ManifestPath, [System.Text.Encoding]::UTF8)
$coverPlan = $coverPlanJson | ConvertFrom-Json
$manifest = $manifestJson | ConvertFrom-Json
$config = Get-Content -LiteralPath $ConfigPath -Raw
$databaseUrl = [regex]::Match($config, 'url:\s*["'']?jdbc:mysql://([^:/]+):(\d+)/([^?"'']+)')
$username = [regex]::Match($config, '(?m)^\s{4}username:\s*["'']?([^"''\r\n]+)["'']?\s*$')
$password = [regex]::Match($config, '(?m)^\s{4}password:\s*["'']([^"''\r\n]+)["'']\s*$')
$minioEndpoint = [regex]::Match($config, '(?m)^\s{2}endpoint:\s*["'']?([^"''\r\n]+)["'']?\s*$')
$bucket = [regex]::Match($config, '(?m)^\s{2}bucket:\s*([^\s#]+)')
if (-not ($databaseUrl.Success -and $username.Success -and $password.Success -and $minioEndpoint.Success -and $bucket.Success)) {
    throw 'Required personal MySQL or MinIO settings could not be read from application-personal.yml.'
}

$targetDatabase = $databaseUrl.Groups[3].Value
if ($targetDatabase -ne 'vibe_music_personal') {
    throw "Refusing to repair unexpected database '$targetDatabase'. Expected vibe_music_personal."
}
$mediaRoot = $minioEndpoint.Groups[1].Value.TrimEnd('/') + '/' + $bucket.Groups[1].Value

function ConvertTo-PublicUrl([string]$Key) {
    $encodedKey = (($Key -split '/') | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/'
    return "$mediaRoot/$encodedKey"
}

function ConvertTo-SqlLiteral([AllowNull()][string]$Value) {
    if ($null -eq $Value) { return 'NULL' }
    return "'" + $Value.Replace('\', '\\').Replace("'", "''") + "'"
}

$coversBySourceFile = @{}
foreach ($cover in $coverPlan.covers) {
    if ($coversBySourceFile.ContainsKey($cover.sourceFile)) {
        throw "Duplicate embedded cover mapping for $($cover.sourceFile)."
    }
    if (-not (Test-Path -LiteralPath $cover.file -PathType Leaf)) {
        throw "Extracted cover file is missing: $($cover.file)"
    }
    if ($cover.key -notmatch '^songCovers/embedded/') {
        throw "Unexpected cover key for $($cover.sourceFile): $($cover.key)"
    }
    $coversBySourceFile[$cover.sourceFile] = $cover
}

$missingBySourceFile = @{}
foreach ($item in $coverPlan.missing) {
    $missingBySourceFile[$item.file] = $item
}

$mappingRows = [System.Collections.Generic.List[string]]::new()
$unmappedSongs = [System.Collections.Generic.List[string]]::new()
foreach ($song in $manifest.Songs) {
    $hasCover = $coversBySourceFile.ContainsKey($song.File)
    $hasMissingRecord = $missingBySourceFile.ContainsKey($song.File)
    if ($hasCover -eq $hasMissingRecord) {
        $unmappedSongs.Add($song.File)
        continue
    }
    $audioUrl = ConvertTo-PublicUrl ("songs/" + $song.File)
    $coverUrl = if ($hasCover) { ConvertTo-PublicUrl $coversBySourceFile[$song.File].key } else { $null }
    $mappingRows.Add("SELECT $(ConvertTo-SqlLiteral $audioUrl) AS audio_url, $(ConvertTo-SqlLiteral $coverUrl) AS cover_url")
}
if ($unmappedSongs.Count -gt 0) {
    throw "Cover extraction plan does not account for every manifest song. Unmapped: $($unmappedSongs.Count)."
}
if ($mappingRows.Count -ne $manifest.Songs.Count) {
    throw 'Mapping row count does not match the source manifest song count.'
}

$targetDirectory = Join-Path $PSScriptRoot '..\target'
New-Item -ItemType Directory -Path $targetDirectory -Force | Out-Null
$mappingSqlPath = Join-Path $targetDirectory 'personal-song-cover-map.sql'
$mappingSql = $mappingRows -join "`r`nUNION ALL`r`n"
[System.IO.File]::WriteAllText($mappingSqlPath, $mappingSql, [System.Text.UTF8Encoding]::new($false))
$mappingTable = "($mappingSql) AS cover_map"

$optionsPath = Join-Path $env:TEMP ('.mysql-song-cover-repair-' + [guid]::NewGuid().ToString('N') + '.cnf')
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

function Invoke-MySqlFile([string]$FilePath) {
    $sourcePath = [System.IO.Path]::GetFullPath($FilePath).Replace('\', '/')
    Invoke-MySql @('--execute', "source $sourcePath") | Out-Null
}

try {
    $auditPath = Join-Path $targetDirectory 'personal-song-cover-audit.sql'
    $auditSql = @"
SELECT COUNT(*), SUM(match_count = 1), SUM(match_count = 0), SUM(match_count > 1)
FROM (
    SELECT cover_map.audio_url, COUNT(song.id) AS match_count
    FROM $mappingTable
    LEFT JOIN $targetDatabase.tb_song AS song
        ON BINARY song.audio_url = BINARY cover_map.audio_url
    GROUP BY cover_map.audio_url
) AS match_audit;
"@
    [System.IO.File]::WriteAllText($auditPath, $auditSql, [System.Text.UTF8Encoding]::new($false))
    $audit = @(Invoke-MySql @('--batch', '--skip-column-names', '--execute', "source $(([System.IO.Path]::GetFullPath($auditPath)).Replace('\', '/'))"))
    $counts = ($audit | Select-Object -Last 1) -split "`t"
    if ($counts.Count -ne 4) {
        throw "Unexpected cover audit response: $($audit -join ' ')"
    }
    $totalMappings = [int]$counts[0]
    $uniqueMatches = [int]$counts[1]
    $missingMatches = [int]$counts[2]
    $duplicateMatches = [int]$counts[3]
    Write-Output "Database: $targetDatabase"
    Write-Output "Source songs: $($manifest.Songs.Count)"
    Write-Output "Embedded covers: $($coverPlan.extractedCovers)"
    Write-Output "Songs without embedded covers (will use client fallback): $($coverPlan.missingCovers)"
    Write-Output "Database URL matches: $uniqueMatches unique, $missingMatches missing, $duplicateMatches duplicate"

    if ($totalMappings -ne $manifest.Songs.Count -or $uniqueMatches -ne $totalMappings -or $missingMatches -ne 0 -or $duplicateMatches -ne 0) {
        throw 'Every source song must match exactly one database row; no song cover rows were changed.'
    }
    if (-not $Apply) {
        Write-Output 'Dry run only. Re-run with -Apply after uploading the extracted images to MinIO.'
        return
    }

    $backupSqlPath = Join-Path $targetDirectory 'personal-song-cover-backup.sql'
    $backupQuery = "SELECT CONCAT('UPDATE ``$targetDatabase``.tb_song SET cover_url = ', IF(cover_url IS NULL, 'NULL', QUOTE(cover_url)), ' WHERE id = ', id, ';') FROM ``$targetDatabase``.tb_song ORDER BY id;"
    $backupLines = @(Invoke-MySql @('--batch', '--skip-column-names', '--execute', $backupQuery))
    if ($backupLines.Count -eq 0) {
        throw 'Song cover backup is empty; refusing to update the database.'
    }
    $backupDirectory = Join-Path $targetDirectory 'backups'
    New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
    $backupPath = Join-Path $backupDirectory ('personal-song-covers-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.sql')
    [System.IO.File]::WriteAllLines($backupPath, [string[]]$backupLines, [System.Text.UTF8Encoding]::new($false))

    $updateSqlPath = Join-Path $targetDirectory 'personal-song-cover-update.sql'
    $updateSql = @"
START TRANSACTION;
UPDATE $targetDatabase.tb_song AS song
INNER JOIN $mappingTable
    ON BINARY song.audio_url = BINARY cover_map.audio_url
SET song.cover_url = cover_map.cover_url
WHERE NOT (song.cover_url <=> cover_map.cover_url);
COMMIT;
"@
    [System.IO.File]::WriteAllText($updateSqlPath, $updateSql, [System.Text.UTF8Encoding]::new($false))
    Invoke-MySqlFile $updateSqlPath

    $verifyPath = Join-Path $targetDirectory 'personal-song-cover-verify.sql'
    $verifySql = @"
SELECT COUNT(*)
FROM $targetDatabase.tb_song AS song
INNER JOIN $mappingTable
    ON BINARY song.audio_url = BINARY cover_map.audio_url
WHERE NOT (song.cover_url <=> cover_map.cover_url);
"@
    [System.IO.File]::WriteAllText($verifyPath, $verifySql, [System.Text.UTF8Encoding]::new($false))
    $mismatches = Invoke-MySql @('--batch', '--skip-column-names', '--execute', "source $(([System.IO.Path]::GetFullPath($verifyPath)).Replace('\', '/'))")
    Write-Output "Backup: $([System.IO.Path]::GetFullPath($backupPath))"
    Write-Output "Post-update cover mismatches: $($mismatches | Select-Object -Last 1)"
} finally {
    Remove-Item -LiteralPath $optionsPath -Force -ErrorAction SilentlyContinue
}