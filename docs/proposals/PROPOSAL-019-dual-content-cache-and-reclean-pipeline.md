---
id: PROPOSAL-019
title: 双副本正文缓存与规则重洗流水线 (Dual Content Cache and Re-clean Pipeline)
status: accepted
author: Antigravity & User
date: 2026-09-25
---

# PROPOSAL-019: 双副本正文缓存与规则重洗流水线

## 1. 业务背景与问题痛点

在当前架构中，网络书源或离线缓存下载章节正文时，服务端在提取完书源正文后直接执行用户自定义替换净化规则（`ReplaceRule`），并将清洗后的结果单份持久化写入 SQLite 缓存表 `book_content_cache`。

这导致两个关键问题：
1. **原始正文丢失**：当用户编写了错误的替换规则（如误删大段正文或误伤关键词）时，缓存中的文本已被污染且无法还原，必须强制清空缓存并重新全量联网下载。
2. **规则更新无法回溯**：用户新导入或更新了错字/去广告净化规则后，已缓存的成百上千章书籍无法应用新规则，要么忍受旧缓存中的广告/错字，要么重新消耗网络流量和时间全本重新缓存。

## 2. 目标与非目标 (Goals & Non-Goals)

### Goals
- **双副本存储 (Dual Storage)**：在 SQLite `book_content_cache` 表中同时持久化存储「书源提取原文 (`raw_content`, `raw_title`)」与「清洗后展示内容 (`content`, `title`)」。
- **秒开直出 (Fast Serving)**：正常阅读、TTS 朗读与翻页加载时，服务端直接从数据库读取清洗后的 `content`/`title` 响应，零运行时重复清洗开销。
- **一键重洗 (Re-clean Pipeline)**：提供重洗服务接口与书架 UI 入口，允许用户在更新替换规则后，基于数据库中留存的 `raw_content` 重新运行最新启用的规则链，快速更新 `content` 副本。
- **平滑兼容 (Backward Compatibility)**：自动兼容存量旧缓存数据，旧数据 `raw_content` 缺省时以已有 `content` 兜底，绝不破坏现有功能。

### Non-Goals
- 不在每次前端 `GET /api/content` 请求时动态重算（必须基于双副本静态化读取以保障秒开体验）。
- 本次不在服务端启动无节制的全局全量定时重洗后台任务（重洗由用户在操作书架/书籍或规则时主动触发）。

## 3. 核心用户故事 (User Stories)

- **Story 1（离线缓存与在线阅读双副本入库）**：
  作为用户，当我在阅读器中打开新章节或点击“离线缓存全本”时，后端提取书源正文后，同时保留书源提取的原始文本 (`raw_content`) 与应用当前规则清洗后的文本 (`content`) 存入数据库。前端获取到的依然是洗净后的完美排版，体验丝滑。
- **Story 2（书架卡片三点菜单手动重洗）**：
  作为用户，当我针对某本书（如《诡秘之主》）新增或修改了一批角色名错字替换规则后，点击书架上该书卡片底部的「三个点」管理菜单，选择「重新应用净化规则 / 重新清洗缓存」，系统快速对该书所有已缓存章节基于原文重跑规则链，并提示“已重新清洗 X 章”。重新进入阅读器后立刻看到最新净化效果。
- **Story 3（规则误删还原与规则测试）**：
  作为用户，若我不小心写错了一条贪婪匹配正则导致正文缺失，在停用该错误规则后，直接点击「重新清洗缓存」，系统即可基于未被破坏的 `raw_content` 瞬间恢复正常正文，无需清空缓存重新联网抓取。
- **Story 4（存量旧数据兼容）**：
  作为老用户，升级服务后原有的 SQLite 数据库自动完成列扩展（`ALTER TABLE ... ADD COLUMN raw_content`），未有 raw 数据的旧缓存章节在点击重洗时以现有内容兜底，不会崩溃报错。

## 4. 系统接口与架构设计

### 4.1 数据库结构升级
`book_content_cache` 表扩展字段：
```sql
ALTER TABLE book_content_cache ADD COLUMN raw_title text;
ALTER TABLE book_content_cache ADD COLUMN raw_content text;
```

### 4.2 服务端 API
- `POST /api/bookshelf/reclean`：
  - 请求体：`{ "sourceId": "...", "bookUrl": "..." }`
  - 响应：`{ "success": true, "recleanedChapters": 128, "bookName": "..." }`
- `POST /api/bookshelf/batch-reclean`：
  - 请求体：`{ "books": [{ "sourceId": "...", "bookUrl": "..." }] }`
  - 响应：`{ "success": true, "totalRecleaned": 256 }`

### 4.3 前端交互集成
- 书架书籍管理弹层（`BookManageSheet`，点击书籍卡片 `...` 触发）：在「离线缓存全本」与「标记已读」之间新增「重新应用净化规则」按钮，显示当前已缓存章节数及重洗操作反馈。
- 书架批量操作栏（`ShelfBatchBar`）：批量选中书籍时支持「批量重洗规则」。

## 5. 验收基准 (Acceptance Criteria)

- [ ] **数据库自愈迁移**：启动时自动检测并为 `book_content_cache` 添加 `raw_title` 和 `raw_content` 列。
- [ ] **双副本正确落盘**：调用 `runner.content()` 与 `cacheBookContent()` 时，`raw_content` 正确存储未应用用户 ReplaceRule 的纯文本，`content` 存储清洗后的文本。
- [ ] **重洗功能闭环**：
  1. 缓存某章节（包含错字 "小丑"）；
  2. 新建规则将 "小丑" 替换为 "愚者"；
  3. 点击重洗，数据库中 `content` 变为 "愚者"，`raw_content` 保持 "小丑"；
  4. 修改规则将 "小丑" 替换为 "克莱恩"，再次重洗，`content` 更新为 "克莱恩"；
  5. 删除规则后再次重洗，`content` 完美还原为 "小丑"。
- [ ] **单测与类型检查**：后端 `./gradlew :server:test` 全部通过，前端 `npm run check` 与 `tsx test/run-all.ts` 零报错。
