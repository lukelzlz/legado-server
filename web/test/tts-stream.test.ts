import test from 'node:test'
import assert from 'node:assert/strict'
import { HttpAudioTtsEngine, isPlayInterruptedError } from '../src/ttsEngine'
import { defaultReaderSettings } from '../src/readerSettings'

class FakeAudio {
  static instances: FakeAudio[] = []
  static playError: Error | null = null
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
    if (FakeAudio.playError) return Promise.reject(FakeAudio.playError)
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

test('isPlayInterruptedError correctly detects play interruption errors', () => {
  assert.equal(isPlayInterruptedError(null), false)
  assert.equal(isPlayInterruptedError(undefined), false)
  assert.equal(isPlayInterruptedError(new Error('网络连接超时')), false)
  assert.equal(isPlayInterruptedError(new Error('The play() request was interrupted by a call to pause(). https://goo.gl/LdLk22')), true)
  assert.equal(isPlayInterruptedError(new Error('The play() request was interrupted by a new load request.')), true)

  const abortError = new Error('The play() request was canceled')
  abortError.name = 'AbortError'
  assert.equal(isPlayInterruptedError(abortError), true)
})

test('HTTP TTS engine ignores play interruption errors when interrupted by pause', async t => {
  const originalAudio = globalThis.Audio
  const originalEventSource = globalThis.EventSource
  const originalFetch = globalThis.fetch
  Object.assign(globalThis, { Audio: FakeAudio, EventSource: FakeEventSource })
  FakeAudio.playError = new Error('The play() request was interrupted by a call to pause(). https://goo.gl/LdLk22')

  globalThis.fetch = async (input) => {
    const url = String(input)
    if (url === '/api/tts/session') {
      return new Response(JSON.stringify({
        sessionId: 'session-interrupted',
        audioUrl: '/api/tts/session/session-interrupted/audio',
        eventsUrl: '/api/tts/session/session-interrupted/events',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(JSON.stringify({ accepted: true }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  t.after(() => {
    FakeAudio.playError = null
    globalThis.Audio = originalAudio
    globalThis.EventSource = originalEventSource
    globalThis.fetch = originalFetch
  })

  const engine = new HttpAudioTtsEngine()
  const reportedErrors: Error[] = []
  engine.speak('测试被打断的音频。', settings, () => {}, err => reportedErrors.push(err), 'replace')
  await new Promise(resolve => setTimeout(resolve, 20))

  // Interruption by pause should NOT trigger onError
  assert.equal(reportedErrors.length, 0)
  engine.stop()
})

test('Calling pause during session creation avoids triggering play', async t => {
  const originalAudio = globalThis.Audio
  const originalEventSource = globalThis.EventSource
  const originalFetch = globalThis.fetch
  let playCalled = false

  class GuardedAudio extends FakeAudio {
    override play() {
      playCalled = true
      return super.play()
    }
  }

  Object.assign(globalThis, { Audio: GuardedAudio, EventSource: FakeEventSource })
  globalThis.fetch = async (input) => {
    const url = String(input)
    if (url === '/api/tts/session') {
      await new Promise(resolve => setTimeout(resolve, 10))
      return new Response(JSON.stringify({
        sessionId: 'session-paused',
        audioUrl: '/api/tts/session/session-paused/audio',
        eventsUrl: '/api/tts/session/session-paused/events',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(JSON.stringify({ accepted: true }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  t.after(() => {
    globalThis.Audio = originalAudio
    globalThis.EventSource = originalEventSource
    globalThis.fetch = originalFetch
  })

  const engine = new HttpAudioTtsEngine()
  const reportedErrors: Error[] = []
  engine.speak('测试初始暂停。', settings, () => {}, err => reportedErrors.push(err), 'replace')
  // User pauses immediately while session is connecting
  engine.pause()
  await new Promise(resolve => setTimeout(resolve, 30))

  assert.equal(playCalled, false)
  assert.equal(reportedErrors.length, 0)
  engine.stop()
})

test('Relative clock anchoring prevents deadlock over 60 consecutive drifted chunks', async t => {
  const originalAudio = globalThis.Audio
  const originalEventSource = globalThis.EventSource
  const originalFetch = globalThis.fetch

  Object.assign(globalThis, { Audio: FakeAudio, EventSource: FakeEventSource })
  globalThis.fetch = async (input) => {
    const url = String(input)
    if (url === '/api/tts/session') {
      return new Response(JSON.stringify({
        sessionId: 'session-drift',
        audioUrl: '/api/tts/session/session-drift/audio',
        eventsUrl: '/api/tts/session/session-drift/events',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(JSON.stringify({ accepted: true }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  t.after(() => {
    globalThis.Audio = originalAudio
    globalThis.EventSource = originalEventSource
    globalThis.fetch = originalFetch
  })

  const engine = new HttpAudioTtsEngine()
  let endedCount = 0
  const errors: Error[] = []

  // Chunk 1 starts session
  engine.speak('句子1测试内容。', settings, () => { endedCount++ }, err => errors.push(err), 'replace', { chunkId: 'chunk-1' })
  await new Promise(resolve => setTimeout(resolve, 10))

  const events = FakeEventSource.instances[FakeEventSource.instances.length - 1]
  const audio = FakeAudio.instances[FakeAudio.instances.length - 1]

  for (let i = 1; i <= 60; i++) {
    const chunkId = `chunk-${i}`
    if (i > 1) {
      engine.speak(`句子${i}测试内容。`, settings, () => { endedCount++ }, err => errors.push(err), 'continue', { chunkId })
    }
    // Server emits durationMs = 2000, audioEndMs = i * 2000
    events.emit('chunk_end', {
      type: 'chunk_end',
      sessionId: 'session-drift',
      chunkId,
      durationMs: 2000,
      audioEndMs: i * 2000,
    })
    // Simulate player clock advancing by 1.95s (50ms drift per chunk, total 3000ms drift over 60 chunks)
    audio.currentTime += 1.95
    audio.emit('timeupdate')
    assert.equal(endedCount, i, `Chunk ${i} should have completed despite accumulated drift`)
  }

  assert.equal(endedCount, 60)
  assert.equal(errors.length, 0)
  engine.stop()
})

test('Stall watchdog forces onEnd when audio clock halts near chunk end', async t => {
  const originalAudio = globalThis.Audio
  const originalEventSource = globalThis.EventSource
  const originalFetch = globalThis.fetch

  Object.assign(globalThis, { Audio: FakeAudio, EventSource: FakeEventSource })
  globalThis.fetch = async (input) => {
    const url = String(input)
    if (url === '/api/tts/session') {
      return new Response(JSON.stringify({
        sessionId: 'session-stall',
        audioUrl: '/api/tts/session/session-stall/audio',
        eventsUrl: '/api/tts/session/session-stall/events',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(JSON.stringify({ accepted: true }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  t.after(() => {
    globalThis.Audio = originalAudio
    globalThis.EventSource = originalEventSource
    globalThis.fetch = originalFetch
  })

  const engine = new HttpAudioTtsEngine()
  let ended = false
  engine.speak('句子测试停滞看门狗。', settings, () => { ended = true }, () => {}, 'replace', { chunkId: 'chunk-stall' })
  await new Promise(resolve => setTimeout(resolve, 10))

  const events = FakeEventSource.instances[FakeEventSource.instances.length - 1]
  const audio = FakeAudio.instances[FakeAudio.instances.length - 1]

  events.emit('chunk_end', {
    type: 'chunk_end',
    sessionId: 'session-stall',
    chunkId: 'chunk-stall',
    durationMs: 2000,
  })

  // Audio advances to 1.82s (near end: 2.0s - 0.18s), but stops moving (e.g. decoder silence/buffer underrun)
  audio.currentTime = 1.82
  audio.emit('timeupdate')
  assert.equal(ended, false, 'Should not end immediately before watchdog timeout')

  // Wait 400ms for stall watchdog
  await new Promise(resolve => setTimeout(resolve, 400))
  audio.emit('timeupdate')
  assert.equal(ended, true, 'Stall watchdog should have triggered onEnd after clock halt')

  engine.stop()
})

test('Stall watchdog triggers onEnd when audio halts 400ms before chunk end due to trailing silence', async t => {
  const originalAudio = globalThis.Audio
  const originalEventSource = globalThis.EventSource
  const originalFetch = globalThis.fetch

  Object.assign(globalThis, { Audio: FakeAudio, EventSource: FakeEventSource })
  globalThis.fetch = async (input) => {
    const url = String(input)
    if (url === '/api/tts/session') {
      return new Response(JSON.stringify({
        sessionId: 'session-silence',
        audioUrl: '/api/tts/session/session-silence/audio',
        eventsUrl: '/api/tts/session/session-silence/events',
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response(JSON.stringify({ accepted: true }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }
  t.after(() => {
    globalThis.Audio = originalAudio
    globalThis.EventSource = originalEventSource
    globalThis.fetch = originalFetch
  })

  const engine = new HttpAudioTtsEngine()
  let ended = false
  engine.speak('测试静音尾隙。', settings, () => { ended = true }, () => {}, 'replace', { chunkId: 'chunk-silence' })
  await new Promise(resolve => setTimeout(resolve, 10))

  const events = FakeEventSource.instances[FakeEventSource.instances.length - 1]
  const audio = FakeAudio.instances[FakeAudio.instances.length - 1]

  events.emit('chunk_end', {
    type: 'chunk_end',
    sessionId: 'session-silence',
    chunkId: 'chunk-silence',
    durationMs: 3000,
  })

  // Audio halts at 2.6s (400ms before 3.0s duration due to Edge-TTS trailing silence frames)
  audio.currentTime = 2.60
  audio.emit('timeupdate')
  assert.equal(ended, false, 'Should not end immediately before timeout')

  // Wait 400ms for stall watchdog
  await new Promise(resolve => setTimeout(resolve, 400))
  audio.emit('timeupdate')
  assert.equal(ended, true, 'Stall watchdog with 600ms window should trigger onEnd after 350ms silence')

  engine.stop()
})



