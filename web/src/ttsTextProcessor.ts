export type TtsSentence = {
  sentenceIndex: number
  text: string
  globalIndex: number
}

export type TtsParagraph = {
  paragraphIndex: number
  rawText: string
  cleanedText: string
  sentences: TtsSentence[]
}

export type TtsChunk = {
  globalIndex: number
  paragraphIndex: number
  sentenceIndex: number
  text: string
}

export type TtsChapterData = {
  title: string
  paragraphs: TtsParagraph[]
  chunks: TtsChunk[]
  totalChunks: number
  totalChars: number
}

const DECORATION_PATTERN = /^[=\-_*~#·\s]{3,}$/
const REPETITIVE_SYMBOLS = /[=\-_*~#·]{2,}/g

export function cleanTextForSpeech(text: string, filterSymbols = true): string {
  if (!text) return ''
  let cleaned = text.trim()
  if (DECORATION_PATTERN.test(cleaned)) return ''

  if (filterSymbols) {
    // Strip decorative borders
    cleaned = cleaned.replace(REPETITIVE_SYMBOLS, '')
    // Strip bracket enclosures like 【...】 or [ps:...]
    cleaned = cleaned.replace(/[【】\[\]「」『』]/g, '')
    // Remove extra trailing exclamation/question sequences like ！！！ -> ！
    cleaned = cleaned.replace(/！{2,}/g, '！').replace(/!{2,}/g, '!')
    cleaned = cleaned.replace(/？{2,}/g, '？').replace(/\?{2,}/g, '?')
    cleaned = cleaned.replace(/\s+/g, ' ').trim()
  }
  return cleaned
}

/**
 * Split text into sentence chunks by punctuation
 */
export function splitSentences(paragraphText: string, filterSymbols = true): string[] {
  const cleaned = cleanTextForSpeech(paragraphText, filterSymbols)
  if (!cleaned) return []

  // Split by common Chinese and English sentence terminators, keeping ending punctuation
  const regex = /[^。！？!?；;….\n]+[。！？!?；;….\n]*/g
  const matches = cleaned.match(regex)
  if (!matches || matches.length === 0) {
    return [cleaned]
  }

  const result: string[] = []
  for (const item of matches) {
    const trimmed = item.trim()
    // Skip punctuation-only fragments (e.g. a lone `"` or `。`) that would produce 0-byte audio
    const stripped = trimmed.replace(/[\s\u0000-\u002F\u003A-\u0040\u005B-\u0060\u007B-\u00BF\u2000-\u206F\u2018\u2019\u201C\u201D\u3000-\u303F\uFF00-\uFFEF]/g, '')
    if (stripped.length >= 2) {
      result.push(trimmed)
    }
  }
  return result.length > 0 ? result : [cleaned]
}

/**
 * Parse chapter title and content into structured paragraphs and playable speech chunks
 */
export function processChapterForTts(
  chapterTitle: string,
  rawParagraphs: string[],
  filterSymbols = true,
): TtsChapterData {
  const paragraphs: TtsParagraph[] = []
  const chunks: TtsChunk[] = []
  let globalIndex = 0
  let totalChars = 0

  const cleanTitle = cleanTextForSpeech(chapterTitle, filterSymbols)
  if (cleanTitle) {
    const titleText = cleanTitle.endsWith('。') || cleanTitle.endsWith('！') || cleanTitle.endsWith('？')
      ? cleanTitle
      : `${cleanTitle}。`
    chunks.push({
      globalIndex: globalIndex++,
      paragraphIndex: -1, // -1 represents chapter title
      sentenceIndex: 0,
      text: titleText,
    })
    totalChars += titleText.length
  }

  for (let pIdx = 0; pIdx < rawParagraphs.length; pIdx++) {
    const raw = rawParagraphs[pIdx]
    const cleaned = cleanTextForSpeech(raw, filterSymbols)
    const sentencesText = splitSentences(raw, filterSymbols)

    const sentences: TtsSentence[] = []
    for (let sIdx = 0; sIdx < sentencesText.length; sIdx++) {
      const sText = sentencesText[sIdx]
      sentences.push({
        sentenceIndex: sIdx,
        text: sText,
        globalIndex,
      })

      chunks.push({
        globalIndex: globalIndex++,
        paragraphIndex: pIdx,
        sentenceIndex: sIdx,
        text: sText,
      })
      totalChars += sText.length
    }

    paragraphs.push({
      paragraphIndex: pIdx,
      rawText: raw,
      cleanedText: cleaned,
      sentences,
    })
  }

  return {
    title: chapterTitle,
    paragraphs,
    chunks,
    totalChunks: chunks.length,
    totalChars,
  }
}
