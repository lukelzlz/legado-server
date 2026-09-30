import React from 'react'
import { useTranslation } from 'react-i18next'
import { Icon } from './icons'
import { TtsPlayState } from './ttsEngine'
import { SleepTimerOption } from './TtsSettingsModal'

export interface TtsPlayerBarProps {
  playState: TtsPlayState
  currentParagraphIndex: number
  totalParagraphs: number
  currentChunkIndex: number
  totalChunks: number
  activeSentenceText: string
  sleepTimer: SleepTimerOption
  remainingSeconds: number | null
  onTogglePlay: () => void
  onPrevChunk: () => void
  onNextChunk: () => void
  onPrevChapter: () => void
  onNextChapter: () => void
  onOpenSettings: () => void
  onClose: () => void
}

export const TtsPlayerBar: React.FC<TtsPlayerBarProps> = ({
  playState,
  currentParagraphIndex,
  totalParagraphs,
  currentChunkIndex,
  totalChunks,
  activeSentenceText,
  sleepTimer,
  remainingSeconds,
  onTogglePlay,
  onPrevChunk,
  onNextChunk,
  onPrevChapter,
  onNextChapter,
  onOpenSettings,
  onClose,
}) => {
  const { t } = useTranslation()
  const isPlaying = playState === 'playing'
  const isBuffering = playState === 'buffering'

  const formatTimerLabel = () => {
    if (sleepTimer === 'off') return null
    if (sleepTimer === 'chapter') return t('tts.timerChapterShort', '本章止')
    if (sleepTimer === 'paragraph') return t('tts.timerParagraphShort', '本段止')
    if (remainingSeconds !== null && remainingSeconds > 0) {
      const mins = Math.ceil(remainingSeconds / 60)
      return `${mins}m`
    }
    return `${sleepTimer}m`
  }

  const timerLabel = formatTimerLabel()

  return (
    <aside className="tts-floating-bar" aria-label={t('tts.playerBarAria', '朗读播放控制器')}>
      {/* Progress & Live Text Snippet */}
      <div className="tts-bar-info" onClick={onOpenSettings} title={t('tts.openSettingsHint', '点击展开朗读设置')}>
        <div className="tts-bar-status-dot-wrap">
          <span className={`tts-bar-status-dot ${isPlaying ? 'pulse' : ''}`} />
        </div>
        <div className="tts-bar-text-group">
          <div className="tts-bar-meta">
            <span className="tts-bar-badge">
              {currentParagraphIndex >= 0 ? t('tts.paragraphProgress', { current: currentParagraphIndex + 1, total: totalParagraphs, defaultValue: `第 ${currentParagraphIndex + 1}/${totalParagraphs} 段` }) : t('tts.chapterTitleBadge', '章节标题')}
            </span>
            {isBuffering && <span className="tts-bar-loading">{t('tts.loadingAudio', '加载音频中...')}</span>}
            {timerLabel && (
              <span className="tts-bar-timer-tag">
                <Icon name="clock" style={{ width: 12, height: 12 }} />
                {timerLabel}
              </span>
            )}
          </div>
          <div className="tts-bar-snippet" title={activeSentenceText}>
            {activeSentenceText || t('tts.readyToRead', '准备朗读...')}
          </div>
        </div>
      </div>

      {/* Main Playback Controls */}
      <div className="tts-bar-controls">
        <button
          type="button"
          className="tts-bar-btn nav-btn"
          onClick={onPrevChapter}
          title={t('reader.prevChapter', '上一章')}
        >
          <Icon name="arrowLeft" />
        </button>

        <button
          type="button"
          className="tts-bar-btn"
          onClick={onPrevChunk}
          disabled={currentChunkIndex <= 0}
          title={t('tts.prevChunk', '上一句 / 上一段')}
        >
          <Icon name="skipBack" />
        </button>

        <button
          type="button"
          className="tts-bar-btn primary-play-btn"
          onClick={onTogglePlay}
          title={isPlaying ? t('tts.pauseReading', '暂停朗读') : t('tts.resumeReading', '继续朗读')}
        >
          <Icon name={isPlaying ? 'pause' : 'play'} />
        </button>

        <button
          type="button"
          className="tts-bar-btn"
          onClick={onNextChunk}
          disabled={currentChunkIndex >= totalChunks - 1}
          title={t('tts.nextChunk', '下一句 / 下一段')}
        >
          <Icon name="skipForward" />
        </button>

        <button
          type="button"
          className="tts-bar-btn nav-btn"
          onClick={onNextChapter}
          title={t('reader.nextChapter', '下一章')}
        >
          <Icon name="arrowRight" />
        </button>
      </div>

      {/* Actions */}
      <div className="tts-bar-actions">
        <button
          type="button"
          className="tts-bar-btn subtle-btn"
          onClick={onOpenSettings}
          title={t('tts.settingsAndTuning', '朗读与调音设置')}
        >
          <Icon name="sliders" />
        </button>

        <button
          type="button"
          className="tts-bar-btn subtle-btn close-btn"
          onClick={onClose}
          title={t('tts.exitTts', '退出听书')}
        >
          <Icon name="close" />
        </button>
      </div>
    </aside>
  )
}
