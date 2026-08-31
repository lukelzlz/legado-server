---
id: ADR-006
title: 双模式 TTS 引擎、分片预缓冲与视口高亮联动架构
status: accepted # proposed | accepted | superseded | deprecated
date: 2026-08-31
---

# ADR-006: 双模式 TTS 引擎、分片预缓冲与视口高亮联动架构

## 1. 决策背景 (Context)

Legado 原版 Android 具备极佳的朗读体验：不仅支持原生系统 TTS，还支持强大的在线 HTTP TTS（如 Edge-TTS 等神经网络音色），并具备精细的段落高亮、自动翻页、跨章连播、睡眠定时与锁屏控制。
在迁移重构至无头后端与 Web 前端时，面临以下架构挑战：
1. **浏览器 Web Speech API 局限**：不同平台（Chrome/Safari/Firefox）对长文本的 `speechSynthesis` 支持差异大，容易在 15 秒后静音或意外中断，且不同系统音色质量参差不齐。
2. **HTTP 在线 TTS 的跨域与网络限制**：前端直接请求外部 TTS 服务容易受到 CORS、防盗链与 Token 鉴权阻碍；且分段音频请求若串行执行会导致句子间停顿明显（有明显打嗝卡顿感）。
3. **播放控制与 React 渲染状态解耦**：音频播放是一套连续时间轴状态机，若直接与 React 频繁触发的组件重新渲染深度绑定，易造成音频重放、断音或闭包陷阱。

---

## 2. 裁定方案 (Decision)

### 2.1 双引擎统一抽象 (`TtsEngine` 接口)
在前端设计统一的 TTS 控制层，抽象出统一的播放引擎接口：
- **`WebSpeechEngine`**：封装 `window.speechSynthesis`，智能按标点符号切分分片，自带保活与超时熔断保护，秒级响应。
- **`HttpTtsEngine`**：通过服务端中继代理（`/api/tts/speak`）获取音频流。服务端预置 Microsoft Edge-TTS 协议驱动与自定义 HTTP 模板引擎。

### 2.2 句子级切分与双缓冲预拉取 (Sentence Chunking & Pre-buffering)
- 将章节按段落切分，段落内部再按逗号、句号、感叹号、问号等切分为句子。
- **滑动窗口预缓冲**：在播放第 $N$ 句音频的同时，后台异步发起第 $N+1$ 句的音频合成拉取（Blob URL 缓存池），当前句播毕后 0 延迟无缝切换到下一句。

### 2.3 解耦的播放状态机与高亮视口联动
- 核心播放器 (`TtsPlayerCore`) 作为独立的单例/状态机运行，维护当前段落索引 `paragraphIndex`、句子索引 `sentenceIndex`、播放状态 `idle | playing | paused | buffering` 及倒计时定时器。
- 正文渲染层通过精确的 CSS 类名（`.tts-active-paragraph`, `.tts-active-sentence`）实现视觉高亮。
- 视口跟随：利用 `scrollIntoView({ behavior: 'smooth', block: 'center' })` 进行平滑中心对齐；分页模式下计算目标段落所在分栏并自动切换 `pageIndex`。

### 2.4 服务端 Edge-TTS 与自定义 HTTP 代理设计
- 服务端提供纯 JVM 跨平台实现的 Edge-TTS WebSocket/HTTP 中继与 HTTP 模板引擎解析（替换 `{{speakText}}`、`{{speakSpeed}}`、`{{speakVoice}}`），并设置适当的内存缓存（LRU Cache）。

---

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

### 备选方案 A：仅支持纯前端 Web Speech API
- **否决理由**：移动端部分浏览器（如微信内置浏览器、iOS Safari 或部分国产安卓浏览器）内置 Web Speech 语音极少且音质机械生硬，无法满足用户对 Legado 级高拟真听书体验的要求。

### 备选方案 B：前端直接请求第三方 TTS API (无需后端代理)
- **否决理由**：绝大多数第三方/自建 TTS 服务不携带 CORS 允许跨域头，浏览器会直接拦截报错；且直接在前端明文暴露 API Key 存在安全隐患。

### 备选方案 C：一次性合成整章音频并下载播放
- **否决理由**：一章数千字合成耗时高达 5-15 秒，开播首字延迟极高（严重劣化秒开体验）；且无法精确定位段落高亮与任意点击段落跳转。

---

## 4. 后果与权衡 (Consequences & Trade-offs)

### 正面收益 (Pros)
1. **秒开无延迟**：首句以毫秒级开始播放，后续句子并行预加载，全程丝滑无缝。
2. **极高音质体验**：支持微软小晓、云希等业界顶尖音色，兼备离线系统音色。
3. **视觉与听觉完美同步**：读者随时可在正文看到当前念读位置，点击任意段落即可换段播放。
4. **跨端友好**：锁屏、耳机线控、防息屏全方位适配。

### 负面代价与应对 (Cons & Mitigations)
1. **服务端带宽消耗**：在线 TTS 音频流会经过服务端中继。
   - *应对*：服务端引入 LRU 内存/临时磁盘缓存，对相同文本、发音人与语速的音频分片进行复用，避免重复请求。
