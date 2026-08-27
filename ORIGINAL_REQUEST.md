# Original User Request

## 2026-08-15T04:40:10Z

全量重构优化 Legado 服务端 (`server/`) 与 Web 客户端 (`web/`) 的并发搜索延迟、整书离线缓存吞吐、数据库持久化查询效率，以及前端开书级联请求与大目录渲染性能，并在本地启动服务通过真实书源与浏览器交互完成完整的端到端验证。

Working directory: /Users/zhangran/Documents/antigravity/joyful-galileo
Integrity mode: development

## Requirements

### R1. 服务端并发搜索与候选源校验优化
消除搜索候选源深度串行 3 次网络请求（详情/目录/正文）导致的级联阻塞与超时，放宽对封面 URL 的过度硬性拦截；优化流式搜索并发吞吐，确保各书源搜索结果能够低延迟实时流式推送。

### R2. 整书离线缓存并发化与断点续传
重构 `BookCacheService` 离线缓存链路：
- 支持受控并发（多协程受限并发下载章节），大幅提升长篇小说离线缓存吞吐；
- 增加断点续传能力，跳过数据库中已存在的有效章节，避免重复拉取；
- 提供细粒度缓存进度实时更新，避免单章写入时频繁执行全表 Count 查询。

### R3. 数据库连接生命周期与大字段查询优化
- 优化 `Database` 的短连接高频开启/销毁与重复 PRAGMA 开销；
- 优化 `listSources` 查询语句，避免将所有书源庞大的 JSON `payload` 规则一次性加载到内存中；
- 保持数据一致性、事务安全与 PBKDF2/Session 鉴权逻辑完好。

### R4. Web 前端搜索开书性能与大目录渲染优化
- 消除搜索结果中点击书籍时对所有备选书源发起的 40+ 级联 API 请求，改为优先直连当前书源、其余备选书源按需/异步懒加载；
- 优化长篇小说数千章节目录的 DOM 挂载开销与渲染性能，避免侧边栏与移动端目录 Sheet 展开卡顿；
- 优化阅读器高频滚动事件中的布局回流（Reflow）计算与正文文本分段缓存。

### R5. 真实数据与端到端浏览器验证
- 必须本地启动完整的 Ktor 服务端与 Web 前端；
- 使用真实书源数据（或实际书源订阅导入）进行多书源流式搜索；
- 在真实浏览器环境中测试：搜索结果秒开、大章节目录流畅浏览与展开、阅读进度与离线缓存实时写入、页面滚动与主题切换无卡顿。

## Verification Resources

- 服务端单元测试套件：`./gradlew :server:test`
- Web 前端构建与类型检查：`cd web && npm run check && npm run build`
- 浏览器端到端测试工具：Chrome 浏览器 / DevTools 交互与控制台日志检查

## Acceptance Criteria

### 服务端与单元测试验证
- [ ] `./gradlew :server:test` 全部通过，无任何测试失败
- [ ] 针对搜索流程优化、缓存断点续传、书源轻量列表查询编写或更新回归单元测试并全部通过

### 前端与构建验证
- [ ] `cd web && npm run check` 零 TypeScript 类型错误
- [ ] `cd web && npm run build` 成功构建生产静态资源

### 真实数据与浏览器 E2E 验证
- [ ] 本地服务成功启动并在真实浏览器中完成交互体验
- [ ] 使用真实书源进行关键词搜索，验证流式搜索结果能够秒级实时刷出且无卡死
- [ ] 在搜索结果中点击书籍能够实现**秒开阅读器**，且无冗余并发 API 轰炸
- [ ] 阅读器中包含数千章目录的长篇小说，目录展开、章节切换与正文滚动保持流畅（无严重掉帧与主线程阻塞）
- [ ] 书架缓存任务能够正常断点续传并在 UI 上实时呈现百分比进度

## Follow-up — 2026-08-15T08:02:04Z

继续上一次未完成的优化任务。

Working directory: /Users/zhangran/Documents/antigravity/joyful-galileo
Integrity mode: development

## 当前状态

前一个团队已完成大部分代码修改，但因额度中断而停止。目前状态：

- ✅ 前端优化代码已完成，`npm run check` 与 `npm run build` 均通过
- ❌ 服务端测试失败：80 个测试中有 **16 个失败**，需修复后才能继续验证

## 当前测试失败清单

以下测试失败，请逐一修复：

```
BookCacheServiceTest > rejects oversized chapters exceeding 2 MiB limit FAILED
    java.lang.IllegalArgumentException at BookCacheServiceTest.kt:199

CrossFeatureBackendTest > concurrent search and background chapter caching execute without SQLite lock contention FAILED
    java.lang.AssertionError at CrossFeatureBackendTest.kt:404

CrossFeatureBackendTest > sequential multi-source switching across three sources maintains bookshelf integrity FAILED
    org.sqlite.SQLiteException at CrossFeatureBackendTest.kt:136

CrossFeatureBackendTest > multi-source switching preserves reading progress across sources and purges old cache FAILED
    org.sqlite.SQLiteException at CrossFeatureBackendTest.kt:94

DatabaseLifecycleTest > bookshelf switch cleans orphan covers and cache entries FAILED
    org.sqlite.SQLiteException at DatabaseLifecycleTest.kt:318

DatabaseTest > switching a shelf source clears old cached data FAILED
    org.sqlite.SQLiteException at DatabaseTest.kt:186

E2EScenariosTest > scenario 4 - large book 5000 chapters TOC virtual browsing and chapter retrieval FAILED
    java.lang.AssertionError at E2EScenariosTest.kt:283

SearchRoutesTest > search concurrency supports bounded execution and scales with CPU FAILED
    java.lang.AssertionError at SearchRoutesTest.kt:130
```

主要问题分类：
1. **SQLiteException**：多为并发写入时的锁争用（`org.sqlite.SQLiteException`），很可能是数据库连接池/WAL 模式与锁等待超时配置问题，需在测试中正确隔离或在 `Database.kt` 中保证事务级别正确
2. **AssertionError**：逻辑断言失败，需看具体测试期望值与实际输出的偏差
3. **IllegalArgumentException**：BookCacheService 大小限制校验逻辑问题

## 修复目标

1. 修复全部 16 个失败测试，使 `./gradlew :server:test` 100% 通过
2. 确保 `cd web && npm run check && npm run build` 继续保持通过
3. 完成本地真实书源与浏览器端到端 E2E 验证（R5）

## Acceptance Criteria（原始要求）

### 服务端与单元测试验证
- [ ] `./gradlew :server:test` 全部通过，无任何测试失败
- [ ] 针对搜索流程优化、缓存断点续传、书源轻量列表查询编写或更新回归单元测试并全部通过

### 前端与构建验证
- [ ] `cd web && npm run check` 零 TypeScript 类型错误
- [ ] `cd web && npm run build` 成功构建生产静态资源

### 真实数据与浏览器 E2E 验证
- [ ] 本地服务成功启动并在真实浏览器中完成交互体验
- [ ] 使用真实书源进行关键词搜索，验证流式搜索结果能够秒级实时刷出且无卡死
- [ ] 在搜索结果中点击书籍能够实现**秒开阅读器**，且无冗余并发 API 轰炸
- [ ] 阅读器中包含数千章目录的长篇小说，目录展开、章节切换与正文滚动保持流畅（无严重掉帧与主线程阻塞）
- [ ] 书架缓存任务能够正常断点续传并在 UI 上实时呈现百分比进度

## Verification commands

- `./gradlew :server:test`
- `cd web && npm run check && npm run build`
- 启动服务：`./gradlew :server:run`，前端：`cd web && npm run dev`

