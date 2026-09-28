---
id: SESSION-HIST-009
title: 历史会话归档：从零克隆起步、聚合书源（大灰狼）全链路兼容攻关与内置 WebView 登录代理
date: 2026-09-12
type: historical_archive
original_conversation_id: 8cc12da2-c7df-4e45-b195-29f3ab271b4e
source_workspace: E:\Desktop\yuedu web
frames: 10079
user_prompts: 29
tool_calls: 452
---

# 历史推演归档：项目起源与聚合书源兼容攻关

> **来源会话**：`--E-Desktop-yuedu~0020web-- / session-8cc12da2-c7df-4e45-b195-29f3ab271b4e`（2026-09-12 单日长会话，10,079 帧 / 15,812 行 / 29 轮用户诉求 / 452 次工具调用）
> **会话标题**：「帮我下载 git 并克隆 github.com/lukelzlz/legado-server」→「下载 Git 并克隆 legado-server」
> **历史地位**：这是本仓库**事实上的一次性奠基会话**。今日 `master` 上的登录代理、书源兼容、沙箱 API 补全、`data:` 载荷协议等核心能力，都能追溯到这一天的推演。
> **原始工作区**：`E:\Desktop\yuedu web`（非当前 `E:\Desktop\1234`），历史命令中的路径需按此理解。

---

## 0. 为什么这篇归档重要

本次 `/doc-init` 之前，仓库的 Session 编年史从 **2026-09-14** 起算（`SESSION-HIST-001` 标称 08-15，实际索引里最早已有内容）。而 **09-12 这一天发生的事从未被归档**——它恰好是理解「为什么服务端长成今天这样」的关键缺失环节。

该会话同时是**环境基线的最早来源**：用户当时此机器上**连 Git 都没有**，是从 `winget install Git.Git` 开始，一路装工具链、克隆仓库、下载预编译 JAR、启动服务的。

---

## 1. 起点：裸机环境搭建（用户诉求第 1 轮）

> 「帮我下载 git 并克隆 github.com/lukelzlz/legado-server」

- `git --version` / `winget --version` 均不存在 → `winget install --id Git.Git -e --source winget --accept-package-agreements --accept-source-agreements --disable-interactivity`。
- 随后用户问「是否需要梯子」，助手**实测下载速率**（对 `objects.githubusercontent.com` 做 5 秒限速测速）而非主观判断，确认必须走代理。
- 代理地址固定为 `127.0.0.1:7890`，Gradle 需显式传 `-Dhttp.proxyHost/-Dhttp.proxyPort/-Dhttps.proxyHost/-Dhttps.proxyPort`。

**新增部落知识（此前未入库）——下载方式的吞吐量差异极大**：

| 方式 | 实测速率 | 结论 |
| :--- | :--- | :--- |
| `Invoke-WebRequest`（IWR） | **4 ~ 11 KB/s** | 会被缓冲进内存，**禁止用于大文件** |
| `HttpWebRequest` 流式下载 | **~5.2 MB/s** | 快 **500 倍**，大文件首选 |
| `ghfast.top` 等 GitHub 镜像 | 11 KB/s | 不可靠 |

> 27.6 MB 的 `legado-server.jar`：IWR 需 **约 2 小时**，流式仅需 **数秒**。会话中连续两次被 IWR 误导（先 11 KB/s、后 4 KB/s），第三次才切流式成功。

- **JAVA_HOME 版本漂移史**：会话当时用的是 `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`，而 `AGENTS.md` 现在记录的是 `Amazon Corretto jdk21.0.12_9`。用户中途还贴出 `C:\Program Files\JavaCmdAlias`（各种 java7/8/21 切换 bat）。**结论：该机器上 JDK 路径是高频变动项，每次构建前显式设定 `JAVA_HOME` 是唯一稳妥做法**——这正是 `SESSION-HIST-008` 与 `AGENTS.md` §3 中「JAVA_HOME 可能失效」警告的历史来源。

---

