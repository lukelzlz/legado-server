import { SearchResult } from './api'

export type SearchGroup = { key: string; name: string; author?: string; sources: SearchResult[] }
export type SearchFilters = {
  query: string
  minimumSources: 1 | 2 | 3
  withIntro: boolean
  withCover: boolean
}

export const defaultSearchFilters: SearchFilters = { query: '', minimumSources: 1, withIntro: false, withCover: false }

export function filterSearchGroups(groups: SearchGroup[], filters: SearchFilters): SearchGroup[] {
  const query = filters.query.trim().toLocaleLowerCase()
  return groups.filter(group => {
    const searchable = `${group.name} ${group.author ?? ''}`.toLocaleLowerCase()
    return (!query || searchable.includes(query))
      && group.sources.length >= filters.minimumSources
      && (!filters.withIntro || group.sources.some(source => Boolean(source.intro?.trim())))
      && (!filters.withCover || group.sources.some(source => Boolean(source.coverUrl)))
  })
}
