import React from 'react'

export interface ButtonSpinnerProps {
  /**
   * 尺寸：像素数值或预设 'sm' (12px) | 'md' (14px) | 'lg' (18px)。
   * 默认为 'md' (14px)。
   */
  size?: number | 'sm' | 'md' | 'lg'
  className?: string
  label?: string
}

/**
 * 统一通用按钮/行内加载微动画 Spinner (Issue #67)
 * - 颜色继承当前文字前景色 currentColor，适配任意主题与危险/主色调按钮
 * - 声明 role="status" 与 aria-label="loading" 保证无障碍
 * - 遵循 prefers-reduced-motion 规范
 */
export function ButtonSpinner({ size = 'md', className = '', label = 'loading' }: ButtonSpinnerProps) {
  const sizeClass = size === 'sm' ? 'btn-spinner-sm' : size === 'lg' ? 'btn-spinner-lg' : ''
  const inlineStyle = typeof size === 'number' ? { width: size, height: size } : undefined

  return (
    <span
      className={`btn-spinner ${sizeClass} ${className}`.trim()}
      style={inlineStyle}
      role="status"
      aria-label={label}
    />
  )
}