## 2. 真正的深水区：让服务端兼容用户的真实书源

用户诉求（第 9、10 轮）：

> 「帮我完善这个项目，让他兼容我导入的书源」→「小说部分兼容即可」

目标书源是 **`大灰狼融合VIP5.0`（🍅大灰狼聚合5.8.20 完全版）**——一个**极端重度的聚合源**，也是此后所有兼容性排错的试金石：

| 字段 | 规模 / 特征 |
| :--- | :--- |
| `bookSourceUrl` | **自定义中文标识**（不是 URL） |
| `loginUrl` | **63,292 字符的 JS**（不是网址！） |
| `jsLib` | **33,966 字符**工具库 |
| `searchUrl` | 1,251 字符的 `<js>` 规则 |
| `content` 规则 | 10,979 字符的 `<js>` 规则 |
| `chapterList` | 6,629 字符的 `<js>` 规则 |
| `exploreUrl` | 13,453 字符 |
| `loginUi` | 22 项 |

这种书源把 Legado 生态的「隐性契约」全部压满，是**天然的一致性测试套件**。

### 2.1 排错方法论的建立：用「登录动作」当通用 JS 探针

会话中发现 `/debug` 端点只返回计数，看不到中间值。于是把 **`login-action` 端点当作任意 JS 执行通道**来用：

```
action = `java.toast(String(java.ajax('https://...')).substring(0,200))`
```

`toastMessages` 会随 API 响应返回 ⇒ **无需改代码、无需重启，即可在真实书源上下文中打印任意表达式的值**。

> **这是整场会话最高价值的排障技巧**，也是后来 `login-action` 能执行任意脚本这一「特性」被反复利用的原因。

---

## 3. 五处根因（现象 → 根因 → 修法）

### 3.1 `Line exceeds limit of 8192 characters` —— 用户报的那个错，底下叠着三个 bug

**用户现象**：点登录弹窗里的 🖥 按钮 → 报 `Line exceeds limit of 8192 characters`，且所有按钮都没反应。

**① `JsSandbox` 静默吞掉全部 JS 异常**——这是「按钮全部失灵」的原因

```kotlin
} catch (e: Exception) {
    return null   // ← 无声无息，前端只看到 success=true
}
```

17 个 `loginUi` 按钮调用后**全部返回 `success=true` 却毫无动作**，用户完全无从判断。修法：异常写入 `lastError` 并回传前端，以红色 toast 显示。

> 此坑与 `AGENTS.md` 中「`eval` 成功时必须清空 `lastError`」是同一族问题的前半段——先让它不静默，再让它不残留。

**② `JavaImporter` 未定义，导致 63K 脚本整体中断**

`jsLib:630`：

```js
var javaImport = CompatibilityUtils.safeImport(new JavaImporter());
with (javaImport) {
  /* 整个工具函数库都定义在这个 with 块里！ */
}
```

`JavaImporter` 未定义 → 这行抛 `ReferenceError` → `with` 块**从未执行** → 块内所有工具函数（`getArguments` 等）**根本没被定义**。

关键细节（曾把定位带偏）：报错位置是 `rule.js#630`，但那是**拼接后脚本**的行号（`jsLib + loginUrl + action`）；`jsLib` 单独看，第 630 行才是真正的 `JavaImporter`。

修法：为 `JavaImporter` 提供**安全空替身**——`importClass`/`importPackage` 均为 no-op，构造出可被 `with` 包裹的对象，但绝不暴露任何 Java 能力（防 RCE）。`Packages` 未定义时 `hasJavaClass` 会抛错并被自身 `try/catch` 吞掉，属安全路径。

**③ `loginUrl` 是 6.3 万字符的 JS，却被当成网址用**——8192 报错的**直接来源**

服务端把 `loginUrl`（63292 字符）当作起始地址返回前端 → 塞进 iframe → 请求行超 8192 → Ktor CIO 直接拒绝。

修法：`loginUrl` 仅在 `startsWith("http")` 时才当网址；并新增「书源内容中第一个真实网址」作为兜底起始地址。

