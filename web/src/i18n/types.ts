export type SupportedLocale = 'zh-CN' | 'zh-TW' | 'en-US' | 'ja-JP'

export interface LocaleInfo {
  code: SupportedLocale
  name: string
  nativeName: string
}

export const SUPPORTED_LOCALES: ReadonlyArray<LocaleInfo> = [
  { code: 'zh-CN', name: '简体中文', nativeName: '简体中文' },
  { code: 'zh-TW', name: '繁体中文', nativeName: '繁體中文' },
  { code: 'en-US', name: 'English', nativeName: 'English' },
  { code: 'ja-JP', name: '日本語', nativeName: '日本語' },
]

export const DEFAULT_LOCALE: SupportedLocale = 'zh-CN'
export const STORAGE_LOCALE_KEY = 'legado-locale-v1'

export function normalizeLocale(raw?: string | null): SupportedLocale {
  if (!raw) return DEFAULT_LOCALE
  const lower = raw.trim().toLowerCase()
  if (lower.startsWith('zh-tw') || lower.startsWith('zh-hk') || lower.startsWith('zh-hant') || lower.startsWith('zh-mo')) {
    return 'zh-TW'
  }
  if (lower.startsWith('zh')) {
    return 'zh-CN'
  }
  if (lower.startsWith('en')) {
    return 'en-US'
  }
  if (lower.startsWith('ja')) {
    return 'ja-JP'
  }
  return DEFAULT_LOCALE
}

export function detectBrowserLocale(): SupportedLocale {
  if (typeof navigator === 'undefined' || !navigator.language) {
    return DEFAULT_LOCALE
  }
  return normalizeLocale(navigator.language)
}
