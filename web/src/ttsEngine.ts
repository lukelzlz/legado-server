import { api, TtsSpeakRequest } from './api'
import { ReaderSettings, TtsEngineType } from './readerSettings'

export type TtsPlayState = 'idle' | 'buffering' | 'playing' | 'paused'

export interface ITtsEngine {
  speak(
    text: string,
    settings: ReaderSettings,
    onEnd: () => void,
    onError: (err: Error) => void,
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

/**
 * HTTP / Edge-TTS audio stream engine with sliding-window pre-buffering
 */
export class HttpAudioTtsEngine implements ITtsEngine {
  private audio: HTMLAudioElement | null = null
  private currentObjectUrl: string | null = null
  private preloadedBlobUrls = new Map<string, string>()
  private onEndCb: (() => void) | null = null
  private onErrorCb: ((err: Error) => void) | null = null
  private abortController: AbortController | null = null

  private getAudio(): HTMLAudioElement {
    if (!this.audio) {
      this.audio = new Audio()
      this.audio.preload = 'auto'
      this.audio.addEventListener('ended', () => {
        this.cleanupCurrentUrl()
        this.onEndCb?.()
      })
      this.audio.addEventListener('error', () => {
        this.cleanupCurrentUrl()
        const err = this.audio?.error
        this.onErrorCb?.(new Error(err?.message || '音频播放遇到错误'))
      })
    }
    return this.audio
  }

  async prefetch(text: string, settings: ReaderSettings): Promise<void> {
    const clean = text.trim()
    if (!clean) return
    const key = this.getCacheKey(clean, settings)
    if (this.preloadedBlobUrls.has(key)) return

    try {
      const blob = await this.fetchAudioBlob(clean, settings)
      const url = URL.createObjectURL(blob)
      this.preloadedBlobUrls.set(key, url)
    } catch {
      // Ignore prefetch errors silently
    }
  }

  speak(
    text: string,
    settings: ReaderSettings,
    onEnd: () => void,
    onError: (err: Error) => void,
  ): void {
    this.stop()
    const clean = text.trim()
    if (!clean) {
      onEnd()
      return
    }

    this.onEndCb = onEnd
    this.onErrorCb = onError
    this.abortController = new AbortController()

    const audio = this.getAudio()
    const key = this.getCacheKey(clean, settings)

    const startPlayback = (blobUrl: string) => {
      this.currentObjectUrl = blobUrl
      audio.src = blobUrl
      audio.playbackRate = 1.0 // speed is already controlled via TTS synthesis rate
      audio.play().catch((err) => {
        if (this.abortController?.signal.aborted) return
        onError(err instanceof Error ? err : new Error('无法播放音频，请检查网络或点击页面授予音频权限'))
      })
    }

    if (this.preloadedBlobUrls.has(key)) {
      const cachedUrl = this.preloadedBlobUrls.get(key)!
      this.preloadedBlobUrls.delete(key)
      startPlayback(cachedUrl)
      return
    }

    this.fetchAudioBlob(clean, settings)
      .then((blob) => {
        if (this.abortController?.signal.aborted) return
        const url = URL.createObjectURL(blob)
        startPlayback(url)
      })
      .catch((err) => {
        if (this.abortController?.signal.aborted) return
        onError(err instanceof Error ? err : new Error('获取 TTS 音频失败'))
      })
  }

  pause(): void {
    this.audio?.pause()
  }

  resume(): void {
    this.audio?.play().catch(() => undefined)
  }

  stop(): void {
    this.abortController?.abort()
    this.abortController = null
    if (this.audio) {
      this.audio.pause()
      this.audio.removeAttribute('src')
      this.audio.load()
    }
    this.cleanupCurrentUrl()
    this.onEndCb = null
    this.onErrorCb = null
  }

  clearPreloadCache(): void {
    for (const url of this.preloadedBlobUrls.values()) {
      try { URL.revokeObjectURL(url) } catch {}
    }
    this.preloadedBlobUrls.clear()
  }

  private cleanupCurrentUrl() {
    if (this.currentObjectUrl) {
      try { URL.revokeObjectURL(this.currentObjectUrl) } catch {}
      this.currentObjectUrl = null
    }
  }

  private getCacheKey(text: string, settings: ReaderSettings): string {
    return `${settings.ttsEngine}:${settings.ttsVoice}:${settings.ttsSpeed}:${settings.ttsPitch}:${text}`
  }

  private async fetchAudioBlob(text: string, settings: ReaderSettings): Promise<Blob> {
    const ratePercent = Math.round((settings.ttsSpeed - 1.0) * 100)
    const pitchHz = Math.round((settings.ttsPitch - 1.0) * 50)
    const req: TtsSpeakRequest = {
      text,
      voice: settings.ttsVoice || 'zh-CN-XiaoxiaoNeural',
      rate: ratePercent,
      pitch: pitchHz,
      engine: settings.ttsEngine,
      customUrl: settings.ttsCustomUrl,
      customHeader: settings.ttsCustomHeader,
      customMethod: settings.ttsCustomMethod,
      customBody: settings.ttsCustomBody,
    }
    return api.fetchTtsAudioBlob(req)
  }
}
