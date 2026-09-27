---
id: SESSION-031
title: 备份导入分组（bookGroup.json）与书签（bookmark.json）
date: 2026-09-27
author: Agent
tags: [backup-import, book-group, bookmark, legado]
---

# SESSION-031: 备份导入分组与书签

## 0. 需求

> backup 压缩包里有分组数据 `bookGroup.json`，导入时也要导入分组数据；
> 同时导入备份时阅读记录 `bookmark.json` 同时导入。

## 1. 数据勘查（真实备份）

备份包内含 19 个条目，本次涉及两个：

```
bookGroup.json    2191 bytes
bookmark.json    26376 bytes
```

### 1.1 一个重要澄清：两份数据不是同一份

勘查时发现工作区里有**两份不同的书架数据**，数字不能混用：

|  | `te/bookshelf.json` | `backup2026-09-27.zip` |
| :--- | ---: | ---: |
| 总条数 | 434 | **220** |
| 本地图书 | 14 | **5** |
| 音频（听书） | 31 | **0** |
| 在线小说 | 389 | **215** |

**但 `bookGroup.json` 完全一致**（FQ=27 / 完结=74 / 15=2）——
分组不随书架条目变化，所以两边的分组结论可以互证。

> 我一开始照抄 `te/` 的 434/14/31 到真实备份测试里，立刻被测试打脸
> （`应跳过 14 本本地图书 expected:<14> but was:<5>`）。
> **教训：期望值必须来自当前被验证的那份数据。**

### 1.2 `bookGroup.json` 结构

```json
{"bookSort":-1,"enableRefresh":true,"groupId":-20,"groupName":"在读","order":0,"show":false}
```

16 个分组，其中 **12 个 `groupId` 为负 = Legado 内置智能分组**
（在读/未读/已读/小说/漫画/视频/全部/本地/音频/网络未分组/本地未分组/更新失败）。

它们是**按条件动态筛选的虚拟分组**，实测**全是空的**。
用户自建的正数 id 分组只有 4 个：`FQ(1)` / `完结(2)` / `失效(4)` / `15(8)`。

### 1.3 分组 → 书籍

| id | 分组名 | 书架中数量 | 导入后保留 |
| ---: | :--- | ---: | ---: |
| 1 | FQ | 27 | 27 |
| 2 | 完结 | 74 | 74 |
| 8 | 15 | 2 | 2 |
| 0 | *(未分组)* | 117 | 112 |

一致性检查：bookshelf 用到的 group id **全部**在 `bookGroup.json` 里有定义（无孤儿引用）。

### 1.4 `bookmark.json` 结构

```json
{"bookAuthor":"小白在放鸽子","bookName":"被废修为后…","bookText":"第149章 因为恨你\n　　…",
 "chapterIndex":147,"chapterName":"第149章 因为恨你","chapterPos":0,"content":"","time":1781621060857}
```

47 条，涉及 18 本书，**其中 29 条挂在被过滤的书上**（本地图书/音频）。

## 2. 设计决定（用户确认）

| 决定 | 选择 | 理由 |
| :--- | :--- | :--- |
| 空的内置分组 | **只导入有书的分组** | 12 个内置分组都是空的，导入只会得到一堆点不动的空分组 |
| 孤儿书签 | **跳过** | 书不在书架上，书签没有展示位置 |

## 3. 关键设计：分组是「按名字」关联的

**这是本次最容易出错的地方。**

- `bookshelf.json` 里存的是**数字 `group` id**（如 `1`、`2`、`8`）
- 本服务的 `book_group` 与 `book_shelf.group_name` 是**按名字**关联的
  （`listBookGroups` 的 `left join book_shelf s on s.group_name = g.name collate nocase`）

因此导入必须做**两步**：① 建分组；② 把书的 `group_name` 填上对应名字。
只做①会出现「分组按钮有了但里面是空的」。

另外 `group_name` 的更新带 `and (group_name is null or group_name='')` 条件——
**不能覆盖服务端已有的分组**，否则用户在服务端手工整理过的分组会被一次备份导入悄悄冲掉。

## 4. 实现

### 新增

- `BackupGroupEntry` / `BackupBookmarkEntry` / `Bookmark` / `GroupImportResult`（Models.kt）
- `bookmark` 表：`unique(book_name, book_author, chapter_index, chapter_pos)` 保证幂等
- `Database.importBookGroups(groups, shelf)` —— 建分组 + 赋 `group_name`
- `Database.importBookmarks(marks)`
- `Database.countBookmarks` / `listBookmarks`（供测试与后续 UI）

