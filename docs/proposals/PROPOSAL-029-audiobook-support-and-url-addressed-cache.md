# PROPOSAL-029：有声书（音频书源）端到端支持与内嵌 URL 寻址缓存直出（方案 G2）

> 状态：`Tested`（P0–P3 主干已实现并本地验证通过；尚未推送）
> 关联：[`ADR-029`](../decisions/ADR-029-audiobook-url-addressed-cache-and-range-streaming.md) ·
> [`ACCEPT-029`](../acceptance/ACCEPT-029-audiobook-p0-p3.md)
> 上游来源：PR #46 的 `PROPOSAL-023-audiobook-support-and-cache-playback.md`
> 与 `ADR-023-audiobook-embedded-cache-and-range-streaming.md`（作者 lukelzlz）。
> **本方案把那份规格落成可施工图并实施**，同时解决它没写清/会返工的 4 个硬问题（见 §4）。

---

## 1. 目标

服务端把「有声书（音频书源）」做成端到端可用：

**解析章节音频 → 单进程缓存字节 → `Range` 直出 → 前端播放器可播、可离线、进度精确到毫秒。**

## 2. Non-Goals（硬性）

- ❌ 多进程 `MediaVault` 子服务
- ❌ 接进 TTS 会话链路（`/api/tts/*`）—— 完全隔离（静态资源 vs 有状态会话）
- ❌ 引 ffmpeg / 任何转码或解析库
- ❌ HLS `.m3u8` 重写与 AES-128 解密
- ❌ 全局常驻 Mini 播放条（会污染阅读/漫画用户）
- ❌ 首期做书签/倍速记忆的多端同步

## 3. 复用清单（✅ 既有 / 🔧 改造 / ➕ 新增）

| 能力 | 落点 | 怎么办 |
| :--- | :--- | :--- |
| 书源规则执行 | `RuleRunner.content()` | ✅ 复用 |
| 章节目录 | `RuleRunner.chapters()` + `book_toc_cache` | ✅ 复用 |
| 书源登录态/防盗链头 | `RuleRunner.mediaHeaders()` | ✅ 复用（音频与漫画共用同一份头语义） |
| 单段 Range 手写 | `ByteRangeServing.kt` | 🔧 **从 `WebDavServer` 原样提取**，WebDAV 与音频**共用一份** |
| 原子落盘（`.part` + `ATOMIC_MOVE`） | `AtomicFileStore` | ✅ 复用（KDoc 原本就写着"漫画与有声书共用"） |
| 全局限流 | `MangaImageFetcher` 的 `Semaphore` 模式 | ➕ `AudioFetcher`：全局 3 + **单 host 1** |
| SSRF 校验 | `NetworkSecurity.resolveAndValidateSafeHttpTarget()` | ✅ 复用 |
| 书籍类型 | `BookKind{NOVEL,COMIC,AUDIO}` | ✅ P0 已扩为三值 |
| 备份导入过滤 | `BackupImporter` | 🔧 **放开音频**（G0） |
| 进度存储 | `reading_progress` | 🔧 新增 `position_ms`（G-1） |
| 进度跨端镜像 | `BookProgressSync` | 🔧 音频把毫秒镜像成 `durChapterPos` |
| 播放器韧性守卫 | `HttpAudioTtsEngine` 的 `stalled`/`waiting`/`AbortError` | ✅ **只搬守卫，不搬会话** |
| 播放器外壳/抽屉/设置 | `ReaderScreen` / `MangaReaderScreen` 的既有 class | ✅ 复用 ⇒ 主题零额外适配 |

## 4. 规格里缺失 / 会返工的硬问题（本方案补上）

| # | 问题 | 处置 |
| :--- | :--- | :--- |
| **G-1** | 进度毫秒会被现有校验 **400** 拒（`scrollPosition ∈ 0.0..1.0`） | 新增 `reading_progress.position_ms`（可空）；`scroll_position` 语义**一个字都不动**。写入用 `coalesce(excluded, existing)` ⇒ 文本路径不会抹掉毫秒 |
| **G-2** | `cache_key` 是否内容寻址 | **URL 寻址**（`sha256(sourceId + '\n' + url)`）⇒ key **可预测**，前端在抓取前就能拼 `src`，天然绕开漫画当初"抓取前 key 未知"的坑；代价是放弃跨 URL 字节去重（音频场景收益近零） |
| **G-3** | 一章多分段如何对外 | **track 列表**（前端按 `segmentIndex` 串播）。服务端**不拼流、不转码** |
| **G-7** | `segment_index` / `headers_json` / `duration_ms` 三处缺口 | ① 补 `segment_index` 列（并**刻意不建唯一索引**：书源换地址时唯一索引会让 upsert 直接抛异常 ⇒ 整章打不开）；② 补 `headers_json`（每段专属头，否则重拉 403）；③ `duration_ms` 由前端 `loadedmetadata` 回传（服务端不引解析库） |
| **额外发现** | 音频**没有** L2 章节清单表 | 整章就绪度 = "这一章每段都 ready"，一条 `having` 即得（`Database.readyAudioChapters`）。多一张表就多一处要同步的状态 |

