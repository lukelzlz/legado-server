# SESSION-014：插件系统落地（双运行时、前端 SDK、WebDAV 示例）

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-09-16 |
| 类型 | Feat |
| 关联 | PROPOSAL-013、ADR-013、`docs/plugins/PLUGIN-SDK.md` |
| 状态 | Tested |

> **后续变更（同日晚些时候）**：本文记录的示例插件（`plugins-example/demo`、`plugins-example/webdav`）
> 与 JVM 插件模块 `plugin-webdav/` 已按用户要求从仓库移除，同时去掉了「内置插件播种」与 Gradle 打包机制 ——
> 仓库只保留插件宿主能力。本文其余内容作为当日的实现记录保留原样；JVM 插件路径的测试覆盖改由运行时
> 组装真实 jar 的用例承担（`JarFixturePlugin`），不再依赖内置示例。

## 需求

「把项目的接口尽量全面地引出来，并预留扩展空间；插件可以在主页面添加新的按钮、页面，
管理书籍、管理书源，给项目添加 WebDAV 等功能；插件放到指定文件夹。」

经确认为三个方向：**JS 为主 + JAR 兜底**、**宿主注入 SDK 的 JS 模块**、
**插件目录放在数据目录下 `plugins/`**。

## 落地内容

### 服务端

| 文件 | 职责 |
| --- | --- |
| `plugin-api/`（新模块） | 零依赖契约：`LegadoPlugin`、`PluginContext` + 九个能力接口、`PluginRequest/Response`、权限与事件常量、自带 `PluginJson` |
| `plugins/PluginManifest.kt` | 清单解析与严格校验（id 规则、`apiVersion` 上限、权限白名单、设置项类型、入口存在性） |
| `plugins/PluginRegistry.kt` | `LoadedPlugin` 运行态：路由/监听器/定时任务注册表、生效设置、错误信息 |
| `plugins/PluginHostContext.kt` | `PluginContext` 实现：每个能力方法内部做权限门禁；四个子能力实现（books/sources/subscriptions/covers/http/auth/storage/settings） |
| `plugins/JsPluginRuntime.kt` | Rhino 运行时：prelude 注入、`__legadoCall` 单一派发入口、Kotlin↔JS 值编解码、响应描述符解析、单线程 + 重入内联 |
| `plugins/PluginManager.kt` | 扫描/播种/激活/卸载、事件总线、调度器、路由匹配（`{name}` + 结尾 `*`） |
| `plugins/PluginRoutes.kt` | 管理端点、`web.js`/`assets` 分发、`/r/{path...}` 代理（`handle` 接受任意 HTTP 方法） |
| `Database.kt` | 新增 `plugin_state`、`plugin_kv` 两张表与对应方法 |
| `ServerConfig.kt` | 新增 `pluginsDirectory`（`LEGADO_PLUGINS_DIR`） |
| `resources/plugins/prelude.js` | JS 侧的 `legado.*` 门面（ES5，绑定到单一宿主入口） |

### 前端

新增 `pluginHost.ts`（动态 import + 注册项收集 + 逐插件错误隔离 + `deactivate` 生命周期）、
`pluginSdk.ts`（SDK 类型与注入实现）、`PluginUi.tsx`（注入给插件的九个 UI 组件）、
`PluginsPage.tsx`（插件管理页 + schema 动态表单）、`PluginPageView.tsx`（页面外壳 + ErrorBoundary）；
增量修改 `main.tsx`（`Page` 放宽为 `string`、`#plugins` 与 `#plugin:<id>:<page>` 路由）、
`AppHeader.tsx`（插件导航项/菜单项 + 插件管理入口）、`api.ts`、`icons.tsx`、`styles.css`。

### 示例插件与打包

- `plugins-example/demo/`：JS 插件，演示导航/菜单/页面/设置/存储/服务端路由。
- `plugin-webdav/`（新 Gradle 模块）+ `plugins-example/webdav/`：JVM 插件，
  把书架投影成只读 WebDAV 共享（`PROPFIND`/`GET`/`LOCK`，HTTP Basic + 管理员密码）。
- `server/build.gradle` 新增 `syncBundledPlugins`：把示例插件与编译出的 WebDAV jar 收进
  `bundled-plugins/`，并生成 `index.txt`（jar 内无法枚举目录，所以清单要落成文件）。
  启动时只播种缺失的插件文件夹，永不覆盖用户改动。

## 实施中的关键判断

1. **`RuleRunner` 的规则执行方法是阻塞非 suspend 的**（`search`/`details`/`chapters`/`content`），
   因此可以把"实时执行书源规则"直接暴露成同步插件 API，不需要在插件边界引入协程概念。
2. **单一派发入口**：JS 侧所有 API 都走 `__legadoCall(name, args)`，宿主侧是一个
   `when(operation)`。加一个能力只需在 `dispatch` 加分支 + prelude 加一行绑定，
   避免为 40 多个能力各写一个 Rhino `BaseFunction`。
