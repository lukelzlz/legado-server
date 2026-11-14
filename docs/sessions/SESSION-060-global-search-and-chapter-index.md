# SESSION-060 全局搜索（书架 / 目录 / 正文）+ 章节检索索引

- 日期：2026-11-14
- 分支：`feat/global-search`（基分支 `feat/db-multi-engine`，即 PR #94；**未 push**）
- 关联：`DatabaseSchema.kt`、`Database.kt`、`Models.kt`、`Routes.kt`、`web/src/GlobalSearchModal.tsx`、`web/src/main.tsx`、`web/src/ReaderScreen.tsx`、`web/src/api.ts`、`web/src/styles.css`、四个 i18n 文件
- 用户原话（需求原文）：「在书架页面添加一个搜索按钮，点开后会有一个弹窗出来，三种搜索方式，书架搜索（书架里有的书的作者和书名），目录搜索（书架里已经获取到目录的书里找），正文搜索（已经缓存下来的正文里搜索），记得数据库添加索引，sqlite和pgsql全兼容，小说阅读器中在上方悬浮栏目录按钮右边添加搜索按钮，这个只是正文搜索」

## 目标

本地库内的三路检索（**不跑书源、不出网**），外加阅读器里的「只搜当前书正文」。

| 模式 | 数据源 | 点结果 |
| :-- | :-- | :-- |
| `shelf` | `book_shelf` 的 `name` / `author` | 打开那本书 |
| `toc` | `book_chapter_index`（目录摊平） | 打开书并跳到该章 |
| `content` | `book_content_cache.content` | 打开书并跳到该章 + 高亮 snippet |

## 设计要点

### 1. 为什么新表 `book_chapter_index`

`book_toc_cache.chapters_json` 是整本目录的 JSON 大字段，`like` 检索章名等于全表 `LIKE` JSON 文本，
既慢又容易误命中（URL 里也含关键词）。摊平成「一行一章」才能建 `lower(title)` 表达式索引。
`book_url` 一律取**书架的 `book_url`**（不是 `toc_url`：同一本书的 tocUrl 有 `/toc`、带查询串等多个变体），
与 `book_shelf` 主键对齐后才能 join 出书名/封面。

### 2. 写入时机与清理

- `saveTocCache(...)`：upsert 完 `book_toc_cache` 后，用书架行反查真实 `book_url`
  （只做**精确匹配** `book_url` / `toc_url`），查到才整体替换该书的索引；查不到（书不在书架）**跳过** ——
  目录搜索本来就只针对书架里的书。
- `writeLocalBookCaches(...)`（本地 TXT/EPUB 导入）：两个 toc 键写完后索引一次。
- `removeBookshelf` / `switchBookshelf` / 批量删除：索引行随书架行一起删，避免"目录搜索命中已下架的书"。
- 启动回填 `backfillChapterIndex(limit = 50)`：扫「在书架里但索引一行都没有」的书，解析 `chapters_json` 写入。
  **限量 50 本/次、幂等、失败静默并记日志**（启动路径不许因为一本书的坏 JSON 起不来）。

### 3. 方言兼容（本次的红线）

DDL 与三条查询只用 SQLite 与 PostgreSQL **都支持**的写法：`lower()` 做大小写不敏感、`coalesce()` 兜空、
`limit ?` 传参、`on conflict … do update`。**没有** `rowid` / `insert or` / `pragma` / `strftime` / `changes()`
（`order by rowid` 让 PG 秒 500 的教训见 AGENTS.md）。索引是**表达式索引**：
`book_chapter_index(lower(title))`、`book_shelf(lower(name))`、`book_shelf(lower(coalesce(author,'')))` ——
两家都支持，现有生成器原样下发。回归用例 `GlobalSearchTest` 直接对 `SQLITE_DDL` 与生成后的 PG 语句做断言。

### 4. 接口路径：`POST /api/search/local`（**不是** `/api/search`）

