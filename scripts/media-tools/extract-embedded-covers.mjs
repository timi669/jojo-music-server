import { createHash } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseFile } from 'music-metadata'

const [manifestPath, sourceRoot, outputDirectory, mappingPath] = process.argv.slice(2)
if (!manifestPath || !sourceRoot || !outputDirectory || !mappingPath) {
  throw new Error('Usage: node extract-embedded-covers.mjs <manifest.json> <data-root> <output-directory> <mapping.json>')
}

const manifest = JSON.parse((await readFile(manifestPath, 'utf8')).replace(/^\uFEFF/, ''))
const songsDirectory = path.resolve(sourceRoot, 'songs')
const resolvedOutputDirectory = path.resolve(outputDirectory)
const extensionByMime = new Map([
  ['image/jpeg', '.jpg'],
  ['image/jpg', '.jpg'],
  ['image/png', '.png'],
  ['image/webp', '.webp'],
  ['image/gif', '.gif'],
  ['image/bmp', '.bmp'],
])

function getImageExtension(format) {
  const mime = String(format ?? '').split(';', 1)[0].trim().toLowerCase()
  return extensionByMime.get(mime)
}

await mkdir(resolvedOutputDirectory, { recursive: true })
const covers = []
const missing = []

for (const song of manifest.Songs ?? []) {
  const sourcePath = path.resolve(songsDirectory, song.File)
  const relativePath = path.relative(songsDirectory, sourcePath)
  if (relativePath.startsWith('..') || path.isAbsolute(relativePath)) {
    throw new Error(`Song path escapes the songs directory: ${song.File}`)
  }

  try {
    const metadata = await parseFile(sourcePath)
    const pictures = metadata.common.picture ?? []
    const picture = pictures.find(item => String(item.type ?? '').toLowerCase().includes('front')) ?? pictures[0]
    if (!picture?.data?.length) {
      missing.push({ file: song.File, reason: 'no-embedded-picture' })
      continue
    }

    const extension = getImageExtension(picture.format)
    if (!extension) {
      missing.push({ file: song.File, reason: `unsupported-picture-format:${picture.format ?? 'unknown'}` })
      continue
    }

    const id = createHash('sha256').update(song.File, 'utf8').digest('hex').slice(0, 20)
    const name = `${id}${extension}`
    const coverFile = path.join(resolvedOutputDirectory, name)
    await writeFile(coverFile, picture.data)
    covers.push({
      sourceFile: song.File,
      file: coverFile,
      key: `songCovers/embedded/${name}`,
      size: picture.data.length,
      contentType: String(picture.format).split(';', 1)[0].trim().toLowerCase(),
      pictureType: picture.type ?? '',
    })
  } catch (error) {
    missing.push({ file: song.File, reason: `metadata-read-failed:${error.message}` })
  }
}

const result = {
  sourceRoot: path.resolve(sourceRoot),
  generatedAt: new Date().toISOString(),
  totalSongs: (manifest.Songs ?? []).length,
  extractedCovers: covers.length,
  missingCovers: missing.length,
  covers,
  mediaItems: covers,
  missing,
}
await mkdir(path.dirname(path.resolve(mappingPath)), { recursive: true })
await writeFile(mappingPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8')
console.log(JSON.stringify({
  totalSongs: result.totalSongs,
  extractedCovers: result.extractedCovers,
  missingCovers: result.missingCovers,
  mapping: path.resolve(mappingPath),
}))