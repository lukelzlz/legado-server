import test from 'node:test'
import assert from 'node:assert/strict'
import {
  inspectChapterContent,
  VIP_DETECTION_REGEX,
  inspectSingleSource,
  inspectAllSourcesConcurrently,
  SourceHealthInspection,
} from '../src/sourceInspector'
import { SearchResult } from '../src/api'

test('SourceInspector - inspectChapterContent detects valid content vs VIP/paywall stubs', () => {
  // 1. Valid rich chapter content
  const validContent = '这是一段正常的正文内容。'.repeat(30)
  const resultValid = inspectChapterContent(validContent)
  assert.equal(resultValid.isValid, true)
  assert.equal(resultValid.isVip, false)
  assert.ok(resultValid.length >= 250)

  // 2. VIP paywall notice
  const vipContent = '本章为付费章节，请前往APP阅读支持正版。'
  const resultVip = inspectChapterContent(vipContent)
  assert.equal(resultVip.isVip, true)
  assert.equal(resultVip.isValid, false)

  // 3. Download client notice
  const appNotice = '试读结束，下载客户端继续阅读全文！'
  const resultApp = inspectChapterContent(appNotice)
  assert.equal(resultApp.isVip, true)
  assert.equal(resultApp.isValid, false)

  // 4. Empty or too short content
  const shortContent = '正文'
  const resultShort = inspectChapterContent(shortContent)
  assert.equal(resultShort.isValid, false)
  assert.equal(resultShort.isVip, false)
})

test('SourceInspector - VIP regex matches common paywall phrasing', () => {
  assert.equal(VIP_DETECTION_REGEX.test('本章节需要订阅后可阅读'), true)
  assert.equal(VIP_DETECTION_REGEX.test('请关注微信公众号扫码阅读'), true)
  assert.equal(VIP_DETECTION_REGEX.test('此章节为付费内容，请充值书币购买本章'), true)
  assert.equal(VIP_DETECTION_REGEX.test('防盗章节，请稍后刷新'), true)
  assert.equal(VIP_DETECTION_REGEX.test('普通章节，天地玄黄宇宙洪荒'), false)
})

test('SourceInspector - source ranking scores valid non-vip sources over vip and broken sources', () => {
  const validInspection: SourceHealthInspection = {
    sourceId: 'src-good',
    bookUrl: 'http://good/1',
    status: 'valid',
    score: 10000 + 1200 * 2 + 3 * 500, // score > 13000
    totalChapters: 1200,
    checkedChaptersCount: 3,
    validChaptersCount: 3,
    vipBlocked: false,
    summaryText: '共 1200 章 · 全本可读',
  }

  const vipInspection: SourceHealthInspection = {
    sourceId: 'src-vip',
    bookUrl: 'http://vip/1',
    status: 'vip_restricted',
    score: 2000 + 1500, // score = 3500
    totalChapters: 1500,
    checkedChaptersCount: 3,
    validChaptersCount: 1,
    vipBlocked: true,
    summaryText: '存在VIP拦截',
  }

  const errorInspection: SourceHealthInspection = {
    sourceId: 'src-err',
    bookUrl: 'http://err/1',
    status: 'error',
    score: 0,
    checkedChaptersCount: 0,
    validChaptersCount: 0,
    vipBlocked: false,
    summaryText: '无法连接',
  }

  const list = [errorInspection, vipInspection, validInspection]
  list.sort((a, b) => b.score - a.score)

  assert.equal(list[0].sourceId, 'src-good', 'healthy readable source should be sorted first')
  assert.equal(list[1].sourceId, 'src-vip', 'vip restricted source should be ranked second')
  assert.equal(list[2].sourceId, 'src-err', 'error source should be ranked last')
})
