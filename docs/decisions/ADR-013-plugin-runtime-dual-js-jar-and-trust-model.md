# ADR-013：插件运行时选型——双运行时（Rhino JS + JVM JAR）与信任模型

| 项目 | 内容 |
| --- | --- |
| 编号 | ADR-013 |
| 状态 | Accepted |
| 关联 | PROPOSAL-013、`docs/plugins/PLUGIN-SDK.md` |

## 背景

要让第三方扩展服务端，必须先回答四个问题：

1. 插件用什么语言写、怎么加载？
2. 插件能接触多少宿主内部？
3. 前端界面由谁渲染、在什么上下文里跑？
4. 插件被当作可信代码还是不可信代码？

每一个问题的选择都会反过来决定接口的形状，因此记录在此。

## 决策

### 1. 双运行时：`server.js`（Rhino 沙箱）+ `lib/*.jar`（ServiceLoader）

**选择**：同时支持 JS 与 JVM 插件，二者共用同一套 `PluginContext` 与权限清单，可共存于同一插件。

**理由**：

- **纯 JS 沙箱**做不到协议级能力。WebDAV 需要 `PROPFIND`/`LOCK`、流式读取与稳定的 XML 生成，
  在 Rhino 里手写 XML 拼装既脆弱又慢；这类需求必须落到 JVM。
- **纯 JAR** 又太重。改一行提示语、加一个统计路由、订阅一个事件，都要编译打包，
  插件作者门槛过高，而仓库已有 Rhino 沙箱与 JS 生态可复用（与 Legado 书源生态一致）。
- 因此按 **能力重量** 分流：轻逻辑走 JS 即刻生效，重能力走 JAR。

**代价**：两套运行时都要维护，且能力并非完全对称（例如 JS 侧无法直接拿到 `Path`，
所以 `covers.fileFor` 只存在于 Kotlin）。这类差异在 SDK 文档中显式列出，而不是强行抹平。

### 2. 只暴露策展过的能力，不暴露内部对象

**选择**：插件拿到 `PluginContext`（`books`/`sources`/`subscriptions`/`covers`/`http`/`storage`/
`settings`/`events`/`auth` 九个能力接口），拿不到 `Database`、`RuleRunner`、Ktor 类型。

**理由**：

- 直接给 `Database` 会让插件与数据库 schema 强耦合，任何一次内部重构都会破坏插件。
- 策展层是权限校验的唯一位置：能力方法内部统一 `require(permission)`，
  审计时只需看一个文件，不存在"某条路径绕过校验"的可能。
- 跨边界数据统一为 JSON 形状（`Map<String, Any?>`），配合零依赖的 `PluginJson`，
  插件 jar 可以小到只有一个类（`plugin-api` 零第三方依赖，ServiceLoader 负责发现）。

**代价**：宿主每加一个能力要改三处（`plugin-api` 接口、`PluginHostContext` 实现、
JS prelude 绑定）。这是刻意的摩擦，用来防止接口随意膨胀。

### 3. 前端由宿主注入 SDK，插件只提供模块

**选择**：`web.js` 是标准 ES module，导出的 `activate(sdk)` 接收宿主构造的 SDK
（`React`、`hooks`、`ui` 组件、`api`、`toast`、`storage`、`settings`、`navigate`、
`register*`）。宿主动态 `import` 它，并把注册的页面渲染在既有外壳的内容区。

**被否决的方案**：

- **声明式 schema**（清单里写 JSON 描述界面）：安全但做不出复杂交互，
  WebDAV 的文件浏览、规则编辑器这类界面无法表达。
- **iframe + postMessage**：隔离最好，但样式割裂、无法嵌进主布局，
  且每个插件都要自己实现一套通信协议。

**理由**：插件复用宿主同一份 React 运行时（插件禁止自带 React），
因此界面风格天然一致；页面被 ErrorBoundary 包裹，单插件崩溃只显示错误卡片。

**代价**：插件 JS 运行在页面上下文，可访问 DOM 与 `localStorage`（宿主的存储按插件 id 加前缀，
但这不是安全边界）。对自托管个人服务而言，插件本就由管理员自己安装，该代价可接受。

### 4. 插件是受信任扩展，但仍做权限门禁

**选择**：插件视为受信任代码（JVM 插件完全不受沙箱限制），但**每个能力调用都校验
`plugin.json` 里声明的权限**，未声明即抛 `插件未声明权限: xxx`。

**理由**：

- 目标是 **可审计与防误用**，不是防恶意。真正防恶意需要进程级隔离（独立 JVM + IPC），
  复杂度与收益不成比例。
- 硬失败（抛异常）而非静默返回空数据，让插件作者立刻发现配置错误，
  而不是去 debug 一个"为什么书架是空的"的幽灵问题。
- JS 沙箱仍然保留 `ClassShutter` 全拒绝与 safe 标准库，避免书源 JS 生态里的
  历史问题（反射 RCE）在插件里重演。

### 5. JS 插件单线程执行 + 重入内联

**选择**：每个 JS 插件一个专属单线程 executor；所有脚本入口（路由、事件、定时任务）
都编组到该线程；当调用已在该线程上时**直接内联执行**。

**理由**：

- Rhino scope 非线程安全，串行化是最简单正确的做法，同时让插件作者可以使用模块级变量。
- 宿主事件是同步派发的：路由处理器 `emit` 一个事件时，可能同步回调到本插件自己的监听器。
  此时若再向单线程 executor 提交并 `get()`，就会自等待死锁——重入检测是必需项而非优化。

### 6. 插件路由默认需要会话，协议端点走 `routes.public`

**选择**：`/api/plugins/<id>/r/...` 默认要求管理员会话；需要对外提供协议服务时，
声明 `routes.public` 权限并用 `routePublic` 注册，鉴权由插件自己完成。

**理由**：WebDAV/OPDS 的客户端是文件管理器与阅读器，不可能携带浏览器会话 Cookie；
但把"免鉴权"做成默认行为会让插件端点默认裸露在公网上。用显式权限把风险摊到台面上，
并在文档里说明插件必须自行鉴权（协议插件的典型做法是用 `auth.verifyAdminPassword` 校验 HTTP Basic）。

## 影响

- `plugin-api` 成为对外承诺的稳定接口；破坏性变更需提升 `apiVersion`，
  且 `PluginManifests.validate` 会拒绝声明了更高版本的服务端不支持的插件。
- `PluginView.settingsSchema` 与清单里的 `settings` 是同一份 schema 的两个视图：
  清单键叫 `settings`（声明设置），API 字段叫 `settingsSchema`（暴露 schema）。
- 新增数据库表 `plugin_state`（启用开关 + 设置）与 `plugin_kv`（私有键值存储）。

## 后续可选演进

- 插件签名与来源校验；
- `plugin-api` 的独立发布（mavenLocal / GitHub Packages），让 JVM 插件不依赖本仓库构建；
- 插件级速率限制与资源配额；
- JS 侧补齐 `covers.fileFor` 之类的对称能力（需要引入"插件私有文件句柄"概念，暂不做）。
