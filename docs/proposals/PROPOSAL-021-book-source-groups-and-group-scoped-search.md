---
id: PROPOSAL-021
title: 书源分组与按分组搜书 (Book Source Groups & Group-Scoped Search)
status: implemented # draft | review | accepted | implemented | rejected
author: Antigravity & User
date: 2026-09-28
---

# PROPOSAL-021: 书源分组与按分组搜书

## 1. 业务背景与问题痛点

Legado 书源生态里，`bookSourceGroup`（书源分组）是书源自带的元数据：用户导入的源仓库动辄几百个源，分组是唯一能把它们组织起来的维度。本项目服务端**早已把该字段落库**（`source.source_group`，见 `SourceCodec.parse` / `Database.importSources`），但围绕它的能力一直是残缺的：

1. **分组只能「盲改」，不能管理**：
   只有 `POST /api/sources/batch` 的 `set_group` 能批量把源塞进某个名字，**没有**分组列表、改名、删除。用户想看看「现在到底有哪几个分组」「哪个分组装了多少源」都做不到；组名打错字只能靠再批量改一次来救。
2. **搜索无法按分组收窄（最痛）**：
   `SearchRequest` 里其实有个 `group` 字段，但它**从未被任何路由读取**——是个死字段。于是「搜书」只有两种范围：全部已启用书源（几百个源一起跑，慢且噪声大）或逐个指定单个源。用户明明在手机端按「大灰狼聚合」分组搜书，到了 Web 端却无法复刻这个动作。
3. **备份导入的分组「进来了但看不见」**：
   手机备份的 `bookSource.json` 里每个源都带 `bookSourceGroup`，导入时确实随源落库了，但导入摘要只有「书源 N」——用户无法判断分组到底有没有跟着进来（实测反馈：怀疑分组丢了）。

> 一句话：**分组数据早就在库里，缺的是「看得见、改得动、搜得到」。**

## 2. 目标与非目标 (Goals & Non-Goals)

### Goals
- **书源分组成为一等公民（在「书源」页面管理）**：
  - `GET /api/source-groups` 返回分组列表（分组名 / 书源总数 / **已启用**书源数）；
  - `PUT /api/source-groups/rename` 整组改名，目标组已存在时**按合并处理**；
  - `DELETE /api/source-groups?name=` 删除分组：**只解绑、绝不删书源**（退化为「未分组」）；
  - 书源页分组筛选框选中具体分组后，直接给出「重命名 / 删除分组」入口；新建分组沿用已有的「批量管理 → 移动到分组 → 输入新名字」。
- **搜索栏下面按分组搜书（书库页面）**：
  - 搜索范围做成**选项卡**：`全部书源` + 每个书源分组（带已启用源数）+ `未分组`；
  - **「全部书源」永远是第一项且是默认项**（用户明确要求保留「搜全部」）；
  - 保留既有的「指定单个书源」能力，且与分组范围**互斥**（不会出现「既是单源又是整组」的矛盾范围）；
  - 范围贯通两条搜索通道：`POST /api/search` 与 WebSocket `/api/search/stream`。
- **备份导入如实回报书源分组**：
  - 书源分组随 `bookSource.json` 一起导入（本就在做），导入摘要新增 `sourceGroups`（本批带来的**不同**分组数），WebDAV 导入提示里明示。

### Non-Goals
- **不建独立的分组表 / 不做多对多**：分组在 Legado 语义里就是书源上的一个字符串，为它引入 `source_group` 表 + 关联表，收益不抵复杂度（见 ADR-021）。因此一个源**只属于一个分组**，多分组字符串（如 `A,B`）按整体字符串存用，不做拆分。
- **不做分组排序 / 换组拖拽**：暂按名字排序（`collate nocase`），排序是伪需求（分组是筛选维度，不是展示实体）。
- **不改 `bookGroup.json`（书架分组）的任何语义**：书源分组与书架分组是两件事，本次只动前者。
- **不在书库页做分组管理**：新建/改名/删除都在「书源」页面，书库页只负责「选范围搜书」。

## 3. 核心用户故事 (User Stories)

- **Story 1（按分组搜书）**：
  作为用户，我在手机端把几百个书源按 `大灰狼聚合`、`出版`、`未分组` 分好组并备份到服务端。打开 Web「书库」页，搜索栏下面就是这些分组选项卡。我点 `大灰狼聚合`，输入书名回车——只有这个分组里的源在跑，进度条上的「已检查 / 总数」也只统计这个分组。
- **Story 2（一键回到全量搜索）**：
  作为用户，我在某个分组里没搜到想要的书，直接点最左边的 `全部书源`，再搜一次即可全量检索——不需要清空任何设置，也不会残留上一次的分组范围。
- **Story 3（在书源页整理分组）**：
  作为用户，我导入源仓库后发现分组名是错的（例如把「大灰狼聚合」写成了「大灰狼聚和」）。我在「书源」页面按分组筛选出这一组，点「重命名」，输入正确名字，整组源一次性改名——不用挨个编辑 JSON。
- **Story 4（删组不删源）**：
  作为用户，我确认某个分组不再需要，点「删除分组」，系统明确告诉我「组内书源不会被删除，只会变成未分组」；确认后这些源仍在，并且在「未分组」选项卡里照样能搜到。
- **Story 5（导入后确认分组真的进来了）**：
  作为用户，我在 WebDAV 页导入手机备份，提示写明「导入完成：书源 1（含书源分组 1 个）…」，我立刻能确认分组没丢；随后进「书库」页，`大灰狼聚合` 选项卡就在搜索栏下方。

