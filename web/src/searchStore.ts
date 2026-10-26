import { useSyncExternalStore } from 'react'
import i18n from './i18n'
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
  /** 当前搜索范围中的多个分组；与 selectedSourceIds 按并集执行。 */
  selectedGroups: string[]
  /** 当前搜索范围中的多个单独书源；与 selectedGroups 按并集执行。 */
  selectedSourceIds: string[]
  /** 旧版单值镜像；混合或多选范围时为空。 */
  selectedSourceId: string
  /** 旧版单值镜像；混合或多选范围时为空。 */
  selectedGroup: string
  results: SearchResult[]
  choices: SourceChoice[]
  openBook: OpenBook | null
  message: string
  loading: boolean
  stopped: boolean
  detailLoading: boolean
  detailError: string
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
    const rawCleanName = cleanTitle(result.name) || result.name.trim() || i18n.t('common.unknownBook', '未知书名')
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

export const isValidBookTitle = (title?: string | null): title is string => {
  if (!title) return false
  const t = title.trim()
  return t.length > 0 && t !== '未命名书籍' && t !== '未知书名' && t !== 'null' && t !== 'undefined'
}

export const isValidAuthor = (author?: string | null): author is string => {
  if (!author) return false
  const a = author.trim()
  return a.length > 0 && a !== '未知作者' && a !== '未知' && a !== 'null' && a !== 'undefined'
}

export const loadSourceBook = async (result: SearchResult, alternateSources?: SearchResult[]): Promise<OpenBook> => {
  const details = await api.details(result.sourceId, result.bookUrl)
  const fallbackSources = alternateSources || []

  // Extract best cover: details cover -> result cover -> first non-empty in alternate sources
  const bestCover = details.coverUrl?.trim() ||
    result.coverUrl?.trim() ||
    fallbackSources.find(s => s.coverUrl?.trim())?.coverUrl?.trim()

  // Extract best intro: details intro -> result intro -> longest intro in alternate sources
  let bestIntro = details.intro?.trim() || result.intro?.trim()
  if (!bestIntro) {
    const candidateIntros = fallbackSources.map(s => s.intro?.trim()).filter((intro): intro is string => Boolean(intro))
    if (candidateIntros.length > 0) {
      bestIntro = candidateIntros.reduce((longest, current) => current.length > longest.length ? current : longest, '')
    }
  }

  // Clean title & author (filter out placeholder strings like "未命名书籍")
  const rawName = (isValidBookTitle(details.name) ? details.name.trim() : null) ||
    (isValidBookTitle(result.name) ? result.name.trim() : null) ||
    fallbackSources.find(s => isValidBookTitle(s.name))?.name?.trim() ||
    details.name?.trim() ||
    result.name?.trim() ||
    i18n.t('common.unknownBook', '未知书名')
  const safeName = cleanTitle(rawName) || rawName

  const rawAuthor = (isValidAuthor(details.author) ? details.author.trim() : null) ||
    (isValidAuthor(result.author) ? result.author.trim() : null) ||
    fallbackSources.find(s => isValidAuthor(s.author))?.author?.trim() ||
    details.author?.trim() ||
    result.author?.trim()
  const safeAuthor = cleanAuthor(rawAuthor) || rawAuthor
  const bookKind = details.bookKind || result.bookKind

  const safeDetails: BookDetails = {
    ...details,
    name: safeName,
    author: safeAuthor,
    coverUrl: bestCover || undefined,
    intro: bestIntro || undefined,
    bookKind: bookKind || undefined,
    alternateSources: alternateSources?.filter(s => s.sourceId !== result.sourceId || s.bookUrl !== result.bookUrl),
  }
  const [chapters, progress] = await Promise.all([
    api.chapters(safeDetails.sourceId, safeDetails.tocUrl),
    api.progress(safeDetails.sourceId, result.bookUrl),
  ])
  return { details: safeDetails, bookUrl: result.bookUrl, chapters, progress, bookKind }
}

