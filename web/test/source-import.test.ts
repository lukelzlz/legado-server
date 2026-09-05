import test from 'node:test'
import assert from 'node:assert/strict'
import { extractSourcesFromRaw, parseSourceJsonText, sanitizeImageUrl } from '../src/sourceImport.ts'

test('Source Import - extractSourcesFromRaw handles direct array', () => {
  const sources = [
    { bookSourceName: '源1', bookSourceUrl: 'https://s1.test' },
    { bookSourceName: '源2', bookSourceUrl: 'https://s2.test' },
  ]
  const extracted = extractSourcesFromRaw(sources)
  assert.equal(extracted.length, 2)
  assert.equal((extracted[0] as any).bookSourceName, '源1')
})

test('Source Import - extractSourcesFromRaw handles single source object', () => {
  const single = { bookSourceName: '单源', bookSourceUrl: 'https://single.test' }
  const extracted = extractSourcesFromRaw(single)
  assert.equal(extracted.length, 1)
  assert.equal((extracted[0] as any).bookSourceName, '单源')
})

test('Source Import - extractSourcesFromRaw unwraps data, sources, bookSources, and list envelopes', () => {
  const withData = { code: 200, data: [{ bookSourceName: 'Data源', bookSourceUrl: 'https://data.test' }] }
  assert.equal(extractSourcesFromRaw(withData).length, 1)

  const withSources = { sources: [{ bookSourceName: 'Sources源', bookSourceUrl: 'https://sources.test' }] }
  assert.equal(extractSourcesFromRaw(withSources).length, 1)

  const withBookSources = { bookSources: [{ bookSourceName: 'BookSources源', bookSourceUrl: 'https://bs.test' }] }
  assert.equal(extractSourcesFromRaw(withBookSources).length, 1)

  const withList = { list: [{ bookSourceName: 'List源', bookSourceUrl: 'https://list.test' }] }
  assert.equal(extractSourcesFromRaw(withList).length, 1)
})

test('Source Import - extractSourcesFromRaw handles null, undefined and non-objects', () => {
  assert.deepEqual(extractSourcesFromRaw(null), [])
  assert.deepEqual(extractSourcesFromRaw(undefined), [])
  assert.deepEqual(extractSourcesFromRaw('string'), [])
  assert.deepEqual(extractSourcesFromRaw(123), [])
})

test('Source Import - parseSourceJsonText handles BOM and line-delimited JSON', () => {
  const bomJson = '\uFEFF[{"bookSourceName":"BOM源","bookSourceUrl":"https://bom.test"}]'
  const parsedBom = parseSourceJsonText(bomJson)
  assert.equal(parsedBom.length, 1)
  assert.equal((parsedBom[0] as any).bookSourceName, 'BOM源')

  const ndjson = '{"bookSourceName":"ND1","bookSourceUrl":"https://nd1.test"}\n{"bookSourceName":"ND2","bookSourceUrl":"https://nd2.test"}'
  const parsedNd = parseSourceJsonText(ndjson)
  assert.equal(parsedNd.length, 2)
  assert.equal((parsedNd[0] as any).bookSourceName, 'ND1')
  assert.equal((parsedNd[1] as any).bookSourceName, 'ND2')
})

test('Source Import - parseSourceJsonText parses shareBookSource format with custom URL', () => {
  const jsonText = JSON.stringify([
    {
      bookSourceName: '🍅大灰狼聚合5.8.20(vip完全版)',
      bookSourceUrl: '大灰狼融合VIP5.0',
      bookSourceGroup: '大灰狼聚合',
    }
  ])
  const parsed = parseSourceJsonText(jsonText)
  assert.equal(parsed.length, 1)
  assert.equal((parsed[0] as any).bookSourceUrl, '大灰狼融合VIP5.0')
  assert.equal((parsed[0] as any).bookSourceName, '🍅大灰狼聚合5.8.20(vip完全版)')
})

test('Sanitize Image URL - validates safe protocols and rejects dangerous ones', () => {
  // Valid http / https / relative
  assert.equal(sanitizeImageUrl('https://example.com/cover.jpg'), 'https://example.com/cover.jpg')
  assert.equal(sanitizeImageUrl('http://example.com/cover.png'), 'http://example.com/cover.png')
  assert.equal(sanitizeImageUrl('/api/covers/abc-123'), '/api/covers/abc-123')
  assert.equal(sanitizeImageUrl('  https://example.com/spaced.jpg  '), 'https://example.com/spaced.jpg')

  // Dangerous / invalid schemes
  assert.equal(sanitizeImageUrl('javascript:alert(1)'), null)
  assert.equal(sanitizeImageUrl('javascript:void(0)'), null)
  assert.equal(sanitizeImageUrl('data:text/html,<script>alert(1)</script>'), null)
  assert.equal(sanitizeImageUrl('vbscript:msgbox(1)'), null)
  assert.equal(sanitizeImageUrl('file:///etc/passwd'), null)

  // Empty / null / undefined
  assert.equal(sanitizeImageUrl(''), null)
  assert.equal(sanitizeImageUrl('   '), null)
  assert.equal(sanitizeImageUrl(null), null)
  assert.equal(sanitizeImageUrl(undefined), null)
})
