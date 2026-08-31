# SESSION-HIST-004: 书源登录鉴权、动态 LoginUI 与凭据持久化全量落地

- **会话日期**：2026-08-31
- **分支/Worktree**：`.worktrees/feat-source-login` (`feat/source-login`)
- **议题**：书源登录支持（包含 1:1 Legado 原生弹窗、Rhino JS 宿主桥接、Cookie/Header 持久化与端到端自动化测试）

## 1. 核心需求与历史深坑推演
用户提出支持登录书源，并提供了真实书源规范（如微信文件《🍅大灰狼聚合5.8.20(vip完全版)》与容器内 13,507 个书源数据）：
1. 书源生态中含有大批通过 `loginUi` + `loginUrl`（JS 函数）完成登录与 Token 注入的源。
2. 原生沙箱中需要注入 `source.*`、`cookie.*`、`java.*` 宿主方法，支持 `putLoginHeader`、`getLoginInfo`、`ajax`、`toast`、`setCookie` 等常用操作。
3. `RuleRunner` 网络请求必须自动把持久化的 Cookies 与 Headers 附着到所有对外部书源的 HTTP 请求中。

## 2. 关键代码改动
1. **服务端存储与数据模型**：
   - `Models.kt`：增加 `hasLogin` 字段及 `SourceLoginUiResponse`、`SourceLoginActionResult`、`SourceLoginCheckResult` 等请求/响应实体。
   - `SourceCodec.kt`：新增 `hasLogin` 解析。
   - `Database.kt`：新建 `source_login_state` 表，增加对登录信息、请求头、全局变量、KV 存储及 CookieJar 的全套 CRUD，支持启动时 `migrateSourceTable` 增量添加 `has_login` 列。
2. **JS 沙箱执行环境与网络拦截**：
   - `JsSandbox.kt`：注入 `source`、`cookie`、`java` 桥接对象与 `JsExecutionContext` 执行上下文。
   - `RuleRunner.kt`：新增 `parseLoginUi`、`executeLoginAction`、`checkLoginStatus`，在所有外部请求抓取（`search`, `details`, `chapters`, `content`）中自动装配登录头与 Cookie，并实时解析 `Set-Cookie` 自动落盘。
3. **Ktor REST API 路由**：
   - `Routes.kt`：注册 6 个登录相关 API 路由（`/api/sources/{id}/login-ui`, `/login-info`, `/login-action`, `/login-check`, `/login-header`）。
4. **前端 React 19 客户端**：
   - `web/src/api.ts`：导出登录类型与 API 调用方法。
   - `web/src/SourceLoginModal.tsx`：创建 1:1 兼容原生 Legado 登录弹窗，自适应 Flexbox 响应式布局，支持全控件类型与动作反馈。
   - `web/src/main.tsx`：在书源列表和编辑器中挂载登录入口与徽标。
   - `web/src/styles.css`：完善现代暗色/明色主题下的弹窗与徽标样式。

## 3. 验证结果
1. `npm --prefix web run check`：通过（0 错误）。
2. `npx tsx web/test/run-all.ts`：101 个测试用例全部通过（100% 通过）。
3. `./gradlew :server:test`：全量 122 个 JVM 单元测试全部通过（含 `SourceLoginTest` 5 大场景）。