## 5. 数据模型

```sql
create table if not exists audio_cache (
  cache_key      text primary key,          -- sha256(source_id + '\n' + 规范化 origin_url)
  source_id      text not null,
  book_url       text not null,
  chapter_url    text not null,
  chapter_index  integer not null default 0,-- 滑窗 GC 锚点
  segment_index  integer not null default 0,-- 一章多分段保序
  upstream_url   text not null,             -- 仅服务端；绝不下发前端
  headers_json   text,                      -- 该段专属请求头
  content_type   text not null default '',
  byte_size      integer not null default 0,
  duration_ms    integer not null default 0,-- 前端 loadedmetadata 回传
  state          text not null default 'pending',  -- pending | ready | failed
  last_error     text,
  pinned         integer not null default 0,-- GC 免死金牌
  listened       integer not null default 0,-- 听完（> 80%）
  created_at     integer not null,
  last_access_at integer not null
);
-- 另有：segment/book/state/access/pinned 五个**非唯一**索引

alter table reading_progress add column position_ms integer;  -- 幂等迁移
```

## 6. 接口契约

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| POST | `/api/books/audio` | 解析并返回 track 列表（**只有 key + segmentIndex + status + durationMs，无上游 URL**）；`state=empty` 表示规则没提取到地址 |
| GET | `/api/audio/{key}` | 流式直出 `200/206/416` · `Accept-Ranges` · `ETag` · `immutable` |
| POST | `/api/audio/duration` | 前端回传时长（顺带按 `positionMs/durationMs ≥ 80%` 打「听完」） |
| POST | `/api/books/audio/cache` | 批量缓存入队（全局闸门 + 错峰），`202 Accepted` |
| GET | `/api/books/audio/prefetch` | 正在跑的缓存任务 |
| GET | `/api/books/audio/cached-chapters` | 已**整章就绪**的章节 URL（目录绿点 `●`） |
| GET | `/api/audio/cache/stats` | 占用 / 配额水位 / 按书明细 |
| PUT | `/api/audio/cache/pin` | 「整本离线」免死金牌开关 |
| DELETE | `/api/audio/cache` | `?key=` / `?sourceId&bookUrl` / `?all=true`（先表后盘） |
| PUT | `/api/reading-progress` | 新增可选 `positionMs`；**既有 0..1 校验原样保留**，仅额外拒负数 |

## 7. 前端

- 路由：`bookKind === 'audio'` ⇒ `AudiobookPlayerScreen`（与 `ReaderScreen`/`MangaReaderScreen` 平级，**不做全局 Mini 条**）。
- 版式取自 `有声书阅读器参考/`：大圆角封面 + 深色渐隐叠层 + 播放跳动条 / 信息区 / 细进度条 + 左右时间 /
  `上一条 · 退 15s · 主播放键(64px) · 进 15s · 下一条` / 底部功能条（倍速 · 定时 · 缓存 · 目录 · 设置）。
- **主题完全跟随主 UI**：全文件只用 `--canvas/--surface/--surface-muted/--ink/--muted/--line/--accent/--accent-soft`，
  **不写死任何颜色**（有测试强制）；封面"高斯模糊底衬"用独立图层 `filter: blur()`，**不用 `backdrop-filter`**
  （AGENTS.md：它会破坏 `position: fixed` 的包含块）。
- 毫秒进度：`timeupdate` 节流 4s 落库（`positionMs`），`scrollPosition` 对音频**固定 0**（绝不让一个字段三种语义）。
- 70% 预取下一章；`mediaSession`（锁屏/耳机/车机）+ 睡眠定时（15/30/45/60/90 分钟 · 播完本章）+ 倍速 0.5–3.0x。
- 离线：清单快照（IndexedDB，**所有操作带超时**）+ 整章预热（并发 2）+ SW `CacheFirst` 缓存 `/api/audio/<64hex>`。

## 8. 分期与验收