3. **重入内联**：宿主事件是同步派发的，JS 路由里 `emit` 可能同步回调到自己的监听器；
   若此时再向单线程 executor 提交并 `get()` 会自等待。检测到已在插件线程则直接内联执行。
4. **文本响应补 charset**：JS 字符串按构造即 UTF-8，若插件给的 `contentType` 不含 charset
   （如 `application/json`），客户端会退回 Latin-1 把中文显示成乱码
   （联调时 PowerShell 的 web 客户端正是如此）。宿主统一为文本类 content type 补 `charset=utf-8`。
5. **停用的插件返回 503 而非 404**：否则「插件坏了」会伪装成「路由不存在」，
   把真实原因藏起来。
6. **`web.js` 的导出写法必须归一化**（联调后修复）：宿主最初只读具名导出 `loaded.activate`，
   而契约与两个示例插件写的都是 `export default function activate(sdk)`，于是界面报
   「web.js 未导出 activate 函数」——作者明明导出了，报错与事实相反，排查方向被彻底带偏。
   现在 `web/src/pluginModule.ts` 同时接受默认导出函数、具名导出、默认导出对象三种等价写法，
   并把 `deactivate` 绑定回原对象；报错文案直接写明正确写法。
7. **示例插件必须用真实 SDK 跑一遍**（联调后修复）：`demo/web.js` 写了 `api.settings.get()`，
   但 `settings` 是 SDK 顶层成员、`api` 上只有 `get/post/put/del/plugin/pluginRaw`，
   于是运行时抛 `Cannot read properties of undefined (reading 'get')`。web.js 是未编译的原生
   ES module，**tsc 完全拦不住**。因此新增 `web/test/plugin-examples.test.ts`：用真实 SDK 激活示例插件、
   调用其注册的菜单项 `onClick`、并用 `react-dom/server` 渲染其注册的页面 —— 这条测试能直接抓住该类错误。
   同批还修正了 webdav 示例里几个并不存在的 CSS 类名，并把两个示例插件版本提到 `1.0.1`（前端按 `version` 防缓存）。
8. **插件清单与脚本必须容忍 UTF-8 BOM**：Windows 编辑器加 BOM 是常态，带 BOM 的 JSON 解析失败时
   报错完全看不出是编码问题。`plugin.json`、`server.js`、下发的 `web.js` 三处读取都 `removePrefix(BOM)`。
9. **插件设置端点不能用 `call.respond(Map<String, Any?>)`**（联调后修复）：Ktor 的 kotlinx 转换器会
   退化为「按运行时值猜序列化器」，`demo` 的设置同时含字符串、布尔、数字，直接抛
   `Serializing collections of different element types is not yet supported`，前端只看到
   「服务器内部错误」。**同构数据（只有字符串）完全正常**，所以最早那版 fixture 测试没抓到它；
   改成按 JSON 文本下发，并把 fixture 改成混合类型（已验证：旧代码下这条测试会失败）。

## 验证

- 新增 `PluginSystemTest`（6 个用例）：JS 路由/设置/存储/权限拒绝/路径参数、启停切换、
  **内置 WebDAV jar 插件的 `PROPFIND`（含 401 与 207、href 前缀）**、清单校验、
  `apiVersion` 上限、`PluginJson` 往返（含转义与代理对字符）。
- 前端：`npm run check` 0 错误、`npm run build` 成功、既有 120 个前端测试全绿（子任务验证）；
  追加 `plugin-host-module.test.ts`（6 个用例覆盖三种导出写法、`this` 绑定、报错文案）后为 **126 个全绿**。
- 真机联调（`http://127.0.0.1:8080`）：登录 → `GET /api/plugins` 返回 demo/webdav 两项且字段正确 →
  `GET /api/plugins/demo/r/config` 中文为正确 UTF-8 → `/visit` 存储计数生效 →
  `web.js` 以 `text/javascript; charset=utf-8` 分发 → WebDAV `PROPFIND` 认证后 207、未认证 401。

## 已知环境问题（非本次改动引入）

在 Windows 上 `gradlew :server:test` 会有一批既有测试失败：
它们在 `finally` 里直接 `Files.deleteIfExists(<sqlite>)`，而测试自身**从不调用
`Database.close()`**，Windows 不允许删除仍被占用的文件（macOS 的 POSIX 语义允许，因此原开发
环境不会暴露）。已用干净 worktree（改动前的 HEAD）复现同一批失败，确认与本特性无关。
本次新增的插件测试因此把清理改为尽力而为（`runCatching`），以免平台怪癖掩盖真实断言。

另：`gradle-wrapper.properties` 保持官方 `services.gradle.org` 源，但该地址在当前网络下
只有约 2 KB/s，首次拉取 131MB 分发包会卡死；本次是手动从 GitHub 的 `gradle/gradle-distributions`
release 下载并放入 wrapper 缓存目录解决的，**仓库文件未做任何镜像化修改**（CI 仍走官方源）。
