import React, { Component, ErrorInfo, ReactNode } from 'react'

interface Props {
  children: ReactNode
  fallback?: ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

export class ErrorBoundary extends Component<Props, State> {
  public state: State = {
    hasError: false,
    error: null,
  }

  public static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  public componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('Uncaught React error:', error, errorInfo)
  }

  private handleReset = () => {
    this.setState({ hasError: false, error: null })
    if (typeof window !== 'undefined') {
      location.hash = '#shelf'
      location.reload()
    }
  }

  private handleBackToShelf = () => {
    this.setState({ hasError: false, error: null })
    if (typeof window !== 'undefined') {
      location.hash = '#shelf'
    }
  }

  public render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback
      }
      return (
        <div className="error-boundary-screen" style={{
          padding: '48px 24px',
          textAlign: 'center',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: '60vh',
          gap: '16px',
        }}>
          <h2 style={{ fontSize: '20px', fontWeight: 600 }}>页面渲染遇到异常</h2>
          <p style={{ color: 'var(--muted, #888)', maxWidth: '480px', fontSize: '14px', lineHeight: 1.6 }}>
            {this.state.error?.message || '组件加载失败，请尝试刷新页面或返回书架。'}
          </p>
          <div style={{ display: 'flex', gap: '12px', marginTop: '8px' }}>
            <button
              type="button"
              className="primary-button"
              onClick={this.handleReset}
              style={{ padding: '8px 18px', cursor: 'pointer' }}
            >
              刷新重试
            </button>
            <button
              type="button"
              className="subtle-button"
              onClick={this.handleBackToShelf}
              style={{ padding: '8px 18px', cursor: 'pointer' }}
            >
              返回书架
            </button>
          </div>
        </div>
      )
    }

    return this.props.children
  }
}
