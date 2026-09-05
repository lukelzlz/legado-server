import { api, TtsSessionChunkRequest } from './api'
import { ReaderSettings } from './readerSettings'

export type TtsPlayState = 'idle' | 'buffering' | 'playing' | 'paused'
export type TtsSpeakMode = 'replace' | 'continue'
export type TtsChunkContext = {
  chunkId?: string
  chapterIndex?: number
  paragraphIndex?: number
}

/**
 * Guard: returns true only if the text has >= 2 meaningful characters after stripping
 * punctuation, quotes, and brackets.  Prevents sending bare quotes/punctuation like `"` or `。`
 * to the TTS backend, which would return 0-byte audio and cause ERR_REQUEST_RANGE_NOT_SATISFIABLE.
 */
function isEffectiveText(text: string): boolean {
  if (!text) return false
  // Strip all punctuation, CJK brackets, quotes, whitespace
  const stripped = text.replace(
    /[\s\u0000-\u002F\u003A-\u0040\u005B-\u0060\u007B-\u00BF\u2000-\u206F\u2018\u2019\u201C\u201D\u3000-\u303F\uFF00-\uFFEF]/g,
    '',
  )
  return stripped.length >= 2
}

export interface ITtsEngine {
  speak(
    text: string,
    settings: ReaderSettings,
    onEnd: () => void,
    onError: (err: Error) => void,
    mode?: TtsSpeakMode,
    context?: TtsChunkContext,
  ): void
  pause(): void
  resume(): void
  stop(): void
}

/**
 * Web Speech API local engine
 */
export class WebSpeechEngine implements ITtsEngine {
  private currentUtterance: SpeechSynthesisUtterance | null = null
  private watchdogTimer: number | null = null
  private onEndCb: (() => void) | null = null
  private onErrorCb: ((err: Error) => void) | null = null

  speak(
    text: string,
    settings: ReaderSettings,
    onEnd: () => void,
    onError: (err: Error) => void,
    _mode: TtsSpeakMode = 'replace',
    _context?: TtsChunkContext,
  ): void {
    this.stop()
    if (!('speechSynthesis' in window)) {
      onError(new Error('当前浏览器不支持 Web Speech 语音合成'))
      return
    }

    this.onEndCb = onEnd
    this.onErrorCb = onError

    const utterance = new SpeechSynthesisUtterance(text)
    utterance.rate = Math.max(0.5, Math.min(2.5, settings.ttsSpeed))
    utterance.pitch = Math.max(0.5, Math.min(1.5, settings.ttsPitch))

    // Match voice if selected
    if (settings.ttsVoice) {
      const voices = window.speechSynthesis.getVoices()
      const matched = voices.find(v => v.voiceURI === settings.ttsVoice || v.name === settings.ttsVoice)
      if (matched) {
        utterance.voice = matched
      } else {
        // Fallback to zh-CN if not found
        utterance.lang = 'zh-CN'
      }
    } else {
      utterance.lang = 'zh-CN'
    }

    utterance.onend = () => {
      this.clearWatchdog()
      this.currentUtterance = null
      this.onEndCb?.()
    }

    utterance.onerror = (e) => {
      this.clearWatchdog()
      this.currentUtterance = null
      if (e.error !== 'canceled' && e.error !== 'interrupted') {
        this.onErrorCb?.(new Error(`语音播放异常: ${e.error}`))
      }
    }

    this.currentUtterance = utterance
    window.speechSynthesis.speak(utterance)
    this.startWatchdog()
  }

  pause(): void {
    if ('speechSynthesis' in window && window.speechSynthesis.speaking) {
      window.speechSynthesis.pause()
    }
  }

  resume(): void {
    if ('speechSynthesis' in window && window.speechSynthesis.paused) {
      window.speechSynthesis.resume()
    }
  }

  stop(): void {
    this.clearWatchdog()
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel()
    }
    this.currentUtterance = null
    this.onEndCb = null
    this.onErrorCb = null
  }

  // Workaround for Chrome 15s pause bug
  private startWatchdog() {
    this.clearWatchdog()
    this.watchdogTimer = window.setInterval(() => {
      if ('speechSynthesis' in window && window.speechSynthesis.speaking && !window.speechSynthesis.paused) {
        window.speechSynthesis.pause()
        window.speechSynthesis.resume()
      }
    }, 10000)
  }

  private clearWatchdog() {
    if (this.watchdogTimer !== null) {
      window.clearInterval(this.watchdogTimer)
      this.watchdogTimer = null
    }
  }
}

