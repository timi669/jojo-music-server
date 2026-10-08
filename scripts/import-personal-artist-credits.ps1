[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [switch]$Apply,
    [string]$CatalogPath = (Join-Path $PSScriptRoot '..\target\artist-credit-catalog.json'),
    [string]$MetadataPath = (Join-Path $PSScriptRoot '..\target\artist-metadata-review.csv'),
    [string]$ConfigPath = (Join-Path $PSScriptRoot '..\src\main\resources\application-personal.yml')
)

$ErrorActionPreference = 'Stop'
$mysql = 'D:\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not (Test-Path -LiteralPath $mysql)) {
    throw 'MySQL command-line client was not found in the configured installation directory.'
}

$catalog = [System.IO.File]::ReadAllText($CatalogPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$metadataRows = Import-Csv -LiteralPath $MetadataPath
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

$optionsPath = Join-Path $env:TEMP ('.mysql-artist-credit-' + [guid]::NewGuid().ToString('N') + '.cnf')
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
    return "CONVERT(0x$hex USING utf8mb4)"
}

function ConvertTo-ArtistArea($Row) {
    switch ($Row.countryCode) {
        'CN' { return 'China' }
        'US' { return 'United States' }
        'CA' { return 'Canada' }
        'TW' { return 'Taiwan' }
        'KR' { return 'South Korea' }
        'JP' { return 'Japan' }
        'BR' { return 'Brazil' }
        default {
            if ($Row.area -eq 'Chongqing') { return 'Chongqing' }
            return $null
        }
    }
}

function ConvertTo-ArtistGender($Row) {
    if ($Row.type -eq 'Group') { return 2 }
    switch ($Row.gender) {
        'male' { return 0 }
        'female' { return 1 }
        default { return $null }
    }
}

