import React from 'react'
import { useTranslation } from 'react-i18next'

/**
 * 书架卡片右上角**书籍类型角标的唯一产出点**：漫画 / 听书 / 小说，三者互斥，
 * 任一卡片右上角**最多只有一个**角标 —— 别在 main.tsx 或别处再内联一份。
 * `bookKind` 由服务端下发（默认 `'novel'`）：不是漫画也不是听书即按小说显示。
 * 独立成模块是为了可测：main.tsx 末尾有 createRoot(...) 且 import 了 styles.css，
 * Node 里 import 会直接崩，渲染断言只能打在独立组件上。
 */
export function ShelfKindTag({ bookKind }: { bookKind?: string }) {
  const { t } = useTranslation()
  return (
    <>
      {bookKind === 'comic' && <span className="shelf-card-tag-comic">{t('shelf.comicTag', '漫画')}</span>}
      {bookKind === 'audio' && <span className="shelf-card-tag-audio">{t('shelf.audioTag', '听书')}</span>}
      {bookKind !== 'comic' && bookKind !== 'audio' && <span className="shelf-card-tag-novel">{t('shelf.novelTag', '小说')}</span>}
    </>
  )
}
