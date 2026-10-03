# PROPOSAL-028：RSS 订阅源（对齐 legado-with-MD3 手机版「订阅」）

| 项 | 值 |
| :--- | :--- |
| 状态 | 已实现（待用户验收） |
| 提出 | 2026-10-03 |
| 分支 | `feat/rss-subscription` |
| 参照物 | `backup2026-09-30-PEPM00.zip → rssSources.json`（37,363 字节 / 8 条） |
| 手机版参照实现 | `legado-with-MD3/.../model/rss/RssParserByRule.kt`、`RssParserDefault.kt` |

## 1. 需求（用户原话）

> 「书源订阅改成 legado-with-MD3 这个手机版的这样，ui 维持 web 版的风格」

追问确认：**以上全要（一次做完整套）**，且**像手机一样可以导入仓库**；参照数据用备份包里的 `rssSources.json`。

## 2. ⚠️ 命名冲突：我们已有的「订阅」不是手机版的「订阅」

这是本需求唯一的高风险点，**开工前已与用户确认**（用户选择「确认：新表 `rss_source`/`rss_article`，路由 `/api/rss/*`，前端新页 `#rss`」）。

| | 既有 `source_subscription` | 本次新增 `rss_source` |
| :--- | :--- | :--- |
| 概念 | **书源订阅**：订阅一个书源 JSON 的 URL，定期拉取以更新书源表 | **订阅源**：一个能抓文章列表的源 + 抓到的文章 |
| 表 | `source_subscription` | `rss_source` + `rss_article` |
| 路由 | `/api/subscriptions` | `/api/rss/*` |
| 前端 | `#subscriptions`（导航文案「订阅」） | `#rss`（导航文案「**订阅源**」） |

⇒ 两者**并存，互不复用**。导航文案刻意分开，避免用户看到两个都叫「订阅」的入口。

## 3. 核心 User Stories

1. 作为用户，我能在「订阅源」页**导入**手机端导出的订阅源（粘贴 JSON / 选文件 / 从备份包导入），并看到源列表。
2. 作为用户，我能**刷新**一个源或全部源，把文章抓下来；**未读数**一眼可见；**只看未读**可切换。
3. 作为用户，我点开一篇文章会**打开原文并自动标记已读**；重复刷新**不会把已读又变成未读**。
4. 作为用户，当抓取失败时我**能看到失败原因**，而不是看到一个「0 篇文章」的空列表。
5. 作为用户，当某个源在手机版里只是「打开网页」（没有文章规则）时，我**被告知这一点**并能直接打开网页，而不是对着一个永远为空的列表发懵。
6. 作为用户，我的导出备份包能被手机端读回（`rssSources.json` 字段逐字对齐）。

## 4. 参照数据实测结论（决定实现方式）

字段并集 **31 个**，规则只有 5 个（`ruleArticles` / `ruleLink` / `ruleTitle` / `ruleImage` / `rulePubDate`）。

三条关键结论：

1. **规则大量是 `<js>`**，且用了 `baseUrl`、`cookie.getKey(...)`、`java.*`、模板字符串
   ⇒ 必须复用现有 Rhino 沙箱与 `java.*` 桥，**不能另写一套**。
2. **规则形态与书源同源**（`<js>…</js>$.data[*]` 链式、`路径@js:代码`、`##正则##替换`、JsonPath、CSS）
   ⇒ 优先抽出公用，而不是复制实现。
3. **8 条里只有 1 条有规则**。其余 7 条的 5 个规则字段全空 —— 它们在手机版里是
   「打开网页」的链接收藏，**根本没有文章列表能力**。

补充实测（与直觉不同、已核对手机端源码）：
- `sourceUrl` 允许**任意非空唯一串**（真实数据里有 `https://www.baidu.com/大灰狼番茄书荒广场`、
  `snssdk1128://user/profile/…`、`http@js:eval(String(cache.getFromMemory('yckdm')))`）；
- `redirectPolicy` 是**字符串枚举**（`ASK_CROSS_ORIGIN`），不是整数；
- JS 里的 `baseUrl` 是**当前列表地址**（分类的 `sortUrl` 对应值），**不是 `sourceUrl`**；
- `ruleLink` 用 `sourceUrl` 作 base 绝对化，而 `ruleImage` 用列表地址。

## 5. 非目标（Non-Goals）

明确**不做**，避免"看起来支持但静默失效"：

