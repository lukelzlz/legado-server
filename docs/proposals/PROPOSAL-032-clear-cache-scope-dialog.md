# PROPOSAL-032 · 「清除缓存」按书 + 范围三选弹窗（服务器 / 浏览器 / 两者）

- **状态**：Implemented（本地已实现并通过单测；待验收）
- **关联**：[ACCEPT-032](../acceptance/ACCEPT-032-clear-cache-and-reader-ui-batch.md) · [SESSION-049](../sessions/SESSION-049-cache-layout-i18n-and-reader-toc-batch.md) · [PROPOSAL-031](PROPOSAL-031-browsable-cache-layout-and-readable-cover-names.md)（缓存目录本身的可浏览化）
- **实现提交**：`a7a6730`（书籍管理弹窗新增「清除缓存」，只清这一本）→ `14266b7`（升级为范围三选弹窗）

---

## 1. 需求来源（用户原话）

> 「在移出书架上面添加一个新按钮清除缓存，和移出书架一样用红色配色」

随后就作用域明确回答：

> 「只清这本书的」

再加范围选项：

> 「删除时添加新弹窗三个选项，清除服务器缓存，清除浏览器缓存，清理服务器和浏览器缓存」
>
> 「并且也都是红色配色」

---

## 2. 现状与缺口（改动前）

| 环节 | 实际状态 |
| :--- | :--- |
| 入口 | 书籍管理弹窗只有「编辑信息 / 换源 / 缓存 / 标记读完 / 移出书架」，**没有单书清缓存** |
| 全清 | 只有「离线缓存管理」里的**整机级**操作（`clearAllOffline` / `evictLruOfflineBooks`），粒度太粗 |
| 服务端缓存 | 三个按书清理端点早已存在（图片 / 音频 / 正文），但**前端从未按书调用** |
| 作用域表达 | 用原生 `confirm()` 只能"确定/取消"，**表达不了三种范围** |

也就是说：能力在服务端**早就有**，缺的是"按书 + 明确范围"的用户入口。

---

## 3. 目标

1. 在「移出书架」**上面**新增「清除缓存」，**红色与「移出书架」同源**（同一套 `.manage-action-row.danger`）。
2. 点击后弹出**范围三选**弹窗：清除服务器缓存 / 清除浏览器缓存 / 清理服务器和浏览器缓存，
   三个选项**也是红色**，另加一个**中性**「取消」。
3. 作用域**永远只带这一本书**（`sourceId + bookUrl`）。
4. 范围正确性必须**可被单测真正执行到**，而不是只能对着 `main.tsx` grep 字符串。

## 4. Non-Goals

- **不做整机全清入口**：弹窗里刻意**不接** `clearAllOffline` / `evictLruOfflineBooks` /
  `getOfflineStorageStats`（保留"没有全清分支"的守卫）。整机清理仍归「离线缓存管理」页。
- **不改任何服务端契约**：三个 `DELETE` 端点（图片 / 音频 / 正文）沿用原样，本轮 `server/` **零改动**。
- **不动 `api.ts` / `offlineStorage.ts`**：全部复用既有封装。

---

## 5. 方案

### 5.1 作用域模型抽成纯模块 `web/src/shelfClearCache.ts`（50 行）

```ts
export type ClearCacheScope = 'server' | 'browser' | 'both'
export type ClearCacheStep = 'images' | 'audio' | 'content' | 'local'

export const SERVER_CLEAR_STEPS  = ['images', 'audio', 'content'] as const
export const BROWSER_CLEAR_STEPS = ['local'] as const
export const SCOPE_STEPS = {
  server:  SERVER_CLEAR_STEPS,
  browser: BROWSER_CLEAR_STEPS,
  both:    [...SERVER_CLEAR_STEPS, ...BROWSER_CLEAR_STEPS],
} as const

export function stepsForScope(scope: ClearCacheScope) { return SCOPE_STEPS[scope] ?? [] }
export function isServerStep(step: ClearCacheStep) { return step !== 'local' }
```

**为什么要单独一个模块**：`ShelfPage`、`BookManageModal`、范围弹窗都住在 `main.tsx` 里，
而该模块 ① 顶部 `import './styles.css'`、② 模块顶层直接
`createRoot(document.getElementById('root')!)` ⇒ **Node 侧无法 import 它**（实测 `.css` 报
`Unknown file extension`）。把「作用域 → 精确要执行的清理步骤」抽成纯表，
才能让**「选服务器就绝不动本机、选浏览器就绝不碰服务端」这条范围正确性被单测真正执行到**。

**为什么是表驱动而不是三个 `if` 分支**：四个清理动作用户只能三选一，最容易出的错就是
「改了一条分支忘了另一条」⇒ **串范围**（点"只清浏览器"却把服务端缓存也删了）。
所有调用点都从这张表派生 ⇒ 范围**只在一处定义**，改错一处就会被断言当场抓住。

**`stepsForScope` 对未知作用域返回空数组**：这个函数是唯一决定"删什么"的地方，
宁可少删让用户重试，也**绝不越界删**。

### 5.2 四个步骤对应的真实调用（按书）

