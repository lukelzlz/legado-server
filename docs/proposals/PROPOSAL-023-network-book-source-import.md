---
id: PROPOSAL-023
title: 网络书源导入（URL 拉取、预览选择与内置浏览器线路导入）
status: accepted
author: Agent & User
date: 2026-09-30
---

# PROPOSAL-023: 网络书源导入

## 1. 业务背景与问题痛点

当前服务端只支持两种书源获取方式：**上传本地 JSON 文件**（`POST /api/sources/import`）与
**订阅定时拉取**（`SourceSubscription`）。两者都覆盖不到真实使用中最常见的一条路径：

1. **从社区书源站导入**：用户拿到的是形如
   `https://shuyuan-api.yiove.com/import/book-source-collection/<uuid>`（无 `.json`）或
   `https://shuyuan.nyasama.net/shuyuan/<hash>.json`（有 `.json`）的**网络地址**，
   必须先在手机/浏览器里下载成文件再上传到服务端，多一步且手机上很难操作。
2. **从书源自身的「书源更新」页更新**：聚合书源（如 `大灰狼融合VIP5.0`）在登录弹窗里提供
   `❇️ 更新书源`（`customButton` → `renderVersionPage()`），会打开一个内置页面，页内按线路给出
   `yuedu://booksource/importonline?src=<urlencoded 书源 JSON 地址>` 链接。
   **这些链接在当前内置浏览器里是死的**：`WebViewProxy.rewriteAttr` 经 `absolutize` 只接受
   http(s)，非 http(s) 直接跳过重写，而 iframe 无法处理 `yuedu://` 自定义协议 ⇒ 用户点了没有任何反应，
   书源永远更新不了。

> 实测证据（本次）：`更新/page.html` 共 7 个线路按钮，href 全部为
> `yuedu://booksource/importonline?src=…`；两种网络地址拉取后均为**顶层 JSON 数组**
> （`Array(383)` / 1.63 MB 与 `Array(4)` / 18 KB）。

## 2. 目标与非目标 (Goals & Non-Goals)

### Goals

- **G1**：书源页「导入 JSON」下方新增「**网络导入**」按钮（配专属图标），点击后可从 URL 导入书源。
- **G2**：内置浏览器里点击 `yuedu://booksource/importonline?src=…` **能真正触发导入**，
  且该能力对**任意书源**通用，不针对大灰狼硬编码。
- **G3**：导入前弹出**预览选择弹窗**（还原 Legado 手机端样式），逐项显示书源名与
  状态标签（**新增** / **更新** / **不可导入**），支持逐项勾选、全选/取消全选。
- **G4**：弹窗**跟随当前主题**（light / paper / dark 三套 CSS 变量）。
- **G5**：支持两种链接形态（末段带 `.json` 与不带 `.json`），二者解析路径统一。
- **G6**：导入时**可选**目标分组：选了就套用该分组，没选则不改动分组（新源落「未分组」）。
- **G7**：更新判定采用 **`bookSourceUrl` 归一化匹配 → 未命中再按 `bookSourceName` 匹配**
  （兼容书源改名，如「大灰狼融合VIP5.0」这类名称带版本号、URL 却对不上的情况）。
- **G8**：预览阶段**逐条试解析**（复用 `SourceCodec.parse`），把注定失败的书源标为
  「**不可导入**」并注明原因、**不可勾选**，确认时只提交合格项。
  否则预览会「看着 383 条都能导，点确认却冒出一堆错误」，等于预览在骗人。

> **既有约束（本提案不新增、但必须如实暴露）**：单条书源硬上限 **1 MiB**
> （`SourceCodec.MAX_SOURCE_BYTES = 1024 * 1024`），**对本地文件导入、订阅更新、网络导入一律生效**，
> 超限条目进 `errors` 并被跳过而不中断整批。实测两种网络来源平均每条仅约 4.3 KB，离上限两个数量级。

### Non-Goals

- **不做**在线书源市场的浏览 / 搜索 / 排行 / 订阅管理页（那是独立功能；本提案只做「给一个 URL → 导入」）。
- **不替换**现有 `SourceSubscription`（定时自动拉取）与本地文件导入，二者保持原样。
- **不放松**安全底线：iframe `sandbox` 仍**不含** `allow-same-origin`；不引入服务端无校验的全权代理。
- **不做**每项的「单独更新」按钮（按用户决定改为**状态标签**，确认时统一落库）。
- **不做**把 383 条书源的完整 JSON 全量传给浏览器再回传（见 ADR-023 决策一）。
- **不**给既有 `POST /api/sources/import` 补整包体积上限（已识别为**既有缺口**：该路由无 `RequestValidation`
  且 Ktor CIO 无默认 body 上限，请求体会被全量缓冲进内存）。按用户决定本次不做，留待后续单独立项。

## 3. 核心用户故事 (User Stories)

- **Story 1（书源页网络导入）**：作为用户，我在书源页点击「网络导入」，粘贴
  `https://shuyuan-api.yiove.com/import/book-source-collection/<uuid>`，点「拉取」后看到 383 条书源
  的勾选列表，点「确认」后全部导入，提示如实回报「新增 N 个 / 更新 M 个」。
