# SESSION-054 阅读器书签落地：定位公式、SQLite NULL 唯一键、以及「点不动的顶栏」

- 日期：2026-11-01
- 分支：feat/reader-bookmarks（不合并 main）
- 关联：[ADR-035](../decisions/ADR-035-reader-bookmarks-and-chapter-pos-contract.md)、[ACCEPT-036](../acceptance/ACCEPT-036-reader-bookmarks.md)

## 需求

> 「在小说阅读器下面的悬浮栏添加书签按钮，上面的栏在设置按钮前面添加一个书签按钮，打开是书签列表，
> 可以跳转到书签也可以修改或删除书签，参考这个书签文件，备份的导入导出也要有，数据库里单独开张表 bookmark 来存放」
> —— 附了一份手机端的 `bookmark.json`（8 字段：`bookAuthor/bookName/bookText/chapterIndex/chapterName/chapterPos/content/time`）

## 这次实际踩到的三个坑

### 1. 「重复添加书签」居然会插出重复行 —— SQLite 的 UNIQUE **不认 NULL 相等**

第一版照仓库既有写法用 `insert ... on conflict(book_name, book_author, chapter_index, chapter_pos) do update`。
用带作者的书测全过（服务端单测、真机 curl 都对）。换成**本地 TXT 书（没有作者）**后，
e2e 里连点 7 次「添加书签」，数据库里出现 **7 条 `chapterPos` 全是 0 的书签**。

根因：SQLite 的 UNIQUE 索引把 **NULL 视为互不相等**，`book_author` 为 NULL 时冲突永远不命中。
改法：`saveBookmark` 先 `update ... where book_name=? and coalesce(book_author,'')=coalesce(?,'') and ...`，
`changed()==0` 才 `insert`。并补了一条专门的回归用例
（`BookmarkRoutesTest."adding the same spot twice for a book without author keeps a single row"`）。

> 教训：**凡是唯一键里可能出现 NULL 的 upsert，都不能指望 `on conflict`**。
> 而且这类缺陷只在「无作者的数据」上出现，用普通样本测永远看不见。

### 2. 顶栏按钮「看得见、点不到」——CDP 坐标点击被毛玻璃吃掉

e2e 里 `page.$('.reader-icon-button[aria-label*="书签"]').click()` 一直超时，
而同样位置用 `element.click()`（DOM 派发）立刻生效。
顶栏/底栏都带 `backdrop-filter: blur()`，CDP 注入的坐标点击落在了被它模糊的正文上。
最终 e2e 对顶栏按钮统一走 DOM 派发（断言的仍是真实的 React 处理器与真实渲染结果），
底栏按钮仍用真实鼠标点击（它没有被这个问题影响）。

> 另一个同源坑：**阅读器的 tap 手势有「上下三分之一=翻滚区、中间=唤出工具条」的分区**。
> e2e 一开始点在正文靠上位置，被当成「上一页」→ 章首边界提示，工具条永远不出现。

### 3. 跳转落点必须「在正文到手那一刻」算

书签存的是**章内字符偏移**（手机端 `chapterPos` 语义），落点要转成**滚动比例**，
而这需要目标章的**真实字数**——`changeChapter()` 的时候目标章正文还没到手。
第一版想「另写一套 scrollTo」，立刻撞上滚动流的老问题：相邻章异步挂载会把目标章推下去，
而既有的对齐 effect 有 `MIN_GUARD_FRAMES = 120` 帧的纠偏守卫（专门对付这个）。
最终改成：跨章时把偏移挂 `pendingBookmarkJumpRef`，`applyContent()` 里换算成比例后交给
既有的 `initialPagePositionRef` 通道；同章时用一个 `bookmarkJumpTick` 让对齐 effect 重跑。

> 顺带踩到：**在 e2e 里用 `window.scrollTo` 滚动会被那轮纠偏守卫顶回来**（scrollY 始终 0）。
> 真实用户的 `wheel`/`touchstart` 会取消纠偏（`cancelRetry`），所以测试也必须用
> `page.mouse.wheel()` 去滚——这既是"像真人"，也是唯一能稳定滚动的路径。

## 验证记录

- **前端**：`npx tsx web/test/bookmark.test.ts` 15/15 通过；`web/test/run-all.ts` 396/396 通过（原 381 + 15）。
- **服务端**：`BookmarkRoutesTest` 3/3 通过（HTTP 增删改查 + 无作者去重 + 备份导出里能查到书签）。
  全量 `:server:test` 的失败集合与本机既有基线一致（Windows 下 SQLite 文件占用导致，与本次无关）。
- **真浏览器**（Edge headless，1280×900 与 390×844 两种视口）`web/test/e2e-bookmark-browser.ts` 9/9 通过：
  ```
  4/9 header buttons = [换源, 书签, 阅读设置, 朗读本章]
  7/9 scrolled to 598, jump back to chapter start: scrollY=0
  8/9 jump back to mid-chapter bookmark: scrollY=598     ← 与加书签时的位置完全一致
  9/9 mobile nav = [目录, 换源, 朗读, 添加书签, Aa阅读设置], bookmarks = 3
  ALL BOOKMARK E2E CHECKS PASSED
  ```
- **备份**：`POST /api/webdav/export` 产出的 zip 里 `bookmark.json` 含刚加的那条
  （`nameMatch=True noteMatch=True pos=1234`），字段名与顺序未变。

## 改动清单

| 层 | 文件 | 要点 |
| --- | --- | --- |
| 服务端 | `Models.kt` | `BookmarkListResponse` / `BookmarkSaveRequest` / `BookmarkUpdateRequest`；`Bookmark` 注释逐条对齐手机端语义 |
| 服务端 | `Database.kt` | `saveBookmark`（先 update 后 insert，绕开 NULL 唯一键）、`updateBookmark`、`deleteBookmark`、`selectBookmark` |
| 服务端 | `Routes.kt` | `GET/POST /api/bookmarks`、`PUT/DELETE /api/bookmarks/{id}`（写操作 CSRF） |
| 前端 | `bookmarkPos.ts` | 偏移 ⇄ 比例、摘要裁剪（纯函数） |
| 前端 | `BookmarkListModal.tsx` | 列表 / 跳转 / 行内编辑 / 行内删除确认 / 当前位置添加 |
| 前端 | `ReaderScreen.tsx` | 顶栏按钮（设置前）、底栏 5 格、`addBookmarkHere`、`jumpToBookmark`、`pendingBookmarkJumpRef` |
| 前端 | `api.ts` | 四条接口 + `BookmarkRecord` 类型 |
| 样式 | `styles.css` | 书签弹窗/列表；720px、420px 两个断点顶栏改为 `auto`（容纳第三颗按钮） |
| 文案 | 四个 locale | 11 个新 key（备注/原文/编辑/删除/条数/无摘要/加载失败…） |
| 脚本 | `scripts/verify-bookmark.ps1` | 真机接口 + 备份归档核对（ASCII only，PS 5.1 友好） |
| 测试 | `web/test/bookmark.test.ts`、`web/test/e2e-bookmark-browser.ts`、`BookmarkRoutesTest.kt` | 见上 |

## 留给后续

- 手机端在「章名对不上 + 原序号越界」时会弹「仍要跳转？」确认框，本实现直接跳到最后一章
  （成本更低，暂不做确认框）。
- 书签**没有**做「本页已有书签」的角标（手机端有下拉手势 toggle + 页角标），当前交互是显式的两个入口。
- 漫画/有声阅读器未接书签（本次只要求小说阅读器）。