| 不做 | 原因 |
| :--- | :--- |
| `loginUrl` / `loginUi` 网页登录 | 无头环境无法执行 WebView 交互，且需要 `java.webView`/`java.digestHex` 等缺失 API |
| `injectJs` / `shouldOverrideUrlLoading` | WebView 注入与跳转拦截，无头环境无对应能力 |
| 文章**正文**抓取（`ruleContent`） | 参照数据里没有该字段；本服务的阅读器面向"书"，RSS 正文阅读是独立议题 |
| `ruleNextPage` 翻页 | 参照数据里**没有**该字段（手机端实体有，但这批数据没用），加一个恒为空的字段只会造成"字段存在=能力存在"的错觉 |
| 手机端实体其余十余个字段（`concurrentRate`/`coverDecodeJs`/`startHtml` 等） | 同上：31 列方案已覆盖备份里真实出现的全部字段 |
| Kindle 极简版 `simple/rss.html` 的三个 `/getRss*` 老接口 | 与本提案的路由风格完全不同（`isSuccess/data/errorMsg` 外壳、无 `/api` 前缀），且该页面从未有服务端实现。本轮不改它，保持现状 |

**"暂不支持"必须如实呈现**：无规则源在 UI 上标注「仅打开网页」并给出说明卡片，
而不是给一个永远空的列表（这是本项目反复强调的"静默失败比报错危险"）。

## 6. 技术选型 Why / Trade-offs

### 6.1 复用书源规则求值，而不是为 RSS 另写一套

**Why**：规则形态与书源同源（见 §4），书源侧已把这些坑逐个踩平（`<js>` 链式、`@js:` 后置、
`##` 替换、`id.x` 伪选择器、JsonPath 传真实 Map…）。复制一份必然漂移，且这些漂移**只表现为
"少几条"而不报错**，极难发现。

**Trade-off**：`RuleRunner` 需要暴露 3 个 `internal` 薄封装
（`evalNodeList`/`evalNodeValue`/`evalFetchRssList`）。只改可见性、零行为变更，
但确实扩大了一点点公开面。相比之下"复制 200 行求值逻辑"的长期成本更高。

### 6.2 用 31 列宽表，而不是把整个 JSON 塞进一列

**Why**：列名与手机端字段逐字对应，`sort/groups/enabled/未读统计` 都能走 SQL；
导出时字段顺序稳定。

**Trade-off**：手机端实体新增字段时需要加列（走既有 `migrate*` 模式）。
但本服务只需覆盖**备份里真实出现的字段**，多余字段"如实忽略"优于"假装支持"。

### 6.3 刷新失败落库 + 如实回报，而不是吞掉异常

**Why**：本项目已有专门部落知识——"静默丢数据比抛错危险得多"。RSS 场景尤其明显：
"上游 503" 与 "这个源确实没内容" 在旧式实现里都是"0 条"。

**Trade-off**：`rss_source` 多了三个运行态列（`last_success_at`/`last_attempt_at`/`last_error`），
且导入备份时刻意**不覆盖**它们（否则复制一份备份进去会把健康状态抹掉）。

### 6.4 订阅源标识走 query 参数，不进路径段

**Why**：`sourceUrl` 含 `/`（`https://feed.example.com/rss`），也有自定义 scheme 与中文串。
塞进 `{id}` 路径段会因 `/` 被当分隔符而**永远匹配不上**。实测就是这么踩到的（"明明存在却 404"）。

**Trade-off**：`PUT/DELETE /api/rss/sources?sourceId=…` 不如 `/sources/{id}` 直观，
但与本项目其它"按 sourceId 取数"的接口（搜索/目录/正文）口径一致。

## 7. 验收标准

见 [`docs/acceptance/ACCEPT-028-rss-subscription.md`](../acceptance/ACCEPT-028-rss-subscription.md)。
核心三条：

1. 从真实备份包导入 → 8 个订阅源全部进库，规则与 `jsLib` 逐字保真；
2. 有规则的源能抓到文章列表，且**重复刷新不把已读变未读**；
3. 抓取失败/无规则源都**如实提示**，不出现"静默 0 条"。

## 8. 已知限制（必须在验收时如实告知）

- **唯一有规则的真实源（大灰狼书荒广场）上游已不可达**：`api.langge.cf` 实测 DNS 解析到
  `198.18.0.88`（RFC 2544 基准测试网段，即被黑洞/污染），HTTP 返回 502。
  因此**无法用活源做端到端验收**；规则求值改用录制响应在单测里覆盖
  （`RssRuleParserTest`，与书源侧 `RuleRunnerTest` 同一套接缝）。
- 该源未登录时其规则会伪造一条「进入官网」的占位数据 —— 因此"列表非空"**不是**有效断言，
  有效断言是"标题不恒为『进入官网』"且 `lastError` 为空。
