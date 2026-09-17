# SESSION-015: 书源批量整理、分组维护与轻量连通性健康体检体系

- **日期**：2026-09-17
- **状态**：Verified & Documented
- **关联提案**：[`PROPOSAL-014`](file:///root/legado-server/docs/proposals/PROPOSAL-014-book-source-batch-management-and-health-check.md)
- **关联架构决策**：[`ADR-014`](file:///root/legado-server/docs/decisions/ADR-014-source-batch-operations-and-lightweight-probe-pipeline.md)
- **关联验收手册**：[`ACCEPT-014`](file:///root/legado-server/docs/acceptance/ACCEPT-014-source-batch-and-health-check.md)

---

## 1. 核心需求与背景

在日常使用 Legado 导入网络书源合集（通常包含几十至上百个书源）后，用户常面临以下痛点：
1. **书源失效与连通性未知**：部分源域名过期或不可达，搜索时产生大量无意义超时，严重拖慢搜索效率。
2. **缺乏批量操作**：只能逐个点击编辑、启用、停用或删除，在管理 50+ 书源时操作极其繁琐。
3. **缺乏分组体系与筛选**：大量书源混杂，缺少类似 Legado 移动端的分组分类与按组筛选视图。
4. **移动端触控与安全区体验**：在移动端屏幕下需要清晰的底部悬浮操作栏（Floating Action Bar），同时避让底栏虚拟指示条（Home Indicator）。

---

## 2. 实施方案与技术实现

### 2.1 服务端原子管道与轻量探针 (Ktor + Kotlin)
- **批量操作接口 (`POST /api/sources/batch`)**：
  - 支持 `enable`（批量启用）、`disable`（批量停用）、`delete`（批量删除）、`set_group`（批量设置/清除分组）。
  - 在 SQLite 事务中采用动态参数化 SQL (`WHERE id IN (?, ?, ...)`) 一次性完成更新，杜绝逐行开销。
- **并发健康体检接口 (`POST /api/sources/health-check`)**：
  - 提取书源 URL 根域名/搜索根路径，执行轻量 HEAD/GET 探针探测（5s 极速超时）。
  - 自动归类为 `valid`（正常，时延 < 2s）、`slow`（迟缓，2s ~ 5s）、`failed`（失败，超时/4xx/5xx/DNS 解析异常）。
  - 使用 `boundedConcurrentMap`（最大 16 并发）快速完成全量体检，返回详细毫秒级耗时与统计分布。

### 2.2 前端浮动操作状态机与模态框 (React 19 + TypeScript)
- **批量管理状态机 (`SourcesPage`)**：
  - 顶部入口「批量管理」触发多选模式，卡片左侧滑出原生复选框，整行点击均可切换选中状态。
  - 底部浮动操作栏：实时显示选中数，支持「全选 / 反选」、「批量启用 / 批量停用」、「修改分组」、「导出选中」与「批量删除」。
- **书源分组模态框 (`SourceGroupModal`)**：
  - 预置历史已有分组快捷选择胶囊，支持新建分组或一键清空分组。
  - 侧边栏新增分组下拉筛选器，切换即时过滤列表并显示分组内书源计数。
- **健康体检控制台 (`SourceHealthModal`)**：
  - 动态计时器、全量与分类进度条展示。
  - 分类 Tab 切换（🟢 正常 / 🟡 迟缓 / 🔴 异常），详细展示时延、状态码与错误信息。
  - 一键「禁用全部异常书源」原子操作，秒级净化书源库。

---

## 3. 真实浏览器 (Chrome) E2E 测试演进与避坑

### 3.1 严格限制并发实例（$\le 2$ 实例池）
在 `web/test/e2e-source-batch-health.ts` 中通过 `ChromePool` 信号量严格控制系统内活跃 Chrome 进程数 $\le 2$，杜绝在容器与 CI 环境中因内存耗尽发生假死。

### 3.2 移动端 Sidebar Heading 响应式避坑
- **踩坑**：在 `@media (max-width: 768px)` 中历史代码存在 `.source-sidebar-heading { display: none; }`，导致移动端下「体检」与「批量管理」按钮被隐藏，E2E 判定元素不可点击。
- **修复**：移除 `display: none`，调整移动端顶部内边距为 `padding: 14px 14px 6px`，确保移动端与桌面端体验一致且无视口溢出。

---

## 4. 最短验证结果

```sh
# 1. 前端静态类型检查与单测
npm --prefix web run check
npx tsx web/test/run-all.ts
# Result: 132/132 passed

# 2. 真实 Chrome 浏览器端到端实机测试 (<= 2 实例)
npx tsx web/test/e2e-source-batch-health.ts
# Result: Instance 1 (Desktop 1280x800) PASSED
#         Instance 2 (Mobile Viewport 393x852) PASSED

# 3. 服务端 JVM 单元与集成测试
./gradlew :server:test
# Result: BUILD SUCCESSFUL (All test suites passed)
```
