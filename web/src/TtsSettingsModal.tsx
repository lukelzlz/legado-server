import React, { useEffect, useState } from 'react'
import { api, TtsVoice } from './api'
import { Icon } from './icons'
import { ReaderSettings, TtsEngineType } from './readerSettings'

export type SleepTimerOption = 'off' | '15' | '30' | '45' | '60' | 'chapter' | 'paragraph'

export interface TtsSettingsModalProps {
  settings: ReaderSettings
  onChange: (settings: ReaderSettings) => void
  sleepTimer: SleepTimerOption
  onSleepTimerChange: (option: SleepTimerOption) => void
  remainingSeconds: number | null
  onClose: () => void
}

const SPEED_PRESETS = [0.8, 1.0, 1.25, 1.5, 2.0]

const DEFAULT_EDGE_VOICES: TtsVoice[] = [
  { id: 'zh-CN-XiaoxiaoNeural', name: '晓晓 (女声·温暖自然·推荐)', lang: 'zh-CN', gender: 'Female', localeName: '中文普通话', engine: 'edge', description: '适合小说叙述与日常对话' },
  { id: 'zh-CN-YunxiNeural', name: '云希 (男声·沉稳磁性·推荐)', lang: 'zh-CN', gender: 'Male', localeName: '中文普通话', engine: 'edge', description: '适合玄幻、都市小说旁白与对话' },
  { id: 'zh-CN-YunjianNeural', name: '云健 (男声·激情影视解说)', lang: 'zh-CN', gender: 'Male', localeName: '中文普通话', engine: 'edge', description: '影视解说、热血玄幻音色' },
  { id: 'zh-CN-XiaoyiNeural', name: '晓伊 (女声·柔和抒情)', lang: 'zh-CN', gender: 'Female', localeName: '中文普通话', engine: 'edge', description: '抒情、言情、文艺小说' },
  { id: 'zh-CN-YunyangNeural', name: '云扬 (男声·专业新闻播报)', lang: 'zh-CN', gender: 'Male', localeName: '中文普通话', engine: 'edge', description: '专业稳重播音员音色' },
  { id: 'zh-CN-liaoning-XiaobeiNeural', name: '晓北 (东北话·风趣女声)', lang: 'zh-CN-liaoning', gender: 'Female', localeName: '东北话', engine: 'edge', description: '风趣幽默的东北口音' },
  { id: 'zh-CN-shaanxi-XiaoniNeural', name: '晓妮 (陕西话·特色女声)', lang: 'zh-CN-shaanxi', gender: 'Female', localeName: '陕西话', engine: 'edge', description: '地道西北陕西口音' },
  { id: 'zh-TW-HsiaoChenNeural', name: '晓臻 (台湾腔·温柔女声)', lang: 'zh-TW', gender: 'Female', localeName: '台湾国语', engine: 'edge', description: '台湾国语自然女声' },
  { id: 'zh-TW-YunJheNeural', name: '云哲 (台湾腔·清澈男声)', lang: 'zh-TW', gender: 'Male', localeName: '台湾国语', engine: 'edge', description: '台湾国语阳光男声' },
  { id: 'zh-HK-HiuMaanNeural', name: '晓曼 (粤语·自然女声)', lang: 'zh-HK', gender: 'Female', localeName: '粤语', engine: 'edge', description: '标准粤语自然女声' },
  { id: 'zh-HK-WanLungNeural', name: '云龙 (粤语·磁性男声)', lang: 'zh-HK', gender: 'Male', localeName: '粤语', engine: 'edge', description: '标准粤语磁性男声' },
  { id: 'en-US-JennyNeural', name: 'Jenny (英文·自然女声)', lang: 'en-US', gender: 'Female', localeName: 'English (US)', engine: 'edge', description: 'Natural American English Female' },
  { id: 'en-US-GuyNeural', name: 'Guy (英文·自然男声)', lang: 'en-US', gender: 'Male', localeName: 'English (US)', engine: 'edge', description: 'Natural American English Male' },
]

