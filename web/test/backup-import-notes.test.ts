import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { backupImportNotes, type Translate } from '../src/backupImportNotes'
import { i18n } from '../src/i18n'
import type { BackupImportSummary } from '../src/api'

const here = path.dirname(fileURLToPath(import.meta.url))

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

test('backup import notes - 后台受理封面补抓要如实报告「已受理 N 本」', () => {
  // 封面补抓改成后台异步后，接口返回时 coversFetched/coversFailed 都还是 0，
  // "到底有没有在补"只能靠 coversQueued 说明（用户原话：补上，就弹一下就可以了）。
  const queued = backupImportNotes(summaryOf({ coversQueued: 215 }), t)
  assert.ok(queued.queuedNote.length > 0, '有书受理时必须弹一句提示')
  assert.match(queued.queuedNote, /215/, `受理本数必须出现：${queued.queuedNote}`)
  assert.equal(queued.queuedNote.includes('backupCoverQueuedNote'), false, '文案不得回退成 key')
  // 后台模式下没有同步结果 ⇒ 不得同时冒出一条"封面失败"的干扰提示
  assert.equal(queued.coverNote, '', '异步模式下不该有同步封面结果提示')

  assert.equal(backupImportNotes(summaryOf({ coversQueued: 0 }), t).queuedNote, '', '0 本不得出现受理提示')
  assert.equal(backupImportNotes(summaryOf(), t).queuedNote, '', '字段缺失（旧服务端）不得出现受理提示')
})

test('backup import notes - 导入结果那条 toast 的展示时长单独延长到 5s', () => {
  // 用户原话：「还有导入备份后的提示显示成功什么的的时间长一些，5s」。
  // toast API 的形状是 success(message, duration)（见 Toast.tsx），无同 key 覆盖，
  // 所以只需要在导入结果这一条上传第二个参数 5000。
  const pagePath = path.resolve(here, '../src/WebDavSettingsPage.tsx')
  const source = fs.readFileSync(pagePath, 'utf8')

  const start = source.indexOf('const handleImport = async')
  assert.ok(start >= 0, '找不到备份导入调用点 handleImport')
  const end = source.indexOf('const handleImportWebDavBook', start)
  assert.ok(end > start, '找不到 handleImport 的边界')
  const handleImport = source.slice(start, end)

  assert.match(
    handleImport,
    /toast\.success\(\s*t\('webdav\.backupImportSuccessSummary'[\s\S]*?\}\),[\s\S]*?\n\s*5000,/,
    '导入结果 toast 必须显式传 5000ms（默认 2800ms 看不完）',
  )

  // 其它 toast 一律不动：文件里除了这一处，不得再出现自定义时长。
  const rest = source.slice(0, start) + source.slice(end)
  assert.equal(/,\s*(?:[1-9]\d{2,}|5000)\)/.test(rest), false, '其它 toast 不得被顺手改时长')
})

test('backup import notes - 真实弹窗文案必须把「已受理」插进去（四语言占位符不能漏）', async () => {
  // 前面几条只证明 `backupImportNotes()` 返回的 queuedNote **本身**有内容，
  // 但真正弹给用户的是 `webdav.backupImportSuccessSummary` 这条翻译模板：
  // 只要四语言模板里少写 `{{queuedNote}}`，i18next 会**静默丢弃**这个变量，
  // 界面上根本看不到「封面正在后台补抓」（本分支曾经就是这样，实测渲染结果里 215 凭空消失）。
  // 所以这里必须按页面的真实调用形态渲染一遍，而不是只断言 notes 对象。
  for (const locale of ['zh-CN', 'zh-TW', 'en-US', 'ja-JP']) {
    await i18n.changeLanguage(locale)
    const notes = backupImportNotes(summaryOf({ coversQueued: 215 }), t)
    assert.ok(notes.queuedNote.length > 0, `${locale}: queuedNote 不该为空`)

    // 与 WebDavSettingsPage.handleImport 里那条 toast.success(...) 的参数完全同形。
    const rendered = t('webdav.backupImportSuccessSummary', {
      sources: 1,
      sourceGroupNote: '',
      rules: 0,
      books: 215,
      progress: 100,
      markNote: notes.markNote,
      skipNote: notes.skipNote,
      coverNote: notes.coverNote,
      queuedNote: notes.queuedNote,
      defaultValue: 'FALLBACK-DEFAULT',
    })

    assert.ok(
      rendered.includes(notes.queuedNote),
      `${locale}: 成功提示把 queuedNote 丢了 —— 该语言 backupImportSuccessSummary 缺少 {{queuedNote}} 占位符：${rendered}`,
    )
    assert.match(rendered, /215/, `${locale}: 受理本数必须出现在最终弹窗文案里：${rendered}`)
    assert.equal(rendered.includes('{{'), false, `${locale}: 不得残留未插值的占位符：${rendered}`)
  }
  await i18n.changeLanguage('zh-CN')
})

test('backup import notes - 四语言都有真实文案（不是回退成 key 或空串）', async () => {
  for (const locale of ['zh-CN', 'zh-TW', 'en-US', 'ja-JP']) {
    await i18n.changeLanguage(locale)
    const notes = backupImportNotes(summaryOf({ skippedLocal: 5, coversFetched: 1, coversFailed: 2, coversQueued: 215 }), t)
    assert.ok(notes.skipNote.length > 0, `${locale}: 缺少 backupSkipLocalNote 文案`)
    assert.ok(notes.coverNote.length > 0, `${locale}: 缺少 backupCoverFailedNote 文案`)
    assert.ok(notes.queuedNote.length > 0, `${locale}: 缺少 backupCoverQueuedNote 文案`)
    assert.match(notes.queuedNote, /215/, `${locale}: 受理本数必须插值：${notes.queuedNote}`)
    assert.equal(notes.skipNote.includes('backupSkipLocalNote'), false, `${locale}: 文案回退成了 key`)
    assert.equal(notes.coverNote.includes('backupCoverFailedNote'), false, `${locale}: 文案回退成了 key`)
    assert.equal(notes.queuedNote.includes('backupCoverQueuedNote'), false, `${locale}: 文案回退成了 key`)
  }
  await i18n.changeLanguage('zh-CN')
})
