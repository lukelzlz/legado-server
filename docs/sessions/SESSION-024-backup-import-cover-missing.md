---
id: SESSION-024
title: 修复从 Legado 备份导入的书架封面全部加载不出来（三层根因）
date: 2026-09-26
author: Agent
tags: [backup-import, cover, bookshelf, regression, database]
---

# SESSION-024: 备份导入后书架封面全部加载不出来

## 1. 现象与排查推演 (Investigation & Analysis)

**用户现象**：从 Legado 备份包导入书架后，所有书的封面都加载不出来。

### 排查过程（用真实数据说话，不猜）

**第一步：先看真实数据库，而不是先读代码。**

用户数据目录 `a\data`：数据库 13MB + 备份包已导入，但 `covers\` 目录里**只有 1 个文件**。
这个「1」就是最强信号——435 本书却只有 1 张缓存封面。

**第二步：直接查库统计。**

| 指标 | 数值 |
| :--- | :--- |
| `book_shelf` 总条数 | **435** |
| `cover_key` 为空 | **434** |
| `cover_url` 有值 | **429** |
| `cover_cache` 行数 | **1** |

⇒ 429 本书有**完好的封面 URL**（番茄图床），但**一张都没下载**。

**第三步：查 API 实际返回了什么。**

`GET /api/bookshelf` 返回的 JSON 里 **`coverUrl` 与 `coverKey` 两个字段都不存在**。

## 2. 三层根因（缺一不可，必须同时修）

### 根因 A：导入器只存 URL，从不下载图片
`Database.importLibrary()` 的 SQL 里 `cover_key` 是**字面量 null**：

```sql
insert into book_shelf(...,cover_url,cover_key,...)
values(?,?,?,?,?,?,null,?,?,null)
on conflict(...) do update set
  name=..., cover_url=coalesce(...), last_read_at=..., completed=...
  -- 注意：on conflict 分支里根本没有 cover_key
```

备份包只带封面 **URL**、不带图片本体，而**没有任何代码去补抓**。于是 `cover_key` 永久为空。

### 根因 B：书架查询压根没 SELECT cover_url
`listBookshelf()` / `getShelfBookByUrl()` / `getBookshelf()` 三个查询的列清单里
**都没有 `s.cover_url`**，因此即使库里存了 URL，也到不了 API 响应。

⇒ 前端既没有 key、也没有 url，**没有任何可用的封面来源**。

### 根因 C：前端只认 coverKey
书架卡片与书籍管理弹窗都是：

```tsx
{item.coverKey ? <img src={api.cover(item.coverKey)} /> : <span>{item.name.slice(0,1)}</span>}
```

只走 `/api/covers/<key>` 本地缓存路径，**没有 URL 直连回退**，因此必然退化成文字占位符。

## 3. 最终落地的正确解法

按「最少改动、三层对齐」原则：

| 层 | 改动 |
| :--- | :--- |
| **导入（根治）** | `BackupImporter` 新增 `backfillCovers()`：导入后并发把封面抓成本地副本并回写 `cover_key` |
| **查询（透出）** | 三个书架查询补 `s.cover_url`，`BookshelfItem` 增 `coverUrl` 字段并映射 |
| **前端（回退）** | 新增 `resolveShelfCover(item)`：优先 `coverKey`，回退 `coverUrl` |

### 关键设计取舍（复杂度惩罚下的选择）

1. **补抓为什么要有上限**：435 本书串行抓取会让导入请求长时间挂住，因此
   `MAX_COVER_BACKFILL = 300` + 6 并发 + 单张 15s 超时，超出的靠前端 URL 直连兜底。
2. **补抓失败必须静默**：图床 404/超时/防盗链是常态，单张失败绝不能影响整次导入
   （已用测试固化：`cover fetch failure does not break the import`）。
3. **为什么不只做前端回退**：只回退 URL 会让封面**完全依赖外部图床**——
   图床改防盗链、下线、或用户离线阅读时就全瞎。本地副本才是可靠解，
   URL 回退只是「异步补抓完成前」与「补抓失败后」的过渡。

## 4. 暴力测试与回归对照

### 新增回归测试（`BackupImportTest`，3 用例）
1. `shelf items expose coverUrl after backup import` —— 断言 URL 能透出（覆盖根因 B）
2. `backfills cover cache and writes cover key when cover cache is provided`
   —— 断言 `cover_key` 回写为 64 位 sha256、缓存文件落盘、content-type 入库（覆盖根因 A）
3. `cover fetch failure does not break the import`
   —— 断言抓取失败时导入仍成功且保留 `coverUrl`（覆盖容错）

### 真实数据验证
用用户实际数据库逐条验证修复后的查询：

| 来源类型 | 总数 | 有 cover_url |
| :--- | :--- | :--- |
| 聚合 `data:` 源 | 420 | **416** |
| Android `content://` 本地书 | 14 | 12 |
| http | 1 | 1 |

