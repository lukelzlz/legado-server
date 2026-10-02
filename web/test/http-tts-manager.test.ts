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

test('TtsSettingsModal - renders TTS manage button and custom engine selection', () => {
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

  // Manage button
  assert.ok(html.includes('TTS 管理'))
  // Custom option label
  assert.ok(html.includes('自定义 HTTP'))
})
