---
id: SESSION-037
title: 网络书源导入（URL 拉取、服务端票据预览与内置浏览器线路导入）
date: 2026-09-30
type: feat
related:
  - docs/proposals/PROPOSAL-023-network-book-source-import.md
  - docs/decisions/ADR-023-network-import-preview-cache-and-yuedu-link-interception.md
  - docs/acceptance/ACCEPT-024-network-book-source-import.md
---

# SESSION-037：网络书源导入

## 1. 需求与边界

用户要两条入口：

1. **书源页「导入 JSON」下方新增「网络导入」**：粘贴一个 URL 直接导入书源；
2. **内置浏览器里的「更新书源」线路**：聚合书源（大灰狼）登录弹窗点 `❇️ 更新书源` → 内置浏览器打开
   「书源更新」页 → 点某条线路 → 应当弹出**与上面同一个预览弹窗**并完成导入。

弹窗需还原 Legado 手机端样式（标题「导入书源」+「自定义源分组」+ `⋮` + 每行勾选框与状态标签 +
「取消全选（n/N）」+ 取消/确认），并**跟随主体主题**。按用户拍板：每项的「更新」按钮改为
**状态标签**（新增 / 更新 / 不可导入）；分组**选了才套用，没选就不动**。

## 2. 关键发现（全部有实测证据）

### 2.1 两种链接形态解析路径完全一致，不需要分叉

| 链接 | 末段 | 实测响应 | 体积 |
| :--- | :--- | :--- | :--- |
| `shuyuan-api.yiove.com/import/book-source-collection/<uuid>` | 无 `.json` | `Array(383)` | 1 663 636 B |
| `shuyuan.nyasama.net/shuyuan/<hash>.json` | 有 `.json` | `Array(4)` | 18 767 B |

两条都是**顶层 JSON 数组**，平均每条仅约 4.3 KB。所以「有/无 `.json`」只是一个 URL 形态差异，
不构成两条代码路径。

### 2.2 跨域是硬约束（实测响应头）

带 `Origin: http://127.0.0.1:8080` 请求：

- `shuyuan-api.yiove.com` 只回了 `access-control-allow-credentials: true`，**没有** `allow-origin`
  —— 按 CORS 规范这是**无效配置**（允许携带凭据时 `allow-origin` 必须存在且不能是 `*`），浏览器照样拦；
- `shuyuan.nyasama.net` **一个 CORS 头都没有**。

⇒ 浏览器 `fetch` 必然失败，只有服务端能抓。这直接决定了「服务端代抓」的架构（见 [ADR-023]）。

### 2.3 `yuedu://` 线路链接在内置浏览器里是**死链**

`更新/page.html` 里 7 个线路按钮的 href 全部形如：

```
yuedu://booksource/importonline?src=https%3A%2F%2Fsy.langge.uk%2Fdownload%2F...json
```

而 `WebViewProxy.rewriteAttr` 走 `absolutize`，**非 http(s) 一律返回 null 直接跳过重写**，
iframe 又不认识 `yuedu://` 这个客户端私有协议 ⇒ 用户点了**完全没反应**，书源永远更新不了。
这是本条需求的真正堵点（页面本身能打开，是 PR #12 已经修好的）。

### 2.4 单条书源有 **1 MiB** 硬上限（既有，非本次引入）

```
SourceCodec.kt:42   require(cleanText.toByteArray().size <= MAX_SOURCE_BYTES) { "书源不能超过 1 MiB" }
```

`Database.importSources` 对**每一条**都会调 `SourceCodec.parse`，因此本地文件导入、订阅更新、
网络导入**一律生效**，超限条目进 `errors` 被跳过而不中断整批。实测两种来源平均每条 4.3 KB，
离上限两个数量级 —— 所以 383 条 / 1.63 MB 那个「整包」完全不受影响（限制是**每条**不是**整包**）。

但这也意味着**预览必须如实暴露它**，否则用户会看到「383 条都能导、点确认却冒出一堆错误」。

### 2.5 ⚠️ 踩到的坑：`SourceCodec` 的 Json 是 `isLenient` 的，会把 HTML 错误页解析成裸字符串

把「书源集合解析」抽成共享函数时，最直觉的写法是：

```kotlin
val values = when (element) {
    is JsonArray -> element
    is JsonObject -> ... 
    else -> listOf(element)   // ← 危险
}
```

**但 `SourceCodec` 里的 `json` 配了 `isLenient = true`**（为兼容 Legado 的伪 JSON 头部等），
它会把 `<html><body>404 Not Found</body></html>` 这种文本当成「未加引号的字符串字面量」
**解析成功**并返回一个 `JsonPrimitive`。于是：

- 期望：抛出「内容不是有效 JSON」→ 用户看到「该地址返回的不是有效书源 JSON」；
- 实际：得到「1 条书源」，再逐条试解析失败 → 用户看到**「1 条不可导入」**，
  于是去猜「这条源为什么坏」，而真正的问题是**那个 URL 根本不是书源地址**。

