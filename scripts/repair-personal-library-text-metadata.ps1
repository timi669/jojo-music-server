[CmdletBinding()]
param(
    [switch]$Apply,
    [string]$ConfigPath
)

$ErrorActionPreference = 'Stop'
$scriptDirectory = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($scriptDirectory)) { $scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path }
if ([string]::IsNullOrWhiteSpace($ConfigPath)) { $ConfigPath = Join-Path $scriptDirectory '..\src\main\resources\application-personal.yml' }
$mysql = 'D:\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not (Test-Path -LiteralPath $mysql)) {
    throw 'MySQL command-line client was not found in the configured installation directory.'
}

$config = [System.IO.File]::ReadAllText($ConfigPath, [System.Text.Encoding]::UTF8)
$databaseUrl = [regex]::Match($config, 'url:\s*["'']?jdbc:mysql://([^:/]+):(\d+)/([^?"'']+)')
$username = [regex]::Match($config, '(?m)^\s{4}username:\s*["'']?([^"''\r\n]+)["'']?\s*$')
$password = [regex]::Match($config, '(?m)^\s{4}password:\s*["'']([^"''\r\n]+)["'']\s*$')
if (-not ($databaseUrl.Success -and $username.Success -and $password.Success)) {
    throw 'Could not read the personal database settings.'
}

$databaseName = $databaseUrl.Groups[3].Value
if ($databaseName -ne 'vibe_music_personal' -or $databaseName -notmatch '^[A-Za-z0-9_]+$') {
    throw "Refusing to update unexpected database '$databaseName'."
}

