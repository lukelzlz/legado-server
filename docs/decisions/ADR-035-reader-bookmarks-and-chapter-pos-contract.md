# ADR-035 阅读器书签：复用 `bookmark` 表与「章内字符偏移」定位

- 状态：已实施（feat/reader-bookmarks）
- 日期：2026-11-01
- 关联：[ACCEPT-036](../acceptance/ACCEPT-036-reader-bookmarks.md)（验收手册）、[SESSION-054](../sessions/SESSION-054-reader-bookmarks.md)（推演与踩坑）

## 背景

用户要求：小说阅读器**底部悬浮栏**加「添加书签」，**顶栏设置按钮前面**加「书签」按钮打开书签列表，
列表里能跳转/修改/删除，并且**备份的导入导出也要有**，库里有**独立的 bookmark 表**，字段参考手机端备份的
`bookmark.json`。

约束（都来自当前仓库已定的事实）：

1. `bookmark` 表早就存在（备份导入/导出 `bookmark.json` 用它），字段：
   `book_name, book_author, chapter_index, chapter_name, chapter_pos, book_text, content, created_at`，
   唯一键 `(book_name, book_author, chapter_index, chapter_pos)`。
2. 书签**按书名 + 作者关联**（与开源阅读一致），不挂 `sourceId`/`bookUrl` —— 换源、换 url 后书签必须还在。
3. 备份导出/导入已有实现（`BackupExporter.bookmarkEntry` / `BackupImporter.parseBookmarks`），
   本次**不新增第三条路径**，只让新书签能通过同一张表进备份。
4. 阅读器内部的进度语义是**比例** `scrollPosition`（0..1，相对本章正文区块的滚动范围）；
   而 `bookmark.json` 的 `chapterPos` 是**章内字符偏移**（开源阅读 `Bookmark.chapterPos` 的语义）。

## 决策

### 1. 存储：沿用现有 `bookmark` 表，字段语义逐条对齐手机端

`chapterPos` 一律存**章内字符偏移**（不是比例）。这样：
- 备份导出后手机端能认（同一列、同一语义）；
- 手机端导入进来的书签也能按同一条公式落点。

比例 ⇄ 偏移的换算收口在 `web/src/bookmarkPos.ts`（纯函数、可单测）：
`offset = round(position × 本章正文字符数)`、`position = offset / 章正文字符数`。

**为什么可以线性映射**：中文正文的行高基本只由字数决定，标题与段间距在各章之间是同一套排版
⇒ 两者近似成正比。更关键的是**写入与读取用同一条公式**，所以自己加的书签跳回去是同一屏
（实测：滚到 `scrollY=598` 加书签 → 回顶部 → 点该条 → 回到 `scrollY=598`）。

### 2. 跳转：落在「正文到手的那一刻」换算，不另写定位逻辑

书签里的偏移换算成比例需要**目标章的真实字数**，而 `changeChapter()` 时不掌握目标章正文。因此：

- **跨章跳转**：把 `{chapterIndex, chapterPos}` 挂进 `pendingBookmarkJumpRef`；
  `applyContent()` 拿到正文后换算成比例，再交给**既有的「恢复进度」对齐通道**
  （`initialPagePositionRef` + 重试对齐 effect）。
- **同章跳转**：正文已在手里，直接换算并 `setBookmarkJumpTick(tick+1)` 让对齐 effect 重跑。

**为什么不自己写一套 scrollTo**：滚动流里相邻章是异步挂载的，目标章会被后挂载的章节推下去；
既有对齐 effect 有「重试到真正到位 + 读者一动手就让路」的守卫（踩过多次坑）。
复用它，就自动获得这些守卫。

### 3. 章节对齐：章名优先、下标兜底

书签存的是加书签那一刻的章名与序号。换源/目录重排之后序号会漂，因此跳转与列表展示都走
`matchBestChapter(chapters, bookmark.chapterName, bookmark.chapterIndex)`（与换源同一套匹配）。

### 4. 去重：先按 `coalesce(book_author,'')` 更新，没命中才插入

⚠️ **不能**用 `insert ... on conflict(book_name, book_author, ...) do update` 一把梭：
SQLite 的 UNIQUE 索引把 **NULL 视为互不相等**，而本地书/无名作者的书签 `book_author` 是 NULL
⇒ 冲突永远不命中，连点 N 次就插出 N 条 `chapterPos` 相同的重复行（实测 7 次得 7 条）。
`Database.saveBookmark` 改为「先 update（where 用 `coalesce(book_author,'')=coalesce(?,'')`），
`changed()==0` 才 insert」，NULL 与空串都能命中。

`created_at` 只在首次插入时写入（书签的时间是"什么时候加的"，不是"最后改的"）。

### 5. 交互：底部悬浮栏加、顶栏开列表，弹窗只有一层

