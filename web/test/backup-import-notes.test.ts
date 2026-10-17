import test from 'node:test'
import assert from 'node:assert/strict'
import { backupImportNotes, type Translate } from '../src/backupImportNotes'
import { i18n } from '../src/i18n'
import type { BackupImportSummary } from '../src/api'

// 备份导入结果的提示必须**由摘要数据驱动、只报真实跳过项**。
//
// 背景：旧的过滤规则是「本地图书 + 音频（听书）都跳过」，提示里因此写死了
//   "本地图书 N、音频听书 M"
// 现在音频/漫画都已放行（各自进有声书播放器 / 漫画阅读器），唯一会被跳过的是
// 手机本地图书。若提示还提"音频听书"，用户就会以为自己的听书/漫画又被丢了。

const t = ((key: string, options?: Record<string, unknown>) => i18n.t(key, options) as string) as Translate

function summaryOf(patch: Partial<BackupImportSummary> & Record<string, unknown> = {}): BackupImportSummary {
  return {
    sources: 0,
    sourcesUpdated: 0,
    rules: 0,
    rulesUpdated: 0,
    books: 3,
    booksUpdated: 0,
    progress: 3,
    ...patch,
  } as BackupImportSummary
}

test('backup import notes - 没有本地图书时不得报告任何跳过', () => {
  const notes = backupImportNotes(summaryOf({ skippedLocal: 0 }), t)
  assert.equal(notes.skipNote, '', '没有本地图书就不能出现"已跳过"')
  assert.equal(notes.coverNote, '', '封面全部补齐时不该打扰用户')
})

test('backup import notes - 只报告本地图书，绝不提音频/听书', () => {
  const notes = backupImportNotes(summaryOf({ skippedLocal: 14 }), t)
  assert.match(notes.skipNote, /14/, `跳过条数必须如实出现在提示里：${notes.skipNote}`)
  assert.match(notes.skipNote, /本地图书/, `必须写明跳过原因（手机本地图书）：${notes.skipNote}`)
  for (const forbidden of ['音频', '听书', 'audiobook', 'audio']) {
    assert.equal(
      notes.skipNote.includes(forbidden),
      false,
      `提示里不得再出现「${forbidden}」——听书早已放行，写了会让用户以为又被跳过：${notes.skipNote}`,
    )
  }
})

test('backup import notes - 旧版摘要里的 skippedAudio 字段被彻底忽略', () => {
  // 兼容旧客户端/旧服务端混跑：即便响应里还带着 skippedAudio，也不得影响提示。
  const legacy = summaryOf({ skippedLocal: 0, skippedAudio: 31 } as unknown as Partial<BackupImportSummary>)
  const notes = backupImportNotes(legacy, t)
  assert.equal(notes.skipNote, '', 'skippedAudio 已废弃，不得再触发"已跳过"提示')
})

test('backup import notes - 封面补抓失败要如实报告张数，并说明已回源重试', () => {
  const notes = backupImportNotes(summaryOf({ coversFetched: 23, coversResourced: 9, coversFailed: 12 }), t)
  assert.match(notes.coverNote, /12/, `失败张数必须出现：${notes.coverNote}`)
  assert.match(notes.coverNote, /封面/, `必须说明这是封面问题：${notes.coverNote}`)
  assert.match(notes.coverNote, /9/, `回源补上的张数必须出现（用户要求"失效就自动重新请求"）：${notes.coverNote}`)
  assert.match(notes.coverNote, /回源/, `必须说明已经回源重试过：${notes.coverNote}`)
})

test('backup import notes - 书签跳过要指向真正原因（本地图书）', () => {
  const notes = backupImportNotes(summaryOf({ bookmarks: 2, bookmarksSkipped: 1 }), t)
  assert.match(notes.markNote, /2/, `书签条数：${notes.markNote}`)
  assert.match(notes.markNote, /1/, `跳过条数：${notes.markNote}`)
  assert.match(notes.markNote, /本地图书/, `跳过原因必须写清：${notes.markNote}`)

  const noSkip = backupImportNotes(summaryOf({ bookmarks: 2, bookmarksSkipped: 0 }), t)
  assert.match(noSkip.markNote, /2/, `没有跳过时只报条数：${noSkip.markNote}`)
  assert.equal(noSkip.markNote.includes('跳过'), false, `不该出现空的"跳过 0"：${noSkip.markNote}`)
})

test('backup import notes - 四语言都有真实文案（不是回退成 key 或空串）', async () => {
  for (const locale of ['zh-CN', 'zh-TW', 'en-US', 'ja-JP']) {
    await i18n.changeLanguage(locale)
    const notes = backupImportNotes(summaryOf({ skippedLocal: 5, coversFetched: 1, coversFailed: 2 }), t)
    assert.ok(notes.skipNote.length > 0, `${locale}: 缺少 backupSkipLocalNote 文案`)
    assert.ok(notes.coverNote.length > 0, `${locale}: 缺少 backupCoverFailedNote 文案`)
    assert.equal(notes.skipNote.includes('backupSkipLocalNote'), false, `${locale}: 文案回退成了 key`)
    assert.equal(notes.coverNote.includes('backupCoverFailedNote'), false, `${locale}: 文案回退成了 key`)
  }
  await i18n.changeLanguage('zh-CN')
})
