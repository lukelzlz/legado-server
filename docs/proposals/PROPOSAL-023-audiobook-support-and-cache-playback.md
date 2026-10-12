---
id: PROPOSAL-023
title: 有声书支持与服务端音频缓存播放体系 (Audiobook Support & Server-Side Audio Caching)
status: draft # draft | review | accepted | implemented | rejected
author: Antigravity & User
date: 2026-10-12
---

# PROPOSAL-023: 有声书支持与服务端音频缓存播放体系

## 1. 业务背景与问题痛点

Legado 具备成熟的书源生态，其中 `bookSourceType == 1` 为**音频（听书）书源**。但在目前服务端 `legado-server` 与 Web 客户端中，有声书能力处于完全缺失与被动过滤状态：

1. **导入期被主动拦截丢弃**：
   `ShelfKind.kt` 与 `BackupImporter.kt` 中硬编码拦截 `ShelfKind.AUDIO`（根据 tab 标签 `听书` 判定），备份导入时将其作为 `skippedAudio` 直接跳过，4 个语言文件的导入提示中均明确提示“音频（听书）会被跳过”，导致有声书无法进入书架。
2. **缺乏服务端音频字节缓存与防盗链代理**：
   音频书源的媒体资源通常存在严格的 `Referer` 防盗链、需要书源专属登录凭据（`CookieJar`）或图片/音视频扩展请求头。若前端浏览器直接通过 `<audio src="上游URL">` 播放，极易因跨域、防盗链 403 或鉴权缺失而彻底失败；且直连无法实现断网脱机收听与多端缓存共享。
3. **缺乏独立的有声书沉浸式播放界面**：
   目前的 Web 端只有面向小说文本的 `ReaderScreen` 和面向 TTS 朗读的悬浮栏 `TtsPlayerBar`。有声书本质上需要的是具备大号进度条（SeekBar）、时间轴、快退/快进、倍速调节、睡眠定时器与系统级 `MediaSession`（锁屏/耳机线控）的独立播放器。
4. **历史架构边界混淆风险**：
   服务端现有 `/api/tts/*` 链路是基于 `Edge-TTS` 实时合成的不可缓存广播流（`NoStore`、`no-cache`、`MutableSharedFlow` 探针互斥）；如果将有声书强行塞入 TTS 会话，会破坏音频文件的静态缓存、Range 寻道与多连接并发语义。

---

## 2. 目标与非目标 (Goals & Non-Goals)

### Goals
- **书架与导入放开音频图书**：
  - 放开 `ShelfKind.AUDIO` 与 `BackupImporter` 的过滤限制，有声书正常进入书架并分配 `ShelfKind.AUDIO` 分支；
  - 调整 4 个 locale 语言文件的导入提示文案；
  - 书架卡片根据图书属性渲染专属“听书”徽标或形态，点击直接进入独立播放器。
- **服务端整章音频缓存与流式 Range 直出（方案 G2）**：
  - 复用 `RuleRunner` 执行 `ruleContent.content`，解析出音频 URL（纯文本 URL、JSON 数组多分段、扩展 Header 格式）；
  - 服务端流式代抓并执行内容寻址落盘（`data/legado-server/audio/<hash[0:2]>/<hash>`），采用 `.part` 临时文件 + `ATOMIC_MOVE` 原则，避免半截脏文件；
  - 直出路由 `GET /api/audio/{key}`：手写支持单段 `Range`（`206 Partial Content` / `416 Range Not Satisfiable`），流式直出（`FileChannel` + `respondBytesWriter`），并携带 `Accept-Ranges: bytes`、`ETag` 与 `Cache-Control: public, max-age=31536000, immutable`；
  - 建立 `audio_cache` 数据表，记录缓存元数据、字节数、时长、状态与访问时间；
  - 缓存配额与 GC：默认配额 20 GiB（`LEGADO_AUDIO_CACHE_MAX_BYTES`），分档 LRU 淘汰（手动下载 `pinned` > 已收听 `listened` > 预取未听）；
  - 连播预取：播放到 70% 时服务端/前端触发预取下一章，消除切章等待。
