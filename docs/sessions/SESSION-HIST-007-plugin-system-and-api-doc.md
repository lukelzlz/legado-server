# 历史归档：插件系统（宿主 + SDK + JAR/JS 双运行时）与 API 文档生成

> **来源会话**：`session-09fe8981-c5e7-4d21-b148-a15a886e7785`（2026-09-14 ~ 09-16，主会话）、子代理 `5822af63`（前端宿主与 SDK）、子代理 `2b99319c`（API 文档）
> **分支状态**：全部工作在 **`feat/plugin-system` 分支**（fork `wfanan/wfanan`，曾 force-push f2dd0f3 → fbb109e），**未合并 `master`**；当前 `master` 工作区不含 `plugin-api/`、`plugins-example/`、`web/src/PluginsPage.tsx` 等文件。
> **关联后续**：本归档中被当作「插件能力示例」的 WebDAV，最终改为**服务端原生实现**（见 [`PROPOSAL-015`](../proposals/PROPOSAL-015-webdav-storage-server.md) / [`SESSION-016`](./SESSION-016-webdav-storage-server.md)）。

---

## 1. 需求溯源

用户原始诉求（`session-09fe8981` 第 7 轮）：

> 引出该项目的接口尽量全面，并预留拓展的空间，这个接口用来制作插件，插件可以在主页面添加新的按键、页面什么的，管理书籍，管理书源，**给项目添加 webdav 等功能**，插件放到指定文件夹。

由此确立两条技术路线：**① 先做插件系统（服务端 plugin-api + 运行时 + 前端宿主与 SDK）**，**② WebDAV 先以插件形式验证**（`plugin-webdav/` JAR 插件），后续再评估是否下沉为服务端原生能力（最终在 `SESSION-016` 中落地为原生实现）。

---

## 2. 落地架构

### 2.1 服务端：轻量契约模块 + 宿主运行时

| 位置 | 内容 |
| --- | --- |
| `plugin-api/`（新 Gradle 模块） | `io.legado.plugin.api`：`LegadoPlugin`、`PluginContext`、`PluginModels`、`PluginJson`、`PluginConstants`；JAR 插件只需依赖这个轻量模块，**不必依赖整个服务端** |
| `server/.../plugins/` | `PluginManifest`、`PluginRegistry`、`PluginManager`、`PluginHostContext`、`JsPluginRuntime`、`PluginRoutes` + `resources/plugins/prelude.js` |
| 发现机制 | JAR 插件走 **ServiceLoader**（`META-INF/services/io.legado.plugin.api.LegadoPlugin`）；JS 插件走 `web.js` + `server.js` 运行时 |
| 插件目录 | `LEGADO_PLUGINS_DIR`（默认 `<数据目录>/plugins/<id>/`） |
| 存储 | 插件状态与 KV 表加在 `Database.kt` 内（`connect`/`write` 是私有成员，禁止在外部另建连接） |
| 端点 | `/api/plugins*` 共 10 个（列表/详情/启停/重载/设置/存储/权限门禁/公共路由等） |

### 2.2 前端：宿主 + SDK + 动态注册

- 新增 `web/src/pluginSdk.ts`（SDK 契约与构造）、`pluginHost.ts`（宿主 hook）、`PluginUi.tsx`（注入给插件的宿主 UI 组件）、`PluginsPage.tsx`（插件管理页）、`PluginPageView.tsx`（插件页面容器 + ErrorBoundary）、`pluginModule.ts`（动态模块加载）。
- `AppHeader.tsx` 从「写死 5 个导航按钮」改为**动态渲染插件导航项**；`main.tsx` 的 `AppPage` 硬编码联合类型必须放宽（页面路由改为 `string` + hash 路由）才能承载插件页面。
- SDK 公共面：`sdk.api`（`get/post/put/del/plugin/pluginRaw`）、`sdk.settings`（**顶层成员，与 `api` 平级**）、`sdk.storage`、`sdk.toast`、`sdk.ui`、`sdk.hooks`。
- 插件前端资源防缓存依据是**插件版本号**：`web.js?v=<version>`，改动插件后需提升 `version` 并调用重载接口（返回 `{"reloaded":n}`）。

### 2.3 示例与测试

- 示例插件：`plugins-example/demo`（`plugin.json` / `web.js` / `server.js` / `README.md`）、`plugins-example/webdav`；另设 `plugin-webdav/`（ServiceLoader JAR 插件，内含 PROPFIND + Basic 鉴权）。
- 测试：`server/src/test/.../PluginSystemTest.kt`（含 `JarFixturePlugin.kt`：ServiceLoader 发现、类加载、路由、public 路由、停用时调用 `deactivate`）、`web/test/plugin-host-module.test.ts`、`plugin-sdk-surface.test.ts`、`plugin-examples.test.ts`。
- 文档（**位于插件分支**）：`docs/plugins/PLUGIN-SDK.md`、`docs/proposals/PROPOSAL-011-plugin-system-and-extension-points.md`、`docs/decisions/ADR-011-plugin-runtime-dual-js-jar-and-trust-model.md`、`docs/sessions/SESSION-012-plugin-system-implementation.md`。