export function isPlayInterruptedError(err: unknown): boolean {
  if (!err) return false
  if (typeof DOMException !== 'undefined' && err instanceof DOMException && err.name === 'AbortError') return true
  if (err instanceof Error) {
    if (err.name === 'AbortError') return true
    const msg = err.message.toLowerCase()
    if (msg.includes('interrupted by a call to pause') || msg.includes('interrupted by a new load request')) {
      return true
    }
  }
  return false
}

/**
 * HTTP / Edge-TTS audio stream engine with sliding-window pre-buffering
 */
export class HttpAudioTtsEngine implements ITtsEngine {
  private audio: HTMLAudioElement | null = null
  private sessionId: string | null = null
  private sessionSettingsKey: string | null = null
  private sessionPromise: Promise<void> | null = null
  private sessionAbortController: AbortController | null = null
  private eventSource: EventSource | null = null
  private generation = 0
  private itemSequence = 0
  private pendingItems: StreamItem[] = []
  private submittedItems = new Set<string>()
  private completedItems = new Set<string>()
  private readyItems = new Map<string, number | null>()
  private callbacks = new Map<string, { onEnd: () => void; onError: (err: Error) => void }>()
  private currentItemId: string | null = null
  private currentItem: StreamItem | null = null
  private currentSettings: ReaderSettings | null = null
  private lastError: Error | null = null
  private recoveryAttempts = 0
  private playbackTimer: number | null = null
  private isPaused = false

  private getAudio(): HTMLAudioElement {
    if (!this.audio) {
      this.audio = new Audio()
      this.audio.preload = 'auto'
      this.audio.addEventListener('error', () => {
        if (!this.sessionId) return
        const err = this.audio?.error
        this.recoverOrReport(new Error(err?.message || '音频播放遇到错误'))
      })
      this.audio.addEventListener('ended', () => {
        this.stopPlaybackMonitor()
        this.recoverOrReport(new Error('音频流意外结束'))
      })
      this.audio.addEventListener('play', () => this.startPlaybackMonitor())
      this.audio.addEventListener('playing', () => {
        this.startPlaybackMonitor()
        this.drainPlayback()
      })
      this.audio.addEventListener('pause', () => this.stopPlaybackMonitor())
      this.audio.addEventListener('waiting', () => this.drainPlayback())
      this.audio.addEventListener('timeupdate', () => this.drainPlayback())
    }
    return this.audio
  }

  private startPlaybackMonitor() {
    this.stopPlaybackMonitor()
    this.playbackTimer = window.setInterval(() => {
      if (this.audio && !this.audio.paused) {
        this.drainPlayback()
      }
    }, 100)
  }

  private stopPlaybackMonitor() {
    if (this.playbackTimer !== null) {
      window.clearInterval(this.playbackTimer)
      this.playbackTimer = null
    }
  }

  async prefetch(text: string, settings: ReaderSettings, context: TtsChunkContext = {}): Promise<void> {
    const clean = text.trim()
    if (!isEffectiveText(clean)) return
    if (!this.sessionId && !this.sessionPromise) return
    const key = this.getCacheKey(clean, settings)
    const chunkId = context.chunkId || ''
    if (
      this.pendingItems.some(item => item.key === key || (chunkId && item.id === chunkId)) ||
      this.currentItem?.key === key ||
      (chunkId && this.submittedItems.has(chunkId))
    ) {
      return
    }
    const item = this.createItem(clean, settings, context)
    this.pendingItems.push(item)
    if (this.sessionId && this.sessionSettingsKey === this.getSettingsKey(settings)) {
      await this.submitItem(item, this.generation)
    }
  }

  speak(
    text: string,
    settings: ReaderSettings,
    onEnd: () => void,
    onError: (err: Error) => void,
    mode: TtsSpeakMode = 'replace',
    context: TtsChunkContext = {},
  ): void {
    const clean = text.trim()
    if (!isEffectiveText(clean)) {
      setTimeout(onEnd, 0)
      return
    }
    if (mode === 'replace' || (this.sessionId && this.sessionSettingsKey !== this.getSettingsKey(settings))) {
      this.resetSession()
    }
    this.isPaused = false

    const key = this.getCacheKey(clean, settings)
    const item = this.takePendingItem(key) ?? this.createItem(clean, settings, context)
    const previousItemId = this.currentItemId
    this.currentItemId = item.id
    this.currentItem = item
    this.currentSettings = settings
    if (mode === 'replace' || previousItemId !== item.id) this.recoveryAttempts = 0
    this.lastError = null
    this.callbacks.set(item.id, { onEnd, onError })
    if (this.completedItems.has(item.id)) {
      this.completedItems.delete(item.id)
      setTimeout(onEnd, 0)
      return
    }
    if (this.readyItems.has(item.id)) this.drainPlayback()

    if (!this.sessionId) {
      this.pendingItems = [item, ...this.pendingItems.filter(pending => pending.id !== item.id)]
      if (!this.sessionPromise) this.startSession(settings)
    } else {
      void this.submitItem(item, this.generation)
    }
  }

