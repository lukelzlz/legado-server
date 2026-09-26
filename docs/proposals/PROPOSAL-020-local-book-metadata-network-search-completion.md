---
id: PROPOSAL-020
title: 本地导入书籍联网搜索与元数据补全 (Local Book Metadata Network Search Completion)
status: accepted # draft | review | accepted | implemented | rejected
author: Antigravity & User
date: 2026-09-26
---

# PROPOSAL-020: 本地导入书籍联网搜索与元数据补全

## 1. 业务背景与问题痛点

在 Legado-Server 引入本地书籍导入能力（PROPOSAL-016：TXT/EPUB 文件上传与智能分章入库）后，用户可方便地将本地电子书纳入服务端管理与沉浸式阅读。但在实际使用场景中存在明显的信息缺失痛点：

1. **元数据贫乏与视觉割裂**：
   - 绝大多数本地 TXT 小说仅以文件名作为书名（例如《变成魔女，但是她们都想跟我恋爱.txt》），不包含作者名，更无封面图片；
   - 导入后书架只能展示文字占位封面（如单字“变”），作者栏显示“作者（可选）”，与网络书源导入的精美封面和完整元数据相比视觉体验落差巨大。
2. **手动补全繁琐耗时**：
   - 当前「编辑书籍信息」弹窗仅支持用户手动逐字输入作者名、以及手动在网上搜索图片后复制粘贴外部图片 URL，操作链路长、繁琐且对手机端操作极不友好。
3. **书源生态未被复用**：
   - Legado 服务端已内置强大的书源流式并发搜索系统（`streamSearch` / WebSocket），海量网络书源中已收录大量与本地书籍同名的小说，内含官方/高质量封面、标准作者名与简介，但该能力此前仅服务于网络搜书与阅读器换源，未能赋能本地书籍。

---

## 2. 目标与非目标 (Goals & Non-Goals)

### Goals
- **弹窗内无缝搜索补全 (In-Modal Search & Auto-fill)**：
  - 在「编辑书籍信息」弹窗内部提供「🔍 联网搜索补全信息」功能，默认以当前书名原样作为关键词向所有启用的书源发起流式搜索。
  - 用户可按需手动调整搜索关键词，支持实时搜索进度指示（已完成书源数/匹配结果数）与随时停止搜索。
- **一键填充与视觉预览 (One-Click Application & Real-time Preview)**：
  - 流式呈现匹配到的同名/相关书籍候选卡片（包含封面缩略图、书名、作者、书源名称、最新章节/简介摘要）。
  - 用户点击候选卡片的「选用」按钮后，自动将该候选项的封面 URL 和作者名填入编辑表单，封面预览区即刻更新。
- **备选书源沉淀 (Alternate Sources Enrichment)**：
  - 选中的候选书源（及搜索命中的书源列表）同步沉淀至书籍的 `alternate_sources` 属性持久化存储。
  - 沉淀后，该书籍在后续编辑时将自动激活「从备选书源选择封面」网格列表，方便随时切换不同书源提供的精美封面。
- **服务端持久化扩展 (Backend Update Compatibility)**：
  - 服务端 `PUT /api/bookshelf/info` 接口扩展支持更新 `alternateSources` 字段，并复用已有的封面离线下载与缓存逻辑（`coverCache.cache`），确保封面本地持久化，不受外部防盗链影响。

### Non-Goals
- **不改变本地书籍正文与目录 (Preserve Local Content 100%)**：
  - 本地书籍的 `sourceId` 严格保持为 `loc_book`，章节内容与目录完全来自本地文件系统，绝不替换为网络书源的正文，杜绝本地精排小说内容被网络正文覆盖破坏。
- **不强制自动静默覆盖**：
  - 联网搜索出的结果必须由用户在 UI 上主动点击「选用」确认后才填充，严禁后台未经用户确认盲目自动修改书名或作者。
- **本次不改动数据库 `book_shelf` 新增 `intro` 字段**：
  - 严格保持当前架构整洁度与极简原则，书籍简介可通过候选卡片供用户参考识别，但书架主表不扩充持久化简介字段。

---

## 3. 核心用户故事 (User Stories)

- **Story 1（本地小说一键检索网络元数据）**：
  作为用户，我上传了一本《变成魔女，但是她们都想跟我恋爱.txt》，书架上显示文字占位封面“变”且作者为空。我点击该书卡片的「...」管理菜单选择「编辑书籍信息」，点击「🔍 联网搜索补全」，系统自动以书名发起全网书源检索，几秒内展示了来自刺猬猫、起点或聚合源的同名小说候选，封面高清精致，作者清晰标注。
- **Story 2（一键选用封面与作者并微调保存）**：
  作为用户，在搜索候选列表中我找到了最满意的版本，点击「选用」，弹窗中的作者输入框立刻自动填入作者名，封面预览区立刻显示选中的高清封面；我确认无误后点击「保存修改」，服务端自动将该封面图片下载并持久化缓存到服务器，书架上该书即刻拥有精美封面与作者名。
