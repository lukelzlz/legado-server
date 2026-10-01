import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, type ImportResponse, type NetworkImportPreview } from './api'
import { Icon } from './icons'

/** 「不分组」用的哨兵：与真实分组名区分开。 */
const NO_GROUP = ''
/** 「新建分组…」用的哨兵。 */
const NEW_GROUP = '\u0000new'

interface NetworkImportModalProps {
  /**
   * 预填地址。内置浏览器里点书源「更新书源」页的线路时由宿主传入，给了就自动拉取。
   * 书源页直接点「网络导入」时不传，由用户自己填。
   */
  initialUrl?: string
  /**
   * **已经拿到的预览**（本地文件导入用）。
   *
   * 本地 JSON 由前端读出文件原文、交给服务端同一套解析器算出预览后再打开本弹窗，
   * 因此传了这个就**不再发起拉取**，地址行也退化为只读的文件名展示。
   * 票据由预览接口签发，确认时走的仍是同一条 commit 路径。
   */
  initialPreview?: NetworkImportPreview | null
  /** 本地模式下载体名称（文件名），仅用于展示。 */
  localLabel?: string
  /** 可选的目标分组候选（书源页已有的分组）。 */
  groups?: string[]
  onClose: () => void
  /** 导入成功回调（父级据此刷新书源列表）。 */
  onImported?: (result: ImportResponse) => void
  onToast: (message: string, type?: 'info' | 'success' | 'error') => void
}

/**
 * 网络书源导入弹窗（书源页与内置浏览器**共用同一个组件**）。
 *
 * 布局对齐 Legado 手机端的「导入书源」面板：标题 + 自定义源分组 + ⋮ 菜单；
 * 每行 = 勾选框 + 书源名 + 状态标签（新增 / 更新 / 不可导入）+ 圆形详情按钮；
 * 底部 = 「取消全选（n/N）」+ 取消 / 确认。
 *
 * ⚠️ 调用方必须把它挂成**平级分支**，严禁嵌在另一个 `.modal-backdrop` 内部
 * （仓库既有教训：带 `animation`/`transform` 的遮罩会形成层叠上下文，导致幽灵穿透）。
 */
