---
id: ADR-009
title: TTS 音频流首帧静音垫底与反向代理穿透架构
status: accepted
date: 2026-09-09
---

# ADR-009: TTS 音频流首帧静音垫底与反向代理穿透架构

## 1. 决策背景 (Context)

Edge-TTS 会话流在通过 Nginx 反代或移动端浏览器播放时，容易因以下原因导致“显示播放中但无声且控制中心无显示”：
1. Nginx 默认的 `proxy_buffering on` 将前几个小体积的 MP3 分块与 SSE 进度事件拦截在代理缓冲区中；
2. 浏览器 `<audio>` 建立连接到收到第一句合成音频之间有空窗期（300~600ms），处于 `HAVE_NOTHING` 状态，无法及时被系统 MediaSession/Control Center 识别为活跃音频；
3. 移动端/Safari 对异步触发的 `audio.play()` 存在手势限制（Autoplay restriction）。

## 2. 裁定方案 (Decision)

1. **服务端代理防缓冲响应头**：
   - 在 `/api/tts/session/{id}/audio` 与 `/api/tts/session/{id}/events` 中添加：
     - `X-Accel-Buffering: no`
     - `Cache-Control: no-cache, no-transform`
     - `Connection: keep-alive`
2. **首帧微型 MP3 静音帧垫底（Silence Preamble）**：
   - `/audio` 接口建立时，服务端在向 `output: ByteWriteChannel` 写入真实音频前，优先写入一段标准的微型 MP3 静音帧（24kHz 48kbps mono，约 144 字节）。
   - 该静音帧令浏览器底层解码器瞬间收到合法 MP3 头部，即刻将 readyState 提升至 `HAVE_FUTURE_DATA` 并注册系统控制中心，等待后续句子无缝拼接。
3. **前端同步手势预热**：
   - 用户在界面触发朗读（Click 事件回调）时，前端立即在同步调用栈中创建/获取 `<audio>` 对象并触发静音 `audio.play().catch(...)` 占位，锁定浏览器用户交互授权令牌。

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：要求所有用户手动修改 Nginx 配置 `proxy_buffering off;`**
  - *否决理由*：用户可能使用 Docker、宝塔、Cloudflare、Traefik 等多样化反代环境，不能将稳定性依赖于用户的外部运维配置，服务端通过 `X-Accel-Buffering: no` 即可自动生效。
- **备选方案 B：放弃流式传输，完全改为句句下载单个 MP3 文件播放**
  - *否决理由*：句句下载会导致移动端锁屏后被系统休眠机制挂起，无法实现跨句跨章无感后台连播；保留长连接会话流是移动端后台听书的最佳实践。

## 4. 后果与权衡 (Consequences & Trade-offs)

- **正面收益**：
  - 彻底解决反代缓冲卡顿与首句静音空窗问题；
  - 移动端和系统控制中心秒级响应并展示专辑封面与进度条。
- **负面代价**：
  - 每次开启新会话时服务端多发送约 144 字节静音垫底数据，带宽开销几乎可忽略。
