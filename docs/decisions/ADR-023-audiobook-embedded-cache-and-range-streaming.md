---
id: ADR-023
title: 采用单进程内嵌式整章音频缓存与流式 Range 直出体系 (方案G2) 支持有声书
status: proposed
date: 2026-10-12
---

# ADR-023: 采用单进程内嵌式整章音频缓存与流式 Range 直出体系 (方案G2) 支持有声书

## 1. 决策背景 (Context)

为 `legado-server` 与 Web 客户端引入有声书（音频书源）支持时，面临核心架构与实现形态的权衡：

1. **防盗链与音频托管需求**：
   有声书源大多部署了严格的防盗链（`Referer`）、需要书源登录态（`CookieJar`）或图片/音视频扩展请求头（`url,{"headers": ...}`）。纯前端直连（方案 G1）无法突破此类防盗链，且无法支持离线收听与多端共享。
2. **大字节流式传输与内存开销**：
   音频文件体积较大（每章通常 10MB ~ 60MB，整本书几百 MB 到几 GB），严禁将整文件一次性读入 JVM 内存（`readAllBytes()`），且播放器拖动进度条要求服务端提供标准 HTTP `Range`（`206 Partial Content`）支持。
3. **架构边界与复杂度惩罚**：
   - 存在将音频抓取与存储拆解为独立多进程微服务（方案 G5 / MediaVault）的提案；
   - 存在直接接入现有 TTS 会话链路（`/api/tts/*`）的设想。
   必须依据项目宪法（`AGENTS.md`）与最近落地的漫画架构决策（`ADR-022`），在“系统韧性”、“复杂度惩罚”与“生态深度兼容”之间做出正确裁决。
4. **Legado 安卓原版工程经验**：
   原版通过 `WebBook.getContent()` 抓取音频 URL，底层采用 `AudioCacheManager`（按章落盘、`.part` 守卫、原子重命名转正）管理离线缓存，并以毫秒（`currentPosition`）记录 `durChapterPos`。

---

## 2. 裁定方案 (Decision)

**裁定采用【方案 G2：单进程内嵌式整章音频缓存 + 流式 Range 直出体系】，并深度对齐 Legado 原版有声书生态规范**：

1. **统一单进程架构，坚决否决多进程子服务 (No Multi-process MediaVault)**：
   - 音频代抓、落盘、Range 直出与 GC 完全内嵌在 `legado-server`（端口 8080）中；
   - 遵循 `ADR-022` 确立的单进程哲学，保持单 Docker 镜像、纯内嵌 SQLite 数据库自包含部署，杜绝跨进程 RPC、HMAC 签名与双写一致性风险。
2. **规则层复用现有 `RuleRunner.content()` 机制**：
   - 识别 `bookSourceType == 1` 与书架 `ShelfKind.AUDIO`；
   - 对齐原版行为：音频源的媒体地址直接声明在 `ruleContent.content` 中，兼容纯文本 URL、JSON 数组（多分段音频）及带自定义请求头的扩展 URL，无需另外制造规则方言。
3. **内容寻址原子落盘与大文件流式直出**：
   - 存储路径采用两级散列目录：`data/legado-server/audio/<hash[0:2]>/<hash>`，文件名哈希为 `sha256(source_id + '\n' + 规范化URL)`，避免单目录文件过多；
   - 落盘严格执行 `tmp_*.part` 临时文件 + `ATOMIC_MOVE` 原则，写入完成并校验字节后原子转正，杜绝半截脏缓存；
   - 接口 `GET /api/audio/{key}` 实现流式 Range直出：
     - 复用 WebDAV 模块成熟的 `FileChannel.open(StandardOpenOption.READ)` + `call.respondBytesWriter(...)` 手写单段 Range（206/416）；
     - 严格流式管道，内存占用恒定（单个连接仅几 KB 缓冲区），彻底杜绝 OOM；
     - 返回 `Accept-Ranges: bytes`、`ETag` 与 `Cache-Control: public, max-age=31536000, immutable`，支持浏览器及 Service Worker 本地持久缓存。
4. **与现有 TTS 会话链路彻底物理隔离**：
   - 音频缓存直出为无状态幂等 HTTP 静态资源，与有状态长连接（`NoStore`、`no-cache`、SSE 进度推进、`MutableSharedFlow` 广播）的 TTS 引擎彻底解耦，互不干扰。
