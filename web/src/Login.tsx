import React, { FormEvent, useState } from 'react'
import { api, setCsrfToken } from './api'
import { Logo } from './Logo'

export interface LoginProps {
  onLogin: () => void
}

export function Login({ onLogin }: LoginProps) {
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      const result = await api.login(password)
      setCsrfToken(result.csrfToken)
      onLogin()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '登录失败')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="login-shell">
      <form className="login-panel" onSubmit={submit}>
        <div className="login-mark">
          <Logo size={28} />
          <strong>阅读服务器</strong>
        </div>
        <h1>回到你的阅读空间</h1>
        <p>输入部署时设置的单用户密码继续。</p>
        <label>
          密码
          <input
            autoFocus
            type="password"
            value={password}
            onChange={event => setPassword(event.target.value)}
            required
          />
        </label>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <button className="primary-button" disabled={busy}>
          {busy ? '正在验证...' : '登录'}
        </button>
      </form>
    </main>
  )
}
