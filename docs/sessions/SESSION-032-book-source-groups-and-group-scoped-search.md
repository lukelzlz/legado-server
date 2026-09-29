---
id: SESSION-032
title: 书源分组与按分组搜书的实施与验证 (Book Source Groups & Group-Scoped Search)
date: 2026-09-28
status: implemented
related: [PROPOSAL-021, ADR-021, SESSION-015, SESSION-031]
---

# SESSION-032: 书源分组与按分组搜书

## 1. 起点：分组数据早就在库里，缺的是「看得见 / 改得动 / 搜得到」

开工前先把现状翻了三处（**先查数据、再读代码**）：

| 需求 | 改动前的真实状态 | 差距 |
| :--- | :--- | :--- |
| 书源支持分组字段 | `source.source_group` 已有列；`SourceCodec.parse` 读 `bookSourceGroup`；`importSources`/`saveSource` 都写入 | **已可用**（无需改动） |
| 分组管理 | 只有 `POST /api/sources/batch` 的 `set_group`（批量塞组） | 没有列表 / 改名 / 删除 |
| 按分组搜书 | `SearchRequest.group` **字段存在但全项目无人读取**（`grep` 只有定义处） | 死字段，搜索无法收窄 |
| 备份带分组 | 分组随 `bookSource.json` 一起落库（同一个 upsert 语句） | **已可用**，但导入摘要不体现，用户无法确认 |

> 教训复用（SESSION-HIST-009 同源）：**「字段存在」不等于「能力存在」**。死字段比缺字段更隐蔽 —— 前端读代码时会以为「传 group 就能按分组搜」，实际服务端全程忽略。

## 2. 实际改动

### 2.1 服务端

- `Models.kt`
  - `SearchRequest.group` 补文档（写明「与 `sourceIds` 取交集」「未分组哨兵」）；
  - 新增 `SourceGroupSummary` / `SourceGroupRenameRequest` / `SourceGroupMutationResponse`；
  - 新增 `object SourceGroupFilter { const val UNGROUPED = "__ungrouped__" }`：**哨兵值只此一处定义**，服务端与前端各持一份常量（前端 `UNGROUPED_SOURCE_GROUP`）；
  - `ImportResponse` 新增 `sourceGroups: Int = 0`（默认值放在末尾，既有位置参数调用不受影响）；`BackupImportSummary` 同步新增同名字段。
- `Database.kt`
  - `listSourceGroups()`：`group by trim(source_group) collate nocase`，返回名字 / 总数 / **已启用数**；
  - `renameSourceGroup(from, to)`：整组改名，目标已存在即合并，返回受影响行数；
  - `clearSourceGroup(name)`：`set source_group = null`，**只解绑不删源**；
  - `listSearchSourceRecords(sourceIds, group = null)`：新增分组条件（哨兵 → `source_group is null or trim(...)=''`），旧调用方零改动；
  - `importSources` 顺手统计本批不同分组数（已是 `ParsedSource.group` 在手，不额外解析）。
- `Routes.kt`
  - 新增 `GET /api/source-groups`、`PUT /api/source-groups/rename`、`DELETE /api/source-groups?name=`；
  - 两条搜索通道（`POST /api/search`、WS `/api/search/stream`）都透传 `request.group`；
  - 「最多 20 个源」的封顶条件补上 `request.group.isNullOrBlank()`：**显式选分组 = 明确收窄，应与按 id 指定书源同等对待**，否则 30 个源的分组只会跑 20 个且无任何提示。
- `BackupImporter.kt`：类注释写明「书源分组无独立条目、随源落库；`bookGroup.json` 是书架分组」，摘要透出 `sourceGroups`。

### 2.2 前端

- `api.ts`：3 个分组接口 + `UNGROUPED_SOURCE_GROUP`；`streamSearch(..., group?)` **仅在选了分组时才把 `group` 写进报文**（保持既有报文契约，`api-client.test.ts` 的 `{keyword, sourceIds}` deepEqual 断言因此不受影响）。
- `searchStore.ts`：新增 `selectedGroup`，与 `selectedSourceId` 互斥（setter 互相清空）；`startSearch` 把分组交给搜索通道；`reset` 一并还原。
- `SearchScopeBar.tsx`（新）：书库页搜索栏**下面**的选项卡（`全部书源` → 各分组（显示已启用数）→ `未分组`，右侧「指定单个书源」）。
- `main.tsx`：书库页接入 `SearchScopeBar`；书源页分组筛选框选中具体分组后出现「重命名 / 删除分组」；分组列表统一走 `GET /api/source-groups`。
- `styles.css`：`.library-scope-bar` / `.scope-tab(.active)` 等样式；删掉随旧下拉一起失效的 `.library-source-select`。移动端把「指定单个书源」换行独占一行。

## 3. 踩坑记录（都实际发生过）

