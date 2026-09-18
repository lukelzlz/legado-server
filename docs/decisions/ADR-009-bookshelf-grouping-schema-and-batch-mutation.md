---
id: ADR-009
title: 书架分组存储模型、状态联动与原子批量操作设计
status: accepted # proposed | accepted | superseded | deprecated
date: 2026-09-18
---

# ADR-009: 书架分组存储模型、状态联动与原子批量操作设计

## 1. 决策背景 (Context)
随着书架藏书量上升，单层扁平的书籍列表无法满足用户多样化的分类整理需求。系统需要支持：
1. 自定义分组（增、删、改、排）。
2. 每本书籍的单分组归属。
3. 批量归类、批量状态标记与批量清理。
4. 与现有“阅读状态（正在阅读/已读完）”协同过滤，保持现有界面极致简洁流畅的设计语言。

## 2. 裁定方案 (Decision)

### 2.1 数据库结构与无损迁移
1. **新建分组元数据表 `book_group`**：
   ```sql
   create table if not exists book_group (
     id integer primary key autoincrement,
     name text not null unique collate nocase,
     sort_order integer not null default 0,
     created_at integer not null
   );
   create index if not exists idx_book_group_sort on book_group(sort_order asc, id asc);
   ```
2. **扩展书架表 `book_shelf`**：
   - 动态 `PRAGMA table_info(book_shelf)` 检查并自动执行：
     `alter table book_shelf add column group_name text`
   - `group_name` 存储用户分组名称，为 `NULL` 或 `""` 时表示“未分组”。
3. **数据一致性守卫**：
   - 当重命名分组时，同步更新 `update book_shelf set group_name = ? where group_name = ?`。
   - 当删除分组时，原子执行 `update book_shelf set group_name = null where group_name = ?` 与 `delete from book_group where name = ?`，确保组内书籍无缝回退至未分组，绝不丢书。

### 2.2 服务端 API 契约设计
- `GET /api/bookshelf/groups`：返回 `List<BookGroup>`（包含 `id`, `name`, `sortOrder`, `bookCount`）。
- `POST /api/bookshelf/groups`：创建新分组 `{ name: String }`。
- `PUT /api/bookshelf/groups`：更新分组列表排序或重命名 `{ groups: List<BookGroupUpdate> }` 或单独修改。
- `DELETE /api/bookshelf/groups?name=xxx`：删除分组（组内书籍自动归入未分组）。
- `PUT /api/bookshelf/group`：更新单本书籍所属分组 `{ sourceId: String, bookUrl: String, groupName: String? }`。
- `POST /api/bookshelf/batch`：原子批量操作 `{ action: "move_group" | "mark_completed" | "delete", items: List<BookKey>, targetGroup?: String, completed?: Boolean }`。

### 2.3 前端 UI/UX 架构融合设计
1. **顶部分组横向流式 Tab 栏**：
   - 包含：`全部 (N)` | `未分组 (N)` | `[自定义分组1] (N)` | `[自定义分组2] (N)` ... | `⚙️ 管理` 按钮。
   - 视觉风格与现有 `.shelf-tabs` 保持统一的极简下划线强调色与角标。
2. **二级阅读状态胶囊过滤（Filter Pills）**：
   - 位于分组 Tab 下方或右侧：`全部` / `正在阅读` / `已读完`，实现多维交叉筛选（例如“查看修仙分组中正在阅读的书”）。
3. **批量管理交互模式（Batch Mode）**：
   - 点击右上角「批量管理」触发多选模式。
   - 卡片呈现 Checkbox 勾选框；底部悬浮毛玻璃 Action Bar：显示已选数量，提供「移动分组」、「标记状态」、「移出书架」及「退出管理」。

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：多对多 Tag 标签关联表**
  - *否决理由*：大部分用户在书籍分类上倾向于清晰的一对一文件柜归档心智（每个书放在一个特定分组）。多标签会大幅增加关系表维护复杂度与 UI 勾选复杂度，违反简约原则（复杂度惩罚）。
- **备选方案 B：仅前端 LocalStorage 保存分组**
  - *否决理由*：跨浏览器、跨设备、清空缓存或 Docker 容器重建后会丢失分组，书架数据必须在服务端 SQLite 统一持久化。
- **备选方案 C：采用外键 ID 关联而非分组名称**
  - *权衡考量*：Legado 生态各端导出 JSON 时通常使用可读的 `group` 字符串名称。使用 `group_name` 文本并在重命名时做级联更新，可完美保持与书源生态及备份还原的强兼容性。

## 4. 后果与权衡 (Consequences & Trade-offs)

- **正面收益**：
  - 彻底解决书架藏书量增加后的杂乱问题，满足按类型、追更状态管理书架的需求。
  - 批量操作极大降低书架整理成本。
  - 数据模型简单清晰，升级无痛，向前向后完全兼容。
- **负面代价**：
  - 前端书架页面状态从单一的 `view` 扩展为 `selectedGroup` + `selectedStatus` + `batchMode`，需做好 React 状态解耦与局部缓存优化。
