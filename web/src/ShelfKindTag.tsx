import React from 'react'
import { useTranslation } from 'react-i18next'

/**
 * 书架卡片右上角**书籍类型角标的唯一产出点**：本地 / 漫画 / 听书 / 小说，四者互斥，
 * 任一卡片右上角**最多只有一个**角标 —— 别在 main.tsx 或别处再内联一份。
 *
 * 判定优先序（issue #66 用户要求「本地书导入时右上角标签添加"本地"，漫画有声小说同理」）：
 * 1. **本地导入书优先**（`local` prop，由 `isLocalBook` 按 `loc_book` / `local://` 身份判）——
 *    身份比服务端的 `book_kind` 更可靠：老数据可能还没回填成 `local`；
 * 2. 其次按服务端下发的 `bookKind`（`comic` / `audio` / `local`）；
 * 3. 都不是 ⇒ `novel`（与 `BookshelfItem.bookKind` 的默认值同源）。
 *
 * 独立成模块是为了可测：main.tsx 末尾有 createRoot(...) 且 import 了 styles.css，
 * Node 里 import 会直接崩，渲染断言只能打在独立组件上。
 */
export function ShelfKindTag({ bookKind, local }: { bookKind?: string; local?: boolean }) {
  const { t } = useTranslation()
  if (local || bookKind === 'local') return <span className="shelf-card-tag-local">{t('shelf.localTag', '本地')}</span>
  if (bookKind === 'comic') return <span className="shelf-card-tag-comic">{t('shelf.comicTag', '漫画')}</span>
  if (bookKind === 'audio') return <span className="shelf-card-tag-audio">{t('shelf.audioTag', '听书')}</span>
  return <span className="shelf-card-tag-novel">{t('shelf.novelTag', '小说')}</span>
}
