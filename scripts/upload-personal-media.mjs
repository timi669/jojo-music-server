import { createHash, createHmac } from 'node:crypto'
import { createReadStream, existsSync, readFileSync } from 'node:fs'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const [planPath, configPath, mode = 'dry-run'] = process.argv.slice(2)
if (!planPath || !configPath) {
  throw new Error('Usage: node upload-personal-media.mjs <plan.json> <application-local.yml> [apply|resume|dry-run]')
}

const plan = JSON.parse(readFileSync(planPath, 'utf8').replace(/^\uFEFF/, ''))
const items = plan.mediaItems ?? plan.items
if (!Array.isArray(items)) throw new Error('Plan must contain a mediaItems array')
const config = readFileSync(configPath, 'utf8')
const configValue = (pattern, label) => {
  const match = config.match(pattern)
  if (!match) throw new Error(`Missing ${label} in local server config`)
  return match[1].trim()
}

const endpoint = configValue(/^\s{2}endpoint:\s*["']?([^"'\r\n]+)["']?\s*$/m, 'minio.endpoint').replace(/\/$/, '')
const accessKey = configValue(/^\s{2}accessKey:\s*["']?([^"'\r\n]+)["']?\s*$/m, 'minio.accessKey')
const secretKey = configValue(/^\s{2}secretKey:\s*["']?([^"'\r\n]+)["']?\s*$/m, 'minio.secretKey')
const bucket = mode === 'remove-empty-legacy-bucket' ? 'vibe-music-data' : 'vibe-music-personal'
const endpointUrl = new URL(endpoint)
const region = 'us-east-1'
const emptyHash = createHash('sha256').update('').digest('hex')

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

function hmac(key, value, encoding) {
  return createHmac('sha256', key).update(value).digest(encoding)
}

function awsEncode(value) {
  return encodeURIComponent(value).replace(/[!'()*]/g, character =>
    `%${character.charCodeAt(0).toString(16).toUpperCase()}`
  )
}

function canonicalPath(key = '') {
  const parts = [bucket, ...key.split('/').filter(Boolean)]
  return `/${parts.map(awsEncode).join('/')}`
}

function sign(method, key, bodyHash, query = '') {
  const now = new Date()
  const amzDate = now.toISOString().replace(/[:-]|\.\d{3}/g, '')
  const dateStamp = amzDate.slice(0, 8)
  const canonicalUri = canonicalPath(key)
  const canonicalHeaders = `host:${endpointUrl.host}\nx-amz-content-sha256:${bodyHash}\nx-amz-date:${amzDate}\n`
  const signedHeaders = 'host;x-amz-content-sha256;x-amz-date'
  const canonicalRequest = [method, canonicalUri, query, canonicalHeaders, signedHeaders, bodyHash].join('\n')
  const scope = `${dateStamp}/${region}/s3/aws4_request`
  const stringToSign = `AWS4-HMAC-SHA256\n${amzDate}\n${scope}\n${sha256(canonicalRequest)}`
  const dateKey = hmac(`AWS4${secretKey}`, dateStamp)
  const regionKey = hmac(dateKey, region)
  const serviceKey = hmac(regionKey, 's3')
  const signingKey = hmac(serviceKey, 'aws4_request')
  const signature = hmac(signingKey, stringToSign, 'hex')

  return {
    url: `${endpoint}${canonicalUri}${query ? `?${query}` : ''}`,
    headers: {
      Authorization: `AWS4-HMAC-SHA256 Credential=${accessKey}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`,
      'x-amz-content-sha256': bodyHash,
      'x-amz-date': amzDate,
    },
  }
}

async function request(method, key, body, bodyHash, query = '', contentType) {
  const signed = sign(method, key, bodyHash, query)
  const headers = { ...signed.headers }
  if (contentType) headers['Content-Type'] = contentType
  const response = await fetch(signed.url, {
    method,
    headers,
    body,
    duplex: body && typeof body.pipe === 'function' ? 'half' : undefined,
    signal: AbortSignal.timeout(5 * 60 * 1000),
  })
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 500)
    throw new Error(`${method} ${key || bucket} failed (${response.status}): ${detail}`)
  }
  return response
}

async function hashFile(filePath) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(filePath)) hash.update(chunk)
  return hash.digest('hex')
}

async function ensureBucket(resume) {
  const url = `${endpoint}/${bucket}`
  const head = sign('HEAD', '', emptyHash)
  const existing = await fetch(head.url, {
    method: 'HEAD',
    headers: head.headers,
    signal: AbortSignal.timeout(15000),
  })
  if (existing.ok) {
    if (!resume) throw new Error(`Bucket ${bucket} already exists; use resume mode to continue an interrupted import`)
    return
  }
  if (existing.status !== 404) {
    throw new Error(`Unable to check personal bucket (${existing.status})`)
  }
  const created = await request('PUT', '', null, emptyHash)
  await created.arrayBuffer()
  void url
}

function contentType(item) {
  const extension = path.extname(item.file).toLowerCase()
  if (extension === '.flac') return 'audio/flac'
  if (extension === '.mp3') return 'audio/mpeg'
  if (extension === '.m4a') return 'audio/mp4'
  if (extension === '.png') return 'image/png'
  if (extension === '.jpg' || extension === '.jpeg') return 'image/jpeg'
  if (extension === '.webp') return 'image/webp'
  const buffer = readFileSync(item.file, { flag: 'r' })
  if (buffer.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) return 'image/png'
  if (buffer[0] === 255 && buffer[1] === 216) return 'image/jpeg'
  return 'application/octet-stream'
}

