param(
    [string]$PlanPath = (Join-Path $PSScriptRoot '..\target\personal-library-plan.json'),
    [string]$ConfigPath = (Join-Path $PSScriptRoot '..\src\main\resources\application-local.yml'),
    [string]$DatabaseName = 'vibe_music_personal',
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$mysqlHome = 'D:\MySQL\MySQL Server 8.0\bin'
$mysql = Join-Path $mysqlHome 'mysql.exe'
$mysqldump = Join-Path $mysqlHome 'mysqldump.exe'
if (-not (Test-Path $mysql) -or -not (Test-Path $mysqldump)) {
    throw 'MySQL command-line client was not found in the configured installation directory.'
}
if ($DatabaseName -notmatch '^[A-Za-z0-9_]+$' -or $DatabaseName -eq 'vibe_music') {
    throw 'The personal database name is invalid or points at the existing sample database.'
}

$plan = [System.IO.File]::ReadAllText($PlanPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$config = Get-Content -LiteralPath $ConfigPath -Raw
if ($plan.songs.Count -eq 0 -or $plan.mediaItems.Count -eq 0) {
    throw 'The personal library plan is empty.'
}

$databaseUrl = [regex]::Match($config, 'url:\s*["'']?jdbc:mysql://([^:/]+):(\d+)/([^?"''\s]+)')
$username = [regex]::Match($config, '(?m)^\s{4}username:\s*["'']?([^"''\r\n]+)["'']?\s*$')
$password = [regex]::Match($config, '(?m)^\s{4}password:\s*["'']?([^"''\r\n]+)["'']?\s*$')
$minioEndpoint = [regex]::Match($config, '(?m)^\s{2}endpoint:\s*["'']?([^"''\r\n]+)["'']?\s*$')
if (-not $databaseUrl.Success -or -not $username.Success -or -not $password.Success -or -not $minioEndpoint.Success) {
    throw 'Required local MySQL or MinIO settings could not be read from application-local.yml.'
}

$databaseHost = $databaseUrl.Groups[1].Value
$databasePort = $databaseUrl.Groups[2].Value
$sourceDatabase = $databaseUrl.Groups[3].Value
$mysqlUser = $username.Groups[1].Value
$mysqlPassword = $password.Groups[1].Value
$publicMediaRoot = $minioEndpoint.Groups[1].Value.TrimEnd('/') + '/vibe-music-personal'
$personalConfigPath = Join-Path (Split-Path $ConfigPath -Parent) 'application-personal.yml'

function ConvertTo-SqlLiteral([AllowNull()][object]$Value) {
    if ($null -eq $Value) { return 'NULL' }
    return "'" + ([string]$Value).Replace('\', '\\').Replace("'", "''") + "'"
}

function ConvertTo-PublicUrl([string]$Key) {
    $encodedKey = (($Key -split '/') | ForEach-Object { [uri]::EscapeDataString($_) }) -join '/'
    return "$publicMediaRoot/$encodedKey"
}

function Invoke-MySql([string[]]$Arguments) {
    $output = & $mysql "--defaults-extra-file=$optionsPath" --default-character-set=utf8mb4 @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "MySQL command failed with exit code $LASTEXITCODE. $($output -join ' ')"
    }
    return $output
}

function Invoke-MySqlFile([string]$FilePath, [string]$Database) {
    $sourcePath = $FilePath.Replace('\', '/')
    Invoke-MySql @("--database=$Database", '--execute', "source $sourcePath") | Out-Null
}

$temporaryOptions = Join-Path $env:TEMP ('.mysql-personal-' + [guid]::NewGuid().ToString('N') + '.cnf')
$optionsPath = $temporaryOptions
$escapedPassword = $mysqlPassword.Replace('\', '\\').Replace('"', '\"')
$optionsContent = "[client]`r`nuser=$mysqlUser`r`npassword=`"$escapedPassword`"`r`nhost=$databaseHost`r`nport=$databasePort`r`nprotocol=tcp`r`n"
Set-Content -LiteralPath $optionsPath -Value $optionsContent -Encoding ASCII

try {
    $version = Invoke-MySql @('--execute', 'SELECT VERSION()')
    $existingDatabase = Invoke-MySql @('--batch', '--skip-column-names', '--execute', "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = '$DatabaseName'")
    if ($existingDatabase) {
        throw "Database $DatabaseName already exists; refusing to overwrite it. Choose another name or inspect the existing personal import."
    }

    $adminCount = Invoke-MySql @('--batch', '--skip-column-names', '--database', $sourceDatabase, '--execute', "SELECT COUNT(*) FROM tb_admin WHERE username = 'admin_1'")
    if ([int]($adminCount | Select-Object -Last 1) -ne 1) {
        throw 'The existing sample database does not contain exactly one admin_1 account to preserve.'
    }

    $sourceFiles = @($plan.mediaItems | Where-Object { -not (Test-Path -LiteralPath $_.file -PathType Leaf) })
    if ($sourceFiles.Count -gt 0) {
        throw "$($sourceFiles.Count) media files from the import plan are missing."
    }

    $totalBytes = ($plan.mediaItems | Measure-Object -Property size -Sum).Sum
    $summary = [pscustomobject]@{
        Mode = if ($Apply) { 'apply' } else { 'dry-run' }
        MySqlVersion = ($version | Select-Object -Last 1)
        SourceDatabase = $sourceDatabase
        TargetDatabase = $DatabaseName
        Songs = $plan.songs.Count
        Artists = $plan.artists.Count
        Playlists = $plan.playlists.Count
        Banners = $plan.banners.Count
        MediaObjects = $plan.mediaItems.Count
        MediaGB = [math]::Round($totalBytes / 1GB, 2)
        PreservedAdmin = 'admin_1'
        OldDatabaseWillBeModified = $false
    }
    if (-not $Apply) {
        $summary
        return
    }

    if (Test-Path $personalConfigPath) {
        throw "Personal profile already exists at $personalConfigPath; refusing to overwrite it."
    }

    $targetDirectory = Split-Path ([System.IO.Path]::GetFullPath($PlanPath)) -Parent
    $backupPath = Join-Path $targetDirectory 'vibe_music-before-personal-import.sql'
    $schemaPath = Join-Path $targetDirectory 'vibe_music-schema.sql'
    $dataPath = Join-Path $targetDirectory 'vibe_music_personal-data.sql'
    $sourceDumpPath = Join-Path $PSScriptRoot '..\sql\vibe_music.sql'

    & $mysqldump "--defaults-extra-file=$optionsPath" --default-character-set=utf8mb4 --single-transaction --routines --triggers --events --result-file=$backupPath $sourceDatabase
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $backupPath)) {
        throw 'Full source database backup failed; no target database was created.'
    }

    if (-not (Test-Path $sourceDumpPath)) {
        throw "Schema source file not found: $sourceDumpPath"
    }
    $schemaLines = [System.IO.File]::ReadAllLines($sourceDumpPath, [System.Text.Encoding]::UTF8) |
        Where-Object { $_ -notmatch '^\s*INSERT\s+INTO\s+' }
    $schemaContent = $schemaLines -join [Environment]::NewLine
    $artistNamePattern = [regex]::new('(?s)(CREATE TABLE `tb_artist`\s*\(.*?`name` varchar\()100(\))')
    $artistExpandedSchema = $artistNamePattern.Replace($schemaContent, '${1}255${2}', 1)
    if ($artistExpandedSchema -eq $schemaContent) {
        throw 'Could not widen the personal artist name column in the schema template.'
    }
    $mediaUrlPattern = [regex]::new('(?m)(`(?:avatar|banner_url|cover_url|audio_url)` varchar\()255(\))')
    $expandedSchema = $mediaUrlPattern.Replace($artistExpandedSchema, '${1}1024${2}')
    if ($expandedSchema -eq $artistExpandedSchema) {
        throw 'Could not widen the personal media URL columns in the schema template.'
    }
    [System.IO.File]::WriteAllText($schemaPath, $expandedSchema, [System.Text.UTF8Encoding]::new($false))

    Invoke-MySql @('--execute', "CREATE DATABASE ``$DatabaseName`` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci") | Out-Null
    $createdDatabase = $true
    Invoke-MySqlFile $schemaPath $DatabaseName
    $urlColumnLengths = Invoke-MySql @(
        '--batch',
        '--skip-column-names',
        "--database=$DatabaseName",
        '--execute',
        "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME, '=', CHARACTER_MAXIMUM_LENGTH) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '$DatabaseName' AND COLUMN_NAME IN ('avatar', 'banner_url', 'cover_url', 'audio_url')"
    )
    if (@($urlColumnLengths | Where-Object { $_ -match '=1024$' }).Count -lt 5) {
        throw 'Personal media URL columns did not receive the expected 1024 character capacity.'
    }

    $sqlLines = [System.Collections.Generic.List[string]]::new()
    $sqlLines.Add("INSERT INTO ``$DatabaseName``.tb_admin (id, username, password) SELECT id, username, password FROM ``$sourceDatabase``.tb_admin WHERE username = 'admin_1';")

    $sourceDatabaseUrl = "jdbc:mysql://$($databaseHost):$($databasePort)/$($sourceDatabase)?"
    $personalDatabaseUrl = "jdbc:mysql://$($databaseHost):$($databasePort)/$($DatabaseName)?"
    $personalConfig = $config.Replace($sourceDatabaseUrl, $personalDatabaseUrl)
    $personalConfig = [regex]::Replace($personalConfig, '(?m)^(\s{6}database:\s*)\d+\s*$', '${1}2')
    $personalConfig = [regex]::Replace($personalConfig, '(?m)^(\s{2}bucket:\s*)[^\s#]+', '${1}vibe-music-personal')
    if ($personalConfig -notmatch [regex]::Escape("/$DatabaseName?") -or $personalConfig -notmatch 'bucket:\s*vibe-music-personal') {
        throw 'Unable to create the isolated personal profile from the local server settings.'
    }
    [System.IO.File]::WriteAllText($personalConfigPath, $personalConfig, [System.Text.UTF8Encoding]::new($false))
    $sqlLines.Add("INSERT INTO ``$DatabaseName``.tb_style (id, name) SELECT id, name FROM ``$sourceDatabase``.tb_style;")

    $styleNames = [System.Collections.Generic.SortedSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($song in $plan.songs) {
        foreach ($style in ([string]$song.genre -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
            [void]$styleNames.Add($style)
        }
    }
    foreach ($style in $styleNames) {
        $literal = ConvertTo-SqlLiteral $style
        $sqlLines.Add("INSERT IGNORE INTO ``$DatabaseName``.tb_style (name) VALUES ($literal);")
    }

    foreach ($artist in $plan.artists) {
        $artistName = ConvertTo-SqlLiteral $artist.name
        $avatarUrl = if ($artist.avatarKey) { ConvertTo-SqlLiteral (ConvertTo-PublicUrl $artist.avatarKey) } else { 'NULL' }
        $sqlLines.Add("INSERT INTO ``$DatabaseName``.tb_artist (name, avatar, gender, birth, area, introduction) VALUES ($artistName, $avatarUrl, NULL, NULL, NULL, NULL);")
    }

    $songIndex = 0
    foreach ($song in $plan.songs) {
        $songIndex++
        $artistName = ConvertTo-SqlLiteral $song.artist
        $title = ConvertTo-SqlLiteral $song.title
        $album = ConvertTo-SqlLiteral $song.album
        $duration = ConvertTo-SqlLiteral ([string]$song.durationSeconds)
        $genre = ConvertTo-SqlLiteral $song.genre
        $audioUrl = ConvertTo-SqlLiteral (ConvertTo-PublicUrl ("songs/" + [System.IO.Path]::GetFileName($song.file)))
        $coverUrl = if ($song.coverKey) { ConvertTo-SqlLiteral (ConvertTo-PublicUrl $song.coverKey) } else { 'NULL' }
        $releaseDate = ConvertTo-SqlLiteral $song.releaseDate
        $sqlLines.Add("INSERT INTO ``$DatabaseName``.tb_song (artist_id, name, album, duration, style, cover_url, audio_url, release_time) SELECT id, $title, $album, $duration, $genre, $coverUrl, $audioUrl, $releaseDate FROM ``$DatabaseName``.tb_artist WHERE name = $artistName LIMIT 1;")
        $sqlLines.Add("SET @personal_song_id = LAST_INSERT_ID();")
        foreach ($style in ([string]$song.genre -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
            $styleLiteral = ConvertTo-SqlLiteral $style
            $sqlLines.Add("INSERT IGNORE INTO ``$DatabaseName``.tb_genre (song_id, style_id) SELECT @personal_song_id, id FROM ``$DatabaseName``.tb_style WHERE name = $styleLiteral;")
        }
    }

    foreach ($playlist in $plan.playlists) {
        $title = ConvertTo-SqlLiteral $playlist.title
        $introduction = ConvertTo-SqlLiteral $playlist.introduction
        $style = ConvertTo-SqlLiteral $playlist.style
        $coverUrl = if ($playlist.coverKey) { ConvertTo-SqlLiteral (ConvertTo-PublicUrl $playlist.coverKey) } else { 'NULL' }
        $artistName = ConvertTo-SqlLiteral $playlist.artist
        $sqlLines.Add("INSERT INTO ``$DatabaseName``.tb_playlist (title, cover_url, introduction, style) VALUES ($title, $coverUrl, $introduction, $style);")
        $sqlLines.Add('SET @personal_playlist_id = LAST_INSERT_ID();')
        $sqlLines.Add("INSERT IGNORE INTO ``$DatabaseName``.tb_playlist_binding (playlist_id, song_id) SELECT @personal_playlist_id, s.id FROM ``$DatabaseName``.tb_song s INNER JOIN ``$DatabaseName``.tb_artist a ON a.id = s.artist_id WHERE a.name = $artistName;")
    }

    for ($bannerIndex = 0; $bannerIndex -lt $plan.banners.Count; $bannerIndex++) {
        $bannerUrl = ConvertTo-SqlLiteral (ConvertTo-PublicUrl $plan.banners[$bannerIndex].key)
        $sqlLines.Add("INSERT INTO ``$DatabaseName``.tb_banner (banner_url, status) VALUES ($bannerUrl, 0);")
    }

    [System.IO.File]::WriteAllLines($dataPath, $sqlLines, [System.Text.UTF8Encoding]::new($false))
    Invoke-MySqlFile $dataPath $sourceDatabase

    $verification = Invoke-MySql @(
        '--batch',
        '--skip-column-names',
        '--execute',
        "SELECT 'songs', COUNT(*) FROM ``$DatabaseName``.tb_song UNION ALL SELECT 'artists', COUNT(*) FROM ``$DatabaseName``.tb_artist UNION ALL SELECT 'users', COUNT(*) FROM ``$DatabaseName``.tb_user UNION ALL SELECT 'admins', COUNT(*) FROM ``$DatabaseName``.tb_admin UNION ALL SELECT 'playlists', COUNT(*) FROM ``$DatabaseName``.tb_playlist"
    )
    [pscustomobject]@{
        Result = 'imported'
        Database = $DatabaseName
        Backup = $backupPath
        Counts = @($verification)
        OldDatabaseModified = $false
    }
} catch {
    if ($createdDatabase) {
        try {
            Invoke-MySql @('--execute', "DROP DATABASE ``$DatabaseName``") | Out-Null
            Write-Warning "Removed incomplete personal database $DatabaseName. Source backup is retained."
        } catch {
            Write-Warning "Automatic rollback failed for $DatabaseName. The original database and backup are intact; inspect the target database manually."
        }
    }
    if (Test-Path $personalConfigPath) {
        Remove-Item -LiteralPath $personalConfigPath -Force -ErrorAction SilentlyContinue
    }
    throw
} finally {
    Remove-Item -LiteralPath $optionsPath -Force -ErrorAction SilentlyContinue
}