| 期 | 交付 | 验收 |
| :--- | :--- | :--- |
| **P0** | G0 放开音频入架 + `audioTracks()` + `GET /api/audio/{key}` 直通 + 播放器 | 备份导入后音频书**入架且 `book_kind='audio'`**；三种规则形态都能解析出 track |
| **P1** | `audio_cache` + `AudioFetcher`（流式落盘）+ Range 直出 | 二次播放**零上游请求**；`206/416/304` 正确；中断不留 `.part` |
| **P2** | 批量缓存 + 绿点 + `AudioCacheGc`（20 GiB 滑窗）+ stats/删除 | 磁盘不超配额；**在听前方 5 章与 pinned 不被删** |
| **P3** | 播放器完整形态（倍速/睡眠定时/mediaSession）+ 毫秒进度往返 + 70% 预取 + 离线快照 | 锁屏可控；重开精准回到毫秒；断网可听已缓存章 |

## 9. 测试矩阵（已落地）

| 层 | 用例 | 断言要点 |
| :--- | :--- | :--- |
| 单测 | `AudioTracksTest` | 纯文本 / JSON 多分段保序 / 对象形态 headers / `url,{"headers"}` 与 `,%7B` / 相对地址补全 / 空清单 |
| 单测 | `AudioCacheTest` | 流式落盘、`.part` 不留脏文件、**先盘后表**（文件不在拒绝写 ready）、同 URL 二次命中、换地址清旧行**并回收字节**、弱校验（HTML 错误页 / FLAC 如实报错）、gzip 流式解压、200 MiB 上限、分段头覆盖源级头 |
| 单测 | `AudioCacheGcTest` | 90%→75% 滞回、滑窗 5 章强保护、**音频窗口是 5 不是 15**、中间态宁留不删、pinned 免死、`enabled=false` 连 force 都绕不过 |
| HTTP | `AudioRangeRouteTest` | 401 / 200+ETag+immutable / `206`+`Content-Range`（含后缀式 `-512`）/ `416` + `bytes */size` / `304` / 非法 key 404 / 清单**不含上游 URL** / 绿点 / stats / pin / 删除 |
| HTTP | `AudioProgressTest` | 毫秒往返、文本写入**不抹掉**毫秒、`0` 与 `null` 可区分、负数 400、比例越界仍 400、迁移幂等、镜像 `durChapterPos` |
| 单测 | `BackupImportAudioTest` | 同分布合成备份（389 在线 + 31 音频 + 14 本地）：音频**不再被过滤**、`skippedAudio=0`、本地仍过滤、音频 `durChapterPos` → `position_ms`（文本不导入） |
| 前端 | `audiobook-player.test.ts` | 外壳结构（封面/进度/主键/底部条）、三主题落到根节点、**无 Mini 条/无 createPortal**、SW 规则与真实路由逐字对齐（**精确 64hex，不吃统计接口**）、4 语 `audiobook.*` 键一致、预热并发 ≤2 |

## 10. 部落知识对齐

| 部落知识 | 落点 |
| :--- | :--- |
| 限流必须加在**全局** | `AudioFetcher` 的 `Semaphore(3)` + 单 host `Semaphore(1)` |
| `.part` + `ATOMIC_MOVE` | `AtomicFileStore.writeStream`（音频唯一落盘路径） |
| 「接口 200 + 字节数正常」≠ 能播 | 前端断言 `readyState >= 2 && duration > 0`（浏览器 E2E）；服务端不拿 200 当成功 |
| Range 写错 ⇒ seek 卡死/416 | **同一份** `respondFileRange`，另补 `bytes */size` |
| Workbox RegExp 拿完整 URL 匹配 | SW 规则用**函数**匹配 `url.pathname`，且用精确 64hex 正则避免吃掉 `/api/audio/cache/stats` |
| `Path.resolve()` 对非法字符抛异常 | key 只接受 64 位小写 hex（路由先校验再落 `pathFor`） |
| 「接口 200」≠「数据到手」 | 清单只回 key；`assertFalse(manifest.contains("cdn.example.com"))` |
| 静默丢数据比抛错危险 | 空清单回 `state=empty`；抓取失败回 `502 + 真实原因`（格式不支持/要登录/404 可分辨） |
| `Double` 科学计数法等格式化坑 | 毫秒走 `integer` 列，不经 `Double` |
| 进度比较用 `idx` 而非时间戳 | 跨端合并逻辑未改动；音频只在 `idx` 相同时用毫秒比 |

## 11. 复杂度惩罚与刻意不做

- 音频与漫画**共用** `AtomicFileStore` / `ByteRangeServing` / `ImageBytes` 的字节原语（哈希、gzip）：
  音频只新增**音频独有**的判据（`AudioBytes`），**不复制**哈希/GZIP 实现。
- 不引 ffmpeg / hls.js / 多进程子服务；不做多段 `multipart/byteranges`（`<audio>` 只用单段）。
- 没有"音频章节清单表"（可从 `audio_cache` 聚合得出）。