---

## 3. 四类真实排错（全部为「现象 → 根因 → 修法」）

### 3.1 前端加载失败：`web.js 未导出 activate 函数`

- **根因**：**契约与实现不一致**。契约文档与 SDK 示例（以及自家的 WebDAV 插件）都写 `export default function activate(sdk)`，而宿主加载器读的是 `loaded.activate`（**具名导出**）——默认导出挂在 `loaded.default` 上，取不到。
- **定位手法**：用 node **真实 import 被服务端托管的两个 `web.js`**，打印导出面：
  ```
  demo:   exports=[deactivate, default]  activate=default function
  webdav: exports=[default]              activate=default function
  ```
- **修法**：加载器兼容默认导出与具名导出两种形态，并统一在 SDK 文档中固化约定。
- **沉淀**：跨端契约（宿主 ↔ 插件）必须用「真实模块导出面」验证，不能只靠文档一致。

### 3.2 插件页面报 `Cannot read properties of undefined (reading 'get')`

- **根因**：示例插件自身 bug —— `const { hooks, ui, api, toast, storage } = sdk` 少取了 `settings`，却调用 `api.settings.get()`；`settings` 是 **SDK 顶层成员**，不在 `api` 上。
- **修法**：修正示例（`load()` 与菜单 `onClick` 两处），并补断言测试防回归。

### 3.3 示例设置接口 500：`Serializing collections of different element types is not yet supported`

- **根因**：`call.respond(settingsMap)` 的静态类型是 `Map<String, Any?>`，values 混有 `String/Boolean/Long`，kotlinx 序列化猜不出统一序列化器而抛异常。
- **修法**：响应必须是**强类型 `@Serializable data class`**（与 AGENTS.md §5「严禁 `Map<String, Any>`」同源，本条目补充了具体异常文本便于检索）。
- **附带**：demo 插件返回 `contentType: 'application/json'`（无 charset），Windows PowerShell 按 Latin-1 解码导致中文乱码 → 宿主返回体应显式补 `charset=utf-8`。

### 3.4 重载接口返回 `{"reloaded":0}`：所有插件被判定清单非法而卸载

- **根因**：**Windows PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会写入 BOM**，`plugin.json` 开头多出 `\uFEFF`，清单解析失败。
- **修法**：既修文件（去 BOM），也修解析器（**清单解析必须兼容 UTF-8 BOM**）——与 Legado 生态「书源导入需兼容 BOM」的历史经验一致。
- **附带**：插件页面会读 `window.location.origin`，在 node（SSR/测试）下没有 `window` → 测试需注入或封装（与 `WebDavSettingsPage` 的 `currentOrigin()` 是同一个坑）。

---

## 4. 收尾与清理（用户明确要求）

- 清除本地数据（当时数据目录只有数据库/会话/插件状态，无用户内容）。
- 删除已装载的两个插件与其源码，**仓库只保留插件系统本身**（能力保留、示例不再随项目分发）；`web/test/plugin-examples.test.ts` 收敛为「SDK 公共面」测试，避免引用已删除目录。
- 清理后基线复验：前端 127 测试全绿、类型检查 0 错误；服务端 161 测试 / 52 失败 —— 与基线失败数完全一致（详见 [`SESSION-HIST-008`](./SESSION-HIST-008-windows-environment-and-verification-baseline.md)）。

---

## 5. 历史遗留问题（供后续合并时处理）

1. **文档编号冲突**：插件分支的 `PROPOSAL-011 / ADR-011 / SESSION-012` 与 `master` 上已被占用的 011/012（替换规则引擎、替换规则一级页面）**撞号**。合并插件分支时必须整体重新编号（顺延到 015+），否则索引会自相矛盾。
2. **插件系统的最终归属未决**：插件宿主/SDK/JAR 运行时均在 `feat/plugin-system` 分支，`master` 未包含；WebDAV 最终走原生实现，可作为「插件能力 vs 原生能力」边界的参考案例。
3. **API 文档产物在仓库外**：`C:\Users\w1593\Desktop\reader\API.md`（约 2500~3000 行，覆盖 79 个端点 + 5 条静态路由 + 插件 10 端点）刻意放在 `start-legado.cmd` 同目录、**不进仓库**（用户明确要求）。