⇒ **429/435 本书现在能拿到封面来源**（其余是 Android 本地书，本就没有网络封面）。

### 回归判定（按项目方法论：比失败集合而非数量）
| | 用例数 | 失败数 | 失败集合 |
| :--- | :--- | :--- | :--- |
| 干净基线 worktree | 199 | 53 | 基准 |
| 带修复 | **208** | **53** | **逐条一致** |

⇒ 零回归，净增 9 个通过用例；53 个失败全部为已记录的 Windows SQLite 占用既有失败。
前端 `npm run check` 0 错误、`tsx test/run-all.ts` **145/145 通过**、`vite build` 成功。

## 5. 顺带发现（未修，需独立立项）

**导入大书架会在启动时打满服务**：用户库里有 435 本书，服务启动时会对**全部**书籍
触发后台正文缓存任务。实测：

- `Application started` 后长时间打不出 `Responding at`，`/healthz` 持续超时 **4 分钟以上**；
- 数据库从 13MB 膨胀到 **256MB**，WAL 暴涨；
- 大量 `book cache failed: content://com.android.externalstorage...`（Android 本地书路径在服务端无意义，必然失败）。

根因是**启动时的全量缓存扫描没有并发限流、也没有跳过本地/无效书源**。
这与本次封面问题无关（本次改动前后行为一致），但对大书架用户影响很大，
建议单独立项：① 跳过 `content://` / `local://` 等非网络书源；② 加并发上限与错峰启动。

## 6. 沉淀的教训与部落知识 (Lessons Learned)

1. **「先查数据，再读代码」能极大缩短定位时间**。本次第一步就是查库统计，
   `435 本 / 434 无 key / 429 有 url / 1 张缓存` 四个数字直接锁定了问题全貌，
   避免了在代码里漫无目的地翻找。
2. **同一个功能缺陷常是「多层断链」**。本次三层（导入不抓、查询不选、前端不回退）
   **任何一层单独修都不完整**：只修前端→依赖外部图床；只修导入→历史数据仍坏；
   只修查询→仍无本地副本。修这类 bug 必须**沿数据流走完整条链路**。
3. **「备份包只带 URL」是迁移类功能的通用陷阱**。凡是导入外部备份，
   都要问一句：**「这份数据里有哪些字段是需要我们主动补全/物化的？」**
4. **新增字段要检查所有查询路径**。`cover_url` 一直存在于表里，
   只是没被任何一个书架查询选中——**表里有 ≠ 接口能拿到**。

## 7. 修改文件清单
| 文件 | 变更 |
| :--- | :--- |
| `server/.../BackupImporter.kt` | 新增 `backfillCovers()`（并发/上限/静默失败） |
| `server/.../Database.kt` | 三个书架查询补 `s.cover_url`；`toShelf()` 映射第 17 列 |
| `server/.../Models.kt` | `BookshelfItem` 增 `coverUrl` |
| `server/.../Application.kt` · `WebDavServer.kt` | 把 `coverCache` 透传给 `BackupImporter` |
| `web/src/api.ts` | `BookshelfItem` 类型增 `coverUrl?` |
| `web/src/main.tsx` | 新增 `resolveShelfCover()`；两处封面渲染改用之 |
| `server/src/test/.../BackupImportTest.kt` | 新增 3 个回归用例 |
