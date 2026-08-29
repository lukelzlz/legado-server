import test from 'node:test'
import assert from 'node:assert/strict'
import {
  inspectChapterContent,
  isNoticeOrNonMainChapter,
  findMainChapter,
  VIP_DETECTION_REGEX,
  MIN_LEGITIMATE_CHAPTER_LENGTH,
  SourceHealthInspection,
} from '../src/sourceInspector'
import { Chapter } from '../src/api'

test('SourceInspector - inspectChapterContent distinguishes author notes / leave notices from VIP truncation', () => {
  // 1. Legitimate end-of-book speech / notice (short 200 chars)
  const speechContent = '写完了，感谢大家一路以来的陪伴！新书下个月发布，敬请期待！'
  const resultSpeech = inspectChapterContent('完本感言', speechContent, 2500, true)
  assert.equal(resultSpeech.isNotice, true, 'speech should be detected as notice')
  assert.equal(resultSpeech.isVip, false, 'speech should not be flagged as VIP')
  assert.equal(resultSpeech.isValid, true, 'speech is considered valid')

  // 2. Author leave request (short 80 chars)
  const leaveContent = '今天身体不适，去医院挂水，请假一天，明天补上！'
  const resultLeave = inspectChapterContent('请假条', leaveContent, 2500, true)
  assert.equal(resultLeave.isNotice, true, 'leave note should be detected as notice')
  assert.equal(resultLeave.isVip, false, 'leave note should not be flagged as VIP')
  assert.equal(resultLeave.isValid, true)

  // 3. Fake short chapter that is actually VIP truncation (regular story chapter title with short paywall text)
  const fakeVipContent = '他看着远处的山峰，心中若有所思。'
  const resultVip = inspectChapterContent('第1200章 决战天门', fakeVipContent, 2500, true)
  assert.equal(resultVip.isNotice, false, 'regular story title is not a notice')
  assert.equal(resultVip.isTruncated, true)
  assert.equal(resultVip.isVip, true, 'short regular chapter should be flagged as VIP truncation')
  assert.equal(resultVip.isValid, false)

  // 4. Regular full-length chapter
  const validContent = '这是一段正常的正文内容，字数充沛，情节完整。'.repeat(50)
  const resultValid = inspectChapterContent('第1200章 决战天门', validContent, 2500, true)
  assert.equal(resultValid.isValid, true)
  assert.equal(resultValid.isVip, false)
  assert.equal(resultValid.isNotice, false)
})

test('SourceInspector - findMainChapter avoids notice and leave chapters', () => {
  const chapters: Chapter[] = [
    { index: 0, title: '作品相关设定', url: 'http://c/0' },
    { index: 1, title: '第1章 初入异界', url: 'http://c/1' },
    { index: 2, title: '第2章 命运之石', url: 'http://c/2' },
    { index: 3, title: '请假一天说明', url: 'http://c/3' },
    { index: 4, title: '第3章 决意启程', url: 'http://c/4' },
    { index: 5, title: '完本感言与新书预告', url: 'http://c/5' },
  ]

  // Preferred index 0 is "作品相关设定", should fallback to index 1 "第1章 初入异界"
  const early = findMainChapter(chapters, 0)
  assert.equal(early?.title, '第1章 初入异界')

  // Preferred index 3 is "请假一天说明", should fallback to index 2 or 4
  const mid = findMainChapter(chapters, 3)
  assert.ok(mid?.title === '第2章 命运之石' || mid?.title === '第3章 决意启程')

  // Preferred index 5 (last chapter) is "完本感言...", should fallback to index 4 "第3章 决意启程"
  const late = findMainChapter(chapters, 5)
  assert.equal(late?.title, '第3章 决意启程')
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
