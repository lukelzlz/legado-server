# SESSION-056 书签页封面链路 / 批量栏三连修 / 点书签跳转 / 统一 PR

- 日期：2026-11-01
- 分支：`feat/reader-bookmarks`（已 merge `fix/simple-eink-entry`，PR #84/#85 已关闭，待统一发一个 PR）
- 关联：`BookmarkCoverService.kt`、`Database.book_cover`、`BookmarksPage.tsx`、`main.tsx`、`ReaderScreen.tsx`、`ReplaceRulesPage.tsx`

## 用户需求（原话）

> 「如果在线的书没有封面那就根据书名作者去书源请求，导入时请求，如果超过五次没找到就标记，
> 在书签页批量管理旁边添加按钮请求封面，在这个页面点击封面就是手动设置和上传图片，
> 在删除完书签后，封面文件同步删除，在书架删除书时不删除书签，
> 只要书架或者书签任何一个或两者存在，那书籍的封面文件就保留」

> 「手动设置封面是像书架给书匹配封面一样，可以手动选书源搜出来的和上传本地图片」

> 「修复不论是规则批量管理时还是书签批量管理时悬浮栏都显示已选 {{count}} 项 0 / 24，
> 再书签页不是有两个批量管理吗，当打开了一个时打开另一个会把之前打开的关了，
> 在书签和规则的批量管理悬浮栏上添加取消按钮，在书签页点击书签时跳转到书籍书签的位置」

> 「先把github的pr撤了，等到现在的所有任务完成后统一发一个pr」「书签和url的合并1到一起防止后续还要rebase」

## 一、封面链路（服务端）

**为什么必须新开一张表**：书签按「书签表本身」存（不要求书在书架），所以「只在书签里有的书」根本没有
`book_shelf` 行可以挂 `cover_key`。

```sql
create table book_cover (
  book_name text not null,
  book_author text not null default '',   -- ⚠️ 不能可空：SQLite 的 PK/UNIQUE 把 NULL 视为互不相等
  cover_key text,
  missing_attempts integer not null default 0,
  last_attempt_at integer not null default 0,
  updated_at integer not null default 0,
  primary key (book_name, book_author)
);
```

**抓取优先级**（`BookmarkCoverService.resolve`）：
1. 书架里有同名（+同作者）的书 ⇒ 用它自己的 `sourceId/bookUrl/alternateSources` 走成熟的 `CoverBackfiller`；
2. 否则用「书名 + 作者」去已启用书源搜索（**限 3 个源**、只取第一条同名命中），拿它的详情页封面再走 `CoverBackfiller`。

**「超过五次没找到就标记」**：每次失败 `missing_attempts + 1`（抓到即清零），满 `COVER_MISSING_LIMIT = 5`
⇒ 概览里 `coverMissing = true`，批量请求默认跳过它；用户手动点「按书名重新获取」时传 `force = true` 仍可再试。

**接口**：
| 方法 | 路径 | 用途 |
| --- | --- | --- |
| POST | `/api/covers/refresh` | 批量补封面（`books[]` 为空 ⇒ 自动取「书签页所有无封面的书」）|
| GET | `/api/covers/candidates?bookName=&bookAuthor=` | 「手动设置封面」的书源候选（**只列不写**，限 12 条）|
| PUT | `/api/covers/book` | 挂载封面：`coverKey`（已上传）/ `coverUrl`（服务端代下，带该书源 header）/ `remove` |

**封面文件的生命周期**（严格按用户那句「只要书架或者书签任何一个或两者存在就保留」）：
- `removeBookshelf` 的孤儿判定同时看三处 ⇒ ① 别的书架行还用着这个 key；② 这本书还有书签；③ `book_cover` 里还有引用。
  三者皆无才算孤儿、才删文件。
- **移出书架不删书签**（`removeBookshelf` 不碰 `bookmark` 表）——书签靠「书签」页继续管理。
- 删书签（单条/批量/删整本）后统一调 `purgeIfUnused`：书架里也没有、书签也删光、`book_cover` 也清掉后才删文件。
- 「移除封面」= 清 `book_cover` + 清该书所有书架行的 `cover_key`，再按引用计数决定是否删文件。

## 二、封面链路（前端）

