import { useEffect, useMemo, useState } from 'react'
import { api } from './api'
import { Icon } from './icons'
import { PluginIcon } from './PluginUi'
import type { HostMenuItem, HostNavItem, HostPluginPage } from './pluginHost'
import type { PluginManifestSummary, PluginSettingField } from './pluginSdk'
import { settingsSchemaOf } from './pluginSdk'
import { toast } from './Toast'

/**
 * 插件管理页（宿主内置页面 `plugins`）。
 *
 * 列出所有插件并支持重载、启用/停用、按 settingsSchema 动态编辑设置，
 * 同时展示每个插件注册到宿主的导航项、菜单项与页面。
 */

export type PluginsPageProps = {
  plugins: PluginManifestSummary[]
  navItems: HostNavItem[]
  menuItems: HostMenuItem[]
  pages: HostPluginPage[]
  loadErrors: Record<string, string>
  onReload: () => Promise<void>
  onEnable: (id: string) => Promise<void>
  onDisable: (id: string) => Promise<void>
  onNavigate: (page: string) => void
}

const RUNTIME_LABELS: Record<string, string> = { js: 'JS 插件', jar: 'JAR 插件', 'js+jar': 'JS + JAR' }

const PERMISSION_LABELS: Record<string, string> = {
  storage: '插件存储',
  settings: '插件设置',
  'books.read': '读取书架',
  'books.write': '修改书架',
  'books.network': '书源联网',
  'sources.read': '读取书源',
  'sources.write': '修改书源',
  'subscriptions.read': '读取订阅',
  'subscriptions.write': '修改订阅',
  covers: '封面缓存',
  http: '外网请求',
  'http.private': '内网请求',
  auth: '管理员校验',
  events: '事件总线',
  schedule: '定时任务',
  'routes.public': '公开路由',
}

const runtimeLabel = (runtime: string) => RUNTIME_LABELS[runtime] ?? runtime
const permissionLabel = (permission: string) => PERMISSION_LABELS[permission] ?? permission
const errorOf = (error: unknown, fallback: string) => error instanceof Error && error.message ? error.message : fallback

/** 按 settingsSchema 把插件当前设置值渲染成表单 */
function PluginSettingsModal({ plugin, onClose }: { plugin: PluginManifestSummary; onClose: () => void }) {
  const [values, setValues] = useState<Record<string, unknown>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const schema = settingsSchemaOf(plugin)

  useEffect(() => {
    let active = true
    const defaults: Record<string, unknown> = {}
    for (const field of schema) {
      if (field.default !== undefined) defaults[field.key] = field.default
    }
    void api.pluginSettings(plugin.id).then(current => {
      if (active) setValues({ ...defaults, ...current })
    }).catch(cause => {
      if (active) {
        setValues(defaults)
        setError(errorOf(cause, '无法读取插件设置'))
      }
    }).finally(() => {
      if (active) setLoading(false)
    })
    return () => { active = false }
  }, [plugin.id, schema])

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  const setValue = (key: string, value: unknown) => setValues(current => ({ ...current, [key]: value }))

  const save = async () => {
    setSaving(true)
    setError('')
    try {
      await api.updatePluginSettings(plugin.id, values)
      toast.success(`插件「${plugin.name}」设置已保存`)
      onClose()
    } catch (cause) {
      setError(errorOf(cause, '保存插件设置失败'))
    } finally {
      setSaving(false)
    }
  }

  const renderField = (field: PluginSettingField) => {
    const value = values[field.key]
    if (field.type === 'boolean') {
      return (
        <label className="plugin-switch" key={field.key}>
          <input
            type="checkbox"
            checked={value === true}
            onChange={event => setValue(field.key, event.target.checked)}
          />
          <span className="plugin-switch-track" aria-hidden="true" />
          <span className="plugin-switch-label">{field.label}</span>
          {field.hint && <small className="plugin-field-hint">{field.hint}</small>}
        </label>
      )
    }
    const control = field.type === 'select' ? (
      <select value={typeof value === 'string' ? value : String(value ?? '')} onChange={event => setValue(field.key, event.target.value)}>
        {(field.options ?? []).map(option => (
          <option key={option.value} value={option.value}>{option.label}</option>
        ))}
      </select>
    ) : field.type === 'textarea' ? (
      <textarea
        rows={3}
        value={typeof value === 'string' ? value : value === undefined || value === null ? '' : String(value)}
        onChange={event => setValue(field.key, event.target.value)}
      />
    ) : field.type === 'number' ? (
      <input
        type="number"
        value={typeof value === 'number' || typeof value === 'string' ? String(value) : ''}
        onChange={event => setValue(field.key, event.target.value === '' ? '' : Number(event.target.value))}
      />
    ) : (
      <input
        type={field.type === 'password' ? 'password' : 'text'}
        autoComplete={field.type === 'password' ? 'new-password' : 'off'}
        value={typeof value === 'string' ? value : value === undefined || value === null ? '' : String(value)}
        onChange={event => setValue(field.key, event.target.value)}
      />
    )
    return (
      <label className="plugin-field" key={field.key}>
        <span className="plugin-field-label">{field.label}<code>{field.key}</code></span>
        {control}
        {field.hint && <small className="plugin-field-hint">{field.hint}</small>}
      </label>
    )
  }

  return (
    <div className="modal-backdrop top-layer-modal-backdrop" onClick={onClose}>
      <div className="plugin-modal" role="dialog" aria-modal="true" aria-label={`插件设置: ${plugin.name}`} onClick={event => event.stopPropagation()}>
        <header className="plugin-modal-header">
          <div>
            <span className="section-kicker">插件设置</span>
            <h2>{plugin.name}</h2>
          </div>
          <button type="button" className="subtle-button close-btn" onClick={onClose} aria-label="关闭">
            <Icon name="close" />
          </button>
        </header>
        <div className="plugin-modal-body">
          {loading ? (
            <p className="plugin-muted">正在读取设置...</p>
          ) : schema.length === 0 ? (
            <p className="plugin-muted">该插件没有声明任何设置项。</p>
          ) : (
            <div className="plugin-settings-form">{schema.map(renderField)}</div>
          )}
          {error && <p className="form-error">{error}</p>}
        </div>
        <footer className="plugin-modal-footer">
          <button type="button" className="subtle-button" onClick={onClose} disabled={saving}>取消</button>
          <button type="button" className="primary-button" onClick={() => void save()} disabled={saving || loading}>
            {saving ? '保存中...' : '保存设置'}
          </button>
        </footer>
      </div>
    </div>
  )
}

