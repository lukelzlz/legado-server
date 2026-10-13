---
id: SESSION-036
title: 修复大灰狼书源「书源设置中心」设置结果无法回传（无头端 startBrowserAwait 通道缺失）
date: 2026-09-30
type: fix
related:
  - docs/acceptance/ACCEPT-023-source-settings-result-return.md
---

# SESSION-036：书源设置中心结果回传修复

## 1. 问题现象

用户在书源登录弹窗点开 `⚙️ 书源设置中心`（书源自定义按钮 `openSourceSettings()`）：

1. 设置页能正常打开、能改，但**改完点「✓ 登录完成」后设置不生效**，只弹「未读取到设置结果，已保留原配置。」；
2. 随后还会**跳出一个新页面**，地址是 `data:text/html;base64,...`（就是设置页本身又开了一份）。

对应分析样本：`html/书源设置.html`（由 `/doc-init` 从运行实例中另存），其关键契约：
页面把 state 写进 **4 个出口** —— `#source-settings-result`、`#source-settings-final-result`（URL 编码 JSON）、
Cookie `source_settings_result`、`location.hash = #config=…`；书源 JS 侧按 **内嵌 body → Cookie → URL** 三条通道读回。

## 2. 复现（真实数据 + 真实浏览器）

- 数据：克隆用户 `data/` 到 `.fixcheck-data/`（含真实 `大灰狼融合VIP5.0` 书源与登录态），清空 `app_user` 后用 `ADMIN_PASSWORD=admin123` 重新初始化；**未触碰用户真实库**。
- 服务：`gradlew :server:run`，`LEGADO_PORT=18084`、`LEGADO_SECURE_COOKIES=false`。
- 浏览器：`puppeteer-core` + 系统 Edge（无头），驱动真实 UI：书源页 → 登录弹窗 → 点 `⚙️ 书源设置中心`。
- 直接调 `POST /api/sources/{id}/login-action {action:"openSourceSettings()"}` 的响应：

```json
{ "success": true,
  "toasts": ["未读取到设置结果，已保留原配置。\nError: 未读取到设置结果"],
  "openUrlKind": "data:text/html;base64,PCFkb2N0eXBlIGh0bW", "openUrlLength": 52794 }
```

iframe 内实测（`frame.evaluate`）：

| 观测 | 值 | 含义 |
| :--- | :--- | :--- |
| `document.title` | `书源设置` | 页面正常渲染 |
| `#source-settings-final-result` 长度 | **824**（切到「听书」后同步变化） | **结果本来就已经在 DOM 里** |
| `document.cookie = 'probe=1'` | `throw:SecurityError` | Cookie 通道不可能到达书源 jar |
| `window.parent === window` | **true** | frame-busting 屏蔽后，页面**无法**向宿主 postMessage |
| `window.__legadoHost` | `undefined` | 屏蔽前没有留下真实宿主引用 |

## 3. 根因（三通道全断 + 一条隐藏陷阱）

| 书源 JS 读取通道 | 无头端实际 | 依据 |
| :--- | :--- | :--- |
| ① 内嵌 body：`browserResult.body()` 里正则取 `<script id="source-settings-*-result">` | **断**：`startBrowserAwait` 是 stub，记录 `openUrl` 后**立即返回** `{url, body: ""}`；用户还没操作，JS 已经开始读结果 | `JsSandbox.kt:591-601` |
| ② Cookie：`cookie.getCookie(baseUrl)` | **断**：页面在 `127.0.0.1` 代理源下、iframe `sandbox` 无 `allow-same-origin` ⇒ `document.cookie` 抛 `SecurityError`；且服务端 `harvestCookies()` 只吸收**被代理响应**的 `Set-Cookie` | `SourceWebViewModal.tsx:268`、`WebViewProxy.kt:96-161,234` |
| ③ URL：`browserResult.url()` 匹配 `#config=` | **断**：stub 返回的是 `data:` 原始地址，不含 `#config=` | `JsSandbox.kt:595-599` |
| ⚠️ 隐藏陷阱：向宿主 postMessage | **断**：`injectBootstrap()` 先把 `window.parent` 指回自身（frame-busting 屏蔽），此后再 `window.parent.postMessage(...)` 只会发给自己 —— **既有的 `navigated` 地址回报因此长期是死代码** | `WebViewProxy.kt` `injectBootstrap` |

⇒ 结果必然落入 `throw new Error('未读取到设置结果')`，`source.setVariable()` 永不执行。

**乱开新窗口的根因**（现象 2）：`SourceLoginModal` 在「登录完成后重跑触发动作」时用 `allowBuiltInBrowser=false`，
于是走 `window.open(res.openUrl, '_blank')`；而 `openSourceSettings()` 每次运行都会重新生成一份
`data:text/html;base64,…`（52KB）⇒ 浏览器把设置页又开成顶层新标签。

## 4. 修复设计（最小改动，保持安全底线）

**服务端**

1. `WebViewProxy.injectBootstrap()`：在屏蔽 `parent/top/frameElement` **之前**抓住真实宿主窗口并存为
   `window.__legadoHost`，`navigated` 与后续消息一律经它发送（顺带把地址栏回报这条死代码救活）。
2. `WebViewProxy.injectSettingsCollector()`：**仅当页面内容含结果容器 id**（`source-settings-final-result` /
   `source-settings-result` / `bubble-settings-result`）时注入采集脚本；用 `MutationObserver` + `load`/`pagehide`
   + 1.5s 兜底轮询读取容器文本，`decodeURIComponent` + `JSON.parse` 后
   `postMessage({source:'legado-webview', type:'settings-result', resultId, settings})`，并对同一文本去重。
   **不注入到任意第三方页面**，避免无谓的消息与耦合。