### 改动

- `BackupImporter`：解析 `bookGroup.json`、`bookmark.json`；
  `parseShelf` 增加 `groups` 参数做 id→名字映射
- 顺序：`importLibrary` → `importBookGroups`（**必须在书架之后**，它要更新 `group_name`）
  → `importBookmarks`
- 摘要增加 `bookmarks` / `bookmarksSkipped`，前端 toast 如实提示

`readSection` 本来就 `lowercase()` 匹配条目名，所以 `bookGroup.json` 的大小写无需额外处理。

## 5. 真实备份验证结果

对 `backup2026-09-27-PEPM00.zip`（220 条）实跑：

| 项目 | 期望 | 实测 |
| :--- | :--- | :--- |
| 跳过本地图书 | 5 | **5** ✓ |
| 跳过音频 | 0 | **0** ✓ |
| 导入在线小说 | 215 | **215** ✓ |
| 分组 | 只有 FQ/完结/15 | **FQ 27 / 完结 74 / 15 2** ✓ |
| 内置空分组 | 一个都不要 | **0 个** ✓ |
| 书签导入 | 18 | **18** ✓ |
| 书签跳过 | 29 | **29** ✓ |

抽查：`被废修为后，我能否逃离她的魔爪`（小白在放鸽子）的 2 条书签正确入库。

## 6. 调试过程中被测试抓出的三个问题

写测试时踩了三个坑，**都是我测试代码自身的 bug，不是产品代码**——
但它们正好说明「为什么真实数据核对不可省」：

| 现象 | 真实原因 |
| :--- | :--- |
| `13 个内置智能分组 expected 13 but was 12` | 我数错了：负数 id 实际是 **12** 个（把正数 `4=失效` 也当成内置了） |
| `重复导入不应新增 expected 0 but was 1` | **SQLite 的 upsert 在 `do update` 时 `changes()` 同样返回 1** —— 想验证幂等必须查**实际行数**，不能看 `changes()` |
| `完结 应有 1 本书 but was 0` | 我构造的两本书 **`bookUrl` 完全相同**，被 `book_shelf` 的唯一键 `(source_id, book_url)` UPSERT 合并成一行。真实备份里 bookUrl 含各自 `book_id`，天然唯一 |

另外 `updateBookshelfInfo` 只写 `book_shelf.group_name`、**不会**建 `book_group` 行，
所以测试「手工分组不被覆盖」时必须先显式 `createBookGroup("手工分组")` 才能观察到它。

## 7. 验证

- `BackupGroupAndBookmarkTest`（**12 用例**）：真实 `bookGroup.json` 结构解析、
  只导有书分组、id→名字映射、`group=0` 即未分组、书签结构解析、
  孤儿书签跳过、作者为 null 也能匹配、入库赋分组、幂等、不覆盖已有分组、空输入安全、端到端
- `RealBackupGroupAndBookmarkTest`（**1 用例**）：对真实备份包实跑，用 `assumeTrue`
  在文件缺失时跳过（CI 无此文件属正常）
- 回归：315 → **328** 用例，失败 **54 → 54 集合逐条一致** ⇒ 零回归
- 前端：`npm run check` 0 错误、`tsx run-all.ts` **146/146**

## 8. 沉淀的教训

1. **期望值必须来自「当前被验证的那份数据」**：`te/` 的 434 条与备份的 220 条是两份数据，
   照抄数字会得到假失败（也会掩盖真问题）。
2. **SQLite upsert 的 `changes()` 不能用来判断「是否新增」**：`do update` 也算 1 行受影响。
3. **构造测试数据时唯一键必须唯一**：`book_shelf` 的主键是 `(source_id, book_url)`，
   两本虚构书用同一个 URL 会被静默合并成一行，症状是「导入了 2 本但只查到 1 本」。
4. **「建分组」与「把书放进分组」是两件事**：跨系统导入时先确认目标的关联方式
   （这里是按名字，备份是按 id），漏掉映射那步会得到「分组存在但为空」。
5. **导入不能覆盖用户已有数据**：`group_name` 只填空值，`book_group` 用 upsert 不删不改。
