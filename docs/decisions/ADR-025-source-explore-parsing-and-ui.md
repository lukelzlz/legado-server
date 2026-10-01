---
id: ADR-025
title: 书源发现页（Explore）服务端解析管线与响应式 UI 架构
status: accepted
date: 2026-10-01
---

# ADR-025: 书源发现页（Explore）服务端解析管线与响应式 UI 架构

## 1. 决策背景 (Context)

在 Legado 开源生态中，“发现页”是各大站点提供推荐、榜单、标签与分类浏览的核心规范：
- `exploreUrl` 格式繁多：包含多行纯文本（以 `\n` 切分）、`标题::URL` 格式、`标题&&URL` 格式、包含缩进或布局属性的层级文本、JSON 数组格式，以及依赖 `<js>...</js>` 运行时求值的动态脚本。
- `ruleExplore` 规范松散：大量书源开发者为了精简规则，未编写 `ruleExplore`，而是直接依赖 Legado 客户端默认回退至 `ruleSearch` 的底层行为。
- 前端需要安全、快速且优雅地渲染树形/网格分类，并实现书籍分页浏览、加架与直读。

## 2. 裁定方案 (Decision)

### 2.1 服务端沙箱求值与统一契约
在服务端 `RuleRunner` 中收敛两个核心解析方法，并复用现有网络与沙箱基础设施：
1. **分类解析 `exploreCategories(sourceJson: String): List<ExploreCategory>`**：
   - 提取 `exploreUrl`，若含有 `<js>` 或 `@js:`，在带 `sourceContext` 的 Rhino 沙箱中求值；
   - 对求值结果进行格式自适应探测：
     - 若为 JSON 格式（`[` 开头），反序列化为分类对象数组；
     - 若为纯文本多行格式，按行扫描，提取 `::` 或 `&&` 前后的标题与链接，支持多级缩进或者平铺列表；
   - 产出统一的 DTO 契约：
     ```kotlin
     @Serializable
     data class ExploreCategory(
         val title: String,
         val url: String? = null,
         val subCategories: List<ExploreCategory> = emptyList(),
     )
     ```
2. **书籍列表解析 `exploreBooks(sourceJson: String, exploreUrl: String, page: Int): List<SearchResult>`**：
   - 替换 URL 中的 `{{page}}` 占位符（若无则保持单页）；
   - 使用 `splitUrlOptions` 与 `mergeOptions` 融合书源登录凭据、Cookie 与 Header，通过安全沙箱请求上游页面；
   - 规则执行管道：优先读取 `ruleExplore`；若 `ruleExplore` 为空或其 `bookList` 规则为空，自动采用 `ruleSearch`；
   - 抽取 `name`, `author`, `bookUrl`, `coverUrl`, `intro`, `kind` 等字段并归一化绝对路径。

### 2.2 REST API 设计
在 `Routes.kt` 中注册受会话鉴权保护的标准端点：
- `GET /api/explore/sources`：返回所有配置了 `exploreUrl` 的有效书源列表（包含 id、name、group、hasExplore 元数据）。
- `GET /api/explore/categories?sourceId={id}`：解析并返回该书源的分类目录列表。
- `GET /api/explore/books?sourceId={id}&url={url}&page={page}`：请求指定分类的书籍列表。

### 2.3 前端响应式双栏与卡片瀑布流布局
- **导航与页面整合**：
  - 顶栏导航增设「发现」（Explore）选项卡；
  - 桌面端：左侧（或二级侧栏）提供书源切换与当前书源的分类树/标签卡片，右侧主视口为书籍流式卡片网格；
  - 移动端：上方放置书源下拉选择器与横向可滑动的分类标签栏，下方紧跟书籍流；
- **加入书架与去重防重**：
  - 前端比对当前书架状态（根据书名和作者），已在书架的书籍直接展示“已在书架”，未在书架的提供一键“加入书架”按钮，调用 `POST /api/bookshelf`。

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

### 备选方案 A：由前端直接运行 JS 规则并拉取上游网页
- *否决理由*：
  1. 现代浏览器存在同源策略（CORS），绝大多数小说站点均未开放允许跨域读取网页 HTML/JSON。
  2. 书源的 `<js>` 脚本普遍依赖 Java 反射代理（如 `java.ajax`、`java.base64Decode`、`source.getVariable()`），浏览器前端根本无法模拟这些纯 JVM 沙箱特性。

### 备选方案 B：服务端全量后台爬虫预热并持久化至 SQLite
- *否决理由*：
  1. 严重违背无头轻量设计原则：预爬取全网数十甚至上百个书源的所有发现页会导致海量无效网络 I/O、触发目标站反爬风控、造成用户数据库瞬间膨胀。
  2. 违背“极简与工程整洁度”宪法：按需请求（On-demand）才是标准阅读器生态的正道。

## 4. 后果与权衡 (Consequences & Trade-offs)

### 正面收益
- 100% 激活 Legado 现存丰富书源的发现生态，极大丰富 Web 端寻书体验。
- 逻辑收敛在 `RuleRunner`，与现有搜索/正文/目录管线共用网络与沙箱底层，零外部额外依赖。
- 规范化回退至 `ruleSearch`，最大化兼顾民间非标书源的可用性。

### 潜在风险与缓解对策
- **部分书源的 `exploreUrl` JS 执行缓慢或网络超时**：
  - *缓解措施*：前端加载分类与书籍列表时提供骨架屏与 Loading 反馈，网络请求配备全局超时保护与错误提示，避免长时间挂起。