**连带修掉：`data:` URL 超长**
`教程`/`书源更新`/`设置中心` 等按钮传的是 `data:text/html;base64,...`（动辄 5 万字符），同样撑爆请求行。修法：**新增服务端内置页面托管**，`data:` 载荷经 POST 上传后用短 key 引用（即 `AGENTS.md` 中「巨型 Data URL 必须转由服务端托管」条目的由来）。

**验证结果**：17 个 `loginUi` 按钮逐个实测 → **15 正常 / 2 为站点自身 400**；`jc()` 的 4646 字符 data URL → 页面 URL 仅 **176 字符**，HTTP 200。

---

### 3.2 搜索 0 结果 —— 两个独立根因（`jsLib` 缺失 + JSON 被 `toString`）

用户诉求（第 15 轮）：「搜索不到书籍，可能搜索还没兼容书源」

**根因 A：`searchUrl` 的 JS 求值没有注入 `jsLib`**

`RuleRunner.search()` 第 49-52 行直接 `jsSandbox.eval(jsCode, ...)`，**不拼接 `jsLib`**；而同一文件里 `parseLoginUi`(168)、`executeLoginAction`(213)、`checkLoginStatus`(267) **都拼了**。

结果：`searchUrl` 调用的 `getArguments(source.getVariable(), 'server')`（定义在 `jsLib` 内）抛 `ReferenceError` → 被静默吞掉 → 返回 null → 报 `searchUrl JS 计算未返回有效地址`。

`NodeValue.value` 的 `<js>` 规则路径同样缺 `jsLib`。

**修法（架构级，而非打补丁）**：让 `JsSandbox` 自身感知当前书源的 `jsLib` —— 把 `jsLib` 放进 `JsExecutionContext`，`eval` 时若 `execContext.jsLib` 非空则自动前置，并新增 `jsSandbox.withSourceContext(...)` 包裹各公开入口。

> 选择理由：**一次修复所有调用点**，且与 Legado 语义一致（`jsLib` 对书源的所有规则全局可见）。逐个改调用点既遗漏又会反复回归。

**根因 B：JSON 条目被 `toString()` 后传给 `<js>` 规则——`result.book_id` 恒为 undefined**

```kotlin
mapOf("result" to intermediate, ...)
// intermediate = html?.html() ?: json?.toString() ?: rawBody ?: ""
```

JsonPath（Jayway）返回的是 `net.minidev.json.JSONObject`（继承 `HashMap`），`toString()` 得到 **Java Map 格式** `{book_id=xxx, source=yyy}`——**不是 JSON**。书源 JS 里 `result.book_id` ⇒ undefined ⇒ `bookUrl` 生成失败 ⇒ 条目被 `mapNotNull` 丢弃 ⇒ **204 条全丢，0 结果**。

修法：传入**真实 JSON 对象**（Map）而非字符串，交由沙箱 `toJsValue` 递归转成 `NativeObject`。

**根因 C（在 A、B 修完后才浮现）：`valueJson` 不处理 `##` 正则替换后缀**

`ruleSearch.name = "$.book_name##（别名：.*?）"`。`valueJson`（748-755 行）只处理 `{{}}` 模板，随后直接 `readJson(template)` ⇒ JsonPath 把 `##（别名：.*?）` 当作路径一部分 ⇒ **解析异常 ⇒ 被 `runCatching` 吞掉 ⇒ name 为 null ⇒ 204 条再次全丢**。

而 `##` 语义**在 HTML 路径（681-682 行）是被正确处理的**，只有 JSON 路径漏了。

修法：抽出共享的 `applyRegexReplace(value, rule)`（Legado 语义：`##正则##替换` 成对出现，替换串缺省为空串），HTML 与 JSON 两条路径共用。

**验证结果**：搜索 **204 条结果**，书名/作者/`data:;base64` 形式的 `bookUrl` 全部正常。

---

### 3.3 打开书籍失败 —— `data:` 载荷协议

