---
id: PROPOSAL-009
title: TTS 播放管线穿透、反向代理防缓冲与首帧静音垫底优化
status: accepted
author: Agent & User
date: 2026-09-09
---

# PROPOSAL-009: TTS 播放管线穿透、反向代理防缓冲与首帧静音垫底优化

## 1. 业务背景与问题痛点

用户反馈在 Web 阅读器中使用 **Edge-TTS（云端音色）** 朗读时出现异常：
> **现象**：播放器 UI 状态显示正常（未报红、未转圈、显示正在播放），但**实际没有声音输出**，且系统/移动端控制中心（MediaSession 控制条）**没有音频流活跃显示**。

经排查与推演，定位到以下关键根因：
1. **反向代理（Nginx / Caddy）缓冲拦截**：
   - 服务端下发 `/api/tts/session/$id/audio`（分块 MP3 流）与 `/api/tts/session/$id/events`（SSE 进度事件）时，缺少 `X-Accel-Buffering: no` 与 `Cache-Control: no-cache, no-transform` 响应头。
   - Nginx 默认开启 `proxy_buffering on`，首个短句分片体积小（通常仅 2~6 KB），被 Nginx 驻留在上游缓冲区中无法直出给客户端浏览器，导致 `<audio>` 和 `EventSource` 双双陷入饥饿挂起。
2. **浏览器媒体解码管道冷启动与首帧空窗**：
   - 客户端建立 `/audio` 流连接后，由于 Edge-TTS 云端握手与生成第一句音频存在 300~600ms 物理耗时，`<audio>` 元素在连接初期接收 0 字节，处于 `HAVE_NOTHING` 状态，浏览器媒体引擎与系统控制中心无法激活音频会话。
3. **移动端/Safari 用户手势激活（User Gesture Activation）过期**：
   - 原前端逻辑在点击“朗读”后先发起 `api.createTtsSession()` 异步网络请求，待 Promise 返回后再调用 `audio.play()`。在移动端及 Safari 下，网络延迟会导致浏览器的用户手势上下文失效，从而被静默拦截或无法主动唤醒。
4. **SSE 异常静默吞没**：
   - 前端 `EventSource.onerror` 仅做了空函数忽略，若鉴权失效或代理断开无法及时感知并降级重连。

---

## 2. 目标与非目标 (Goals & Non-Goals)

### Goals
- **目标 1（代理直通防缓冲）**：在服务端所有 TTS 音频流与 SSE 事件通道响应头上显式追加 `X-Accel-Buffering: no`、`Cache-Control: no-cache, no-transform` 与 `Connection: keep-alive`，彻底穿透 Nginx / CDN 缓冲。
- **目标 2（首帧静音垫底 Preamble）**：服务端在 `/audio` 连接建立瞬间，立即向 `ByteWriteChannel` 直出极小体积（约 100 字节）的合规 MP3 静音帧（Silence Preamble），使浏览器 `<audio>` 瞬间进入 `HAVE_FUTURE_DATA` 准备就绪状态并立即激活系统控制中心（MediaSession）。
- **目标 3（同步用户手势占位解锁）**：前端在用户点击播放的同步事件栈中立即对 `<audio>` 执行预热占位，确保移动端及 Safari 用户手势权限 100% 保持有效。
- **目标 4（SSE 错误检测与双向容灾）**：前端增加 EventSource 心跳/重连监控，在通道异常或持续无音频推送时提供自愈恢复机制。

### Non-Goals
- 不修改现有的分片断句算法（`splitSentences`）与相对时钟锚定逻辑。
- 不引入外部转码工具（如 FFmpeg）。

---

## 3. 核心用户故事 (User Stories)

### Story 1: 秒级出声与系统控制中心活跃
> **作为** 小说读者，
> **当我** 在阅读器中点击“朗读本章”或切换段落时，
> **系统应** 在点击后瞬间激活播放器与系统控制中心（Control Center），并在首句云端生成完毕后平滑输出声音，无需等待代理缓冲区满。

### Story 2: 反向代理（Nginx/CDN）下的稳定穿透
> **作为** 在自建服务器或 Docker + Nginx 反向代理环境下使用 Legado 的用户，
> **当我** 开启连续 TTS 听书时，
> **服务端应** 携带 `X-Accel-Buffering: no` 确保 SSE 进度事件与分块 MP3 实时推送到浏览器，杜绝任何中间缓冲导致的卡滞。

### Story 3: 移动端/Safari 后台与熄屏播放稳定
> **作为** 手机端读者，
> **当我** 点击朗读后锁屏或切到后台，
> **系统应** 正确持有系统音频焦点与 MediaSession 状态，平滑连续跨句与跨章播报。

---

## 4. 验收基准 (Acceptance Criteria)

- [ ] 服务端单元测试：验证 `/api/tts/session/{id}/audio` 与 `/api/tts/session/{id}/events` 包含 `X-Accel-Buffering: no` 且流建立时首帧包含 MP3 静音帧。
- [ ] 前端测试与类型检查：`npm --prefix web run check` 与 `npx tsx web/test/run-all.ts` 0 报错通过。
- [ ] 真实浏览器验证：点击朗读后 `<audio>` 即刻响应，Edge-TTS 声音正常流出，控制中心同步展示播放中状态。