-- **现代化 Web 独立有声书播放器（`AudiobookPlayerScreen` · Apple Music 现代毛玻璃流媒体风）**：
  - **零全局侵入（独立屏幕）**：独立路由 `/audiobook/:sourceId/:bookHash`，与 `ReaderScreen` 平级；**坚决不设置全局 Mini 播放条**，不听有声书的用户在书架、书库、搜索、设置中零视觉干扰、零常驻 DOM；点击退出即暂停并销毁句柄，保持全局绝对整洁。
  - **流媒体沉浸质感（Apple Music 风格）**：
    - **动态毛玻璃底衬**：以当前书籍封面提取柔和高斯模糊氛围背景（`backdrop-filter` + 大半径模糊），完美融合现有 `--canvas` / `--surface` 三套主题（晓白、护眼、夜读）；
    - **大圆角悬浮封面卡片**：`border-radius: 20px`，深层立体阴影，播放时微浮升起，暂停时平稳归位；
    - **流媒体时间轴（SeekBar）**：极简纤细滑轨，支持大热区拖拽与 Hover/Touch 浮动时间气泡提示，时间标签采用等宽数字（`tabular-nums`）；
    - **Apple 风格毛玻璃胶囊药丸按钮**：倍速（`0.75x~2.0x`，不变调）与睡眠定时器（`15m~本集完`）采用半透明毛玻璃胶囊样式；
    - **核心大键位**：中心大号播放/暂停按钮（`64px` 圆形），配合专属快退 15s / 快进 30s 跳过按键、上一集/下一集与循环模式切换。
  - **宽屏桌面端双栏自适应**：手机端居中沉浸流式排版；桌面宽屏端自动展开为**左侧控制台 + 右侧常驻全集列表**，无需频繁点开抽屉，充分利用屏幕空间。
  - **系统媒体会话**：深度适配浏览器 `navigator.mediaSession`（锁屏显示封面与元数据、系统通知栏、蓝牙耳机播放/暂停/快进退控制）；
  - **守卫与韧性**：继承并复用项目既有的播放器看门狗（`stalled`/`waiting` 自动唤醒、`AbortError`/`interrupted` 静默拦截、`loadedmetadata` 守卫）。
- **阅读进度精准毫秒级持久化**：
  - `reading_progress.scroll_position`（`real` 类型）定死为**毫秒偏移量**，与 Legado 安卓原版 `durChapterPos` 语义 100% 对齐，保障多端 WebDAV 备份同步一致性。

### Non-Goals
- **坚决不做全局常驻 Mini 播放条**：听书能力完全收敛在播放器页面内部，退出播放器即暂停并释放资源，坚决杜绝在书架、书库中残留常驻悬浮底栏，保证纯阅读/漫画用户的视觉纯净。
- **不引入外部多进程子服务（坚决否决方案 G5）**：不拆解独立运行的 `MediaVault` 进程，单 Docker 容器自包含部署，单 SQLite 数据库持久化，严格遵循项目架构与 `ADR-022` 复杂度控制红线。
- **不把有声书接入 TTS 流式广播会话**：音频缓存直出为无状态幂等 HTTP 静态资源，与有状态长连接 SSE/WebSocket TTS 管道彻底物理隔离。
- **不引入重量级服务端音频转码库（如 ffmpeg）**：优先支持主流 HTML5 音频格式（MP3、M4A、AAC、OGG、WEBM），对罕见的无损格式（FLAC/APE/WAV）若浏览器无法解码则如实报错提示，不使后端膨胀。
- **首期不做 HLS 本地分片重写与解密（方案 G4）**：集中精力保障占 95% 以上的主流直链音频源，若未来有强烈的 m3u8 书源诉求再作为扩展子模块接入。
- **首期不做纯内存 singleflight 边下边播复杂状态机（方案 G3）**：以整章缓存 + 70% 连播预取为基石，优先保证数据落盘完整性与 Range 稳定，后续再做渐进增强。

---

## 3. 核心用户故事 (User Stories)

- **Story 1（导入手机备份与听书书架呈现）**：
  作为用户，我通过 WebDAV 或文件导入包含听书书架的 Legado 备份。系统不再跳过音频书籍，导入摘要中明确展示导入的听书数量。在书架页面中，听书以醒目的音频标识展示，点击封面直接进入有声书播放器。
- **Story 2（点击秒播与进度恢复）**：
  作为用户，我打开一本正在听的有声书。播放器自动恢复到上次收听的章节和毫秒进度（在音频元数据加载就绪后精准 seek）；若当前章节已被缓存，播放秒级启动，拖动进度条跳转毫无卡顿。
- **Story 3（切章连播无感预取）**：
  作为用户，我正在连续收听长篇评书。当第一集播放到 70% 时，系统在后台静默预取下一集的音频缓存；第一集播放完毕后自动无缝切入下一集，无需任何等待。
- **Story 4（离线整本下载与脱机收听）**：
  作为用户，我准备在通勤断网环境下听书。在目录抽屉中点击“缓存整本”或“缓存后20章”，系统加入后台离线任务队列并展示进度。已缓存的章节在目录中显示绿色圆点 `●`；断网时播放器可流畅直出本地缓存音频。
