import { useEffect, useState } from 'react'
import { Icon } from './icons'

export type ToastType = 'success' | 'info' | 'error' | 'warning'

export type ToastItem = {
  id: string
  type: ToastType
  message: string
  duration?: number
}

type ToastListener = (toasts: ToastItem[]) => void

class ToastStore {
  private toasts: ToastItem[] = []
  private listeners = new Set<ToastListener>()

  subscribe(listener: ToastListener) {
    this.listeners.add(listener)
    listener(this.toasts)
    return () => {
      this.listeners.delete(listener)
    }
  }

  private notify() {
    this.listeners.forEach(listener => listener([...this.toasts]))
  }

  show(message: string, type: ToastType = 'info', duration = 2800) {
    const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
    const toast: ToastItem = { id, message, type, duration }
    this.toasts = [...this.toasts, toast]
    this.notify()

    if (duration > 0) {
      setTimeout(() => {
        this.dismiss(id)
      }, duration)
    }
    return id
  }

  dismiss(id: string) {
    this.toasts = this.toasts.filter(t => t.id !== id)
    this.notify()
  }

  success(message: string, duration = 2800) {
    return this.show(message, 'success', duration)
  }

  info(message: string, duration = 2800) {
    return this.show(message, 'info', duration)
  }

  error(message: string, duration = 3500) {
    return this.show(message, 'error', duration)
  }

  warning(message: string, duration = 3000) {
    return this.show(message, 'warning', duration)
  }
}

export const toast = new ToastStore()

export function ToastContainer() {
  const [toasts, setToasts] = useState<ToastItem[]>([])

  useEffect(() => {
    return toast.subscribe(setToasts)
  }, [])

  if (toasts.length === 0) return null

  return (
    <div className="toast-container" aria-live="polite" role="region">
      {toasts.map(item => {
        const iconName = item.type === 'success' ? 'check'
          : item.type === 'error' ? 'close'
          : item.type === 'warning' ? 'sliders'
          : 'book'

        return (
          <div key={item.id} className={`toast-item toast-${item.type}`} role="status">
            <span className="toast-icon">
              <Icon name={iconName} />
            </span>
            <span className="toast-message">{item.message}</span>
            <button
              className="toast-close"
              onClick={() => toast.dismiss(item.id)}
              aria-label="关闭提示"
            >
              <Icon name="close" />
            </button>
          </div>
        )
      })}
    </div>
  )
}