修法：顶层既不是数组也不是对象就**显式抛错**。

```kotlin
    // 顶层既不是数组也不是对象 ⇒ 一定不是书源集合，必须显式拒绝。
    else -> throw IllegalArgumentException("内容不是有效的书源集合")
```

**这是被单测逼出来的**（`preview reports non JSON and empty collections clearly` 一开始失败），
不是读代码发现的 —— 再次印证仓库既有教训「静默丢数据/静默误判比抛错危险得多」。

## 3. 实现设计（要点）

### 服务端

- **`SourceCodec.parseSourceList()`（新，共享）**：把一份响应体拆成「逐条书源 JSON」，
  兼容顶层数组 / `{data|sources|bookSources|list:[]}` / 单个书源对象 / UTF-8 BOM。
  **`SubscriptionService` 改为委托同一实现** —— 避免订阅与导入两处口径漂移
  （仓库既有原则：「两处口径必须同源」）。
- **`NetworkSourceImport`（新）**：`preview()` 拉取 → 逐条 `SourceCodec.parse` 试解析 →
  判定 `new/update/invalid` → 把**最终要落库的 JSON** 存进内存票据缓存，只回元数据 + token；
  `commit()` 凭 token 取回、按**下标**挑选、落库。
- **票据约束**：TTL 10 分钟、最多 8 份、单次响应体 ≤ 8 MiB（与 `WebViewProxy` 同量级）；
  token 为 24 字节 `SecureRandom` 十六进制；**用完即焚**（一次性）。
- **更新判定**：`bookSourceUrl` 归一化匹配 → 未命中再按 `bookSourceName` 匹配（兼容改名）。
  **名称兜底命中时必须把 `bookSourceUrl` 改写为该现有源的 id**，否则 upsert 会凭新 URL
  插出一条重复源 —— 表现为「提示更新、实际新增」。
- **两条路由**：`POST /api/sources/import-url/preview` 与 `.../commit`，
  会话 + CSRF 鉴权，错误走 `respondApiError` 并补四语字典（8 个 `import_*` 码）。
- **`WebViewProxy.injectBootstrap`**：追加**捕获阶段** `click` 监听，命中
  `a[href^="yuedu://booksource/importonline"]` 时 `preventDefault` → 抽出 `src`（URL 解码一次）
  → 经 `window.__legadoHost.postMessage({type:'import-online', url})` 交回宿主。
  **对任意书源通用**，不针对大灰狼硬编码。

### 前端

- `icons.tsx` 新增 `cloudDownload`（云 + 下箭头），与本地导入的 `upload`（托盘 + 上箭头）区分。
- `NetworkImportModal.tsx`（新，书源页与内置浏览器**共用**）：URL 输入 + 拉取 → 预览列表
  （勾选框 + 名称 + 状态标签 + 圆形详情按钮）→「取消全选（n/N）」→ 取消/确认；
  `⋮` 提供全选/取消全选/反选；「自定义源分组」提供 不分组 / 已有分组 / 新建分组。
- `SourceWebViewModal`：新增 `import-online` 消息分支（同样用 `event.source === iframe.contentWindow`
  认定来源，因为 iframe 是不透明源），经 ref 回调上报，避免重建消息监听。
- `SourceLoginModal` / `SourcesPage`：把导入弹窗挂成**平级分支**，严禁嵌套 `.modal-backdrop`
  （仓库既有弹窗架构约定）。
- 样式全部取 `var(--surface/--ink/--line/--accent/...)`，三套主题自动跟随。

## 4. 验证

### 4.1 单元 / 契约测试

- **服务端**
  - `NetworkSourceImportTest` **12** 例：新增判定、URL 命中判更新、**名称兜底改写 id**、
    invalid 附原因、只导入子集、分组策略（选了才套用 / 已有分组不被清空）、
    票据一次性与过期、包装形态、非 JSON 与空集合、`readBounded` 体积上限、
    地址校验（空 / 非 HTTP / 内网）、**票据数量上限**。
  - `NetworkImportRouteTest` **1** 例：未鉴权拒绝、缺 CSRF 拒绝、六个地址校验错误码、
    **四语消息**（en-US 下含英文且不含中文）、票据过期码。
  - `SourceCodecTest` **+2** 例：共享解析器的五种包装形态 + BOM + 拒绝非 JSON。
- **前端**：`network-import.test.ts` **2** 例（弹窗结构契约 / 中英渲染）；
  `npm --prefix web run check` 通过；套件 **178/178**（含四语键对齐与 800+ 键非空校验）。

### 4.2 零回归（按仓库铁律比对**失败集合**，不看数量）

| | 用例 | 失败 |
| :--- | ---: | ---: |
| 干净基线 worktree（`HEAD` = `14c6f90`，无本次改动） | 362 | 54 |
| 本次改动后 | 377 | 54 |

