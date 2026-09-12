---
id: SESSION-010
title: 内置浏览器反向代理登录与复杂聚合书源生态兼容
date: 2026-09-12
author: Agent & User
tags: [webview, proxy, login, cookie, rhino, jssandbox, aggregate-sources, rules]
---

# SESSION-010: 内置浏览器反向代理登录与复杂聚合书源生态兼容

## 1. 现象与需求背景 (Investigation & Analysis)

- **核心诉求**：
  1. **内置浏览器网页登录**：Legado 书源生态中部分站点需要网页交互登录（如验证码、滑块、图形验证、OAuth 回调等）。浏览器出于同源策略无法跨站 `iframe` 目标站点、也无法读取其 Cookie。
  2. **聚合类书源兼容性**：大量聚合源（如各大 VIP 融合源）在检索、目录和正文规则中使用 `with (JavaImporter(...))`、复合规则链 `<js>...</js>$.data`、`##regex##replace` 以及 `data:;base64` / `data:;hex` 数据载荷在步骤间传递上下文，之前由于沙箱环境缺少 API、JSON 序列化丢失对象结构导致条目解析为空。
- **痛点推演与架构解法**：
  1. **无头整站反向代理（WebViewProxy）**：服务端提供轻量反向代理，对目标站点 HTML/CSS/Srcset 实施 AST 路径重写与引导脚本注入（屏蔽 Frame Busting、postMessage 跨域通信），前端使用安全沙箱 iframe（无 `allow-same-origin`）嵌入，上游响应头 `Set-Cookie` 自动过滤并沉淀至书源 Cookie Jar。
  2. **超长 Data URL 内存托管**：聚合源脚本常生成动辄数万字符的自包含页面（教程/更新/设置），直接作为 URL 传递会超出 Ktor 8192 字节限制，通过 POST `/browser/inline` 服务端注册与短 Key 托管彻底消除溢出风险。
  3. **沙箱安全空替身与规则链递归**：提供 `createJavaImporterStub` 安全空替身阻断 RCE 同时规避 `ReferenceError`；`NodeValue` 支持后置规则链递归解析与 Map 原生传参。
  4. **Cookie 属性过滤与层级匹配**：严格剥离 `Path`/`HttpOnly` 等 Set-Cookie 属性，按标准 DNS 分级（精确 host -> 父 domain）回退匹配。

## 2. 最终落地的正确解法 (Final Solution)

1. **服务端反向代理与安全防护**：
   - [`server/src/main/kotlin/io/legado/server/WebViewProxy.kt`](file:///root/legado-server/server/src/main/kotlin/io/legado/server/WebViewProxy.kt)：实现轻量级 HTML/CSS 重写引擎，结合 `NetworkSecurity` 强制防御 SSRF，实施 30 分钟短时效 Ticket 令牌鉴权与书源域名白名单。
2. **规则执行沙箱与生态补全**：
   - [`server/src/main/kotlin/io/legado/server/JsSandbox.kt`](file:///root/legado-server/server/src/main/kotlin/io/legado/server/JsSandbox.kt)：注入 `JavaImporter` 安全替身，增加 `book` / `chapter` 上下文桥接及 `hexDecodeToString` / `hexEncodeToString` / `getWebViewUA` 等常用工具 API。
   - [`server/src/main/kotlin/io/legado/server/RuleRunner.kt`](file:///root/legado-server/server/src/main/kotlin/io/legado/server/RuleRunner.kt)：支持 `<js>...</js>` 后置规则链递归求值与 `##` 正则替换，JSON 节点保持原生 Map/List 对象传递。
3. **Cookie Jar 规范化存储**：
   - [`server/src/main/kotlin/io/legado/server/Database.kt`](file:///root/legado-server/server/src/main/kotlin/io/legado/server/Database.kt)：实现 `extractCookiePair` 与分级域名匹配。
4. **前端沉浸式 Web 浏览器弹窗**：
   - [`web/src/SourceWebViewModal.tsx`](file:///root/legado-server/web/src/SourceWebViewModal.tsx)：提供前进/后退/刷新/地址栏/Cookie 徽标/在新标签打开/一键登录检测完整闭环。

## 3. 沉淀的教训与部落知识 (Lessons Learned)

- **[反向代理/沙箱] Iframe 隔离严禁开启 `allow-same-origin`**：反向代理第三方不可信 Web 页面时，iframe 必须禁用 `allow-same-origin`，将其置于 opaque origin（`null`）下，杜绝被代理页面的恶意 JS 触碰宿主 DOM 与会话。
- **[沙箱/安全] `JavaImporter` 必须采用安全空替身模式**：Legado 书源中大量 `jsLib` 工具库使用 `with (JavaImporter(...))` 组织代码。沙箱严禁开启真实 Java 反射（防止 RCE 漏洞），必须返回 `importClass`/`importPackage` 均为 no-op 的安全空替身，既规避 `ReferenceError` 崩溃，又确保纯沙箱环境安全。
- **[Cookie 管理] Set-Cookie 存库前必须剥离指令属性**：上游响应中的 `Path=/; HttpOnly; SameSite=Lax; Max-Age=3600` 等指令属性若直接整串入库，会导致后续作为客户端请求头 `Cookie:` 发送时将属性一并带出引发上游 400 报错。
- **[HTTP 规范/Ktor] 巨型 Data URL 必须转由服务端托管避免请求行超限**：书源 JS 生成的自包含 Base64 网页动辄数万字符，直接拼入 iframe URL 会触发 Ktor 8192 字符上限报 400，必须先通过 POST 上传服务端内存托管，前端仅引用短 key。
