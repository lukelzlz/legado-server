import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { i18n } from './i18n'
import { api, joinWebDavPath, webDavFileUrl, WebDavInfo, ProgressSyncSettings, BackupExportSettings } from './api'
import { toast } from './Toast'
import { Icon } from './icons'

/**
 * 本地书籍支持的扩展名（**产品约束：只有 TXT 与 EPUB**）。
 *
 * 前端用它做两件事：① `<input accept>` 让文件选择器默认只列这些；
 * ② 提交前再过滤一次 —— 用户可以手动切到「所有文件」，光靠 accept 拦不住。
 *
 * 服务端 `LocalBookParser.isSupported` 有**同一份规则**，两层都要有：
 * 前端拦是为了即时反馈，服务端拦才是真正的防线。
 * `.text` 是 `.txt` 的历史命名习惯，一并放行。
 */
export const LOCAL_BOOK_ACCEPT_ATTR = '.txt,.text,.epub'

/** 是否为受支持的本地书格式（大小写不敏感）。 */
export function isSupportedLocalBook(filename: string): boolean {
  return /\.(txt|text|epub)$/i.test(filename)
}

/** 字节数易读化（服务端返回的是精确字节）。 */
export function formatDavSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${unit === 0 ? value : value.toFixed(value >= 100 ? 0 : 1)} ${units[unit]}`
}

/**
 * 修改时间：**24 小时内**用相对时间（刚刚 / N 分钟前 / N 小时前），
 * **超过 24 小时**直接给绝对时刻 `YYYY-MM-DD HH:mm`（24 小时制，精确到分钟）。
 *
 * 刻意不再有「N 天前」这一档：文件列表里最常见的问题是「这份备份到底是几点生成的」，
 * 相对天数答不了这个问题，而带时分的绝对时刻可以。
 */
export function formatDavTime(timestamp: number, now = Date.now(), t: (k: string, opt?: any) => string = (k, opt) => String(i18n.t(k, opt as any))): string {
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '-'
  const diff = now - timestamp
  if (diff < 60_000) return t('webdav.timeJustNow', { defaultValue: '刚刚' })
  if (diff < 3_600_000) return t('webdav.timeMinutesAgo', { count: Math.floor(diff / 60_000), defaultValue: `${Math.floor(diff / 60_000)} 分钟前` })
  if (diff < 86_400_000) return t('webdav.timeHoursAgo', { count: Math.floor(diff / 3_600_000), defaultValue: `${Math.floor(diff / 3_600_000)} 小时前` })
  const date = new Date(timestamp)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 面包屑：根目录 + 逐级路径。 */
export function davBreadcrumbs(path: string, t: (k: string, opt?: any) => string = (k, opt) => String(i18n.t(k, opt as any))): Array<{ name: string; path: string }> {
  const segments = path.split('/').filter(Boolean)
  const crumbs: Array<{ name: string; path: string }> = [{ name: t('webdav.rootDir', { defaultValue: '根目录' }), path: '' }]
  segments.forEach((segment, index) => {
    crumbs.push({ name: segment, path: segments.slice(0, index + 1).join('/') })
  })
  return crumbs
}

/** 当前访问来源（服务端渲染 / 测试环境下退化为空串）。 */
export const currentOrigin = () => (typeof location === 'undefined' ? '' : location.origin)

/** 备份包识别：只给 `.zip` 提供「导入」入口，是否 Legado 备份由服务端二次校验。 */
export const isBackupArchive = (name: string) => name.toLowerCase().endsWith('.zip')

/**
 * 本地书籍识别：只给 TXT / EPUB 提供「导入书籍」入口。
 *
 * **直接复用 [isSupportedLocalBook]**，不另写一份后缀判断 ——
 * 否则两处规则一旦漂移，就会出现「按钮显示了但导入报不支持」的矛盾。
 * 与 [isBackupArchive] 并列，构成文件管理里两种导入入口的判定。
 */
export const isLocalBookFile = (name: string) => isSupportedLocalBook(name)

async function copyText(text: string, successMessage: string, failureMessage?: string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.success(successMessage)
  } catch {
    toast.warning(failureMessage || i18n.t('common.clipboardNotSupported', '当前环境不支持剪贴板，请手动复制'))
  }
}

export function WebDavSettingsPage() {
  const { t } = useTranslation()
  const [info, setInfo] = useState<WebDavInfo | null>(null)
  const [path, setPath] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const uploadInputRef = useRef<HTMLInputElement>(null)

  // 进度同步（手机端 bookProgress 文件夹）
  const [syncSettings, setSyncSettings] = useState<ProgressSyncSettings | null>(null)
  const [syncDirInput, setSyncDirInput] = useState('')
  const [syncSaving, setSyncSaving] = useState(false)

  // 备份导出（Legado 格式 zip 写入 WebDAV 存储区）
  const [exportSettings, setExportSettings] = useState<BackupExportSettings | null>(null)
  const [exportDirInput, setExportDirInput] = useState('')
  const [deviceNameInput, setDeviceNameInput] = useState('')
  const [autoPageCloseInput, setAutoPageCloseInput] = useState(false)
  const [autoBookCloseInput, setAutoBookCloseInput] = useState(false)
  const [exportSaving, setExportSaving] = useState(false)
  const [exporting, setExporting] = useState(false)

  // 本地书籍导入（仅 TXT / EPUB）
  const localBookInputRef = useRef<HTMLInputElement>(null)
  const [localBookBusy, setLocalBookBusy] = useState(false)

  /** 产品约束：本地书籍只支持 TXT 与 EPUB，前端先过滤一次，避免上传后才发现不支持。 */
  const LOCAL_BOOK_ACCEPT = LOCAL_BOOK_ACCEPT_ATTR

  const handleImportLocalBooks = async (files: FileList | null) => {
    if (!files || files.length === 0) return
    const all = Array.from(files)
    const supported = all.filter(file => isSupportedLocalBook(file.name))
    const rejected = all.filter(file => !isSupportedLocalBook(file.name))
    if (supported.length === 0) {
      toast.warning(t('webdav.onlyTxtEpub', { count: rejected.length, defaultValue: `仅支持 TXT / EPUB 格式，已忽略 ${rejected.length} 个文件` }))
      return
    }
    setLocalBookBusy(true)
    try {
      const result = await api.importLocalBooks(supported)
      const names = result.results.filter(item => item.success).map(item => item.name ?? item.filename)
      if (result.failed === 0 && rejected.length === 0) {
        const namesSummary = names.length ? '：' + names.slice(0, 3).join('、') + (names.length > 3 ? t('source.etcCount', { count: names.length, defaultValue: ' 等' }) : '') : ''
        toast.success(t('webdav.importSuccessBooks', { count: result.imported, names: namesSummary, defaultValue: `成功导入 ${result.imported} 本本地书籍${namesSummary}` }))
      } else {
        // 部分失败要如实说明，否则用户不知道哪几本没进来
        const failedNames = result.results.filter(item => !item.success).map(item => `${item.filename}（${item.error ?? t('common.failed', { defaultValue: '解析失败' })}）`)
        const parts = [t('webdav.importSuccessPart', { count: result.imported, defaultValue: `成功 ${result.imported} 本` })]
        if (result.failed > 0) parts.push(t('webdav.importFailedPart', { count: result.failed, names: failedNames.slice(0, 3).join('；'), defaultValue: `失败 ${result.failed} 本：${failedNames.slice(0, 3).join('；')}` }))
        if (rejected.length > 0) parts.push(t('webdav.importIgnoredPart', { count: rejected.length, defaultValue: `忽略 ${rejected.length} 个非 TXT/EPUB 文件` }))
        toast.warning(parts.join('；'))
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('webdav.importLocalBooksFailed', { defaultValue: '本地书籍导入失败' }))
    } finally {
      setLocalBookBusy(false)
    }
  }

  const loadSyncSettings = useCallback(async () => {
    try {
      const s = await api.progressSyncSettings()
      setSyncSettings(s)
      setSyncDirInput(s.directoryName)
    } catch {
      // 进度同步配置读取失败不影响文件页其它功能，静默即可
      setSyncSettings(null)
    }
  }, [])

  const handleSaveSyncSettings = async () => {
    const name = syncDirInput.trim()
    if (!name) {
      toast.warning(t('webdav.syncFolderEmpty', { defaultValue: '文件夹名不能为空' }))
      return
    }
    setSyncSaving(true)
    try {
      const saved = await api.saveProgressSyncSettings(name)
      setSyncSettings(saved)
      setSyncDirInput(saved.directoryName)
      if (saved.available) {
        toast.success(t('webdav.syncConfigSaved', { dir: saved.directoryName, count: saved.fileCount, defaultValue: `进度文件夹已设为 ${saved.directoryName}（发现 ${saved.fileCount} 个进度文件）` }))
      } else {
        toast.warning(t('webdav.syncConfigSavedNotExists', { dir: saved.directoryName, defaultValue: `已保存为 ${saved.directoryName}，但该文件夹还不存在，下次同步时会自动创建` }))
      }
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('webdav.saveFailed', { defaultValue: '保存失败' }))
    } finally {
      setSyncSaving(false)
    }
  }

  const loadExportSettings = useCallback(async () => {
    try {
      const s = await api.backupExportSettings()
      setExportSettings(s)
      setExportDirInput(s.exportDir)
      setDeviceNameInput(s.deviceName)
      setAutoPageCloseInput(s.exportOnPageClose)
      setAutoBookCloseInput(s.exportOnBookClose)
    } catch {
      // 与进度同步同理：导出设置读取失败不该影响文件页其它功能
      setExportSettings(null)
    }
  }, [])

  const handleSaveExportSettings = async () => {
    setExportSaving(true)
    try {
      const saved = await api.saveBackupExportSettings({
        exportDir: exportDirInput.trim(),
        deviceName: deviceNameInput.trim(),
        exportOnPageClose: autoPageCloseInput,
        exportOnBookClose: autoBookCloseInput,
      })
      setExportSettings(saved)
      setExportDirInput(saved.exportDir)
      setDeviceNameInput(saved.deviceName)
      setAutoPageCloseInput(saved.exportOnPageClose)
      setAutoBookCloseInput(saved.exportOnBookClose)
      toast.success(
        saved.deviceName
          ? t('webdav.exportConfigSaved', { dir: saved.exportDir || '/', device: saved.deviceName, defaultValue: `已保存：导出到 ${saved.exportDir || 'WebDAV 根目录'}，设备名 ${saved.deviceName}` })
          : t('webdav.exportConfigSavedNoDevice', { dir: saved.exportDir || '/', defaultValue: `已保存：导出到 ${saved.exportDir || 'WebDAV 根目录'}（未设设备名）` }),
      )
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('webdav.saveFailed', { defaultValue: '保存失败' }))
    } finally {
      setExportSaving(false)
    }
  }

  /**
   * 导出备份到 WebDAV 存储区。
   *
   * 导出后必须**刷新当前目录列表**：文件落在「导出路径」里，如果用户正好停在那层目录，
   * 不刷新就看不到刚生成的 zip。
   */
  const handleExportBackup = async () => {
    setExporting(true)
    try {
      const result = await api.backupExport()
      toast.success(
        t('webdav.exportSuccess', {
          name: result.fileName,
          sources: result.sources,
          books: result.books,
          bookmarks: result.bookmarks,
          size: formatDavSize(result.size),
          defaultValue: `已导出 ${result.fileName}（书源 ${result.sources} 个、书籍 ${result.books} 本、书签 ${result.bookmarks} 条、${formatDavSize(result.size)}）`,
        }),
      )
      if (path === (exportSettings?.exportDir ?? '')) void load(path)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('webdav.exportFailed', { defaultValue: '备份导出失败' }))
    } finally {
      setExporting(false)
    }
  }

  const load = useCallback(async (target: string) => {
    setLoading(true)
    try {
      setInfo(await api.webDavInfo(target))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('webdav.readStatusFailed', { defaultValue: '读取 WebDAV 状态失败' }))
      setInfo(null)
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    void load(path)
  }, [path, load])

  useEffect(() => {
    void loadSyncSettings()
  }, [loadSyncSettings])

  useEffect(() => {
    void loadExportSettings()
  }, [loadExportSettings])

  const refresh = () => void load(path)

  // 概览卡片的「复制地址」要用它拼完整 URL；刻意走 currentOrigin() 而不是裸用全局 `origin`，
  // 否则在无头渲染（web/test 的 renderToStaticMarkup）下会取到 undefined。
  const origin = currentOrigin()

  const breadcrumbs = useMemo(() => davBreadcrumbs(path, t), [path, t])
  const entries = info?.entries ?? []

  const handleUpload = async (files: FileList | null) => {
    if (!files || files.length === 0) return
    setBusy(true)
    let uploaded = 0
    const failures: string[] = []
    for (const file of Array.from(files)) {
      try {
        await api.webDavUpload(joinWebDavPath(path, file.name), file)
        uploaded += 1
      } catch (error) {
        failures.push(`${file.name}：${error instanceof Error ? error.message : t('webdav.uploadFailed', { defaultValue: '上传失败' })}`)
      }
    }
    setBusy(false)
    if (uploaded > 0) toast.success(t('webdav.uploadedCount', { count: uploaded, defaultValue: `已上传 ${uploaded} 个文件` }))
    if (failures.length > 0) toast.error(failures.join('；'))
    if (uploadInputRef.current) uploadInputRef.current.value = ''
    void load(path)
  }

  const handleCreateFolder = async () => {
    const name = window.prompt(t('webdav.folderNamePrompt', { defaultValue: '新建文件夹名称' }))?.trim()
    if (!name) return
    setBusy(true)
    try {
      await api.webDavCreateFolder(joinWebDavPath(path, name))
      toast.success(t('webdav.folderCreated', { defaultValue: '文件夹已创建' }))
      void load(path)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('webdav.createFolderFailed', { defaultValue: '创建文件夹失败' }))
    } finally {
      setBusy(false)
    }
  }

  const handleDelete = async (entry: { name: string; path: string; directory: boolean }) => {
    const targetName = entry.directory ? t('webdav.folder', { defaultValue: '文件夹' }) : t('webdav.file', { defaultValue: '文件' })
    const extraMsg = entry.directory ? t('webdav.deleteDirExtra', { defaultValue: '其中的内容会一并删除。' }) : ''
    if (!window.confirm(t('webdav.deleteEntryConfirm', { target: targetName, name: entry.name, extra: extraMsg, defaultValue: `确定要删除${targetName}「${entry.name}」吗？${extraMsg}` }))) return
    setBusy(true)
    try {
      await api.webDavDelete(entry.path)
      toast.success(t('webdav.deleted', { defaultValue: '已删除' }))
      void load(path)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('webdav.deleteFailed', { defaultValue: '删除失败' }))
    } finally {
      setBusy(false)
    }
  }

  const handleImport = async (entry: { name: string; path: string }) => {
    if (!window.confirm(t('webdav.importBackupConfirm', { name: entry.name, defaultValue: `导入备份「${entry.name}」？\n会写入其中的书源、替换净化规则、书架、分组、书签与阅读进度（同名书源/规则按其 ID 覆盖，书架按书合并，进度只在更新时间较新时覆盖）。\n\n本地图书与音频（听书）会被跳过——服务端读不到手机本机文件，也只支持文本阅读；挂在被跳过书籍上的书签同样跳过。` }))) return
    setBusy(true)
    try {
      const summary = await api.webDavImport(entry.path)
      const total = (a: number, b: number) => a + b
      // 本地图书（手机本机文件）与音频/听书在服务端没有可用能力，导入时会被跳过，
      // 这里如实告知用户，避免「明明导入了却少了几十本」的困惑。
      const skipped = (summary.skippedLocal ?? 0) + (summary.skippedAudio ?? 0)
      const skipNote = skipped > 0
        ? t('webdav.backupSkipLocalNote', {
            count: skipped,
            local: summary.skippedLocal ?? 0,
            audio: summary.skippedAudio ?? 0,
            defaultValue: `；已跳过 ${skipped} 条服务端用不了的书（本地图书 ${summary.skippedLocal ?? 0}、音频听书 ${summary.skippedAudio ?? 0}）`,
          })
        : ''
      // 书签同理：挂在被跳过书籍上的书签没有展示位置，也一并跳过并如实报告。
      const markNote = (summary.bookmarksSkipped ?? 0) > 0
        ? t('webdav.backupBookmarksSkippedNote', {
            count: summary.bookmarks ?? 0,
            skipped: summary.bookmarksSkipped,
            defaultValue: `；书签 ${summary.bookmarks ?? 0} 条（跳过 ${summary.bookmarksSkipped} 条，其所属书籍未导入）`,
          })
        : t('webdav.backupBookmarksNote', {
            count: summary.bookmarks ?? 0,
            defaultValue: `；书签 ${summary.bookmarks ?? 0} 条`,
          })
      if (total(summary.sources, summary.sourcesUpdated) + total(summary.rules, summary.rulesUpdated) + total(summary.books, summary.booksUpdated) === 0) {
        toast.warning(t('webdav.backupEmptyWarning', { skipNote, defaultValue: `备份包里没有可导入的内容（已忽略 RSS / TTS / 主题等条目）${skipNote}` }))
      } else {
        // 书源分组没有独立文件，它是书源自带的 `bookSourceGroup`，随书源一起落库；
        // 只有带进来过才提一句，避免在提示里堆一个恒为 0 的字段。
        const sourceGroupNote = (summary.sourceGroups ?? 0) > 0
          ? t('webdav.backupSourceGroupsNote', { count: summary.sourceGroups, defaultValue: `（含书源分组 ${summary.sourceGroups} 个）` })
          : ''
        toast.success(
          t('webdav.backupImportSuccessSummary', {
            sources: total(summary.sources, summary.sourcesUpdated),
            sourceGroupNote,
            rules: total(summary.rules, summary.rulesUpdated),
            books: total(summary.books, summary.booksUpdated),
            progress: summary.progress,
            markNote,
            skipNote,
            defaultValue:
              `导入完成：书源 ${total(summary.sources, summary.sourcesUpdated)}${sourceGroupNote}，` +
              `替换规则 ${total(summary.rules, summary.rulesUpdated)}，` +
              `书籍 ${total(summary.books, summary.booksUpdated)}，阅读进度 ${summary.progress}` +
              markNote +
              skipNote,
          }),
        )
      }
      void load(path)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('webdav.backupImportFailed', { defaultValue: '备份导入失败' }))
    } finally {
      setBusy(false)
    }
  }

  /**
   * 把 WebDAV 存储区里的**一本书**导入书架（TXT / EPUB）。
   *
   * 与「导入本地书籍」（浏览器上传）等价，只是字节已经在服务器上，
   * 所以直接传路径、不必再上传一遍 —— 对放在 WebDAV 里的大文件尤其省事。
   */
  const handleImportWebDavBook = async (entry: { name: string; path: string }) => {
    setBusy(true)
    try {
      const result = await api.importWebDavBook(entry.path)
      const item = result.results[0]
      if (item?.success) {
        toast.success(t('webdav.importedBookSuccess', { name: item.name ?? entry.name, chapters: item.totalChapters ? `（${item.totalChapters} 章）` : '', defaultValue: `已导入《${item.name ?? entry.name}》${item.totalChapters ? `（${item.totalChapters} 章）` : ''}` }))
      } else {
        toast.error(item?.error ?? t('webdav.importFailed', { defaultValue: '导入失败' }))
      }
      void load(path)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : t('webdav.importBookFailed', { defaultValue: '导入书籍失败' }))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="webdav-page">
      <header className="page-title">
        <div>
          <span className="section-kicker">{t('webdav.sectionKicker', { defaultValue: '文件服务' })}</span>
          <h1>WebDAV</h1>
          <p>{t('webdav.sectionDesc', { defaultValue: '把电脑或手机上的文件直接投递到服务器数据目录；也能在这里同步阅读进度、导入本地书籍，或按 Legado 格式导出备份。' })}</p>
        </div>
        <button type="button" className="ghost-button" onClick={refresh} disabled={loading}>
          <Icon name="refresh" />
          <span>{t('common.refresh', { defaultValue: '刷新' })}</span>
        </button>
      </header>

      <section className="webdav-status-grid">
        <article className="webdav-card">
          <span className="webdav-card-label">{t('webdav.serviceStatus', { defaultValue: '服务状态' })}</span>
          <strong className="webdav-card-value">
            <span className="webdav-status-dot" />{t('webdav.running', { defaultValue: '运行中' })}
          </strong>
          <span className="webdav-card-hint">{t('webdav.builtInHint', { defaultValue: '已内置在服务端，无需额外端口或容器' })}</span>
        </article>
        <article className="webdav-card">
          <span className="webdav-card-label">{t('webdav.accessUrl', { defaultValue: '访问地址' })}</span>
          <strong className="webdav-card-value webdav-card-mono">{origin}{info?.url ?? '/webdav'}</strong>
          <button
            type="button"
            className="subtle-button"
            onClick={() => void copyText(`${origin}${info?.url ?? '/webdav'}`, t('webdav.urlCopied', { defaultValue: '访问地址已复制' }))}
          >
            <Icon name="copy" />
            <span>{t('webdav.copyUrl', { defaultValue: '复制地址' })}</span>
          </button>
        </article>
        <article className="webdav-card">
          <span className="webdav-card-label">{t('webdav.authScheme', { defaultValue: '认证方式' })}</span>
          <strong className="webdav-card-value">HTTP Basic</strong>
          <span className="webdav-card-hint">{t('webdav.authHint', { defaultValue: '用户名任意填写，密码即当前登录密码' })}</span>
        </article>
        <article className="webdav-card">
          <span className="webdav-card-label">{t('webdav.storedData', { defaultValue: '已存数据' })}</span>
          <strong className="webdav-card-value">
            {info ? t('webdav.filesAndBytes', { count: info.fileCount, size: formatDavSize(info.totalBytes), defaultValue: `${info.fileCount} 个文件 · ${formatDavSize(info.totalBytes)}` }) : '—'}
          </strong>
          <span className="webdav-card-hint">{info ? t('webdav.dirsAndPath', { count: info.directoryCount, path: info.directory, defaultValue: `${info.directoryCount} 个文件夹 · ${info.directory}` }) : t('common.loading', { defaultValue: '正在读取…' })}</span>
        </article>
      </section>

      <section className="webdav-section">
        <h2 className="webdav-section-title">{t('webdav.syncTitle', { defaultValue: '阅读进度同步' })}</h2>
        <p className="webdav-section-desc">
          {t('webdav.syncDesc', { defaultValue: '读取并写回 Legado 手机端的进度文件夹。打开书籍时会取「数据库」与「进度文件」中较新的一份；之后每翻一章都会自动写回进度文件，手机与网页进度保持一致。' })}
        </p>
        <div className="progress-sync-card">
          {/* 输入框后面直接跟「保存」，不再单列一行按钮区（原「重新读取」按钮已删除：
              每次进入本页都会自动读取，手动刷新没有意义） */}
          <div className="progress-sync-row">
            <label className="progress-sync-field">
              <span className="progress-sync-label">{t('webdav.syncFolderLabel', { defaultValue: '进度文件夹名' })}</span>
              <input
                type="text"
                value={syncDirInput}
                placeholder="bookProgress"
                onChange={e => setSyncDirInput(e.target.value)}
                spellCheck={false}
                disabled={syncSaving}
              />
            </label>
            <button type="button" className="primary-button" onClick={() => void handleSaveSyncSettings()} disabled={syncSaving}>
              {syncSaving ? t('common.saving', { defaultValue: '保存中…' }) : t('common.save', { defaultValue: '保存' })}
            </button>
          </div>
          <small className="progress-sync-hint">
            {t('webdav.syncFolderHint', { defaultValue: '相对 WebDAV 根目录。手机端备份通常是 legado/bookProgress，就按这个填（支持多级子目录）。' })}
          </small>
          <div className="progress-sync-status">
            {syncSettings === null ? (
              <span className="progress-sync-badge is-warn">{t('webdav.syncBadgeWarn', { defaultValue: '未读取到配置' })}</span>
            ) : syncSettings.available ? (
              <>
                <span className="progress-sync-badge is-ok">{t('webdav.syncBadgeEnabled', { defaultValue: '已启用' })}</span>
                <span className="progress-sync-path">
                  {t('webdav.syncFolderInfo', { path: syncSettings.directoryPath, count: syncSettings.fileCount, defaultValue: `${syncSettings.directoryPath} · 已发现 ${syncSettings.fileCount} 个进度文件` })}
                </span>
              </>
            ) : (
              <>
                <span className="progress-sync-badge is-warn">{t('webdav.syncBadgeNotCreated', { defaultValue: '文件夹不存在' })}</span>
                <span className="progress-sync-path">
                  {t('webdav.syncFolderPending', { path: info?.directory ?? 'webdav', name: syncSettings.directoryName, defaultValue: `路径 ${info?.directory ?? 'webdav'}/${syncSettings.directoryName} 尚未创建，首次同步时会自动建好` })}
                </span>
              </>
            )}
          </div>
        </div>
      </section>

      <section className="webdav-section">
        <h2 className="webdav-section-title">{t('webdav.exportTitle', { defaultValue: '备份导出' })}</h2>
        <p className="webdav-section-desc">
          {t('webdav.exportDesc', { defaultValue: '按 Legado 备份包格式导出书源及其分组、书架及书籍分组、阅读进度，写入下面的导出路径。导出的 zip 会出现在「文件管理」里，可直接下载，也能被手机 App 通过 WebDAV 取走。' })}
        </p>
        <div className="progress-sync-card">
          <div className="progress-sync-row">
            <label className="progress-sync-field">
              <span className="progress-sync-label">{t('webdav.exportPathLabel', { defaultValue: '导出路径' })}</span>
              <input
                type="text"
                value={exportDirInput}
                placeholder={t('webdav.exportPathPlaceholder', { defaultValue: '留空 = WebDAV 根目录' })}
                onChange={e => setExportDirInput(e.target.value)}
                spellCheck={false}
                disabled={exportSaving}
              />
            </label>
            <button type="button" className="primary-button" onClick={() => void handleSaveExportSettings()} disabled={exportSaving}>
              {exportSaving ? t('common.saving', { defaultValue: '保存中…' }) : t('common.save', { defaultValue: '保存' })}
            </button>
          </div>
          <div className="progress-sync-row">
            <label className="progress-sync-field">
              <span className="progress-sync-label">{t('webdav.exportDeviceLabel', { defaultValue: '设备名后缀' })}</span>
              <input
                type="text"
                value={deviceNameInput}
                placeholder="CD_Watch_A"
                onChange={e => setDeviceNameInput(e.target.value)}
                spellCheck={false}
                disabled={exportSaving}
              />
            </label>
          </div>
          <small className="progress-sync-hint">
            {t('webdav.exportHint', { defaultValue: '导出路径相对 WebDAV 根目录，留空即根目录（支持多级子目录）。设备名拼在日期之后，生成 backup2026-09-30-CD_Watch_A.zip 这样的文件名；留空则只有日期。' })}
          </small>
          {/* 自动导出触发点：开关存在服务端，前端只上报「发生了触发」，由服务端决定要不要写盘 */}
          <div className="export-auto-switches">
            <label className="export-auto-switch">
              <input
                type="checkbox"
                checked={autoPageCloseInput}
                onChange={e => setAutoPageCloseInput(e.target.checked)}
                disabled={exportSaving}
              />
              <span>{t('webdav.exportOnPageClose', { defaultValue: '关闭网页时自动备份' })}</span>
            </label>
            <label className="export-auto-switch">
              <input
                type="checkbox"
                checked={autoBookCloseInput}
                onChange={e => setAutoBookCloseInput(e.target.checked)}
                disabled={exportSaving}
              />
              <span>{t('webdav.exportOnBookClose', { defaultValue: '关闭书籍时自动备份' })}</span>
            </label>
          </div>
          <small className="progress-sync-hint">
            {t('webdav.autoExportHint', { defaultValue: '勾选后，关闭本页面或退出阅读时会自动导出一次。同一天、同一设备名只保留最新一份（新备份直接覆盖旧备份）。' })}
          </small>
          {exportSettings !== null && (
            <div className="progress-sync-status">
              <span className="progress-sync-badge is-ok">{t('webdav.exportBadgeSaved', { defaultValue: '已配置' })}</span>
              <span className="progress-sync-path">
                {t('webdav.exportCurrent', { dir: exportSettings.exportDir || '/', name: exportSettings.deviceName || t('webdav.exportNoDevice', { defaultValue: '未设设备名' }), defaultValue: `导出到 ${exportSettings.exportDir || 'WebDAV 根目录'} · ${exportSettings.deviceName || '未设设备名'}` })}
              </span>
            </div>
          )}
        </div>
      </section>

      <section className="webdav-section">
        <div className="webdav-files-head">
          <h2 className="webdav-section-title">{t('webdav.localBooksTitle', { defaultValue: '本地书籍' })}</h2>
          <div className="webdav-files-actions">
            <input
              ref={localBookInputRef}
              type="file"
              multiple
              hidden
              accept={LOCAL_BOOK_ACCEPT}
              onChange={event => {
                void handleImportLocalBooks(event.target.files)
                // 清空 value，否则连续选同一个文件不会再触发 change
                event.target.value = ''
              }}
            />
            <button
              type="button"
              className="primary-button"
              onClick={() => localBookInputRef.current?.click()}
              disabled={localBookBusy}
            >
              <Icon name="upload" />
              <span>{localBookBusy ? t('webdav.importing', { defaultValue: '导入中…' }) : t('webdav.importLocalBooks', { defaultValue: '导入本地书籍' })}</span>
            </button>
          </div>
        </div>
        <p className="webdav-section-desc">
          {t('webdav.localBooksHint', { defaultValue: '支持 TXT 与 EPUB 两种格式，可一次选择多本。TXT 会自动探测编码（UTF-8 / GB18030）并按章节标题智能分章；EPUB 会读取其自带目录与封面。导入后书籍进入书架，可直接在线阅读。' })}
        </p>
      </section>

      <section className="webdav-section">
        <div className="webdav-files-head">
          <h2 className="webdav-section-title">{t('webdav.fileManagerTitle', { defaultValue: '文件管理' })}</h2>
          <div className="webdav-files-actions">
            <input
              ref={uploadInputRef}
              type="file"
              multiple
              hidden
              onChange={event => void handleUpload(event.target.files)}
            />
            <button type="button" className="subtle-button" onClick={handleCreateFolder} disabled={busy}>
              <Icon name="plus" />
              <span>{t('webdav.newFolder', { defaultValue: '新建文件夹' })}</span>
            </button>
            <button
              type="button"
              className="subtle-button"
              onClick={() => void handleExportBackup()}
              disabled={busy || exporting}
              title={t('webdav.exportBackupTitle', { defaultValue: '按 Legado 备份格式导出书源及分组、书架及分组、阅读进度到导出路径' })}
            >
              <Icon name="download" />
              <span>{exporting ? t('webdav.exporting', { defaultValue: '导出中…' }) : t('webdav.exportBackup', { defaultValue: '导出备份' })}</span>
            </button>
            <button type="button" className="primary-button" onClick={() => uploadInputRef.current?.click()} disabled={busy}>
              <Icon name="upload" />
              <span>{busy ? t('common.loading', { defaultValue: '处理中…' }) : t('webdav.uploadFile', { defaultValue: '上传文件' })}</span>
            </button>
          </div>
        </div>

        <nav className="webdav-breadcrumbs" aria-label={t('webdav.breadcrumbsAria', { defaultValue: 'WebDAV 目录路径' })}>
          {breadcrumbs.map((crumb, index) => (
            <React.Fragment key={crumb.path || 'root'}>
              {index > 0 && <span className="webdav-crumb-sep">/</span>}
              <button
                type="button"
                className={crumb.path === path ? 'webdav-crumb active' : 'webdav-crumb'}
                onClick={() => setPath(crumb.path)}
              >
                {crumb.name}
              </button>
            </React.Fragment>
          ))}
        </nav>

        {loading ? (
          <p className="webdav-empty">{t('webdav.loadingDirectory', { defaultValue: '正在读取目录…' })}</p>
        ) : entries.length === 0 ? (
          <p className="webdav-empty">{t('webdav.emptyDirectory', { defaultValue: '这个目录还是空的，点击右上角「上传文件」投递第一个文件。' })}</p>
        ) : (
          <ul className="webdav-file-list">
            {entries.map(entry => (
              <li key={entry.path} className="webdav-file-row">
                <span className={`webdav-file-icon ${entry.directory ? 'is-folder' : ''}`}>
                  <Icon name={entry.directory ? 'folder' : 'file'} />
                </span>
                <div className="webdav-file-main">
                  {entry.directory ? (
                    <button type="button" className="webdav-file-name is-link" onClick={() => setPath(entry.path)}>
                      {entry.name}
                    </button>
                  ) : (
                    <span className="webdav-file-name">{entry.name}</span>
                  )}
                  <span className="webdav-file-meta">
                    {entry.directory ? t('webdav.folder', { defaultValue: '文件夹' }) : formatDavSize(entry.size)} · {formatDavTime(entry.modifiedAt, Date.now(), t)}
                  </span>
                </div>
                <div className="webdav-file-actions">
                  {!entry.directory && isBackupArchive(entry.name) && (
                    <button
                      type="button"
                      className="subtle-button"
                      onClick={() => void handleImport(entry)}
                      disabled={busy}
                      title={t('webdav.importBackupTitle', { defaultValue: '导入 Legado 备份包中的书源、替换规则、书架与阅读进度' })}
                    >
                      <Icon name="importFile" />
                      <span>{t('webdav.importBackup', { defaultValue: '导入' })}</span>
                    </button>
                  )}
                  {!entry.directory && isLocalBookFile(entry.name) && (
                    <button
                      type="button"
                      className="subtle-button"
                      onClick={() => void handleImportWebDavBook(entry)}
                      disabled={busy}
                      title={t('webdav.importBookToShelfTitle', { defaultValue: '把这本书导入书架（仅 TXT / EPUB），导入后可直接阅读' })}
                    >
                      <Icon name="book" />
                      <span>{t('webdav.importBookToShelf', { defaultValue: '导入书籍' })}</span>
                    </button>
                  )}
                  {!entry.directory && (
                    <a
                      className="subtle-button"
                      href={webDavFileUrl(entry.path)}
                      download={entry.name}
                      title={t('webdav.download', { defaultValue: '下载' })}
                    >
                      <Icon name="download" />
                      <span>{t('webdav.download', { defaultValue: '下载' })}</span>
                    </a>
                  )}
                  <button
                    type="button"
                    className="danger-btn"
                    onClick={() => void handleDelete(entry)}
                    disabled={busy}
                    title={t('common.delete', { defaultValue: '删除' })}
                  >
                    <Icon name="trash" />
                    <span>{t('common.delete', { defaultValue: '删除' })}</span>
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <p className="webdav-notice">
        <Icon name="settings" />
        <span>{t('webdav.securityHint', { defaultValue: 'WebDAV 使用 HTTP Basic 认证，公网部署请务必通过 HTTPS 反向代理访问；反代层还需调大 client_max_body_size 才能上传大文件。' })}</span>
      </p>
    </main>
  )
}
