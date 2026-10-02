import React from 'react'
import test from 'node:test'
import assert from 'node:assert/strict'
import { renderToStaticMarkup } from 'react-dom/server'
import { HttpTtsManagerModal } from '../src/HttpTtsManagerModal'
import { TtsSettingsModal } from '../src/TtsSettingsModal'
import { defaultReaderSettings, ensureValidTtsSettings, loadReaderSettings } from '../src/readerSettings'

test('HttpTtsManagerModal - renders modal layout with toolbar and empty prompt correctly', () => {
  const html = renderToStaticMarkup(
    React.createElement(HttpTtsManagerModal, {
      onClose: () => {},
      currentTtsId: 100,
    })
  )

  // Title and header
  assert.ok(html.includes('自定义 HTTP 朗读引擎管理') || html.includes('volume2'))
  // Actions
  assert.ok(html.includes('新建引擎'))
  assert.ok(html.includes('导入 httpTTS'))
  assert.ok(html.includes('全部导出'))
})

test('ReaderSettings - ensureValidTtsSettings preserves custom engine when ttsHttpTtsId is set', () => {
  const settingsWithHttpId = {
    ...defaultReaderSettings,
    ttsEngine: 'custom' as const,
    ttsHttpTtsId: 1001,
    ttsCustomUrl: '',
  }

  const result = ensureValidTtsSettings(settingsWithHttpId)
  // Should NOT fallback to edge because ttsHttpTtsId is valid
  assert.equal(result.ttsEngine, 'custom')
  assert.equal(result.ttsHttpTtsId, 1001)

  // When both ttsHttpTtsId and ttsCustomUrl are missing, should fallback to edge
  const settingsWithoutAny = {
    ...defaultReaderSettings,
    ttsEngine: 'custom' as const,
    ttsHttpTtsId: undefined,
    ttsCustomUrl: '',
  }
  const resultFallback = ensureValidTtsSettings(settingsWithoutAny)
  assert.equal(resultFallback.ttsEngine, 'edge')
})

test('TtsSettingsModal - renders custom engine selection and manage entry', () => {
  const html = renderToStaticMarkup(
    React.createElement(TtsSettingsModal, {
      settings: {
        ...defaultReaderSettings,
        ttsEngine: 'custom',
        ttsHttpTtsId: 12345,
      },
      onChange: () => {},
      sleepTimer: 'off',
      onSleepTimerChange: () => {},
      remainingSeconds: null,
      onClose: () => {},
    })
  )

  // Redundant manage button next to Engine title should be removed
  assert.ok(!html.includes('TTS 管理'))
  // Contextual management prompt and custom option label are present
  assert.ok(html.includes('+ 添加或管理规则'))
  assert.ok(html.includes('自定义 HTTP'))
  // Redundant manual URL inputs in main dialog should be removed
  assert.ok(!html.includes('自定义 HTTP 源参数'))
  assert.ok(!html.includes('ttsCustomSection'))
  assert.ok(!html.includes('ttsCustomUrl'))
})

test('CSS - secondary button and tts timer grid styles alignment', async () => {
  const fs = await import('node:fs')
  const path = await import('node:path')
  const css = fs.readFileSync(path.resolve('web/src/styles.css'), 'utf-8')

  // secondary-button should be formally declared with primary-button metrics
  assert.ok(css.includes('.primary-button, .secondary-button'))
  assert.ok(css.includes('.secondary-button {'))

  // tts-timer-grid should have 6 columns in desktop and 3 columns on mobile
  assert.ok(css.includes('grid-template-columns: repeat(6, 1fr)'))
  assert.ok(css.includes('grid-template-columns: repeat(3, 1fr)'))
})