- `Compare-Object` 双向对称差集 **均为 0**（即：既没有新增失败，也没有失败被"掩盖"）。
- 54 条失败**全部**是 `java.nio.file.FileSystemException` —— 正是 `AGENTS.md` 记录的
  「测试删除被 WAL 占用的 sqlite」Windows 基线噪声。
- 新增的 15 个用例全部通过，因此 377 = 362 + 15。

### 4.3 真实浏览器端到端（Edge + puppeteer-core）：**40/40**

在**隔离数据副本**上（`LEGADO_PORT=18084`，绝不触碰用户真实库）驱动真实 UI：

| 组 | 覆盖 | 结果 |
| :--- | :--- | :--- |
| A | 「网络导入」按钮存在、文案、**专属图标**、确实位于「导入 JSON」**下方** | 4/4 |
| B | 弹窗：标题「导入书源」、自定义源分组、`⋮`、地址框、未拉取时确认禁用 | 6/6 |
| C | 拉 `.json` 链接得 4 条、全「新增」、计数 `(4/4)`、摘要如实、`⋮` 取消全选/全选 | 8/8 |
| D | 确认后出「导入完成」、弹窗关闭、**服务端书源 +4**（用 API 计数） | 3/3 |
| E | 再拉同一地址 → 4 条全部变「**更新**」（不产生重复源） | 1/1 |
| F | 分组选择器可选「不分组 / 已有分组 / 新建分组…」，新建分组真的落库 | 3/3 |
| G | 空地址 / **内网地址(SSRF)** / 非书源 JSON 三种异常均有明确提示 | 3/3 |
| H | 三主题底色各不相同、深色确实偏暗、文字色随主题变化 | 3/3 |
| I | 登录弹窗有「❇️ 更新书源」→ 内置浏览器打开 → **7 条 `yuedu://` 线路** → 点线路**弹出导入弹窗**、地址自动填入、自动拉取、状态标签正确 | 8/8 |

> **本次踩到的两个"测试自身"的坑（与产品无关，但对后续写 E2E 的人有用）**：
> 1. **主题类挂在 `.app-shell` 上**（`main.tsx` 里 `app-shell theme-${settings.theme}`），
>    虽然也镜像到 `documentElement`/`body`，但 `.app-shell` 是弹窗**更近的祖先**，
>    只改 `documentElement`/`body` 会被它盖掉 —— 第一版脚本三主题读到**完全相同**的底色，误报失败。
> 2. **「书源更新」页的线路按钮不是静态 HTML**：该页要先 `fetch()` 各线路的 `/version` 成功后才由 JS
>    生成（实测约 **8 秒**，期间一直显示「正在检查更新...」、`#buttonGroup` 为空且 `display:none`）。
>    打开就断言会误报「0 条线路」，必须**轮询等待**。

### 4.4 服务端直连自证

```
POST /api/sources/import-url/preview        未鉴权 -> 401（证明路由存在且受会话保护）
GET  /index.html -> bundle index-DWbSNIp9.js 内含 network-import-modal（证明新组件已打包）
```


## 5. 已知未覆盖

1. **`location.href` 直跳 `yuedu://` 拦不住**：语言层面无法改写 `location` 赋值，
   与 SESSION-036 记录的「段评气泡绕过代理」是同一类限制。当前 `更新/page.html` 用的是
   静态 `<a href>`，因此本次可用；若将来出现 `location.href = 'yuedu://…'` 的写法需重新评估。
2. **内存票据是有状态的**：多实例部署下 preview 与 commit 可能落到不同实例而票据失效。
   本项目是单实例 headless 服务（既有 session / `WebViewProxy` 票据同样如此），可接受。
3. **`POST /api/sources/import` 的整包体积上限仍然缺失**（该路由未装 `RequestValidation`，
   Ktor CIO 亦无默认 body 上限）。这是**独立于本提案的既有缺口**，按用户决定留待后续单独立项。
4. **上游 HTTP 失败保留原始状态码文案**（如「上游返回 HTTP 404」）：`import_upstream_failed`
   刻意**不**放进四语字典，与既有 `subscription_update_failed` 的处理方式一致 ——
   状态码是用户可据以行动的信息（404 = 链接失效），不该被泛化成「拉取失败」。

## 6. 变更文件

- 服务端：`NetworkSourceImport.kt`（新）、`SourceCodec.kt`、`SubscriptionService.kt`、
  `Models.kt`、`Routes.kt`、`I18nMessages.kt`、`WebViewProxy.kt`
- 前端：`icons.tsx`、`NetworkImportModal.tsx`（新）、`api.ts`、`main.tsx`、
  `SourceWebViewModal.tsx`、`SourceLoginModal.tsx`、`styles.css`、`i18n/locales/*.json`（四语）
- 测试：`NetworkSourceImportTest.kt`（新）、`NetworkImportRouteTest.kt`（新）、
  `SourceCodecTest.kt`、`web/test/network-import.test.ts`（新）、`web/test/run-all.ts`