try {
    $artistRows = foreach ($line in (Invoke-MySql 'SELECT JSON_ARRAY(id, HEX(name), gender, HEX(area)) FROM tb_artist ORDER BY id')) {
        $columns = ConvertFrom-Json -InputObject $line
        [pscustomobject]@{
            artistId = [long]$columns[0]
            artistName = ConvertFrom-Utf8Hex ([string]$columns[1])
            gender = $columns[2]
            area = ConvertFrom-Utf8Hex ([string]$columns[3])
        }
    }

    $artistsByName = [System.Collections.Generic.Dictionary[string, System.Collections.Generic.List[object]]]::new([System.StringComparer]::Ordinal)
    $artistsById = @{}
    foreach ($artist in $artistRows) {
        if (-not $artistsByName.ContainsKey($artist.artistName)) {
            $artistsByName[$artist.artistName] = [System.Collections.Generic.List[object]]::new()
        }
        $artistsByName[$artist.artistName].Add($artist)
        $artistsById[$artist.artistId] = $artist
    }

    $databaseSongs = foreach ($line in (Invoke-MySql "SELECT JSON_ARRAY(s.id, s.artist_id, HEX(SUBSTRING_INDEX(SUBSTRING_INDEX(s.audio_url, '?', 1), '/', -1))) FROM tb_song s ORDER BY s.id")) {
        $columns = ConvertFrom-Json -InputObject $line
        [pscustomobject]@{
            songId = [long]$columns[0]
            primaryArtistId = [long]$columns[1]
            fileName = ConvertFrom-Utf8Hex ([string]$columns[2])
        }
    }
    $songsByFile = @{}
    foreach ($song in $databaseSongs) {
        $fileName = [uri]::UnescapeDataString($song.fileName)
        if (-not $songsByFile.ContainsKey($fileName)) { $songsByFile[$fileName] = [System.Collections.Generic.List[object]]::new() }
        $songsByFile[$fileName].Add($song)
    }

    $metadataPlans = [System.Collections.Generic.Dictionary[string, object]]::new([System.StringComparer]::Ordinal)
    foreach ($row in $metadataRows | Where-Object {
        $_.lookupStatus -eq 'unique-exact-candidate' -and
        $_.requiresManualReview -eq 'false' -and
        $_.candidateName -ceq $_.artistName
    }) {
        $gender = ConvertTo-ArtistGender $row
        $area = ConvertTo-ArtistArea $row
        if ($null -eq $gender -and [string]::IsNullOrWhiteSpace($area)) { continue }
        if ($metadataPlans.ContainsKey($row.artistName)) { throw "Duplicate approved metadata for '$($row.artistName)'." }
        $metadataPlans[$row.artistName] = [pscustomobject]@{ gender = $gender; area = $area }
    }

    $creditPlans = [System.Collections.Generic.List[object]]::new()
    $newArtistNames = [System.Collections.Generic.Dictionary[string, object]]::new([System.StringComparer]::Ordinal)
    foreach ($track in $catalog.tracks | Where-Object { @($_.credits).Count -gt 1 }) {
        if ($track.creditSource -ne 'id3-tpe1') { throw "Refusing non-ID3 multi-credit source '$($track.creditSource)' for '$($track.file)'." }
        if (-not $songsByFile.ContainsKey($track.file) -or $songsByFile[$track.file].Count -ne 1) {
            throw "Expected exactly one live song for '$($track.file)'."
        }
        $song = $songsByFile[$track.file][0]
        if (-not $artistsById.ContainsKey($song.primaryArtistId)) { throw "Legacy primary artist missing for song $($song.songId)." }

        $credits = [System.Collections.Generic.List[string]]::new()
        foreach ($credit in $track.credits) {
            if ([string]::IsNullOrWhiteSpace($credit)) { throw "Empty credit in '$($track.file)'." }
            if (-not $credits.Contains([string]$credit)) { $credits.Add([string]$credit) }
        }
        $primaryArtist = $artistsById[$song.primaryArtistId]
        $primaryInCredits = $false
        foreach ($credit in $credits) {
            if ($credit -ceq $primaryArtist.artistName) { $primaryInCredits = $true }
            if ($artistsByName.ContainsKey($credit) -and $artistsByName[$credit].Count -gt 1) {
                throw "Multiple exact artist entities exist for credit '$credit'."
            }
            if (-not $artistsByName.ContainsKey($credit) -and -not $newArtistNames.ContainsKey($credit)) {
                $plan = if ($metadataPlans.ContainsKey($credit)) { $metadataPlans[$credit] } else { [pscustomobject]@{ gender = $null; area = $null } }
                $newArtistNames[$credit] = $plan
            }
        }
        if (-not $primaryInCredits -and -not $newArtistNames.ContainsKey($primaryArtist.artistName) -and -not $artistsByName.ContainsKey($primaryArtist.artistName)) {
            $newArtistNames[$primaryArtist.artistName] = [pscustomobject]@{ gender = $null; area = $null }
        }
        $creditPlans.Add([pscustomobject]@{
            song = $song
            credits = $credits
            primaryArtist = $primaryArtist
            primaryInCredits = $primaryInCredits
            sourceFile = $track.file
        })
    }

    $metadataUpdateCount = 0
    foreach ($name in $metadataPlans.Keys) {
        if ($artistsByName.ContainsKey($name)) {
            if ($artistsByName[$name].Count -ne 1) { throw "Multiple exact artist entities exist for metadata candidate '$name'." }
            $artist = $artistsByName[$name][0]
            $plan = $metadataPlans[$name]
            if (($null -eq $artist.gender -and $null -ne $plan.gender) -or ([string]::IsNullOrWhiteSpace($artist.area) -and -not [string]::IsNullOrWhiteSpace($plan.area))) {
                $metadataUpdateCount++
            }
        }
    }

    $newNames = @($newArtistNames.Keys)
    $creditCount = 0
    foreach ($plan in $creditPlans) { $creditCount += $plan.credits.Count + $(if ($plan.primaryInCredits) { 0 } else { 1 }) }
    Write-Output "Database: $databaseName"
    Write-Output "Approved metadata candidates: $($metadataPlans.Count)"
    Write-Output "Existing artists to enrich: $metadataUpdateCount"
    Write-Output "New exact-name artist entities: $($newNames.Count)"
    Write-Output "Songs with multiple embedded credits: $($creditPlans.Count)"
    Write-Output "Song-artist links to write: $creditCount"

    if (-not $Apply) {
        Write-Output 'Preview only. Pass -Apply to back up and apply these changes.'
        return
    }
    if (-not $PSCmdlet.ShouldProcess($databaseName, 'Back up and import approved artist metadata and embedded song credits')) { return }

    $songIds = @($creditPlans | ForEach-Object { $_.song.songId }) -join ','
    $artistNamesForBackup = @($metadataPlans.Keys + $newNames | Sort-Object -Unique)
    $backupArtistRows = @()
    foreach ($artist in $artistRows | Where-Object { $artistNamesForBackup -ccontains $_.artistName }) {
        $backupArtistRows += $artist
    }
    $backupLinkRows = @()
    if ($songIds) {
        foreach ($line in (Invoke-MySql "SELECT JSON_ARRAY(song_id, artist_id, credit_order, is_primary, credit_source) FROM tb_song_artist WHERE song_id IN ($songIds) ORDER BY song_id, credit_order")) {
            $backupLinkRows += ,(ConvertFrom-Json -InputObject $line)
        }
    }
    $backup = [pscustomobject]@{
        createdAt = [DateTime]::UtcNow.ToString('o')
        database = $databaseName
        artists = $backupArtistRows
        songArtistLinks = $backupLinkRows
    }
    $backupDirectory = Join-Path $PSScriptRoot '..\target\backups'
    New-Item -ItemType Directory -Path $backupDirectory -Force | Out-Null
    $backupPath = Join-Path $backupDirectory ('artist-credit-import-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '.json')
    [System.IO.File]::WriteAllText($backupPath, (ConvertTo-Json -InputObject $backup -Depth 8), [System.Text.UTF8Encoding]::new($false))

    $sql = [System.Collections.Generic.List[string]]::new()
    $sql.Add('START TRANSACTION;')
    foreach ($name in $newNames) {
        $metadata = $newArtistNames[$name]
        $genderSql = if ($null -eq $metadata.gender) { 'NULL' } else { [string]$metadata.gender }
        $areaSql = if ([string]::IsNullOrWhiteSpace($metadata.area)) { 'NULL' } else { ConvertTo-SqlUtf8 $metadata.area }
        $nameSql = ConvertTo-SqlUtf8 $name
        $sql.Add("INSERT INTO tb_artist (name, gender, area) SELECT $nameSql, $genderSql, $areaSql WHERE NOT EXISTS (SELECT 1 FROM tb_artist WHERE BINARY name = BINARY $nameSql);")
    }
    foreach ($name in $metadataPlans.Keys) {
        $metadata = $metadataPlans[$name]
        $nameSql = ConvertTo-SqlUtf8 $name
        $assignments = [System.Collections.Generic.List[string]]::new()
        if ($null -ne $metadata.gender) { $assignments.Add("gender = COALESCE(gender, $($metadata.gender))") }
        if (-not [string]::IsNullOrWhiteSpace($metadata.area)) {
            $areaSql = ConvertTo-SqlUtf8 $metadata.area
            $assignments.Add("area = COALESCE(NULLIF(area, ''), $areaSql)")
        }
        if ($assignments.Count -gt 0) {
            $sql.Add("UPDATE tb_artist SET $($assignments -join ', ') WHERE BINARY name = BINARY $nameSql;")
        }
    }
    foreach ($plan in $creditPlans) {
        $songId = $plan.song.songId
        $sql.Add("DELETE FROM tb_song_artist WHERE song_id = $songId;")
        $order = 1
        foreach ($credit in $plan.credits) {
            $creditSql = ConvertTo-SqlUtf8 $credit
            $primaryFlag = if ($credit -ceq $plan.primaryArtist.artistName) { 1 } else { 0 }
            $sql.Add("INSERT INTO tb_song_artist (song_id, artist_id, credit_order, is_primary, credit_source) SELECT $songId, id, $order, $primaryFlag, 'id3-tpe1' FROM tb_artist WHERE BINARY name = BINARY $creditSql;")
            $order++
        }
        if (-not $plan.primaryInCredits) {
            $sql.Add("INSERT INTO tb_song_artist (song_id, artist_id, credit_order, is_primary, credit_source) VALUES ($songId, $($plan.primaryArtist.artistId), $order, 1, 'legacy-primary');")
        }
    }
    $sql.Add('COMMIT;')

    $sqlPath = Join-Path $backupDirectory ('artist-credit-import-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '.sql')
    [System.IO.File]::WriteAllText($sqlPath, ($sql -join "`r`n"), [System.Text.Encoding]::ASCII)
    $sourcePath = $sqlPath.Replace('\', '/')
    $null = Invoke-MySql "source $sourcePath"
    Write-Output "Backup: $([System.IO.Path]::GetFullPath($backupPath))"
    Write-Output "Executed SQL: $([System.IO.Path]::GetFullPath($sqlPath))"

    $verification = Invoke-MySql "SELECT JSON_ARRAY('song_artist_total', COUNT(*)) FROM tb_song_artist UNION ALL SELECT JSON_ARRAY('songs_with_multiple_links', COUNT(*)) FROM (SELECT song_id FROM tb_song_artist GROUP BY song_id HAVING COUNT(*) > 1) linked_songs; SELECT JSON_ARRAY(a.name, COUNT(DISTINCT sa.song_id)) FROM tb_song_artist sa JOIN tb_artist a ON a.id = sa.artist_id WHERE sa.song_id IN ($songIds) GROUP BY a.name ORDER BY a.name"
    Write-Output 'Live database verification:'
    $verification | ForEach-Object { Write-Output $_ }
} finally {
    if ([System.IO.File]::Exists($optionsPath)) {
        [System.IO.File]::Delete($optionsPath)
    }
}