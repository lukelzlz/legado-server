import { useSyncExternalStore } from 'react'
import { api, BookDetails, SearchResult, SearchStreamEvent, streamSearch } from './api'
import { OpenBook } from './ReaderScreen'
import { cleanAuthor, cleanTitle, defaultSearchFilters, SearchFilters, SearchGroup } from './searchFilters'

export type SourceChoiceStatus = 'idle' | 'loading' | 'loaded' | 'error'

export type SourceChoice = {
  result: SearchResult
  status: SourceChoiceStatus
  book?: OpenBook
  error?: string
}

export interface SearchStoreState {
  keyword: string
  selectedSourceId: string
  results: SearchResult[]
  choices: SourceChoice[]
  openBook: OpenBook | null
  message: string
  loading: boolean
  stopped: boolean
  detailLoading: boolean
  progress: SearchStreamEvent | null
  filters: SearchFilters
}

export const bookKey = (name: string, author?: string): string => {
  const cleanN = cleanTitle(name).toLowerCase()
  const cleanA = (cleanAuthor(author) ?? '').toLowerCase()
  return `${cleanN}\u0000${cleanA}`
}

export const groupSearchResults = (results: SearchResult[]): SearchGroup[] => {
  const groupsByKey = new Map<string, SearchGroup>()
  const titleToKnownAuthorKeys = new Map<string, string[]>()
  const titleToUnknownAuthorKey = new Map<string, string>()

  for (const result of results) {
    const rawCleanName = cleanTitle(result.name) || result.name.trim() || '未知书名'
    const rawCleanAuthor = cleanAuthor(result.author)
    const titleKey = rawCleanName.toLowerCase()

    if (rawCleanAuthor) {
      const key = `${titleKey}\u0000${rawCleanAuthor.toLowerCase()}`
      let group = groupsByKey.get(key)
      if (!group) {
        group = { key, name: rawCleanName, author: rawCleanAuthor, sources: [] }
        groupsByKey.set(key, group)
        const list = titleToKnownAuthorKeys.get(titleKey) ?? []
        list.push(key)
        titleToKnownAuthorKeys.set(titleKey, list)
      } else {
        if (group.name.includes('[') || group.name.includes('“') || group.name.length < rawCleanName.length) {
          group.name = rawCleanName
        }
      }
      group.sources.push(result)
    } else {
      const knownKeys = titleToKnownAuthorKeys.get(titleKey)
      if (knownKeys && knownKeys.length === 1) {
        const targetGroup = groupsByKey.get(knownKeys[0])!
        targetGroup.sources.push(result)
      } else {
        const unknownKey = `${titleKey}\u0000`
        let group = groupsByKey.get(unknownKey)
        if (!group) {
          group = { key: unknownKey, name: rawCleanName, author: undefined, sources: [] }
          groupsByKey.set(unknownKey, group)
          titleToUnknownAuthorKey.set(titleKey, unknownKey)
        } else {
          if (group.name.includes('[') || group.name.includes('“') || group.name.length < rawCleanName.length) {
            group.name = rawCleanName
          }
        }
        group.sources.push(result)
      }
    }
  }

  for (const [titleKey, unknownKey] of titleToUnknownAuthorKey.entries()) {
    const unknownGroup = groupsByKey.get(unknownKey)
    if (!unknownGroup) continue
    const knownKeys = titleToKnownAuthorKeys.get(titleKey)
    if (knownKeys && knownKeys.length === 1) {
      const targetGroup = groupsByKey.get(knownKeys[0])!
      targetGroup.sources.push(...unknownGroup.sources)
      groupsByKey.delete(unknownKey)
    }
  }

  return Array.from(groupsByKey.values())
}

export const loadSourceBook = async (result: SearchResult, alternateSources?: SearchResult[]): Promise<OpenBook> => {
  const details = await api.details(result.sourceId, result.bookUrl)
  const safeDetails: BookDetails = {
    ...details,
    name: details.name?.trim() || result.name || '未知书名',
    author: details.author?.trim() || result.author,
    coverUrl: details.coverUrl || result.coverUrl,
    intro: details.intro || result.intro,
    alternateSources: alternateSources?.filter(s => s.sourceId !== result.sourceId || s.bookUrl !== result.bookUrl),
  }
  const [chapters, progress] = await Promise.all([
    api.chapters(safeDetails.sourceId, safeDetails.tocUrl),
    api.progress(safeDetails.sourceId, result.bookUrl),
  ])
  return { details: safeDetails, bookUrl: result.bookUrl, chapters, progress }
}