async function uploadItem(item, index) {
  if (!existsSync(item.file)) throw new Error(`Source media missing: ${item.file}`)
  const bodyHash = await hashFile(item.file)
  const contentLength = (await import('node:fs/promises')).stat(item.file).then(stat => stat.size)
  const headers = { 'Content-Type': contentType(item), 'Content-Length': String(await contentLength) }
  const signed = sign('PUT', item.key, bodyHash)
  Object.assign(headers, signed.headers)
  const response = await fetch(signed.url, {
    method: 'PUT',
    headers,
    body: createReadStream(item.file),
    duplex: 'half',
    signal: AbortSignal.timeout(5 * 60 * 1000),
  })
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 500)
    throw new Error(`Upload ${item.key} failed (${response.status}): ${detail}`)
  }
  await response.arrayBuffer()
  if (index % 50 === 0 || index === items.length) {
    console.log(`Uploaded ${index}/${items.length}: ${item.key}`)
  }
}

async function setPublicReadPolicy() {
  const policy = JSON.stringify({
    Version: '2012-10-17',
    Statement: [{
      Effect: 'Allow',
      Principal: { AWS: ['*'] },
      Action: ['s3:GetObject'],
      Resource: [`arn:aws:s3:::${bucket}/*`],
    }],
  })
  const body = Buffer.from(policy)
  const query = 'policy='
  const response = await request('PUT', '', body, sha256(body), query, 'application/json')
  await response.arrayBuffer()
}

if (mode === 'dry-run') {
  const missing = items.filter(item => !existsSync(item.file))
  if (missing.length) throw new Error(`${missing.length} media files are missing from the source directory`)
  const bytes = items.reduce((total, item) => total + item.size, 0)
  console.log(JSON.stringify({ bucket, objects: items.length, bytes, mode }, null, 2))
  process.exit(0)
}

if (mode === 'remove-empty-legacy-bucket') {
  const parameters = new URLSearchParams({ 'list-type': '2', 'max-keys': '1' })
  parameters.sort()
  const query = parameters.toString().replace(/\+/g, '%20')
  const listing = await request('GET', '', null, emptyHash, query)
  const body = await listing.text()
  const objectCount = [...body.matchAll(/<Contents>/g)].length
  if (objectCount !== 0 || /<IsTruncated>true<\/IsTruncated>/.test(body)) {
    throw new Error(`Refusing to delete non-empty bucket ${bucket}`)
  }

  const deleted = await request('DELETE', '', null, emptyHash)
  await deleted.arrayBuffer()
  const head = sign('HEAD', '', emptyHash)
  const remaining = await fetch(head.url, { method: 'HEAD', headers: head.headers })
  if (remaining.status !== 404) throw new Error(`Bucket ${bucket} still exists after deletion`)
  console.log(JSON.stringify({ bucket, objectsBeforeDelete: 0, deleted: true, mode }, null, 2))
  process.exit(0)
}

if (mode === 'verify') {
  let continuationToken
  const actualItems = new Map()
  do {
    const parameters = new URLSearchParams({ 'list-type': '2', 'max-keys': '1000' })
    if (continuationToken) parameters.set('continuation-token', continuationToken)
    parameters.sort()
    const query = parameters.toString().replace(/\+/g, '%20')
    const response = await request('GET', '', null, emptyHash, query)
    const body = await response.text()
    const xmlUnescape = value => value
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'")
      .replace(/&#39;/g, "'")
      .replace(/&#x27;/gi, "'")
      .replace(/&#(\d+);/g, (_, decimal) => String.fromCodePoint(Number(decimal)))
      .replace(/&#x([\da-f]+);/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
      .replace(/&amp;/g, '&')
    for (const [, entry] of body.matchAll(/<Contents>(.*?)<\/Contents>/gs)) {
      const key = entry.match(/<Key>(.*?)<\/Key>/s)?.[1]
      const size = entry.match(/<Size>(\d+)<\/Size>/)?.[1]
      if (key && size) actualItems.set(xmlUnescape(key), Number(size))
    }
    const tokenMatch = body.match(/<NextContinuationToken>(.*?)<\/NextContinuationToken>/)
    continuationToken = tokenMatch ? xmlUnescape(tokenMatch[1]) : undefined
  } while (continuationToken)

  const expectedItems = new Map(items.map(item => [item.key, item.size]))
  const missing = [...expectedItems.keys()].filter(key => !actualItems.has(key))
  const unexpected = [...actualItems.keys()].filter(key => !expectedItems.has(key))
  const sizeMismatches = [...expectedItems]
    .filter(([key, size]) => actualItems.has(key) && actualItems.get(key) !== size)
    .map(([key, expectedBytes]) => ({ key, expectedBytes, actualBytes: actualItems.get(key) }))
  const bytes = [...actualItems.values()].reduce((total, size) => total + size, 0)
  const expectedBytes = items.reduce((total, item) => total + item.size, 0)
  const result = {
    bucket,
    objects: actualItems.size,
    expectedObjects: items.length,
    bytes,
    expectedBytes,
    missing: missing.slice(0, 10),
    unexpected: unexpected.slice(0, 10),
    sizeMismatches: sizeMismatches.slice(0, 10),
    mode,
  }
  console.log(JSON.stringify(result, null, 2))
  if (actualItems.size !== items.length || missing.length || unexpected.length || sizeMismatches.length) process.exitCode = 1
  process.exit()
}

if (!['apply', 'resume'].includes(mode)) throw new Error(`Unknown mode: ${mode}`)
await ensureBucket(mode === 'resume')
await setPublicReadPolicy()

let completed = 0
const workers = Array.from({ length: 3 }, async () => {
  while (completed < items.length) {
    const index = completed++
    await uploadItem(items[index], index + 1)
    if (index % 20 === 0) await delay(5)
  }
})
await Promise.all(workers)
console.log(`Uploaded ${items.length} objects to ${bucket}; anonymous read is enabled for local browser playback.`)