- 左栏标题旁新增 **「请求封面」** 按钮（批量管理左边），批量补抓并 toast 汇总 `fetched/failed/skipped/marked`；
- **点任意封面**（左栏缩略图、右栏大图）打开「设置封面」弹层：
  - **书源候选九宫格**（来自 `/api/covers/candidates`，点一张即挂载）；
  - **上传本地图片**（复用 `/api/covers/upload` → `PUT /api/covers/book`）；
  - **按书名重新获取**（`force = true`）、**移除封面**；
- 抓满 5 次仍无封面的书在左栏显示「无封面」标记（hover 解释「已停止自动补抓，点封面可手动设置」）。

## 三、批量栏三连修（用户实测报障）

1. **「已选 {{count}} 项 0 / 24」**：`common.selected` 的值带 `{{count}}`，而规则页/书签页/书源页都把它当**纯标签**
   用（后面紧跟 `<strong>{已选计数}</strong> / 总数`）⇒ 占位符原样漏出。改成纯标签（已选/已選/Selected/選択中），
   并同步 `i18n.test.ts` 的断言（该 key 被三处共用，所以一处改完全部修好）。
2. **两个批量管理必须互斥**：打开左栏批量 ⇒ 收起右栏并清空其选择；反之亦然（避免两条悬浮栏同时压在屏幕上）。
3. **批量栏加「取消」**：书签页两条工具栏 + 规则页批量栏都提供「取消」（退出批量模式并清空选择）。

## 四、点书签跳到书签位置

- `BookmarksPage` 右栏整行是按钮，点击回调 `onOpenBookmark({bookName, bookAuthor, chapterIndex, chapterPos})`；
- `main.tsx` 的 `openBookmarkTarget`：书签只按「书名+作者」关联，所以要先在书架里找回同一本书
  （同名优先、作者做二次校验），再 `openShelfItem(item, bookmark)`；**书不在书架时提示但保留书签**；
- `ReaderScreen` 新增 `startBookmark`，直接把书签偏移塞进既有的 `pendingBookmarkJumpRef`
  （开屏正文到手时换算成滚动比例，复用原来的对齐通道，不另写一套 scrollTo）。

## 五、测试与实测

| 项 | 结果 |
| --- | --- |
| `BookmarkCoverDbTest`（新增） | 5/5：5 次标记、概览兜底封面、移出书架保封面、孤儿回收、共享 key 不误删 |
| `BookmarkRoutesTest` | 通过（含书签 CRUD/批量/备份） |
| `bookmark-page.test.ts` | 19/19（批量栏占位符、互斥、取消、跳转、封面契约） |
| 前端全量 `run-all.ts` | 422/422 |
| `npm --prefix web run check` | 0 error |
| 真 Edge：`e2e-bookmark-browser.ts` | ALL BOOKMARK E2E CHECKS PASSED |
| 真 Edge：`e2e-simple-eink-browser.ts` | 4/4（SW 接管后 `/simple/#shelf` 仍是墨水屏版） |
| 真实服务封面联调（`_artifacts/verify-covers.mjs`） | 8/8：refresh 抓 1 张、上传→挂载→回读→取图 976KB→移除→回读为空 |

> ⚠️ 联调脚本的副作用教训：`verify-covers.mjs` 会把「概览第一本」的封面换掉再移除（清 `cover_key`）。
> 收尾必须用记下的原 key 重新 `PUT /api/covers/book` 恢复，并断言 `/api/covers/<原key>` 仍返回图片。
> 破坏性联调一律「先读原值、结束恢复」。

## 六、踩坑

1. **切到基于 main 的分支去改书签代码**：`fix/simple-eink-entry`（main 基线）里没有 `bookmark` 的 CRUD ⇒ grep 全空、
   编译缺符号。教训：动手前确认分支含目标特性代码。
2. **`extractFunctionBody` 靠花括号计数**：给 `openShelfItem` 加一个对象字面量类型的参数，直接把它截断导致
   `shelf-cover-timing` 假失败 ⇒ 参数类型抽成 `type BookmarkJumpTarget`。
3. **`.NET` API 在 pwsh 里按进程 CWD 解析相对路径**（不认 `cd`）⇒ 文件读写一律 `Resolve-Path`/绝对路径。
4. **移除封面不能只清 `book_cover`**：书在书架时封面权威在 `book_shelf.cover_key`，必须一起清（`clearCoverOnShelfRows`）。
