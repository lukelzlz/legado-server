---
id: SESSION-008
title: TTS 播放管线穿透、反向代理防缓冲与首帧静音垫底优化
date: 2026-09-09
author: Agent & User
tags: [tts, edge-tts, nginx, buffering, mp3, silence-preamble, media-session]
---

# SESSION-008: TTS 播放管线穿透、反向代理防缓冲与首帧静音垫底优化

## 1. 现象与排查推演 (Investigation & Analysis)

- **初始现象**：用户在阅读器中开启 Edge-TTS 朗读时，播放条显示播放中（未报错、未转圈），但听不到任何声音输出，且系统/移动端控制中心未显示活跃音频流。
- **全链路推演**：
  1. **反代缓冲截留**：服务端在下发 `/audio`（MP3 音频流）与 `/events`（SSE 进度事件通道）时未显式下发 `X-Accel-Buffering: no`。Nginx / Caddy 默认开启 `proxy_buffering on`，首个短句分片体积（2~6 KB）低于代理缓冲区阈值，导致数据被驻留在反代缓存中无法直出给客户端 `<audio>` 与 `EventSource`，前端陷入饥饿挂起。
  2. **首帧空窗与媒体解码器冷启动**：从建立流连接到收到首句 Edge-TTS 合成音频有 300~600ms 物理延迟，`<audio>` 处于 `HAVE_NOTHING` 状态，浏览器未激活音频焦点，系统控制中心（MediaSession）无法捕获播放状态。
  3. **用户手势授权衰减**：移动端在异步创建 Session 的 Promise 返回后再调用 `audio.play()` 容易发生手势上下文过期。

## 2. 最终落地的正确解法 (Final Solution)

1. **反代防缓冲响应头注入**：
   - 在 [`server/src/main/kotlin/io/legado/server/Routes.kt`](file:///root/legado-server/server/src/main/kotlin/io/legado/server/Routes.kt) 的 `/audio` 与 `/events` 路由中强制追加：
     - `X-Accel-Buffering: no`
     - `Cache-Control: no-cache, no-transform`
     - `Connection: keep-alive`
2. **首帧微型 MP3 静音帧垫底（Silence Preamble）**：
   - 在 [`server/src/main/kotlin/io/legado/server/TtsSessionService.kt`](file:///root/legado-server/server/src/main/kotlin/io/legado/server/TtsSessionService.kt) 中，客户端一旦建立音频流连接，服务端即刻向 `ByteWriteChannel` 输出 144 字节的合法 LAME MP3 帧头（24kHz 48kbps Mono）并立即 flush。浏览器即刻进入 `HAVE_FUTURE_DATA` 准备状态并唤醒系统控制中心。
3. **前端同步手势预热**：
   - 在 [`web/src/ttsEngine.ts`](file:///root/legado-server/web/src/ttsEngine.ts) 中，用户点击朗读时在同步调用栈中立即预热 `<audio>` 实例，保持移动端与 Safari 用户交互授权有效。

## 3. 沉淀的教训与部落知识 (Lessons Learned)

- **[反代流式传输] SSE 与音频流必须强制声明 `X-Accel-Buffering: no`**：任何基于 HTTP Chunked 或 SSE 的实时媒体流，均须在响应头中下发 `X-Accel-Buffering: no` 与 `Cache-Control: no-cache, no-transform`，防止各种反向代理与 CDN 默认缓冲截流。
- **[音频解码管线] 长连接流首帧静音垫底可彻底消除冷启动假死**：对于按需分片合成的长音频流，在客户端建立连接时先输出微型合规静音帧（Preamble），可使 HTML5 `<audio>` 瞬间进入就绪状态并激活系统级 MediaSession 控制中心，杜绝首句云端合成等待期内的无响应假象。
