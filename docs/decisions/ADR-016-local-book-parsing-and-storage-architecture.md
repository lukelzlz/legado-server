---
id: ADR-016
title: 本地图书（TXT/EPUB）解析引擎、虚拟书源与解析入库一体化架构
status: accepted # proposed | accepted | superseded | deprecated
date: 2026-09-19
---

# ADR-016: 本地图书（TXT/EPUB）解析引擎、虚拟书源与解析入库一体化架构

## 1. 决策背景 (Context)

Legado Server 需要扩展对本地电子书（TXT、EPUB）的导入与阅读支持。在设计本地图书架构时，面临以下核心技术选型与权衡：
1. **书源与标识体系**：本地书籍是作为特殊书源还是独立体系？
2. **正文存储与服务模式**：是导入时一次性切分入库（解析入库一体化），还是只存原文件并在请求章节时动态寻址解压（按需解析）？
3. **TXT 编码与分章算法**：如何在不引入过重第三方依赖的前提下，实现高精度的编码检测与章节拆分？
4. **EPUB 解析依赖**：如何在遵循纯 JVM 原则的前提下，轻量且健壮地解析 EPUB 2/3 标准？

---

## 2. 裁定方案 (Decision)

### 2.1 虚拟书源机制 (`sourceId = "loc_book"`)
- 统一定义本地虚拟书源标识常量 `LOC_BOOK_SOURCE_ID = "loc_book"`，与 Legado Android 客户端生态标准保持一致。
- 本地书籍唯一标识采用 `local://<book_uuid>` 格式，`tocUrl = "local://<book_uuid>/toc"`，`chapterUrl = "local://<book_uuid>/chapter_<index>"`。
- 在 `book_shelf` 中，本地书籍同样以此三元组作为主键（`source_id`, `book_url`）。

### 2.2 解析入库一体化 (Parse-and-Ingest Pipeline)
- **上传阶段**：用户上传文件后，服务端在后台线程将原始文件安全持久化到 `LEGADO_DATA_DIR/local_books/<book_uuid>.<ext>`。
- **解析切片**：解析器提取书籍元数据（书名、作者、封面、简介）和全量章节目录与正文。
- **落库管道**：
  1. 封面图写入 `CoverCache`（EPUB 提取原图，TXT 生成 SVG/PNG 艺术封面）。
  2. 目录数据序列化写入 `book_toc_cache`。
  3. 各章节正文批量写入 `book_content_cache`。
  4. 书籍信息插入 `book_shelf`，状态初始置为 `cacheState = 'ready'`，`totalChapters = N`, `cachedChapters = N`。
- **收益**：前端所有现有路由（`/api/books/chapters`, `/api/books/content`, `/api/bookshelf/cached-chapters`）和阅读器组件（翻页、滚动、虚拟化、TTS、离线 Service Worker）**无需任何条件分支改动**即可无缝支持本地书籍。

### 2.3 TXT 智能探测与分章引擎
- **编码检测**：采用启发式多编码校验算法：
  1. 优先校验 UTF-8 BOM (`EF BB BF`) 与 UTF-16 BOM (`FE FF` / `FF FE`)；
  2. 尝试 UTF-8 严格解码（检验有效 UTF-8 字节序列）；
  3. 若 UTF-8 校验失败或乱码率过高，降级尝试 `GB18030`（向下兼容 GBK / GB2312）解码。
- **分章正则体系**：
  - 核心正则表达式集合：
    - `(?:\r?\n)(?:第[0-9一二三四五六七八九十百千万]+[章回节卷集幕计篇部]|Chapter\s+[0-9]+|引子|序言|楔子|尾声|后记|番外|终章)[^\r\n]{0,35}`
  - 首章前内容自动归纳为「前言/引子」或简介。
  - **保底策略**：若整本 TXT 无任何匹配章节，按 5000 字区间在自然换行处切分，生成「第 X 节」，确保 100% 可读。

### 2.4 纯 JVM EPUB 轻量解压与解析
- 使用 `java.util.zip.ZipFile` 读取 EPUB 压缩包，零外部重型依赖。
- 解析 `META-INF/container.xml` 定位 `.opf` 根文件。
- 解析 OPF 清单（`manifest`）与阅读顺序（`spine`）：
  - 抽取 `<dc:title>`、`<dc:creator>`、`<dc:description>`；
  - 寻找 `properties="cover-image"` 或 `id="cover"` 的图片项，提取作为封面；
  - 解析 `toc.ncx` 或 `nav.xhtml` 提取结构化章节标题与 `href`；
  - 使用轻量 HTML 文本清洗器（保留段落换行 `<p>`, `<br>`）提取纯净正文。

---

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：按需实时解析（不缓存正文，每次读章节去读取/解压原文件）**
  - *否决理由*：每次切章、离线缓存或 TTS 朗读都需要定位并解压原文件，性能开销大；且导致本地书籍需要一套完全独立的读取接口和逻辑，破坏了现有 `/api/books/content` 的统一设计。
- **备选方案 B：引入 Apache Tika 或重型 EPUB 库 (EpubLib)**
  - *否决理由*：第三方重型库体积庞大（数 MB 到数十 MB），包含大量 Android/Java AWT 不兼容组件，违反「工程整洁度与复杂度惩罚」原则。使用纯 JVM `ZipFile` + Jsoup 已完全足以高保真解析 EPUB 2/3。

---

## 4. 后果与权衡 (Consequences & Trade-offs)

- **正面收益**：
  - 极高的阅读一致性：本地书籍与网络书源书籍在阅读器内体验完全一致，支持进度同步、净化规则应用和 TTS 流式朗读。
  - 零破坏性变更：现有 API 与前端交互流程 100% 保持向后兼容。
  - 极低的运维复杂度：随 Docker 数据卷 `LEGADO_DATA_DIR` 一起持久化，备份迁移简单。
- **负面代价**：
  - 超大 TXT（如 50MB 以上巨作）在上传解析入库时需消耗几百毫秒 CPU 与 SQLite 批量写入时间，需要在上传接口中采用协程与批量事务优化。
