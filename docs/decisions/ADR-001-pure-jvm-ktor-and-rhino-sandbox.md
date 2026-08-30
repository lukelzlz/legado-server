---
id: ADR-001
title: 彻底解耦 Android 原生平台，采用 Ktor + Kotlin JVM + Rhino JS 沙箱
status: accepted
date: 2026-08-15
---

# ADR-001: 彻底解耦 Android 原生平台，采用 Ktor + Kotlin JVM + Rhino JS 沙箱

## 1. 决策背景 (Context)
Legado 原生项目是专为 Android 操作系统构建的客户端 App，底层依赖 Android SDK、Android Room 数据库、Android WebView 及 Activity/Service 生命周期。为了将 Legado 的核心能力（规则解析、多书源抓取、离线缓存）迁移到标准 Linux 服务器、Docker 容器与 NAS 等无头环境，必须对底层运行时架构进行重构决策。

## 2. 裁定方案 (Decision)
1. **服务端框架**：采用 **Kotlin JVM + Ktor** 异步轻量级框架作为服务端核心。
2. **规则引擎沙箱**：采用 **Mozilla Rhino** 作为 JavaScript 规则执行沙箱，配合 **Jsoup**（HTML DOM 解析）与 **JsonPath**（JSON 解析），提供完全跨平台的 Legado 规则执行引擎（`RuleRunner`）。
3. **消除 Android 运行时**：严禁在 `server/` 与 `web/` 模块中引入任何 Android Framework 原生包。

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：使用 Robolectric 模拟 Android 环境运行原生代码**
  - *否决理由*：Robolectric 启动极慢、内存开销巨大（单个实例数百 MB）、并发性能低下且维护复杂度极高，不适合生产级服务端。
- **备选方案 B：使用 Node.js / Puppeteer 运行无头浏览器**
  - *否决理由*：无头 Chrome 镜像极大（>1GB），内存开销以 GB 计算，且无法直接复用已有的 Kotlin/Java 规则解析生态与高效并发协程。
- **备选方案 C：使用 GraalVM Polyglot JS**
  - *否决理由*：GraalVM 体积庞大且需要特定 JDK 运行时，Rhino JS 纯纯嵌入标准 JVM，对跨平台 Docker 镜像更轻量友好。

## 4. 后果与权衡 (Consequences & Trade-offs)

### 正面收益
- 服务端可在任何标准 JVM (Java 17+) 及 Docker 环境中秒级启动。
- 内存基线仅需 ~100MB，单容器可轻松并发承载数百个书源搜索。
- 与前端 Web UI 完全解耦，对外暴露标准的 REST 与 WebSocket 接口。

### 负面代价
- 需要重新实现部分 Android 特有规则工具类（如字符串编码、正则二次提取、特定加密算法），但已通过 `RuleRunner` 完全覆盖并通过全量回归测试。