  pause(): void {
    this.isPaused = true
    this.audio?.pause()
    if (this.sessionId) void api.controlTtsSession(this.sessionId, 'pause').catch(() => undefined)
  }

  resume(): void {
    this.isPaused = false
    if (this.lastError) {
      this.restartCurrentItem()
      return
    }
    if (this.sessionId) void api.controlTtsSession(this.sessionId, 'resume').catch(() => undefined)
    this.audio?.play().catch(err => {
      if (isPlayInterruptedError(err)) return
      this.reportError(err instanceof Error ? err : new Error('无法继续播放音频'))
    })
  }

  stop(): void {
    this.resetSession()
  }

  clearPreloadCache(): void {
    this.pendingItems = []
  }

  private getCacheKey(text: string, settings: ReaderSettings): string {
    return `${settings.ttsEngine}:${settings.ttsVoice}:${settings.ttsSpeed}:${settings.ttsPitch}:${text}`
  }

  private getSettingsKey(settings: ReaderSettings): string {
    return `${settings.ttsEngine}:${settings.ttsVoice}:${settings.ttsSpeed}:${settings.ttsPitch}:${settings.ttsCustomUrl}:${settings.ttsCustomHeader}:${settings.ttsCustomMethod}:${settings.ttsCustomBody}`
  }

  private createItem(text: string, settings: ReaderSettings, context: TtsChunkContext = {}): StreamItem {
    const id = context.chunkId || `client-${++this.itemSequence}`
    return {
      id,
      key: this.getCacheKey(text, settings),
      request: this.createChunkRequest(id, text, settings, context),
    }
  }

  private createChunkRequest(id: string, text: string, settings: ReaderSettings, context: TtsChunkContext): TtsSessionChunkRequest {
    const ratePercent = Math.round((settings.ttsSpeed - 1.0) * 100)
    const pitchHz = Math.round((settings.ttsPitch - 1.0) * 50)
    return {
      chunkId: id,
      text,
      chapterIndex: context.chapterIndex ?? -1,
      paragraphIndex: context.paragraphIndex ?? -1,
      engine: settings.ttsEngine,
      voice: settings.ttsVoice || 'zh-CN-XiaoxiaoNeural',
      rate: ratePercent,
      pitch: pitchHz,
      customUrl: settings.ttsCustomUrl,
      customHeader: settings.ttsCustomHeader,
      customMethod: settings.ttsCustomMethod,
      customBody: settings.ttsCustomBody,
    }
  }

  private takePendingItem(key: string): StreamItem | undefined {
    const index = this.pendingItems.findIndex(item => item.key === key)
    if (index < 0) return undefined
    const [item] = this.pendingItems.splice(index, 1)
    return item
  }

  private async startSession(settings: ReaderSettings): Promise<void> {
    const generation = ++this.generation
    const abortController = new AbortController()
    this.sessionAbortController = abortController
    this.sessionSettingsKey = this.getSettingsKey(settings)
    this.sessionPromise = api.createTtsSession(abortController.signal)
      .then(async session => {
        if (generation !== this.generation) {
          void api.deleteTtsSession(session.sessionId).catch(() => undefined)
          return
        }
        this.sessionId = session.sessionId
        this.eventSource = this.openEvents(session.eventsUrl, generation)
        const audio = this.getAudio()
        audio.src = session.audioUrl
        audio.playbackRate = 1.0
        const items = [...this.pendingItems]
        this.pendingItems = []
        for (const item of items) await this.submitItem(item, generation)
        if (generation !== this.generation) return
        if (this.isPaused) return
        await audio.play().catch(err => {
          if (isPlayInterruptedError(err)) return
          this.reportError(err instanceof Error ? err : new Error('无法播放音频，请检查网络或点击页面授予音频权限'))
        })
      })
      .catch(error => {
        if (generation === this.generation) this.reportError(error instanceof Error ? error : new Error('创建 TTS 音频流失败'))
      })
      .finally(() => {
        if (generation === this.generation) {
          this.sessionPromise = null
          this.sessionAbortController = null
        }
      })
  }