3. `WebViewProxy.normalizeSettingsResult()`：把回传对象收敛成可落库的源变量 —— 丢弃一次性
   `_settings_nonce`、书旗音色回落 `multi_role`、`pstyle` 归一为字符串（与书源 JS 自己 `setVariable()` 前
   的三步兜底一致）；空对象 / 无任何已知设置键 / 超 64KB 一律判为不可信并拒绝。
4. 新路由 `POST /api/sources/{id}/browser/result`：会话 + CSRF 鉴权 → 校验**票据属于该书源且未过期**
   （`WebViewProxy.hasTicket`）→ 归一化 → `database.saveSourceVariable()` 落库。

**前端**

5. `SourceWebViewModal`：监听 `settings-result` 消息（**必须**用 `event.source === iframe.contentWindow`
   校验来源，因为 iframe 是 opaque origin），300ms 防抖提交；关闭弹窗与点「检测登录」前各 flush 一次；
   首次成功后 toast「书源设置已保存」并回调 `onSettingsSaved`。
6. `SourceLoginModal`：`data:` 等**内联地址一律走内置浏览器**（`isInlineBrowserTarget`），不再 `window.open`
   （避免开出无意义新标签）；`onSettingsSaved` 时清掉 `pendingBrowserActionRef` 并刷新源变量显示，
   **取消「重跑触发动作」**（否则设置页会被再开一遍）。
7. 新增纯函数模块 `web/src/sourceSettingsResult.ts`：消息校验、内联地址判定、气泡结果合并（与书源 JS 的
   `_bubble_applied === '1'` + `_bubble_nonce === _settings_nonce` + `pstyle` 白名单正则口径一致）。
8. `RuleRunner.executeLoginAction()`：无头端必然触发的**误报**「未读取到设置结果」在「本次请求打开了内置页面」
   时被替换为「设置中心已在内置浏览器中打开，改动会自动保存」；不涉及内置浏览器的提示**原样保留**（严禁误伤）。

**红线保持**：`sandbox` 依然**不含** `allow-same-origin`（AGENTS.md 既有安全底线），改用「注入采集 + postMessage +
服务端票据校验」这一条不依赖同源的回传通道。

## 5. 验证

- **端到端（真实书源 + 真实 Edge + 隔离数据副本）：17/17 断言全绿**，关键断言：
  `__legadoHost` 抓到、frame-busting 仍生效、结果容器 824 字符、切到「听书」后
  `source_variable` 落库为 `{"tab":"听书",...,"find_tab":"听书","find_source":"全部"}`、`_settings_nonce` 未落库、
  无关字段（`server`/`qttoken`/`plcolor`…）保留、toast 为「设置中心已在内置浏览器中打开，改动会自动保存」+
  「书源设置已保存」、**无 `data:` 顶层新页面**、关窗后设置仍是本次值。
- **单测**：`WebViewProxyTest` 19（新增 5：采集脚本注入/不注入、宿主引用先于屏蔽、归一化镜像兜底、拒收无关/超长、票据校验）
  ＋ `SourceBrowserResultRouteTest` 2（路由落库与拒绝路径；登录动作误报替换且不误伤）。
- **前端**：`npm --prefix web run check` 通过；`npx tsx web/test/run-all.ts` **170/170**（新增 4 条）。
- **零回归（按仓库铁律比对失败集合）**：干净基线 worktree（HEAD）**353 用例/54 失败** vs 修复版
  **360 用例/同样 54 失败**，`Compare-Object` 双向 diff **均为 0**，且 54 条全部是既有的
  `java.nio.file.FileSystemException`（Windows 删除被 WAL 占用的 sqlite）。

## 6. 已知未覆盖（本次不做，留档）

1. **「段评气泡」子流程仍不通**（实测证据）：设置页的 `openBubblePreview()` 用
   `location.href = ${server}/static/bubble_preview.html` 直接跳转**绝对地址**，
   而代理只改写 `fetch/XHR/window.open`，**不改写 `location` 赋值**（语言层面也无法改写）⇒ iframe 直接落到
   `https://v5.langge.uk/static/...`：既无 `__legadoHost`（结果回传不了），页内 `fetch('/api/bubbles')` 也拿不到数据
   （实测气泡列表 **0 项**）。前端已具备气泡结果合并逻辑（`mergeBubbleResult`），待该页走代理后即可生效；
   可选修法：采集脚本捕获阶段拦 `#bubble-preview-open` 点击并改写到 `__legadoProxy.toPage(url)`。
2. **自定义服务器线路的 origin 边界**：内联页的 BASE 取票据域名，若用户把 `server` 改成不在书源域名白名单里的
   自定义线路，气泡页/探针的绝对地址解析可能落到兜底域名（未实测，属既有行为）。
3. **「在新标签页打开」不参与回传**：从弹窗 ⧉ 打开的独立标签没有宿主窗口，采集脚本按设计静默不发消息。

## 7. 变更文件

- 服务端：`server/src/main/kotlin/io/legado/server/{WebViewProxy,Routes,Models,RuleRunner}.kt`
- 前端：`web/src/{SourceWebViewModal.tsx,SourceLoginModal.tsx,api.ts,sourceSettingsResult.ts}`（`web/dist` 已重建）
- 测试：`server/src/test/kotlin/io/legado/server/{WebViewProxyTest,SourceBrowserResultRouteTest}.kt`、`web/test/{source-settings-result.test.ts,run-all.ts}`