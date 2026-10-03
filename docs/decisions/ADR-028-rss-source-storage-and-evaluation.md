# ADR-028：RSS 订阅源的存储、求值与刷新失败语义

| 项 | 值 |
| :--- | :--- |
| 状态 | 已接受 |
| 日期 | 2026-10-03 |
| 关联 | [PROPOSAL-028](../proposals/PROPOSAL-028-rss-subscription.md) |
| 分支 | `feat/rss-subscription` |

## 背景

需要把 Legado 手机版的「订阅」（RSS 订阅源）搬到服务端。三个约束决定了设计：

1. 本服务已有的「订阅」是**书源订阅**（`source_subscription`），与 RSS 语义完全不同；
2. 参照数据里规则**大量是 `<js>`**，与书源规则同源，书源侧已把这些坑踩平；
3. 参照数据 8 条里**只有 1 条有规则**，其余 7 条是"打开网页"的链接收藏。

## 决策

### D1：新建 `rss_source` / `rss_article`，不复用 `source_subscription`

两者概念、表、路由、前端页面全部独立。导航文案分开为「订阅」（书源订阅）与
「**订阅源**」（RSS），避免同级出现两个同名入口。

**被否方案**：在 `source_subscription` 上加类型列区分 —— 会让一个表承担两种完全不同的
生命周期（书源订阅按 URL 拉 JSON；RSS 源有 31 个字段与独立的文章表），且既有前端页面
必须跟着改写，风险与收益不成比例。

### D2：求值复用 `RuleRunner` / `JsSandbox`，只加 3 个 `internal` 接缝

新增 `RssRuleParser` **只做编排**（按手机版顺序串联），不复制任何求值实现：

| 新增接缝 | 作用 |
| :--- | :--- |
| `RuleRunner.evalNodeList(body, rule, baseUrl)` | 列表规则求值（`<js>` 自动走 `jsListNodes`） |
| `RuleRunner.evalNodeValue(node, rule, body, baseUrl)` | 单条取值（Html/Json、`<js>`、`@js:`、`##` 全部分派） |
| `RuleRunner.evalFetchRssList(url, header, sourceId, db)` | 列表请求取数（解析 URL 内联选项 + 叠加源 `header`） |

第三个刻意把 `UrlOptions`（private 数据类）留在 `RuleRunner` 内部，避免为传参扩大可见性。

**被否方案**：在新文件里重写 JsonPath/CSS/`<js>` 求值 —— 书源侧的兼容分支
（`id.x` 伪选择器、`!a:b:c` 多下标、`路径@js:`、`##` 双路径）是多次线上排障的产物，
复制必然漂移，而漂移**只表现为"少几条"而不报错**。

### D3：31 列宽表，列名与手机端字段逐字对应

`source_url` 为主键，**不做 URL 校验**（真实数据里有中文自定义串、`snssdk1128://`、
`http@js:…`）。运行态另加 `last_success_at` / `last_attempt_at` / `last_error` / `updated_at`，
**不参与备份导出**。

`redirect_policy` 存**字符串枚举**（`ASK_CROSS_ORIGIN` 等），不是整数 ——
手机端 `RedirectPolicy` 是 enum，Room 默认值就是字符串。

### D4：`rss_article` 的 upsert 保留 `read` 标记与旧值

`(source_url, link)` 唯一。冲突时：
- `read` 列**不更新** —— 刷新一次就把读过的文章重新标成未读，是最不可接受的回归；
- 可空字段用 `coalesce(excluded.x, rss_article.x)` —— 本次没取到就保留旧值，不要用 null 冲掉。

新增条数**先查后写**判定，不用 `changes()`（SQLite 的 `on conflict do update` 在 update 分支
同样报告 1 行受影响，见 AGENTS.md）。

### D5：刷新失败落库 + 如实回报，绝不退化成"0 篇"

- 成功：写 `last_success_at`、清空 `last_error`；
- 失败：写 `last_error` + `last_attempt_at`，**不写** `last_success_at`；
- 接口返回 `failed` + `message`，批量刷新返回逐源结果数组（单源失败不中断其余）。

