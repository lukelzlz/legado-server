import React, { useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Icon } from './icons'

/**
 * 书库页顶部的主搜索框（唯一的「输入书名/作者 + 搜索」入口）。
 *
 * 为什么单独一个文件（与 `SearchScopeBar` 同构）：搜索框的交互（清空 / `Esc` / 空输入禁用）
 * 需要能脱离 `main.tsx` 做渲染级回归测试——`main.tsx` 末尾有 `createRoot(...)`，Node 里 import 不了。
 */
export interface SearchBoxProps {
  /** 当前关键词（受控；真值来源是 searchStore）。 */
  keyword: string
  /** 搜索请求进行中：提交按钮换成「停止搜索」，并在输入框右侧显示加载指示器。 */
  loading: boolean
  /** 关键词变化。 */
  onChange: (keyword: string) => void
  /** 提交搜索（回车与主按钮共用同一条路径）。 */
  onSubmit: () => void
  /** 中断进行中的搜索。 */
  onStop: () => void
}

/**
 * 「清空」的唯一实现：置空关键词，并把焦点交还输入框（方便直接重新输入）。
 * 导出只为可单测（假 ref / 假 onChange 即可验证），不打算作为对外 API。
 */
export function clearSearchInput(
  input: { current: Pick<HTMLInputElement, 'focus'> | null },
  onChange: (keyword: string) => void,
): void {
  onChange('')
  input.current?.focus()
}

/**
 * 输入框按键接线：`Esc` 清空并保持焦点。
 *
 * `Enter` **刻意不在这里处理**——它由外层 `<form>` 的 submit 事件负责，
 * 同一个提交动作只保留一条实现，避免回车触发两次搜索。
 */
export function handleSearchKeyDown(
  event: Pick<React.KeyboardEvent<HTMLInputElement>, 'key' | 'preventDefault'>,
  onClear: () => void,
): void {
  if (event.key !== 'Escape') return
  event.preventDefault()
  onClear()
}

export function SearchBox({ keyword, loading, onChange, onSubmit, onStop }: SearchBoxProps) {
  const { t } = useTranslation()
  const inputRef = useRef<HTMLInputElement>(null)
  /** 清空按钮的显隐：有任何字符（含空格）就显示，用户随时能把框恢复干净。 */
  const hasContent = keyword.length > 0
  /** 提交按钮的可用性：纯空白等同于空输入（searchStore.startSearch 本来也会直接 return）。 */
  const canSubmit = keyword.trim().length > 0
  const clear = () => clearSearchInput(inputRef, onChange)

  return (
    <form
      className={`library-search-form${loading ? ' is-searching' : ''}`}
      role="search"
      onSubmit={event => {
        event.preventDefault()
        onSubmit()
      }}
    >
      <Icon name="search" />
      <input
        ref={inputRef}
        type="text"
        value={keyword}
        onChange={event => onChange(event.target.value)}
        onKeyDown={event => handleSearchKeyDown(event, clear)}
        placeholder={t('library.searchPlaceholder', '输入书名或作者')}
        aria-label={t('library.searchPlaceholder', '输入书名或作者')}
        enterKeyHint="search"
        autoComplete="off"
        spellCheck={false}
      />
      {loading && (
        <span
          className="library-search-spinner"
          role="status"
          aria-label={t('library.searching', '正在搜索')}
        />
      )}
      {hasContent && (
        <button
          type="button"
          className="library-search-clear"
          onClick={clear}
          title={t('library.clearSearch', '清空')}
          aria-label={t('library.clearSearch', '清空')}
        >
          <Icon name="close" />
        </button>
      )}
      {loading ? (
        <button
          type="button"
          className="primary-button library-search-submit"
          onClick={onStop}
        >
          {t('library.stopSearch', '停止搜索')}
        </button>
      ) : (
        <button
          type="submit"
          className="primary-button library-search-submit"
          disabled={!canSubmit}
        >
          {t('library.startSearch', '搜索')}
        </button>
      )}
    </form>
  )
}
