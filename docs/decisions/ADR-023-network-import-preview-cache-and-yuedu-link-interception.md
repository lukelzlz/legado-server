---
id: ADR-023
title: 网络导入采用「服务端代抓 + 内存票据预览」与「代理层捕获 yuedu:// 链接」
status: accepted
date: 2026-09-30
---

# ADR-023: 网络书源导入的两项关键裁定

关联提案：[PROPOSAL-023](../proposals/PROPOSAL-023-network-book-source-import.md)

## 1. 决策背景 (Context)

网络导入要同时满足四个约束，而它们互相牵制：

1. **浏览器跨域**：社区书源站的 JSON 接口不提供 CORS 头，前端 `fetch` 必然被浏览器拦掉。
2. **体积**：实测 `shuyuan-api.yiove.com` 那条返回 **383 条书源 / 1.63 MB**；
   若「前端拉取 → 回传服务端落库」，等于把 1.6 MB 搬两趟，且**客户端成为导入内容的权威来源**（可篡改面）。
3. **安全**：用户能填任意 URL ⇒ 必须有 SSRF 防护（拒绝内网/本机/环回地址）。
4. **交互**：内置浏览器里的 `yuedu://booksource/importonline?src=…` 必须能触发导入。
   `WebViewProxy.rewriteAttr` 经 `absolutize` 只接受 http(s)，非 http(s) **直接跳过重写**，
   iframe 又不认识 `yuedu://` ⇒ 现状是「点了完全没反应」。

## 2. 裁定方案 (Decision)

### 决策一：服务端代抓 + 内存票据缓存预览（两步式）

- `POST /api/sources/import-url/preview { url }`
  → 服务端拉取（复用 `NetworkSecurity.resolveAndValidateSafeHttpTarget` 做 SSRF 校验）
  → 解析为书源数组 → **仅返回元数据**（名称 / URL / 分组 / 类型 / 备注 / 状态标签）
  → 同时返回一次性 `token`，完整书源原文留在服务端**内存缓存**。
- `POST /api/sources/import-url/commit { token, selected[], group? }`
  → 按 token 取回缓存 → 只导入勾选项 → 走既有 `Database.importSources` 落库。

缓存约束：**TTL 10 分钟、最多 8 条、单条 ≤ 8 MB**，超限按最旧淘汰；token 为随机不可预测串。
（与既有 `WebViewProxy` 票据模式同源，不引入新依赖。）

### 决策二：在代理 bootstrap 脚本内以「捕获阶段点击拦截」处理 `yuedu://` 导入链接

在 `injectBootstrap` 已注入的脚本里追加一个 `document` 级 `click` 监听（`capture: true`）：

- 命中 `a[href^="yuedu://booksource/importonline"]` → `preventDefault()` →
  解析 `src` 查询参数（URL 解码一次）→ 经 `window.__legadoHost.postMessage` 上报
  `{ source:'legado-webview', type:'import-online', url }`。
- 宿主（`SourceWebViewModal`）收到后上报父级，由 `SourceLoginModal` 以**平级分支**挂载
  `NetworkImportModal` 并用该 URL 自动拉取。

**通用性**：不针对大灰狼硬编码，任何书源只要用同一 `yuedu://` 约定即可生效。

### 决策三：预览阶段对每条**试解析**，把注定失败的标为「不可导入」

**背景约束**：单条书源有硬上限 **1 MiB**（`SourceCodec.MAX_SOURCE_BYTES = 1024 * 1024`），
且**所有**导入路径最终都经 `SourceCodec.parse` 逐条校验（`Database.importSources` 的循环）。
该限制并非本提案引入，但网络导入会一次带来几百条，**不透明地暴露它就会误导用户**。

**裁定**：`preview` 阶段就对每条书源跑一次 `SourceCodec.parse`（`runCatching` 包裹）：

- 成功 → 按 G7 判定为「新增」或「更新」，**可勾选**；
- 失败（超 1 MiB / 缺 `bookSourceUrl` / 非 JSON 对象 / `bookSourceUrl` 为空）
  → 标为「**不可导入**」并附上 `parse` 抛出的原因，**不可勾选**；
- `commit` 阶段只接受合格且被勾选的条目。

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