- 底部悬浮栏（`mobile-reader-nav`）从 4 格改 5 格：目录 / 换源 / 朗读 / **添加书签** / 设置；
- 顶栏在**设置按钮前**插入书签按钮，打开 `BookmarkListModal`；
- 弹窗内：点条目跳转、行内编辑（原文 + 备注）、行内确认删除、以及「添加书签（当前位置）」。
- **不嵌套第二层 `modal-backdrop`**：编辑/删除确认都是列表内的行内状态
  （嵌套遮罩会因动画/层叠上下文出现幽灵穿透，仓库里已记过这条）。

### 6. 手机端顶栏容纳第三颗按钮

原来 720px / 420px 两个断点把右侧操作区写死成 `80px` / `76px`，只塞得下 2 颗 36px 按钮。
新增按钮后改为 `auto` + 标题 `ellipsis` 收缩（`grid-template-columns: 80px minmax(0,1fr) auto`）。

### 7. 「书签」页：书签是**全量落库**的，与书架无关

用户第二条要求（2026-11-01）原话：

> 「一股脑全存到表里，不论有没有这本书，不论是本地的还是什么的，在主页上边像规则一样单开一页叫书签，
> 界面仿照规则的页面，左面的列表是书籍名字作者和封面，右边是选中的书的书签，
> 如果某本书的书签在右边删完了，那就左边列表的书籍就消失，左右边都要批量管理」

据此确定三件事：

1. **导入不再按书架过滤书签**（`BackupImporter`）。旧实现会丢掉"书不在书架"的书签并报
   `bookmarksSkipped`；现在全部落库，该字段连同前端「跳过 N 条」提示一起删除。
   ——理由：既然独立页面以 `bookmark` 表为唯一数据源，被过滤掉的书签就再也没有入口了。
2. **左栏是服务端聚合出来的**（`GET /api/bookmarks/overview`）：
   `bookmark` **LEFT JOIN** `book_shelf`（按 `name` + `coalesce(author,'')`）取封面，
   按「最新一条书签时间」倒序。书不在书架 ⇒ `coverKey/coverUrl` 为 null，前端用 `BookCover`
   回落到首字占位。**聚合口径不变则"删完即消失"自动成立**——不需要前端再算一遍。
3. **两侧批量删除都走一个接口**（`POST /api/bookmarks/delete-batch`）：
   `ids` 删单条、`books` 删整本（书签删光后该书自然从左栏消失），返回**真实删除行数**。

界面复用「替换净化规则」页的整套 class（`rules-page-container` / `rules-sidebar` / `rules-list` /
`rule-list-card` / `source-batch-bar`），用户要求"仿照规则的页面"，复用后两页的批量手感完全一致。

⚠️ 一个必须知道的连带影响：规则页在 `max-width: 768px` 下把 `.rules-content-area` 设成
`display: none`（它靠卡片内手风琴）。书签页是「左书右签」，若照抄这条规则手机上就**看不到书签列表、
也就删不掉单条**。因此书签页额外加了 `.bookmarks-page` 类，在窄屏把它改回 `display: block`。

## 未采纳的方案

- **新建独立表**：`bookmark` 表已存在且被备份链路复用，再开一张只会让导入导出出现两条落库路径。
- **「书签」页只显示书架上的书**：与用户"一股脑全存、不论有没有这本书"直接冲突，且导入被过滤后那些书签将永远无入口。
- **左栏在前端用全量书签自己聚合**：需要把整表书签拉到前端再分组，书签多时首屏变慢；服务端一条 GROUP BY 就够。
- **删完后前端自己把书从列表里摘掉**：容易与真实库不一致（部分失败时"看起来删了实际还在"），一律重拉聚合结果。
- **`chapterPos` 存比例**：与手机端语义不一致，导出的备份在手机上会跳错位置。
- **把 `bookUrl` 加进关联键**：换源即丢书签；开源阅读的 `bookmarks` 表也只用书名+作者（`bookUrl` 仅用于跳转校验）。
- **用百分比 + DOM 段落测量双向换算**：能更精确，但要在滚动/分页两套渲染里各写一份测量逻辑，
  且跨章时无 DOM 可测；收益是"误差小一个段落"，成本是复制一份易碎的几何代码。

## 后果

- 书签在 `bookmark.json` 里天然可导出导入；实测导出 zip 的 `bookmark.json` 含新加条目（字段与顺序照旧）。
- 作者为空的书重复点同一位置不会再刷出重复行。
- 落点精度为一个段落以内；跨端导入的书签同样按比例落点。
- 已知边界：换源后章名完全对不上、且原序号越界时，会退到「最后一章」；
  与手机端一致，不额外弹「仍要跳转？」确认框（当前实现直接跳，成本更低）。
