import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const [catalogPath, metadataPath, outputPath] = process.argv.slice(2)
if (!catalogPath || !metadataPath || !outputPath) {
  throw new Error('Usage: node suggest-artist-credit-splits.mjs <artist-catalog.json> <metadata-candidates.json> <output.csv>')
}

const readJson = async file => JSON.parse((await readFile(file, 'utf8')).replace(/^\uFEFF/, ''))
const catalog = await readJson(catalogPath)
const metadata = await readJson(metadataPath)
const normalize = name => name.normalize('NFKC').trim().replace(/\s+/gu, ' ').toLocaleLowerCase()
const confirmedSingles = new Map(
  catalog.artists.filter(artist => !artist.reviewRequired).map(artist => [normalize(artist.name), artist.name])
)
const uniqueExternalCandidates = new Map(
  metadata.candidates
    .filter(candidate => candidate.status === 'unique-exact-candidate' && candidate.selected)
    .map(candidate => [normalize(candidate.name), candidate.selected])
)

function splitCredit(value) {
  return value
    .replace(/\b(?:feat\.?|ft\.?|featuring|with)\b/giu, '|')
    .split(/[\/／,，、;；|]+/u)
    .map(part => part.trim())
    .filter(Boolean)
}

const rows = []
for (const artist of catalog.artists.filter(item => item.reviewRequired)) {
  const segments = splitCredit(artist.name)
  if (segments.length < 2) continue
  const matches = segments.map(segment => {
    const key = normalize(segment)
    const single = confirmedSingles.get(key)
    const external = uniqueExternalCandidates.get(key)
    return {
      segment,
      matchType: single ? 'present-as-single-credit' : external ? 'unique-external-candidate' : 'unverified',
      matchedName: single ?? external?.name ?? '',
      musicBrainzId: external?.id ?? '',
    }
  })
  const verifiedCount = matches.filter(match => match.matchType !== 'unverified').length
  rows.push({
    artistCredit: artist.name,
    trackCount: artist.trackCount,
    proposedSegments: segments,
    matchedSegments: matches,
    suggestionStatus: verifiedCount === segments.length
      ? 'all-segments-have-evidence-review-before-use'
      : verifiedCount > 0
        ? 'partial-evidence-manual-review'
        : 'no-segment-evidence-manual-review',
    sourceFiles: artist.sourceFiles,
  })
}

const csvEscape = value => `"${String(value ?? '').replaceAll('"', '""')}"`
const csvRows = [[
  'artistCredit',
  'trackCount',
  'proposedSegments',
  'segmentEvidence',
  'suggestionStatus',
  'sourceFiles',
]]
for (const row of rows) {
  csvRows.push([
    row.artistCredit,
    row.trackCount,
    row.proposedSegments.join(' | '),
    JSON.stringify(row.matchedSegments),
    row.suggestionStatus,
    row.sourceFiles.join(' | '),
  ])
}

const resolvedOutput = path.resolve(outputPath)
await mkdir(path.dirname(resolvedOutput), { recursive: true })
await writeFile(resolvedOutput, `\uFEFF${csvRows.map(row => row.map(csvEscape).join(',')).join('\r\n')}\r\n`, 'utf8')
console.log(JSON.stringify({
  combinedCredits: catalog.artists.filter(item => item.reviewRequired).length,
  splitSuggestions: rows.length,
  allSegmentsHaveEvidence: rows.filter(row => row.suggestionStatus === 'all-segments-have-evidence-review-before-use').length,
  partialEvidence: rows.filter(row => row.suggestionStatus === 'partial-evidence-manual-review').length,
  noSegmentEvidence: rows.filter(row => row.suggestionStatus === 'no-segment-evidence-manual-review').length,
  output: resolvedOutput,
}))