### 决策一相关

- **备选 A：前端直接 `fetch` 目标 URL，再把结果 POST 给 `/api/sources/import`**
  - *否决理由*：① 跨域，社区站不给 CORS 头，直接失败；② 383 条要搬两趟；
    ③ **信任边界错位**——服务端变成「客户端说导什么就导什么」，而校验（SSRF、体积、格式）都失去意义。
- **备选 B：preview 与 commit 各拉一次（commit 只传 URL）**
  - *否决理由*：两次请求之间源站内容可能变化，用户「看到 4 条、导入 5 条」；
    且 1.63 MB 白拉两遍。要修就得加内容哈希比对，复杂度反而更高。
- **备选 C：把拉取结果落盘暂存**
  - *否决理由*：预览是**瞬时**数据，落盘会引入清理/并发/迁移负担，违反复杂度惩罚原则。

### 决策二相关

- **备选 D：在 `rewriteHtml` 里把 `yuedu://` 的 `href` 改写成代理地址**
  - *否决理由*：① `absolutize` 的语义就是「只处理 http(s)」，在此开特例会让「什么算可代理」变得含糊；
    ② 只覆盖**静态** `href`，对 JS 动态插入的链接无效；
    ③ 需要再造一套「标记 → 回读」协议，不如直接拦点击直白。
- **备选 E：服务端把 `yuedu://` 视为可代理协议，直接去请求**
  - *否决理由*：`yuedu://` 是**客户端私有协议**，不是网络协议，服务端无法请求；
    真正要的是它 `src` 参数里的 http(s) 地址，应在拦截时抽取。
- **备选 F：要求用户手动复制 `src` 地址再粘贴到「网络导入」**
  - *否决理由*：这正是当前痛点，等于不做 Story 3。

### 决策三相关

- **备选 G：把逐条校验推迟到 commit，靠返回的 `errors` 事后告知**
  - *否决理由*：预览的职责就是「让用户在落库**前**知道会发生什么」。事后报错会让用户面对
    「383 条勾好、点确认却冒出一堆错误」，被迫反推哪条坏了再重来 —— **预览变成误导**。
- **备选 H：预览阶段把不合格项直接过滤掉、不显示**
  - *否决理由*：用户会以为源站只有 380 条，实际是有 3 条被**静默丢弃**。这与仓库既有部落知识
    「**静默丢数据比抛错危险得多**」直接相悖，必须显式标出并给出原因。

## 4. 后果与权衡 (Consequences & Trade-offs)

### 正面收益

- 跨域、体积、安全、交互四个约束一次性解决；服务端始终是导入内容的权威来源。
- 复用既有 `NetworkSecurity` 与 `importSources`，新增代码集中在两个路由 + 一个新模块。
- `yuedu://` 拦截对全部书源通用，未来出现同类页面无需再改。

### 负面代价与已知边界

- **内存缓存有状态**：多实例部署时 preview 与 commit 可能落到不同实例而票据失效。
  本项目是**单实例 headless 服务**（SQLite + 进程内状态，既有 session/ticket 同样如此），可接受。
- **`location.href` 直跳 `yuedu://` 拦不住**：语言层面无法改写 `location` 赋值，与
  SESSION-036 记录的「段评气泡绕过代理」是同一类限制。当前 `更新/page.html` 用的是静态
  `<a href>`，因此本次可用；若将来出现 `location.href` 形式的导入跳转，需重新评估。
- **名称兜底匹配存在误合并风险**：故 UI 必须在**确认前**就把每项判成「新增」还是「更新」暴露给用户
  （Story 3/5），让用户有机会取消勾选，而不是静默覆盖。
- 名称兜底命中时，**必须把待导入书源的 `bookSourceUrl` 改写为该现有源的 id**，
  否则 upsert 会凭新 URL 插出一条新书源，导致「说是更新、实际新增」。
- **本次有意不做**：既有 `POST /api/sources/import` 的**整包体积上限**缺失
  （该路由未装 `RequestValidation`，Ktor CIO 亦无默认 body 上限，请求体会被全量缓冲进内存）。
  这是**独立于本提案的既有缺口**，按用户决定留待后续单独立项，此处仅记录在案。
