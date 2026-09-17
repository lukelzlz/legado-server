import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, joinWebDavPath, webDavFileUrl, WebDavInfo } from './api'
import { toast } from './Toast'
import { Icon } from './icons'

/** 客户端接入指引：按当前访问来源拼出可复制的连接信息。 */
export function webDavClientGuides(origin: string, urlPath: string) {
  const url = `${origin}${urlPath}`
  return [
    {
      id: 'windows',
      title: 'Windows 资源管理器',
      steps: '「此电脑」→ 右键 → 映射网络驱动器 → 粘贴下面的地址 → 使用其他凭据连接（用户名任意填，密码为登录密码）',
      command: url,
      copyLabel: '复制地址',
    },
    {
      id: 'macos',
      title: 'macOS Finder',
      steps: 'Finder → 前往 → 连接服务器（⌘K）→ 粘贴下面的地址 → 注册用户（用户名任意填，密码为登录密码）',
      command: url,
      copyLabel: '复制地址',
    },
    {
      id: 'rclone',
      title: 'rclone / 命令行',
      steps: '先配置远端（密码用 rclone obscure 生成），随后即可像本地目录一样拷贝文件',
      command: `rclone config create legado webdav url=${url} vendor=other user=legado pass=$(rclone obscure '你的登录密码')\nrclone copy ./some-book.txt legado:books/`,
      copyLabel: '复制命令',
    },
    {
      id: 'legado',
      title: 'Legado App 备份',
      steps: '在 App 的「备份与恢复 / WebDAV」中填入下面的地址，账号任意填，密码为当前登录密码，备份文件会落到数据目录的 webdav 文件夹',
      command: url,
      copyLabel: '复制地址',
    },
  ]
}

