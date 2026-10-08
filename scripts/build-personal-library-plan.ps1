param(
    [string]$DataRoot = 'D:\BaiduNetdiskDownload\vibe-music-data',
    [string]$ManifestPath = (Join-Path $PSScriptRoot '..\target\personal-library-manifest.json'),
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\target\personal-library-plan.json')
)

$ErrorActionPreference = 'Stop'
$manifest = [System.IO.File]::ReadAllText($ManifestPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$unknownArtistLabel = ([char]0x672A).ToString() + [char]0x77E5 + [char]0x6B4C + [char]0x624B
$unknownAlbumLabel = ([char]0x672A).ToString() + [char]0x77E5 + [char]0x4E13 + [char]0x8F91
$singleTrackLabel = ([char]0x5355).ToString() + [char]0x66F2
$artistDirectory = Join-Path $DataRoot 'artists'
$playlistDirectory = Join-Path $DataRoot 'playlists'
$bannerDirectory = Join-Path $DataRoot 'banners'
$extractedCoverDirectory = Join-Path $PSScriptRoot '..\target\personal-song-covers'
$coverMappingPath = Join-Path $PSScriptRoot '..\target\personal-song-covers.json'
$coverExtractorPath = Join-Path $PSScriptRoot 'media-tools\extract-embedded-covers.mjs'
if (-not (Test-Path -LiteralPath $coverExtractorPath -PathType Leaf)) {
    throw "Embedded cover extractor not found: $coverExtractorPath"
}
& node $coverExtractorPath $ManifestPath $DataRoot $extractedCoverDirectory $coverMappingPath | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to extract embedded song covers.'
}
$coverExtraction = Get-Content -LiteralPath $coverMappingPath -Raw | ConvertFrom-Json
$embeddedCoversBySong = @{}
foreach ($cover in $coverExtraction.covers) {
    $embeddedCoversBySong[$cover.sourceFile] = $cover
}

function Get-ImageContentType([string]$Path) {
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $header = New-Object byte[] 12
        $count = $stream.Read($header, 0, $header.Length)
    } finally {
        $stream.Dispose()
    }
    if ($count -ge 8 -and [BitConverter]::ToString($header[0..7]) -eq '89-50-4E-47-0D-0A-1A-0A') { return 'image/png' }
    if ($count -ge 3 -and $header[0] -eq 255 -and $header[1] -eq 216 -and $header[2] -eq 255) { return 'image/jpeg' }
    if ($count -ge 12 -and [System.Text.Encoding]::ASCII.GetString($header, 0, 4) -eq 'RIFF' -and [System.Text.Encoding]::ASCII.GetString($header, 8, 4) -eq 'WEBP') { return 'image/webp' }
    throw "Unsupported image format: $Path"
}

function Get-NormalizedArtistName([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return '' }
    return [regex]::Replace($Value.Normalize([System.Text.NormalizationForm]::FormKC).ToLowerInvariant(), '[^\p{L}\p{N}]', '')
}

function Get-MediaItems([string]$Directory, [string]$Prefix) {
    Get-ChildItem -LiteralPath $Directory -File | Sort-Object Name | ForEach-Object {
        [pscustomobject]@{
            file = $_.FullName
            key = "$Prefix/$($_.Name)"
            size = $_.Length
            contentType = Get-ImageContentType $_.FullName
        }
    }
}

