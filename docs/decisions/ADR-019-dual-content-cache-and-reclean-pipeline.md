---
id: ADR-019
title: 采用 SQLite 单表双列存储原文副本与批量重洗流水线架构
status: accepted
date: 2026-09-25
---

# ADR-019: 采用 SQLite 单表双列存储原文副本与批量重洗流水线架构

## 1. 决策背景 (Context)

在 Legado-Server 中，书源抓取的章节正文需经过两层清洗：
1. 书源自带清洗（`cleanContent` 与 `ruleContent.replaceRegex`）；
2. 用户全局/书籍级自定义替换净化规则（`ReplaceRule` 链）。

过去设计中直接将清洗后的最终文本写入 `book_content_cache.content`，导致“洗坏无法还原”以及“新增规则无法应用于已有缓存”的顽疾。需要设计一种高效、低开销且兼容现有生态的双副本存储与重洗机制。

## 2. 裁定方案 (Decision)

1. **单表双列扩展模式**：
   - 在现有 `book_content_cache` 表中新增可空列 `raw_title TEXT` 和 `raw_content TEXT`。
   - `content` / `title` 保持为“当前生效的展示副本”，`raw_content` / `raw_title` 为“书源提取的基础原文副本”。
   - 启动时通过 `Database.init()` 动态执行幂等的 `ALTER TABLE book_content_cache ADD COLUMN ...`。
2. **读写分离与秒开保证**：
   - 读路径（`/api/content`、TTS 朗读、离线打包）：仍然执行 `SELECT title, content FROM book_content_cache`，前端和 TTS 零计算开销直出。
   - 写路径（`runner.content()` / `cacheBookContent`）：`RuleRunner` 返回结构体携带 `rawContent` / `rawTitle` 与清洗后的 `content` / `title`，落库时同步写入两套字段。
3. **批量重洗执行管道 (Re-clean Pipeline)**：
   - 封装 `Database.recleanBookCache(sourceId, bookUrl)`。
   - 在单一事务与批量 PreparedStatement 中，检索 `COALESCE(raw_content, content)`，结合该书籍作用域最新的启用法则重新调用 `ContentProcessor.processContent` / `processTitle`，更新 `content` 字段并刷新时间戳。
4. **前端入口收敛**：
   - 书架书籍卡片「三个点」管理菜单（`BookManageSheet`）中提供「重新应用净化规则」按钮，提供即时状态与成功反馈。
   - 书架批量操作栏支持多选一键批量重洗。

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：新建独立 `book_content_raw` 表**
  - *否决理由*：增加连表查询与额外的表维护、事务处理和外键同步复杂度（违反复杂度惩罚原则）。单表新增两列既保证原子更新，又完全复用现有的联合主键 `(source_id, book_url, chapter_url)` 与索引。
- **备选方案 B：数据库只存 raw_content，每次读取时在内存中实时计算清洗**
  - *否决理由*：大章节目录连续翻页、流式搜索与高并发 TTS 朗读时，每次请求都需遍历多条正则与 Rhino JS 沙箱，会导致服务端 CPU 飙升并显著增加响应延迟，破坏秒开体验。
- **备选方案 C：规则每次变更时后台全量自动静默重洗全库**
  - *否决理由*：当用户书架存有数十本书、数万章离线缓存时，修改一条规则会引发后台长时间高负载磁盘 IO 与 CPU 占用，影响正常阅读响应。按需由用户触发或针对单书触发是更可控、更安全的选择。

## 4. 后果与权衡 (Consequences & Trade-offs)

- **正面收益**：
  - 彻底解决规则误伤无法恢复的痛点，调试替换规则零心理负担。
  - 新增/导入规则后一秒完成全书重洗，无需重新耗费网络下载。
  - 读取路径保持零损耗秒开。
- **负面代价**：
  - 磁盘占用：已缓存章节的 SQLite 存储体积会增加约 1 倍（纯文本通常单章几 KB 至十几 KB，对现代服务器与本地磁盘影响极小）。
