param(
    [string]$DataRoot = 'D:\BaiduNetdiskDownload\vibe-music-data',
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\target\personal-library-manifest.json')
)

$ErrorActionPreference = 'Stop'
$songDirectory = Join-Path $DataRoot 'songs'
if (-not (Test-Path $songDirectory -PathType Container)) {
    throw "Song directory not found: $songDirectory"
}

function Get-FirstTagValue([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return '' }
    return (($Value -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Select-Object -Unique | Select-Object -First 1) -join '')
}

function Get-AllTagValues([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return '' }
    return (($Value -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Select-Object -Unique) -join ',')
}

$shell = New-Object -ComObject Shell.Application
$folder = $shell.Namespace($songDirectory)
if (-not $folder) {
    throw "Unable to read song directory: $songDirectory"
}

$propertyIndexes = @{
    Artist = 13
    Album = 14
    Year = 15
    Genre = 16
    Title = 21
    Duration = 27
}
foreach ($propertyName in $propertyIndexes.Keys) {
    if (-not $folder.GetDetailsOf($null, $propertyIndexes[$propertyName])) {
        throw "Windows audio tag property unavailable: $propertyName"
    }
}

$songs = foreach ($file in Get-ChildItem $songDirectory -File | Sort-Object Name) {
    $item = $folder.ParseName($file.Name)
    $artist = Get-FirstTagValue ($folder.GetDetailsOf($item, $propertyIndexes.Artist))
    $album = Get-FirstTagValue ($folder.GetDetailsOf($item, $propertyIndexes.Album))
    $title = Get-FirstTagValue ($folder.GetDetailsOf($item, $propertyIndexes.Title))
    $genre = Get-AllTagValues ($folder.GetDetailsOf($item, $propertyIndexes.Genre))
    $yearText = Get-FirstTagValue ($folder.GetDetailsOf($item, $propertyIndexes.Year))
    $year = if ($yearText -match '\d{4}') { [int]$Matches[0] } else { $null }
    $durationText = Get-FirstTagValue ($folder.GetDetailsOf($item, $propertyIndexes.Duration))
    $durationSeconds = $null
    if ($durationText -match '^(?:(\d+):)?(\d{1,2}):(\d{2})$') {
        $hours = if ($Matches[1]) { [int]$Matches[1] } else { 0 }
        $durationSeconds = [math]::Round(($hours * 3600) + ([int]$Matches[2] * 60) + [int]$Matches[3], 2)
    }

    [pscustomobject]@{
        File = $file.Name
        LastWriteDate = $file.LastWriteTime.ToString('yyyy-MM-dd')
        FileSize = $file.Length
        Artist = $artist
        Album = $album
        Title = $title
        Genre = $genre
        Year = $year
        DurationSeconds = $durationSeconds
    }
}

$manifest = [pscustomobject]@{
    Source = (Resolve-Path $DataRoot).Path
    GeneratedAt = (Get-Date).ToString('o')
    Songs = @($songs)
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path $resolvedOutput -Parent
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $resolvedOutput -Encoding UTF8

$artistCount = @($manifest.Songs | Where-Object Artist | ForEach-Object Artist | Sort-Object -Unique).Count
[pscustomobject]@{
    Output = $resolvedOutput
    Songs = $manifest.Songs.Count
    DistinctTaggedArtists = $artistCount
    MissingArtist = @($manifest.Songs | Where-Object { -not $_.Artist }).Count
    MissingTitle = @($manifest.Songs | Where-Object { -not $_.Title }).Count
    MissingAlbum = @($manifest.Songs | Where-Object { -not $_.Album }).Count
    MissingDuration = @($manifest.Songs | Where-Object { -not $_.DurationSeconds }).Count
}