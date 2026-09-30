---
id: SESSION-035
title: 全栈国际化与语言服务端漫游持久化（React-i18next + Ktor Accept-Language + app_setting 持久化）
date: 2026-09-30
type: feature
related:
  - docs/proposals/PROPOSAL-022-fullstack-i18n-and-server-locale-persistence.md
  - docs/decisions/ADR-022-react-i18next-and-ktor-accept-language-resolution.md
  - docs/acceptance/ACCEPT-022-fullstack-i18n-and-server-locale-persistence.md
---

# 全栈国际化与语言服务端漫游持久化

## 1. 背景与交付目标

随着海外用户与跨地域（港澳台、欧美、日本）使用场景增多，系统需从纯中文硬编码演进为现代化多语言体系：
1. **支持语种**：简体中文（`zh-CN`，默认）、繁体中文（`zh-TW`）、英语（`en-US`）、日语（`ja-JP`）。
2. **前后端一致性**：
   - 前端采用成熟轻量的 `react-i18next` + `i18next` 方案，全量静态词典，在顶栏下拉菜单提供语言切换网格；
   - 服务端提取 `Accept-Language` 请求头与通用业务错误消息字典（`ServerMessages`），实现 `ApiError` 响应消息本地化；
   - **错误码严格恒定**：`ApiError.code` 严禁翻译，永远保持英文字符串（如 `invalid_credentials`、`csrf_token_missing`），杜绝破坏前端错误分支逻辑；
3. **服务端漫游持久化**：
   - 用户的语言设置存入 SQLite `app_setting(key='locale')`，通过 `GET /api/settings/locale` 与 `PUT /api/settings/locale` 实现跨设备漫游；
   - 客户端同时写入 `localStorage`，断网脱机或未登录时支持优先恢复本地语言并降级到浏览器语言（`navigator.language`）；
4. **严格的非目标（Non-Goals）**：
   - 书名、作者、正文、章节目录、书源名称及规则脚本为动态生态数据，严禁机翻篡改，保持原样。

---

## 2. 关键架构设计与落地

### 2.1 服务端消息本地化与路由（`server/`）
- **`I18nMessages.kt`**：
  - 定义 `ServerMessages` 结构与 4 种语言文案字典；
  - 核心解析扩展：`ApplicationCall.resolveLocale()`，按 RFC 4647 前缀匹配规则解析 `Accept-Language`（例如 `en-GB` 回退到 `en-US`，`zh-HK` 回退到 `zh-TW`）；
  - 统一错误返回扩展：`ApplicationCall.respondApiError(status, code, messageKey, defaultMessage)`，自动按解析语言本地化 `message`。
- **`Routes.kt` / `Models.kt`**：
  - 新增 `LocaleSettingRequest(val locale: String)` 与 `LocaleSettingResponse(val locale: String)`；
  - 注册 `GET /api/settings/locale`（读 `app_setting`，未设置返回默认 `zh-CN`）；
  - 注册 `PUT /api/settings/locale`（写 `app_setting`，带 Session 与 CSRF 保护）。
- **统一应用**：`Auth.kt` 与 `WebDavServer.kt` 中的错误响应全面接入 `respondApiError`。

### 2.2 前端 i18n 与交互（`web/`）
- **依赖引入**：`i18next` (`^26.4.2`) 与 `react-i18next` (`^17.0.15`)；
- **词典模块化**：`web/src/i18n/locales/{zh-CN, zh-TW, en-US, ja-JP}.json`，统一维护公共动作、导航标签、登录提示、书库过滤、状态与通知；
- **状态同步机制**：
  - `web/src/api.ts`：所有 HTTP 请求、WebDAV 写请求与 TTS 音频流请求统一自动携带请求头 `Accept-Language: getCurrentLocale()`；
  - `web/src/main.tsx`：用户登录后与初始化会话时，主动请求 `api.getLocale()` 并同步更新前端 `i18n.changeLanguage()` 与 `localStorage`；用户切换语言时不仅前端即时响应，更异步调用 `api.setLocale()` 漫游持久化；
- **UI 集成**：
  - `AppHeader.tsx`：导航抽屉/菜单内新增 2×2 语言选择网格，当前语言带高亮标识，一键即时无刷切换；
  - 导航标签（书架/书库/书源/订阅/规则/文件）、操作按钮与全局组件统一接入 `useTranslation()`。

---

## 3. 踩坑与防御性原则（Tribal Knowledge）

1. **测试基线与静态断言冲突**：
   - `web/test` 中大量既有测试（如 `HeaderMenu.test.ts`, `search-scope-bar.test.ts`, `ReplaceRulesPage.test.ts`）使用 `renderToStaticMarkup` 对具体中文原生文案做断言。
   - **防御策略**：`zh-CN.json` 中的翻译项必须 100% 保持与历史中文文案逐字一致；前端初始化缺省语言必须锁定为 `zh-CN`，Node 测试环境不传 `localStorage` 时平滑渲染中文，使所有既有 170 条前端测试无感通过。
2. **ESM / tsx 导出面陷阱**：
   - 在 tsx 环境中从 `web/src/i18n/index.ts` 导入类型或常量时，若未显式 `export * from './types'` 会在 Node 运行时报 `undefined` 导入错误。必须在 index 中完整重导出 `types.ts`。
3. **API 错误码与消息契约分离**：
   - 业务逻辑与客户端分支只能基于 `ApiError.code` 判定（如 401 登出判定、CSRF 刷新重试），`ApiError.message` 仅用于人机可读的 Toast 或提示框呈现。

---

## 4. 自动化验证记录

```bash
# 1. 前端类型检查
npm --prefix web run check
# 结果: 0 errors

# 2. 前端测试套件（含新增 i18n 单元测试）
npx tsx web/test/run-all.ts
# 结果: 170/170 passing (含 4 条新增 i18n 专项用例)

# 3. 前端生产打包验证
npm --prefix web run build
# 结果: built in 786ms, dist/index.html, dist/sw.js generated

# 4. 后端测试（含新增 I18nLocaleAndErrorTest）
./gradlew :server:test -x :server:buildWeb
# 结果: 355 tests completed, 0 unexpected failures, 54 known SQLite teardown failures on Windows (基线零回归)
```
