import {
  putOfflineChapter,
  getOfflineChapter,
  saveShelfSnapshot,
  getShelfSnapshot,
  enqueueOfflineProgress,
  flushOfflineProgress,
} from './offlineStorage'

export type SourceSummary = { id: string; name: string; url: string; group?: string; enabled: boolean; isJsSource: boolean; hasLogin: boolean; updatedAt: number; version: number }
export type SourceRecord = { id: string; json: string; version: number; updatedAt: number }
export type SearchResult = { sourceId: string; name: string; author?: string; bookUrl: string; coverUrl?: string; intro?: string }
export type BookDetails = { sourceId: string; name: string; author?: string; intro?: string; coverUrl?: string; tocUrl: string; alternateSources?: SearchResult[] }
export type Chapter = { index: number; title: string; url: string }
export type ReadingProgress = { sourceId: string; bookUrl: string; chapterUrl: string; chapterIndex: number; scrollPosition: number; updatedAt: number }
export type BookshelfItem = { sourceId: string; bookUrl: string; name: string; author?: string; tocUrl: string; coverKey?: string; coverUrl?: string; chapterIndex?: number; scrollPosition?: number; lastReadAt: number; cachedChapters: number; totalChapters: number; cacheState: 'idle' | 'caching' | 'ready' | 'failed'; cacheError?: string; completed: boolean; alternateSources?: SearchResult[]; groupName?: string }
export type BookshelfWrite = { sourceId: string; bookUrl: string; name: string; author?: string; tocUrl: string; coverUrl?: string; alternateSources?: SearchResult[]; groupName?: string }
export type BookshelfSourceSwitch = { oldSourceId: string; oldBookUrl: string; book: BookshelfWrite; alternateSources?: SearchResult[] }
export type BookGroup = { id: number; name: string; sortOrder: number; bookCount: number }
export type BookshelfBatchRequest = {
  action: 'move_group' | 'mark_completed' | 'delete'
  items: { sourceId: string; bookUrl: string }[]
  targetGroup?: string
  completed?: boolean
}
export type ImportResponse = { imported: number; updated: number; skipped: number; errors: string[] }
export type LocalBookImportItem = { filename: string; success: boolean; bookUrl?: string; name?: string; author?: string; totalChapters: number; error?: string }
export type LocalBookImportResponse = { total: number; imported: number; failed: number; results: LocalBookImportItem[] }
export type SourceSubscription = { id: number; url: string; enabled: boolean; createdAt: number; updatedAt: number; lastSuccessAt?: number; lastAttemptAt?: number; lastError?: string; lastImported: number; contentHash?: string }
export type SearchStreamEvent = { type: 'start' | 'results' | 'progress' | 'done' | 'error'; totalSources: number; completedSources: number; matchedSources: number; emptySources: number; failedSources: number; resultCount: number; results: SearchResult[]; message?: string }

export type BatchSourceAction = 'enable' | 'disable' | 'delete' | 'set_group'
export type BatchSourceRequest = {
  action: BatchSourceAction
  ids: string[]
  group?: string
}

export type BatchSourceResponse = {
  ok: boolean
  affected: number
  action: string
  message?: string
}

export type SourceHealthCheckItem = {
  id: string
  name: string
  ok: boolean
  latencyMs: number
  statusCode: number
  statusCategory: 'valid' | 'slow' | 'failed' | 'blocked'
  error?: string
}

export type SourceHealthCheckResponse = {
  total: number
  successCount: number
  slowCount: number
  failedCount: number
  durationMs: number
  results: SourceHealthCheckItem[]
}


export type WebDavEntry = { name: string; path: string; directory: boolean; size: number; modifiedAt: number }
export type WebDavInfo = { url: string; directory: string; path: string; parent?: string | null; fileCount: number; directoryCount: number; totalBytes: number; entries: WebDavEntry[] }
export type BackupImportSummary = {
  sources: number
  sourcesUpdated: number
  rules: number
  rulesUpdated: number
  books: number
  booksUpdated: number
  progress: number
}

