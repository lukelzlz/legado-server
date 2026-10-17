import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const SRC_DIR = path.resolve(here, '../src')
const LOCALES = ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'] as const

// ---------------------------------------------------------------------------
// The locale files under test are *imported*, not re-read from disk, so this
// suite validates exactly the resources the running app bundles.
// ---------------------------------------------------------------------------
import zhCN from '../src/i18n/locales/zh-CN.json'
import zhTW from '../src/i18n/locales/zh-TW.json'
import enUS from '../src/i18n/locales/en-US.json'
import jaJP from '../src/i18n/locales/ja-JP.json'

const RESOURCES: Record<string, unknown> = { 'zh-CN': zhCN, 'zh-TW': zhTW, 'en-US': enUS, 'ja-JP': jaJP }

/** Flatten {"shelf":{"novelTag":"小说"}} into {"shelf.novelTag":"小说"}. */
function flatten(obj: unknown, prefix = '', out: Record<string, string> = {}): Record<string, string> {
  if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
    for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
      flatten(v, prefix ? `${prefix}.${k}` : k, out)
    }
  } else if (typeof obj === 'string') {
    out[prefix] = obj
  }
  return out
}

function walkSourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walkSourceFiles(full, out)
    else if (/\.tsx?$/.test(entry.name)) out.push(full)
  }
  return out
}

/**
 * Return the index of the ')' matching the '(' at `openIdx`, ignoring
 * parentheses that appear inside strings, template literals or comments.
 */
function matchParen(text: string, openIdx: number): number {
  let depth = 0
  for (let i = openIdx; i < text.length; i++) {
    const c = text[i]
    if (c === '/' && text[i + 1] === '/') {
      while (i < text.length && text[i] !== '\n') i++
      continue
    }
    if (c === '/' && text[i + 1] === '*') {
      i = text.indexOf('*/', i + 2) + 1
      continue
    }
    if (c === '"' || c === "'" || c === '`') {
      const quote = c
      i++
      while (i < text.length && text[i] !== quote) {
        if (text[i] === '\\') i++
        i++
      }
      continue
    }
    if (c === '(') depth++
    else if (c === ')') {
      depth--
      if (depth === 0) return i
    }
  }
  return -1
}

/** Split a call's argument text on top-level commas (string/bracket aware). */
function splitArgs(argText: string): string[] {
  const parts: string[] = []
  let depth = 0
  let cur = ''
  for (let i = 0; i < argText.length; i++) {
    const c = argText[i]
    if (c === '"' || c === "'" || c === '`') {
      const quote = c
      cur += c
      i++
      while (i < argText.length && argText[i] !== quote) {
        if (argText[i] === '\\') {
          cur += argText[i]
          i++
        }
        cur += argText[i]
        i++
      }
      cur += quote
      continue
    }
    if (c === '(' || c === '[' || c === '{') depth++
    if (c === ')' || c === ']' || c === '}') depth--
    if (c === ',' && depth === 0) {
      parts.push(cur)
      cur = ''
      continue
    }
    cur += c
  }
  if (cur.trim()) parts.push(cur)
  return parts.map(s => s.trim())
}

