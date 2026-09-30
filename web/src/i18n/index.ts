import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import zhCN from './locales/zh-CN.json'
import zhTW from './locales/zh-TW.json'
import enUS from './locales/en-US.json'
import jaJP from './locales/ja-JP.json'
import {
  DEFAULT_LOCALE,
  detectBrowserLocale,
  normalizeLocale,
  STORAGE_LOCALE_KEY,
  type SupportedLocale,
} from './types'

export * from './types'

export const resources = {
  'zh-CN': { translation: zhCN },
  'zh-TW': { translation: zhTW },
  'en-US': { translation: enUS },
  'ja-JP': { translation: jaJP },
} as const

export function getInitialLocale(): SupportedLocale {
  if (typeof window === 'undefined' || typeof localStorage === 'undefined') {
    return DEFAULT_LOCALE
  }
  try {
    const saved = localStorage.getItem(STORAGE_LOCALE_KEY)
    if (saved) {
      return normalizeLocale(saved)
    }
  } catch {
    // Ignore storage access error
  }
  return detectBrowserLocale()
}

const initialLocale = getInitialLocale()

// Synchronously update html lang attribute if document is available
if (typeof document !== 'undefined' && document.documentElement) {
  document.documentElement.lang = initialLocale
}

i18n
  .use(initReactI18next)
  .init({
    resources,
    lng: initialLocale,
    fallbackLng: DEFAULT_LOCALE,
    supportedLngs: ['zh-CN', 'zh-TW', 'en-US', 'ja-JP'],
    interpolation: {
      escapeValue: false,
    },
    react: {
      useSuspense: false,
    },
  })

export async function changeAppLanguage(locale: SupportedLocale): Promise<SupportedLocale> {
  const normalized = normalizeLocale(locale)
  await i18n.changeLanguage(normalized)
  if (typeof localStorage !== 'undefined') {
    try {
      localStorage.setItem(STORAGE_LOCALE_KEY, normalized)
    } catch {
      // Ignore
    }
  }
  if (typeof document !== 'undefined' && document.documentElement) {
    document.documentElement.lang = normalized
  }
  return normalized
}

export function getCurrentLocale(): SupportedLocale {
  return normalizeLocale(i18n.language)
}

export { i18n }
export default i18n
