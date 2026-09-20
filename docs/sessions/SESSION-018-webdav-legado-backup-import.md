---
id: SESSION-018
title: 「文件」页支持导入 Legado 备份包（书源/替换规则/书架/阅读进度）
date: 2026-09-20
author: Agent
tags: [webdav, backup, import, bookshelf, progress, replace-rules, source]
---

# SESSION-018: 「文件」页支持导入 Legado 备份包（书源/替换规则/书架/阅读进度）

## 1. 现象与需求背景 (Background & Problem)
- **需求痛点**：用户在手机端 Legado App 长期积累的阅读数据（书源、替换净化规则、书架藏书、阅读进度）通常以 `backup-*.zip` 备份包形式导出。在此之前，服务端缺乏整包搬迁能力，用户若想迁移数据，只能手工解包后分别在不同页面逐项导入，繁琐且极易丢失阅读进度。
- **改动目标**：在内置 WebDAV「文件」管理页中，为 `.zip` 备份包提供一键「导入」入口，自动提取并落库书源、替换净化规则、书架书籍与阅读进度，并自动防御恶意构造的 Zip 炸弹与路径穿越。

---

## 2. 核心架构与关键实现 (Key Implementation)

### 2.1 纯内存流式解析与 Zip 安全防线
- **规避 Zip-Slip**：使用 `java.util.zip.ZipFile` 按固定条目名检索并直接以流读取到内存，绝不解压落盘到本地文件系统，从根源上杜绝文件穿越漏洞；
- **防御 Zip 炸弹**：严格检查包内条目未压缩声明体积——单条目不得超过 64 MiB，整包累计未压缩体积不得超过 256 MiB；
- **条目智能过滤**：仅识别核心条目 `bookSource.json`、`replaceRule.json`、`bookshelf.json`（不区分大小写并支持子目录前缀），静默忽略 RSS、TTS、书签、主题等无对应能力的条目；三者皆缺时友好报错拦截。

### 2.2 书源主键与书架 Origin 归一化对齐
- Legado 备份包中 `bookshelf.json` 内的 `origin` 字段通常带有 `#` 或 `##` 注解（如 `https://example.com/##聚合`），而服务端书源主键在导入时经 `SourceCodec.parse` 剥离注解已归一化为基准 URL。
- 服务端在解析书架条目时，调用 `SourceCodec.normalizeSourceId(origin)` 统一去注解，保证导入后的书籍精确挂载在对应的有效书源下，避免书架书籍落入不存在的书源。

### 2.3 阅读进度智能防回退与幂等合并
- **防回退守卫**：阅读进度仅在备份时间晚于或等于库中既有进度时覆盖（`where excluded.updated_at >= reading_progress.updated_at`），防止用户意外导入历史旧备份时将当前较新的阅读进度回退；
- **章节字符偏移解耦**：Legado App 的 `durChapterPos` 是字符绝对偏移量，与服务端 0~1 的小数滚动进度 `scroll_position` 语义不同，因此不强行赋值小数滚动位置，仅落库章节序号与更新时间戳；
- **书架与替换规则 Upsert**：书架以 `(source_id, book_url)` 复合主键合并，同名替换规则按 ID 幂等覆盖。

### 2.4 鉴权双轨与前端交互集成
- **鉴权模型**：`POST /api/webdav/import` 归入页面写操作。外部 WebDAV 客户端走 HTTP Basic 直接放行；Web 设置页走会话鉴权，必须校验 `X-CSRF-Token`；
- **Web 前端**：在「文件」管理页对 `.zip` 文件显示「导入」操作按钮（显式 `type="button"`），点击后二次确认，调用后反馈导入/更新数量 Toast 提示。

---

## 3. 红蓝对抗审查 (Skeptic vs Defender)

| 审查维度 | 挑刺点 (Skeptic) | 释疑与防御 (Defender) |
| :--- | :--- | :--- |
| **路径安全** | `request.path` 是否可能穿越出 WebDAV 根目录？ | 由 `WebDavStorage.resolve()` 全面拦截 `..`、`\`、`\0` 并校验 `startsWith(root)`。 |
| **内存与解压** | 损坏或特制超大 zip 是否会导致服务端 OOM？ | `ZipFile` 先校验条目总大小 $\le 256\text{MB}$ 与单条目 $\le 64\text{MB}$，超限直接抛出异常拒绝。 |
| **进度覆盖** | 仅 `chapterIndex > 0` 才落库进度是否会漏掉第一章？ | Legado 书架默认初始为 0，若对 0 也写入进度会导致书架所有未读书籍均产生进度记录；阅读器对无进度书籍默认从第 0 章开启，逻辑平滑一致。 |
| **书源关联** | 备份中书架的 `origin` 与书源 ID 不一致怎么办？ | 提取前通过 `SourceCodec.normalizeSourceId()` 统一剥离 `##` 注解，精确与书源 ID 对齐。 |
| **复杂度假说** | 是否引入了冗余设计？ | 全程仅新增 `BackupImporter`（117 行）与数据库批量合并方法，复用既有 JSON 解析管道，无任何多余依赖。 |

---

## 4. 沉淀的教训与部落知识 (Lessons Learned)
- **[WebDAV/备份导入] Zip 纯内存流式按需读取与 Zip 炸弹防护**：导入备份包时严禁解压落盘，必须使用 `ZipFile` 按需匹配目标文件名流式读取入内存，并设定单条目（64MB）与整包（256MB）解压体积上限，同时彻底规避 Zip-Slip 与 Zip 炸弹。
- **[数据迁移/书架] 书架 `origin` 必须经 `normalizeSourceId` 归一化**：Legado App 的书架记录常携带带有 `##注解` 的书源 URL，落库前必须调用 `SourceCodec.normalizeSourceId` 剥离注解以与 `book_source` 主键对齐，杜绝书籍悬空。
- **[数据迁移/进度] 跨端阅读进度写入必须带 `excluded.updated_at >= reading_progress.updated_at` 防回退守卫**：导入备份进度时严禁无条件覆盖，必须以更新时间戳为守卫防止旧备份冲掉新进度；同时 Android 的字符偏移量 `durChapterPos` 不得强塞入服务端的百分比 `scroll_position`。
