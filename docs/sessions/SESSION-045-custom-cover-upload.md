---
id: SESSION-045
title: 书籍编辑弹窗支持自定义本地封面上传与图片魔数强校验
date: 2026-10-04
author: Agent
tags: [bookshelf, cover, upload, multipart, validation, security, issue-38]
branch: feat/issue-38-custom-cover-upload
worktree: ../legado-issue-38
---

# SESSION-045: 书籍编辑弹窗支持自定义本地封面上传与图片魔数强校验 (Issue #38)

## 1. 现象与需求背景 (Background & Problem Statement)
- **需求背景**：Issue #38 (by @wfanan) 提出，书架中部分书籍没有在线封面，或第三方书源失效/抓取不到封面。
- **用户期望**：在「编辑书籍信息」弹窗 (`BookInfoEditModal`) 的封面区域提供上传按钮，仅允许常见合法图片格式，上传后可作为书籍封面持久化。

## 2. 审查与风险挑刺 (Skeptic Audit)
- **风险 1：文件上传伪造与任意文件上传漏洞 (Arbitrary File Upload)**
  - 不能轻信前端传来的扩展名或请求头 `Content-Type: image/jpeg`。如果上传恶意脚本或 HTML，可能引发存储型 XSS 或资源投毒。
  - **解法**：在 `CoverCache` 中建立 `detectImageContentType(bytes)`，通过前导 12 字节魔数进行白名单校验（仅放行 JPEG、PNG、GIF、WebP、BMP）。不符合图片特征的字节拒绝落盘，直接返回 HTTP 400 `invalid_cover`。
- **风险 2：拒绝服务与体积膨胀 (DoS / Size Limit)**
  - 严禁无限制接收大体积文件。
  - **解法**：上传端点显式设防：单张封面限制 ≤ 5MB；前端在文件选择阶段前置阻断 > 5MB 的文件。
- **风险 3：数据库状态与孤立封面 (Cover Key & State Sync)**
  - 过去封面落盘主要依靠 `coverCache.cache(url)`。外部文件上传需要同时在 `cover_cache` 表记录 `(cache_key, content_type)`，否则 `/api/covers/{key}` 获取时会缺失准确的 `Content-Type` 并回退为二进制流。
  - **解法**：在 `Database` 中抽象 `recordCoverCache(coverKey, contentType)`，在上传成功后立即同步写库。同时 `updateBookshelfInfo` 支持传入 `coverKey`，在「清除封面」时（显式空串）统一将 `cover_key` 与 `cover_url` 置空。
- **风险 4：复杂度惩罚检查 (Complexity Penalty)**
  - 严惩为了一个上传功能引入复杂的独立附件管理表或额外 OSS 抽象层。
  - 充分复用既有的 SHA-256 内容寻址哈希体系与 `/api/covers/{key}` 静态直出路由。

## 3. 最终落地的方案 (Final Solution)
1. **服务端 (`server/`)**：
   - `CoverCache.kt`：导出 `detectImageContentType` 与 `looksLikeImage`，魔数推导 `image/jpeg`、`image/png`、`image/gif`、`image/bmp`、`image/webp`。
   - `Database.kt`：增加 `recordCoverCache`；`updateBookshelfInfo` 增加 `coverKey` 支持。
   - `Models.kt`：`BookshelfInfoUpdateRequest` 补充 `coverKey` 字段，新增 `CoverUploadResponse` 强类型 DTO。
   - `Routes.kt`：新增 `POST /api/covers/upload` 端点，支持会话鉴权与 `X-CSRF-Token` 校验，返回 `{ coverKey, contentType, url }`。
2. **前端与 i18n (`web/`)**：
   - `api.ts`：导出 `uploadCover(file: File): Promise<CoverUploadResponse>`，`updateBookshelfInfo` 支持可选 `coverKey`。
   - `main.tsx` + `styles.css`：在 `BookInfoEditModal` 封面区域新增「上传本地封面」次级按钮 (`.secondary-button.upload-cover-btn`)，文件选择器严格限制格式，支持即时预览与「清除/恢复」回退。
   - 补齐四语国际化 (`zh-CN`, `zh-TW`, `en-US`, `ja-JP`)。
3. **验证闭环**：
   - `CoverUploadRoutesTest.kt`：端到端验证合法图片上传、落盘、更新书架并获取图片；验证非图片文件返回 400 拦截。
   - `cover-upload.test.ts`：静态结构与 API 契约防线单测。

## 4. 沉淀的教训与部落知识 (Tribal Knowledge)
- **[封面管理/安全] 用户自定义上传封面必须经魔数强校验并有 5MB 上限**：不能仅靠文件后缀名或 multipart 的 `content-type` 判定，必须通过字节魔数校验（JPEG/PNG/GIF/WebP/BMP）。落盘后必须在 `cover_cache` 表记录真实 MIME 类型，避免直出响应头丢失。