`bookUrl` 形如 `data:;base64,<base64 JSON>,{"type":"qingtian"}`——**bookUrl 本身就是 data: URL**，是聚合源在「搜索 → 详情 → 目录 → 正文」之间传递参数的载体，无法直接 HTTP 请求。

书源用 `ruleBookInfo.init`（初始化规则）解包：

```js
if (String(baseUrl).startsWith("data:")) {
  let res = JSON.parse(java.hexDecodeToString(result));
  ...
}
```

**修法**：`declarativeDetails` / `chapters` / `content` 三处统一支持——若 URL 是 data: 载荷则**解码后作为规则的 `result`**，而非发起网络请求。

**又一坑：合法性校验（`nodes()` 不支持 `<js>` 列表规则）**
`ruleToc.chapterList` 是 `<js>` 规则，但 `nodes(body, rule)` 只判断「以 `$` 开头 → JsonPath，否则 → CSS 选择器」⇒ `<js>` 被当成 CSS 选择器 ⇒ 目录 0 章。修法：`nodes` 显式支持 `<js>` 列表规则求值后再取项。

**验证结果**：详情全字段正确（`name`=十日终焉、`author`=杀虫队队员、真实 `intro`、番茄真实封面图）；目录 **1496 章** 正确。

---

### 3.4 `URL 解析失败: Illegal character in query` —— OkHttp 宽松 vs `java.net.URI` 严格

沙箱 `java.ajax` 调用形如下述 URL：

```
https://api.langge.cf/detail?...&variable={"custom":""}
```

URL 中含**未转义的 `{`、`"`、`}`**。Android 侧 Legado 用 OkHttp（**自动编码非法字符**，宽松），而服务端用 `java.net.URI(...)`（**严格遵循 RFC 3986**）⇒ 抛 `URISyntaxException`。

**修法**：在 `RuleRunner.parseUri` 中对 query/fragment 的非法字符做 `percentEncodeIllegal` 宽容编码（与 `WebViewProxy` 中同一套手法）。

> **这是"通杀型"修复**：Legado 书源把未编码 JSON 塞进 URL 是**常态**，此坑不修会持续以「莫名其妙的请求失败」形式出现。

**定位手法复盘**：先明确「上游 `curl` 直连 HTTP 200、数据完好」，再用 §2.1 的 toast 探针在**沙箱内**复现同一请求，从而把问题锁定在本项目的 URI 解析层，而非网络或上游。

---

### 3.5 正文为空 —— `qread` 别名污染设备指纹分支

正文规则（10,979 字符）内通过 `checkEnv()` 判定客户端环境：

```js
try { java.qread(); return "轻阅读"; } catch (e) { /* → 继续判定为改版 */ }
```

Legado 中 `java.qread()` 在**非企点客户端上应当抛异常**。而服务端为了「凑齐 API 面」把 `qread` **别名到了浏览器函数**（不抛错）⇒ `checkEnv()` 恒返回「轻阅读」⇒ `islyc=false` ⇒ `book.imageStyle='TEXT'` ⇒ **整条正文取数分支走错**。

修法：移除 `qread` 别名，让它在非企点环境下如实抛错。

> **教训**：为了「让脚本不报错」而给缺失 API 随便补个别名，会**静默改变书源的控制流**，比直接抛错更难查。缺失 API 应当「如实缺失」或「语义等价地实现」，绝不能张冠李戴。

**会话收尾状态**：截至该会话结束，正文仍是 **`【未完成】正文规则在规则求值路径下返回空`**（独立执行同一脚本能拿到 2.4 万字正文）。后续会话（`SESSION-019-dagou-content-root-cause`）才彻底闭环——即 `AGENTS.md` 中「jsLib 与规则脚本分离求值」「`<div>` 清洗删光正文」两条。

---

## 4. 内置 WebView 登录反向代理（当时的重大新功能）

用户诉求（第 11 轮）：「要求可以正常登录书源，在网页可以用类似 webview 的页面」