| step | 调用 | 服务端语义 |
| :--- | :--- | :--- |
| `images` | `DELETE /api/books/images/cache?sourceId=&bookUrl=` | `mangaPrefetch.cancel()` → `imageCache.deleteBook()`，**清单也一起清**（否则下次打开会拿到一张"零图片"的空清单） |
| `audio` | `DELETE /api/audio/cache?sourceId=&bookUrl=` | 同构（`AudioCache.deleteBook`） |
| `content` | `DELETE /api/bookshelf/cache?...&clearData=true` | `bookCache.cancel()` + `clearBookCacheContent()`；**不删 `book_toc_cache`** ⇒ 目录、阅读进度、书架记录、封面都不受影响 |
| `local` | `clearOfflineBook(sourceId, bookUrl)` | 本机 IndexedDB `legado_offline_db.chapters` 走 `by_book` 索引 + `IDBKeyRange.only([sourceId, bookUrl])` |

四个清理调用在整个 `main.tsx` **各只有一处、全在 `stepsForScope` 循环内**。

### 5.3 弹窗 `ClearCacheScopeModal`

- 三个选项一律 `manage-action-row danger`（红色与「移出书架」同源，都来自
  `.manage-action-row.danger { color: var(--danger) }`），**取消**用中性 `manage-action-row`；
- `createPortal(..., document.body)` 逃出带 `backdrop-filter` 的包含块，
  并**显式**绑 `` theme-${theme} ``（theme 由 `App settings.theme → ShelfPage → 弹窗` 传递，
  不读全局/localStorage）—— 否则脱离 `.app-shell` 后主题变量回退，夜间模式下会出现
  透光背景 + 隐形黑字；
- 由 `ShelfPage` 以**平级条件分支**挂载（此时书籍管理弹窗已关闭），
  杜绝"带动画的 `modal-backdrop` 里再嵌一层 `modal-backdrop`"；
- `role="dialog"` + `aria-modal` + `aria-label`；5 个按钮全部 `type="button"`；
  `busy` 期间不可关、按钮 `disabled`（防重复提交）。

### 5.4 记账：只报全成功，或点名**实际执行过**的失败段

循环内**唯一一处 `catch`** 里**唯一一处 `push`**（三元挑标签）：

- 全成功 ⇒ `toast.success('《书名》缓存已清除')`；
- 有失败 ⇒ `toast.warning('《书名》部分缓存未清除：图片缓存、正文缓存，可稍后重试')`；
- 因为是"循环内唯一 push + 标签由 step 派生"，选**「服务器」时结构上不可能**出现
  "本机离线正文"字样；
- `finally` 复位 busy 状态，随后 `void load()` 回书架拉最新状态（否则缓存进度条停在旧值）。

### 5.5 i18n

新增 9 个 key（弹窗标题 / 三个选项 / 三个副说明 / 取消 + 说明），并把
`shelf.clearCacheConfirm`（"确定继续吗？"）**改名复用**为 `shelf.clearCacheHint`：

> 「将清除《{{name}}》已下载的缓存；阅读进度、书架记录与封面不受影响，请选择清除范围。」

**四语都不留悬空 key**。用户可见文案（zh-CN）：

| key | 文案 |
| :--- | :--- |
| `shelf.clearCache` | 清除缓存 |
| `shelf.clearCacheServer` | 清除服务器缓存 |
| `shelf.clearCacheServerDesc` | 清掉服务端已下载的图片、音频与正文，下次阅读会重新下载 |
| `shelf.clearCacheBrowser` | 清除浏览器缓存 |
| `shelf.clearCacheBrowserDesc` | 只清本机已下载的离线正文，不影响服务端 |
| `shelf.clearCacheBoth` | 清理服务器和浏览器缓存 |
| `shelf.clearCacheBothDesc` | 一次清干净：服务端与本机一起清 |
| `shelf.clearCacheCancel` | 取消 |
| `shelf.clearCacheCancelDesc` | 不做任何清理，返回书籍管理 |

### 5.6 如实说明的副作用

`clearOfflineBook` 内部会顺带清该书的**漫画清单快照**（`clearMangaManifests`，既有行为，
同样按 `[sourceId, bookUrl]` 索引 ⇒ **仍然只影响这一本**）。SW 里的图片字节由 SW 自己的配额策略负责。

---

## 6. User Stories

1. 作为读者，我在某本书上点「清除缓存」，**先看到书名和一句说明**（会清什么、不会动什么），
   再从三个范围里选一个。
2. 作为读者，我选「清除浏览器缓存」⇒ 服务端缓存**一个字都没动**（下次仍然秒开正文/图片）。
3. 作为读者，我选「清除服务器缓存」⇒ 提示里**绝不会**出现「本机离线正文」这种没被执行过的段。
4. 作为读者，我只想清这一本 ⇒ 另外两本书的缓存（同源不同书、同书号不同源）**一字未动**。
5. 作为读者，我点「取消」⇒ **什么都不会被删**，弹窗关闭回到书籍管理。

---

## 7. 验收要点

见 [ACCEPT-032](../acceptance/ACCEPT-032-clear-cache-and-reader-ui-batch.md)。核心可观察行为：

- 「清除缓存」按钮在「移出书架」**上面**、红色与它逐字同源；
- 弹窗三个选项 + 取消，**前三个红色、取消中性**，取消在最后；
- 选「服务器」⇒ 只发三个 `DELETE`（图片/音频/正文），**没有** IndexedDB 删除；
- 选「浏览器」⇒ **只**删本机 IndexedDB 离线正文，服务端三个端点**一次都没有被调用**；
- 选「两者」⇒ 四步有序执行；
- 清一本后，其余书的缓存与阅读进度、书架记录、封面**全部不受影响**。
