import test from 'node:test'
import assert from 'node:assert/strict'
import { sanitizeImageUrl } from '../src/sourceImport.ts'

/**
 * 注意：这里**刻意没有 JSON 解析的用例**。
 *
 * 书源集合的解析（含顶层数组、`{data}`/`{sources}`/`{bookSources}`/`{list}` 包装、
 * 单条书源对象、UTF-8 BOM、以及一行一条的 NDJSON）统一由服务端的
 * `SourceCodec.parseSourceList` 负责，覆盖见 `server/src/test/.../SourceCodecTest.kt`。
 * 前端原先那份实现已删除，避免两处口径漂移（同一文件「本地能导、网络报无效」）。
 */
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
