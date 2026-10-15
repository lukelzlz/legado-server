# ADR-029：有声书采用「URL 寻址 + 原子流式落盘 + Range 直出」的内嵌缓存体系（方案 G2）

> 状态：`Accepted`（已实施并本地验证；尚待推送）
> 关联：[`PROPOSAL-029`](../proposals/PROPOSAL-029-audiobook-support-and-url-addressed-cache.md) ·
> [`ACCEPT-029`](../acceptance/ACCEPT-029-audiobook-p0-p3.md)

## 1. 背景与问题

服务端已有漫画（图片书源）的完整链路：**内容寻址**缓存 + 手写 Range 直出。
有声书（音频书源）要落地时有四件事**必须在写代码前拍板**，否则一定返工：

1. **进度语义冲突**：现有 `PUT /api/reading-progress` 校验 `scrollPosition ∈ 0.0..1.0`，
   而音频的进度语义是**毫秒**（对齐安卓 `durChapterPos`）⇒ 毫秒值会被 **400** 直接拒掉。
2. **key 模型**：内容寻址（去重）还是 URL 寻址（可预测）？
3. **多分段一章**：服务端拼成整段，还是下发 track 列表？
4. **配额与窗口**：音频单章 10~60 MiB，与漫画（十几 MB/章）不是一个量级。

## 2. 决策

### 2.1 进度：**新增 `reading_progress.position_ms`**（不动既有语义）

- `scroll_position` 继续服务文本/漫画的比例语义，**一个字都不改**（含那条 400 校验）；
- 仅音频写 `position_ms`；写入用 `coalesce(excluded.position_ms, reading_progress.position_ms)`
  ⇒ 文本路径（不带该字段）**不会抹掉**音频的毫秒；
- 迁移写成幂等的 `alter table ... add column`（仿 `migrateReadingProgress()`）。

**否决的选项**：
- (b) 按 kind 放宽校验 ⇒ 一个 `Double` 字段三种语义（比例/比例/毫秒），后来者必踩；且 `Double` 存毫秒有精度隐忧。
- (c) 双写比例 + 毫秒 ⇒ 冗余且必然不一致。

### 2.2 key：**URL 寻址**（`sha256(sourceId + '\n' + 规范化URL)`）

- 音频地址稳定且 key **可预测** ⇒ 前端在**抓取之前**就能拼出 `/api/audio/{key}`，
  天然绕开漫画当初"内容寻址下抓取前 key 未知"的坑（那正是漫画被迫改用定位 URL 的原因）；
- 含 `sourceId` ⇒ 跨源不脏命中（同漫画裁决）；
- 代价：放弃跨 URL 字节去重。音频场景收益近零（同一段音频不会在多个地址间共享字节），可接受。

### 2.3 多分段：**下发 track 列表，前端串播**

服务端**不拼流、不转码**（Non-Goal 禁 ffmpeg）。一章 N 段 ⇒ `segmentIndex` 保序，前端段内串播、
段末才换章。`segment_index` 必须落库（规格缺口），但**刻意不建唯一索引**：
书源换了某一段的地址时，新 key 会挂在同一个 `segment_index` 上，唯一索引会让那次 upsert
**直接抛异常**（一次书源变更 ⇒ 整章打不开）；排序/拼装只需要索引能加速，不需要唯一性。

### 2.4 配额与 GC 窗口：**20 GiB + 后方 5 章**

- 配额默认 20 GiB（`LEGADO_AUDIO_CACHE_MAX_GB`），与漫画 10 GiB **分开配置**（共用会让"听书挤掉漫画"成为常态）；
- 沿用漫画的滑窗分档部落知识，但**窗口从 15 章收到 5 章**：一章音频 10~60 MiB，
  "后方 15 章"就是近 1 GiB。**窗口必须按字节量级定，不能按"章数习惯"定**；
- 滞回仍为 90% → 75%，pinned 免死，失败一律静默。

### 2.5 落盘与直出：**共用既有实现，不另写一份**

- 落盘：`AtomicFileStore.writeStream`（`.part` + `ATOMIC_MOVE`）。音频是 10~60 MiB，
  **严禁** `readAllBytes()`（60 MB × 并发 3 = 180 MB 堆，必炸）；
