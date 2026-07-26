export type SourceSummary = { id: string; name: string; url: string; group?: string; enabled: boolean; isJsSource: boolean; updatedAt: number; version: number }
export type SourceRecord = { id: string; json: string; version: number; updatedAt: number }
export type SearchResult = { sourceId: string; name: string; author?: string; bookUrl: string; coverUrl?: string; intro?: string }
export type BookDetails = { sourceId: string; name: string; author?: string; intro?: string; coverUrl?: string; tocUrl: string }
export type Chapter = { index: number; title: string; url: string }
export type ReadingProgress = { sourceId: string; bookUrl: string; chapterUrl: string; chapterIndex: number; scrollPosition: number; updatedAt: number }
export type BookshelfItem = { sourceId: string; bookUrl: string; name: string; author?: string; tocUrl: string; coverKey?: string; chapterIndex?: number; scrollPosition?: number; lastReadAt: number }
export type BookshelfWrite = { sourceId: string; bookUrl: string; name: string; author?: string; tocUrl: string; coverUrl?: string }

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
  import: (sources: string[]) => request<{ imported: number; skipped: number; errors: string[] }>('/api/sources/import', { method: 'POST', body: JSON.stringify({ sources }) }),
  search: (keyword: string, sourceIds?: string[]) => request<SearchResult[]>('/api/search', { method: 'POST', body: JSON.stringify({ keyword, sourceIds }) }),
  details: (sourceId: string, bookUrl: string) => request<BookDetails>('/api/books/details', { method: 'POST', body: JSON.stringify({ sourceId, bookUrl }) }),
  chapters: (sourceId: string, bookUrl: string) => request<Chapter[]>('/api/books/chapters', { method: 'POST', body: JSON.stringify({ sourceId, bookUrl }) }),
  content: (sourceId: string, chapterUrl: string) => request<{ title?: string; content: string }>('/api/books/content', { method: 'POST', body: JSON.stringify({ sourceId, chapterUrl }) }),
  progress: (sourceId: string, bookUrl: string) => request<ReadingProgress | undefined>(`/api/reading-progress?sourceId=${encodeURIComponent(sourceId)}&bookUrl=${encodeURIComponent(bookUrl)}`),
  saveProgress: (sourceId: string, bookUrl: string, chapterUrl: string, chapterIndex: number, scrollPosition: number) => request<ReadingProgress>('/api/reading-progress', { method: 'PUT', body: JSON.stringify({ sourceId, bookUrl, chapterUrl, chapterIndex, scrollPosition }) }),
  bookshelf: () => request<BookshelfItem[]>('/api/bookshelf'),
  addToBookshelf: (book: BookshelfWrite) => request<BookshelfItem>('/api/bookshelf', { method: 'POST', body: JSON.stringify(book) }),
  removeFromBookshelf: (sourceId: string, bookUrl: string) => request<void>(`/api/bookshelf?sourceId=${encodeURIComponent(sourceId)}&bookUrl=${encodeURIComponent(bookUrl)}`, { method: 'DELETE' }),
  cover: (key: string) => `/api/covers/${encodeURIComponent(key)}`,
}