需求写的是 `POST /api/search`，但**该路径已被「多书源全网搜索」占用**（`api.search`、`SearchRoutesTest`、
`/api/search/stream` 都在这一族）。Ktor 路由树里同一节点注册两个 handler 不报错，但第二个要么永不生效、
要么在第一个 `respond` 之后抛异常 —— 属于"看起来能用、某天突然 500"。因此本地库搜索落在
`/api/search/local`，语义也更准确（搜的是**本地库**）。**这是与需求原文唯一的有意偏差。**

### 5. 前端

- `GlobalSearchModal`：三 tab（`modes` 可收窄）、输入 300ms debounce + `AbortController` 取消旧请求、
  空/加载/错误三态、`createPortal(…, document.body)` + 显式 `theme-*`（脱离 `.app-shell` 后变量会丢）、
  ESC ≡ 关闭。
- 书架页顶栏「搜索」按钮 → 三 tab 弹窗；目录/正文结果复用 `openShelfItem(item, bookmark)` 那条
  通道跳章（内部 `matchBestChapter`「章名优先、下标兜底」）；书不在架 ⇒ 明确提示，绝不猜 item 去开书。
- 阅读器顶栏**目录按钮右边**加搜索按钮 → 同一个组件 `modes={['content']}` + `scope={sourceId, bookUrl}`，
  点结果 `matchBestChapter` → `changeChapter`。
- i18n 四语言新增 13 个 `search.*` key（`search.placeholder` 保留给书库搜索页，弹窗用 `search.modalPlaceholder`）。

## 证据（真跑，2026-11-14）

| 项 | 结果 |
| :-- | :-- |
| `npm --prefix web run check` | 0 error |
| `npx tsx web/test/run-all.ts` | **448 用例全绿**（新增 `web/test/global-search.test.ts` 13 条） |
| `./gradlew :server:test --tests GlobalSearchTest` | **6 用例 0 失败**（三模式命中 / scope 过滤 / 空查询 / limit 截断 / 索引替换 / 回填幂等 / DDL 方言守卫 / snippet 规则） |
| 全量 `:server:test` | 742 用例 57 失败 —— **全部是既有的 `FileSystemException`（Windows 删不掉被占用的 sqlite）**；`MultiEngineDbTest` 的「22 张表」断言因新增表改为 23，同 worktree 基线逐条比对：失败集合完全一致（ApiRoutesHttpTest 8/8、DatabaseLifecycleTest 11/11 都失败，基线同样失败） |
| PG 真机（8255 / `legado_search_smoke` / 192.168.1.68:5432） | `[db] 建表完成：**23 张表 / 27 个索引**`；重启第二遍无 DDL 报错（幂等） |
| PG 三模式 curl | `shelf` 200/1 条、`toc` 200/1 条（第二章 风暴将至 idx=2）、`content` 200/1 条（snippet 含「剑光如雪」） |
| PG scope 过滤 | 同关键词 + 正确 `sourceId/bookUrl` ⇒ 1 条；换成 `local://not-this-book` ⇒ **0 条** |
| PG 边界 | 空查询 200/0 条；`limit=1` ⇒ `truncated=true`；未知模式 400 `invalid_mode`；未登录 401 |
| PG JDBC 探针（`pg_indexes`） | `book_chapter_index_title_idx` / `book_shelf_name_ci_idx` / `book_shelf_author_ci_idx` 三条都在，且都是 `btree (lower(...))` 表达式索引 |
| 清理 | 8255 实例已停；`pg_terminate_backend` 后 `drop database legado_search_smoke` 成功（再连报 `database does not exist`） |

## 已知边界 / 未做

1. **正文搜索是 `lower(content) like lower(?)` 顺序扫描**：对"已缓存正文"这个子集够用，前端 debounce 挡住高频；
   真正的全文检索（PG `tsvector` / SQLite FTS5）会引入"两家表达式不一致"，本次刻意不做。
2. **启动回填每轮只补 50 本**：整架书（实测 400+）一次全解析会推迟 `Application started`；剩下的由下次启动继续（幂等）。
3. **`toc` 索引只在"书架里的书"上建**：书不在书架时 `saveTocCache` 不写索引（无法确定 `book_url` 就不硬塞）。
4. 未做「搜索历史 / 结果分页」：需求未提，`limit` 夹在 1..100、超出用 `truncated` 提示细化关键词。
