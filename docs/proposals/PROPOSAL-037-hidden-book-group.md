---
id: PROPOSAL-037
title: 书架私密/隐藏分组功能设计（对齐 Legado 原版 isPrivate 与全部列表中完全排除）
author: lukelzlz & assistant
status: accepted # proposed | accepted | rejected
created_at: 2026-11-12
updated_at: 2026-11-13
issue: 89
---

# PROPOSAL-037: 书架私密/隐藏分组功能设计

## 1. 业务背景与问题痛点 (Motivation & Problem Statement)

在 GitHub [Issue #89](https://github.com/lukelzlz/legado-server/issues/89) 中，用户提出需求：
> “希望分组内的书可以隐藏，不对外展示. 有需要时再取消隐藏”
> “分组及分组内的所有书”
> 决策指令：“在全部中排除”

### 痛点分析
当前 Web 书架在 `selectedGroup === 'all'` 时会无差别渲染所有书籍。即使单纯将分组 Tab 隐藏，只要用户在「全部」Tab 浏览，隐私书籍的封面、书名、最近阅读进度依然会赫然出现在屏幕上，在公开场合（如办公室、通勤、向他人展示书架）极易造成隐私泄露。

在开源阅读（Legado）Android 原版中：
- `BookGroup` 实体具备 `isPrivate: Boolean` 与 `show: Boolean` 属性；
- 支持将分组设为私密/隐藏；
- 备份规范 `bookGroup.json` 包含该属性。

为彻底解决隐私顾虑并深度对齐 Legado 原版生态，本提案引入**书架分组私密/隐藏机制**，实现**分组与组内书籍在书架主视图（包含「全部」标签页）中的彻底防窥过滤与平滑解锁查看**。

---

## 2. 目标与非目标 (Goals & Non-Goals)

### 2.1 目标 (Goals)
1. **分组级私密开关**：在「书架分组管理」弹窗中，每个分组可一键开启/关闭隐藏状态（对应 `isPrivate` 与 Legado 的 `show`）。
2. **「全部」列表彻底排除**：在默认防窥状态下，书架「全部」Tab 严格排除所有属于隐藏分组的书籍，且顶部统计数字（书籍总数、正在阅读、已读完）同步剔除，杜绝露馅。
3. **顶栏 Tab 防窥隐藏**：默认状态下，隐藏分组的 Tab 从书架顶栏移除，不平铺展示。
4. **分组管理中随时恢复显示**：在「分组管理」弹窗中，随时点击开关恢复公开显示，书架顶部栏恢复该 Tab 且「全部」列表恢复包含；顶栏无多余展开按钮，保持极简。
5. **Legado 备份双向互通**：导入与导出 `backup*.zip`（`bookGroup.json`）时，完整保留 `isPrivate` 与 `show` 属性，与 Legado 移动端生态 100% 互通。

### 2.2 非目标 (Non-Goals)
1. **单本书级繁复打标（Non-Goal）**：不引入针对单本书的独立打标/毛玻璃遮罩。用户通过归入隐藏分组即可批量管理隐藏状态。
2. **多用户与角色权限系统（Non-Goal）**：本项目为单用户/家庭私有无头服务（PBKDF2 会话鉴权），不额外建立复杂的多租户隔离。
3. **书源搜索结果屏蔽（Non-Goal）**：全网书源搜索是公共检索，不在书架隐藏范畴。

---

## 3. 核心用户故事 (User Stories)

### Story 1: 分组标记为私密/隐藏
- **作为** 登录用户，
- **我想要** 在书架「分组管理」弹窗中，点击某分组的「眼睛 / 私密」图标，将其设为隐藏分组，
- **以便于** 将该分组及组内的所有书籍一并纳入防窥保护。

### Story 2: 书架主视图防窥（「全部」列表完全排除）
- **作为** 处于公开环境下的用户，
- **我想要** 在进入书架、停留在「全部」Tab 或按阅读状态筛选时，隐藏分组中的所有书籍均不出现，且书籍统计角标不计入隐藏书籍，
- **以便于** 他人在旁边时完全无法察觉到隐藏书籍的存在。

### Story 3: 分组管理中恢复与切换显示 (方案 A)
- **作为** 用户，
- **我想要** 在需要阅读隐藏书籍时，通过书架分组管理弹窗将该分组重新开启显示，
- **以便于** 该分组重新在书架顶部 Tab 栏展示且进入「全部」列表；看完可随时在管理弹窗再次设为隐藏。

### Story 4: 备份导入导出无损互通
- **作为** 同时使用 Legado 手机端与本服务的用户，
- **我想要** 在 Web 端导出备份并在手机端恢复（或反向导入）时，隐藏分组状态保持一致，
- **以便于** 跨端迁移书架时免去重新设置分组隐私的繁琐工作。

---

## 4. 架构设计与改动清单 (Architecture & Changes)

### 4.1 数据库结构与迁移 (`Database.kt`)
- `book_group` 表新增字段：
  ```sql
  alter table book_group add column is_private integer not null default 0;
  ```
- 在 `initDb` 中执行 `PRAGMA table_info(book_group)` 判定并自动补齐字段，确保现有 SQLite 数据库无感兼容。
- 数据库操作方法：
  - `listBookGroups(): List<BookGroup>` 读取 `is_private` 列；
  - `updateBookGroupPrivacy(name: String, isPrivate: Boolean): BookGroup` 提供原子更新；
  - `createBookGroup` / `renameBookGroup` 保留原有语义。

### 4.2 数据传输模型 (`Models.kt`)
- `BookGroup` 增加字段：
  ```kotlin
  @Serializable
  data class BookGroup(
      val id: Long,
      val name: String,
      val sortOrder: Int,
      val bookCount: Int = 0,
      val isPrivate: Boolean = false,
  )
  ```
- 新增请求 DTO：
  ```kotlin
  @Serializable
  data class BookGroupPrivacyRequest(val name: String, val isPrivate: Boolean)
  ```

### 4.3 服务端 API 契约 (`Routes.kt`)
- 新增路由：`PUT /api/bookshelf/groups/privacy`
  - 鉴权：需要管理员 Session；
  - 参数：`BookGroupPrivacyRequest(name, isPrivate)`；
  - 响应：更新后的 `BookGroup` 对象；
- `GET /api/bookshelf/groups`：自动返回带有 `isPrivate` 字段的分组列表。

### 4.4 备份导入与导出对齐 (`BackupExporter.kt` / `BackupImporter.kt`)
- **导出 `bookGroup.json`**：
  - `"show": !group.isPrivate`
  - `"isPrivate": group.isPrivate`
- **导入 `bookGroup.json`**：
  - 解析时若 `isPrivate == true` 或 `show == false`，均设为 `isPrivate = true`。

### 4.5 Web 客户端交互 (`web/src/main.tsx` & `web/src/api.ts`)
- **类型定义**：`export type BookGroup = { id: number; name: string; sortOrder: number; bookCount: number; isPrivate: boolean }`
- **API 方法**：`updateBookGroupPrivacy: (name: string, isPrivate: boolean) => request<BookGroup>(...)`
- **书架状态逻辑 (`ShelfPage`)**：
  - `hiddenGroupNames = useMemo(() => new Set(groups.filter(g => g.isPrivate).map(g => g.name.toLowerCase())), [groups])`；
  - **关键列表过滤**：
    - 当 `selectedGroup === 'all'` 时：过滤排除掉 `item.groupName && hiddenGroupNames.has(item.groupName.toLowerCase())`；
    - 当 `selectedGroup === '__ungrouped__'` 时：只展示无分组书籍；
    - 当选定具体分组时：按选定分组名展示；
  - **角标与计数联动**：
    - `groupCounts.all` 仅统计当前可见书籍总数（排除隐藏书籍），杜绝数字端倪；
  - **顶栏 Tab 展示**：
    - 仅展示公开分组：`groups.filter(g => !g.isPrivate)`，顶栏保持极简无额外展开按钮；
    - 若当前选中的分组在管理中被设为隐藏，自动安全退回「全部」视图；
- **「管理分组」弹窗 (`ManageGroupsModal`)**：
  - 每行分组展示眼睛按钮与 🔒 徽标，点击一键切换 `isPrivate`，即时保存并刷新。

---

## 5. 验收标准与验证用例 (Acceptance Criteria)

- [ ] **数据库与迁移测试**：
  - 旧库无 `is_private` 列时能够自动平滑迁移且旧数据 `isPrivate` 为 `false`。
  - `Database.updateBookGroupPrivacy` 可正确切换 0 与 1。
- [ ] **备份导入导出兼容测试**：
  - 导出的 `bookGroup.json` 包含正确的 `show` 与 `isPrivate` 键值。
  - 导入包含 `show: false` 的 Legado 原版备份包时，该分组自动标记为 `isPrivate = true`。
- [ ] **书架列表与计数防窥验证**：
  - 将分组 A 设为隐藏后，返回书架「全部」Tab，组内书籍立即消失；
  - 顶部总书数、正在阅读、已读完数量同步减少，无数字端倪；
  - 顶栏 Tab 栏不出现该隐藏分组。
- [ ] **显隐切换与恢复验证**：
  - 开启「显示已隐藏分组」或在分组管理中取消隐藏后，分组与书籍恢复展示。
- [ ] **全套测试回归无异常**：
  - `./gradlew :server:test` 与 `npm --prefix web run check && npx tsx web/test/run-all.ts` 全部通过。