export type ReplaceRule = {
  id: string
  name: string
  group?: string
  pattern: string
  replacement: string
  isRegex: boolean
  scope?: string
  excludeScope?: string
  scopeTitle?: boolean
  scopeContent?: boolean
  isEnabled: boolean
  order: number
  timeoutMillisecond?: number
  createdAt?: number
  updatedAt?: number
}

export type ReplaceRuleImportResponse = {
  imported: number
  updated: number
  skipped: number
  total: number
}

export type ReplaceRulePreviewRequest = {
  text: string
  rule?: Partial<ReplaceRule>
  bookName?: string
  sourceUrl?: string
}

export type ReplaceRulePreviewResponse = {
  originalText: string
  cleanedText: string
  changed: boolean
  appliedRules: string[]
}

export type BookRecleanResponse = {
  sourceId: string
  bookUrl: string
  recleanedChapters: number
  totalChapters: number
}

export type BatchBookRecleanResponse = {
  totalRecleaned: number
  results: BookRecleanResponse[]
}

export type FlexChildStyle = {
  layout_flexGrow?: number
  layout_flexShrink?: number
  layout_alignSelf?: string
  layout_flexBasisPercent?: number
  layout_wrapBefore?: boolean
  layout_justifySelf?: string
}

export type SourceLoginUiItem = {
  name: string
  type: string
  action?: string
  chars?: (string | null)[]
  default?: string
  viewName?: string
  style?: FlexChildStyle
  key?: string
  hint?: string
  value?: string
  options?: string[]
  countdown?: number
}

export type SourceLoginUiResponse = {
  sourceId: string
  sourceName: string
  hasLogin: boolean
  loginUi: SourceLoginUiItem[]
  loginUrl?: string
  loginInfo: Record<string, string>
  loginHeader?: string
  sourceVariable?: string
}

export type SourceLoginActionResult = {
  success: boolean
  toastMessages: string[]
  openUrl?: string
  copyText?: string
  updatedLoginInfo?: Record<string, string>
  updatedLoginHeader?: string
  updatedVariable?: string
  reRenderUi: boolean
  error?: string
}

export type SourceLoginCheckResult = {
  loggedIn: boolean
  message?: string
}

export type SourceBrowserSession = {
  token: string
  startUrl: string
  expiresInSeconds: number
  inlineOnly?: boolean
}

export type SourceBrowserCookies = {
  count: number
  domains: string[]
  cookies: Record<string, string>
}


/** WebDAV 相对路径 → 逐段编码的 URL 路径（空段会被丢弃，避免出现 `//`）。 */
export const encodeWebDavPath = (path: string) =>
  path.split('/').filter(segment => segment.length > 0).map(encodeURIComponent).join('/')

/** 拼出可直接下载/预览的 WebDAV 地址（浏览器带会话 Cookie 访问，无需 Basic 凭据）。 */
export const webDavFileUrl = (path: string) => `/webdav/${encodeWebDavPath(path)}`

/** 在 WebDAV 相对路径下拼接子项名称。 */
export const joinWebDavPath = (base: string, name: string) =>
  [...base.split('/').filter(Boolean), name].join('/')

/** WebDAV 写操作：浏览器侧走会话鉴权，必须显式携带 CSRF 头。 */
async function webDavWrite(path: string, init: RequestInit): Promise<void> {
  const headers = new Headers(init.headers)
  headers.set('X-CSRF-Token', csrfToken ?? '')
  const response = await fetch(webDavFileUrl(path), { ...init, headers, credentials: 'same-origin' })
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: `操作失败（HTTP ${response.status}）` })) as { message?: string }
    throw new Error(body.message ?? `操作失败（HTTP ${response.status}）`)
  }
}

