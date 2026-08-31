# PROPOSAL-005: 书源登录鉴权、动态 LoginUI 驱动与凭据持久化

## 1. 提案摘要 (Executive Summary)
为无头阅读服务端（Ktor + Rhino JS 沙箱）与现代化 Web 客户端（React 19）提供 1:1 兼容原生 Legado（阅读 Android 原生）书源登录生态的能力。支持服务端动态解析 `loginUi` 表单控件、执行 `loginUrl` / `login.apply(this)` 自定义交互 Action、自动拦截并持久化 HTTP Cookie 与 Header 凭据，并在 Web 端实现自适应 Flexbox 原生弹窗与状态徽标展示。

## 2. 背景与问题定义 (Problem Statement)
在 Legado 庞大的书源生态（如测试覆盖的 13,507 个书源）中，约有 1,162 个源依赖身份鉴权、VIP 校验或动态 Token 计算。
此前无头服务端缺乏书源登录交互通道与凭据持久化表，导致：
1. VIP / 登录书源无法获取正文或目录。
2. 原生书源定义的 `loginUi`、`loginUrl`、`loginCheckJs` 无法在服务端无头环境执行。
3. `source.putLoginHeader()`、`source.getLoginInfo()`、`cookie.setCookie()` 等全局注入缺失，JS 执行报错。

## 3. 架构设计与实施规范 (Architecture Specification)
1. **服务端存储模型**：
   - 在 SQLite 中新建 `source_login_state` 表持久化各书源的 `login_info` (JSON)、`login_header` (JSON/String)、`source_variable`、`source_kv` 及 `cookie_jar`。
   - `source` 表增加 `has_login` 字段并支持启动时自适应迁移。
2. **Rhino JS 沙箱桥接**：
   - 注入 `source` 全局对象：提供 `getLoginInfo()`, `putLoginInfo()`, `getLoginHeader()`, `putLoginHeader()`, `getVariable()`, `setVariable()`, `put()`, `get()` 等全套 API。
   - 注入 `cookie` 全局对象：提供 `getCookie()`, `setCookie()`, `removeCookie()`, `replaceCookie()`, `mapToCookie()` 等。
   - 注入 `java` 桥接方法：`ajax()`, `post()`, `get()`, `toast()`, `startBrowser()`, `copyText()`, `base64Decode()`, `base64Encode()`, `md5()`, `randomUUID()` 等。
3. **规则引擎与网络拦截**：
   - `RuleRunner.kt` 在执行搜索、详情、目录及正文抓取时自动注入已保存的 CookieJar 与 LoginHeaders，并自动捕获抓取响应的 `Set-Cookie` 落盘。
   - `parseLoginUi()` 解析静态 JSON / 动态 `@js:` 表达式。
   - `executeLoginAction()` 执行表单按钮点击与长按交互。
4. **前端交互与自适应渲染**：
   - Web 端 `SourceLoginModal.tsx` 依据 `layout_flexBasisPercent`、`layout_flexGrow` 动态自适应排版。
   - 渲染 `text`, `password`, `toggle`, `select`, `button`, `label` 等全量控件。
   - 提供查看/复制/删除登录头与清空登录信息功能。

## 4. 交付与状态 (Status)
- **状态**：`Implemented`
- **关联 ADR**：[`ADR-005`](../decisions/ADR-005-book-source-login-ui-and-session-state.md)
- **关联 Session**：[`SESSION-HIST-004`](../sessions/SESSION-HIST-004-book-source-login-implementation.md)