- 直出：把 `WebDavServer` 里那份手写单段 Range **原样提取**成 `ByteRangeServing.respondFileRange`，
  WebDAV 与音频共用一份。AGENTS.md 明文「Range 写错 ⇒ 播放器 seek 卡死 / 416」，
  两套实现必然漂移；音频只额外挂 `ETag` / `Cache-Control: immutable`。

### 2.6 校验策略：**弱校验 + 内容类型白名单**（与图片刻意不同）

图片是**强魔数校验**（不认识就拒绝）。音频不能照抄：容器/编码太杂（`mp4` 里可能是 AAC 也可能是 ALAC），
强魔心会**误杀合法编码**。因此：

- 目标是"**是不是一张 HTML/JSON 错误页**"（弱校验），加上 **MP3/M4A/AAC/OGG/WEBM 内容类型白名单**；
- FLAC/APE/WAV 等**如实报错**（"该格式不在直出白名单内，且本服务不做转码"），
  而不是静默返回坏字节 —— 用户需要知道下一步该做什么（换源）；
- 上游会谎报 `Content-Type`：**魔数优先，响应头兜底**。

### 2.7 与 TTS 链路**完全隔离**

`/api/tts/*` 是有状态会话（SSE 进度、`MutableSharedFlow` 广播、探针互斥），
而音频缓存是**无状态、幂等、immutable** 的静态资源 —— 两者语义相反。
把有声书塞进 TTS 会话会破坏 Range 寻道与多连接并发。

## 3. 后果

### 正面

- 进度语义干净：文本/漫画/音频各有一列，互不干扰；跨端镜像时 `position_ms` ↔ `durChapterPos` **直传**，无需换算。
- 前端能"先拼 URL 再播放"，`<audio>` 直接走服务端；`ETag + immutable` 让二次播放**零上游请求**。
- 一处 Range 实现 ⇒ seek 行为与 WebDAV 下载同源，不会各自漂移。
- 音频独有判据（弱校验/白名单）与图片强校验并存，互不影响。

### 负面 / 代价

- 放弃跨 URL 字节去重（可接受，理由见 2.2）。
- `audio_cache` 与 `manga_image_cache` 是两张形状相似但语义不同的表 ——
  刻意如此：**共用一个表会让"内容寻址 vs URL 寻址"混在一张表里**，后来的改动必然互相绊倒。
- `position_ms` 需要一次幂等迁移（已在 `migrateReadingProgress` 内）。

### 风险与对策

| 风险 | 症状 | 对策（已落地） |
| :--- | :--- | :--- |
| `readAllBytes()` 大音频 | 服务端 OOM / 卡死 | 强制 `writeStream` 流式；200 MiB/段硬上限 |
| 防盗链 | 403，整章放不出 | `Referer = 章节页` + 书源 header + Cookie；每段专属头优先 |
| 半截文件被读到 | 播到一半断流 | `.part` + `ATOMIC_MOVE`；校验在移动**之前** ⇒ 不合格不留成品 |
| Range 写错 | seek 卡住 / 416 | 复用同一份实现，并补 `Content-Range: bytes */size` |
| GC 把在听的删了 | 听着听着断流 | 滑窗强保护（后方 5 章）+ pinned + 90/75 滞回 |
| 毫秒进度被 400 拒 | 进度永远存不上 | §2.1 的新增列方案 |
| 书源换段地址后旧行残留 | 永远占盘 + 绿点凑不齐 | 重新登记时清旧行，**并回收其字节**（删前仍问一次表，防跨章共享地址误删） |
| SW 规则吃掉统计接口 | 清理后水位纹丝不动 | 音频 SW 规则用精确 `^/api/audio/[0-9a-f]{64}$`，不用前缀匹配 |

## 4. 与既有 ADR 的关系

- **不推翻 ADR-028（漫画内容寻址）**：漫画与音频各自保留自己的 key 模型（G-5）。
  两者共用的是**落盘原语与直出实现**，不是缓存语义。
- 延续 SESSION-025（限流必须在全局）、SESSION-027（魔数优先于响应头）、
  `[媒体缓存/GC]`（滑窗保护 + 已读历史优先）三条部落知识。