let csrfToken: string | null = null
export const setCsrfToken = (token: string | null) => { csrfToken = token }

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  if (init.method && !['GET', 'HEAD'].includes(init.method)) headers.set('X-CSRF-Token', csrfToken ?? '')
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' })
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: response.statusText })) as { message?: string }
    throw new Error(body.message ?? '请求失败')
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

export type TtsVoice = {
  id: string
  name: string
  lang: string
  gender: string
  localeName: string
  engine: string
  description?: string
}

export type TtsSpeakRequest = {
  text: string
  voice?: string
  rate?: number
  pitch?: number
  engine?: string
  customUrl?: string
  customHeader?: string
  customMethod?: string
  customBody?: string
}

export type TtsSessionInfo = {
  sessionId: string
  audioUrl: string
  eventsUrl: string
}

export type TtsSessionChunkRequest = {
  chunkId: string
  text: string
  chapterIndex: number
  paragraphIndex: number
  engine: string
  voice: string
  rate: number
  pitch: number
  customUrl?: string
  customHeader?: string
  customMethod?: string
  customBody?: string
}

export const api = {
  session: () => request<{ authenticated: boolean; csrfToken?: string }>('/api/auth/session'),
  login: (password: string) => request<{ csrfToken: string }>('/api/auth/login', { method: 'POST', body: JSON.stringify({ password }) }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  sources: (query = '') => request<SourceSummary[]>(`/api/sources?q=${encodeURIComponent(query)}`),
  source: (id: string) => request<SourceRecord>(`/api/sources/${encodeURIComponent(id)}`),
  save: (id: string, json: string, version?: number) => request<SourceRecord>(`/api/sources/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ json, version }) }),
  remove: (id: string) => request<void>(`/api/sources/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  batchSources: (action: BatchSourceAction, ids: string[], group?: string) =>
    request<BatchSourceResponse>('/api/sources/batch', { method: 'POST', body: JSON.stringify({ action, ids, group }) }),
  healthCheckSources: (ids?: string[], timeoutMs = 5000) =>
    request<SourceHealthCheckResponse>('/api/sources/health-check', { method: 'POST', body: JSON.stringify({ ids, timeoutMs }) }),
  exportSources: (ids?: string[]) =>
    request<string[]>(`/api/sources/export${ids && ids.length > 0 ? `?${ids.map(id => `id=${encodeURIComponent(id)}`).join('&')}` : ''}`),
  validate: (id: string) => request<{ valid: boolean; errors: string[]; warnings: string[] }>(`/api/sources/${encodeURIComponent(id)}/validate`, { method: 'POST' }),
  import: (sources: string[]) => request<ImportResponse>('/api/sources/import', { method: 'POST', body: JSON.stringify({ sources }) }),
  subscriptions: () => request<SourceSubscription[]>('/api/subscriptions'),
  saveSubscription: (url: string, enabled = true) => request<SourceSubscription>('/api/subscriptions', { method: 'POST', body: JSON.stringify({ url, enabled }) }),
  removeSubscription: (id: number) => request<void>(`/api/subscriptions/${id}`, { method: 'DELETE' }),
  updateSubscription: (id: number) => request<ImportResponse>(`/api/subscriptions/${id}/update`, { method: 'POST' }),
  updateSubscriptions: () => request<{ updated: number; failed: number }>('/api/subscriptions/update', { method: 'POST' }),
  getSourceLoginUi: (sourceId: string) => request<SourceLoginUiResponse>(`/api/sources/${encodeURIComponent(sourceId)}/login-ui`),
  saveSourceLoginInfo: (sourceId: string, loginInfo: Record<string, string>) => request<{ ok: boolean }>(`/api/sources/${encodeURIComponent(sourceId)}/login-info`, { method: 'POST', body: JSON.stringify({ loginInfo }) }),
  saveSourceLoginHeader: (sourceId: string, loginHeader: string) => request<{ ok: boolean }>(`/api/sources/${encodeURIComponent(sourceId)}/login-header`, { method: 'POST', body: JSON.stringify({ loginHeader }) }),
  saveSourceCookie: (sourceId: string, cookie: string, url?: string) => request<{ ok: boolean; message?: string }>(`/api/sources/${encodeURIComponent(sourceId)}/login-cookie`, { method: 'POST', body: JSON.stringify({ cookie, url }) }),
  clearSourceLoginInfo: (sourceId: string) => request<{ ok: boolean }>(`/api/sources/${encodeURIComponent(sourceId)}/login-info`, { method: 'DELETE' }),
  clearSourceLoginHeader: (sourceId: string) => request<{ ok: boolean }>(`/api/sources/${encodeURIComponent(sourceId)}/login-header`, { method: 'DELETE' }),
  executeSourceLoginAction: (sourceId: string, action: string, loginData: Record<string, string>, isLongClick = false) => request<SourceLoginActionResult>(`/api/sources/${encodeURIComponent(sourceId)}/login-action`, { method: 'POST', body: JSON.stringify({ action, loginData, isLongClick }) }),
  checkSourceLogin: (sourceId: string) => request<SourceLoginCheckResult>(`/api/sources/${encodeURIComponent(sourceId)}/login-check`),
  createSourceBrowserSession: (sourceId: string, url?: string) => request<SourceBrowserSession>(`/api/sources/${encodeURIComponent(sourceId)}/browser/session`, { method: 'POST', body: JSON.stringify({ url }) }),
  closeSourceBrowserSession: (sourceId: string, token: string) => request<void>(`/api/sources/${encodeURIComponent(sourceId)}/browser/session?t=${encodeURIComponent(token)}`, { method: 'DELETE' }),
  sourceBrowserCookies: (sourceId: string) => request<SourceBrowserCookies>(`/api/sources/${encodeURIComponent(sourceId)}/browser/cookies`),
  sourceBrowserPageUrl: (sourceId: string, token: string, target: string) =>
    `/api/sources/${encodeURIComponent(sourceId)}/browser/page?t=${encodeURIComponent(token)}&u=${encodeURIComponent(target)}`,
  /** 书源 JS 生成的数据地址页面：交给服务端解码托管，避免超长 URL 触发请求行 8192 上限 */
  registerSourceBrowserInline: (sourceId: string, token: string, url: string) =>
    request<{ key: string }>(`/api/sources/${encodeURIComponent(sourceId)}/browser/inline`, { method: 'POST', body: JSON.stringify({ token, url }) }),
  sourceBrowserInlineUrl: (sourceId: string, token: string, key: string) =>
    `/api/sources/${encodeURIComponent(sourceId)}/browser/page?t=${encodeURIComponent(token)}&i=${encodeURIComponent(key)}`,
  search: (keyword: string, sourceIds?: string[], signal?: AbortSignal) => request<SearchResult[]>('/api/search', { method: 'POST', body: JSON.stringify({ keyword, sourceIds }), signal }),
  details: (sourceId: string, bookUrl: string, signal?: AbortSignal) => request<BookDetails>('/api/books/details', { method: 'POST', body: JSON.stringify({ sourceId, bookUrl }), signal }),
  chapters: (sourceId: string, bookUrl: string, signal?: AbortSignal) => request<Chapter[]>('/api/books/chapters', { method: 'POST', body: JSON.stringify({ sourceId, bookUrl }), signal }),
  content: async (sourceId: string, chapterUrl: string, bookUrl?: string, signal?: AbortSignal) => {
    try {
      const res = await request<{ title?: string; content: string }>('/api/books/content', { method: 'POST', body: JSON.stringify({ sourceId, chapterUrl, bookUrl }), signal })
      if (res?.content && bookUrl) {
        void putOfflineChapter(sourceId, bookUrl, chapterUrl, res.title || null, res.content)
      }
      return res
    } catch (err) {
      if (bookUrl) {
        const offline = await getOfflineChapter(sourceId, bookUrl, chapterUrl)
        if (offline && offline.content) {
          return { title: offline.title ?? undefined, content: offline.content }
        }
      }
      throw err
    }
  },
  progress: (sourceId: string, bookUrl: string, signal?: AbortSignal) => request<ReadingProgress | undefined>(`/api/reading-progress?sourceId=${encodeURIComponent(sourceId)}&bookUrl=${encodeURIComponent(bookUrl)}`, { signal }),
  saveProgress: async (sourceId: string, bookUrl: string, chapterUrl: string, chapterIndex: number, scrollPosition: number) => {
    const progressItem = { sourceId, bookUrl, chapterUrl, chapterIndex, scrollPosition, updatedAt: Date.now() }
    enqueueOfflineProgress(progressItem)
    try {
      const res = await request<ReadingProgress>('/api/reading-progress', { method: 'PUT', body: JSON.stringify({ sourceId, bookUrl, chapterUrl, chapterIndex, scrollPosition }) })
      void flushOfflineProgress(async (item) => {
        await request<ReadingProgress>('/api/reading-progress', { method: 'PUT', body: JSON.stringify(item) })
      })
      return res
    } catch {
      return { sourceId, bookUrl, chapterUrl, chapterIndex, scrollPosition, updatedAt: progressItem.updatedAt }
    }
  },
  bookshelf: async () => {
    try {
      const list = await request<BookshelfItem[]>('/api/bookshelf')
      saveShelfSnapshot(list)
      return list
    } catch (err) {
      const snapshot = getShelfSnapshot()
      if (snapshot && snapshot.length > 0) {
        return snapshot as BookshelfItem[]
      }
      throw err
    }
  },
  addToBookshelf: (book: BookshelfWrite) => request<BookshelfItem>('/api/bookshelf', { method: 'POST', body: JSON.stringify(book) }),
  removeFromBookshelf: (sourceId: string, bookUrl: string) => request<void>(`/api/bookshelf?sourceId=${encodeURIComponent(sourceId)}&bookUrl=${encodeURIComponent(bookUrl)}`, { method: 'DELETE' }),
  cacheBookshelfBook: (sourceId: string, bookUrl: string) => request<{ status: string }>('/api/bookshelf/cache', { method: 'POST', body: JSON.stringify({ sourceId, bookUrl }) }),
  cacheBookshelfRange: (data: { sourceId: string; bookUrl: string; startIndex?: number; endIndex?: number; count?: number }) =>
    request<{ status: string }>('/api/bookshelf/cache', { method: 'POST', body: JSON.stringify(data) }),
  getCachedChapters: (sourceId: string, bookUrl: string) =>
    request<{ sourceId: string; bookUrl: string; cachedChapterUrls: string[]; cachedCount: number; totalChapters: number }>(
      `/api/bookshelf/cached-chapters?sourceId=${encodeURIComponent(sourceId)}&bookUrl=${encodeURIComponent(bookUrl)}`
    ),
  clearBookCacheData: (sourceId: string, bookUrl: string) =>
    request<void>(`/api/bookshelf/cache?sourceId=${encodeURIComponent(sourceId)}&bookUrl=${encodeURIComponent(bookUrl)}&clearData=true`, { method: 'DELETE' }),
  cancelBookCache: (sourceId: string, bookUrl: string) => request<void>(`/api/bookshelf/cache?sourceId=${encodeURIComponent(sourceId)}&bookUrl=${encodeURIComponent(bookUrl)}`, { method: 'DELETE' }),
  setBookshelfCompleted: (sourceId: string, bookUrl: string, completed: boolean) => request<BookshelfItem>('/api/bookshelf/status', { method: 'PUT', body: JSON.stringify({ sourceId, bookUrl, completed }) }),
  recleanBookCache: (sourceId: string, bookUrl: string) =>
    request<BookRecleanResponse>('/api/bookshelf/reclean', { method: 'POST', body: JSON.stringify({ sourceId, bookUrl }) }),
  batchRecleanBookCache: (books: { sourceId: string; bookUrl: string }[]) =>
    request<BatchBookRecleanResponse>('/api/bookshelf/batch-reclean', { method: 'POST', body: JSON.stringify({ books }) }),
  updateBookshelfInfo: (data: { sourceId: string; bookUrl: string; name: string; author?: string; coverUrl?: string; groupName?: string; alternateSources?: SearchResult[] }) => request<BookshelfItem>('/api/bookshelf/info', { method: 'PUT', body: JSON.stringify(data) }),
  switchBookshelfSource: (value: BookshelfSourceSwitch) => request<BookshelfItem>('/api/bookshelf/switch-source', { method: 'POST', body: JSON.stringify(value) }),
  importLocalBooks: async (files: File[]): Promise<LocalBookImportResponse> => {
    const formData = new FormData()
    for (const file of files) {
      formData.append('file', file, file.name)
    }
    const headers = new Headers()
    if (csrfToken) headers.set('X-CSRF-Token', csrfToken)
    const response = await fetch('/api/bookshelf/import-local', {
      method: 'POST',
      headers,
      credentials: 'same-origin',
      body: formData,
    })
    if (!response.ok) {
      const err = await response.json().catch(() => ({ message: response.statusText })) as { message?: string }
      throw new Error(err.message ?? '本地书籍上传失败')
    }
    return response.json() as Promise<LocalBookImportResponse>
  },
  bookGroups: () => request<BookGroup[]>('/api/bookshelf/groups'),
  createBookGroup: (name: string) => request<BookGroup>('/api/bookshelf/groups', { method: 'POST', body: JSON.stringify({ name }) }),
  renameBookGroup: (oldName: string, newName: string) => request<BookGroup>('/api/bookshelf/groups/rename', { method: 'PUT', body: JSON.stringify({ oldName, newName }) }),
  reorderBookGroups: (groupNames: string[]) => request<BookGroup[]>('/api/bookshelf/groups/order', { method: 'PUT', body: JSON.stringify({ groupNames }) }),
  deleteBookGroup: (name: string) => request<void>(`/api/bookshelf/groups?name=${encodeURIComponent(name)}`, { method: 'DELETE' }),
  updateBookGroup: (sourceId: string, bookUrl: string, groupName?: string | null) => request<BookshelfItem>('/api/bookshelf/group', { method: 'PUT', body: JSON.stringify({ sourceId, bookUrl, groupName }) }),
  batchBookshelf: (data: BookshelfBatchRequest) => request<{ affected: number }>('/api/bookshelf/batch', { method: 'POST', body: JSON.stringify(data) }),
  cover: (key: string) => `/api/covers/${encodeURIComponent(key)}`,
  webDavInfo: (path = '') => request<WebDavInfo>(`/api/webdav/info${path ? `?path=${encodeURIComponent(path)}` : ''}`),
  webDavUpload: (path: string, file: File) => webDavWrite(path, { method: 'PUT', body: file }),
  webDavCreateFolder: (path: string) => webDavWrite(path, { method: 'MKCOL' }),
  webDavDelete: (path: string) => webDavWrite(path, { method: 'DELETE' }),
  webDavImport: (path: string) => request<BackupImportSummary>('/api/webdav/import', { method: 'POST', body: JSON.stringify({ path }) }),
  getReplaceRules: (params?: { q?: string; group?: string; scope?: string }) => {
    const sp = new URLSearchParams()
    if (params?.q) sp.set('q', params.q)
    if (params?.group) sp.set('group', params.group)
    if (params?.scope) sp.set('scope', params.scope)
    const qs = sp.toString()
    return request<ReplaceRule[]>(`/api/replace-rules${qs ? `?${qs}` : ''}`)
  },
  getReplaceRule: (id: string) => request<ReplaceRule>(`/api/replace-rules/${encodeURIComponent(id)}`),
  createReplaceRule: (rule: Partial<ReplaceRule>) => request<ReplaceRule>('/api/replace-rules', { method: 'POST', body: JSON.stringify(rule) }),
  updateReplaceRule: (id: string, rule: Partial<ReplaceRule>) => request<ReplaceRule>(`/api/replace-rules/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(rule) }),
  deleteReplaceRule: (id: string) => request<void>(`/api/replace-rules/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  deleteReplaceRulesBatch: (ids: string[]) => request<{ deleted: number }>('/api/replace-rules/delete-batch', { method: 'POST', body: JSON.stringify(ids) }),
  toggleReplaceRules: (ids: string[], enabled: boolean) => request<{ updated: number }>('/api/replace-rules/toggle', { method: 'POST', body: JSON.stringify({ ids, enabled }) }),
  importReplaceRulesText: (text: string) => request<ReplaceRuleImportResponse>('/api/replace-rules/import', { method: 'POST', body: text }),
  importReplaceRulesUrl: (url: string) => request<ReplaceRuleImportResponse>('/api/replace-rules/import', { method: 'POST', body: JSON.stringify({ url }) }),
  previewReplaceRule: (req: ReplaceRulePreviewRequest) => request<ReplaceRulePreviewResponse>('/api/replace-rules/preview', { method: 'POST', body: JSON.stringify(req) }),
  getTtsVoices: () => request<TtsVoice[]>('/api/tts/voices'),
  createTtsSession: (signal?: AbortSignal) => request<TtsSessionInfo>('/api/tts/session', { method: 'POST', body: '{}', signal }),
  appendTtsSessionChunk: (sessionId: string, chunk: TtsSessionChunkRequest) => request<{ accepted: boolean }>(`/api/tts/session/${encodeURIComponent(sessionId)}/chunks`, { method: 'POST', body: JSON.stringify(chunk) }),
  controlTtsSession: (sessionId: string, action: 'pause' | 'resume' | 'stop') => request<{ accepted: boolean }>(`/api/tts/session/${encodeURIComponent(sessionId)}/control`, { method: 'POST', body: JSON.stringify({ action }) }),
  deleteTtsSession: (sessionId: string) => request<void>(`/api/tts/session/${encodeURIComponent(sessionId)}`, { method: 'DELETE' }),
  fetchTtsAudioBlob: async (req: TtsSpeakRequest): Promise<Blob> => {
    const headers = new Headers({ 'Content-Type': 'application/json' })
    if (csrfToken) headers.set('X-CSRF-Token', csrfToken)
    const response = await fetch('/api/tts/speak', {
      method: 'POST',
      headers,
      credentials: 'same-origin',
      body: JSON.stringify(req),
    })
    if (!response.ok) {
      const err = await response.json().catch(() => ({ message: response.statusText })) as { message?: string }
      throw new Error(err.message ?? '语音合成失败')
    }
    return response.blob()
  },
}

export function streamSearch(keyword: string, sourceIds: string[] | undefined, onEvent: (event: SearchStreamEvent) => void, onError: (message: string) => void, onClose: () => void): WebSocket {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  const socket = new WebSocket(`${protocol}//${location.host}/api/search/stream?csrf=${encodeURIComponent(csrfToken ?? '')}`)
  let reported = false
  socket.addEventListener('open', () => queueMicrotask(() => {
    if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ keyword, sourceIds }))
  }))
  socket.addEventListener('message', event => {
    try { onEvent(JSON.parse(event.data) as SearchStreamEvent) }
    catch { reported = true; onError('搜索响应格式无效') }
  })
  socket.addEventListener('error', () => { if (!reported) { reported = true; onError('搜索连接中断，请重试') } })
  socket.addEventListener('close', () => { if (!reported) onClose() })
  return socket
}