- **Story 5（息屏播放与耳机控制）**：
  作为手机 Web / PWA 用户，我锁上手机屏幕或切到后台。系统通知栏与锁屏界面清晰展示当前书籍封面、作者与章节名；我按动蓝牙耳机的按键，可以正常暂停/继续播放或快进快退，后台朗读不会被系统假死冻结。
- **Story 6（睡眠定时与自动关停）**：
  作为睡前听书用户，我在播放控制面板中设定“30 分钟后停止”或“播完本章后停止”。倒计时结束或本章播放完毕后，音频自动淡出或暂停，并准确保存停播时的毫秒进度。
- **Story 7（磁盘配额与透明清理）**：
  作为自建服务用户，我收听了大量音频导致磁盘占用升高。服务端的 `AudioCacheGc` 守护任务根据 20 GiB 上限自动淘汰最久未听的章节，且严格保留我手动“固定下载（pinned）”的书籍；在设置或存储统计中，我能清楚看到听书占用的总磁盘空间与各书籍的明细。

---

## 4. 详细接口设计与数据模型

### 4.1 数据模型设计

```sql
-- 音频章节缓存元数据表
create table if not exists audio_cache (
  cache_key      text primary key,   -- sha256(source_id + '\n' + 规范化 origin_url)
  source_id      text not null,
  book_url       text not null,      -- 用于按本统计占用与批量删除
  chapter_url    text not null,
  origin_url     text not null,      -- 原始音频源地址（仅服务端排障/重拉使用，绝不下发前端）
  content_type   text not null,      -- audio/mpeg · audio/mp4 · audio/aac · audio/ogg
  bytes          integer not null,
  duration_ms    integer not null default 0,   -- 媒体时长毫秒
  state          text not null,      -- pending | downloading | ready | failed
  downloaded     integer not null default 0,
  pinned         integer not null default 0,   -- 1 = 手动下载整本（GC 豁免保护）
  listened       integer not null default 0,   -- 1 = 听过 30% 以上（GC 优先保留）
  last_error     text,
  created_at     integer not null,
  last_access_at integer not null
);

create index if not exists audio_cache_lru_idx   on audio_cache(last_access_at);
create index if not exists audio_cache_book_idx  on audio_cache(source_id, book_url);
create index if not exists audio_cache_state_idx on audio_cache(state);
```

### 4.2 API 契约设计

| 方法 | 路径 | 参数 / Body | 说明 |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/books/audio` | `{ sourceId, bookUrl, chapterUrl }` | 解析章节音频资源，返回 track 列表及缓存 key/状态（**不暴露上游原始 URL**） |
| `GET` | `/api/audio/{key}` | Header: `Range: bytes=START-END` | **音频流式直出**：200/206/416 响应，`Accept-Ranges`，`ETag`，`immutable` |
| `POST` | `/api/books/audio/cache` | `{ sourceId, bookUrl, chapterUrls: [...] }` | 批量提交音频章节缓存任务 |
| `GET` | `/api/books/audio/cached-chapters` | `?sourceId=...&bookUrl=...` | 获取当前书籍已缓存就绪的章节 URL 列表（驱动目录打点 `●`） |
| `DELETE`| `/api/books/audio/cache` | `?key=...` 或 `?bookUrl=...` | 删除指定章节或指定整本书籍的音频缓存 |
| `GET` | `/api/audio/cache/stats` | 无 | 获取音频缓存总容量占用、配额水位与按图书分组统计 |

---

## 5. 验收基准 (Acceptance Criteria)

- [ ] **代码类型与自动化单测**：
  - `npm --prefix web run check` 零类型报错；
  - `npx tsx web/test/run-all.ts` 前端单测全绿；
  - `./gradlew :server:test` 服务端测试无回归。
- [ ] **解析与缓存落盘验收**：
  - 音频书源能正确通过 `RuleRunner` 解析并抓取音频；
  - 音频文件在 `data/legado-server/audio/<hash[0:2]>/<hash>` 正确落盘，下载中断不产生坏文件（`.part` 守卫）；
  - `GET /api/audio/{key}` 支持标准 `Range` 请求，返回 `206 Partial Content`，越界时严格返回 `416`。
- [ ] **播放器交互验收**：
  - 能够从书架平滑进入 `/audiobook/:sourceId/:bookHash` 播放页；
  - 页面大号进度条、倍速、快进退操作顺畅，seek 反应迅速；
  - 切章与连播预取顺畅；
  - 定时关闭（睡眠定时器）能准确在设定时间或章末暂停播放；
  - 退出并重新进入时，毫秒级断点能精准恢复。
- [ ] **系统集成验收**：
  - 手机端/浏览器端 `navigator.mediaSession` 正常呈现元数据，锁屏/控制中心能响应暂停与进度跳转；
  - 导入 Legado 真实备份包（含音频图书）不再被过滤，图书正常入架。
