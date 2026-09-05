import test from 'node:test'
import assert from 'node:assert/strict'
import { HttpAudioTtsEngine } from '../src/ttsEngine'
import { defaultReaderSettings } from '../src/readerSettings'

class FakeAudio {
  static instances: FakeAudio[] = []
  src = ''
  preload = ''
  playbackRate = 1
  currentTime = 0
  paused = true
  private listeners = new Map<string, Array<() => void>>()

  constructor() {
    FakeAudio.instances.push(this)
  }

  addEventListener(type: string, listener: () => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  pause() {
    this.paused = true
  }

  play() {
    this.paused = false
    return Promise.resolve()
  }

  removeAttribute(name: string) {
    if (name === 'src') this.src = ''
  }

  load() {
    this.paused = true
  }

  emit(type: string) {
    for (const listener of this.listeners.get(type) ?? []) listener()
  }
}

class FakeEventSource {
  static instances: FakeEventSource[] = []
  readonly url: string
  private listeners = new Map<string, Array<(event: MessageEvent) => void>>()
  closed = false

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: (event: MessageEvent) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  close() {
    this.closed = true
  }

  emit(type: string, data: Record<string, unknown>) {
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data: JSON.stringify(data) } as MessageEvent)
    }
  }
}

const settings = { ...defaultReaderSettings, ttsEngine: 'edge' as const }

test('HTTP TTS session keeps one audio element and appends chunks to one session', async t => {
  const originalAudio = globalThis.Audio
  const originalEventSource = globalThis.EventSource
  const originalFetch = globalThis.fetch
  const requests: string[] = []
  let sessionCount = 0

  Object.assign(globalThis, { Audio: FakeAudio, EventSource: FakeEventSource })
  globalThis.fetch = async (input) => {
    const url = String(input)
    requests.push(url)
    if (url === '/api/tts/session') {
      sessionCount++
      return new Response(JSON.stringify({
        sessionId: 'session-1',
        audioUrl: '/api/tts/session/session-1/audio',
        eventsUrl: '/api/tts/session/session-1/events',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    if (url.endsWith('/chunks')) {
      return new Response(JSON.stringify({ accepted: true }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(JSON.stringify({ accepted: true }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  t.after(() => {
    globalThis.Audio = originalAudio
    globalThis.EventSource = originalEventSource
    globalThis.fetch = originalFetch
  })

  const engine = new HttpAudioTtsEngine()
  let firstEnded = 0
  let secondEnded = 0
  const errors: Error[] = []
  engine.speak('第一句测试内容。', settings, () => { firstEnded++ }, error => errors.push(error), 'replace', { chunkId: 'chunk-1', chapterIndex: 2, paragraphIndex: 0 })
  await new Promise(resolve => setTimeout(resolve, 0))
  await engine.prefetch('第二句测试内容。', settings, { chunkId: 'chunk-2', chapterIndex: 2, paragraphIndex: 0 })
  await new Promise(resolve => setTimeout(resolve, 20))

  assert.equal(sessionCount, 1)
  assert.equal(FakeAudio.instances.length, 1)
  assert.equal(FakeEventSource.instances.length, 1)
  assert.equal(requests.filter(url => url.endsWith('/chunks')).length, 2)

  const events = FakeEventSource.instances[0]
  events.emit('chunk_end', { type: 'chunk_end', sessionId: 'session-1', chunkId: 'chunk-1', audioEndMs: 1000 })
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(firstEnded, 0)
  FakeAudio.instances[0].currentTime = 1
  FakeAudio.instances[0].emit('timeupdate')
  assert.equal(firstEnded, 1)

  engine.speak('第二句测试内容。', settings, () => { secondEnded++ }, error => errors.push(error), 'continue', { chunkId: 'chunk-2', chapterIndex: 2, paragraphIndex: 0 })
  events.emit('chunk_end', { type: 'chunk_end', sessionId: 'session-1', chunkId: 'chunk-2' })
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.equal(secondEnded, 1)
  assert.deepEqual(errors, [])
  engine.stop()
})
