import React, { FormEvent, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { api, setCsrfToken } from './api'
import { Logo } from './Logo'
import { SUPPORTED_LOCALES, changeAppLanguage, getCurrentLocale } from './i18n'

export interface LoginProps {
  onLogin: () => void
}

export function Login({ onLogin }: LoginProps) {
  const { t, i18n } = useTranslation()
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
      setError(cause instanceof Error ? cause.message : t('login.failed'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="login-shell">
      <form className="login-panel" onSubmit={submit}>
        <div className="login-mark">
          <Logo size={28} />
          <strong>{t('login.brand')}</strong>
        </div>
        <h1>{t('login.title')}</h1>
        <p>{t('login.desc')}</p>
        <label>
          {t('login.password')}
          <input
            autoFocus
            type="password"
            value={password}
            onChange={event => setPassword(event.target.value)}
            required
            placeholder={t('login.passwordPlaceholder')}
          />
        </label>
        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
        <button type="submit" className="primary-button" disabled={busy}>
          {busy ? t('login.submitting') : t('login.submit')}
        </button>
        <div className="login-lang-switch" role="radiogroup" aria-label={t('header.language')}>
          {SUPPORTED_LOCALES.map(loc => (
            <button
              key={loc.code}
              type="button"
              className={`login-lang-btn ${(i18n.language || getCurrentLocale()) === loc.code ? 'active' : ''}`}
              onClick={() => void changeAppLanguage(loc.code)}
            >
              {loc.nativeName}
            </button>
          ))}
        </div>
      </form>
    </main>
  )
}
