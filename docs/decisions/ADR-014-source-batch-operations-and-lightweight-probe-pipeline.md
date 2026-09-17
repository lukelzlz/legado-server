---
id: ADR-014
title: 书源批量事务管道、轻量并发探针与浮动管理状态机
status: accepted
date: 2026-09-16
---

# ADR-014: 书源批量事务管道、轻量并发探针与浮动管理状态机

## 1. 决策背景 (Context)

随着书源数据规模增加，用户需要高效整理、归类书源，并排查由于上游域名过期、反爬拦截等原因失效的书源。需要确定以下关键架构与技术选型：
1. 服务端如何高效承载书源的批量启用、停用、删除、修改分组与导出；
2. 书源健康体检的策略深度与并发控制模型（如何兼顾速度、准确性与网络资源消耗）；
3. 前端书源管理页面的批量选择状态机与浮动操作栏组件设计。

---

## 2. 核心架构决策 (Decisions)

### 决策 1：服务端批量操作事务管道与参数化更新
- 在 `Database.kt` 中设计统一的批量操作入口 `batchUpdateSources(ids: List<String>, action: BatchSourceAction, group: String?)`：
  - 针对 `enable` / `disable`：使用 `UPDATE source SET enabled = ?, updated_at = ? WHERE id IN (...)` 单条 SQL 批处理。
  - 针对 `delete`：使用 `DELETE FROM source WHERE id IN (...)` 并同步清理关联的 `source_login_state`。
  - 针对 `set_group`：使用 `UPDATE source SET source_group = ?, updated_at = ? WHERE id IN (...)`。
- 在 `Routes.kt` 中暴露 `POST /api/sources/batch`，使用强类型 DTO 接收请求，防止非法的空操作或越权注入。

### 决策 2：轻量化两阶段健康探针模型 (Lightweight Two-Stage Probe)
- **阶段一（静态语法与 URL 检查）**：检测书源 JSON 结构合法性与 `bookSourceUrl` 格式。
- **阶段二（并发连通性探测）**：
  - 优先探测 `searchUrl` 或 `bookSourceUrl` 的基础连通性，采用 HTTP HEAD（若 405/403 则降级为带 Range/User-Agent 的 GET 请求）。
  - 设置单源严格超时为 5 秒，使用 Kotlin 协程 `async(Dispatchers.IO)` 配合 `Semaphore(8)` 严格限制并发数量为 8，防止突发流量打满服务器出口带宽或被上游判定为 DDoS。
  - 记录精确往返耗时（`latencyMs`），捕获 `ConnectTimeoutException`, `UnknownHostException`, `SSLHandshakeException` 等明确网络异常并转换为用户易懂的提示。

### 决策 3：前端集成式多选与浮动操作状态机 (Integrated Batch State Machine)
- 避免新建割裂的二级页面，直接在现有 `SourcesPage` 内集成批量模式：
  - `isBatchMode: boolean` 控制多选框的显隐与列表多选状态。
  - `selectedIds: Set<string>` 维护当前选中的书源 ID 集合。
  - 顶部增加「健康体检」与「批量管理」触发按钮，底部提供响应式悬浮操作条（Floating Action Bar），在移动端自动适配 `env(safe-area-inset-bottom)`。
  - 批量分组修改提供快捷弹窗，输入或选取已有分组后一键应用。
  - 体检完成后在顶部或弹窗呈现统计面板，提供「一键停用失效源」的快速捷径。

---

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：深度全流程测试（搜索 -> 目录 -> 正文）作为体检标准**
  - *否决理由*：几百个书源若全部跑一遍搜索、目录和正文抓取，需要数分钟甚至更久，极易触发上游站点的反爬风控导致服务器 IP 被封禁，且消耗大量 CPU/内存。轻量连通性 + 语法探测可在 10~20 秒内完成数百个书源的快速筛查，速度与安全性最优。
- **备选方案 B：独立开辟“书源体检”二级新页面**
  - *否决理由*：增加页面跳转割裂感，用户在体检后仍需回到原页面进行书源编辑和删除。将体检状态与多选操作原生融入主列表，体验更自然流畅。

---

## 4. 后果与权衡 (Consequences & Trade-offs)

### 正面收益
1. 极大提升大规模书源库的维护效率，用户可一键清理死链、整理分类。
2. 轻量探针性能极高，数百个书源可在短时间内快速检测完毕。
3. 纯原生 JVM 协程实现，零外部基础设施依赖。

### 负面代价与应对
- **少数书源可能封禁单纯的 HEAD/GET 探测**：在探针中设置通用浏览器的 `User-Agent` 与防盗链头，并针对 403/405 等特定状态码作自适应降级探测，降低误判率。
