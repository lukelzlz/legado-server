import test from 'node:test'
import assert from 'node:assert/strict'
import {
  inspectChapterContent,
  VIP_DETECTION_REGEX,
  MIN_LEGITIMATE_CHAPTER_LENGTH,
  SourceHealthInspection,
} from '../src/sourceInspector'

test('SourceInspector - inspectChapterContent detects valid content vs VIP/paywall stubs', () => {
  // 1. Valid rich chapter content (2000 chars)
  const validContent = '这是一段正常的正文内容，字数充沛，情节完整。'.repeat(50)
  const resultValid = inspectChapterContent(validContent, 2000, true)
  assert.equal(resultValid.isValid, true)
  assert.equal(resultValid.isVip, false)
  assert.equal(resultValid.isTruncated, false)
  assert.ok(resultValid.length >= MIN_LEGITIMATE_CHAPTER_LENGTH)

  // 2. VIP paywall notice
  const vipContent = '本章为付费章节，请前往APP阅读支持正版。'
  const resultVip = inspectChapterContent(vipContent, 2000, true)
  assert.equal(resultVip.isVip, true)
  assert.equal(resultVip.isValid, false)

  // 3. Late chapter with suspicious short length (< 450 chars, no explicit VIP keywords)
  const shortLateContent = '他看着远处的山峰，心中若有所思。'
  const resultShortLate = inspectChapterContent(shortLateContent, 2500, true)
  assert.equal(resultShortLate.isTruncated, true)
  assert.equal(resultShortLate.isVip, true, 'short late chapter should be flagged as VIP/truncated')
  assert.equal(resultShortLate.isValid, false)

  // 4. Dramatic drop in late chapter length (e.g. early was 3000 chars, late is 400 chars)
  const droppedContent = '段落内容。'.repeat(60) // ~300 chars
  const resultDrop = inspectChapterContent(droppedContent, 3000, true)
  assert.equal(resultDrop.isTruncated, true)
  assert.equal(resultDrop.isVip, true)
  assert.equal(resultDrop.isValid, false)
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
    score: 12000 + 1200 * 3 + 2800,
    totalChapters: 1200,
    checkedChaptersCount: 4,
    validChaptersCount: 4,
    vipBlocked: false,
    avgLateChapterLength: 2800,
    summaryText: '共 1200 章 · 全本可读 (后段字数充足 均2800字)',
  }

  const vipTruncatedInspection: SourceHealthInspection = {
    sourceId: 'src-vip-truncated',
    bookUrl: 'http://vip/1',
    status: 'vip_restricted',
    score: 2000 + 1500 + 320,
    totalChapters: 1500,
    checkedChaptersCount: 4,
    validChaptersCount: 1,
    vipBlocked: true,
    avgLateChapterLength: 320,
    summaryText: '共 1500 章 · 后期章节较短/疑似VIP截断 (抽检均320字)',
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

  const list = [errorInspection, vipTruncatedInspection, validInspection]
  list.sort((a, b) => b.score - a.score)

  assert.equal(list[0].sourceId, 'src-good', 'healthy full-text source should rank 1st')
  assert.equal(list[1].sourceId, 'src-vip-truncated', 'vip truncated source should rank 2nd')
  assert.equal(list[2].sourceId, 'src-err', 'error source should rank last')
})
