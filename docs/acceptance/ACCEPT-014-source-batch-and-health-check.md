# 书源批量整理、分组维护与轻量连通性健康体检实操验收手册 (ACCEPT-014)

> **关联提案**：[`docs/proposals/PROPOSAL-014-book-source-batch-management-and-health-check.md`](file:///root/legado-server/docs/proposals/PROPOSAL-014-book-source-batch-management-and-health-check.md)  
> **关联架构决策**：[`docs/decisions/ADR-014-source-batch-operations-and-lightweight-probe-pipeline.md`](file:///root/legado-server/docs/decisions/ADR-014-source-batch-operations-and-lightweight-probe-pipeline.md)

---

## 1. 验收环境与快捷验证命令

### 最短自动化验证套件
```bash
# 1. 前端类型检查与自动化测试套件
npm --prefix web run check && npx tsx web/test/run-all.ts

# 2. 服务端 JVM 单元测试
./gradlew :server:test

# 3. 前后端综合一键验证
npm --prefix web run check && npx tsx web/test/run-all.ts && ./gradlew :server:test
```

### 本地 Docker 容器部署热更新验证
```bash
# 构建并重新启动本地测试容器
docker build -f Dockerfile.server -t test-legado-server:latest .
docker stop test-legado 2>/dev/null || true
docker rm -f test-legado 2>/dev/null || true
docker run -d --name test-legado -p 8080:8080 \
  -e ADMIN_PASSWORD=admin123 \
  -e LEGADO_SECURE_COOKIES=false \
  -v $(pwd)/.data:/data test-legado-server:latest
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/index.html
```

---

## 2. 核心功能实操核验清单

### 场景一：批量管理模式开启与多选状态机
1. **核验步骤**：
   - 进入主导航 **「书源」** 管理页面；
   - 点击左侧侧边栏顶部的 **「批量管理」** 按钮；
   - 观察列表项变化：每个书源左侧出现勾选复选框，页面底部升起浮动操作栏（`.source-batch-bar`）；
   - 测试点击 **「全选」**、**「全不选」**、**「反选」** 按钮；
   - 在搜索框中输入关键词（如 `笔趣`），列表实时过滤，点击「全选」，验证是否仅选中当前过滤后的结果；
   - 点击浮动栏上的 **「完成」** 或侧边栏顶部的 **「退出批量」**，验证多选状态是否安全退出并重置。
2. **核验标准**：
   - 浮动栏计数显示（`已选 X / Y`）完全精确，选择与反选响应无延迟。

---

### 场景二：批量启用、批量停用与状态指示
1. **核验步骤**：
   - 勾选若干处于启用状态的书源，点击浮动栏的 **「批量停用」**；
   - 观察列表：被选书源名称后立即显示灰色 **「已停用」** 徽标；
   - 再次勾选这些书源，点击浮动栏的 **「批量启用」**；
   - 观察列表：已停用徽标消失，书源恢复为正常启用状态。
2. **核验标准**：
   - 状态更新通过 `POST /api/sources/batch` 原子落库，刷新页面后状态依然保持一致。

---

### 场景三：批量设置分组与分组标签筛选
1. **核验步骤**：
   - 勾选多个书源，点击浮动栏上的 **「修改分组」**；
   - 在弹出的「批量设置分组」模态框中：
     - 测试输入新分组名称（如 `精选推荐`）并提交；
     - 观察侧边栏顶部出现分组下拉选择器 `精选推荐 (N)`，选择该分组能够精准过滤；
   - 再次勾选这些书源，在分组弹窗中选择 **「清空分组（移出所有分组）」** 并确认；
   - 观察书源的分组被成功清除。
2. **核验标准**：
   - 分组设置即时生效，已有分组可以作为快捷标签芯片一键点选填充。

---

### 场景四：批量安全删除与二次确认
1. **核验步骤**：
   - 勾选测试书源，点击浮动栏上的 **「批量删除」** 危险按钮；
   - 观察弹出的确认对话框：明确列出了待删除的书源数量与名称摘要；
   - 点击确认后，验证列表即时剔除被删除的书源。
2. **核验标准**：
   - 服务端级联清理 `source` 与 `source_login_state` 记录，无残留数据。

---

### 场景五：批量导出为标准 Legado JSON
1. **核验步骤**：
   - 勾选指定书源，点击浮动栏上的 **「导出选中」**；
   - 观察浏览器触发下载 `legado_sources_export_YYYY-MM-DD.json` 文件；
   - 打开导出的文件，检查是否为包含书源完整规则的标准 JSON 数组格式；
   - 将导出的 JSON 文件通过「导入 JSON」重新导入，验证无损解析。
2. **核验标准**：
   - 导出的文件 100% 兼容 Legado 官方规范。

---

### 场景六：轻量并发连通性健康体检 (Health Probe)
1. **核验步骤**：
   - 点击书源侧边栏顶部的 **「体检」** 按钮；
   - 系统自动开始对书源进行 8 并发轻量连通性与规则语法探测；
   - 观察体检模态框：
     - 顶部实时显示检测耗时与动态进度条；
     - 分类标签精确统计 `全部`、🟢 `正常`、🟡 `迟缓`、🔴 `失效` 数量；
     - 列表项卡片清晰展示每个书源的响应耗时（如 `128ms`）、HTTP 状态码以及错误原因（如超时、DNS 错误等）；
   - 测试点击 **「一键停用失效源」** 或 **「仅重试失效项」**。
2. **核验标准**：
   - 服务端单源严格 5 秒超时，探测速度极快且不阻塞协程池，一键停用后主列表同步标记为已停用。

---

### 场景七：移动端与全面屏安全区适配
1. **核验步骤**：
   - 在手机浏览器或 Chrome 设备模拟器中打开书源页面，进入批量模式；
   - 观察底部浮动操作栏：完美避开底部的系统手势条（`env(safe-area-inset-bottom)`），侧边栏与列表滚动容器具备足够底部间距，最后一项内容不被浮动栏遮挡。
2. **核验标准**：
   - 移动端各按钮易于点击，无元素遮挡或排版错位。