- **Story 3（书名标签过滤与手动微调搜索词）**：
  作为用户，如果我导入的文件名包含特殊标签（如《某某小说[精校全本第1-500章].txt》），我点击「联网搜索补全」后，在搜索输入框中手动将多余后缀删除，重新点击搜索，精准命中目标小说并完成补全。
- **Story 4（备选封面随时挑选）**：
  作为用户，在本次补全并保存后，未来我若再次打开「编辑书籍信息」，表单下方的「从备选书源选择封面」网格中已保留了当初搜索命中的多个书源封面，我可以一键点击换用另一款设计风格的封面。

---

## 4. 详细技术方案与接口设计

### 4.1 前端交互与组件增强 (`web/src/main.tsx`)
1. **`BookInfoEditModal` 内部集成搜索面板**：
   - 新增状态：
     - `searchOpen: boolean`（是否展开联网搜索面板）
     - `searchKeyword: string`（初始值为当前书名 `item.name`）
     - `searching: boolean`（是否正在流式搜索）
     - `searchResults: SearchResult[]`（当前搜索累积结果）
     - `searchProgress: SearchStreamEvent | null`（搜索流式进度）
     - `alternateSources: SearchResult[]`（初始化为 `item.alternateSources || []`）
   - **交互流程**：
     - 用户点击「🔍 联网搜索补全」切换展开搜索面板；
     - 面板顶部提供搜索输入框、[搜索] 按钮与 [停止] 按钮；
     - 搜索基于已有的 `streamSearch(searchKeyword, onEvent)` WebSocket 接口，动态接收 `result` 与 `progress` 事件；
     - 搜索结果卡片支持横向/纵向虚拟列表呈现，展示封面、书名、作者、书源名称与简介片段；
     - 点击「选用」按钮：
       - `if (candidate.author) setAuthor(candidate.author)`
       - `if (candidate.coverUrl) setCoverUrl(candidate.coverUrl)`
       - 将候选结果追加合并入 `alternateSources`
       - 提示“已填入封面与作者”
2. **保存提交 (`handleSave`)**：
   - `api.updateBookshelfInfo` 请求体中附带最新的 `alternateSources`。

### 4.2 前端 API 契约 (`web/src/api.ts`)
```typescript
updateBookshelfInfo: (data: {
  sourceId: string
  bookUrl: string
  name: string
  author?: string
  coverUrl?: string
  groupName?: string
  alternateSources?: SearchResult[]
}) => request<BookshelfItem>('/api/bookshelf/info', { method: 'PUT', body: JSON.stringify(data) })
```

### 4.3 服务端数据模型与持久化 (`server/`)
1. **`BookshelfInfoUpdateRequest` 模型升级**：
   ```kotlin
   @Serializable data class BookshelfInfoUpdateRequest(
       val sourceId: String,
       val bookUrl: String,
       val name: String,
       val author: String? = null,
       val coverUrl: String? = null,
       val groupName: String? = null,
       val alternateSources: List<SearchResult>? = null,
   )
   ```
2. **`Database.updateBookshelfInfo` 持久化升级**：
   - 提取原有 `alternate_sources`：
     ```kotlin
     val oldAlts = rs.getString("alternate_sources")
     ```
   - 若 `request.alternateSources != null`，将其序列化为 JSON 字符串并写入 `alternate_sources = ?`：
     ```sql
     UPDATE book_shelf
     SET name=?, author=?, cover_url=?, cover_key=?, group_name=?, alternate_sources=?
     WHERE source_id=? AND book_url=?
     ```
   - 若传入了新的 `coverUrl`，复用现有的 `coverCache.cache(url)` 机制自动拉取并转存至 `cover_cache` 表，彻底解决外链图片防盗链和时效性失效问题。

---

## 5. 验收基准 (Acceptance Criteria)

- [ ] **UI 交互**：在「编辑书籍信息」弹窗中点击「联网搜索补全」，展示搜索面板，默认填入当前书籍名称，点击搜索能够流式接收书源搜索结果与进度。
- [ ] **信息回填**：点击候选书籍「选用」后，作者输入框自动填入作者、封面预览区自动更新为所选封面，且候选封面进入下方备选网格。
- [ ] **接口与落盘**：点击保存后，`PUT /api/bookshelf/info` 成功保存书名、作者、新封面（自动落盘至 `coverCache`）与 `alternateSources`，书架卡片即时刷新。
- [ ] **本地阅读不受影响**：补全信息后，本地书籍仍然能正常打开阅读、大章节虚拟滚动、目录跳转、正文离线缓存及 TTS 朗读，`sourceId` 依旧为 `loc_book`。
- [ ] **自动化测试**：
  - 前端类型检查 `npm --prefix web run check` 与单元测试 `npx tsx web/test/run-all.ts` 全绿通过。
  - 服务端单元测试 `./gradlew :server:test` 验证 `updateBookshelfInfo` 携带 `alternateSources` 的行为准确无误。
