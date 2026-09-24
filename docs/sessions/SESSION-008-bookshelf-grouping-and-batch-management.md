---
id: SESSION-008
title: 书架自定义分组存储、安全降级与多选批量管理
date: 2026-09-18
author: Antigravity
tags: [bookshelf, grouping, batch-operation, sqlite, react, full-stack]
---

# SESSION-008: 书架自定义分组存储、安全降级与多选批量管理

## 1. 需求与架构背景 (Investigation & Analysis)
- **业务痛点**：用户在书架书籍增多（几十本上百本）后，缺乏有效的组织与整理手段；切换书架状态和批量删除/改组流程繁复，每次仅能单本操作。
- **核心挑战**：
  1. **数据安全第一原则**：删除或重命名分组时，绝对不能级联删除组内的图书数据与阅读进度。
  2. **多层级过滤联动**：顶部一级分组标签（带数量徽标）与二级阅读状态胶囊（全部/正在阅读/已读完）需要高性能即时筛选。
  3. **批量操作原子性**：批量改组、批量标记状态与批量移出书架必须具备事务保证。
  4. **表单重名与异常防护**：避免向前端直接暴露 SQLite 底层 `UNIQUE constraint` 异常。

## 2. 尝试过的无效方案与踩坑 (Failed Attempts & Why)
- *尝试方案 1*：直接在客户端前端状态中维护分组，不存入后端 SQLite。
  - *失败原因*：多端、刷新或容器重启后分组丢失，无法与书籍实体关联。
- *尝试方案 2*：删除分组时直接 `DELETE FROM book_group` 并依靠数据库 `ON DELETE SET NULL` 级联外键。
  - *失败原因*：SQLite 默认外键约束可能被关闭或兼容性不一致；显式在代码事务中先执行 `UPDATE book_shelf SET group_name=null WHERE group_name=?` 再删除分组记录最为安全稳妥。
- *尝试方案 3*：创建/重命名分组时依赖底层 SQLite UNIQUE 约束抛出异常直接 catch。
  - *失败原因*：底层异常文本不友好且容易导致不同环境日志告警，应在代码层显式 `select count(*)` 预检并抛出 `IllegalArgumentException("分组「xxx」已存在")`。

## 3. 最终落地的正确解法 (Final Solution)
1. **服务端存储与迁移**：
   - 新建 `book_group (id, name, sort_order, created_at)` 表与唯一索引。
   - `book_shelf` 表通过 SQLite `ALTER TABLE ADD COLUMN` 动态安全升级新增 `group_name` 字段与索引。
2. **安全降级保证**：
   - 组删除时，将书籍 `group_name` 重置为 `null`（归入“未分组”），保留全部阅读进度与离线缓存。
   - 组重命名时，在同一事务中同步更新 `book_shelf.group_name`。
3. **批量事务接口 (`/api/bookshelf/batch`)**：
   - 在单一数据库事务中批量执行 `move_group`, `mark_completed`, `delete`，避免多次网络往返与中间脏状态。
4. **前端交互与视效**：
   - 一级分组标签栏（`ShelfGroupTabs`）带有未分组、全部与各分组数量 Badge，末尾集成 `+ 管理分组` 弹窗。
   - 二级阅读状态胶囊（`ShelfStatusPills`）无缝联动。
   - 批量管理模式：卡片右上角复选框、高亮选中、底部毛玻璃悬浮操作栏（全选/反选、移动分组、标记状态、移出书架）。
5. **自动化真机与容器验证**：
   - 通过 Puppeteer 驱动本机 Chrome 在真实 Docker 容器环境中执行全链路自动化实操，录制高清 MP4 视频与多维页面截图。

## 4. 沉淀的教训与部落知识 (Lessons Learned & Tribal Knowledge)
- **[书架/分组] 组删除永远遵循软解绑降级（Soft Fallback）**：删除 `book_group` 记录前必须先执行 `UPDATE book_shelf SET group_name=null WHERE group_name=? COLLATE NOCASE`，绝不级联删除书籍。
- **[前端/交互] React Controlled Input 自动化分发事件**：在 Puppeteer 自动化测试中，修改 React 控制型表单输入框时需使用 `nativeSetter.call(input, val)` 配合 `dispatchEvent(new Event('input', { bubbles: true }))` 确保 React 内部 Fiber 状态同步更新。
- **[SQL/SQLite] 分组名称防重名友好拦截**：创建与重命名分组前需显式执行 `SELECT count(*) ... COLLATE NOCASE` 预校验，避免暴露底层 `SQLITE_CONSTRAINT_UNIQUE` 异常。