并发限流用**一个全局 `Semaphore(4)`** 罩住所有源，而不是每源各自限流
（AGENTS.md 教训：单任务内部限流 × 无界任务数 = 没限流）。

### D6：订阅源标识走 query 参数，不进路径段

`GET/PUT/DELETE /api/rss/sources?sourceId=…`、`POST /api/rss/sources/refresh?sourceId=…`。

**原因（实测）**：`sourceUrl` 含 `/`，塞进 `{id}` 路径段会被当作路径分隔符 ⇒ 永远 404
（"明明存在却匹配不上"）。与本项目其它按 `sourceId` 取数的接口口径一致。
文章 id 是自增整数，拼路径安全。

### D7：无 `ruleArticles` 的源走默认 RSS/XML 解析，并如实标注

- 服务端：`RssRuleParser` 支持 RSS 2.0（`rss>channel>item`）、Atom（`feed>entry`）、
  RDF（`rdf:RDF>item`）三种容器，用 **Jsoup XML 解析器**（不解析外部实体，规避 XXE）。
- 解析不出条目 ⇒ **如实返回空表**，不伪造数据。
- 前端：这类源标注「仅打开网页」，给说明卡片 + 「打开网页」按钮。
  自定义 scheme 浏览器打不开时也如实提示。

## 后果

**正面**
- 备份包 `rssSources.json` 与手机端双向可读（字段逐字对齐，含"必写恒写、可选省略"的键分布）。
- 抓取与阅读语义对齐手机版（`baseUrl` 取列表地址、`-` 前缀反转、title 空丢弃、
  link 用 `sourceUrl` 绝对化）。
- 失败可见，不会出现"静默 0 条"。

**负面 / 代价**
- `RuleRunner` 的 `internal` 面多了 3 个方法。
- 导出文件清单从 6 个增至 7 个，**任何"恰好 N 个文件"的断言都要同步**（已同步
  `BackupExporterTest` 与 `WebDavRoutesTest`）。
- 手机端实体新增字段时需加列；本服务只覆盖备份里真实出现的 31 个字段。
- 无规则的 7 个源在服务端**没有文章可抓**（它们本来就没有 feed），这是事实而非缺陷。

## 顺带修掉的两个沙箱缺陷（本任务定位）

两者都会让**真实 RSS 源静默 0 条**，且都属于"语义对齐"而非"补别名"：

1. **`java.log(msg)` 必须回显入参**（手机版 `JsExtensions.kt` 的 `return msg`）。
   生态惯用法是 `java.ajax(java.log(url))`；旧实现返回 `undefined` ⇒
   `ajax("undefined")` ⇒ `parseUri` 抛错 ⇒ 被 `eval` 的 catch 吞掉 ⇒ 规则整体返回 null
   ⇒ **0 条且无任何报错**。真实源「大灰狼书荒广场」的 `ruleArticles` 正是这个写法。
2. **补 `cache` 桥**（`get`/`put`/`getFromMemory`/`putMemory`）。真实数据的 `sourceUrl` 与
   `header` 规则都依赖它，缺失会让这类源静默失效。落库回读刻意走
   「Java 容器 → `toJsValue` → `JSON.stringify` → 沙箱内 `JSON.parse`」，
   因为实测 `NativeJSON.parse` 在该调用形态下**直接返回原字符串**（"还原"静默失效），
   而只还原成 Java 壳时 `String(obj)` 会抛「未找到对象默认值」。

## 参考

- `server/src/main/kotlin/io/legado/server/RssRuleParser.kt`
- `server/src/main/kotlin/io/legado/server/RssSourceCodec.kt`
- `server/src/main/kotlin/io/legado/server/RssService.kt`
- `server/src/test/kotlin/io/legado/server/RssRuleParserTest.kt`（13 用例）
- `server/src/test/kotlin/io/legado/server/RssRoutesTest.kt`（11 用例）
- `server/src/test/kotlin/io/legado/server/BackupRssSourcesImportTest.kt`（真实备份 8 条逐字段 + 幂等）
