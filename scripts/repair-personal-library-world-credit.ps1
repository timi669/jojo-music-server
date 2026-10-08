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

$optionsPath = Join-Path $env:TEMP ('.mysql-world-credit-' + [guid]::NewGuid().ToString('N') + '.cnf')
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

function ConvertFrom-Utf8Hex([string]$Hex) {
    if ([string]::IsNullOrEmpty($Hex)) { return '' }
    $bytes = [byte[]]::new($Hex.Length / 2)
    for ($index = 0; $index -lt $Hex.Length; $index += 2) {
        $bytes[$index / 2] = [Convert]::ToByte($Hex.Substring($index, 2), 16)
    }
    return [System.Text.Encoding]::UTF8.GetString($bytes)
}

function ConvertTo-SqlUtf8([string]$Value) {
    $hex = [BitConverter]::ToString([System.Text.Encoding]::UTF8.GetBytes($Value)).Replace('-', '')
    return "CONVERT(X'$hex' USING utf8mb4)"
}

try {
    $credits = @(
        'Lionel Richie', 'Stevie Wonder', 'Paul Simon', 'Kenny Rogers', 'James Ingram',
        'Tina Turner', 'Billy Joel', 'Michael Jackson', 'Diana Ross', 'Dionne Warwick',
        'Willie Nelson', 'Al Jarreau', 'Bruce Springsteen', 'Kenny Loggins', 'Steve Perry',
        'Daryl Hall', 'Huey Lewis', 'Cyndi Lauper', 'Kim Carnes', 'Bob Dylan', 'Ray Charles'
    )
    $songRows = @(Invoke-MySql "SELECT JSON_ARRAY(id, HEX(name), artist_id, HEX(album), HEX(audio_url)) FROM tb_song WHERE id = 872")
    if ($songRows.Count -ne 1) { throw "Expected exactly one source song at id 872; found $($songRows.Count)." }
    $song = ConvertFrom-Json -InputObject $songRows[0]
    if ((ConvertFrom-Utf8Hex ([string]$song[1])) -ne 'We Are The World') {
        throw 'Song id 872 no longer matches the reviewed We Are The World record.'
    }

    $artistRows = foreach ($line in (Invoke-MySql 'SELECT JSON_ARRAY(id, HEX(name)) FROM tb_artist ORDER BY id')) {
        $row = ConvertFrom-Json -InputObject $line
        [pscustomobject]@{ artistId = [long]$row[0]; name = ConvertFrom-Utf8Hex ([string]$row[1]) }
    }
    $artistsByName = [System.Collections.Generic.Dictionary[string, System.Collections.Generic.List[object]]]::new([System.StringComparer]::Ordinal)
    foreach ($artist in $artistRows) {
        if (-not $artistsByName.ContainsKey($artist.name)) { $artistsByName[$artist.name] = [System.Collections.Generic.List[object]]::new() }
        $artistsByName[$artist.name].Add($artist)
    }
    foreach ($credit in $credits) {
        if ($artistsByName.ContainsKey($credit) -and $artistsByName[$credit].Count -gt 1) {
            throw "Multiple exact artist entities exist for '$credit'."
        }
    }
    $newArtists = @($credits | Where-Object { -not $artistsByName.ContainsKey($_) })
    $existingLinks = @(Invoke-MySql "SELECT JSON_ARRAY(sa.song_id, sa.artist_id, sa.credit_order, sa.is_primary, HEX(sa.credit_source), HEX(a.name)) FROM tb_song_artist sa JOIN tb_artist a ON a.id = sa.artist_id WHERE sa.song_id = 872 ORDER BY sa.credit_order")

    Write-Output "Database: $databaseName"
    Write-Output 'Song: We Are The World (id 872)'
    Write-Output "Source artist credits: $($credits.Count)"
    Write-Output "Existing exact artist entities: $($credits.Count - $newArtists.Count)"
    Write-Output "New exact-name entities required: $($newArtists.Count)"
    Write-Output "Current song links to replace: $($existingLinks.Count)"
    if (-not $Apply) {
        Write-Output 'Preview only. Pass -Apply to back up and apply these exact source credits.'
        return
    }

    $creditSqlValues = @($credits | ForEach-Object { ConvertTo-SqlUtf8 $_ })
    $existingArtistBackup = @($artistRows | Where-Object { $credits -ccontains $_.name } | ForEach-Object { [pscustomobject]@{ artistId = $_.artistId; name = $_.name } })
    $backup = [pscustomobject]@{
        createdAt = [DateTime]::UtcNow.ToString('o')
        database = $databaseName
        song = $song
        existingArtists = $existingArtistBackup
        links = @($existingLinks | ForEach-Object { ConvertFrom-Json -InputObject $_ })
    }
    $backupDirectory = Join-Path $scriptDirectory '..\target\backups'
    New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
    $timestamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
    $backupPath = Join-Path $backupDirectory "we-are-the-world-credit-$timestamp.json"
    [System.IO.File]::WriteAllText($backupPath, (ConvertTo-Json -InputObject $backup -Depth 8), [System.Text.UTF8Encoding]::new($true))

    $sql = [System.Collections.Generic.List[string]]::new()
    $sql.Add('SET NAMES utf8mb4;')
    $sql.Add('START TRANSACTION;')
    foreach ($credit in $newArtists) {
        $nameSql = ConvertTo-SqlUtf8 $credit
        $sql.Add("INSERT INTO tb_artist (name) SELECT $nameSql WHERE NOT EXISTS (SELECT 1 FROM tb_artist WHERE BINARY name = BINARY $nameSql);")
    }
    $leadArtistSql = ConvertTo-SqlUtf8 $credits[0]
    $sql.Add('DELETE FROM tb_song_artist WHERE song_id = 872;')
    $sql.Add("UPDATE tb_song SET artist_id = (SELECT id FROM tb_artist WHERE BINARY name = BINARY $leadArtistSql LIMIT 1) WHERE id = 872;")
    for ($index = 0; $index -lt $credits.Count; $index++) {
        $nameSql = $creditSqlValues[$index]
        $primary = if ($index -eq 0) { 1 } else { 0 }
        $order = $index + 1
        $sql.Add("INSERT INTO tb_song_artist (song_id, artist_id, credit_order, is_primary, credit_source) SELECT 872, id, $order, $primary, 'vorbis-artist-tag' FROM tb_artist WHERE BINARY name = BINARY $nameSql;")
    }
    $sql.Add('COMMIT;')
    $sqlPath = Join-Path $backupDirectory "we-are-the-world-credit-$timestamp.sql"
    [System.IO.File]::WriteAllLines($sqlPath, $sql, [System.Text.Encoding]::ASCII)
    $null = Invoke-MySql "source $($sqlPath.Replace('\', '/'))"

    $verified = Invoke-MySql "SELECT JSON_ARRAY(sa.song_id, sa.credit_order, sa.is_primary, HEX(a.name), sa.credit_source) FROM tb_song_artist sa JOIN tb_artist a ON a.id = sa.artist_id WHERE sa.song_id = 872 ORDER BY sa.credit_order"
    Write-Output "Backup: $([System.IO.Path]::GetFullPath($backupPath))"
    Write-Output "Executed SQL: $([System.IO.Path]::GetFullPath($sqlPath))"
    Write-Output "Verified link rows: $($verified.Count)"
} finally {
    if ([System.IO.File]::Exists($optionsPath)) { [System.IO.File]::Delete($optionsPath) }
}