export const TtsSettingsModal: React.FC<TtsSettingsModalProps> = ({
  settings,
  onChange,
  sleepTimer,
  onSleepTimerChange,
  remainingSeconds,
  onClose,
}) => {
  const [edgeVoices, setEdgeVoices] = useState<TtsVoice[]>(DEFAULT_EDGE_VOICES)
  const [localVoices, setLocalVoices] = useState<SpeechSynthesisVoice[]>([])

  useEffect(() => {
    // Fetch server voices
    api.getTtsVoices()
      .then(voices => {
        if (voices && voices.length > 0) setEdgeVoices(voices)
      })
      .catch(() => undefined)

    // Load local voices
    if ('speechSynthesis' in window) {
      const updateVoices = () => {
        const v = window.speechSynthesis.getVoices()
        if (v && v.length > 0) setLocalVoices(v)
      }
      updateVoices()
      window.speechSynthesis.addEventListener('voiceschanged', updateVoices)
      return () => {
        window.speechSynthesis.removeEventListener('voiceschanged', updateVoices)
      }
    }
  }, [])

  const handleEngineChange = (engine: TtsEngineType) => {
    let defaultVoice = settings.ttsVoice
    if (engine === 'edge') {
      defaultVoice = 'zh-CN-XiaoxiaoNeural'
    } else if (engine === 'webSpeech' && localVoices.length > 0) {
      const zh = localVoices.find(v => v.lang.startsWith('zh'))
      defaultVoice = zh ? zh.voiceURI : localVoices[0].voiceURI
    }
    onChange({ ...settings, ttsEngine: engine, ttsVoice: defaultVoice })
  }

  const formatTimerRemaining = (seconds: number) => {
    const mins = Math.floor(seconds / 60)
    const secs = seconds % 60
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`
  }

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="tts-settings-dialog" onClick={e => e.stopPropagation()} role="dialog" aria-modal="true">
        <header className="source-login-header">
          <div className="source-login-title-group">
            <h3 className="source-login-title"><Icon name="volume2" /> 听书朗读设置</h3>
          </div>
          <button type="button" className="source-login-action-btn" onClick={onClose} title="关闭">✕</button>
        </header>

        <div className="source-login-body tts-modal-content">
          {/* Engine Selection */}
          <div className="tts-setting-section">
            <label className="tts-section-label">朗读引擎</label>
            <div className="tts-engine-grid">
              <button
                type="button"
                className={`tts-engine-btn ${settings.ttsEngine === 'edge' ? 'active' : ''}`}
                onClick={() => handleEngineChange('edge')}
              >
                <strong>微软 Edge-TTS</strong>
                <span>高拟真 AI 神经网络音色 (推荐)</span>
              </button>
              <button
                type="button"
                className={`tts-engine-btn ${settings.ttsEngine === 'webSpeech' ? 'active' : ''}`}
                onClick={() => handleEngineChange('webSpeech')}
              >
                <strong>浏览器 Web Speech</strong>
                <span>系统本地音色·零流量秒开</span>
              </button>
              <button
                type="button"
                className={`tts-engine-btn ${settings.ttsEngine === 'custom' ? 'active' : ''}`}
                onClick={() => handleEngineChange('custom')}
              >
                <strong>自定义 HTTP 源</strong>
                <span>自建 API 或第三方 TTS</span>
              </button>
            </div>
          </div>

          {/* Voice Selection */}
          {settings.ttsEngine === 'edge' && (
            <div className="tts-setting-section">
              <label className="tts-section-label">发音人 (音色)</label>
              <select
                className="login-ui-select"
                value={settings.ttsVoice}
                onChange={e => onChange({ ...settings, ttsVoice: e.target.value })}
              >
                {edgeVoices.map(v => (
                  <option key={v.id} value={v.id}>
                    {v.name} ({v.localeName})
                  </option>
                ))}
              </select>
            </div>
          )}

          {settings.ttsEngine === 'webSpeech' && (
            <div className="tts-setting-section">
              <label className="tts-section-label">系统发音人</label>
              <select
                className="login-ui-select"
                value={settings.ttsVoice}
                onChange={e => onChange({ ...settings, ttsVoice: e.target.value })}
              >
                {localVoices.map((v, i) => (
                  <option key={`${v.voiceURI}-${i}`} value={v.voiceURI}>
                    {v.name} ({v.lang})
                  </option>
                ))}
              </select>
            </div>
          )}

          {/* Speed / Rate */}
          <div className="tts-setting-section">
            <div className="tts-slider-header">
              <label className="tts-section-label">语速调节</label>
              <span className="tts-value-badge">{settings.ttsSpeed.toFixed(1)}x</span>
            </div>
            <input
              type="range"
              min="0.5"
              max="3.0"
              step="0.1"
              value={settings.ttsSpeed}
              onChange={e => onChange({ ...settings, ttsSpeed: parseFloat(e.target.value) })}
              className="tts-slider"
            />
            <div className="tts-preset-chips">
              {SPEED_PRESETS.map(preset => (
                <button
                  key={preset}
                  type="button"
                  className={`tts-chip ${settings.ttsSpeed === preset ? 'active' : ''}`}
                  onClick={() => onChange({ ...settings, ttsSpeed: preset })}
                >
                  {preset}x
                </button>
              ))}
            </div>
          </div>

          {/* Pitch */}
          <div className="tts-setting-section">
            <div className="tts-slider-header">
              <label className="tts-section-label">语调 (Pitch)</label>
              <span className="tts-value-badge">{settings.ttsPitch.toFixed(2)}</span>
            </div>
            <input
              type="range"
              min="0.5"
              max="1.5"
              step="0.05"
              value={settings.ttsPitch}
              onChange={e => onChange({ ...settings, ttsPitch: parseFloat(e.target.value) })}
              className="tts-slider"
            />
          </div>

          {/* Sleep Timer */}
          <div className="tts-setting-section">
            <div className="tts-slider-header">
              <label className="tts-section-label">睡眠定时器 (定时关闭)</label>
              {remainingSeconds !== null && remainingSeconds > 0 && (
                <span className="tts-countdown-badge">⏳ 倒计时 {formatTimerRemaining(remainingSeconds)}</span>
              )}
            </div>
            <div className="tts-timer-grid">
              <button
                type="button"
                className={`tts-timer-btn ${sleepTimer === 'off' ? 'active' : ''}`}
                onClick={() => onSleepTimerChange('off')}
              >
                关闭
              </button>
              <button
                type="button"
                className={`tts-timer-btn ${sleepTimer === '15' ? 'active' : ''}`}
                onClick={() => onSleepTimerChange('15')}
              >
                15 分钟
              </button>
              <button
                type="button"
                className={`tts-timer-btn ${sleepTimer === '30' ? 'active' : ''}`}
                onClick={() => onSleepTimerChange('30')}
              >
                30 分钟
              </button>
              <button
                type="button"
                className={`tts-timer-btn ${sleepTimer === '45' ? 'active' : ''}`}
                onClick={() => onSleepTimerChange('45')}
              >
                45 分钟
              </button>
              <button
                type="button"
                className={`tts-timer-btn ${sleepTimer === '60' ? 'active' : ''}`}
                onClick={() => onSleepTimerChange('60')}
              >
                60 分钟
              </button>
              <button
                type="button"
                className={`tts-timer-btn ${sleepTimer === 'chapter' ? 'active' : ''}`}
                onClick={() => onSleepTimerChange('chapter')}
              >
                读完本章
              </button>
            </div>
          </div>

          {/* Toggles */}
          <div className="tts-setting-section">
            <div className="tts-toggle-row">
              <label className="login-ui-toggle-label">
                <span>读完本章自动连播下一章</span>
                <input
                  type="checkbox"
                  checked={settings.ttsAutoNextChapter}
                  onChange={e => onChange({ ...settings, ttsAutoNextChapter: e.target.checked })}
                />
              </label>
            </div>
            <div className="tts-toggle-row">
              <label className="login-ui-toggle-label">
                <span>过滤特殊标点与多余装饰符号</span>
                <input
                  type="checkbox"
                  checked={settings.ttsFilterSymbols}
                  onChange={e => onChange({ ...settings, ttsFilterSymbols: e.target.checked })}
                />
              </label>
            </div>
          </div>

          {/* Custom HTTP TTS Settings */}
          {settings.ttsEngine === 'custom' && (
            <div className="tts-setting-section tts-custom-section">
              <label className="tts-section-label">自定义 HTTP 源参数</label>
              <p className="tts-hint">
                可用占位符：<code>&#123;&#123;speakText&#125;&#125;</code>, <code>&#123;&#123;speakSpeed&#125;&#125;</code>, <code>&#123;&#123;speakVoice&#125;&#125;</code>
              </p>
              <div className="login-ui-item-field">
                <label className="login-ui-field-label">接口 URL</label>
                <input
                  type="text"
                  className="login-ui-input"
                  placeholder="https://api.example.com/tts?text={{speakText}}&speed={{speakSpeed}}"
                  value={settings.ttsCustomUrl || ''}
                  onChange={e => onChange({ ...settings, ttsCustomUrl: e.target.value })}
                />
              </div>
              <div className="login-ui-item-field">
                <label className="login-ui-field-label">请求方式 (GET / POST)</label>
                <select
                  className="login-ui-select"
                  value={settings.ttsCustomMethod || 'GET'}
                  onChange={e => onChange({ ...settings, ttsCustomMethod: e.target.value })}
                >
                  <option value="GET">GET</option>
                  <option value="POST">POST</option>
                </select>
              </div>
              {settings.ttsCustomMethod === 'POST' && (
                <div className="login-ui-item-field">
                  <label className="login-ui-field-label">POST 请求体 (Body JSON)</label>
                  <textarea
                    className="source-login-textarea"
                    rows={3}
                    placeholder='{"text": "{{speakText}}", "voice": "{{speakVoice}}"}'
                    value={settings.ttsCustomBody || ''}
                    onChange={e => onChange({ ...settings, ttsCustomBody: e.target.value })}
                  />
                </div>
              )}
            </div>
          )}
        </div>

        <div style={{ padding: '12px 18px', display: 'flex', justifyContent: 'flex-end', borderTop: '1px solid var(--line)' }}>
          <button type="button" className="primary-button" onClick={onClose}>完成</button>
        </div>
      </div>
    </div>
  )
}