const initialSearchState: SearchStoreState = {
  keyword: '',
  selectedSourceId: '',
  results: [],
  choices: [],
  openBook: null,
  message: '',
  loading: false,
  stopped: false,
  detailLoading: false,
  progress: null,
  filters: defaultSearchFilters,
}

export class SearchStore {
  private state: SearchStoreState = { ...initialSearchState }
  private listeners = new Set<() => void>()
  private socket: WebSocket | null = null

  getSnapshot = (): SearchStoreState => this.state

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  private emitChange() {
    for (const listener of this.listeners) {
      listener()
    }
  }

  private setState(next: Partial<SearchStoreState> | ((current: SearchStoreState) => Partial<SearchStoreState>)) {
    const patch = typeof next === 'function' ? next(this.state) : next
    this.state = { ...this.state, ...patch }
    this.emitChange()
  }

  setKeyword = (keyword: string) => {
    this.setState({ keyword })
  }

  setSelectedSourceId = (selectedSourceId: string) => {
    this.setState({ selectedSourceId })
  }

  setFilters = (filters: SearchFilters | ((current: SearchFilters) => SearchFilters)) => {
    this.setState(current => ({
      filters: typeof filters === 'function' ? filters(current.filters) : filters,
    }))
  }

  setOpenBook = (openBook: OpenBook | null) => {
    this.setState({ openBook })
  }

  setChoices = (choices: SourceChoice[]) => {
    this.setState({ choices })
  }

  startSearch = (keyword?: string, sourceId?: string) => {
    const query = (keyword !== undefined ? keyword : this.state.keyword).trim()
    if (!query) return

    const source = sourceId !== undefined ? sourceId : this.state.selectedSourceId

    if (this.socket) {
      try {
        if (this.socket.readyState === WebSocket.OPEN) {
          this.socket.send(JSON.stringify({ type: 'cancel' }))
        }
      } catch {
        // ignore
      }
      try {
        this.socket.close()
      } catch {
        // ignore
      }
      this.socket = null
    }

    this.setState({
      keyword: query,
      selectedSourceId: source,
      loading: true,
      stopped: false,
      message: '',
      progress: null,
      openBook: null,
      choices: [],
      results: [],
      filters: defaultSearchFilters,
    })

    const socket = streamSearch(
      query,
      source ? [source] : undefined,
      packet => {
        if (this.socket !== socket) return
        if (packet.type === 'start' || packet.type === 'progress' || packet.type === 'done') {
          this.setState({ progress: packet })
        }
        if (packet.type === 'results') {
          this.setState(prev => {
            const known = new Set(prev.results.map(item => `${item.sourceId}\u0000${item.bookUrl}`))
            const additions = packet.results.filter(item => !known.has(`${item.sourceId}\u0000${item.bookUrl}`))
            return additions.length ? { results: [...prev.results, ...additions] } : {}
          })
        }
        if (packet.type === 'error') {
          this.setState({
            message: packet.message || '搜索失败',
            loading: false,
          })
          this.socket = null
        }
        if (packet.type === 'done') {
          this.setState({
            progress: packet,
            loading: false,
          })
          this.socket = null
        }
      },
      error => {
        if (this.socket === socket) {
          this.setState({
            message: error,
            loading: false,
          })
          this.socket = null
        }
      },
      () => {
        if (this.socket === socket) {
          this.setState({
            loading: false,
          })
          this.socket = null
        }
      }
    )

    this.socket = socket
  }

  stopSearch = () => {
    const socket = this.socket
    this.socket = null
    this.setState({
      stopped: true,
      loading: false,
    })
    if (socket) {
      try {
        if (socket.readyState === WebSocket.OPEN) {
          socket.send(JSON.stringify({ type: 'cancel' }))
        }
      } catch {
        // ignore
      }
      try {
        socket.close()
      } catch {
        // ignore
      }
    }
  }

