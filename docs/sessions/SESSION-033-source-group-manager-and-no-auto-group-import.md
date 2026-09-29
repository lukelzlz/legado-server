---
id: SESSION-033
title: 分组管理独立入口与「导入不自动分组」语义修订 (Group Manager Panel & No-Auto-Group Import)
date: 2026-09-29
status: tested
related: [PROPOSAL-021, ADR-021, SESSION-032, SESSION-015]
---

# SESSION-033: 分组管理独立入口与「导入不自动分组」语义修订

## 1. 用户诉求（原话拆解）

> 「不自动给书源分组，然后分组管理从批量管理中独立出一个按钮，分组可以删除批量勾选，添加到某一个分组」

追问后确认的两条语义边界：

| 场景 | 期望 | 落地位置 |
| :--- | :--- | :--- |
| **导入书源文件**（JSON / 订阅更新） | **不自动分组**，新源一律「未分组」 | `Routes.kt` `/sources/import`、`SubscriptionService` → `importSources(applyGroups = false)` |
| **导入手机备份** | 按备份里的 `bookSourceGroup` 落库 | `BackupImporter` → `importSources(applyGroups = true)` |
| 分组管理入口 | 从「批量管理」里**独立成一个按钮** | 书源页 `分组管理` 按钮 + `SourceGroupManagerModal` |
| 分组管理能力 | 可删组、可批量勾选书源加入某个分组 | 面板内「已有分组」+「把书源加入分组」两块 |

「空分组」按第一点（沿用字符串列模型）：不引入分组表，**分组随第一条源出现、源全部移走后自然消失**（见 ADR-021）。

## 2. 改动

### 2.1 后端：两条导入路径的分组语义分开

- `SourceCodec.parse(text, keepGroup: Boolean = true)`
  - `keepGroup = false` 时**同时**清掉记录里的 `group` **和 payload 里的 `bookSourceGroup`/`sourceGroup`/`group`**。
  - 只清列不清 payload 是个隐蔽坑：编辑弹窗读的是 payload，会显示一个库里并不存在的分组，用户一保存又把它写回列里。
- `Database.importSources(rawSources, applyGroups: Boolean = false)`
  - `applyGroups = false` 时 **DO UPDATE 子句里不出现 `source_group`** —— 新行写 null，**已有行保持原样**。
    这是不可省的守卫：否则「再导入一次同一个书源文件」会把用户手工分好的组全部清空（本次已用测试锁死）。
  - `sourceGroups` 计数在 `applyGroups = false` 时恒为 0（提示里如实显示「不带分组」）。
  - 默认值取 `false`（= 用户新语义），备份路径**显式**传 `true`，避免以后新增调用点时默认把分组带进来。
- 调用点：`Routes` `/sources/import` → false；`SubscriptionService.update` → false；`BackupImporter` → true。
- 书源编辑器保存（`PUT /api/sources/{id}`）仍走默认 `keepGroup = true`：那是用户**手写**的 JSON，不该替他丢字段。

### 2.2 前端：`SourceGroupManagerModal`（新）+ 移除旧入口

- 新面板（书源页顶部 `分组管理` 按钮，与「体检 / 批量管理」并列）：
  - 已有分组列表：名字 + 「N 个 · 启用 M」+ 「重命名」「删除」；
  - 把书源加入分组：目标分组下拉（已有分组 / `+ 新建分组…` 配输入框）+「加入分组」「移出分组」；
  - 可搜索的书源勾选列表（名称/地址/分组），带 全选 / 全不选 / 反选，**未分组的源排在前面**。
  - 书源列表在面板内**单独拉全量**（`api.sources()`），不复用外层可能已被筛选框过滤的 `sources`。
- 删除 `SourceGroupModal.tsx`（旧的「批量设置分组」，随批量管理一起被替换）与它的整套 CSS；
  批量操作栏里的「修改分组」按钮、书源页分组筛选框下的「重命名/删除分组」两枚按钮**一并移除**（分组管理只剩一个入口）。
- 导入提示补一句：「（导入的书源默认未分组，可在「分组管理」里归类）」——不然用户会以为导入把分组弄丢了。

## 3. 踩坑与注意

