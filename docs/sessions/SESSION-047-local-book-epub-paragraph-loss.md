---
id: SESSION-047
title: 本地书 EPUB 正文段落全部丢失（Jsoup 空白归一化 + 存量幂等回填）
date: 2026-10-08
author: Agent
tags: [local-book, epub, parser, jsoup, data-repair]
---

# SESSION-047: 本地书 EPUB 正文段落全部丢失

## 0. 现象与定位

用户导入本地 EPUB 后，阅读页**完全没有分段**：整章正文被压成一行。

先查数据、再读代码（本仓库既有方法论）：真实书架 6 本 EPUB 在 `book_content_cache`
里的**换行总数全部为 0**，而同一架上的 TXT 有 91083 个换行 ⇒ 问题被锁定在 **EPUB 解析路径**，
与字体/排版/CSS 无关。

| 书名 | 章节数 | 入库换行数（修复前） |
| :--- | ---: | ---: |
| 极品家丁之玉德仙坊 单黄毛修改版 1-52章 | 56 | 0 |
| 极品家丁之玉德仙坊（原版1_4_新篇…） | 72 | 0 |
| soushu2025_com@极品家丁之玉德仙坊_二改版 | 304 | 0 |
| 娱乐春秋（番外加料） | 776 | 0 |
| 放开那个女巫 | 1504 | 0 |
| 黎明之剑 | 1605 | 0 |
| 放开那个女巫 八改豪华魔改修改整合版（TXT） | 1518 | 91083 |

对照原始 XHTML 可确认段落本来就是齐的（例如《黎明之剑》某个正文文件里有 359 个 `<p>`）。

## 1. 根因

`LocalBookParser.extractHtmlContent()` 与 `extractHtmlTitleAndContent()`（spine 兜底路径）都是：

```kotlin
doc.select("p, div, br, h1, h2, h3, h4, h5, h6, tr, li, blockquote").prepend("\n\n")
val text = doc.body()?.text() ?: doc.text()
```

`prepend` 插入的是**文本节点**，而 Jsoup 的 `Element.text()` 会对每个文本节点做空白归一化
（`normaliseWhitespace`，`\n`/`\t` → 空格）⇒ 插进去的换行**一个都活不下来**，
整章"<p> 段落"被拼成一行。

TXT 路径不受影响：`parseTxt` 是按行切分后再 `joinToString`，换行本来就在。

## 2. 修复

块级元素边界改用**非空白占位符**（字面量 `\n`，两个字符）标记——它不参与空白归一化，
取完文本后再统一换回真换行；顺带把 `head`/`title` 一并移除，避免 EPUB 的 `<title>`
混进正文：

```kotlin
private fun htmlToText(doc: Document): String {
    doc.select("script, style, link, meta, head, title").remove()
    doc.select("br").append("\\n")
    doc.select("p, div, h1, h2, h3, h4, h5, h6, tr, li, blockquote, section, article").prepend("\\n")
    val raw = doc.body()?.text() ?: doc.text()
    return cleanText(raw.replace("\\n", "\n"))
}
```

章节切分走 NCX/nav，与本次改动无关 ⇒ **章节数完全不变**（真机 6 本逐本核对一致）。

## 3. 老数据不会自动变好：提供幂等回填入口

本地书的目录与正文是在**导入那一刻**解析落库的（`book_toc_cache` / `book_content_cache`），
因此解析器修好 ≠ 老书修好：段落边界在导入时已丢，**替换净化规则也救不回来**。

按仓库既有约定（*「修复『新数据不再出错』≠『老数据自动好了』」——为历史坏数据提供幂等的一次性修复入口*），
本次一并提供 `POST /api/bookshelf/reparse-local`：

- 用 `local_books/<bookId>.<ext>` 里留存的**原始文件**重新解析 ⇒ 用户不需要重新上传
- 只重写目录 / 正文 / 缓存状态，**绝不触碰 `book_shelf`**：书名、作者、分组、
  封面（含用户手工改过的）全部保留
- `local://<bookId>` 不变 ⇒ **阅读进度与书签自动延续**
- 幂等（先清后写、章节 URL 由序号推导），可传 `sourceId`/`bookUrl` 只修一本，默认整批
- 书架管理弹窗新增「重新解析本地文件」按钮（仅 `loc_book` 显示），与既有「重新应用净化规则」同构

**为什么不做成启动时自动迁移**：解析要把整本书读进内存（大 EPUB 解压后上百 MB），
全架自动重解析会在启动时制造一次不可控的资源尖峰（参见 SESSION-025 的启动饱和教训）。
交给用户显式触发，返回逐本明细（`reparsed`/`skipped`/`failed` + 原因），失败静默降级到单本而不是整批中断。

## 4. 验证

- 单测 `LocalBookParserTest`：
  - `parseEpub keeps block level paragraph breaks`：相邻 `<p>`、嵌套 `<div>`、`<br>` 都必须断行；
    `head` 里的 `<title>` 不得进入正文
  - `replaceLocalBookCache rewrites caches but keeps shelf metadata and progress`：
    回填后正文与目录更新、章节数变少时旧章节行被清掉、书名/作者/分组/进度不变、
    重复调用结果一致（幂等）、不在书架中的 `bookId` 被拒绝（不造孤儿缓存）
- 真机回归（docker 实例 + 真实书架，走 `POST /api/bookshelf/reparse-local`）：
  章节数 6 本全部不变；换行数 `0 → 21742 / 52344 / 98468 / 88960 / 136746 / 173236`；
  `reading_progress` 3 条 **0 失效**；抽查《黎明之剑》正文 3423 字 / 55 段
- 前端：`npm --prefix web run check` **0 错误**；`npx tsx web/test/run-all.ts` **209/209**（+1 新用例：
  `LocalBookImport - reparseLocalBooks posts to the one-off repair endpoint`，锁定端点路径/请求体/空参形态）
- 服务端：`./gradlew :server:test` 与**干净基线逐条比对**——
  基线 `440 用例 / 0 失败 / 2 跳过`，本分支 `442 用例 / 0 失败 / 2 跳过`（+2 新用例，失败集合完全相同 ⇒ 零回归）

## 5. 沉淀的教训

1. **在 Jsoup 里「用换行标记块级边界」必须用非空白占位符**：`Element.text()` 会归一化空白，
   任何插进去的 `\n` 都活不下来；这类"看起来对、跑起来静默丢格式"的写法要往
   `append("\\n")` + `replace` 或 `NodeTraversor` 上靠。
2. **解析结果一旦落库，解析器就不再是唯一真相**：修解析逻辑时必须同时回答
   「已经入库的数据怎么办」，否则用户看到的是"升级了但没变化"。
3. **回填只应重写「由原文件推导出来的部分」**：书架元数据、分组、封面、阅读进度都是
   用户/服务端的资产，重解析时一行都不能碰（`bookUrl` 保持不变是最省力的保证方式）。
