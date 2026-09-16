# PROPOSAL-013：插件系统与可扩展接口体系

| 项目 | 内容 |
| --- | --- |
| 编号 | PROPOSAL-013 |
| 状态 | Implemented |
| 关联 | ADR-013、`docs/plugins/PLUGIN-SDK.md` |

## 1. 背景与动机

Legado Server 早期把所有能力都编译进单一 fat JAR：书源管理、离线缓存、TTS、替换净化……
每加一个功能都必须改核心代码、重新编译、重启服务。随着部署场景从「个人 VPS」扩展到
NAS、家用电脑与容器，用户开始提出这些需求：

- 在主页面加自己的按钮与页面；
- 把书架共享出去（WebDAV / OPDS 这类协议）；
- 对接自己的存储、通知、自动化流程；
- 在不 fork 仓库的前提下做定制。

这些需求共同指向一件事：**服务需要一套稳定的扩展点**，而不是让每个需求都变成一个核心 PR。

## 2. 目标

1. 第三方代码在不修改、不重新编译服务端的前提下扩展后端能力与 Web 界面。
2. 插件放在一个约定目录里，放进去即可被识别，可在运行期启用/停用/重载。
3. 同时支持两类插件：轻量的 JS 插件（沙箱内执行）与重型 JVM 插件（JAR）。
4. 前端扩展点覆盖：导航按钮、功能菜单项、整页界面。
5. 后端扩展点覆盖：书籍、书架、书源、订阅、封面、出站 HTTP、私有存储、设置、事件、定时任务、自定义路由。
6. 能力必须可审计：每个能力都要显式声明权限，未声明即拒绝。

## 3. 非目标

- 不做插件市场与在线分发（插件由管理员自行放置）。
- 不做多租户隔离：插件与宿主同权限域，视为受信任扩展。
- 不为 JS 插件提供完整 Node 生态（无 `require`、无 npm 依赖）。

## 4. 方案概要

### 4.1 目录与清单

```
<dataDir>/plugins/<id>/
├── plugin.json     清单：入口、权限、设置项 schema
├── server.js       后端脚本（Rhino 沙箱）
├── web.js          前端 ES module（宿主注入 SDK）
├── lib/*.jar       JVM 插件（ServiceLoader）
└── data/           插件私有可写目录（自动创建）
```

插件目录放在数据目录下（可用 `LEGADO_PLUGINS_DIR` 覆盖），因此备份或容器挂载数据目录时
插件随之持久化，升级服务端代码也不会丢插件。仓库本身不内置任何插件，只提供宿主能力。

### 4.2 双运行时

| 运行时 | 适用 | 隔离 |
| --- | --- | --- |
| `server.js`（Rhino） | 轻量逻辑：路由、钩子、定时任务 | 沙箱：`ClassShutter` 拒绝一切 Java 类，safe 标准库，无文件系统 |
| `lib/*.jar`（JVM） | 协议级能力、流式 IO、性能敏感 | 无沙箱，等同普通 Java 代码 |
| `web.js`（ES module） | 前端界面 | 运行在页面上下文，使用宿主注入的 `sdk.React` 与 `sdk.ui` |

两者可共存于同一插件，共享同一份权限与同一个 `PluginContext`。

### 4.3 接口边界

- 新增 `plugin-api` 模块：**零第三方依赖**的稳定契约（`LegadoPlugin`、`PluginContext`、
  `PluginRequest/Response`、权限与事件常量、自带 JSON 编解码）。
- 插件不接触 `Database` / `RuleRunner` 等内部对象，只通过策展过的能力接口操作数据，
  宿主在每个调用点做权限校验。
- 跨边界数据统一为 JSON 形状的 `Map<String, Any?>`，因此服务端可以自由重构内部模型。

### 4.4 前端扩展点

宿主在用户登录后动态 `import` 各插件的 `web.js`，把 SDK 注入 `activate(sdk)`；
插件通过 `registerNavItem` / `registerMenuItem` / `registerPage` 注册界面。
插件页面渲染在宿主外壳内容区，外层用 ErrorBoundary 隔离，单个插件崩溃不影响其他插件与应用。

### 4.5 路由与鉴权

- 插件路由挂在 `/api/plugins/<id>/r/<path>`，默认需要管理员会话。
- 协议端点（WebDAV 等）需要 `routes.public` 权限，注册后由插件自行鉴权。
- 管理端点 `/api/plugins/*` 负责列表、重载、启停、设置读写与 `web.js`/`assets` 分发。

## 5. 交付物

| 交付 | 位置 |
| --- | --- |
| 插件契约模块 | `plugin-api/` |
| 服务端运行时（清单/管理/宿主上下文/JS 桥/路由） | `server/src/main/kotlin/io/legado/server/plugins/` |
| 数据库表与存储方法 | `server/.../Database.kt`（`plugin_state`、`plugin_kv`） |
| 前端宿主与 SDK | `web/src/pluginHost.ts`、`pluginSdk.ts`、`PluginUi.tsx`、`PluginsPage.tsx`、`PluginPageView.tsx` |
| 插件开发指南 | `docs/plugins/PLUGIN-SDK.md` |
| 测试（含运行时组装真实 jar 验证 JVM 插件路径） | `server/src/test/kotlin/io/legado/server/PluginSystemTest.kt` |

## 6. 验收标准

1. 放入一个含 `plugin.json` + `server.js` 的文件夹，重启或重载后路由可用。
2. JS 插件可通过 `legado.*` 读写书架/书源/设置/存储，未声明权限时调用报错。
3. 前端能加载 `web.js`，导航按钮、菜单项与页面均可用，单插件报错不影响全局。
4. JVM 插件能注册任意 HTTP 方法的公开路由并自行鉴权（协议类端点的关键能力，由测试中的 jar 插件用例覆盖）。
5. 启停插件即时生效，无需重启服务。
6. `gradlew :server:test` 中插件相关测试通过。

## 7. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 插件代码破坏服务 | 前端 ErrorBoundary 隔离；后端每个监听器/任务/路由调用独立 try-catch；单插件失败只记录错误不阻断启动 |
| 插件越权 | 权限清单 + 调用点校验；JS 沙箱禁用 Java 反射 |
| 插件泄漏资源 | 插件在专属单线程 executor 上执行，卸载时关闭 executor、类加载器并取消定时任务 |
| JS 调用宿主造成死锁 | 同一插件线程上重入时直接内联执行，避免单线程 executor 自等待 |
| 出站请求被滥用做 SSRF | 默认拒绝内网/回环/链路本地地址，需显式 `http.private` |