1. **`applyGroups = false` 的 upsert 绝不能写 `source_group=excluded.source_group`**：写就等于「用 null 覆盖」。SQL 里那一项是按需拼接的（`groupOnConflict`），并有专门的回归用例。
2. **payload 与列必须同步清理**（上面的 `keepGroup` 说明）。
3. **不要复用外层 `sources` 做分组管理**：它会被书源页的搜索框过滤（SESSION-032 已踩过一次），表现为「明明有 200 个源却只能勾到 12 个」。
4. 🚨 **语义变更会静默打穿既有测试**：默认值从「采用分组」改成「不采用」后，**两条既有断言直接失效**——
   - `DatabaseTest > listSources projects summary fields without loading large payload`：断言 `summary.group == "精品"`，第一次全量跑就**真失败**（这条当时还是个"1 failed"被我当成 flake，实际是回归；改为显式 `applyGroups = true`，因为它验证的是投影而不是导入语义）；
   - `ApiRoutesHttpTest` 的批量分组用例断言 `batch2.group == "旧分组"`（该组来自导入）——这条在本机被 teardown 的 `FileSystemException` **掩盖**（Java 的 finally 抛异常会顶替原异常），Linux CI 上才会暴露。已改为：导入后**显式断言分组为空**（锁住新语义），再用一次 `set_group` 把 batch2 单独置组，让「批量改组只影响被选中的源」这条原意保留。
   > 教训：**改默认值/语义时，必须 `grep` 断言点（而不只是调用点）**；且「本机失败数没变」不等于没回归——本机有 54 个 teardown 失败会掩盖断言失败。
5. **弹窗遮罩会吃掉后续点击**：e2e 里不先关掉面板就去点「批量管理」，点击被 backdrop 当成"关闭面板"，表现为「点了按钮没反应、等不到批量栏」。
6. 旧 e2e 断言（「选中分组后出现重命名/删除」）随入口迁移而失效，已改为断言「独立按钮 + 面板内三块能力 + 批量管理里不再有分组动作」。

## 4. 验证

| 命令 / 手段 | 结果 |
| :--- | :--- |
| `npm --prefix web run check` | 通过 |
| `npx tsx web/test/run-all.ts` | **164 / 164**（新增 3 条：面板结构、目标分组判定、删除文案） |
| `./gradlew :server:test`（本次相关用例） | `SourceGroupTest`(7) / `SourceGroupRoutesTest`(1) / `SourceCodecTest`(9) / `BackupImportTest`(7) / `RealBackupSourceGroupTest`(1) 全绿 |
| `./gradlew :server:test`（全量） | 349 用例 / **54 失败 / 1 跳过**；失败均为既有的 Windows SQLite teardown 噪声，且**没有任何一条落在本次改动的测试类**（见第 3 节第 4 条的两个既有断言修好后的结果） |
| 真实实例联调（`installDist` + 真实 HTTP） | 8 条断言全绿：导入书源文件 `sourceGroups=0` 且分组列表为空 / 批量勾选加入分组 / **再导入不覆盖手工分组** / **真实手机备份导入 `sourceGroups=1` 且出现 `大灰狼聚合`** / 改名撞名即合并 / 删组只解绑（书源数不变） |
| 真实浏览器 `web/test/e2e-source-groups.ts`（本机 Edge） | **16 条断言全绿**：搜索范围选项卡（全部书源第一且默认）+ 「分组管理」独立按钮 + 面板可开/含重命名·删除·加入分组·移出分组/可批量勾选 + 批量管理里已无分组动作 |

新增/更新的服务端用例：
- `SourceCodecTest.keepGroup false strips the group from both the record and the payload`
- `SourceGroupTest.plain source import never auto groups and never wipes a manual group`
- `SourceGroupRoutesTest`：`/sources/import` 不再带分组（`sourceGroups == 0` 且分组列表为空）→ 再导入一次不得冲掉手工分组。
- 既有断言修正：`DatabaseTest`（显式备份语义）、`ApiRoutesHttpTest`（导入不带分组 + 单独置组）。

## 5. 未做

- 空分组（需要分组表，ADR-021 已明确否决）；
- 按规则自动归类（用户明确要求「不自动分组」）。