const initialSearchState: SearchStoreState = {
  keyword: '',
  selectedGroups: [],
  selectedSourceIds: [],
  selectedSourceId: '',
  selectedGroup: '',
  results: [],
  choices: [],
  openBook: null,
  message: '',
  loading: false,
  stopped: false,
  detailLoading: false,
  detailError: '',
  progress: null,
  filters: defaultSearchFilters,
}

export class SearchStore {
  private state: SearchStoreState = { ...initialSearchState }
  private listeners = new Set<() => void>()
  private socket: WebSocket | null = null
  private detailRequestId = 0

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

  setSearchScope = (selectedGroups: string[], selectedSourceIds: string[]) => {
    const groups = Array.from(new Set(selectedGroups.filter(Boolean)))
    const sourceIds = Array.from(new Set(selectedSourceIds.filter(Boolean)))
    this.setState({
      selectedGroups: groups,
      selectedSourceIds: sourceIds,
      selectedGroup: groups.length === 1 && sourceIds.length === 0 ? groups[0] : '',
      selectedSourceId: sourceIds.length === 1 && groups.length === 0 ? sourceIds[0] : '',
    })
  }

  setSelectedGroups = (selectedGroups: string[]) => {
    this.setSearchScope(selectedGroups, this.state.selectedSourceIds)
  }

  setSelectedSourceIds = (selectedSourceIds: string[]) => {
    this.setSearchScope(this.state.selectedGroups, selectedSourceIds)
  }

  setSelectedSourceId = (selectedSourceId: string) => {
    // 旧版单源入口保留互斥语义；新选择器使用 setSelectedSourceIds 支持混合范围。
    this.setSearchScope([], selectedSourceId ? [selectedSourceId] : [])
  }

  setSelectedGroup = (selectedGroup: string) => {
    // 旧版单组入口保留互斥语义；新选择器使用 setSelectedGroups 支持多组范围。
    this.setSearchScope(selectedGroup ? [selectedGroup] : [], [])
  }

  setFilters = (filters: SearchFilters | ((current: SearchFilters) => SearchFilters)) => {
    this.setState(current => ({
      filters: typeof filters === 'function' ? filters(current.filters) : filters,
    }))
  }

  setOpenBook = (openBook: OpenBook | null) => {
    if (!openBook) this.detailRequestId++
    this.setState({ openBook, detailLoading: false, detailError: '' })
  }

  setChoices = (choices: SourceChoice[]) => {
    this.setState({ choices })
  }

  startSearch = (keyword?: string, sourceId?: string) => {
    const query = (keyword !== undefined ? keyword : this.state.keyword).trim()
    if (!query) return

    const sourceIds = sourceId !== undefined
      ? (sourceId ? [sourceId] : [])
      : this.state.selectedSourceIds
    const groups = sourceId !== undefined ? [] : this.state.selectedGroups

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

    this.detailRequestId++
    this.setState({
      keyword: query,
      selectedGroups: groups,
      selectedSourceIds: sourceIds,
      selectedGroup: groups.length === 1 && sourceIds.length === 0 ? groups[0] : '',
      selectedSourceId: sourceIds.length === 1 && groups.length === 0 ? sourceIds[0] : '',
      loading: true,
      stopped: false,
      message: '',
      progress: null,
      openBook: null,
      detailLoading: false,
      detailError: '',
      choices: [],
      results: [],
      filters: defaultSearchFilters,
    })

    const socket = streamSearch(
      query,
      sourceIds.length ? sourceIds : undefined,
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
            message: packet.message || i18n.t('search.failed', '搜索失败'),
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
      },
      groups.length === 1 && sourceIds.length === 0
        ? groups[0]
        : (groups.length ? groups : undefined)
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
    if (this.state.detailLoading || !group.sources.length) return
    const requestId = ++this.detailRequestId
    const first = group.sources[0]
    const placeholder: OpenBook = {
      details: {
        sourceId: first.sourceId,
        tocUrl: '',
        name: group.name,
        author: group.author,
        intro: first.intro,
        coverUrl: group.sources.find(source => source.coverUrl)?.coverUrl,
        bookKind: first.bookKind,
      },
      bookUrl: first.bookUrl,
      chapters: [],
      bookKind: first.bookKind,
    }
    const initialChoices: SourceChoice[] = group.sources.map(result => ({ result, status: 'idle' }))
    this.setState({ detailLoading: true, detailError: '', message: '', openBook: placeholder, choices: initialChoices })

    for (let i = 0; i < group.sources.length; i++) {
      const candidate = group.sources[i]
      this.setState(prev => ({
        choices: prev.choices.map((c, idx) => (idx === i ? { ...c, status: 'loading' } : c)),
      }))
      try {
        const book = await loadSourceBook(candidate, group.sources)
        if (requestId !== this.detailRequestId) return
        this.setState(prev => ({
          choices: prev.choices.map((c, idx) => (idx === i ? { ...c, book, status: 'loaded' } : c)),
          openBook: book,
          detailLoading: false,
        }))
        return
      } catch (err) {
        if (requestId !== this.detailRequestId) return
        const errMsg = err instanceof Error ? err.message : i18n.t('search.cannotReadSource', '无法读取此书源')
        this.setState(prev => ({
          choices: prev.choices.map((c, idx) => (idx === i ? { ...c, status: 'error', error: errMsg } : c)),
        }))
      }
    }

    this.setState({ detailError: i18n.t('search.allSourcesUnreadable', '所有书源均无法读取'), detailLoading: false })
  }

