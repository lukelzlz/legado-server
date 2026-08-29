export type SourceSummary = { id: string; name: string; url: string; group?: string; enabled: boolean; isJsSource: boolean; updatedAt: number; version: number }
export type SourceRecord = { id: string; json: string; version: number; updatedAt: number }
export type SearchResult = { sourceId: string; name: string; author?: string; bookUrl: string; coverUrl?: string; intro?: string }
export type BookDetails = { sourceId: string; name: string; author?: string; intro?: string; coverUrl?: string; tocUrl: string; alternateSources?: SearchResult[] }
export type Chapter = { index: number; title: string; url: string }
export type ReadingProgress = { sourceId: string; bookUrl: string; chapterUrl: string; chapterIndex: number; scrollPosition: number; updatedAt: number }
export type BookshelfItem = { sourceId: string; bookUrl: string; name: string; author?: string; tocUrl: string; coverKey?: string; chapterIndex?: number; scrollPosition?: number; lastReadAt: number; cachedChapters: number; totalChapters: number; cacheState: 'idle' | 'caching' | 'ready' | 'failed'; cacheError?: string; completed: boolean; alternateSources?: SearchResult[] }
export type BookshelfWrite = { sourceId: string; bookUrl: string; name: string; author?: string; tocUrl: string; coverUrl?: string; alternateSources?: SearchResult[] }
export type BookshelfSourceSwitch = { oldSourceId: string; oldBookUrl: string; book: BookshelfWrite; alternateSources?: SearchResult[] }
export type ImportResponse = { imported: number; updated: number; skipped: number; errors: string[] }
export type SourceSubscription = { id: number; url: string; enabled: boolean; createdAt: number; updatedAt: number; lastSuccessAt?: number; lastAttemptAt?: number; lastError?: string; lastImported: number; contentHash?: string }
export type SearchStreamEvent = { type: 'start' | 'results' | 'progress' | 'done' | 'error'; totalSources: number; completedSources: number; matchedSources: number; emptySources: number; failedSources: number; resultCount: number; results: SearchResult[]; message?: string }

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

export const api = {
  session: () => request<{ authenticated: boolean; csrfToken?: string }>('/api/auth/session'),
  login: (password: string) => request<{ csrfToken: string }>('/api/auth/login', { method: 'POST', body: JSON.stringify({ password }) }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  sources: (query = '') => request<SourceSummary[]>(`/api/sources?q=${encodeURIComponent(query)}`),
  source: (id: string) => request<SourceRecord>(`/api/sources/${encodeURIComponent(id)}`),
  save: (id: string, json: string, version?: number) => request<SourceRecord>(`/api/sources/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify({ json, version }) }),
  remove: (id: string) => request<void>(`/api/sources/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  validate: (id: string) => request<{ valid: boolean; errors: string[]; warnings: string[] }>(`/api/sources/${encodeURIComponent(id)}/validate`, { method: 'POST' }),
  import: (sources: string[]) => request<ImportResponse>('/api/sources/import', { method: 'POST', body: JSON.stringify({ sources }) }),
  subscriptions: () => request<SourceSubscription[]>('/api/subscriptions'),
  saveSubscription: (url: string, enabled = true) => request<SourceSubscription>('/api/subscriptions', { method: 'POST', body: JSON.stringify({ url, enabled }) }),
  removeSubscription: (id: number) => request<void>(`/api/subscriptions/${id}`, { method: 'DELETE' }),
  updateSubscription: (id: number) => request<ImportResponse>(`/api/subscriptions/${id}/update`, { method: 'POST' }),
  updateSubscriptions: () => request<{ updated: number; failed: number }>('/api/subscriptions/update', { method: 'POST' }),
  search: (keyword: string, sourceIds?: string[]) => request<SearchResult[]>('/api/search', { method: 'POST', body: JSON.stringify({ keyword, sourceIds }) }),
  details: (sourceId: string, bookUrl: string) => request<BookDetails>('/api/books/details', { method: 'POST', body: JSON.stringify({ sourceId, bookUrl }) }),
  chapters: (sourceId: string, bookUrl: string) => request<Chapter[]>('/api/books/chapters', { method: 'POST', body: JSON.stringify({ sourceId, bookUrl }) }),
  content: (sourceId: string, chapterUrl: string, bookUrl?: string) => request<{ title?: string; content: string }>('/api/books/content', { method: 'POST', body: JSON.stringify({ sourceId, chapterUrl, bookUrl }) }),
  progress: (sourceId: string, bookUrl: string) => request<ReadingProgress | undefined>(`/api/reading-progress?sourceId=${encodeURIComponent(sourceId)}&bookUrl=${encodeURIComponent(bookUrl)}`),
  saveProgress: (sourceId: string, bookUrl: string, chapterUrl: string, chapterIndex: number, scrollPosition: number) => request<ReadingProgress>('/api/reading-progress', { method: 'PUT', body: JSON.stringify({ sourceId, bookUrl, chapterUrl, chapterIndex, scrollPosition }) }),
  bookshelf: () => request<BookshelfItem[]>('/api/bookshelf'),
  addToBookshelf: (book: BookshelfWrite) => request<BookshelfItem>('/api/bookshelf', { method: 'POST', body: JSON.stringify(book) }),
  removeFromBookshelf: (sourceId: string, bookUrl: string) => request<void>(`/api/bookshelf?sourceId=${encodeURIComponent(sourceId)}&bookUrl=${encodeURIComponent(bookUrl)}`, { method: 'DELETE' }),
  cacheBookshelfBook: (sourceId: string, bookUrl: string) => request<{ status: string }>('/api/bookshelf/cache', { method: 'POST', body: JSON.stringify({ sourceId, bookUrl }) }),
  cancelBookCache: (sourceId: string, bookUrl: string) => request<void>(`/api/bookshelf/cache?sourceId=${encodeURIComponent(sourceId)}&bookUrl=${encodeURIComponent(bookUrl)}`, { method: 'DELETE' }),
  setBookshelfCompleted: (sourceId: string, bookUrl: string, completed: boolean) => request<BookshelfItem>('/api/bookshelf/status', { method: 'PUT', body: JSON.stringify({ sourceId, bookUrl, completed }) }),
  switchBookshelfSource: (value: BookshelfSourceSwitch) => request<BookshelfItem>('/api/bookshelf/switch-source', { method: 'POST', body: JSON.stringify(value) }),
  cover: (key: string) => `/api/covers/${encodeURIComponent(key)}`,
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
