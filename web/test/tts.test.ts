import test from 'node:test'
import assert from 'node:assert/strict'
import { cleanTextForSpeech, splitSentences, processChapterForTts } from '../src/ttsTextProcessor'

test('TTS Text Processor - cleanTextForSpeech filters noise and decorative symbols', () => {
  const dirty1 = '====== 第一章 降临 ======'
  assert.equal(cleanTextForSpeech(dirty1, true), '第一章 降临')

  const dirty2 = '【读者大大求收藏！！！】'
  assert.equal(cleanTextForSpeech(dirty2, true), '读者大大求收藏！')

  const dirty3 = '---- 分割线 ----\n~~~~ 这是正文 ~~~~'
  assert.equal(cleanTextForSpeech(dirty3, true), '分割线 这是正文')

  const normal = '林轩深吸了一口气，拔出了腰间的长剑。'
  assert.equal(cleanTextForSpeech(normal, true), '林轩深吸了一口气，拔出了腰间的长剑。')
})

test('TTS Text Processor - splitSentences splits Chinese and English sentences accurately', () => {
  const text1 = '风声呼啸。黑夜中，一道身影疾驰而过！谁在那里？他大声喝道。'
  const sentences1 = splitSentences(text1)
  assert.deepEqual(sentences1, [
    '风声呼啸。',
    '黑夜中，一道身影疾驰而过！',
    '谁在那里？',
    '他大声喝道。',
  ])

  const text2 = 'Hello world! This is a test. Are you ready? Yes, absolutely.'
  const sentences2 = splitSentences(text2)
  assert.equal(sentences2.length, 4)
  assert.equal(sentences2[0], 'Hello world!')
  assert.equal(sentences2[1], 'This is a test.')
})

test('TTS Text Processor - processChapterForTts compiles complete chapter into indexed chunks', () => {
  const chapterTitle = '第一章 初入江湖'
  const paragraphs = [
    '林轩站在山巅，望着远方的云海。',
    '师父，我一定会回来的！他暗暗发誓。',
    '山风凛冽，吹动着他的衣袍。远处传来了几声清脆的鸟鸣。',
  ]

  const data = processChapterForTts(chapterTitle, paragraphs, true)

  assert.equal(data.title, '第一章 初入江湖')
  assert.ok(data.chunks.length >= 4)

  // First chunk is chapter title
  assert.equal(data.chunks[0].globalIndex, 0)
  assert.equal(data.chunks[0].paragraphIndex, -1)
  assert.equal(data.chunks[0].text, '第一章 初入江湖。')

  // Second chunk is first paragraph
  assert.equal(data.chunks[1].globalIndex, 1)
  assert.equal(data.chunks[1].paragraphIndex, 0)
  assert.equal(data.chunks[1].text, '林轩站在山巅，望着远方的云海。')

  // Third chunk is second paragraph
  assert.equal(data.chunks[2].globalIndex, 2)
  assert.equal(data.chunks[2].paragraphIndex, 1)
  assert.equal(data.chunks[2].text, '师父，我一定会回来的！')

  // Total characters count should be positive
  assert.ok(data.totalChars > 0)
})

test('TTS Text Processor - handles empty chapter gracefully', () => {
  const data = processChapterForTts('', [], true)
  assert.equal(data.chunks.length, 0)
  assert.equal(data.totalChars, 0)
})
