import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Icon } from './icons'
import type { HostPluginPage } from './pluginHost'

/**
 * 插件页面渲染外壳。
 *
 * 插件代码由第三方提供，渲染异常必须被拦在这里：只退化成本页的错误卡片，
 * 不能让整个宿主应用白屏。
 */

type PluginErrorBoundaryProps = {
  pluginId: string
  title: string
  onHome: () => void
  children: ReactNode
}

type PluginErrorBoundaryState = { error: string | null }

export class PluginErrorBoundary extends Component<PluginErrorBoundaryProps, PluginErrorBoundaryState> {
  state: PluginErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: unknown): PluginErrorBoundaryState {
    return { error: error instanceof Error ? error.message : String(error) }
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.error(`[plugins] ${this.props.pluginId} 页面渲染失败`, error, info.componentStack)
  }

  render() {
    if (this.state.error) {
      return (
        <section className="plugin-error-card" role="alert">
          <Icon name="close" />
          <h2>插件页面出错了</h2>
          <p>插件「{this.props.title}」（{this.props.pluginId}）渲染时抛出异常，宿主其它页面不受影响。</p>
          <pre className="plugin-error-detail">{this.state.error}</pre>
          <button type="button" className="subtle-button" onClick={this.props.onHome}>返回书库</button>
        </section>
      )
    }
    return this.props.children
  }
}

export function PluginPageView({ page, navigate }: { page: HostPluginPage; navigate: (page: string) => void }) {
  return (
    <main className="plugin-page-shell">
      <PluginErrorBoundary
        key={page.key}
        pluginId={page.pluginId}
        title={page.title}
        onHome={() => navigate('library')}
      >
        {page.render({ pageId: page.id, pluginId: page.pluginId, navigate })}
      </PluginErrorBoundary>
    </main>
  )
}

/** 插件页面尚未注册（或注册它的插件加载失败）时的兜底视图 */
export function PluginPageFallback({
  pageKey,
  loading,
  onHome,
}: {
  pageKey: string
  loading: boolean
  onHome: () => void
}) {
  if (loading) {
    return (
      <main className="plugin-page-shell">
        <div className="plugin-page-pending">
          <span className="plugin-spinner-ring" />
          <p>正在加载插件页面...</p>
        </div>
      </main>
    )
  }
  return (
    <main className="plugin-page-shell">
      <section className="plugin-error-card" role="alert">
        <Icon name="close" />
        <h2>插件页面不可用</h2>
        <p>没有插件注册页面「{pageKey}」，可能是插件未启用、加载失败或已被卸载。</p>
        <button type="button" className="subtle-button" onClick={onHome}>返回书库</button>
      </section>
    </main>
  )
}
