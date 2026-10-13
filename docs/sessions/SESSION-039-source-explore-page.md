---
id: SESSION-039
title: 书源发现页（Explore）多维分类浏览与加架直读实现
date: 2026-10-01
author: Agent
tags: [explore, book-source, rule-engine, web-ui, i18n]
---

# SESSION-039: 书源发现页（Explore）多维分类浏览与加架直读实现 (Issue #24)

## 1. 现象与需求背景 (Investigation & Analysis)
- **痛点**：在 Legado（开源阅读）生态中，“发现页”是广大书友在无特定目标时淘书、浏览各大榜单（周热榜、月票榜、新书榜）与题材分类（玄幻、都市、悬疑）最核心的功能。现有系统已将书源搜索、书架、替换规则、WebDAV 等无头化，但书源中配置的 `exploreUrl` 与 `ruleExplore` 一直未被利用。
- **目标**：响应 GitHub Issue #24，在 Web 端建立顶栏「发现」一级入口，激活服务端 `RuleRunner` 针对 `exploreUrl` 的全语法解析能力，提供响应式双栏分类浏览、流式分页（加载更多）、一键加入书架与秒开阅读。

## 2. 踩坑记录与排查推演 (Tribal Knowledge & Bug Fixes)

### 2.1 CSS 规则不带 `@text` 时导致字段返回 null
- **排查过程**：在为 `exploreBooks` 编写测试时，起初将书籍名称规则写为 `"name": ".book-name"`，结果断言 `books.size == 1` 报 `expected:<1> but was:<0>`。
- **根因**：Legado 的 HTML 取值规则必须以 `@text`、`@href`、`@src` 或 `@attr(...)` 结尾。在 `RuleRunner.valuePlain` 中，规则按 `@` 分割，若无 `@`，则将最后一个段当作属性名传给 `el.attr(rawMode)`。对于 `.book-name`，由于不存在该属性，返回了 null，导致 `items.mapNotNull` 过滤丢弃了整本书籍。
- **防范**：编写书源测试与规则时，CSS 取值规则严格遵循 Legado 标准写上 `@text` 或 `@href`。

### 2.2 发现分类 `exploreUrl` 的三态兼容
- Legado 书源中 `exploreUrl` 极其丰富自由：
  1. **多行纯文本**：以 `::` 或 `&&` 分隔标题与 URL，支持多级缩进与层级标头（无分隔符的行作为分组标头，紧随的缩进行作为子分类）。
  2. **JSON 数组/树**：直接返回 `[{"title":"...","url":"..."}]` 或嵌套 `url: [{title, url}]` 或 `subCategories: [...]`。
  3. **沙箱脚本**：以 `<js>...</js>` 或 `@js:...` 动态计算。
- **解法**：在 `RuleRunner.exploreCategories` 中，先在沙箱中求值，随后根据结果特征（`startsWith("[")`）自动探测走 JSON 递归解析还是多行行扫描状态机，产出统一树形 DTO `ExploreCategory`。

### 2.3 `ruleExplore` 为空时的透明回退
- 海量民间书源因偷懒未配置 `ruleExplore`。
- **解法**：在 `exploreBooks` 执行时，优先检查 `ruleExplore?.string("bookList")`，若未配置或为空，则自动回退至 `ruleSearch`。此举完美兼容 Legado 原生客户端的隐性契约，让上千个民间书源即刻生效。

## 3. 最终落地的方案架构 (Final Architecture)

1. **服务端 DTO 与存储**：
   - `Models.kt` 增加 `ExploreCategory` 与 `ExploreSourceItem`。
   - `Database.kt` 增加 `listExploreSources()`，利用 SQLite `json_extract(payload, '$.exploreUrl') is not null` 秒级检索。
2. **服务端解析管道 (`RuleRunner.kt`)**：
   - `exploreCategories(sourceJson)`：沙箱求值 + 格式自适应探测（JSON / 纯文本行扫描状态机）。
   - `exploreBooks(sourceJson, exploreUrl, page)`：支持 `{{page}}` 宏替换、`splitUrlOptions` 选项拆解，以及 `ruleExplore` -> `ruleSearch` 继承回退。
3. **API 路由 (`Routes.kt`)**：
   - `GET /api/explore/sources`、`GET /api/explore/categories`、`GET /api/explore/books`，全量接入会话鉴权与 `call.respondApiError`。
4. **前端响应式发现中心 (`ExplorePage.tsx` + `styles.css`)**：
   - 顶栏增设「发现」一级入口，支持四语 i18n。
   - 桌面端左侧书源与分类树、右侧网格卡片流；移动端自动收敛为顶部横向滚动选项卡。
   - 书籍卡片提供封面兜底、简介折叠、一键加入书架（带实时查重与状态切换）与“立即阅读”（直接秒开阅读器）。

## 4. 验证矩阵

| 验证项 | 测试命令 / 路径 | 结果 |
| :--- | :--- | :--- |
| 前端类型检查 | `npm --prefix web run check` | 0 error |
| 前端自动化测试 | `npx tsx web/test/run-all.ts` | 183 / 183 全绿（含 `explore.test.ts`） |
| 后端单元测试 | `./gradlew :server:test` | 全部通过（含 `ExploreRuleRunnerTest`、`ExploreRoutesTest`） |
