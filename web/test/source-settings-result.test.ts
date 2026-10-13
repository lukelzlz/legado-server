import test from 'node:test'
import assert from 'node:assert/strict'
import {
  isBubbleResult,
  isInlineBrowserTarget,
  mergeBubbleResult,
  readSettingsResultMessage,
  SETTINGS_RESULT_IDS,
} from '../src/sourceSettingsResult.ts'

test('settings result - inline data urls must stay inside the built-in browser', () => {
  // 书源自生成页面全部是 data: 内联页：window.open 只会开出空白新标签且拿不到结果
  assert.equal(isInlineBrowserTarget('data:text/html;base64,PCFkb2N0eXBl'), true)
  assert.equal(isInlineBrowserTarget('  data:text/html,<html></html>  '), true)
  assert.equal(isInlineBrowserTarget('https://v5.langge.uk/source-settings'), false)
  assert.equal(isInlineBrowserTarget('http://219.154.201.122:5006/'), false)
  assert.equal(isInlineBrowserTarget(''), false)
})

test('settings result - message from the iframe is validated and parsed', () => {
  const payload = { tab: '听书', sources: '书旗', _settings_nonce: 'n1' }
  const parsed = readSettingsResultMessage({
    source: 'legado-webview',
    type: 'settings-result',
    resultId: SETTINGS_RESULT_IDS[0],
    settings: payload,
  })
  assert.deepEqual(parsed, { resultId: 'source-settings-final-result', settings: payload })

  // 非本协议消息 / 非对象载荷 / 空对象全部丢弃
  assert.equal(readSettingsResultMessage(null), null)
  assert.equal(readSettingsResultMessage({ source: 'legado-webview', type: 'navigated', url: 'https://a' }), null)
  assert.equal(readSettingsResultMessage({ source: 'other', type: 'settings-result', settings: payload }), null)
  assert.equal(readSettingsResultMessage({ source: 'legado-webview', type: 'settings-result', settings: 'nope' }), null)
  assert.equal(readSettingsResultMessage({ source: 'legado-webview', type: 'settings-result', settings: [1, 2] }), null)
  assert.equal(readSettingsResultMessage({ source: 'legado-webview', type: 'settings-result', settings: {} }), null)
})

test('settings result - bubble result merges into settings only for the same session', () => {
  const settings = { tab: '小说', pstyle: '0', _settings_nonce: 'nonce-1' }
  const bubble = { pstyle: '7', _bubble_nonce: 'nonce-1', _bubble_applied: '1' }

  assert.deepEqual(mergeBubbleResult(settings, bubble), { tab: '小说', pstyle: '7', _settings_nonce: 'nonce-1' })
  // 没有设置结果时不落库（气泡结果自身不是源变量）
  assert.equal(mergeBubbleResult(null, bubble), null)
  // 气泡结果缺失时按原设置提交
  assert.deepEqual(mergeBubbleResult(settings, null), settings)
  // nonce 不一致 / 未点应用 ⇒ 忽略气泡样式
  assert.deepEqual(mergeBubbleResult(settings, { ...bubble, _bubble_nonce: 'other' }), settings)
  assert.deepEqual(mergeBubbleResult(settings, { ...bubble, _bubble_applied: '0' }), settings)
  // 样式值必须匹配书源 JS 的白名单正则
  assert.deepEqual(mergeBubbleResult(settings, { ...bubble, pstyle: 'bad style!' }), settings)
})

test('settings result - bubble container id is distinguishable from settings container', () => {
  assert.equal(isBubbleResult('bubble-settings-result'), true)
  assert.equal(isBubbleResult('source-settings-final-result'), false)
  assert.equal(isBubbleResult('source-settings-result'), false)
})
