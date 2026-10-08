import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseFile } from 'music-metadata'

const [manifestPath, sourceRoot, outputPath] = process.argv.slice(2)
if (!manifestPath || !sourceRoot || !outputPath) {
  throw new Error('Usage: node build-artist-credit-catalog.mjs <manifest.json> <data-root> <output.json>')
}

const manifest = JSON.parse((await readFile(manifestPath, 'utf8')).replace(/^\uFEFF/, ''))
const songsDirectory = path.resolve(sourceRoot, 'songs')
const resolvedOutputPath = path.resolve(outputPath)
const artistCatalog = new Map()
const tracks = []
const errors = []

function normalizeArtistName(name) {
  return name.normalize('NFKC').trim().replace(/\s+/gu, ' ').toLocaleLowerCase()
}

function reviewReason(name) {
  if (/[、，,;；/]/u.test(name) || /\b(?:feat\.?|ft\.?|featuring|with)\b/iu.test(name)) {
    return 'possible-combined-credit'
  }
  return ''
}

function stringValues(value) {
  return (Array.isArray(value) ? value : [value])
    .filter(item => typeof item === 'string')
    .map(item => item.trim())
    .filter(Boolean)
}

for (const song of manifest.Songs ?? []) {
  const sourcePath = path.resolve(songsDirectory, song.File)
  const relativePath = path.relative(songsDirectory, sourcePath)
  if (relativePath.startsWith('..') || path.isAbsolute(relativePath)) {
    throw new Error(`Song path escapes the songs directory: ${song.File}`)
  }

  try {
    const metadata = await parseFile(sourcePath, { duration: false, skipCovers: true })
    const rawArtists = (metadata.common.artists ?? []).map(value => value.trim()).filter(Boolean)
    const rawArtist = metadata.common.artist?.trim() ?? ''
    const nativeArtistTags = Object.entries(metadata.native ?? {}).flatMap(([format, tags]) =>
      tags
        .filter(tag => /artist|performer|^TPE1$|^TPE2$|^TMCL$|^TIPL$/i.test(tag.id ?? ''))
        .map(tag => ({ format, id: tag.id, value: tag.value }))
    )
    const id3LeadArtists = nativeArtistTags
      .filter(tag => tag.id.toUpperCase() === 'TPE1')
      .flatMap(tag => stringValues(tag.value))
    const vorbisArtistValues = nativeArtistTags
      .filter(tag => tag.id.toUpperCase() === 'ARTIST')
      .flatMap(tag => stringValues(tag.value))
    const credits = id3LeadArtists.length
      ? [...new Set(id3LeadArtists)]
      : vorbisArtistValues.length
        ? [...new Set(vorbisArtistValues)]
        : rawArtists.length
          ? [...new Set(rawArtists)]
          : rawArtist
            ? [rawArtist]
            : []
    const creditSource = id3LeadArtists.length
      ? 'id3-tpe1'
      : vorbisArtistValues.length > 1
        ? 'vorbis-multiple-artist-tags'
        : vorbisArtistValues.length
          ? 'vorbis-artist-tag'
          : rawArtists.length
            ? 'common-artists'
            : rawArtist
              ? 'common-artist'
              : 'missing'
    const structuredMultipleCredits = id3LeadArtists.length > 1 || vorbisArtistValues.length > 1 || rawArtists.length > 1
    const trackReasons = [...new Set(credits.map(reviewReason).filter(Boolean))]

    for (const name of credits) {
      const key = normalizeArtistName(name)
      if (!key) continue
      const entry = artistCatalog.get(key) ?? {
        name,
        normalizedName: key,
        trackCount: 0,
        structuredCreditCount: 0,
        reviewRequired: false,
        reviewReasons: new Set(),
        sourceFiles: [],
      }
      entry.trackCount++
      if (structuredMultipleCredits) entry.structuredCreditCount++
      if (reviewReason(name)) {
        entry.reviewRequired = true
        entry.reviewReasons.add(reviewReason(name))
      }
      entry.sourceFiles.push(song.File)
      artistCatalog.set(key, entry)
    }

    tracks.push({
      file: song.File,
      title: metadata.common.title ?? song.Title ?? '',
      album: metadata.common.album ?? song.Album ?? '',
      rawArtist,
      rawArtists,
      nativeArtistTags,
      credits,
      creditSource,
      isrc: stringValues(metadata.common.isrc),
      musicBrainzRecordingIds: stringValues(metadata.common.musicbrainz_recordingid),
      musicBrainzArtistIds: stringValues(metadata.common.musicbrainz_artistid),
      structuredMultipleCredits,
      reviewReasons: trackReasons,
    })
  } catch (error) {
    errors.push({ file: song.File, error: error.message })
    tracks.push({
      file: song.File,
      rawArtist: '',
      rawArtists: [],
      credits: [],
      creditSource: 'metadata-read-failed',
      structuredMultipleCredits: false,
      reviewReasons: ['metadata-read-failed'],
    })
  }
}

const artists = [...artistCatalog.values()]
  .map(entry => ({
    ...entry,
    reviewReasons: [...entry.reviewReasons],
  }))
  .sort((left, right) => right.trackCount - left.trackCount || left.name.localeCompare(right.name))
const result = {
  sourceRoot: path.resolve(sourceRoot),
  generatedAt: new Date().toISOString(),
  totalTracks: tracks.length,
  uniqueArtistCredits: artists.length,
  tracksWithStructuredMultipleCredits: tracks.filter(track => track.structuredMultipleCredits).length,
  tracksRequiringReview: tracks.filter(track => track.reviewReasons.length > 0).length,
  tracksWithoutArtistCredit: tracks.filter(track => track.credits.length === 0).length,
  metadataReadErrors: errors.length,
  artists,
  tracks,
  errors,
}

await mkdir(path.dirname(resolvedOutputPath), { recursive: true })
await writeFile(resolvedOutputPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8')
console.log(JSON.stringify({
  totalTracks: result.totalTracks,
  uniqueArtistCredits: result.uniqueArtistCredits,
  tracksWithStructuredMultipleCredits: result.tracksWithStructuredMultipleCredits,
  tracksRequiringReview: result.tracksRequiringReview,
  tracksWithoutArtistCredit: result.tracksWithoutArtistCredit,
  metadataReadErrors: result.metadataReadErrors,
  output: resolvedOutputPath,
}))