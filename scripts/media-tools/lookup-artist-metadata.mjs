import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

const [catalogPath, outputPath, limitArgument] = process.argv.slice(2)
if (!catalogPath || !outputPath) {
  throw new Error('Usage: node lookup-artist-metadata.mjs <artist-catalog.json> <output.json> [limit]')
}

const catalog = JSON.parse((await readFile(catalogPath, 'utf8')).replace(/^\uFEFF/, ''))
const resolvedOutputPath = path.resolve(outputPath)
const limit = limitArgument ? Number.parseInt(limitArgument, 10) : Number.POSITIVE_INFINITY
if (Number.isNaN(limit) || limit < 1) throw new Error('Limit must be a positive integer')

let report = {
  source: 'MusicBrainz artist search API',
  generatedAt: new Date().toISOString(),
  candidates: [],
  skippedCombinedCredits: [],
  errors: [],
}
try {
  report = JSON.parse((await readFile(resolvedOutputPath, 'utf8')).replace(/^\uFEFF/, ''))
} catch (error) {
  if (error.code !== 'ENOENT') throw error
}

const normalized = value => value.normalize('NFKC').trim().replace(/\s+/gu, ' ').toLocaleLowerCase()
const existing = new Map(report.candidates.map(candidate => [candidate.name, candidate]))
const skippedNames = new Set(report.skippedCombinedCredits.map(item => item.name))
const queryable = catalog.artists.filter(artist => !artist.reviewRequired)
const pending = queryable.filter(artist => !existing.has(artist.name))
const lookupItems = pending.slice(0, limit)

report.skippedCombinedCredits = catalog.artists
  .filter(artist => artist.reviewRequired)
  .map(artist => ({
    name: artist.name,
    trackCount: artist.trackCount,
    reason: 'combined-or-ambiguous-credit; not searched as a single artist',
  }))

function escapeLucenePhrase(value) {
  return value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')
}

function sleep(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

await mkdir(path.dirname(resolvedOutputPath), { recursive: true })
for (const [index, artist] of lookupItems.entries()) {
  const query = `artist:"${escapeLucenePhrase(artist.name)}"`
  const url = new URL('https://musicbrainz.org/ws/2/artist/')
  url.searchParams.set('query', query)
  url.searchParams.set('fmt', 'json')
  url.searchParams.set('limit', '10')

  try {
    let response
    for (let attempt = 0; attempt < 4; attempt++) {
      response = await fetch(url, {
        headers: {
          Accept: 'application/json',
          'User-Agent': 'JOJO-MUSIC-artist-catalog/1.0 (https://github.com/timi669/jojo-music-server)',
        },
        signal: AbortSignal.timeout(20000),
      })
      if (response.ok) break
      if (![429, 503].includes(response.status) || attempt === 3) {
        throw new Error(`MusicBrainz returned HTTP ${response.status}`)
      }
      const retryAfter = Number.parseInt(response.headers.get('retry-after') ?? '', 10)
      await sleep(Number.isFinite(retryAfter) ? retryAfter * 1000 : 2000 * (attempt + 1))
    }

    const data = await response.json()
    const results = (data.artists ?? []).map(item => ({
      id: item.id,
      name: item.name,
      score: item.score ?? 0,
      type: item.type ?? null,
      gender: item.gender ?? null,
      country: item.country ?? null,
      area: item.area?.name ?? null,
      beginArea: item['begin-area']?.name ?? null,
      disambiguation: item.disambiguation ?? '',
      tags: (item.tags ?? []).slice(0, 5).map(tag => tag.name),
    }))
    const exactMatches = results.filter(item => normalized(item.name) === normalized(artist.name))
    const selected = exactMatches.length === 1 && exactMatches[0].score >= 90
      ? exactMatches[0]
      : null

    report.candidates.push({
      name: artist.name,
      trackCount: artist.trackCount,
      status: selected ? 'unique-exact-candidate' : exactMatches.length ? 'ambiguous-exact-matches' : 'no-exact-match',
      selected,
      exactMatchCount: exactMatches.length,
      results,
      requiresReview: true,
    })
    report.errors = report.errors.filter(item => item.name !== artist.name)
  } catch (error) {
    report.errors = report.errors.filter(item => item.name !== artist.name)
    report.errors.push({ name: artist.name, message: error.message })
  }

  report.generatedAt = new Date().toISOString()
  await writeFile(resolvedOutputPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
  console.log(`Looked up ${index + 1}/${lookupItems.length}: ${artist.name}`)
  if (index < lookupItems.length - 1) await sleep(1100)
}

const summary = {
  queriedThisRun: lookupItems.length,
  savedCandidates: report.candidates.length,
  exactCandidateCount: report.candidates.filter(item => item.status === 'unique-exact-candidate').length,
  ambiguousCount: report.candidates.filter(item => item.status === 'ambiguous-exact-matches').length,
  noExactMatchCount: report.candidates.filter(item => item.status === 'no-exact-match').length,
  errorCount: report.errors.length,
  skippedCombinedCredits: report.skippedCombinedCredits.length,
  output: resolvedOutputPath,
}
const candidateByName = new Map(report.candidates.map(candidate => [candidate.name, candidate]))
const csvEscape = value => `"${String(value ?? '').replaceAll('"', '""')}"`
const csvRows = [
  [
    'artistName',
    'trackCount',
    'requiresManualReview',
    'lookupStatus',
    'candidateName',
    'musicBrainzId',
    'score',
    'type',
    'gender',
    'countryCode',
    'area',
    'beginArea',
    'disambiguation',
    'candidateAlternatives',
    'sourceFiles',
  ],
]
for (const artist of catalog.artists) {
  const candidate = candidateByName.get(artist.name)
  const selected = candidate?.selected
  const alternatives = candidate?.results?.map(result => ({
    id: result.id,
    name: result.name,
    score: result.score,
    type: result.type,
    gender: result.gender,
    country: result.country,
    area: result.area,
    disambiguation: result.disambiguation,
  })) ?? []
  csvRows.push([
    artist.name,
    artist.trackCount,
    artist.reviewRequired || Boolean(candidate && candidate.status !== 'unique-exact-candidate'),
    candidate?.status ?? (artist.reviewRequired ? 'combined-credit-not-looked-up' : 'lookup-pending'),
    selected?.name ?? '',
    selected?.id ?? '',
    selected?.score ?? '',
    selected?.type ?? '',
    selected?.gender ?? '',
    selected?.country ?? '',
    selected?.area ?? '',
    selected?.beginArea ?? '',
    selected?.disambiguation ?? '',
    JSON.stringify(alternatives),
    artist.sourceFiles.join(' | '),
  ])
}
const csvPath = path.join(path.dirname(resolvedOutputPath), 'artist-metadata-review.csv')
await writeFile(csvPath, `\uFEFF${csvRows.map(row => row.map(csvEscape).join(',')).join('\r\n')}\r\n`, 'utf8')
summary.reviewCsv = csvPath
console.log(JSON.stringify(summary, null, 2))