  openGroup = async (group: SearchGroup) => {
    this.setState({
      detailLoading: true,
      message: '',
      openBook: null,
    })

    const initialChoices: SourceChoice[] = group.sources.map(result => ({ result, status: 'idle' }))
    this.setState({ choices: initialChoices })

    let loadedBook: OpenBook | null = null
    for (let i = 0; i < group.sources.length; i++) {
      const candidate = group.sources[i]
      this.setState(prev => ({
        choices: prev.choices.map((c, idx) => (idx === i ? { ...c, status: 'loading' } : c)),
      }))
      try {
        const book = await loadSourceBook(candidate, group.sources)
        this.setState(prev => ({
          choices: prev.choices.map((c, idx) => (idx === i ? { ...c, book, status: 'loaded' } : c)),
        }))
        loadedBook = book
        break
      } catch (err) {
        const errMsg = err instanceof Error ? err.message : '无法读取此书源'
        this.setState(prev => ({
          choices: prev.choices.map((c, idx) => (idx === i ? { ...c, status: 'error', error: errMsg } : c)),
        }))
      }
    }

    if (loadedBook) {
      this.setState({ openBook: loadedBook, detailLoading: false })
    } else {
      this.setState({ message: '所有书源均无法读取', detailLoading: false })
    }
  }

  chooseSource = async (choice: SourceChoice, groupSources?: SearchResult[]) => {
    if (choice.book) {
      this.setState({ openBook: choice.book })
      return
    }

    this.setState(prev => ({
      choices: prev.choices.map(c =>
        c.result.sourceId === choice.result.sourceId && c.result.bookUrl === choice.result.bookUrl
          ? { ...c, status: 'loading', error: undefined }
          : c
      ),
    }))

    try {
      const book = await loadSourceBook(choice.result, groupSources)
      this.setState(prev => ({
        choices: prev.choices.map(c =>
          c.result.sourceId === choice.result.sourceId && c.result.bookUrl === choice.result.bookUrl
            ? { ...c, book, status: 'loaded' }
            : c
        ),
        openBook: book,
      }))
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : '无法读取此书源'
      this.setState(prev => ({
        choices: prev.choices.map(c =>
          c.result.sourceId === choice.result.sourceId && c.result.bookUrl === choice.result.bookUrl
            ? { ...c, status: 'error', error: errMsg }
            : c
        ),
      }))
    }
  }

  reset = () => {
    if (this.socket) {
      try {
        if (this.socket.readyState === WebSocket.OPEN) {
          this.socket.send(JSON.stringify({ type: 'cancel' }))
        }
      } catch {
        // ignore
      }
      try {
        this.socket.close()
      } catch {
        // ignore
      }
      this.socket = null
    }
    this.setState({ ...initialSearchState })
  }
}

export const searchStore = new SearchStore()

export function useSearchStore(): SearchStoreState & {
  setKeyword: (keyword: string) => void
  setSelectedSourceId: (sourceId: string) => void
  setFilters: (filters: SearchFilters | ((current: SearchFilters) => SearchFilters)) => void
  setOpenBook: (book: OpenBook | null) => void
  startSearch: (keyword?: string, sourceId?: string) => void
  stopSearch: () => void
  openGroup: (group: SearchGroup) => Promise<void>
  chooseSource: (choice: SourceChoice, groupSources?: SearchResult[]) => Promise<void>
  reset: () => void
} {
  const state = useSyncExternalStore(searchStore.subscribe, searchStore.getSnapshot)
  return {
    ...state,
    setKeyword: searchStore.setKeyword,
    setSelectedSourceId: searchStore.setSelectedSourceId,
    setFilters: searchStore.setFilters,
    setOpenBook: searchStore.setOpenBook,
    startSearch: searchStore.startSearch,
    stopSearch: searchStore.stopSearch,
    openGroup: searchStore.openGroup,
    chooseSource: searchStore.chooseSource,
    reset: searchStore.reset,
  }
}
