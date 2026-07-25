export type ReaderTheme = 'light' | 'paper' | 'dark'
export type ReaderSettings = { theme: ReaderTheme; fontSize: number; lineHeight: number }

const storageKey = 'legado-reader-settings-v1'
export const defaultReaderSettings: ReaderSettings = { theme: 'light', fontSize: 19, lineHeight: 1.95 }

export function clampScrollPosition(value: number): number {
  return Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0
}

export function scrollPosition(scrollTop: number, scrollHeight: number, clientHeight: number): number {
  return clampScrollPosition(scrollTop / Math.max(1, scrollHeight - clientHeight))
}

export function loadReaderSettings(): ReaderSettings {
  try {
    const saved = JSON.parse(window.localStorage.getItem(storageKey) ?? '{}') as Partial<ReaderSettings>
    return {
      theme: saved.theme === 'paper' || saved.theme === 'dark' ? saved.theme : defaultReaderSettings.theme,
      fontSize: typeof saved.fontSize === 'number' ? Math.min(28, Math.max(15, saved.fontSize)) : defaultReaderSettings.fontSize,
      lineHeight: typeof saved.lineHeight === 'number' ? Math.min(2.4, Math.max(1.45, saved.lineHeight)) : defaultReaderSettings.lineHeight,
    }
  } catch {
    return defaultReaderSettings
  }
}

export function saveReaderSettings(settings: ReaderSettings): void {
  window.localStorage.setItem(storageKey, JSON.stringify(settings))
}
