export type ReaderTheme = 'light' | 'paper' | 'dark'
export type ReaderFont = 'song' | 'hei' | 'kai' | 'fangsong' | 'system'
export type ReaderPageMode = 'scroll' | 'paginate'
export type ReaderColumnMode = 'auto' | 'single' | 'double'
export type TtsEngineType = 'webSpeech' | 'edge' | 'custom'

export type ReaderSettings = {
  theme: ReaderTheme
  fontSize: number
  lineHeight: number
  letterSpacing: number
  paragraphSpacing: number
  contentPadding: number
  font: ReaderFont
  pageMode: ReaderPageMode
  maxWidth: number
  columnMode: ReaderColumnMode
  sidebarPinned: boolean
  ttsEngine: TtsEngineType
  ttsVoice: string
  ttsSpeed: number
  ttsPitch: number
  ttsAutoNextChapter: boolean
  ttsFilterSymbols: boolean
  ttsCustomUrl?: string
  ttsCustomHeader?: string
  ttsCustomBody?: string
  ttsCustomMethod?: string
}

const storageKey = 'legado-reader-settings-v2'
export const defaultReaderSettings: ReaderSettings = {
  theme: 'light',
  fontSize: 19,
  lineHeight: 1.95,
  letterSpacing: 0,
  paragraphSpacing: 1.15,
  contentPadding: 80,
  font: 'song',
  pageMode: 'scroll',
  maxWidth: 860,
  columnMode: 'auto',
  sidebarPinned: false,
  ttsEngine: 'edge',
  ttsVoice: 'zh-CN-XiaoxiaoNeural',
  ttsSpeed: 1.0,
  ttsPitch: 1.0,
  ttsAutoNextChapter: true,
  ttsFilterSymbols: true,
  ttsCustomUrl: '',
  ttsCustomHeader: '',
  ttsCustomBody: '',
  ttsCustomMethod: 'GET',
}

export function clampScrollPosition(value: number): number {
  return Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0
}

export function scrollPosition(scrollTop: number, scrollHeight: number, clientHeight: number): number {
  return clampScrollPosition(scrollTop / Math.max(1, scrollHeight - clientHeight))
}

export function parseReaderFont(font: unknown): ReaderFont {
  if (font === 'hei' || font === 'kai' || font === 'fangsong' || font === 'system') return font
  return 'song'
}

export function parsePageMode(mode: unknown): ReaderPageMode {
  return mode === 'paginate' ? 'paginate' : 'scroll'
}

export function parseColumnMode(mode: unknown): ReaderColumnMode {
  if (mode === 'single' || mode === 'double' || mode === 'auto') return mode
  return 'auto'
}

export function parseMaxWidth(width: unknown): number {
  if (typeof width === 'number' && Number.isFinite(width)) {
    return Math.min(1400, Math.max(560, Math.round(width)))
  }
  return defaultReaderSettings.maxWidth
}

export function parseTtsEngine(engine: unknown): TtsEngineType {
  if (engine === 'webSpeech' || engine === 'edge' || engine === 'custom') return engine
  return defaultReaderSettings.ttsEngine
}

export function loadReaderSettings(): ReaderSettings {
  try {
    const raw = window.localStorage.getItem(storageKey) || window.localStorage.getItem('legado-reader-settings-v1')
    const saved = JSON.parse(raw ?? '{}') as Partial<ReaderSettings> & { font?: string }
    return {
      theme: saved.theme === 'paper' || saved.theme === 'dark' ? saved.theme : defaultReaderSettings.theme,
      fontSize: typeof saved.fontSize === 'number' ? Math.min(28, Math.max(15, saved.fontSize)) : defaultReaderSettings.fontSize,
      lineHeight: typeof saved.lineHeight === 'number' ? Math.min(2.4, Math.max(1.45, saved.lineHeight)) : defaultReaderSettings.lineHeight,
      letterSpacing: typeof saved.letterSpacing === 'number' ? Math.min(1.5, Math.max(-.25, saved.letterSpacing)) : defaultReaderSettings.letterSpacing,
      paragraphSpacing: typeof saved.paragraphSpacing === 'number' ? Math.min(2, Math.max(.7, saved.paragraphSpacing)) : defaultReaderSettings.paragraphSpacing,
      contentPadding: typeof saved.contentPadding === 'number' ? Math.min(120, Math.max(20, saved.contentPadding)) : defaultReaderSettings.contentPadding,
      font: parseReaderFont(saved.font),
      pageMode: parsePageMode(saved.pageMode),
      maxWidth: parseMaxWidth(saved.maxWidth),
      columnMode: parseColumnMode(saved.columnMode),
      sidebarPinned: typeof saved.sidebarPinned === 'boolean' ? saved.sidebarPinned : defaultReaderSettings.sidebarPinned,
      ttsEngine: parseTtsEngine(saved.ttsEngine),
      ttsVoice: typeof saved.ttsVoice === 'string' && saved.ttsVoice.trim() ? saved.ttsVoice : defaultReaderSettings.ttsVoice,
      ttsSpeed: typeof saved.ttsSpeed === 'number' && Number.isFinite(saved.ttsSpeed) ? Math.min(3.0, Math.max(0.5, saved.ttsSpeed)) : defaultReaderSettings.ttsSpeed,
      ttsPitch: typeof saved.ttsPitch === 'number' && Number.isFinite(saved.ttsPitch) ? Math.min(1.5, Math.max(0.5, saved.ttsPitch)) : defaultReaderSettings.ttsPitch,
      ttsAutoNextChapter: typeof saved.ttsAutoNextChapter === 'boolean' ? saved.ttsAutoNextChapter : defaultReaderSettings.ttsAutoNextChapter,
      ttsFilterSymbols: typeof saved.ttsFilterSymbols === 'boolean' ? saved.ttsFilterSymbols : defaultReaderSettings.ttsFilterSymbols,
      ttsCustomUrl: typeof saved.ttsCustomUrl === 'string' ? saved.ttsCustomUrl : '',
      ttsCustomHeader: typeof saved.ttsCustomHeader === 'string' ? saved.ttsCustomHeader : '',
      ttsCustomBody: typeof saved.ttsCustomBody === 'string' ? saved.ttsCustomBody : '',
      ttsCustomMethod: saved.ttsCustomMethod === 'POST' ? 'POST' : 'GET',
    }
  } catch {
    return defaultReaderSettings
  }
}

export const readerFontFamilies: Record<ReaderFont, string> = {
  song: '"Songti SC", "STSong", "SimSun", "Noto Serif CJK SC", "Noto Serif SC", "Source Han Serif SC", "Songti", STSongti, serif',
  hei: '"PingFang SC", "Microsoft YaHei", "Hiragino Sans GB", "WenQuanYi Micro Hei", "Noto Sans CJK SC", "Source Han Sans SC", "Heiti SC", sans-serif',
  kai: '"Kaiti SC", "STKaiti", "KaiTi", "SimKai", "KaiTi_GB2312", "STKaiti-SC-Regular", "BiauKai", "DFKai-SB", 楷体, 楷体_GB2312, serif',
  fangsong: '"FangSong", "STFangsong", "FangSong_GB2312", "STFangsong-SC-Regular", "SimFang", 仿宋, 仿宋_GB2312, serif',
  system: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "PingFang SC", "Microsoft YaHei", sans-serif',
}

export function getReaderFontFamily(font: ReaderFont): string {
  return readerFontFamilies[font] || readerFontFamilies.song
}

export function saveReaderSettings(settings: ReaderSettings): void {
  window.localStorage.setItem(storageKey, JSON.stringify(settings))
}



