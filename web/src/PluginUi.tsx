import { useEffect, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { ICON_NAMES, Icon, type IconName } from './icons'

/**
 * 注入给插件的宿主 UI 组件集合。
 *
 * 插件只能拿到这些组件，不能直接 import 宿主内部模块，因此这里既是能力边界也是样式边界：
 * 组件全部复用现有 CSS 变量与类名，插件页面在三种主题下与宿主页面视觉一致。
 */

/** 插件可能传入任意图标名，非法名称回退到 book，避免渲染出空图标 */
export function PluginIcon({ name, className }: { name?: string; className?: string }) {
  const resolved: IconName = name && (ICON_NAMES as readonly string[]).includes(name) ? (name as IconName) : 'book'
  return <Icon name={resolved} className={className} />
}

export type PluginButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'type'> & {
  variant?: 'primary' | 'ghost' | 'danger'
  size?: 'sm' | 'md'
  icon?: string
}

/** 按钮默认且强制 type="button"，避免插件在表单内误触提交 */
export function PluginButton({ variant = 'ghost', size = 'md', icon, className, children, ...rest }: PluginButtonProps) {
  const variantClass = variant === 'primary' ? 'primary-button' : variant === 'danger' ? 'danger-button' : 'subtle-button'
  return (
    <button
      type="button"
      {...rest}
      className={`${variantClass} plugin-btn plugin-btn-${size}${className ? ` ${className}` : ''}`}
    >
      {icon && <PluginIcon name={icon} />}
      {children}
    </button>
  )
}

export function PluginModal({
  title,
  onClose,
  children,
  footer,
}: {
  title: string
  onClose: () => void
  children?: ReactNode
  footer?: ReactNode
}) {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  return (
    <div className="modal-backdrop top-layer-modal-backdrop" onClick={onClose}>
      <div
        className="plugin-modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={event => event.stopPropagation()}
      >
        <header className="plugin-modal-header">
          <h2>{title}</h2>
          <button type="button" className="subtle-button close-btn" onClick={onClose} aria-label="关闭">
            <Icon name="close" />
          </button>
        </header>
        <div className="plugin-modal-body">{children}</div>
        {footer && <footer className="plugin-modal-footer">{footer}</footer>}
      </div>
    </div>
  )
}

export function PluginCard({ title, actions, children }: { title?: string; actions?: ReactNode; children?: ReactNode }) {
  return (
    <section className="plugin-card-ui">
      {(title || actions) && (
        <header className="plugin-card-ui-head">
          {title && <h3>{title}</h3>}
          {actions && <div className="plugin-card-ui-actions">{actions}</div>}
        </header>
      )}
      <div className="plugin-card-ui-body">{children}</div>
    </section>
  )
}

export function PluginSpinner({ label = '加载中...' }: { label?: string }) {
  return (
    <div className="plugin-spinner" role="status">
      <span className="plugin-spinner-ring" />
      <span>{label}</span>
    </div>
  )
}

export function PluginEmpty({ message }: { message: string }) {
  return (
    <div className="plugin-empty">
      <Icon name="book" />
      <p>{message}</p>
    </div>
  )
}

export function PluginPageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <header className="plugin-page-header">
      <div>
        <h2>{title}</h2>
        {description && <p>{description}</p>}
      </div>
      {actions && <div className="plugin-page-header-actions">{actions}</div>}
    </header>
  )
}

export function PluginField({ label, hint, children }: { label: string; hint?: string; children?: ReactNode }) {
  return (
    <label className="plugin-field">
      <span className="plugin-field-label">{label}</span>
      {children}
      {hint && <small className="plugin-field-hint">{hint}</small>}
    </label>
  )
}

export type PluginTableColumn = {
  key: string
  title: string
  render?: (row: unknown, index: number) => ReactNode
  width?: string | number
}

function renderCell(row: unknown, column: PluginTableColumn, index: number): ReactNode {
  if (column.render) return column.render(row, index)
  if (!row || typeof row !== 'object') return row === null || row === undefined ? '' : String(row)
  const value = (row as Record<string, unknown>)[column.key]
  if (value === null || value === undefined) return ''
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}

export function PluginTable({ columns, rows, empty }: { columns: PluginTableColumn[]; rows: unknown[]; empty?: string }) {
  if (rows.length === 0) return <PluginEmpty message={empty || '暂无数据'} />
  return (
    <div className="plugin-table-wrap">
      <table className="plugin-table">
        <thead>
          <tr>
            {columns.map(column => (
              <th
                key={column.key}
                style={column.width ? { width: typeof column.width === 'number' ? `${column.width}px` : column.width } : undefined}
              >
                {column.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={index}>
              {columns.map(column => (
                <td key={column.key}>{renderCell(row, column, index)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** 注入给插件的 ui 命名空间 */
export const pluginUi = {
  Button: PluginButton,
  Icon: PluginIcon,
  Modal: PluginModal,
  Card: PluginCard,
  Spinner: PluginSpinner,
  Empty: PluginEmpty,
  PageHeader: PluginPageHeader,
  Field: PluginField,
  Table: PluginTable,
}
