---
id: SESSION-017
title: 本地图书（TXT/EPUB）导入解析、虚拟书源与书架无缝集成
date: 2026-09-19
author: Agent
tags: [local-book, txt-parser, epub-parser, encoding-detection, bookshelf, svg-cover]
---

# SESSION-017: 本地图书（TXT/EPUB）导入解析、虚拟书源与书架无缝集成

## 1. 业务背景与问题痛点 (Investigation & Analysis)

- **痛点分析**：
  1. 服务端原有架构完全建立在远程网络书源之上，当书源失效或用户需要阅读私有精排小说、同人合集、本地文献（TXT / EPUB 格式）时无法导入与阅读。
  2. 中文 TXT 电子书编码多样（UTF-8, UTF-8 BOM, GBK / GB2312 / GB18030, UTF-16），缺乏智能探测容易导致大面积乱码；且 TXT 无结构化目录，需通过中文小说命名规律智能正则拆分。
  3. EPUB 为多层 ZIP 压缩包，内含 XML 规范、OPF 清单、NCX/Nav 目录、XHTML 正文与内嵌封面，需在纯 JVM 环境下轻量解压并清洗为纯净正文。

## 2. 方案推演与红蓝对抗 (Adversarial Review & Trade-offs)

### 2.1 存储与读取模式抉择
- **Skeptic 挑刺**：若采用“按需读取原文件”，每次切章和朗读都要动态解压原文件，且必须为本地书单独维护一套完全不同的正文/目录路由，破坏现有 API 的整洁性。
- **Defender 释疑**：采用**解析入库一体化**。上传时一次性完成元数据抽取、分章与正文入库（写入 `book_shelf`, `book_toc_cache`, `book_content_cache`），分配标准虚拟书源 ID `loc_book`。现有阅读器目录虚拟化、换源、净化规则、滚动/双栏排版、离线缓存与 Edge-TTS 连续流式听书无需做破坏性修改即可 100% 无缝复用。

### 2.2 极端边界与容错防御
- **TXT 编码兼容**：启发式多级探测（BOM -> UTF-8 严格解码 -> GB18030 严格解码 -> UTF-8 容错解码），杜绝中文乱码。
- **TXT 无章节降级**：若散文/短篇无任何 `第X章` 标题匹配，自动在自然段落换行处按 4000 字区间切分（“第 1 节”、“第 2 节”），保证 100% 可读。
- **EPUB 结构容错**：优先解析 NCX 目录，次级解析 EPUB 3 Nav 导航，末级兜底按 Spine 顺序枚举 XHTML，正文通过 Jsoup 剥离脚本样式并保留换行排版。
- **自适应艺术封面**：EPUB 提取原装高清封面；TXT 或无封面书籍自动生成精致纯 SVG 艺术字封面写入 `CoverCache`。
- **删除联动清理**：移出书架时，SQLite 数据库记录（书架、目录、正文、阅读进度、缓存状态、孤立封面）与磁盘原始文件同步清理。

## 3. 落地实施细节 (Implementation)

1. **解析引擎 (`LocalBookParser.kt`)**：
   - 纯 JVM `ZipInputStream` + `Jsoup`，零外部新增依赖。
   - 实现 `parseTxt`、`parseEpub` 与 `generateSvgCover`。
2. **数据库扩展 (`Database.kt`)**：
   - 新增 `importLocalBook` 批量事务写入；
   - 更新 `removeBookshelf` 与 `switchBookshelf` 清理 `book_toc_cache`。
3. **接口端点 (`Routes.kt`)**：
   - 新增 `POST /api/bookshelf/import-local`（Multipart 多文件批量上传）；
   - `/books/details`、`/books/chapters`、`/books/content` 全面适配 `loc_book` 虚拟书源。
4. **前端交互 (`main.tsx` & `api.ts` & `styles.css`)**：
   - 书架新增「导入本地」按钮与全屏拖拽上传；
   - 本地图书卡片打上专属「本地」徽标；
   - 管理抽屉适配本地图书（隐藏网络换源，支持编辑信息与移出书架）。

## 4. 部落知识库与避坑经验沉淀 (Lessons Learned)

- **[本地书籍/虚拟书源] `loc_book` 虚拟书源与解析入库一体化**：本地上传的电子书（TXT/EPUB）使用标准 `sourceId = "loc_book"` 与 `local://<book_id>` 路径，解析后目录与正文直接写入 `book_toc_cache` 与 `book_content_cache`，使阅读器与 Edge-TTS 能够无差别透明调用，杜绝针对本地书籍另起炉灶建立平行阅读接口。
- **[TXT/编码与分章] 启发式编码探测与字数分块降级**：TXT 编码检测必须按 BOM -> UTF-8 Strict -> GB18030 Strict 梯度回退；分章正则匹配失败时必须配备固定字数自然换行降级分块，防止无目录短文导入崩溃。
- **[EPUB/纯 JVM] 轻量 ZipInputStream 结合 Jsoup 处理 EPUB 2/3**：无需引入庞大的第三方重型电子书库，利用标准库 `ZipInputStream` 配合 Jsoup 即可完整提取 OPF 元数据、NCX/Nav 目录与 XHTML 文本。