$artistFiles = @(Get-ChildItem -LiteralPath $artistDirectory -File | Sort-Object Name)
$playlistFiles = @(Get-ChildItem -LiteralPath $playlistDirectory -File | Sort-Object Name)
$bannerFiles = @(Get-ChildItem -LiteralPath $bannerDirectory -File | Sort-Object Name)
$artistFilesByName = @{}
foreach ($artistFile in $artistFiles) {
    $normalizedName = Get-NormalizedArtistName ([System.IO.Path]::GetFileNameWithoutExtension($artistFile.Name))
    if (-not $normalizedName) { continue }
    if (-not $artistFilesByName.ContainsKey($normalizedName)) {
        $artistFilesByName[$normalizedName] = [System.Collections.Generic.List[object]]::new()
    }
    $artistFilesByName[$normalizedName].Add($artistFile)
}
$legacyAvatarFilesByArtist = @{}
$artistSqlPath = Join-Path $PSScriptRoot '..\sql\vibe_music.sql'
if (Test-Path -LiteralPath $artistSqlPath) {
    $artistInsertPattern = [regex]::new(
        '^INSERT INTO `tb_artist` VALUES \(\d+,\s*''((?:\\.|[^''])*)'',\s*(?:NULL|\d+),\s*''((?:\\.|[^''])*)''',
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    $escapedBackslash = [string][char]92 + [string][char]92
    $escapedQuote = [string][char]92 + [string][char]39
    foreach ($line in Get-Content -LiteralPath $artistSqlPath) {
        $match = $artistInsertPattern.Match($line)
        if (-not $match.Success) { continue }
        $artistName = $match.Groups[1].Value.Replace($escapedQuote, [string][char]39).Replace($escapedBackslash, [string][char]92)
        $avatarFileName = [System.IO.Path]::GetFileName($match.Groups[2].Value)
        $normalizedName = Get-NormalizedArtistName $artistName
        if (-not $normalizedName -or -not (Test-Path -LiteralPath (Join-Path $artistDirectory $avatarFileName) -PathType Leaf)) { continue }
        if (-not $legacyAvatarFilesByArtist.ContainsKey($normalizedName)) {
            $legacyAvatarFilesByArtist[$normalizedName] = [System.Collections.Generic.List[string]]::new()
        }
        $legacyAvatarFilesByArtist[$normalizedName].Add($avatarFileName)
    }
}
$artistCounts = [ordered]@{}
$songs = [System.Collections.Generic.List[object]]::new()

foreach ($sourceSong in $manifest.Songs) {
    $artist = if ($sourceSong.Artist) { $sourceSong.Artist.Trim() } else { $unknownArtistLabel }
    $title = if ($sourceSong.Title) { $sourceSong.Title.Trim() } else { [System.IO.Path]::GetFileNameWithoutExtension($sourceSong.File) }
    $album = if ($sourceSong.Album) {
        $sourceSong.Album.Trim()
    } elseif ($sourceSong.Artist) {
        "$artist $singleTrackLabel"
    } else {
        $unknownAlbumLabel
    }
    $embeddedCover = $embeddedCoversBySong[$sourceSong.File]
    $song = [pscustomobject]@{
        file = Join-Path (Join-Path $DataRoot 'songs') $sourceSong.File
        artist = $artist
        title = $title
        album = $album
        genre = $sourceSong.Genre
        durationSeconds = if ($sourceSong.DurationSeconds) { $sourceSong.DurationSeconds } else { 0 }
        releaseDate = if ($sourceSong.Year -and $sourceSong.Year -ge 1000 -and $sourceSong.Year -le 9999) { '{0:D4}-01-01' -f $sourceSong.Year } else { $sourceSong.LastWriteDate }
        coverKey = if ($embeddedCover) { $embeddedCover.key } else { '' }
    }
    $songs.Add($song)
    if (-not $artistCounts.Contains($artist)) { $artistCounts[$artist] = 0 }
    $artistCounts[$artist]++
}

$artistNames = @($artistCounts.Keys)
$artists = [System.Collections.Generic.List[object]]::new()
foreach ($artistName in $artistNames) {
    $normalizedName = Get-NormalizedArtistName $artistName
    $legacyFiles = @()
    if ($legacyAvatarFilesByArtist.ContainsKey($normalizedName)) {
        $legacyFiles = $legacyAvatarFilesByArtist[$normalizedName].ToArray()
    }
    $matchingFiles = @()
    if ($artistFilesByName.ContainsKey($normalizedName)) {
        $matchingFiles = $artistFilesByName[$normalizedName].ToArray()
    }
    $avatarKey = ''
    if ($legacyFiles.Count -eq 1) {
        $avatarKey = "artists/$($legacyFiles[0])"
    } elseif ($legacyFiles.Count -eq 0 -and $matchingFiles.Count -eq 1) {
        $avatarKey = "artists/$($matchingFiles[0].Name)"
    }
    $artists.Add([pscustomobject]@{
        name = $artistName
        avatarKey = $avatarKey
    })
}

$rankedArtists = @($artists | Sort-Object @{ Expression = { $artistCounts[$_.name] }; Descending = $true })
$playlistCount = [math]::Min([math]::Max($playlistFiles.Count, 8), [math]::Max($rankedArtists.Count, 1))
$playlists = [System.Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $playlistCount; $index++) {
    $artist = $rankedArtists[$index]
    $coverKey = if ($playlistFiles.Count) { "playlists/$($playlistFiles[$index % $playlistFiles.Count].Name)" } else { '' }
    $playlists.Add([pscustomobject]@{
        title = "$($artist.name) Picks"
        introduction = "Local collection for $($artist.name)"
        style = ''
        coverKey = $coverKey
        artist = $artist.name
    })
}

$mediaItems = [System.Collections.Generic.List[object]]::new()
foreach ($song in $songs) {
    $songFile = Get-Item -LiteralPath $song.file
    $mediaItems.Add([pscustomobject]@{
        file = $songFile.FullName
        key = "songs/$($songFile.Name)"
        size = $songFile.Length
        contentType = switch ($songFile.Extension.ToLowerInvariant()) {
            '.flac' { 'audio/flac' }
            '.mp3' { 'audio/mpeg' }
            '.m4a' { 'audio/mp4' }
            default { 'application/octet-stream' }
        }
    })
}
foreach ($cover in $coverExtraction.covers) {
    $mediaItems.Add([pscustomobject]@{
        file = $cover.file
        key = $cover.key
        size = $cover.size
        contentType = $cover.contentType
    })
}
$mediaItems.AddRange([object[]]@(Get-MediaItems $artistDirectory 'artists'))
$mediaItems.AddRange([object[]]@(Get-MediaItems $playlistDirectory 'playlists'))
$mediaItems.AddRange([object[]]@(Get-MediaItems $bannerDirectory 'banners'))

$plan = [pscustomobject]@{
    sourceRoot = (Resolve-Path $DataRoot).Path
    generatedAt = (Get-Date).ToString('o')
    songs = @($songs)
    artists = @($artists)
    playlists = @($playlists)
    banners = @($bannerFiles | ForEach-Object { [pscustomobject]@{ key = "banners/$($_.Name)" } })
    mediaItems = @($mediaItems)
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Path (Split-Path $resolvedOutput -Parent) -Force | Out-Null
$planJson = ConvertTo-Json -InputObject $plan -Depth 8
[System.IO.File]::WriteAllText($resolvedOutput, $planJson, [System.Text.UTF8Encoding]::new($true))

$totalBytes = ($mediaItems | Measure-Object -Property size -Sum).Sum
[pscustomobject]@{
    Output = $resolvedOutput
    Songs = $songs.Count
    Artists = $artists.Count
    Playlists = $playlists.Count
    Banners = $bannerFiles.Count
    MediaObjects = $mediaItems.Count
    TotalGB = [math]::Round($totalBytes / 1GB, 2)
    EmbeddedCovers = $coverExtraction.extractedCovers
    SongsWithoutEmbeddedCover = $coverExtraction.missingCovers
    MissingArtistTags = @($manifest.Songs | Where-Object { -not $_.Artist }).Count
    MissingTitleTags = @($manifest.Songs | Where-Object { -not $_.Title }).Count
}