export function PluginsPage({
  plugins,
  navItems,
  menuItems,
  pages,
  loadErrors,
  onReload,
  onEnable,
  onDisable,
  onNavigate,
}: PluginsPageProps) {
  const [busy, setBusy] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [settingsTarget, setSettingsTarget] = useState<PluginManifestSummary | null>(null)

  const enabledCount = useMemo(() => plugins.filter(plugin => plugin.enabled).length, [plugins])

  const runReload = async () => {
    setBusy('all')
    try {
      await onReload()
    } finally {
      setBusy(null)
    }
  }

  const togglePlugin = async (plugin: PluginManifestSummary) => {
    setBusy(plugin.id)
    try {
      if (plugin.enabled) {
        await onDisable(plugin.id)
        toast.info(`插件「${plugin.name}」已停用`)
      } else {
        await onEnable(plugin.id)
      }
    } finally {
      setBusy(null)
    }
  }

  return (
    <main className="plugins-page">
      <header className="page-title">
        <div>
          <span className="section-kicker">扩展能力</span>
          <h1>插件管理</h1>
          <p>插件可以扩展服务端接口与前端页面；这里负责重载、启停与设置。</p>
        </div>
        <div className="plugin-page-actions">
          <small className="plugin-muted">共 {plugins.length} 个插件，已启用 {enabledCount} 个</small>
          <button type="button" className="subtle-button" onClick={() => void runReload()} disabled={busy !== null}>
            <Icon name="refresh" className={busy === 'all' ? 'spin' : ''} />
            <span>{busy === 'all' ? '重载中...' : '重载插件'}</span>
          </button>
        </div>
      </header>

      {loadErrors['*'] && <p className="form-error">{loadErrors['*']}</p>}

      {plugins.length === 0 ? (
        <section className="plugin-empty">
          <Icon name="settings" />
          <p>还没有安装任何插件。</p>
          <small>把插件目录放到服务端的 plugins 目录后点击「重载插件」即可识别。</small>
        </section>
      ) : (
        <section className="plugin-list">
          {plugins.map(plugin => {
            const pluginNavs = navItems.filter(item => item.pluginId === plugin.id)
            const pluginPages = pages.filter(page => page.pluginId === plugin.id)
            const pluginMenus = menuItems.filter(item => item.pluginId === plugin.id)
            const runtimeError = loadErrors[plugin.id]
            const expanded = expandedId === plugin.id
            const schemaCount = settingsSchemaOf(plugin).length
            return (
              <article key={plugin.id} className={`plugin-item ${plugin.enabled ? '' : 'disabled'} ${runtimeError || plugin.error ? 'has-error' : ''}`}>
                <header className="plugin-item-head">
                  <div className="plugin-title-row">
                    <button
                      type="button"
                      className="plugin-title-btn"
                      aria-expanded={expanded}
                      title="查看该插件注册的导航项、菜单项与页面"
                      onClick={() => setExpandedId(expanded ? null : plugin.id)}
                    >
                      <strong>{plugin.name}</strong>
                      <span className="plugin-version">v{plugin.version}</span>
                      <span className={`plugin-badge ${plugin.enabled ? 'badge-enabled' : 'badge-disabled'}`}>
                        {plugin.enabled ? '已启用' : '已停用'}
                      </span>
                      <span className="plugin-badge badge-runtime" title={`apiVersion ${plugin.apiVersion}`}>{runtimeLabel(plugin.runtime)}</span>
                      {plugin.hasServer && <span className="plugin-badge">服务端</span>}
                      {plugin.hasWeb && <span className="plugin-badge">前端页面</span>}
                      {plugin.loaded && <span className="plugin-badge badge-loaded">已加载</span>}
                    </button>
                  </div>
                  <div className="plugin-item-actions">
                    <button type="button" className="subtle-button plugin-btn-sm" onClick={() => setExpandedId(expanded ? null : plugin.id)}>
                      {expanded ? '收起注册项' : `注册项 ${pluginNavs.length + pluginPages.length + pluginMenus.length}`}
                    </button>
                    <button type="button" className="subtle-button plugin-btn-sm" onClick={() => setSettingsTarget(plugin)}>
                      设置{schemaCount > 0 ? ` (${schemaCount})` : ''}
                    </button>
                    <button
                      type="button"
                      className={`plugin-btn-sm ${plugin.enabled ? 'danger-button' : 'primary-button'}`}
                      onClick={() => void togglePlugin(plugin)}
                      disabled={busy !== null}
                    >
                      {busy === plugin.id ? '处理中...' : plugin.enabled ? '停用' : '启用'}
                    </button>
                  </div>
                </header>

                <div className="plugin-meta">
                  <span>作者：{plugin.author || '未署名'}</span>
                  <span>ID：{plugin.id}</span>
                  <span>API：v{plugin.apiVersion}</span>
                </div>
                {plugin.description && <p className="plugin-desc">{plugin.description}</p>}
                <p className="plugin-dir" title={plugin.directory}>目录：{plugin.directory}</p>

                {plugin.permissions.length > 0 && (
                  <div className="plugin-tags">
                    {plugin.permissions.map(permission => (
                      <span key={permission} className="plugin-tag plugin-tag-perm" title={permission}>
                        {permissionLabel(permission)}
                      </span>
                    ))}
                  </div>
                )}

                {plugin.error && <p className="plugin-error-text">服务端错误：{plugin.error}</p>}
                {runtimeError && <p className="plugin-error-text">前端加载失败：{runtimeError}</p>}

                {expanded && (
                  <div className="plugin-registrations">
                    <div className="plugin-reg-block">
                      <span className="plugin-reg-label">导航项</span>
                      {pluginNavs.length === 0
                        ? <small className="plugin-muted">未注册导航项</small>
                        : pluginNavs.map(item => (
                          <span key={item.id} className="plugin-reg-item">
                            <code>{item.label} → {item.target}</code>
                          </span>
                        ))}
                    </div>
                    <div className="plugin-reg-block">
                      <span className="plugin-reg-label">页面</span>
                      {pluginPages.length === 0
                        ? <small className="plugin-muted">未注册页面</small>
                        : pluginPages.map(page => (
                          <span key={page.key} className="plugin-reg-item">
                            <PluginIcon name={page.icon} />
                            <code>{page.title}（{page.key}）</code>
                            <button type="button" className="subtle-button plugin-btn-sm" onClick={() => onNavigate(page.key)}>打开</button>
                          </span>
                        ))}
                    </div>
                    <div className="plugin-reg-block">
                      <span className="plugin-reg-label">菜单项</span>
                      {pluginMenus.length === 0
                        ? <small className="plugin-muted">未注册菜单项</small>
                        : pluginMenus.map(item => (
                          <span key={item.id} className="plugin-reg-item">
                            <code>{item.label}</code>
                          </span>
                        ))}
                    </div>
                  </div>
                )}
              </article>
            )
          })}
        </section>
      )}

      {settingsTarget && (
        <PluginSettingsModal plugin={settingsTarget} onClose={() => setSettingsTarget(null)} />
      )}
    </main>
  )
}