5. **分档 LRU GC 与 20 GiB 配额控制**：
   - 后台 `AudioCacheGc` 任务在总磁盘占用超过配额（默认 20 GiB）90% 时触发，淘汰至 75% 停止（滞回保护）；
   - 分档淘汰优先级：手动下载整本（`pinned=1`，免死保护） > 已收听（`listened=1`，收听 30% 以上） > 预取未听；同档内按 `last_access_at` 升序淘汰；
   - 写入先盘后表，清理先表后盘。
6. **进度字段定死为毫秒 (Milliseconds Semantic)**：
   - `reading_progress.scroll_position`（`real` 类型）在有声书模式下严格存储**播放时间戳（毫秒）**，与安卓原版 `durChapterPos` 语义完全一致，保障跨设备与 WebDAV 同步无缝对接。
7. **独立播放器架构（`AudiobookPlayerScreen`）**：
   - 独立路由 `/audiobook/:sourceId/:bookHash`，与 `ReaderScreen` 平级，不复用文本阅读器外壳；
   - 全面集成 `navigator.mediaSession`（锁屏/耳机控制），并在连播时提前（播放到 70%）预取下一章，对冲整章下载等待。

---

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

| 备选方案 | 做法 | 否决 / 不作为核心主线理由 |
| :--- | :--- | :--- |
| **方案 G1：前端直连透传** | 服务端只提取音频 URL，浏览器 `<audio src>` 直连 | **无法突破防盗链**（403 频发）、无法带书源 Cookie、无法脱机断网播放、一次性 Token 失效。仅可作为开发初期的冒烟探针，绝不能作为终态交付。 |
| **方案 G3：纯流式回填边下边播** | 播放时服务端边拉边吐给浏览器，后台双写回填 `.part` | 首帧虽快，但存在复杂的 singleflight 探针竞态、Range 冲突（未下到的字节如何响应）与 partial 中间状态机。**适合作为 G2 稳定后的第二阶段体验增强，不宜作为第一阶段基石**。 |
| **方案 G4：HLS 分片缓存与本地 m3u8** | 服务端按分片缓存 `.ts`，重写 playlist 并解密 AES-128 | 仅针对 m3u8 源，对主流直链 MP3/M4A 属于过度设计；引入 `hls.js` 依赖，文件数急剧膨胀。作为**按需扩展分支**，不作主线。 |
| **方案 G5：MediaVault 独立子服务** | 将音视频缓存拆分为独立 8081 端口服务，主服务反代 | **严重违背复杂度惩罚与 ADR-022 决策**：<br/>1. 漫画已裁定内嵌单进程（方案 E），再建独立服务导致架构分裂；<br/>2. 增加内部端口、HMAC 签名与熔断降级负担；<br/>3. 单体部署复杂度徒增。 |

---

## 4. 后果与权衡 (Consequences & Trade-offs)

### 正面收益 (Positive Consequences)
1. **防盗链与离线能力 100% 覆盖**：服务端作为安全代理与缓存底座，彻底消除 403 裂音与网络断流。
2. **极速 Seek 体验**：本地流式 Range 直出，拖动进度条秒级响应，媲美本地音乐播放器。
3. **零额外运维负担**：单镜像部署，零跨进程配置，统一 SQLite 存储。
4. **生态深度兼容**：与 Legado 安卓原版规则、WebDAV 备份进度及字段语义 100% 互通。

### 潜在风险与防御对策 (Risks & Mitigations)
1. **未缓存首章加载等待**：
   - *对策*：在播放器 UI 明确展示“正在下载缓存 X%”进度条；在连播时执行 70% 提前预取下一章，消除连续播放中的等待；后续平滑升级 G3 边下边播。
2. **大并发下载导致 IO 拥塞**：
   - *对策*：引入全局信号量（`MAX_GLOBAL_AUDIO_FETCHES = 3`，单源并发 1），使用独立的 IO 协程调度器，严禁阻塞 Rhino 沙箱与主服务请求。
3. **磁盘空间无节制增长**：
   - *对策*：内置 `AudioCacheGc` 守护任务，20 GiB 配额硬约束，分档 LRU 淘汰，提供 `stats` 可视化统计。