/** 字节数易读化（服务端返回的是精确字节）。 */
export function formatDavSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${unit === 0 ? value : value.toFixed(value >= 100 ? 0 : 1)} ${units[unit]}`
}

/** 修改时间：近期显示相对时间，超过 7 天显示日期。 */
export function formatDavTime(timestamp: number, now = Date.now()): string {
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '-'
  const diff = now - timestamp
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  if (diff < 7 * 86_400_000) return `${Math.floor(diff / 86_400_000)} 天前`
  const date = new Date(timestamp)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** 面包屑：根目录 + 逐级路径。 */
export function davBreadcrumbs(path: string): Array<{ name: string; path: string }> {
  const segments = path.split('/').filter(Boolean)
  const crumbs: Array<{ name: string; path: string }> = [{ name: '根目录', path: '' }]
  segments.forEach((segment, index) => {
    crumbs.push({ name: segment, path: segments.slice(0, index + 1).join('/') })
  })
  return crumbs
}

/** 当前访问来源（服务端渲染 / 测试环境下退化为空串）。 */
export const currentOrigin = () => (typeof location === 'undefined' ? '' : location.origin)

async function copyText(text: string, successMessage: string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.success(successMessage)
  } catch {
    toast.warning('当前环境不支持剪贴板，请手动复制')
  }
}

export function WebDavSettingsPage() {
  const [info, setInfo] = useState<WebDavInfo | null>(null)
  const [path, setPath] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const uploadInputRef = useRef<HTMLInputElement>(null)

  const load = useCallback(async (target: string) => {
    setLoading(true)
    try {
      setInfo(await api.webDavInfo(target))
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '读取 WebDAV 状态失败')
      setInfo(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load(path)
  }, [path, load])

  const refresh = () => void load(path)

  const origin = currentOrigin()
  const guides = useMemo(
    () => webDavClientGuides(origin, info?.url ?? '/webdav'),
    [origin, info?.url],
  )
  const breadcrumbs = useMemo(() => davBreadcrumbs(path), [path])
  const entries = info?.entries ?? []

  const handleUpload = async (files: FileList | null) => {
    if (!files || files.length === 0) return
    setBusy(true)
    let uploaded = 0
    const failures: string[] = []
    for (const file of Array.from(files)) {
      try {
        await api.webDavUpload(joinWebDavPath(path, file.name), file)
        uploaded += 1
      } catch (error) {
        failures.push(`${file.name}：${error instanceof Error ? error.message : '上传失败'}`)
      }
    }
    setBusy(false)
    if (uploaded > 0) toast.success(`已上传 ${uploaded} 个文件`)
    if (failures.length > 0) toast.error(failures.join('；'))
    if (uploadInputRef.current) uploadInputRef.current.value = ''
    void load(path)
  }

  const handleCreateFolder = async () => {
    const name = window.prompt('新建文件夹名称')?.trim()
    if (!name) return
    setBusy(true)
    try {
      await api.webDavCreateFolder(joinWebDavPath(path, name))
      toast.success('文件夹已创建')
      void load(path)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '创建文件夹失败')
    } finally {
      setBusy(false)
    }
  }

  const handleDelete = async (entry: { name: string; path: string; directory: boolean }) => {
    if (!window.confirm(`确定要删除${entry.directory ? '文件夹' : '文件'}「${entry.name}」吗？${entry.directory ? '其中的内容会一并删除。' : ''}`)) return
    setBusy(true)
    try {
      await api.webDavDelete(entry.path)
      toast.success('已删除')
      void load(path)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '删除失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="webdav-page">
      <header className="page-title">
        <div>
          <span className="section-kicker">文件服务</span>
          <h1>WebDAV</h1>
          <p>把电脑或手机上的文件直接投递到服务器数据目录，支持资源管理器、Finder、rclone 与 Legado App 备份。</p>
        </div>
        <button type="button" className="ghost-button" onClick={refresh} disabled={loading}>
          <Icon name="refresh" />
          <span>刷新</span>
        </button>
      </header>

      <section className="webdav-status-grid">
        <article className="webdav-card">
          <span className="webdav-card-label">服务状态</span>
          <strong className="webdav-card-value">
            <span className="webdav-status-dot" />运行中
          </strong>
          <span className="webdav-card-hint">已内置在服务端，无需额外端口或容器</span>
        </article>
        <article className="webdav-card">
          <span className="webdav-card-label">访问地址</span>
          <strong className="webdav-card-value webdav-card-mono">{origin}{info?.url ?? '/webdav'}</strong>
          <button
            type="button"
            className="subtle-button"
            onClick={() => void copyText(`${origin}${info?.url ?? '/webdav'}`, '访问地址已复制')}
          >
            <Icon name="copy" />
            <span>复制地址</span>
          </button>
        </article>
        <article className="webdav-card">
          <span className="webdav-card-label">认证方式</span>
          <strong className="webdav-card-value">HTTP Basic</strong>
          <span className="webdav-card-hint">用户名任意填写，密码即当前登录密码</span>
        </article>
        <article className="webdav-card">
          <span className="webdav-card-label">已存数据</span>
          <strong className="webdav-card-value">
            {info ? `${info.fileCount} 个文件 · ${formatDavSize(info.totalBytes)}` : '—'}
          </strong>
          <span className="webdav-card-hint">{info ? `${info.directoryCount} 个文件夹 · ${info.directory}` : '正在读取…'}</span>
        </article>
      </section>

      <section className="webdav-section">
        <h2 className="webdav-section-title">客户端接入</h2>
        <div className="webdav-guide-grid">
          {guides.map(guide => (
            <article key={guide.id} className="webdav-guide">
              <div className="webdav-guide-head">
                <strong>{guide.title}</strong>
                <button type="button" className="subtle-button" onClick={() => void copyText(guide.command, '已复制到剪贴板')}>
                  <Icon name="copy" />
                  <span>{guide.copyLabel}</span>
                </button>
              </div>
              <p>{guide.steps}</p>
              <pre className="webdav-guide-command">{guide.command}</pre>
            </article>
          ))}
        </div>
      </section>

      <section className="webdav-section">
        <div className="webdav-files-head">
          <h2 className="webdav-section-title">文件管理</h2>
          <div className="webdav-files-actions">
            <input
              ref={uploadInputRef}
              type="file"
              multiple
              hidden
              onChange={event => void handleUpload(event.target.files)}
            />
            <button type="button" className="subtle-button" onClick={handleCreateFolder} disabled={busy}>
              <Icon name="plus" />
              <span>新建文件夹</span>
            </button>
            <button type="button" className="primary-button" onClick={() => uploadInputRef.current?.click()} disabled={busy}>
              <Icon name="upload" />
              <span>{busy ? '处理中…' : '上传文件'}</span>
            </button>
          </div>
        </div>

        <nav className="webdav-breadcrumbs" aria-label="WebDAV 目录路径">
          {breadcrumbs.map((crumb, index) => (
            <React.Fragment key={crumb.path || 'root'}>
              {index > 0 && <span className="webdav-crumb-sep">/</span>}
              <button
                type="button"
                className={crumb.path === path ? 'webdav-crumb active' : 'webdav-crumb'}
                onClick={() => setPath(crumb.path)}
              >
                {crumb.name}
              </button>
            </React.Fragment>
          ))}
        </nav>

        {loading ? (
          <p className="webdav-empty">正在读取目录…</p>
        ) : entries.length === 0 ? (
          <p className="webdav-empty">这个目录还是空的，点击右上角「上传文件」投递第一个文件。</p>
        ) : (
          <ul className="webdav-file-list">
            {entries.map(entry => (
              <li key={entry.path} className="webdav-file-row">
                <span className={`webdav-file-icon ${entry.directory ? 'is-folder' : ''}`}>
                  <Icon name={entry.directory ? 'folder' : 'file'} />
                </span>
                <div className="webdav-file-main">
                  {entry.directory ? (
                    <button type="button" className="webdav-file-name is-link" onClick={() => setPath(entry.path)}>
                      {entry.name}
                    </button>
                  ) : (
                    <span className="webdav-file-name">{entry.name}</span>
                  )}
                  <span className="webdav-file-meta">
                    {entry.directory ? '文件夹' : formatDavSize(entry.size)} · {formatDavTime(entry.modifiedAt)}
                  </span>
                </div>
                <div className="webdav-file-actions">
                  {!entry.directory && (
                    <a
                      className="subtle-button"
                      href={webDavFileUrl(entry.path)}
                      download={entry.name}
                      title="下载"
                    >
                      <Icon name="download" />
                      <span>下载</span>
                    </a>
                  )}
                  <button
                    type="button"
                    className="danger-btn"
                    onClick={() => void handleDelete(entry)}
                    disabled={busy}
                    title="删除"
                  >
                    <Icon name="trash" />
                    <span>删除</span>
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <p className="webdav-notice">
        <Icon name="settings" />
        <span>WebDAV 使用 HTTP Basic 认证，公网部署请务必通过 HTTPS 反向代理访问；反代层还需调大 client_max_body_size 才能上传大文件。</span>
      </p>
    </main>
  )
}