export const NetworkImportModal: React.FC<NetworkImportModalProps> = ({
  initialUrl,
  initialPreview = null,
  localLabel,
  groups = [],
  onClose,
  onImported,
  onToast,
}) => {
  const { t } = useTranslation()
  /** 本地文件模式：预览已经由预览接口算好，本弹窗只负责挑选与确认。 */
  const localMode = initialPreview !== null
  const [url, setUrl] = useState(initialUrl ?? '')
  const [fetching, setFetching] = useState(false)
  const [committing, setCommitting] = useState(false)
  const [preview, setPreview] = useState<NetworkImportPreview | null>(initialPreview)
  const [selected, setSelected] = useState<Set<number>>(
    // 默认全选「可导入」的条目（与手机端一致）；本地模式一进来就按同一规则选好
    () => new Set((initialPreview?.sources ?? []).filter(item => item.status !== 'invalid').map(item => item.index)),
  )
  const [detailIndex, setDetailIndex] = useState<number | null>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const [groupOpen, setGroupOpen] = useState(false)
  const [groupChoice, setGroupChoice] = useState<string>(NO_GROUP)
  const [newGroupName, setNewGroupName] = useState('')
  /** 已自动拉取过，避免父组件重渲染时反复请求。 */
  const autoFetchedRef = useRef(false)

  const effectiveGroup = groupChoice === NEW_GROUP ? newGroupName.trim() : groupChoice

  const runPreview = useCallback(async (target: string) => {
    const value = target.trim()
    if (!value) {
      onToast(t('source.networkImportUrlRequired', '请填写书源地址'), 'info')
      return
    }
    setFetching(true)
    try {
      const result = await api.previewNetworkImport(value)
      setPreview(result)
      // 默认全选「可导入」的条目（与手机端一致）
      setSelected(new Set(result.sources.filter(item => item.status !== 'invalid').map(item => item.index)))
      setDetailIndex(null)
    } catch (error) {
      setPreview(null)
      setSelected(new Set())
      onToast(error instanceof Error ? error.message : t('source.networkImportFailed', '拉取失败'), 'error')
    } finally {
      setFetching(false)
    }
  }, [onToast, t])

  // 从内置浏览器线路进来时自动拉取一次（**本地文件模式不拉取**：预览已经给了）
  useEffect(() => {
    if (localMode) return
    if (autoFetchedRef.current) return
    if (!initialUrl || !initialUrl.trim()) return
    autoFetchedRef.current = true
    void runPreview(initialUrl)
  }, [initialUrl, localMode, runPreview])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      // 本弹窗可能被挂在**外层弹窗的遮罩内部**（例如内置浏览器里点「更新书源」线路唤出），
      // 因此必须把 Escape 就地吃掉，避免继续传播给外层监听。
      //
      // ⚠️ 注意 `stopImmediatePropagation()` 只能拦住**注册更晚**的监听器：同一个 target 上
      // 的监听器按注册顺序触发，外层的 useEffect 先跑 ⇒ 它的监听器先注册。
      // 真正防住连带关闭的是外层不注册 Escape（现状如此，实测已验证），这里只是兜底。
      event.stopImmediatePropagation()
      onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  const selectable = useMemo(
    () => (preview?.sources ?? []).filter(item => item.status !== 'invalid'),
    [preview],
  )

  const toggleOne = (index: number) => {
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(index)) next.delete(index)
      else next.add(index)
      return next
    })
  }

  const selectAll = () => setSelected(new Set(selectable.map(item => item.index)))
  const clearAll = () => setSelected(new Set())
  const invert = () => setSelected(prev => new Set(selectable.map(i => i.index).filter(index => !prev.has(index))))

  const handleConfirm = async () => {
    if (!preview) return
    const indexes = [...selected].sort((a, b) => a - b)
    if (indexes.length === 0) {
      onToast(t('source.networkImportEmptySelection', '请先勾选要导入的书源'), 'info')
      return
    }
    setCommitting(true)
    try {
      const result = await api.commitNetworkImport(preview.token, indexes, effectiveGroup || null)
      onToast(
        t('source.networkImportDone', {
          imported: result.imported,
          updated: result.updated,
          defaultValue: `导入完成：新增 ${result.imported} 个，更新 ${result.updated} 个`,
        }),
        'success',
      )
      onImported?.(result)
      onClose()
    } catch (error) {
      onToast(error instanceof Error ? error.message : t('source.networkImportCommitFailed', '导入失败'), 'error')
    } finally {
      setCommitting(false)
    }
  }

  const groupLabel = effectiveGroup || t('source.networkImportNoGroup', '不分组')

  return (
    // ⚠️ `stopPropagation` 不是可选项：本弹窗被挂成**外层弹窗遮罩的 DOM 子节点**
    // （SourceLoginModal 里 `<div className="modal-backdrop" onClick={onClose}>` → 本弹窗），
    // 不阻止冒泡的话，点一下本弹窗的遮罩会连带把外层登录弹窗与内置浏览器**一起关掉**。
    // 实测复现：点击前 login/webview/network 三层都在，点击后三层的遮罩数从 3 变 0。
    <div className="modal-backdrop" onClick={event => { event.stopPropagation(); onClose() }}>
      <div className="network-import-modal" onClick={event => event.stopPropagation()}>
        <header className="network-import-header">
          <h3 className="network-import-title">{t('source.networkImportTitle', '导入书源')}</h3>
          <div className="network-import-header-actions">
            <button
              type="button"
              className="network-import-group-btn"
              title={t('source.networkImportCustomGroupHint', '选择本次导入的目标分组')}
              onClick={() => { setGroupOpen(open => !open); setMenuOpen(false) }}
            >
              {t('source.networkImportCustomGroup', '自定义源分组')}
              {effectiveGroup ? <span className="network-import-group-current">{groupLabel}</span> : null}
            </button>
            <button
              type="button"
              className="network-import-more-btn"
              aria-label={t('common.more', '更多')}
              onClick={() => { setMenuOpen(open => !open); setGroupOpen(false) }}
            >
              <Icon name="more" />
            </button>
          </div>
        </header>

        {groupOpen && (
          <div className="network-import-popover">
            <select
              className="network-import-group-select"
              value={groupChoice}
              onChange={event => setGroupChoice(event.target.value)}
            >
              <option value={NO_GROUP}>{t('source.networkImportNoGroup', '不分组')}</option>
              {groups.filter(Boolean).map(name => <option key={name} value={name}>{name}</option>)}
              <option value={NEW_GROUP}>{t('source.networkImportNewGroup', '新建分组…')}</option>
            </select>
            {groupChoice === NEW_GROUP && (
              <input
                className="network-import-group-input"
                placeholder={t('source.networkImportGroupNamePlaceholder', '输入新分组名')}
                value={newGroupName}
                onChange={event => setNewGroupName(event.target.value)}
              />
            )}
            <p className="network-import-popover-hint">
              {t('source.networkImportGroupHint', '不选则保持原分组不变，新书源落「未分组」。')}
            </p>
          </div>
        )}

        {menuOpen && (
          <div className="network-import-popover network-import-menu">
            <button type="button" onClick={() => { selectAll(); setMenuOpen(false) }}>{t('source.networkImportSelectAll', '全选')}</button>
            <button type="button" onClick={() => { clearAll(); setMenuOpen(false) }}>{t('source.networkImportDeselectAll', '取消全选')}</button>
            <button type="button" onClick={() => { invert(); setMenuOpen(false) }}>{t('source.networkImportInvert', '反选')}</button>
          </div>
        )}

        <div className="network-import-body">
          {localMode ? (
            // 本地文件模式：没有地址可填，这一行退化为「来源文件名」的只读展示，
            // 让用户一眼看出这批条目是从哪个文件读出来的。
            <div className="network-import-url-row">
              <input
                className="network-import-url-input"
                value={localLabel ?? t('source.localImportLabel', '本地文件')}
                readOnly
                disabled
                aria-label={t('source.localImportLabel', '本地文件')}
              />
            </div>
          ) : (
            <div className="network-import-url-row">
              <input
                className="network-import-url-input"
                placeholder="https://example.com/book-sources.json"
                value={url}
                onChange={event => { setUrl(event.target.value); setPreview(null); setSelected(new Set()) }}
                onKeyDown={event => { if (event.key === 'Enter') void runPreview(url) }}
              />
              <button type="button" className="network-import-fetch-btn" disabled={fetching} onClick={() => void runPreview(url)}>
                {fetching ? t('source.networkImportFetching', '拉取中…') : t('source.networkImportFetch', '拉取')}
              </button>
            </div>
          )}

          {preview && (
            <>
              <p className="network-import-summary">
                {t('source.networkImportSummary', {
                  total: preview.total,
                  newCount: preview.newCount,
                  updateCount: preview.updateCount,
                  invalidCount: preview.invalidCount,
                  defaultValue: `共 ${preview.total} 条：新增 ${preview.newCount}，更新 ${preview.updateCount}，不可导入 ${preview.invalidCount}`,
                })}
              </p>
              <div className="network-import-list">
                {preview.sources.map(item => {
                  const invalid = item.status === 'invalid'
                  const checked = selected.has(item.index)
                  return (
                    <div key={item.index} className={`network-import-row ${invalid ? 'is-invalid' : ''}`}>
                      <label className="network-import-row-main">
                        <input
                          type="checkbox"
                          className="network-import-check"
                          checked={checked}
                          disabled={invalid}
                          onChange={() => toggleOne(item.index)}
                        />
                        <span className="network-import-name" title={item.name}>{item.name}</span>
                      </label>
                      <span className={`network-import-badge is-${item.status}`}>
                        {invalid
                          ? t('source.networkImportBadgeInvalid', '不可导入')
                          : item.status === 'update'
                            ? t('source.networkImportBadgeUpdate', '更新')
                            : t('source.networkImportBadgeNew', '新增')}
                      </span>
                      {invalid ? (
                        <span className="network-import-reason" title={item.reason ?? undefined}>{item.reason}</span>
                      ) : (
                        <button
                          type="button"
                          className="network-import-detail-btn"
                          title={t('source.networkImportDetail', '查看书源详情')}
                          aria-label={t('source.networkImportDetail', '查看书源详情')}
                          onClick={() => setDetailIndex(detailIndex === item.index ? null : item.index)}
                        >
                          <Icon name="edit" />
                        </button>
                      )}
                      {detailIndex === item.index && !invalid && (
                        <div className="network-import-detail">
                          <span>{t('source.networkImportFieldUrl', '地址')}：{item.url || '—'}</span>
                        </div>
                      )}
                    </div>
                  )
                })}
              </div>
              <button type="button" className="network-import-selectall-pill" onClick={clearAll}>
                {t('source.networkImportDeselectAllCount', {
                  selected: selected.size,
                  total: selectable.length,
                  defaultValue: `取消全选（${selected.size}/${selectable.length}）`,
                })}
              </button>
            </>
          )}
        </div>

        <footer className="network-import-footer">
          <button type="button" className="network-import-cancel-btn" onClick={onClose}>
            {t('common.cancel', '取消')}
          </button>
          <button
            type="button"
            className="network-import-confirm-btn"
            disabled={!preview || committing || fetching}
            onClick={() => void handleConfirm()}
          >
            {committing ? t('common.loading', '处理中…') : t('source.networkImportConfirm', '确认')}
          </button>
        </footer>
      </div>
    </div>
  )
}