/** Unwrap a single- or double-quoted string literal into its raw value. */
function literalString(src: string | undefined): string | null {
  if (!src) return null
  const m = /^(['"])((?:\\.|(?!\1)[^\\])*)\1$/.exec(src.trim())
  if (!m) return null
  return m[2].replace(/\\(['"\\])/g, '$1')
}

/**
 * Collect every literal (non-dynamic) i18n key referenced by `t(...)` in web/src.
 *
 * Only keys written as a quoted dotted string literal count, e.g.
 *   t('shelf.novelTag', '小说')
 *   t('shelf.novelTag', { count }, '小说')     -> 3rd arg default
 *   t('shelf.novelTag', { defaultValue: '小说', count })
 *   t("shelf.novelTag")
 *
 * ---------------------------------------------------------------------------
 * BOUNDARY: dynamically composed keys are deliberately NOT asserted here.
 * `t(\`tts.voice_${id}\`)` and friends resolve to a runtime-dependent key that
 * cannot be verified statically; including them would produce false failures.
 * Today that is exactly two call sites in TtsSettingsModal.tsx
 * (`tts.voice_${v.id}` / `tts.locale_${v.lang}`), both with a real fallback.
 * If you introduce a new dynamic key, make sure it has a sensible inline
 * default or a non-literals-safe fallback, because this test will not cover it.
 * ---------------------------------------------------------------------------
 */
function collectLiteralKeys(): Map<string, string[]> {
  const used = new Map<string, string[]>()
  const callRe = /\bt\(\s*/g

  for (const file of walkSourceFiles(SRC_DIR)) {
    const text = fs.readFileSync(file, 'utf8')
    const rel = path.relative(SRC_DIR, file).replace(/\\/g, '/')
    callRe.lastIndex = 0
    let m: RegExpExecArray | null
    while ((m = callRe.exec(text))) {
      const open = text.indexOf('(', m.index + m[0].length - 1)
      if (open < 0) continue
      const close = matchParen(text, open)
      if (close < 0) continue
      const args = splitArgs(text.slice(open + 1, close))
      const key = literalString(args[0])
      // Non-literal (template literal / variable) first argument => dynamic key: skip.
      if (!key) continue
      if (!/^[A-Za-z0-9_$-]+(\.[A-Za-z0-9_$-]+)+$/.test(key)) continue
      const line = text.slice(0, m.index).split('\n').length
      const where = `${rel}:${line}`
      const sites = used.get(key)
      if (sites) sites.push(where)
      else used.set(key, [where])
    }
  }
  return used
}

const FLAT: Record<string, Record<string, string>> = {}
for (const loc of LOCALES) FLAT[loc] = flatten(RESOURCES[loc])

const USED_KEYS = collectLiteralKeys()

test('i18n parity - every literal t() key in web/src exists in all 4 locales', () => {
  const missing: string[] = []

  for (const [key, sites] of USED_KEYS) {
    for (const loc of LOCALES) {
      if (!(key in FLAT[loc])) {
        missing.push(`${loc}  ${key}   (used at ${sites[0]})`)
      }
    }
  }

  assert.deepEqual(
    missing,
    [],
    `Missing i18n keys — add them to web/src/i18n/locales/*.json:\n  ${missing.join('\n  ')}`,
  )
})

test('i18n parity - every referenced key resolves to a non-empty string in all 4 locales', () => {
  const empty: string[] = []

  for (const [key, sites] of USED_KEYS) {
    for (const loc of LOCALES) {
      const value = FLAT[loc][key]
      if (value === undefined) continue // covered by the previous test
      if (value.trim().length === 0) empty.push(`${loc}  ${key}   (used at ${sites[0]})`)
    }
  }

  assert.deepEqual(
    empty,
    [],
    `Empty i18n values (a blank string silently falls back or shows nothing):\n  ${empty.join('\n  ')}`,
  )
})

test('i18n parity - the scan actually sees a meaningful number of keys', () => {
  // Guards against the scanner silently breaking (e.g. a regex that matches nothing),
  // which would make the two assertions above vacuously pass.
  assert.ok(
    USED_KEYS.size > 1000,
    `Expected the source scan to find over 1000 literal keys, found ${USED_KEYS.size}`,
  )
})

test('i18n parity - no locale may contain a key another locale is missing', () => {
  // The reverse direction: a key present only in e.g. en-US means the other three
  // languages fall back to zh-CN (or show the raw key), which users see as Chinese.
  const allKeys = new Set<string>()
  for (const loc of LOCALES) for (const k of Object.keys(FLAT[loc])) allKeys.add(k)

  const asymmetric: string[] = []
  for (const key of allKeys) {
    const absent = LOCALES.filter(loc => !(key in FLAT[loc]))
    if (absent.length > 0 && absent.length < LOCALES.length) {
      asymmetric.push(`${key}  missing in: ${absent.join(', ')}`)
    }
  }

  assert.deepEqual(asymmetric, [], `Locale key parity broken:\n  ${asymmetric.join('\n  ')}`)
})
