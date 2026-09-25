---
id: SESSION-021
title: 双副本正文缓存与规则重洗流水线实现与深度对抗审查
date: 2026-09-25
author: Antigravity & User
tags: [replace-rules, cache, sqlite, reclean, dual-storage, performance]
---

# SESSION-021: 双副本正文缓存与规则重洗流水线实现与深度对抗审查

## 1. 现象与需求背景 (Background & Problem)

在 Legado-Server 之前的架构中，章节正文抓取后直接应用用户自定义替换规则（`ReplaceRule`）清洗，并将清洗后的单份文本写入 SQLite `book_content_cache.content`。
导致两个核心痛点：
1. **规则误杀无法恢复**：一旦规则写错导致正文被清空或关键文字被误替换，缓存即被污染，只能全量清空缓存并耗费流量和时间重新联网下载；
2. **存量缓存无法应用新规则**：新增或导入错字/净化规则后，已缓存的章节无法享受新规则，造成阅读体验割裂。

## 2. 红蓝对抗审查 (Skeptic Audit)

### 2.1 方案选型辩论：独立 Raw 表 vs 单表双列
- **Skeptic 质疑**：为什么不新建一张 `book_content_raw` 表，将原文和清洗后文本彻底解耦？
- **Defender 释疑**：
  1. 新建表需要维护两套联合主键 `(source_id, book_url, chapter_url)` 和索引，在缓存删除、书籍移除、单章更新时需要双表事务与双倍 SQL 开销（违反复杂度惩罚原则）；
  2. 采用单表双列扩展（`raw_title text, raw_content text`），既利用现有索引保证原子性，又无需多表 JOIN。

### 2.2 性能与秒开辩论：读时动态清洗 vs 存储双副本静态直出
- **Skeptic 质疑**：如果只存 `raw_content`，每次用户打开章节或 TTS 朗读时在内存中动态跑 `ContentProcessor.processContent()` 岂不是更省磁盘？
- **Defender 释疑**：
  1. 高速翻页、流式搜索与并发 TTS 朗读是高频操作。如果每次请求都遍历多条复杂正则与 Rhino JS 沙箱，会导致服务端 CPU 持续高负荷，并显著增加响应延迟，破坏“秒开阅读”的核心原则；
  2. 现代磁盘对纯文本占用极度宽松，存双副本是以极低存储换取极致读取性能的最佳实践。

### 2.3 边界与旧数据兼容性 (Backward Compatibility)
- **Skeptic 质疑**：升级后已有历史旧缓存的 `raw_content` 为 `NULL`，点击重洗会不会把正文洗成空串？
- **Defender 释疑**：
  - 查询与重洗均使用 `COALESCE(raw_title, title)` 与 `COALESCE(raw_content, content)`。若旧数据 raw 字段为 NULL，以现有 content 兜底作为清洗输入，绝不出现空值覆盖。

## 3. 最终落地的正确解法 (Final Architecture)

1. **数据库结构**：
   - 为 `book_content_cache` 新增 `raw_title TEXT` 与 `raw_content TEXT` 列；
   - 启动时通过 `migrateBookContentCache` 执行幂等 `ALTER TABLE` 迁移。
2. **写路径双副本持久化**：
   - `RuleRunner.content()` 提取完书源基础正文后，记录 `rawTitle` 与 `rawContent`，再运行用户规则清洗，得到 `cleanedTitle` 与 `cleanedText`，一起返回并落库。
3. **批量重洗流水线**：
   - `Database.recleanBookCache(sourceId, bookUrl, jsSandbox)`：批量拉取书籍全部章节的 raw 副本，匹配该书生效的规则链重新执行 `ContentProcessor`，并通过 JDBC 批处理更新 `content`/`title`。
4. **前端交互与操作入口**：
   - 书架书籍卡片「三个点」管理菜单中提供「重新应用净化规则」按钮，提供即时状态与完成章节反馈；
   - 书架批量操作栏中提供「重洗规则」按钮，支持多本书籍一键批量重洗。

## 4. 沉淀的部落知识 (Tribal Knowledge)

- **[正文缓存/规则] 双副本静态直出与幂等重洗**：正文缓存表 `book_content_cache` 必须同时维护 `raw_content`（书源基础原文）与 `content`（当前生效清洗副本）。前端与 TTS 必须直接读取 `content` 保障零运算秒开；当用户调整规则时，重洗逻辑必须以 `COALESCE(raw_content, content)` 为基准重新运行规则链并覆盖回写 `content`，确保规则误删后可瞬间无损还原。
