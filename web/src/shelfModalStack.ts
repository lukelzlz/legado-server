/**
 * 书架「书籍管理（一级）」→「清除缓存范围选择（二级）」两级弹窗的**层级状态机**。
 *
 * ## 为什么要有它（GitHub issue #66）
 * 修复前两级弹窗是两个**彼此独立**的可空状态（`managingItem` / `clearingCacheItem`）：
 * 点「清除缓存」打开二级时顺手把一级置 `null`。二级的「取消」只关掉二级
 * ⇒ 一级早已不存在 ⇒ 两级一起消失回到书架，与取消行的文案
 * 「不做任何清理，返回书籍管理」**直接矛盾**。
 *
 * 两个独立可空量的通病是「谁都可以顺手把对方清掉」，而且这种错**看起来很像对的**
 * （"打开新弹窗先关旧的"）。因此这里换成一个**单一状态**：当前在哪一层 + 这一层是哪本书。
 * 于是「取消 / ESC / 点遮罩 / 清理执行完」= 同一个有名字的转移 `closeClearCacheScope`，
 * 它**只退一级**、一级的书原样保留；要连一级一起关，必须显式 `closeManage`。
 * 换句话说：**「关子级顺手关掉父级」在类型层面就写不出来了**。
 *
 * ## 为什么单独一个纯模块
 * 与 `shelfClearCache.ts` 同因：`main.tsx` ① `import './styles.css'`、
 * ② 模块顶层直接 `createRoot(document.getElementById('root')!)` ⇒ Node 侧无法 import
 * （实测 `.css` 报 `Unknown file extension`）。所以 `main.tsx` 里的 UI 交互只能对着源码 grep。
 * 抽成纯 reducer 后，「点取消后仍停留在书籍管理」这条**真实状态转移**才能在
 * `web/test` 里被真正执行断言，而不是靠字符串匹配。
 *
 * ## 渲染铁律（AGENTS.md）
 * `layer` 是「哪一级可见」的**唯一判据**：两级**互斥挂载**，永远不会同时渲染
 * ⇒ 不可能出现「一个 `modal-backdrop` 里嵌另一个 `modal-backdrop`」，
 * 也不会出现两层遮罩叠在一起。二级关闭后一级自动重新出现，不需要额外恢复动作。
 */

import type { BookshelfItem } from './api'
import { isLocalBook } from './localBook'

/** 当前可见的弹窗层级：书架 / 一级（书籍管理）/ 二级（清除缓存范围选择）。 */
export type ShelfModalLayer = 'shelf' | 'manage' | 'clearCacheScope'

/**
 * 单一状态：层级 + 该层级的书。
 *
 * 关键点：`clearCacheScope` 这一层**自己带着那本书**（与打开它时的一级是同一本），
 * 所以「退回一级」不需要任何外部记忆，`item` 直接复用。
 */
export type ShelfModalState =
  | { layer: 'shelf' }
  | { layer: 'manage'; item: BookshelfItem }
  | { layer: 'clearCacheScope'; item: BookshelfItem }

export type ShelfModalAction =
  /** 书架卡片点「管理」→ 打开一级。 */
  | { type: 'openManage'; item: BookshelfItem }
  /** 一级里点「清除缓存」→ **叠加**二级；一级的书原样保留（取消要回到它）。 */
  | { type: 'openClearCacheScope' }
  /** 二级的取消 / ESC / 点遮罩 / 清理执行完 → **只退一级**，回到书籍管理。 */
  | { type: 'closeClearCacheScope' }
  /** 关闭一级 → 回书架。 */
  | { type: 'closeManage' }
  /** 一级里的编辑 / 缓存状态回写。 */
  | { type: 'updateItem'; item: BookshelfItem }
  /** 书架列表轮询回灌进度（只同步进度相关字段，不动书名/封面等可编辑字段）。 */
  | { type: 'syncSnapshot'; items: readonly BookshelfItem[] }

/** 初始态：两级都关着，人在书架。 */
export const SHELF_MODAL_SHELF: ShelfModalState = { layer: 'shelf' }

export function shelfModalReducer(state: ShelfModalState, action: ShelfModalAction): ShelfModalState {
  switch (action.type) {
    case 'openManage':
      return { layer: 'manage', item: action.item }

    case 'openClearCacheScope':
      // 只有一级真的开着才叠二级；否则保持原样（二级不可能脱离父级单独出现）。
      //
      // 本地书（issue #66）**永远进不了这一层**：它的正文只能从留存的原文件重新解析得到，
      // 不存在"清掉缓存下次重新下载"这条路径。前端已经不给它渲染「清除缓存」行，
      // 这里再挡一道是**防 UI 绕过**（任何别的入口/未来的按钮都不能把它送进范围弹窗）。
      // 拦在 reducer 而不是渲染处：如果把状态切到 clearCacheScope 又不渲染二级，
      // 用户会看到两个弹窗一起消失且无处可退，比"什么都不发生"更糟。
      if (state.layer !== 'manage' || isLocalBook(state.item)) return state
      return { layer: 'clearCacheScope', item: state.item }

    case 'closeClearCacheScope':
      // issue #66 的落点：只退一级。注意**不**返回书架，一级的书原样带回去
      return state.layer === 'clearCacheScope' ? { layer: 'manage', item: state.item } : state

    case 'closeManage':
      return SHELF_MODAL_SHELF

    case 'updateItem':
      return state.layer === 'shelf' ? state : { layer: state.layer, item: action.item }

    case 'syncSnapshot': {
      if (state.layer === 'shelf') return state
      const prev = state.item
      const fresh = action.items.find(value => value.sourceId === prev.sourceId && value.bookUrl === prev.bookUrl)
      if (!fresh) return state
      if (
        fresh.cachedChapters === prev.cachedChapters
        && fresh.totalChapters === prev.totalChapters
        && fresh.cacheState === prev.cacheState
        && fresh.cacheError === prev.cacheError
      ) return state
      return {
        layer: state.layer,
        item: {
          ...prev,
          cachedChapters: fresh.cachedChapters,
          totalChapters: fresh.totalChapters,
          cacheState: fresh.cacheState,
          cacheError: fresh.cacheError,
        },
      }
    }
  }
}