**约束**：浏览器不允许跨站 iframe、也读不到别站 Cookie ⇒ **只能由服务端整站反向代理到同源路径下**。

落地实现：
- **服务端 `WebViewProxy.kt`（新增）**：Jsoup 全量 HTML 重写（链接/表单/子资源/srcset/内联 CSS/`@import`）；注入引导脚本屏蔽 frame-busting，并把页面 JS 的 `fetch`/`XHR`/`window.open` 也改写到代理路径；**自动嗅探 GBK 等中文站点编码**（`Content-Type` 不带头也能正确解码）；目标站的 `Set-Cookie` **直接吸收进书源自己的 Cookie jar** ⇒ 登录即刻生效，无需手动复制。
- **前端 `SourceWebViewModal.tsx`（新增）**：iframe 内嵌 + 地址栏 + 前进/后退/刷新/回首页 + Cookie 计数 + 「✓ 登录完成」。登录成功后**自动重跑当初触发浏览器的登录动作**，让书源 JS 带着已落库的 Cookie 真正完成校验。
- 把书源 JS 的 `startBrowserAwait` **从外部标签页改为内置 WebView**（原来是 `window.open(res.openUrl, '_blank')`，登录产生的 Cookie 与书源完全脱节）。

**踩坑（已并入 `AGENTS.md`）**：
- `Set-Cookie` 存库前必须**剥离属性**：`parseCookieString("session=abc; Path=/; HttpOnly")` 会把 `Path=/` 当键存进 jar（`HttpOnly` 因无 `=` 被跳过），后续作为请求头发送时污染上游。修法：写专门的 `extractCookiePair` 取 `name=value`。
- **SSRF 防护的错误码错位**：`NetworkSecurity.resolveAndValidateSafeHttpTarget` 抛的是 `IllegalArgumentException`（非 `WebViewException`）⇒ 未被 `respondProxied` 捕获 ⇒ 落到 StatusPages 变成 `500 服务器内部错误`。安全上仍拦截成功 ✅，但用户体验极差。修法：在 `validateTarget` 内捕获并重抛为 `WebViewException`。
- **白名单/令牌实测矩阵**：不相关域名 → 502 拦截 ✅；内网 IP `127.0.0.1` → 拦截 ✅；伪造 token → 502 拒绝 ✅；已吊销 token → 502 拒绝 ✅。

---

## 5. 回归判定方法论的起源

会话中第一次系统性地建立了**「干净基线 worktree 比对失败集合」**这一方法：

| | 用例总数 | 失败数 | 失败集合 |
| :--- | :--- | :--- | :--- |
| 干净基线（未改动的 HEAD） | 136 | **51** | 基准集合 |
| 改动后工作树 | 150 | **51** | **与基线完全一致** |

⇒ 零回归，且净增 14 个通过用例（`WebViewProxyTest`）。

会话中一度把 `DatabaseLifecycleTest` 的失败误判为「自己引入的」，随后用干净 HEAD 复跑证明**是既有失败**。根因也在此查清：测试**从不调用 `database.close()`** 就删 SQLite（`Database` 持有 WAL 模式下的连接池），Windows 不允许删除被占用文件，而 macOS 的 POSIX 语义允许 ⇒ **原始开发环境永不暴露**。

> 这正是 `AGENTS.md` §3 与 `SESSION-HIST-008` 中「本机长期固定 51~52 个失败」「判定回归必须比失败集合而非数量」的**最早来源**。注意数字从 51 演进到 52（新增用例所致），**数量本身无意义**。

---

## 6. 交付与协作史

