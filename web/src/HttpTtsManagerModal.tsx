import React, { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, HttpTts } from './api'
import { Icon } from './icons'

export interface HttpTtsManagerModalProps {
  onClose: () => void
  onSelectTts?: (tts: HttpTts) => void
  currentTtsId?: number
}

export const HttpTtsManagerModal: React.FC<HttpTtsManagerModalProps> = ({
  onClose,
  onSelectTts,
  currentTtsId,
}) => {
  const { t } = useTranslation()
  const [list, setList] = useState<HttpTts[]>([])
  const [loading, setLoading] = useState(true)
  const [searchQuery, setSearchQuery] = useState('')
  const [editingTts, setEditingTts] = useState<HttpTts | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [importModalOpen, setImportModalOpen] = useState(false)
  const [importText, setImportText] = useState('')
  const [testingId, setTestingId] = useState<number | null>(null)
  const [message, setMessage] = useState<{ text: string; type: 'success' | 'error' } | null>(null)

  const audioRef = useRef<HTMLAudioElement | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const showToast = (text: string, type: 'success' | 'error' = 'success') => {
    setMessage({ text, type })
    setTimeout(() => setMessage(null), 3500)
  }

  const loadList = async () => {
    try {
      setLoading(true)
      const data = await api.getHttpTtsList()
      setList(data)
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      showToast(msg, 'error')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadList()
  }, [])

  const handleDelete = async (tts: HttpTts) => {
    if (!tts.id) return
    const confirmed = window.confirm(t('tts.confirmDelete', '确定要删除该朗读引擎吗？\n{{name}}', { name: tts.name }))
    if (!confirmed) return
    try {
      await api.deleteHttpTts(tts.id)
      showToast(t('tts.deletedSuccess', '已删除'), 'success')
      setList(prev => prev.filter(item => item.id !== tts.id))
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      showToast(msg, 'error')
    }
  }

  const handleExportSingle = (tts: HttpTts) => {
    const jsonStr = JSON.stringify([tts], null, 2)
    const blob = new Blob([jsonStr], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${tts.name || 'httpTTS'}.httpTTS.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  const handleExportAll = () => {
    if (list.length === 0) {
      showToast(t('tts.emptyExport', '当前没有可导出的朗读引擎'), 'error')
      return
    }
    const jsonStr = JSON.stringify(list, null, 2)
    const blob = new Blob([jsonStr], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'httpTTS.json'
    a.click()
    URL.revokeObjectURL(url)
  }

  const handleTestTts = async (tts: HttpTts) => {
    const testId = tts.id ?? -1
    setTestingId(testId)
    try {
      if (audioRef.current) {
        audioRef.current.pause()
        audioRef.current = null
      }
      const blob = await api.testHttpTts({
        tts,
        text: '欢迎使用开源阅读自定义朗读',
      })
      const audioUrl = URL.createObjectURL(blob)
      const audio = new Audio(audioUrl)
      audioRef.current = audio
      audio.onended = () => {
        setTestingId(null)
        URL.revokeObjectURL(audioUrl)
      }
      audio.onerror = () => {
        setTestingId(null)
        URL.revokeObjectURL(audioUrl)
        showToast(t('tts.playbackFailed', '音频播放失败，请检查返回格式'), 'error')
      }
      await audio.play()
      showToast(t('tts.testSuccess', '合成成功，正在试听发音'), 'success')
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      showToast(msg, 'error')
      setTestingId(null)
    }
  }

  const handleImportJsonText = async (text: string) => {
    try {
      const parsed = JSON.parse(text)
      const rawList = Array.isArray(parsed) ? parsed : [parsed]
      const items: HttpTts[] = rawList.map((item: Record<string, unknown>, idx: number) => ({
        id: typeof item.id === 'number' ? item.id : Date.now() + idx,
        name: String(item.name || `TTS-${idx + 1}`),
        url: String(item.url || ''),
        header: typeof item.header === 'string' ? item.header : (item.header ? JSON.stringify(item.header) : null),
        contentType: typeof item.contentType === 'string' ? item.contentType : null,
        concurrentRate: typeof item.concurrentRate === 'string' ? item.concurrentRate : null,
        loginUrl: typeof item.loginUrl === 'string' ? item.loginUrl : null,
        loginCheckJs: typeof item.loginCheckJs === 'string' ? item.loginCheckJs : null,
        loginUi: typeof item.loginUi === 'string' ? item.loginUi : (item.loginUi ? JSON.stringify(item.loginUi) : null),
        jsLib: typeof item.jsLib === 'string' ? item.jsLib : null,
        enabledCookieJar: Boolean(item.enabledCookieJar),
        lastUpdateTime: typeof item.lastUpdateTime === 'number' ? item.lastUpdateTime : Date.now(),
      }))

      if (items.length === 0 || !items.some(i => i.url)) {
        throw new Error(t('tts.invalidJsonFormat', '未识别到有效的 httpTTS 规则'))
      }

      const res = await api.importHttpTts(items)
      showToast(t('tts.importResult', '成功导入 {{count}} 条规则', { count: res.imported }), 'success')
      setImportModalOpen(false)
      setImportText('')
      loadList()
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err)
      showToast(msg, 'error')
    }
  }

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = evt => {
      const content = evt.target?.result
      if (typeof content === 'string') {
        handleImportJsonText(content)
      }
    }
    reader.readAsText(file, 'utf-8')
    e.target.value = ''
  }

  const filteredList = list.filter(item => {
    if (!searchQuery.trim()) return true
    const q = searchQuery.toLowerCase()
    return item.name.toLowerCase().includes(q) || item.url.toLowerCase().includes(q)
  })

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className="tts-settings-dialog http-tts-manager-dialog"
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        style={{ width: 'min(720px, 96vw)', maxHeight: '90vh' }}
      >
        <header className="source-login-header">
          <div className="source-login-title-group">
            <h3 className="source-login-title">
              <Icon name="volume2" /> {t('tts.managerTitle', '自定义 HTTP 朗读引擎管理')}
            </h3>
          </div>
          <button type="button" className="source-login-action-btn" onClick={onClose} title={t('common.close', '关闭')}>
            ✕
          </button>
        </header>

        {message && (
          <div
            style={{
              padding: '8px 16px',
              fontSize: '13px',
              backgroundColor: message.type === 'error' ? 'var(--danger-bg, #fee2e2)' : 'var(--success-bg, #dcfce7)',
              color: message.type === 'error' ? 'var(--danger, #ef4444)' : 'var(--success, #16a34a)',
              borderBottom: '1px solid var(--line)',
            }}
          >
            {message.text}
          </div>
        )}

        {/* Toolbar */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '12px 18px',
            borderBottom: '1px solid var(--line)',
            gap: '8px',
            flexWrap: 'wrap',
          }}
        >
          <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
            <button
              type="button"
              className="primary-button"
              style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', padding: '6px 12px', fontSize: '13px' }}
              onClick={() => {
                setEditingTts({
                  name: '',
                  url: '',
                  header: '',
                  contentType: 'audio/mpeg',
                  concurrentRate: '0',
                })
                setIsNew(true)
              }}
            >
              <Icon name="plus" /> {t('tts.newEngine', '新建引擎')}
            </button>
            <button
              type="button"
              className="secondary-button"
              style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', padding: '6px 12px', fontSize: '13px' }}
              onClick={() => setImportModalOpen(true)}
            >
              <Icon name="cloudDownload" /> {t('tts.importJson', '导入 httpTTS')}
            </button>
            <button
              type="button"
              className="secondary-button"
              style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', padding: '6px 12px', fontSize: '13px' }}
              onClick={handleExportAll}
              title={t('tts.exportAllDesc', '导出全部规则为 httpTTS.json')}
            >
              <Icon name="download" /> {t('tts.exportAll', '全部导出')}
            </button>
          </div>

          <div style={{ position: 'relative', minWidth: '160px', flex: '1', maxWidth: '240px' }}>
            <input
              type="text"
              className="login-ui-input"
              style={{ padding: '6px 10px', fontSize: '13px' }}
              placeholder={t('common.search', '搜索...')}
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
            />
          </div>
        </div>

        {/* List Content */}
        <div className="source-login-body" style={{ flex: 1, overflowY: 'auto', padding: '12px 18px' }}>
          {loading ? (
            <div style={{ padding: '30px', textAlign: 'center', color: 'var(--muted)' }}>
              {t('common.loading', '正在加载...')}
            </div>
          ) : filteredList.length === 0 ? (
            <div style={{ padding: '40px 20px', textAlign: 'center', color: 'var(--muted)' }}>
              <p style={{ margin: '0 0 10px 0', fontSize: '14px' }}>{t('tts.noEngines', '暂无自定义 HTTP 朗读引擎')}</p>
              <span style={{ fontSize: '12px' }}>
                {t('tts.importPrompt', '可点击上方“新建引擎”手动配置，或点击“导入 httpTTS”载入 Legado 手机端导出的规则。')}
              </span>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {filteredList.map(tts => {
                const isSelected = currentTtsId === tts.id
                const isTesting = testingId === tts.id
                return (
                  <div
                    key={tts.id}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      padding: '12px 14px',
                      borderRadius: '8px',
                      border: isSelected ? '1.5px solid var(--accent)' : '1px solid var(--line)',
                      backgroundColor: 'var(--surface-muted)',
                      gap: '12px',
                    }}
                  >
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                        <strong style={{ fontSize: '14px', color: 'var(--ink)' }}>{tts.name}</strong>
                        {isSelected && (
                          <span
                            style={{
                              fontSize: '11px',
                              padding: '1px 6px',
                              borderRadius: '4px',
                              backgroundColor: 'var(--accent)',
                              color: '#fff',
                            }}
                          >
                            {t('tts.currentActive', '当前选中')}
                          </span>
                        )}
                        {tts.contentType && (
                          <span
                            style={{
                              fontSize: '11px',
                              padding: '1px 6px',
                              borderRadius: '4px',
                              backgroundColor: 'var(--line)',
                              color: 'var(--muted)',
                            }}
                          >
                            {tts.contentType.replace('audio/', '').toUpperCase()}
                          </span>
                        )}
                      </div>
                      <div
                        style={{
                          fontSize: '12px',
                          color: 'var(--muted)',
                          whiteSpace: 'nowrap',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                        }}
                        title={tts.url}
                      >
                        {tts.url}
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', flexShrink: 0 }}>
                      <button
                        type="button"
                        className="secondary-button"
                        style={{ padding: '5px 10px', fontSize: '12px', display: 'inline-flex', alignItems: 'center', gap: '4px' }}
                        onClick={() => handleTestTts(tts)}
                        disabled={isTesting}
                        title={t('tts.testListen', '试听发音测试')}
                      >
                        <Icon name="play" /> {isTesting ? t('tts.testing', '合成中...') : t('tts.test', '试听')}
                      </button>
                      {onSelectTts && !isSelected && (
                        <button
                          type="button"
                          className="secondary-button"
                          style={{ padding: '5px 10px', fontSize: '12px' }}
                          onClick={() => onSelectTts(tts)}
                        >
                          {t('tts.selectUse', '使用')}
                        </button>
                      )}
                      <button
                        type="button"
                        className="secondary-button"
                        style={{ padding: '5px 8px' }}
                        onClick={() => {
                          setEditingTts({ ...tts })
                          setIsNew(false)
                        }}
                        title={t('common.edit', '编辑')}
                      >
                        <Icon name="edit" />
                      </button>
                      <button
                        type="button"
                        className="secondary-button"
                        style={{ padding: '5px 8px' }}
                        onClick={() => handleExportSingle(tts)}
                        title={t('common.export', '导出')}
                      >
                        <Icon name="download" />
                      </button>
                      <button
                        type="button"
                        className="secondary-button"
                        style={{ padding: '5px 8px', color: 'var(--danger, #ef4444)' }}
                        onClick={() => handleDelete(tts)}
                        title={t('common.delete', '删除')}
                      >
                        <Icon name="trash" />
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>

        <div style={{ padding: '12px 18px', display: 'flex', justifyContent: 'flex-end', borderTop: '1px solid var(--line)' }}>
          <button type="button" className="primary-button" onClick={onClose}>
            {t('common.done', '完成')}
          </button>
        </div>
      </div>

      {/* Editor Modal */}
      {editingTts && (
        <HttpTtsEditDialog
          tts={editingTts}
          isNew={isNew}
          onSave={async updated => {
            try {
              const saved = await api.saveHttpTts(updated)
              showToast(t('common.saved', '已保存'), 'success')
              setEditingTts(null)
              loadList()
              if (onSelectTts && isNew) {
                onSelectTts(saved)
              }
            } catch (err: unknown) {
              const msg = err instanceof Error ? err.message : String(err)
              showToast(msg, 'error')
            }
          }}
          onTest={async tts => {
            await handleTestTts(tts)
          }}
          onClose={() => setEditingTts(null)}
        />
      )}

      {/* Import Modal */}
      {importModalOpen && (
        <div className="modal-backdrop" onClick={() => setImportModalOpen(false)}>
          <div
            className="tts-settings-dialog"
            onClick={e => e.stopPropagation()}
            style={{ width: 'min(500px, 94vw)', padding: '0' }}
          >
            <header className="source-login-header">
              <h3 className="source-login-title">
                <Icon name="cloudDownload" /> {t('tts.importTitle', '导入 httpTTS 规则')}
              </h3>
              <button type="button" className="source-login-action-btn" onClick={() => setImportModalOpen(false)}>
                ✕
              </button>
            </header>
            <div style={{ padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label className="login-ui-field-label" style={{ marginBottom: '6px' }}>
                  {t('tts.uploadFilePrompt', '方式一：选择本地 JSON 文件')}
                </label>
                <input
                  type="file"
                  ref={fileInputRef}
                  accept=".json,application/json"
                  style={{ display: 'none' }}
                  onChange={handleFileUpload}
                />
                <button
                  type="button"
                  className="secondary-button"
                  style={{ display: 'inline-flex', alignItems: 'center', gap: '6px', width: '100%', justifyContent: 'center', padding: '8px' }}
                  onClick={() => fileInputRef.current?.click()}
                >
                  <Icon name="upload" /> {t('tts.chooseLocalFile', '从文件选择 httpTTS.json')}
                </button>
              </div>

              <div>
                <label className="login-ui-field-label" style={{ marginBottom: '6px' }}>
                  {t('tts.pastePrompt', '方式二：直接粘贴 JSON 内容')}
                </label>
                <textarea
                  className="source-login-textarea"
                  rows={8}
                  placeholder='[{"name": "百度语音", "url": "http://tts.baidu.com/..."}]'
                  value={importText}
                  onChange={e => setImportText(e.target.value)}
                />
              </div>
            </div>
            <div
              style={{
                padding: '12px 18px',
                display: 'flex',
                justifyContent: 'flex-end',
                gap: '8px',
                borderTop: '1px solid var(--line)',
              }}
            >
              <button type="button" className="secondary-button" onClick={() => setImportModalOpen(false)}>
                {t('common.cancel', '取消')}
              </button>
              <button
                type="button"
                className="primary-button"
                disabled={!importText.trim()}
                onClick={() => handleImportJsonText(importText)}
              >
                {t('common.confirm', '确认导入')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

interface HttpTtsEditDialogProps {
  tts: HttpTts
  isNew: boolean
  onSave: (tts: HttpTts) => void
  onTest: (tts: HttpTts) => void
  onClose: () => void
}

const HttpTtsEditDialog: React.FC<HttpTtsEditDialogProps> = ({
  tts: initialTts,
  isNew,
  onSave,
  onTest,
  onClose,
}) => {
  const { t } = useTranslation()
  const [name, setName] = useState(initialTts.name)
  const [url, setUrl] = useState(initialTts.url)
  const [header, setHeader] = useState(initialTts.header || '')
  const [contentType, setContentType] = useState(initialTts.contentType || 'audio/mpeg')
  const [concurrentRate, setConcurrentRate] = useState(initialTts.concurrentRate || '0')

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!name.trim()) {
      alert(t('tts.nameRequired', '请输入引擎名称'))
      return
    }
    if (!url.trim()) {
      alert(t('tts.urlRequired', '请输入请求 URL'))
      return
    }
    onSave({
      ...initialTts,
      name: name.trim(),
      url: url.trim(),
      header: header.trim() ? header.trim() : null,
      contentType: contentType.trim() ? contentType.trim() : null,
      concurrentRate: concurrentRate.trim() ? concurrentRate.trim() : null,
      lastUpdateTime: Date.now(),
    })
  }

  const currentSnapshot: HttpTts = {
    ...initialTts,
    name: name.trim() || '测试引擎',
    url: url.trim(),
    header: header.trim() ? header.trim() : null,
    contentType: contentType.trim() ? contentType.trim() : null,
    concurrentRate: concurrentRate.trim() ? concurrentRate.trim() : null,
  }

  return (
    <div className="modal-backdrop" onClick={onClose} style={{ zIndex: 1100 }}>
      <div
        className="tts-settings-dialog"
        onClick={e => e.stopPropagation()}
        style={{ width: 'min(580px, 94vw)', maxHeight: '90vh' }}
      >
        <header className="source-login-header">
          <h3 className="source-login-title">
            <Icon name="edit" /> {isNew ? t('tts.newEngineTitle', '新建 HTTP 朗读引擎') : t('tts.editEngineTitle', '编辑 HTTP 朗读引擎')}
          </h3>
          <button type="button" className="source-login-action-btn" onClick={onClose}>
            ✕
          </button>
        </header>

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
          <div className="source-login-body" style={{ flex: 1, overflowY: 'auto', padding: '16px 20px', display: 'flex', flexDirection: 'column', gap: '14px' }}>
            <div className="login-ui-item-field">
              <label className="login-ui-field-label">{t('tts.engineName', '引擎名称 *')}</label>
              <input
                type="text"
                className="login-ui-input"
                placeholder={t('tts.engineNamePlaceholder', '例如：百度语音 / 阿里云TTS')}
                value={name}
                onChange={e => setName(e.target.value)}
                required
              />
            </div>

            <div className="login-ui-item-field">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <label className="login-ui-field-label">{t('tts.requestUrl', '请求 URL (支持 Legado 语法) *')}</label>
              </div>
              <p className="tts-hint" style={{ margin: '4px 0 6px 0', fontSize: '11px', color: 'var(--muted)' }}>
                {t('tts.urlHint', '支持 url,{options} 语法及宏变量：')}&nbsp;
                <code>&#123;&#123;speakText&#125;&#125;</code>, <code>&#123;&#123;speakSpeed&#125;&#125;</code>, <code>&#123;&#123;java.encodeURI(...)&#125;&#125;</code>
              </p>
              <textarea
                className="source-login-textarea"
                rows={4}
                placeholder="http://tts.example.com/api,&#10;{&quot;method&quot;: &quot;POST&quot;, &quot;body&quot;: &quot;text={{speakText}}&quot;}"
                value={url}
                onChange={e => setUrl(e.target.value)}
                required
              />
            </div>

            <div className="login-ui-item-field">
              <label className="login-ui-field-label">{t('tts.customHeaderLabel', '请求头 (Header JSON, 可选)')}</label>
              <textarea
                className="source-login-textarea"
                rows={2}
                placeholder='{"User-Agent": "okhttp/4.9.2"}'
                value={header}
                onChange={e => setHeader(e.target.value)}
              />
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
              <div className="login-ui-item-field">
                <label className="login-ui-field-label">{t('tts.contentTypeLabel', '音频类型 (Content-Type)')}</label>
                <input
                  type="text"
                  className="login-ui-input"
                  placeholder="audio/mpeg 或 audio/wav"
                  value={contentType}
                  onChange={e => setContentType(e.target.value)}
                />
              </div>

              <div className="login-ui-item-field">
                <label className="login-ui-field-label">{t('tts.concurrentRateLabel', '并发频率限制')}</label>
                <input
                  type="text"
                  className="login-ui-input"
                  placeholder="0 (不限制)"
                  value={concurrentRate}
                  onChange={e => setConcurrentRate(e.target.value)}
                />
              </div>
            </div>
          </div>

          <div
            style={{
              padding: '12px 18px',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              borderTop: '1px solid var(--line)',
            }}
          >
            <button
              type="button"
              className="secondary-button"
              style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}
              onClick={() => onTest(currentSnapshot)}
            >
              <Icon name="play" /> {t('tts.testVoice', '测试发音')}
            </button>
            <div style={{ display: 'flex', gap: '8px' }}>
              <button type="button" className="secondary-button" onClick={onClose}>
                {t('common.cancel', '取消')}
              </button>
              <button type="submit" className="primary-button">
                {t('common.save', '保存')}
              </button>
            </div>
          </div>
        </form>
      </div>
    </div>
  )
}
