param(
    [string]$CatalogPath = (Join-Path $PSScriptRoot '..\target\artist-credit-catalog.json'),
    [string]$ConfigPath = (Join-Path $PSScriptRoot '..\src\main\resources\application-personal.yml'),
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\target\song-artist-credit-reconciliation.csv'),
    [string]$ArtistOutputPath = (Join-Path $PSScriptRoot '..\target\artist-entity-reconciliation.csv')
)

$ErrorActionPreference = 'Stop'
$mysql = 'D:\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not (Test-Path -LiteralPath $mysql)) {
    throw 'MySQL command-line client was not found in the configured installation directory.'
}

$catalog = [System.IO.File]::ReadAllText($CatalogPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$config = [System.IO.File]::ReadAllText($ConfigPath, [System.Text.Encoding]::UTF8)
$databaseUrl = [regex]::Match($config, 'url:\s*["'']?jdbc:mysql://([^:/]+):(\d+)/([^?"'']+)')
$username = [regex]::Match($config, '(?m)^\s{4}username:\s*["'']?([^"''\r\n]+)["'']?\s*$')
$password = [regex]::Match($config, '(?m)^\s{4}password:\s*["'']([^"''\r\n]+)["'']\s*$')
if (-not ($databaseUrl.Success -and $username.Success -and $password.Success)) {
    throw 'Could not read the personal database settings.'
}

$databaseName = $databaseUrl.Groups[3].Value
if ($databaseName -ne 'vibe_music_personal' -or $databaseName -notmatch '^[A-Za-z0-9_]+$') {
    throw "Refusing to audit unexpected database '$databaseName'."
}

$optionsPath = Join-Path $env:TEMP ('.mysql-song-artist-audit-' + [guid]::NewGuid().ToString('N') + '.cnf')
$escapedPassword = $password.Groups[1].Value.Replace('\', '\\').Replace('"', '\"')
$optionsContent = "[client]`r`nuser=$($username.Groups[1].Value.Trim())`r`npassword=`"$escapedPassword`"`r`nhost=$($databaseUrl.Groups[1].Value)`r`nport=$($databaseUrl.Groups[2].Value)`r`nprotocol=tcp`r`n"
Set-Content -LiteralPath $optionsPath -Value $optionsContent -Encoding ASCII

function Invoke-MySql([string[]]$Arguments) {
    $output = & $mysql "--defaults-extra-file=$optionsPath" --default-character-set=utf8mb4 --batch --raw --skip-column-names @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Read-only MySQL audit failed with exit code $LASTEXITCODE. $($output -join ' ')"
    }
    return $output
}

function Normalize-ArtistName([string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Name)) { return '' }
    return [regex]::Replace($Name.Normalize([System.Text.NormalizationForm]::FormKC).ToLowerInvariant(), '[^\p{L}\p{N}]', '')
}

function Normalize-ArtistText([string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Name)) { return '' }
    return [regex]::Replace($Name.Normalize([System.Text.NormalizationForm]::FormKC).ToLowerInvariant().Trim(), '\s+', ' ')
}

function ConvertFrom-Utf8Hex([string]$Hex) {
    if ([string]::IsNullOrEmpty($Hex)) { return '' }
    $bytes = [byte[]]::new($Hex.Length / 2)
    for ($index = 0; $index -lt $Hex.Length; $index += 2) {
        $bytes[$index / 2] = [Convert]::ToByte($Hex.Substring($index, 2), 16)
    }
    return [System.Text.Encoding]::UTF8.GetString($bytes)
}

try {
    $query = "SELECT JSON_ARRAY(s.id, s.audio_url, HEX(s.name), a.id, HEX(a.name)) FROM $databaseName.tb_song s LEFT JOIN $databaseName.tb_artist a ON a.id = s.artist_id ORDER BY s.id;"
    $databaseRows = @(Invoke-MySql @('--execute', $query))
    $databaseByFile = @{}
    foreach ($line in $databaseRows) {
        $columns = ConvertFrom-Json -InputObject $line
        if ($columns.Count -ne 5) { throw "Unexpected JSON song row: $line" }
        $uri = [uri]$columns[1]
        $fileName = [uri]::UnescapeDataString(($uri.AbsolutePath -split '/')[-1])
        if (-not $databaseByFile.ContainsKey($fileName)) {
            $databaseByFile[$fileName] = [System.Collections.Generic.List[object]]::new()
        }
        $databaseByFile[$fileName].Add([pscustomobject]@{
            songId = $columns[0]
            audioUrl = $columns[1]
            songName = ConvertFrom-Utf8Hex ([string]$columns[2])
            artistId = $columns[3]
            currentArtist = ConvertFrom-Utf8Hex ([string]$columns[4])
        })
    }

    $artistQuery = "SELECT JSON_ARRAY(id, HEX(name)) FROM $databaseName.tb_artist ORDER BY id;"
    $artistRows = @(Invoke-MySql @('--execute', $artistQuery))
    $artistsByText = @{}
    $artistsByNormalizedName = @{}
    foreach ($line in $artistRows) {
        $columns = ConvertFrom-Json -InputObject $line
        if ($columns.Count -ne 2) { throw "Unexpected JSON artist row: $line" }
        $entity = [pscustomobject]@{
            artistId = $columns[0]
            artistName = ConvertFrom-Utf8Hex ([string]$columns[1])
        }
        $textKey = Normalize-ArtistText $entity.artistName
        $normalizedKey = Normalize-ArtistName $entity.artistName
        if (-not $artistsByText.ContainsKey($textKey)) {
            $artistsByText[$textKey] = [System.Collections.Generic.List[object]]::new()
        }
        if (-not $artistsByNormalizedName.ContainsKey($normalizedKey)) {
            $artistsByNormalizedName[$normalizedKey] = [System.Collections.Generic.List[object]]::new()
        }
        $artistsByText[$textKey].Add($entity)
        $artistsByNormalizedName[$normalizedKey].Add($entity)
    }

    $artistReportRows = foreach ($artistCredit in $catalog.artists) {
        $textKey = Normalize-ArtistText $artistCredit.name
        $normalizedKey = Normalize-ArtistName $artistCredit.name
        $exactEntityMatches = @()
        if ($artistsByText.ContainsKey($textKey)) {
            $exactEntityMatches = @($artistsByText[$textKey].ToArray())
        }
        $normalizedEntityMatches = @()
        if ($artistsByNormalizedName.ContainsKey($normalizedKey)) {
            $normalizedEntityMatches = @($artistsByNormalizedName[$normalizedKey].ToArray())
        }
        $matchType = if ($exactEntityMatches.Count -eq 1) {
            'exact-name'
        } elseif ($exactEntityMatches.Count -gt 1) {
            'ambiguous-exact-name'
        } elseif ($normalizedEntityMatches.Count -eq 1) {
            'normalized-name-review'
        } elseif ($normalizedEntityMatches.Count -gt 1) {
            'ambiguous-normalized-name'
        } else {
            'no-existing-artist-entity'
        }
        $candidateEntities = if ($exactEntityMatches.Count -gt 0) { $exactEntityMatches } else { $normalizedEntityMatches }
        [pscustomobject]@{
            sourceArtistName = $artistCredit.name
            trackCount = $artistCredit.trackCount
            reviewRequired = $artistCredit.reviewRequired
            databaseMatchType = $matchType
            candidateArtistIds = (@($candidateEntities | ForEach-Object { $_.artistId }) -join ' | ')
            candidateDatabaseNames = (@($candidateEntities | ForEach-Object { $_.artistName }) -join ' | ')
            sourceFiles = $artistCredit.sourceFiles -join ' | '
        }
    }
    $reportRows = foreach ($track in $catalog.tracks) {
        $matchedRows = @()
        if ($databaseByFile.ContainsKey($track.file)) {
            $matchedRows = @($databaseByFile[$track.file].ToArray())
        }
        $credits = @($track.credits)
        $status = if ($matchedRows.Count -eq 0) {
            'database-song-missing'
        } elseif ($matchedRows.Count -gt 1) {
            'duplicate-audio-file-match'
        } elseif ($credits.Count -eq 0) {
            'no-artist-credit'
        } elseif ($credits.Count -gt 1) {
            'multiple-credits-require-link-table'
        } elseif ((Normalize-ArtistName $credits[0]) -eq (Normalize-ArtistName $matchedRows[0].currentArtist)) {
            'single-credit-matches-current'
        } else {
            'single-credit-differs-from-current'
        }

        [pscustomobject]@{
            sourceFile = $track.file
            title = $track.title
            album = $track.album
            creditSource = $track.creditSource
            artistCredits = $credits -join ' | '
            currentSongId = if ($matchedRows.Count -eq 1) { $matchedRows[0].songId } else { '' }
            currentArtistId = if ($matchedRows.Count -eq 1) { $matchedRows[0].artistId } else { '' }
            currentArtist = if ($matchedRows.Count -eq 1) { $matchedRows[0].currentArtist } else { '' }
            status = $status
        }
    }
    $resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
    New-Item -ItemType Directory -Path (Split-Path $resolvedOutput -Parent) -Force | Out-Null
    $csvLines = @($reportRows | ConvertTo-Csv -NoTypeInformation)
    [System.IO.File]::WriteAllLines($resolvedOutput, [string[]]$csvLines, [System.Text.UTF8Encoding]::new($true))

    $resolvedArtistOutput = [System.IO.Path]::GetFullPath($ArtistOutputPath)
    New-Item -ItemType Directory -Path (Split-Path $resolvedArtistOutput -Parent) -Force | Out-Null
    $artistCsvLines = @($artistReportRows | ConvertTo-Csv -NoTypeInformation)
    [System.IO.File]::WriteAllLines($resolvedArtistOutput, [string[]]$artistCsvLines, [System.Text.UTF8Encoding]::new($true))

    Write-Output "Source tracks: $($catalog.totalTracks)"
    Write-Output "Database rows: $($databaseRows.Count)"
    Write-Output "Reconciliation rows: $(@($reportRows).Count)"
    $reportRows | Group-Object status | Sort-Object Name | ForEach-Object { Write-Output "$($_.Name): $($_.Count)" }
    Write-Output "Report: $resolvedOutput"
    Write-Output "Artist entities: $($artistRows.Count)"
    $artistReportRows | Group-Object databaseMatchType | Sort-Object Name | ForEach-Object { Write-Output "$($_.Name): $($_.Count)" }
    Write-Output "Artist report: $resolvedArtistOutput"
} finally {
    Remove-Item -LiteralPath $optionsPath -Force -ErrorAction SilentlyContinue
}