- 用户要求：「帮我单开一个 wfanan 的分支上传上去，不要影响原来的」⇒ 贡献走 fork `wfanan/wfanan` + PR，不直推 `master`。
- 用 `gh` CLI 走 web 授权登录（`gh auth login --hostname github.com --git-protocol https --web`）。
- **踩坑：`.cmd` 批处理文件的 BOM 导致整脚本崩坏**。用户贴出的回显满是 `'锘緻echo' 不是内部或外部命令` —— `锘緻` 正是 **UTF-8 BOM（`EF BB BF`）被按 GBK 解码**的结果，BOM 混进了 `@echo off` 行。与「`plugin.json` BOM 导致插件全卸载」同源，**Windows PowerShell/cmd 写文件带 BOM 是本项目的高频陷阱**。
- **踩坑：PowerShell 传中文 JSON body 编码错乱**：用 `Invoke-RestMethod -Body <string>` 传含中文的 JSON，源名会被写坏导致后续按名查不到（404）。⇒ 联调脚本应显式指定 UTF-8 字节而非字符串。
- 用户最终关注「我会出现在贡献列表吗」⇒ 引出 `AGENTS.md` 中「Force Push 历史孤立对象导致 Contributors 残留」条目（切换默认分支为 `main` 以强制 GitHub 重构索引）。
- CI 在 PR 后失败（`Backend Tests & Build` Failed in 2m23s）——即当时尚未意识到 Windows 基线失败数会被 CI 放大。

---

## 7. 沉淀至部落知识库的经验条目

| # | 经验条目 | 当前是否已在 `AGENTS.md` |
| :--- | :--- | :--- |
| 1 | `JavaImporter` 用安全空替身；`JsSandbox` 异常不得静默吞掉 | ✅ 已有 |
| 2 | 巨型 `data:` URL 转服务端托管，避免 8192 请求行超限 | ✅ 已有 |
| 3 | `Set-Cookie` 存库前剥离指令属性 | ✅ 已有 |
| 4 | Iframe 代理严禁 `allow-same-origin` | ✅ 已有 |
| 5 | `jsLib` 对书源所有规则全局可见（`JsExecutionContext` + `withSourceContext` 统一注入） | ✅ 已在「Rhino 沙箱顶级 return 包装」条目中体现 |
| 6 | JSON 条目必须以**真 JSON**（非 `toString()`）传给 `<js>` 规则 | ⚠️ **本次补录** |
| 7 | `##正则##替换` 语法必须在 JSON 与 HTML **两条路径**统一应用 | ⚠️ **本次补录** |
| 8 | `java.net.URI` 严格 vs OkHttp 宽松：query 内未编码 `{}"` 必须宽容编码 | ⚠️ **本次补录** |
| 9 | **缺失的沙箱 API 严禁张冠李戴补别名**（`qread` 污染 `checkEnv()` 分支） | ⚠️ **本次补录** |
| 10 | `nodes()` 之外，`<js>` **列表规则**（`chapterList`）需专门支持 | ⚠️ **本次补录** |
| 11 | `login-action` 端点可当**任意 JS 探针**用（`java.toast` 回传） | ⚠️ **本次补录（高价值排障技巧）** |
| 12 | `Invoke-WebRequest` 大文件慢 500 倍，必须用流式下载 | ⚠️ **本次补录** |
| 13 | PowerShell/cmd 写文件带 BOM 是本项目高频陷阱（`.cmd`、`plugin.json`） | ✅ 部分已有（插件条目） |
| 14 | PowerShell 传含中文 JSON body 需显式 UTF-8 字节 | ⚠️ **本次补录** |
| 15 | 测试不 `close()` 就删 SQLite ⇒ Windows 固定失败，须比**失败集合** | ✅ 已有（`SESSION-HIST-008`） |

---

## 8. 本次 `/doc-init` 结论

- 本会话是仓库的**奠基会话**，此前完全未归档；现已将其中 **5 处根因 + 1 个重大功能 + 1 套回归方法论** 固化为可检索条目。
- 其中 **第 6/7/8/9/10/11/12/14 条** 属于本次新挖掘、此前未沉淀的部落知识，已同步增量写入 `AGENTS.md` §5。
- **遗留未闭环**：正文（`ruleContent`）在此会话结束时仍返回空，由后续 `SESSION-019` 闭环——**两篇归档需对照阅读**。