  chooseSource = async (choice: SourceChoice, groupSources?: SearchResult[]) => {
    const allCandidates = groupSources || this.state.choices.map(c => c.result)
    const fallbackCover = allCandidates.find(s => s.coverUrl?.trim())?.coverUrl?.trim()
    const candidateIntros = allCandidates.map(s => s.intro?.trim()).filter((intro): intro is string => Boolean(intro))
    const fallbackIntro = candidateIntros.length > 0
      ? candidateIntros.reduce((longest, current) => current.length > longest.length ? current : longest, '')
      : undefined

    if (choice.book) {
      const current = choice.book
      const bestCover = current.details.coverUrl?.trim() || fallbackCover
      const bestIntro = current.details.intro?.trim() || fallbackIntro
      const currentName = current.details.name?.trim()
      const existingName = this.state.openBook?.details.name?.trim()
      const bestName = isValidBookTitle(currentName)
        ? currentName
        : (isValidBookTitle(existingName) ? existingName : (cleanTitle(choice.result.name) || choice.result.name || currentName))
      const updatedBook: OpenBook = {
        ...current,
        details: {
          ...current.details,
          name: bestName,
          coverUrl: bestCover || undefined,
          intro: bestIntro || undefined,
          alternateSources: allCandidates.filter(s => s.sourceId !== choice.result.sourceId || s.bookUrl !== choice.result.bookUrl),
        },
      }
      this.setState({ openBook: updatedBook })
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
      const book = await loadSourceBook(choice.result, allCandidates)
      this.setState(prev => ({
        choices: prev.choices.map(c =>
          c.result.sourceId === choice.result.sourceId && c.result.bookUrl === choice.result.bookUrl
            ? { ...c, book, status: 'loaded' }
            : c
        ),
        openBook: book,
      }))
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : i18n.t('search.cannotReadSource', '无法读取此书源')
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
    this.detailRequestId++
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
  setSearchScope: (groups: string[], sourceIds: string[]) => void
  setSelectedGroups: (groups: string[]) => void
  setSelectedSourceIds: (sourceIds: string[]) => void
  setKeyword: (keyword: string) => void
  setSelectedSourceId: (sourceId: string) => void
  setSelectedGroup: (group: string) => void
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
    setSearchScope: searchStore.setSearchScope,
    setSelectedGroups: searchStore.setSelectedGroups,
    setSelectedSourceIds: searchStore.setSelectedSourceIds,
    setKeyword: searchStore.setKeyword,
    setSelectedSourceId: searchStore.setSelectedSourceId,
    setSelectedGroup: searchStore.setSelectedGroup,
    setFilters: searchStore.setFilters,
    setOpenBook: searchStore.setOpenBook,
    startSearch: searchStore.startSearch,
    stopSearch: searchStore.stopSearch,
    openGroup: searchStore.openGroup,
    chooseSource: searchStore.chooseSource,
    reset: searchStore.reset,
  }
}
