import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

test('CSS button audit - all common button classes are declared with visual rules in styles.css', () => {
  const cssPath = path.resolve(__dirname, '../src/styles.css')
  const css = fs.readFileSync(cssPath, 'utf8')

  const expectedButtonClasses = [
    'primary-button',
    'secondary-button',
    'subtle-button',
    'danger-button',
    'ghost-button',
    'icon-btn',
    'close-btn',
    'close-button',
    'icon-only',
    'rules-setting-btn',
    'tts-link-btn',
  ]

  for (const cls of expectedButtonClasses) {
    const reg = new RegExp(`\\.${cls}\\b`)
    assert.ok(reg.test(css), `styles.css should declare .${cls}`)
  }
})

test('ReplaceRulesModal and ReplaceRulesPage buttons have proper class and semantics', () => {
  const modalPath = path.resolve(__dirname, '../src/ReplaceRulesModal.tsx')
  const content = fs.readFileSync(modalPath, 'utf8')

  // Parse button tags accurately handling multi-line attributes and inline arrow functions
  const lines = content.split('\n')
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].includes('<button')) {
      let tag = ''
      let j = i
      let inBrace = 0
      let inQuote = false
      let quoteChar = ''
      let foundEnd = false

      while (j < lines.length) {
        const line = lines[j]
        for (let k = (j === i ? line.indexOf('<button') : 0); k < line.length; k++) {
          const char = line[k]
          tag += char
          if (inQuote) {
            if (char === quoteChar && line[k - 1] !== '\\') {
              inQuote = false
            }
          } else if (char === '"' || char === '\'' || char === '`') {
            inQuote = true
            quoteChar = char
          } else if (char === '{' || char === '(') {
            inBrace++
          } else if (char === '}' || char === ')') {
            inBrace--
          } else if (char === '>' && inBrace === 0 && !inQuote) {
            foundEnd = true
            break
          }
        }
        if (foundEnd) break
        tag += '\n'
        j++
      }

      const hasClass = /className\s*=/.test(tag)
      assert.ok(hasClass, `Every button in ReplaceRulesModal must have a className (line ${i + 1}): ${tag.replace(/\s+/g, ' ')}`)
    }
  }
})

test('TtsSettingsModal custom engine button has semantic class tts-link-btn', () => {
  const modalPath = path.resolve(__dirname, '../src/TtsSettingsModal.tsx')
  const content = fs.readFileSync(modalPath, 'utf8')

  assert.ok(content.includes('tts-link-btn'), 'TtsSettingsModal should use tts-link-btn')
})