1. **`SearchRequest.group` 是死字段**：`grep` 全项目只有 `Models.kt:179` 一处 —— 若不先查数据/调用点，很容易写出「前端已经在传 group」的错误结论。
2. **分组列表不能从 `sources` 推导**：书源页的 `load()` 用带 `q` 的结果回调 `onSourcesChange`，会把 App 层的 `sources` 改写；从它推导分组会得到「随书源页搜索词变化」的列表。必须走服务端聚合接口。
3. **`SourceGroupRoutesTest` 需要一个不落盘的搜索断言**：书源指向不存在的域名，等结果是等超时。改用 WebSocket 的**第一帧 `start`**（`totalSources` = 本次实际选中的书源数）—— 这是「范围是否收窄」的确定观测量。这也是本仓库第一个 WS 测试。
4. **`start` 帧在 0 个源时没有 `totalSources` 字段**：WS 路由用的是 kotlinx 默认 `Json`（不写默认值），所以 `totalSources=0` 被省略。前端 `progress?.totalSources ?? 0` 已兜底，UI 显示正常；联调脚本里必须 `?? 0`，否则拿到 `undefined` 误判失败。
5. **`tsx` 静态渲染走 classic JSX 变换**：`SearchScopeBar.tsx` 少写 `import React` 会在 `renderToStaticMarkup` 时报 `React is not defined`（构建/类型检查都不报）。仓库里能被静态渲染的组件（`WebDavSettingsPage`/`ReplaceRulesPage`）都显式 import 了 React —— 照抄这个约定。
6. **断言 DOM 前先 dump 一次真实 HTML**：React 的属性顺序是 `type/role/aria-selected/class/title`，我按「class 在前」写断言导致 3 个用例假失败；先 dump 再写断言，一次通过。
7. **`npm ... & echo EXIT=%errorlevel%` 在 PowerShell 里拿到的是解析期旧值**：必须看输出文本或用 `cmd /c` 分步，否则会把「类型检查失败」读成 `EXIT=0`。
8. **Puppeteer 点登录点了「立即安装」**：PWA 安装横幅的按钮同样带 `.primary-button`，`page.click('button.primary-button')` 命中横幅 ⇒ 停在登录页。改用 `键盘回车` 提交表单。
9. **同一 mock 里多次 `streamSearch` 会让 `open` 监听器累积**：第二次 flush 时旧 socket 也补发一次，`sentMessages[1]` 拿到的是旧报文 ⇒ 断言下标失真。改为每段用例一个独立 mock。
10. **Node 里 `process.exit()` 撞 libuv 断言**：WS 尚未完全释放时强退会 `Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)`（退出码 `0xC0000409`）。用 `process.exitCode` 让事件循环自然收尾。

## 4. 验证证据（本轮实测）

### 4.1 最短验证命令集

| 命令 | 结果 |
| :--- | :--- |
| `npm --prefix web run check` | 通过（0 错误） |
| `npx tsx web/test/run-all.ts` | **161 / 161 通过**（新增 8 条：分组载荷/范围互斥/接口 URL/选项卡渲染） |
| `./gradlew :server:test` | 347 用例、**54 失败、1 跳过**；与干净基线（338 用例、54 失败、1 跳过）**失败集合逐条比对完全一致 ⇒ 零回归**（本机 54 个失败全为 SQLite 文件占用 teardown，见 SESSION-HIST-008） |

新增服务端用例：`SourceGroupTest`（6）、`SourceGroupRoutesTest`（1，含 WS 范围断言）、`BackupImportTest.imports book source groups carried by bookSource json`（1）、`RealBackupSourceGroupTest`（1）。

### 4.2 真实手机备份（`backup2026-07-12-rk3399pro_pcie.zip`）

- `RealBackupSourceGroupTest`：文件存在时**真跑到**（skipped=0），断言「备份里的 `bookSourceGroup` 变成可搜分组」且「每个分组报出的启用数 == 按该分组取搜索范围的数量」；
- 该备份只有 1 个书源（`🍅大灰狼聚合5.5.22(vip完全版)`，`bookSourceUrl` 是中文串 `大灰狼融合VIP5.0`），分组为 `大灰狼聚合`。

### 4.3 起真实实例联调（`installDist` + 真实 HTTP/WS）

```
导入摘要: {"sources":1, ..., "sourceGroups":1}
分组列表: 大灰狼聚合(总1/启用1)
改名: affected=1 → 聚合 验证组；删组: affected=1，分组数=0 而书源数仍=1，书源分组=null
改名/删不存在的分组: 均 HTTP 404
重新导入: updated=1 sourceGroups=1，分组恢复为 大灰狼聚合
WS 范围断言: 测试A→1 ✔ / 测试B→1 ✔ / 大灰狼聚合→1 ✔ / 未分组→0 ✔ / 全部书源→3 ✔
```

### 4.4 真实浏览器（本机 Edge + puppeteer-core）

`web/test/e2e-source-groups.ts`（手动执行的 e2e，不进 `run-all.ts`）12 条断言全绿：

```
✔ 搜索栏下方存在搜索范围选项卡 — 全部书源 | 大灰狼聚合1 | 测试A1 | 测试B1 | 未分组
✔ 第一项是「全部书源」   ✔ 默认选中「全部书源」   ✔ 存在「未分组」范围   ✔ 分组选项卡带已启用源数
✔ 选项卡位于搜索栏正下方  ✔ 点击分组后该分组成为唯一激活项  ✔ 「全部书源」仍在且已取消激活
✔ 点回「全部书源」后范围复位
✔ 书源页有分组筛选框 — 全部分组 (3) | 大灰狼聚合 (1) | 测试A (1) | 测试B (1) | 未分组 (0)
✔ 未选中具体分组时不显示管理按钮   ✔ 选中分组后出现「重命名 / 删除分组」
```

截图（工作区根目录）：`e2e-source-groups-library-all.png`、`e2e-source-groups-library-group.png`、`e2e-source-groups-sources-manage.png`。

## 5. 未做 / 后续

- 多分组字符串（`A,B`）按整体处理（见 ADR-021 的已知限制），当前真实数据没有这种源；
- 分组排序 / 颜色不做（分组是筛选维度，不是展示实体）；
- 书库页不做分组管理（职责分离：分组在「书源」页）。
