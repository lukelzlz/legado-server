# ADR-005: 书源登录鉴权、动态 LoginUI 驱动与凭据状态持久化

## 1. 状态 (Status)
- **状态**：`Accepted`
- **日期**：2026-08-31
- **决策者**：AI 协同团队 & 项目核心架构师

## 2. 上下文与挑战 (Context & Challenges)
Legado 原生 Android 客户端通过 Android UI 动态构建登录弹窗，并在 Rhino/QuickJS 环境中执行书源自定义登录脚本。
在纯无头 Ktor 服务端与 React 客户端架构下，存在以下技术挑战：
1. **沙箱上下文隔离**：书源 JS 经常在多步骤间共享状态（如登录后设置的 Token、全局变量与 Cookie），必须保证书源级别的上下文与 CookieJar 隔离与安全落盘。
2. **多模式 LoginUI 解析**：`loginUi` 可以是纯 JSON 数组，也可以是以 `@js:` 开头的动态 JavaScript 表达式，必须支持沙箱求值解析。
3. **跨端 Action 执行与事件总线**：在服务端执行 `loginUrl` / `action` 时，客户端需要接收包括 `toast`、唤起浏览器 URL、写入剪贴板及触发 UI 重绘的信号。

## 3. 架构决策与选型 (Architecture Decisions)

### 3.1 SQLite 独立凭据状态表与热迁移
- 创建独立表 `source_login_state`：
  - `source_id PRIMARY KEY`
  - `login_info TEXT` (JSON 键值对)
  - `login_header TEXT` (格式化 Headers 字符串或 JSON)
  - `source_variable TEXT` (书源全局变量)
  - `source_kv TEXT` (键值存储)
  - `cookie_jar TEXT` (域名到 Cookie 的映射)
- 对原有 `source` 表增加 `has_login` 标识，并在服务启动时通过 SQLite `PRAGMA table_info` 自动增量热迁移，保障已有十万级书源库平滑升级。

### 3.2 沙箱桥接与生命周期拦截
- 在 Rhino `JsSandbox` 中通过 `JsExecutionContext` 注入 `source`、`cookie`、`java` 顶层桥接。
- 在 `RuleRunner.fetch` 请求中自动注入持久化的 `Cookie` 与 `LoginHeader`，并解析响应报文中的 `Set-Cookie` 实时同步落盘。

### 3.3 声明式 Flexbox 动态渲染
- Web 端采用 CSS Flexbox 响应式排版容器，读取 `layout_flexBasisPercent`（如 0.5 两列并排、1.0 通栏）并处理控件类型（`text`, `password`, `toggle`, `select`, `button`, `label`），1:1 还原原生 Android 弹窗交互。

## 4. 后果与收益 (Consequences & Benefits)
- **收益**：
  - 完美兼容 Legado 官方与社区所有 VIP / 登录书源。
  - 前后端职责彻底解耦，服务端保持纯 JVM 无头架构，Web 端轻量响应式渲染。
  - 100% 覆盖单测与静态检查，无锁竞争与性能瓶颈。
