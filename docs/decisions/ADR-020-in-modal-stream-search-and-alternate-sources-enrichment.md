---
id: ADR-020
title: 编辑书籍弹窗内流式搜索与备选书源沉淀架构 (In-Modal Stream Search and Alternate Sources Enrichment)
status: accepted
date: 2026-09-26
---

# ADR-020: 编辑书籍弹窗内流式搜索与备选书源沉淀架构

## 1. 决策背景 (Context)

本地导入的书籍（如通过 TXT 或 EPUB 文件导入）往往缺乏封面或作者名信息。需要提供一种便捷机制，允许用户在管理书籍信息时，利用已配置的网络书源并发检索并选择合适的封面、作者等元数据进行补全。

需要明确以下技术权衡：
1. 交互入口应采用独立新弹窗流程，还是在现存的「编辑书籍信息」弹窗内部无缝扩展；
2. 检索到的书源结果是否仅作为临时填表数据，还是作为该书籍的 `alternate_sources` 沉淀入库；
3. 本地书籍元数据更新后，正文抓取与换源机制的边界划分。

---

## 2. 裁定方案 (Decision)

1. **弹窗内集成流式搜索组件 (In-Modal Stream Search)**：
   - 不额外新增顶层模态框或菜单链路，直接在现有的 `BookInfoEditModal`（编辑书籍信息）内部增设「🔍 联网搜索补全」可折叠面板。
   - 默认以当前书名原样作为搜索关键词，复用前端现有的 `streamSearch(keyword)` WebSocket 管道，进行多书源异步并发搜索与流式进度渲染。
2. **选择性元数据回填 (Selective Field Backfill)**：
   - 当用户点击某条搜索结果的「选用」时：
     - 将该结果的作者名回填至 `author` 状态（用户可后续二次修改）；
     - 将该结果的封面图地址回填至 `coverUrl` 状态，触发即时图片预览；
     - 将搜索结果中的有效源追加合并至 `alternateSources`。
3. **数据模型与持久化升级 (Alternate Sources Persistence)**：
   - 后端升级 `BookshelfInfoUpdateRequest` 数据结构，允许可选携带 `alternateSources: List<SearchResult>?`；
   - `Database.updateBookshelfInfo` 在更新书籍 `name`、`author`、`cover_key`、`group_name` 的同时，原子更新 `alternate_sources` 字段；
   - 继承现有的服务端 `CoverCacheService`，当接收到外部 `coverUrl` 时，在后端异步下载并转存为本地 `cover_cache` 实体，抵御第三方书源网站防盗链及图片过期失效。
4. **严格捍卫本地正文边界 (Preserve Local Content & Virtual Source)**：
   - 本地书籍的 `sourceId` 永远维持 `loc_book`，其目录（`book_toc_cache`）与正文缓存（`book_content_cache`）均指向本地文件系统与本地分章记录；
   - 沉淀的 `alternate_sources` 仅用于为书籍提供更多备选封面（`candidateCovers`）与元数据记录，不提供跨源自动换章覆盖，杜绝破坏本地正文。

---

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：在书架卡片菜单新增独立「联网匹配」流程弹窗**
  - *否决理由*：与现有的「编辑书籍信息」职责重叠，割裂了用户的编辑操作。用户往往是在“编辑信息”时发现缺封面、缺作者，才产生“找一找网络信息”的诉求。在同一弹窗内提供搜索补全、即时预览并在同一个保存动作中持久化，心智模型更统一自然。
- **备选方案 B：仅回填表单字段，不将搜索到的候选源保存至 `alternate_sources`**
  - *否决理由*：浪费了用户耗费时间并发搜索产生的结果。若将候选源沉淀至 `alternate_sources`，下方的「从备选书源选择封面」网格便能持久化激活，用户后续随时可以挑选不同书源的高清封面，而无需再次发起搜索。
- **备选方案 C：允许本地书籍直接点击换源并切换为远程网络书源**
  - *否决理由*：违反 PROPOSAL-016 设定的边界。用户上传本地 TXT/EPUB 本身就是为了保存私有/精排/离线版本，一旦“换源”将导致 `sourceId` 脱离 `loc_book`，造成本地正文与远程正文的混乱覆盖，违背本地导入的核心诉求。

---

## 4. 后果与权衡 (Consequences & Trade-offs)

- **正面收益**：
  - 极大提升本地书籍入库后的视觉质感与管理体验，零外部手工复制粘贴成本。
  - 完美复用现有 `streamSearch` 引擎与 `coverCache` 服务端持久化管道，无额外第三方依赖，符合工程整洁度与极简原则。
- **负面代价**：
  - `BookInfoEditModal` 逻辑状态略有增加（引入搜索状态与流式订阅生命周期管理），需要做好组件卸载与重复搜索时的 WebSocket 清理与防抖。
