---
id: SESSION-048
title: 审查与加固 PR #46：漫画全链路（方案 E）与有声书全链路（方案 G2）端到端落地
date: 2026-10-16
author: Agent
tags: [manga, audiobook, cache, range, media, pr46, worktree]
branch: pr46
worktree: ../joyful-galileo-pr46
---

# SESSION-048：审查与加固 PR #46：漫画全链路（方案 E）与有声书全链路（方案 G2）端到端落地

## 1. 背景与审查目标
PR #46 是本项目自立项以来规模最大、功能覆盖最广的核心功能合并，一次性补齐了 **Legado 漫画（图片书源）** 与 **有声书（音频书源）** 的完整端到端支持，包含完整的 PRD 提案、ADR 架构决策、前端全功能阅读/播放组件、服务端多媒体寻址缓存、GC 驱逐模型、HTTP Range 直出及离线 Service Worker 缓存。

根据老冯工程原则与 `doc-review` SOP，通过独立 Git Worktree（`../joyful-galileo-pr46`）挂载并实施全面红蓝对抗审查（Skeptic Audit）、极限边界条件推演与全套自动化测试检验。

---

## 2. 核心架构与关键实现对抗审查

### 2.1 漫画链路（方案 E：内嵌内容寻址缓存体系）
- **书籍类型与规则解析**：
  - `RuleRunner.mangaImages()` 兼容纯文本换行、JSON 数组、HTML `<img>` / `<source>`，只下发内容哈希 key，绝不下发上游真实图片直链，杜绝防盗链拦截与隐私泄漏。
  - 书籍类型支持依据书源 `bookSourceType == 2` 或书籍元数据声明识别。
- **内容寻址图片缓存与安全校验 (`ImageBytes` / `ImageCache`)**：
  - 采用 `sha256(imageBytes)` 作为存储主键；
  - 强制进行二进制魔数强校验（JPEG, PNG, GIF, WebP, BMP），彻底拒绝任何包装成图片的上游 HTML 错误页或 JSON；
  - 弱网与防盗链自动透传 `Referer` / `User-Agent`。
- **滑窗 GC 驱逐算法 (`ImageCacheGc`)**：
  - 以读者当前正在阅读的章节为锚点，往前保留 5 章，往后前瞻保留 15 章；
  - 落后进度超过 5 章且完成度 > 80% 的已读历史章节第一顺位淘汰；
  - 用户整本缓存标记（Pinned）享免死金牌，杜绝离线章节被后台误删。
- **漫画阅读器沉浸式体验 (`MangaReaderScreen`)**：
  - 利用清单给出的原始宽高以 `aspect-ratio` 占位，彻底消除首屏流式下坠和滚动跳动；
  - 进度语义严格按“图片序号”而非“像素高度偏移”；
  - 单张图片失败提供可视占位与一键重试能力。

### 2.2 有声书链路（方案 G2：内嵌 URL 寻址缓存与 Range 直出）
- **音频流式落盘与防 OOM 防线 (`AudioFetcher` / `AudioBytes`)**：
  - 音频属于 10~60 MiB 级的大型媒体资源，严禁 `readAllBytes()` 进内存（并发极易引发 OOM）。全部采用流式管道写至 `.part` 临时文件，校验格式后执行 `ATOMIC_MOVE` 原子落盘；
  - 弱魔数校验（排除 HTML/JSON 错误页）+ 直出白名单（MP3, M4A, AAC, OGG, WEBM, WAV, FLAC）；
  - 全局并发限制 `Semaphore(3)` 与单域名并发限制 1，防止被上游音频站封禁。
- **单段 Range 响应唯一收口 (`ByteRangeServing.kt`)**：
  - 严格遵守 RFC 7233，将 WebDAV 与有声书的 HTTP 206 Partial Content 单段 Range 直出收敛为单一共用实现；
  - 416 严格回传 `Content-Range: bytes */<size>`；
  - 针对带有 `If-None-Match` 的静态资源优先比对 ETag，命中 304 时直接短路返回，零磁盘 IO。
- **进度与播放状态管理 (`AudiobookPlayerScreen`)**：
  - 毫秒级阅读进度单独落库 `reading_progress.position_ms`，严禁放宽既有文本小说进度 `0.0..1.0` 的数学校验，写入采用 4000ms 节流；
  - 封面高斯模糊底衬采用独立图层的 `filter: blur()`，彻底规避 `backdrop-filter` 破坏包含块与掉帧的陷阱；
  - 接入系统控制中心 MediaSession，支持后台播放、锁屏控制及 70% 进度自动预取下一章。

### 2.3 备份导入解禁音频书籍
- `BackupImporter` 不再把音频书当作 `skippedAudio` 过滤丢弃，识别类型后写入 `book_kind = 'audio'`，使得手机端 Legado 备份的有声书可以无缝漫游到 Web 端。

---

## 3. 审查发现的问题与修复加固

1. **验收手册硬编码开发者本地路径**：
   - `docs/acceptance/ACCEPT-028-manga-p0-parse-through.md` 与 `docs/acceptance/ACCEPT-029-audiobook-p0-p3.md` 中残留了 `C:\Users\w1593\Desktop\...` 个人工作目录。
   - 违反 Long-Term Memory 中关于文档跨平台与防路径泄露的工程铁律。
   - **已修复**：统一替换为通用的相对目录操作命令，并将测试密码归一化为 `admin123`。
2. **构建副产物守卫**：
   - 验证构建触发的 `web/dist/sw.js` 仅为 revision 时间戳微调，检查确认无逻辑偏离。

---

## 4. 验证命令与结果

在隔离 Worktree（`../joyful-galileo-pr46`）中执行全部验证：

```bash
# 1. 前端类型检查
npm --prefix web run check
# 结果: tsc -b 退出码 0

# 2. 前端全套自动化测试
npx tsx web/test/run-all.ts
# 结果: pass 246, fail 0, 耗时 3.2s

# 3. 服务端全量单元测试
./gradlew :server:test
# 结果: 75 个测试套件，共 558 个测试用例，Failures: 0, Skipped: 2，BUILD SUCCESSFUL
```

所有测试 100% 通过，无破坏性退化，无架构复杂度膨胀。

---

## 5. 沉淀的教训与部落知识 (Tribal Knowledge)
- **[媒体缓存/流式落盘] 音频文件严禁一次性整读进内存，必须流式落盘并共用 Range 原语**：音频文件动辄数十兆，必须通过 Channel 流式写入 `.part` 临时文件再原子移动。音频播放器高频拖拽 seek 会拆解为大量密集的 Range 请求，Range 逻辑必须与 WebDAV 统一收口至 `respondFileRange`，绝不可重复造轮子。
- **[媒体播放/主题系统] 播放器封面模糊衬底采用独立图层 `filter: blur()`**：任何情况下避免用 `backdrop-filter` 作为播放器大面积背景模糊，否则既破坏子元素 `position: fixed` 的视口锚定，又会在播放帧刷新时导致浏览器重绘掉帧。