## 4. 详细技术方案与接口设计

### 4.1 服务端数据访问 (`server/.../Database.kt`)

```kotlin
/** 分组概览：trim 后聚合，空/空白视为未分组（不进列表），大小写不敏感。 */
fun listSourceGroups(): List<SourceGroupSummary>
// select trim(source_group) as group_name, count(*), sum(enabled = 1)
// from source where source_group is not null and trim(source_group) <> ''
// group by trim(source_group) collate nocase order by group_name collate nocase

fun renameSourceGroup(from: String, to: String): Int   // update source set source_group=? where source_group=? collate nocase
fun clearSourceGroup(name: String): Int                // update source set source_group=null where source_group=? collate nocase

/** 搜索取源：sourceIds 与 group 同时给出时取交集（AND）。 */
fun listSearchSourceRecords(sourceIds: List<String>?, group: String? = null): List<SourceRecord>
```

- **未分组哨兵**：`SourceGroupFilter.UNGROUPED = "__ungrouped__"`（服务端与前端各有一份常量，值必须逐字一致），翻译成 `source_group is null or trim(source_group) = ''`，同时覆盖 `null` 与空串两种落库形态；
- **分组名过滤用 `collate nocase`**，与 `listSourceGroups` 的聚合口径一致，避免「列表显示两组、点任一组命中两组」；
- **`listSearchSourceRecords` 的 `group` 参数带默认值**，既有调用方（含测试）无需改动。

### 4.2 服务端路由 (`server/.../Routes.kt`)

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| GET | `/api/source-groups` | 分组列表（需会话） |
| PUT | `/api/source-groups/rename` | 整组改名；名称空 → 400，同名（忽略大小写）→ 0 影响，原组不存在 → 404 |
| DELETE | `/api/source-groups?name=` | 删组解绑；组不存在 → 404 |
| POST | `/api/search` | 新增按 `group` 收窄；**未显式指定范围**时才套用「最多 20 个源」的上限 |
| WS | `/api/search/stream` | 同上，`start` 帧的 `totalSources` 即本次实际选中的书源数 |

> 上限规则的调整理由：原实现是「`sourceIds == null` 且源数 > 20 就只取前 20」。用户一旦**显式选了分组**，就是明确的收窄意图，应与「按 id 指定书源」同等对待、整组都跑；否则选了 30 个源的分组只会跑 20 个，且没有任何提示。

### 4.3 备份导入（书源分组「跟着源走」）

- `bookSource.json` 的 `bookSourceGroup` 由 `SourceCodec.parse` 归一化后落库（`insert ... on conflict do update set source_group = excluded.source_group`），重复导入会**更新**分组；
- `ImportResponse` 新增 `sourceGroups: Int`（本批**不同**分组数，未分组不计，大小写不敏感去重），`BackupImportSummary.sourceGroups` 透传给 WebDAV 导入提示；
- 备份包里**不存在**「书源分组」条目 —— `bookGroup.json` 是**书架**分组，两者不能混为一谈（历史上这是最容易搞错的点）。

### 4.4 前端 (`web/src`)

- `api.ts`：`sourceGroups()` / `renameSourceGroup(from, to)` / `clearSourceGroup(name)`；`UNGROUPED_SOURCE_GROUP = '__ungrouped__'`；`streamSearch(..., group?)` 与 `api.search(..., group?)` —— **仅在选了分组时才把 `group` 写进报文**，保持既有报文契约不变。
- `searchStore.ts`：新增 `selectedGroup`，与 `selectedSourceId` **互斥**（setter 互相清空），`startSearch` 把它交给搜索通道，`reset` 一并还原。
- `SearchScopeBar.tsx`（新组件，书库页搜索栏**下面**）：
  - `role="tablist"` 的选项卡行：`全部书源` → 各分组（显示 `enabledCount`）→ `未分组`，右侧是「指定单个书源」下拉；
  - 独立成组件而非内联：纯展示 + 回调，可用 `renderToStaticMarkup` 静态渲染锁定「全部书源在最前且默认」这条需求。
- `main.tsx`：书库页接入 `SearchScopeBar`；书源页分组筛选框选中具体分组时显示「重命名 / 删除分组」（`window.prompt` / `window.confirm`，与书架分组删除同样明示「书源不会被删除」）；分组列表统一从 `GET /api/source-groups` 取，**不从 `sources` 推导**（书源页的筛选框会改写 `sources`，推导出来的分组列表会随搜索词变化）。

## 5. 验收基准 (Acceptance Criteria)

> 自动化已覆盖的部分见 `docs/acceptance/ACCEPT-021-source-groups.md`。

- [ ] **书源页**：分组筛选框选中某分组后出现「重命名 / 删除分组」；改名后组内所有源一次性改名；删组后书源仍在、变为未分组。
- [ ] **书库页**：搜索栏**下面**有 `全部书源` + 各分组 + `未分组` 选项卡；默认停在 `全部书源`；选中分组后搜索进度只统计该分组的已启用书源。
- [ ] **回到全量**：点「全部书源」后范围完全复位（源数回到全部已启用源）。
- [ ] **备份导入**：导入手机备份后提示含「含书源分组 N 个」，书库页随即出现对应分组选项卡。
- [ ] **自动化**：`npm --prefix web run check`、`npx tsx web/test/run-all.ts`、`./gradlew :server:test`（与干净基线的失败集合逐条比对，见 SESSION-032）。
