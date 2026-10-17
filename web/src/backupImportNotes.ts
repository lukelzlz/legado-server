import type { BackupImportSummary } from './api'

/** 组件里 `useTranslation()` 拿到的 `t` 的最小形状（便于在 Node 侧直接单测这几个提示）。 */
export type Translate = (key: string, options?: Record<string, unknown>) => string

export type BackupImportNotes = {
  /** 「已跳过 N 本手机本地图书」——**只在真的跳过了本地图书时非空**。 */
  skipNote: string
  /** 「封面补抓成功 X 张、失败 Y 张」——只在**有失败**时非空（成功无需打扰用户）。 */
  coverNote: string
  /**
   * 「已受理 N 本，封面正在后台补抓」——只在 `coversQueued > 0` 时非空。
   *
   * 封面补抓改成**后台异步**后，导入接口返回时还没有 `coversFetched` 数字
   * （那时它恒为 0），所以只能用 `coversQueued` 告知用户"已受理"。
   */
  queuedNote: string
  /** 书签提示（含跳过条数）。 */
  markNote: string
}

/**
 * 备份导入结果提示的**唯一组装点**（数据驱动，文案不再由前端硬编码语义）。
 *
 * ## 为什么要有这个函数
 * 旧的过滤规则是「本地图书 + 音频（听书）都跳过」，提示里因此写死了
 * "本地图书 N、音频听书 M"。现在音频/漫画都已放行，唯一会被跳过的是**手机本地图书**
 * （`content://` 等本机路径，服务端真的读不到）。提示必须只报真实跳过项，
 * 否则用户会以为自己的听书/漫画又被丢了。
 */
export function backupImportNotes(summary: BackupImportSummary, t: Translate): BackupImportNotes {
  const skippedLocal = summary.skippedLocal ?? 0
  const skipNote = skippedLocal > 0
    ? t('webdav.backupSkipLocalNote', {
        count: skippedLocal,
        defaultValue: `；已跳过 ${skippedLocal} 本手机本地图书（书在手机本机，服务端读不到文件）`,
      })
    : ''

  const coversFetched = summary.coversFetched ?? 0
  const coversResourced = summary.coversResourced ?? 0
  const coversFailed = summary.coversFailed ?? 0
  // 只有**真的失败**时才提封面：全成功时不必打扰用户；失败时必须说清"已回源重试过"
  // （用户明确要求「导入时封面链接失效就自动重新请求添上这个」，所以文案不能停留在"失败"）。
  const coverNote = coversFailed > 0
    ? t('webdav.backupCoverFailedNote', {
        count: coversFetched,
        resourced: coversResourced,
        failed: coversFailed,
        defaultValue:
          `；封面补抓成功 ${coversFetched} 张（其中回源补上 ${coversResourced} 张）、失败 ${coversFailed} 张` +
          `（已回源重试；仍失败的多为图床失效/防盗链，这些书显示文字占位）`,
      })
    : ''

  const bookmarks = summary.bookmarks ?? 0
  const bookmarksSkipped = summary.bookmarksSkipped ?? 0
  const markNote = bookmarksSkipped > 0
    ? t('webdav.backupBookmarksSkippedNote', {
        count: bookmarks,
        skipped: bookmarksSkipped,
        defaultValue: `；书签 ${bookmarks} 条（跳过 ${bookmarksSkipped} 条，其所属书籍是手机本地图书、未导入）`,
      })
    : t('webdav.backupBookmarksNote', {
        count: bookmarks,
        defaultValue: `；书签 ${bookmarks} 条`,
      })

  // 封面补抓已改成**后台异步**：接口返回时 `coversFetched/coversFailed` 还来不及有结果
  // （如实为 0），"到底有没有在补"只能靠 `coversQueued` 告知用户"已受理"。
  // 用户明确要求"就弹一下就可以了"，所以这里只报受理数量，不做进度界面；
  // 没补上的书由常驻自愈 worker 之后继续补，不再另弹失败提示。
  const coversQueued = summary.coversQueued ?? 0
  const queuedNote = coversQueued > 0
    ? t('webdav.backupCoverQueuedNote', {
        count: coversQueued,
        defaultValue: `；已受理 ${coversQueued} 本，封面正在后台补抓`,
      })
    : ''

  return { skipNote, coverNote, markNote, queuedNote }
}
