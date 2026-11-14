---
id: SESSION-056
title: 书架私密隐藏分组与「全部」列表彻底防窥排除 (Issue #89)
status: Tested
date: 2026-11-13
author: lukelzlz & assistant
issue: 89
---

# SESSION-056: 书架私密隐藏分组与「全部」列表彻底防窥排除

## 1. 任务背景与问题痛点

在 GitHub [Issue #89](https://github.com/lukelzlz/legado-server/issues/89) 中，用户提出需求：
> “希望分组内的书可以隐藏，不对外展示. 有需要时再取消隐藏”
> “分组及分组内的所有书”
> 决策核心：“在全部中排除”

痛点分析：
1. 原书架分组仅支持普通展示，若仅把分组 Tab 隐藏，当处于默认的「全部」Tab 时，私密书籍的封面与书名仍会一览无余；
2. 开源阅读（Legado）Android 原版 `BookGroup` 具备 `isPrivate` 与 `show: Boolean` 属性，备份包 `bookGroup.json` 导出导入需要保持向前向后兼容；
3. 需要提供平滑的临时展开查阅与一键收回防窥机制。

---

## 2. 核心架构与落地改造

### 2.1 服务端数据层与迁移
1. **表结构升级**（[Database.kt](file:///root/legado-server/server/src/main/kotlin/io/legado/server/Database.kt)）：
   - `book_group` 表新增 `is_private integer not null default 0`；
   - 在 `initDb` 中执行 `migrateBookGroup(db)`，动态查询 `PRAGMA table_info(book_group)` 执行 `alter table` 平滑增量迁移。
2. **读写方法与 API**：
   - `listBookGroups` 与 `getBookGroup` 查询包含 `is_private` 列；
   - 新增 `updateBookGroupPrivacy(name, isPrivate)`；
   - 新增路由 `PUT /api/bookshelf/groups/privacy`，接收 `{ name, isPrivate }`。

### 2.2 开源阅读备份协议双向互通
1. **导出**（[BackupExporter.kt](file:///root/legado-server/server/src/main/kotlin/io/legado/server/BackupExporter.kt)）：
   - `bookGroup.json` 写入 `"show": !group.isPrivate` 与 `"isPrivate": group.isPrivate`。
2. **导入**（[BackupImporter.kt](file:///root/legado-server/server/src/main/kotlin/io/legado/server/BackupImporter.kt)）：
   - 解析时读取 `isPrivate`，并在缺失时当 `show == false` 且非内置智能分组（`!builtIn`）时推导为 `isPrivate = true`。

### 2.3 Web 前端防窥视图 (方案 A)
1. **彻底防窥过滤**（[main.tsx](file:///root/legado-server/web/src/main.tsx)）：
   - `ShelfPage` 在 `selectedGroup === 'all'` 时，过滤排除所有属于隐藏分组的书籍；
   - 顶部总数角标与状态统计（正在阅读、已读完）同步扣减隐藏书籍，杜绝数字端倪；
   - 若用户浏览的分组在管理中被设为隐藏，自动安全切回「全部」Tab。
2. **交互体验**：
   - 分组管理弹窗中提供眼睛按钮一键切换「公开 / 隐藏」，并带有 🔒 徽标；
   - 书架顶栏分组 Tab 栏仅渲染公开分组（`groups.filter(g => !g.isPrivate)`），顶栏彻底干掉任何展开按钮，保持极简；
   - 4 种语言（`zh-CN`, `zh-TW`, `en-US`, `ja-JP`）国际化文案齐全。

---

## 3. 验证与测试基准

1. **前端自动化测试**：
   - `npm --prefix web run check`：通过（0 errors）；
   - `npx tsx web/test/run-all.ts`：436 个测试全部通过（含新增的 `ShelfGrouping.test.ts` 隐藏过滤断言）。
2. **服务端单元测试**：
   - `BackupGroupAndBookmarkTest`（自建分组 `show: false` 映射为 `isPrivate = true` 断言通过）；
   - `DatabaseTest`（`updateBookGroupPrivacy` 与 `isPrivate` 持久化断言通过）；
   - `BackupExporterTest`（私密分组导出 `show: false, isPrivate: true` 断言通过）；
   - Gradle 测试套件执行通过（BUILD SUCCESSFUL）。