- **Story 2（`.json` 形式链接）**：作为用户，我粘贴
  `https://shuyuan.nyasama.net/shuyuan/<hash>.json`，得到 4 条书源的预览列表，
  每项带「**新增**」标签，确认后导入成功。
- **Story 3（内置浏览器更新书源）**：作为用户，我在书源登录弹窗点「❇️ 更新书源」→ 内置浏览器打开
  「书源更新」页 → 我点「主线路」→ **弹出与 Story 1 相同的预览弹窗**（URL 已自动填好并拉取）→
  确认后该书源被**更新**（不是新增重复源），列表项显示「**更新**」标签。
- **Story 4（选择目标分组）**：作为用户，我在预览弹窗的「自定义源分组」里选择/新建一个分组，
  确认后本次导入的书源全部归入该分组；**若我不选**，则书源保持原分组不被改动（新源落「未分组」）。
- **Story 5（勾选控制）**：作为用户，我可以逐项勾选/取消，「取消全选（n/N）」一键切换，
  `⋮` 菜单提供全选 / 取消全选 / 反选。点「取消」不产生任何落库。
- **Story 6（异常如实告知）**：作为用户，当我填的地址是内网地址、
  非 HTTP(S)、返回非 JSON、返回体过大（> 8 MB）或票据过期时，
  我看到**明确的中文错误提示**（其余三语同样覆盖），而不是静默失败或白屏。
- **Story 7（主题跟随）**：作为用户，我在深色 / 羊皮纸 / 浅色主题下打开该弹窗，
  弹窗配色与主界面一致（复用主题变量，不出现透光背景或隐形黑字）。
- **Story 8（不合格条目如实标出）**：作为用户，当某条书源超过 1 MiB、缺少 `bookSourceUrl`
  或根本不是 JSON 对象时，我在**预览里**就看到它被标为「不可导入」并写明原因、且**勾不上**；
  确认后其余合格条目正常落库，不需要靠事后报错来猜哪条坏了。

## 4. 交付面（改动清单预览）

| 层 | 文件 | 内容 |
| :--- | :--- | :--- |
| 前端图标 | `web/src/icons.tsx` | 新增 `cloudDownload`（云 + 下箭头）图标 |
| 前端组件 | `web/src/NetworkImportModal.tsx`（新） | 预览选择弹窗（书源页与内置浏览器共用） |
| 前端接入 | `web/src/main.tsx` | 「网络导入」按钮 + 挂载弹窗 |
| 前端接入 | `web/src/SourceWebViewModal.tsx` | 接收 `import-online` 消息并上报父级 |
| 前端接入 | `web/src/SourceLoginModal.tsx` | 以**平级分支**挂载导入弹窗（严禁嵌套 backdrop） |
| 前端样式 | `web/src/styles.css` | 弹窗专用类，全部使用 `var(--surface/--ink/--line/--accent)` |
| 前端 i18n | `web/src/i18n/locales/*.json` | 四语词条 |
| 服务端代理 | `WebViewProxy.kt` | bootstrap 脚本内**捕获阶段**拦截 `yuedu://` 导入链接并 postMessage 宿主 |
| 服务端路由 | `Routes.kt` / `Models.kt` | `POST /api/sources/import-url/preview` 与 `.../commit` |
| 服务端新模块 | `NetworkSourceImport.kt`（新） | 拉取、逐条试解析（复用 `SourceCodec`）、内存票据缓存、匹配与落库编排 |
| 服务端 i18n | `I18nMessages.kt` | 错误码四语 |
| 测试 | Kotlin + `web/test/*` | 见验收基准 |
| 文档 | `SESSION-037` / `ACCEPT-024` | 实现记录与验收手册 |

## 5. 验收基准 (Acceptance Criteria)

- [ ] 书源页出现「网络导入」按钮与专属图标，位置在「导入 JSON」正下方。
- [ ] 两种链接（带 `.json` / 不带 `.json`）均能拉取并正确解析为书源数组。
- [ ] 预览弹窗逐项显示名称与「新增/更新/不可导入」标签；标签判定与 G7/G8 一致。
- [ ] 不合格条目（超 1 MiB / 缺 `bookSourceUrl` / 非 JSON 对象）在预览中标「不可导入」+ 原因，且勾不上。
- [ ] 内置浏览器点击线路后弹出同一弹窗，且**不再无响应**。
- [ ] 勾选/全选/取消全选/反选行为正确，计数 `(n/N)` 实时准确。
- [ ] 选了分组则归入该组；未选则原有分组不被改动。
- [ ] 三套主题下弹窗配色正确（无透光/隐形文字）。
- [ ] 异常路径（内网、非 HTTP、非 JSON、超大、票据过期）均有明确四语提示。
- [ ] 单测覆盖：URL 解析与形态判定、匹配（URL 命中 / 名称兜底 / 新增）、分组策略、票据过期。
- [ ] 前端测试套件全绿；服务端新用例通过，零回归（按仓库铁律比对失败集合）。