  private async submitItem(item: StreamItem, generation: number): Promise<void> {
    if (!this.sessionId || generation !== this.generation || this.submittedItems.has(item.id)) return
    this.submittedItems.add(item.id)
    try {
      await api.appendTtsSessionChunk(this.sessionId, item.request)
    } catch (error) {
      this.submittedItems.delete(item.id)
      if (generation === this.generation) this.reportError(error instanceof Error ? error : new Error('提交 TTS 朗读分片失败'))
    }
  }

  private openEvents(url: string, generation: number): EventSource {
    const source = new EventSource(url)
    for (const type of ['chunk_end', 'tts_error', 'stopped']) {
      source.addEventListener(type, event => {
        if (generation !== this.generation) return
        let data: TtsStreamEvent
        try {
          data = JSON.parse((event as MessageEvent).data) as TtsStreamEvent
        } catch {
          this.reportError(new Error('TTS 进度事件格式无效'))
          return
        }
        if (type === 'chunk_end' && data.chunkId) {
          this.readyItems.set(data.chunkId, data.audioEndMs ?? null)
          this.drainPlayback()
        } else if (type === 'tts_error') {
          this.recoverOrReport(new Error(data.message || 'TTS 音频流合成失败'))
        } else if (type === 'stopped' && data.message !== 'stopped' && data.message !== 'removed') {
          this.recoverOrReport(new Error('TTS 音频流已停止'))
        }
      })
    }
    // EventSource reconnects automatically. Audio playback remains independent
    // from this metadata channel, so a transient event disconnect is harmless.
    source.onerror = () => undefined
    return source
  }

  private reportError(error: Error) {
    if (isPlayInterruptedError(error)) return
    if (this.lastError?.message === error.message) return
    this.lastError = error
    const callback = this.currentItemId ? this.callbacks.get(this.currentItemId) : undefined
    callback?.onError(error)
  }

  private recoverOrReport(error: Error) {
    const item = this.currentItem
    if (item && this.currentSettings && this.recoveryAttempts < 1) {
      this.recoveryAttempts++
      const settings = this.currentSettings
      const callback = this.callbacks.get(item.id)
      this.resetSession()
      if (callback) this.callbacks.set(item.id, callback)
      this.currentItemId = item.id
      this.currentItem = item
      this.currentSettings = settings
      this.pendingItems = [item]
      this.startSession(settings)
      return
    }
    this.reportError(error)
  }

  private restartCurrentItem() {
    const item = this.currentItem
    const settings = this.currentSettings
    const callback = item ? this.callbacks.get(item.id) : undefined
    this.resetSession()
    if (!item || !settings) return
    this.currentItemId = item.id
    this.currentItem = item
    this.currentSettings = settings
    if (callback) this.callbacks.set(item.id, callback)
    this.pendingItems = [item]
    this.recoveryAttempts = 0
    this.startSession(settings)
  }

  private drainPlayback() {
    const nowMs = this.audio ? this.audio.currentTime * 1000 : Number.NaN
    for (const [id, endMs] of this.readyItems) {
      if (endMs !== null && Number.isFinite(nowMs) && nowMs + 180 < endMs) continue
      this.readyItems.delete(id)
      this.completedItems.add(id)
      const callback = this.callbacks.get(id)
      if (callback) {
        this.callbacks.delete(id)
        callback.onEnd()
      }
    }
  }

  private resetSession() {
    const sessionId = this.sessionId
    this.isPaused = false
    this.stopPlaybackMonitor()
    this.generation++
    this.sessionAbortController?.abort()
    this.sessionAbortController = null
    this.eventSource?.close()
    this.eventSource = null
    this.sessionId = null
    this.sessionSettingsKey = null
    this.sessionPromise = null
    this.submittedItems.clear()
    this.completedItems.clear()
    this.readyItems.clear()
    this.callbacks.clear()
    this.currentItemId = null
    this.currentItem = null
    this.currentSettings = null
    this.lastError = null
    this.pendingItems = []
    if (this.audio) {
      this.audio.pause()
      this.audio.removeAttribute('src')
      this.audio.load()
    }
    if (sessionId) {
      void api.controlTtsSession(sessionId, 'stop').catch(() => undefined)
    }
  }
}

type StreamItem = {
  id: string
  key: string
  request: TtsSessionChunkRequest
}

type TtsStreamEvent = {
  type: string
  sessionId: string
  chunkId?: string
  audioEndMs?: number
  message?: string
}
