---
id: ADR-036
title: 书架私密/隐藏分组存储模型、备份协议对齐与双层防窥视图架构
status: accepted # proposed | accepted | superseded | deprecated
date: 2026-11-13
deciders: lukelzlz, assistant
---

# ADR-036: 书架私密/隐藏分组存储模型、备份协议对齐与双层防窥视图架构

## 1. 决策背景 (Context)

在 GitHub [Issue #89](https://github.com/lukelzlz/legado-server/issues/89) 中，用户明确要求：
> “希望分组内的书可以隐藏，不对外展示. 有需要时再取消隐藏”
> “分组及分组内的所有书”
> “在全部中排除”

目前服务端 `book_group` 表只有 `id, name, sort_order, created_at`，前端 `ShelfPage` 在 `selectedGroup === 'all'` 时会全量渲染全部书籍。
如果不从「全部」列表中排除，单纯隐藏 Tab 仍会在公开场合直接暴露封面和书名，无法达到真正的隐私保护目的。
同时，开源阅读（Legado）Android 原版中 `BookGroup` 具备 `isPrivate: Boolean` 与 `show: Boolean` 属性，备份包 `bookGroup.json` 同样承载此规范。

需要解决以下三个核心决策点：
1. **数据模型与持久化**：如何扩展字段并实现旧数据库平滑迁移？
2. **防窥视图设计（Why 在全部中排除）**：前端如何处理默认排除、状态计数与显式临时查看？
3. **备份协议双向兼容**：如何与开源阅读移动端无损互通？

---

## 2. 备选方案权衡 (Options & Trade-offs)

### 方案 A：针对单本书标记 `is_private`
- **权衡分析**：
  - 用户管理书架是以“分组”为单元（如放同人、特定题材或私密书刊进某一分组）；
  - 单本书标记需要频繁对每本书进行配置，交互成本极高；
  - 破坏了 Legado 原版的 `BookGroup` 备份协议结构。
- **裁定结果**：**否决**。

### 方案 B：仅隐藏分组 Tab，保留在「全部」列表中
- **权衡分析**：
  - 用户只要停留在书架默认的「全部」Tab，所有隐藏书一览无余，未解决核心痛点；
  - 数字统计也未扣减，容易露出端倪。
- **裁定结果**：**否决**。

### 方案 C：分组级 `isPrivate` + 「全部」列表与计数完全排除 + 一键临时展开（采用）
- **权衡分析**：
  - **隐私性极致**：默认防窥状态下，隐藏分组 Tab 隐藏、「全部」Tab 彻底排除、顶部各状态计数同步扣除，公开屏幕完全无痕；
  - **易用性强**：在分组管理弹窗中点击一次开关即可庇护整组书籍；提供顶栏临时眼睛开关或管理弹窗快速恢复；
  - **协议 100% 对齐**：与 Legado 原版 `isPrivate / show` 一一对应，备份包双向互通；
  - **实现最简**：复杂度低，仅在现有书架过滤器追加一层分组私密判据，无破坏性改动。
- **裁定结果**：**通过并采用**。

---

## 3. 裁定方案细节 (Decision)

### 3.1 数据库字段与平滑迁移
1. `book_group` 表增加 `is_private` 列（默认 0，表示公开）：
   ```sql
   alter table book_group add column is_private integer not null default 0;
   ```
2. 启动时通过 `PRAGMA table_info(book_group)` 动态检查，不存在则安全执行 `ALTER TABLE`。
3. 查询和更新：
   - `listBookGroups` 在 SQL 中查询 `g.is_private`；
   - 新增 `updateBookGroupPrivacy(name, isPrivate)`。

### 3.2 备份导入与导出协议对齐
1. **导出**：
   - `bookGroup.json` 中增加字段：
     ```json
     {
       "groupId": 1,
       "groupName": "私密书单",
       "order": 0,
       "show": false,
       "isPrivate": true
     }
     ```
2. **导入**：
   - 优先读取 `isPrivate`；若缺失，则当 `show == false` 时解析为 `isPrivate = true`，确保与 Legado 移动端导出的备份互通。

### 3.3 服务端 API
- 暴露 `PUT /api/bookshelf/groups/privacy` 接口，接收 `{ name: string, isPrivate: boolean }`，返回最新 `BookGroup`。
- `GET /api/bookshelf/groups` 输出 `isPrivate`。

### 3.4 前端防窥与过滤逻辑 (方案 A)
1. **书架主视图防窥**：
   - 过滤集合：当 `selectedGroup === 'all'` 时，过滤排除所有属于隐藏分组（`g.isPrivate === true`）的书籍；
   - 计数联动：`groupCounts.all` 与各阅读状态（正在阅读/已读完）只统计可见书籍，杜绝数字端倪；
   - 导航栏：仅渲染公开分组的 Tab（`groups.filter(g => !g.isPrivate)`），顶栏保持极简，移除任何额外展开按钮；
   - 安全退回：若当前浏览的分组在管理中被设为隐藏，自动退回「全部」视图。
2. **分组管理中切换显隐**：
   - 分组管理弹窗中每行显示眼睛按钮与 🔒 徽标，点击一键切换显隐状态，恢复公开或设为隐藏。

---

## 4. 后果与权衡 (Consequences)

### 正面收益
1. 完美满足 Issue #89 及本次明确的“在全部中排除”的诉求；
2. 移动端 Legado 原版备份互通性得到进一步增强；
3. 工程改动克制，零新增第三方依赖，符合复杂度惩罚原则。

### 约束与注意事项
1. SQLite 单元测试和基线比对需确保 `PRAGMA table_info` 迁移正常；
2. 前端单元测试（`ShelfGrouping.test.ts`）需补充隐藏分组与全部列表排除的断言用例。
