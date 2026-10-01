param(
    [string]$DataRoot = 'D:\BaiduNetdiskDownload\vibe-music-data',
    [string]$ManifestPath = (Join-Path $PSScriptRoot '..\target\personal-library-manifest.json'),
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\target\personal-library-plan.json')
)

$ErrorActionPreference = 'Stop'
$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
$songCoverDirectory = Join-Path $DataRoot 'songCovers'
$artistDirectory = Join-Path $DataRoot 'artists'
$playlistDirectory = Join-Path $DataRoot 'playlists'
$bannerDirectory = Join-Path $DataRoot 'banners'

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

$coverFiles = @(Get-ChildItem -LiteralPath $songCoverDirectory -File | Sort-Object Name)
$artistFiles = @(Get-ChildItem -LiteralPath $artistDirectory -File | Sort-Object Name)
$playlistFiles = @(Get-ChildItem -LiteralPath $playlistDirectory -File | Sort-Object Name)
$bannerFiles = @(Get-ChildItem -LiteralPath $bannerDirectory -File | Sort-Object Name)
$artistCounts = [ordered]@{}
$songs = [System.Collections.Generic.List[object]]::new()
$songIndex = 0

foreach ($sourceSong in $manifest.Songs) {
    $artist = if ($sourceSong.Artist) { $sourceSong.Artist.Trim() } else { '未知歌手' }
    $title = if ($sourceSong.Title) { $sourceSong.Title.Trim() } else { [System.IO.Path]::GetFileNameWithoutExtension($sourceSong.File) }
    $album = if ($sourceSong.Album) { $sourceSong.Album.Trim() } else { "$artist 单曲" }
    $song = [pscustomobject]@{
        file = Join-Path (Join-Path $DataRoot 'songs') $sourceSong.File
        artist = $artist
        title = $title
        album = $album
        genre = $sourceSong.Genre
        durationSeconds = if ($sourceSong.DurationSeconds) { $sourceSong.DurationSeconds } else { 0 }
        releaseDate = if ($sourceSong.Year -and $sourceSong.Year -ge 1000 -and $sourceSong.Year -le 9999) { '{0:D4}-01-01' -f $sourceSong.Year } else { $sourceSong.LastWriteDate }
        coverKey = if ($coverFiles.Count) { "songCovers/$($coverFiles[$songIndex % $coverFiles.Count].Name)" } else { '' }
    }
    $songs.Add($song)
    if (-not $artistCounts.Contains($artist)) { $artistCounts[$artist] = 0 }
    $artistCounts[$artist]++
    $songIndex++
}

$artistNames = @($artistCounts.Keys)
$artists = [System.Collections.Generic.List[object]]::new()
$artistIndex = 0
foreach ($artistName in $artistNames) {
    $artists.Add([pscustomobject]@{
        name = $artistName
        avatarKey = if ($artistFiles.Count) { "artists/$($artistFiles[$artistIndex % $artistFiles.Count].Name)" } else { '' }
    })
    $artistIndex++
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
$mediaItems.AddRange([object[]]@(Get-MediaItems $songCoverDirectory 'songCovers'))
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
$plan | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $resolvedOutput -Encoding UTF8

$totalBytes = ($mediaItems | Measure-Object -Property size -Sum).Sum
[pscustomobject]@{
    Output = $resolvedOutput
    Songs = $songs.Count
    Artists = $artists.Count
    Playlists = $playlists.Count
    Banners = $bannerFiles.Count
    MediaObjects = $mediaItems.Count
    TotalGB = [math]::Round($totalBytes / 1GB, 2)
    MissingArtistTags = @($manifest.Songs | Where-Object { -not $_.Artist }).Count
    MissingTitleTags = @($manifest.Songs | Where-Object { -not $_.Title }).Count
}