$optionsPath = Join-Path $env:TEMP ('.mysql-text-metadata-' + [guid]::NewGuid().ToString('N') + '.cnf')
$escapedPassword = $password.Groups[1].Value.Replace('\', '\\').Replace('"', '\"')
$optionsContent = "[client]`r`nuser=$($username.Groups[1].Value.Trim())`r`npassword=`"$escapedPassword`"`r`nhost=$($databaseUrl.Groups[1].Value)`r`nport=$($databaseUrl.Groups[2].Value)`r`nprotocol=tcp`r`n"
[System.IO.File]::WriteAllText($optionsPath, $optionsContent, [System.Text.Encoding]::ASCII)

function Invoke-MySql([string]$Query) {
    $output = & $mysql "--defaults-extra-file=$optionsPath" "--database=$databaseName" --default-character-set=utf8mb4 --batch --raw --skip-column-names --execute $Query 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL operation failed with exit code $LASTEXITCODE. $($output -join ' ')"
    }
    return @($output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

try {
    $counts = Invoke-MySql "SELECT JSON_ARRAY('corrupt_artist', COUNT(*)) FROM tb_artist WHERE BINARY name = BINARY CONVERT(X'E98F88EE8186E785A1E5A79DE5B1BEE5A29C' USING utf8mb4) UNION ALL SELECT JSON_ARRAY('corrupt_album', COUNT(*)) FROM tb_song WHERE RIGHT(album, CHAR_LENGTH(CONCAT(' ', CONVERT(X'E98D97E69B9FE6B4B8' USING utf8mb4)))) = CONCAT(' ', CONVERT(X'E98D97E69B9FE6B4B8' USING utf8mb4)) UNION ALL SELECT JSON_ARRAY('corrupt_playlist', COUNT(*)) FROM tb_playlist WHERE title LIKE CONCAT('%', CONVERT(X'E98F88EE8186E785A1E5A79DE5B1BEE5A29C' USING utf8mb4), '%') OR introduction LIKE CONCAT('%', CONVERT(X'E98F88EE8186E785A1E5A79DE5B1BEE5A29C' USING utf8mb4), '%') UNION ALL SELECT JSON_ARRAY('migu_artist', COUNT(*)) FROM tb_artist WHERE BINARY name = BINARY 'Justin Bieber[music.migu.cn]' UNION ALL SELECT JSON_ARRAY('migu_song', COUNT(*)) FROM tb_song s JOIN tb_artist a ON a.id = s.artist_id WHERE BINARY a.name = BINARY 'Justin Bieber[music.migu.cn]' AND RIGHT(s.name, 15) = '[music.migu.cn]'"
    $countMap = @{}
    foreach ($line in $counts) {
        $row = ConvertFrom-Json -InputObject $line
        $countMap[[string]$row[0]] = [int]$row[1]
    }
    Write-Output "Database: $databaseName"
    Write-Output "Corrupt artist placeholder rows: $($countMap['corrupt_artist'])"
    Write-Output "Corrupt fallback album rows: $($countMap['corrupt_album'])"
    Write-Output "Corrupt playlist rows: $($countMap['corrupt_playlist'])"
    Write-Output "Site-tagged Justin Bieber artist rows: $($countMap['migu_artist'])"
    Write-Output "Site-tagged Justin Bieber songs: $($countMap['migu_song'])"
    if (-not $Apply) {
        Write-Output 'Preview only. Pass -Apply to back up and repair these exact mojibake placeholders.'
        return
    }

    $backupRows = [System.Collections.Generic.List[string]]::new()
    foreach ($line in (Invoke-MySql "SELECT JSON_ARRAY('artist', id, HEX(name), gender, HEX(area), HEX(introduction)) FROM tb_artist WHERE BINARY name = BINARY CONVERT(X'E98F88EE8186E785A1E5A79DE5B1BEE5A29C' USING utf8mb4)")) { $backupRows.Add($line) }
    foreach ($line in (Invoke-MySql "SELECT JSON_ARRAY('song', s.id, HEX(s.name), HEX(s.album), s.artist_id) FROM tb_song s WHERE RIGHT(s.album, CHAR_LENGTH(CONCAT(' ', CONVERT(X'E98D97E69B9FE6B4B8' USING utf8mb4)))) = CONCAT(' ', CONVERT(X'E98D97E69B9FE6B4B8' USING utf8mb4))")) { $backupRows.Add($line) }
    foreach ($line in (Invoke-MySql "SELECT JSON_ARRAY('playlist', id, HEX(title), HEX(introduction)) FROM tb_playlist WHERE title LIKE CONCAT('%', CONVERT(X'E98F88EE8186E785A1E5A79DE5B1BEE5A29C' USING utf8mb4), '%') OR introduction LIKE CONCAT('%', CONVERT(X'E98F88EE8186E785A1E5A79DE5B1BEE5A29C' USING utf8mb4), '%')")) { $backupRows.Add($line) }
    foreach ($line in (Invoke-MySql "SELECT JSON_ARRAY('migu-artist', id, HEX(name)) FROM tb_artist WHERE BINARY name = BINARY 'Justin Bieber[music.migu.cn]'")) { $backupRows.Add($line) }
    foreach ($line in (Invoke-MySql "SELECT JSON_ARRAY('migu-song', s.id, HEX(s.name), HEX(s.album), s.artist_id) FROM tb_song s JOIN tb_artist a ON a.id = s.artist_id WHERE BINARY a.name = BINARY 'Justin Bieber[music.migu.cn]' AND RIGHT(s.name, 15) = '[music.migu.cn]'")) { $backupRows.Add($line) }
    foreach ($line in (Invoke-MySql "SELECT JSON_ARRAY('migu-link', sa.song_id, sa.artist_id, sa.credit_order, sa.is_primary, HEX(sa.credit_source)) FROM tb_song_artist sa JOIN tb_artist a ON a.id = sa.artist_id WHERE BINARY a.name = BINARY 'Justin Bieber[music.migu.cn]'")) { $backupRows.Add($line) }

    $backupDirectory = Join-Path $scriptDirectory '..\target\backups'
    New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
    $timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $backupPath = Join-Path $backupDirectory "personal-library-text-$timestamp.json"
    [System.IO.File]::WriteAllLines($backupPath, $backupRows, [System.Text.UTF8Encoding]::new($true))

    $sql = @'
SET NAMES utf8mb4;
START TRANSACTION;
SET @bad_artist = CONVERT(X'E98F88EE8186E785A1E5A79DE5B1BEE5A29C' USING utf8mb4);
SET @unknown_artist = CONVERT(X'E69CAAE79FA5E6AD8CE6898B' USING utf8mb4);
SET @unknown_album = CONVERT(X'E69CAAE79FA5E4B893E8BE91' USING utf8mb4);
SET @old_single = CONVERT(X'E98D97E69B9FE6B4B8' USING utf8mb4);
SET @single = CONVERT(X'E58D95E69BB2' USING utf8mb4);
SET @migu_artist = 'Justin Bieber[music.migu.cn]';
SET @justin_bieber = 'Justin Bieber';
SET @migu_suffix = '[music.migu.cn]';
UPDATE tb_artist SET name = @unknown_artist WHERE BINARY name = BINARY @bad_artist;
UPDATE tb_song s
JOIN tb_artist a ON a.id = s.artist_id
SET s.album = CASE
    WHEN BINARY a.name = BINARY @unknown_artist THEN @unknown_album
    ELSE CONCAT(SUBSTRING(s.album, 1, CHAR_LENGTH(s.album) - CHAR_LENGTH(CONCAT(' ', @old_single))), ' ', @single)
END
WHERE RIGHT(s.album, CHAR_LENGTH(CONCAT(' ', @old_single))) = CONCAT(' ', @old_single);
UPDATE tb_song s
JOIN tb_artist migu_artist ON BINARY migu_artist.name = BINARY @migu_artist
JOIN tb_artist justin ON BINARY justin.name = BINARY @justin_bieber
SET s.artist_id = justin.id,
        s.name = LEFT(s.name, CHAR_LENGTH(s.name) - CHAR_LENGTH(@migu_suffix)),
        s.album = CONCAT(@justin_bieber, ' ', @single)
WHERE s.artist_id = migu_artist.id
    AND RIGHT(s.name, CHAR_LENGTH(@migu_suffix)) = @migu_suffix;
UPDATE tb_song_artist sa
JOIN tb_artist migu_artist ON migu_artist.id = sa.artist_id AND BINARY migu_artist.name = BINARY @migu_artist
JOIN tb_artist justin ON BINARY justin.name = BINARY @justin_bieber
SET sa.artist_id = justin.id;
DELETE migu_artist FROM tb_artist migu_artist
WHERE BINARY migu_artist.name = BINARY @migu_artist
    AND NOT EXISTS (SELECT 1 FROM tb_song WHERE artist_id = migu_artist.id)
    AND NOT EXISTS (SELECT 1 FROM tb_song_artist WHERE artist_id = migu_artist.id);
UPDATE tb_playlist
SET title = REPLACE(title, @bad_artist, @unknown_artist),
    introduction = REPLACE(introduction, @bad_artist, @unknown_artist)
WHERE title LIKE CONCAT('%', @bad_artist, '%') OR introduction LIKE CONCAT('%', @bad_artist, '%');
COMMIT;
'@
    $sqlPath = Join-Path $backupDirectory "personal-library-text-$timestamp.sql"
    [System.IO.File]::WriteAllText($sqlPath, $sql, [System.Text.Encoding]::ASCII)
    $sourcePath = $sqlPath.Replace('\', '/')
    $null = Invoke-MySql "source $sourcePath"

    $remaining = Invoke-MySql "SELECT JSON_ARRAY('corrupt_artist', COUNT(*)) FROM tb_artist WHERE BINARY name = BINARY CONVERT(X'E98F88EE8186E785A1E5A79DE5B1BEE5A29C' USING utf8mb4) UNION ALL SELECT JSON_ARRAY('corrupt_album', COUNT(*)) FROM tb_song WHERE RIGHT(album, CHAR_LENGTH(CONCAT(' ', CONVERT(X'E98D97E69B9FE6B4B8' USING utf8mb4)))) = CONCAT(' ', CONVERT(X'E98D97E69B9FE6B4B8' USING utf8mb4)) UNION ALL SELECT JSON_ARRAY('corrupt_playlist', COUNT(*)) FROM tb_playlist WHERE title LIKE CONCAT('%', CONVERT(X'E98F88EE8186E785A1E5A79DE5B1BEE5A29C' USING utf8mb4), '%') OR introduction LIKE CONCAT('%', CONVERT(X'E98F88EE8186E785A1E5A79DE5B1BEE5A29C' USING utf8mb4), '%') UNION ALL SELECT JSON_ARRAY('migu_artist', COUNT(*)) FROM tb_artist WHERE BINARY name = BINARY 'Justin Bieber[music.migu.cn]' UNION ALL SELECT JSON_ARRAY('migu_song', COUNT(*)) FROM tb_song s JOIN tb_artist a ON a.id = s.artist_id WHERE BINARY a.name = BINARY 'Justin Bieber[music.migu.cn]' AND RIGHT(s.name, 15) = '[music.migu.cn]'"
    Write-Output "Backup: $([System.IO.Path]::GetFullPath($backupPath))"
    Write-Output "Executed SQL: $([System.IO.Path]::GetFullPath($sqlPath))"
    Write-Output 'Remaining known mojibake values:'
    $remaining | ForEach-Object { Write-Output $_ }
} finally {
    if ([System.IO.File]::Exists($optionsPath)) { [System.IO.File]::Delete($optionsPath) }
}