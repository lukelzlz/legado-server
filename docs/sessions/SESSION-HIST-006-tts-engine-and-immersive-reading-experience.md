# 听书朗读引擎重构与沉浸式阅读交互实现 (PROPOSAL-006 / ADR-006)

## 1. 核心背景与动机
原 Web 客户端仅提供简单的单次 `SpeechSynthesisUtterance` 全文朗读，缺乏分句分段高亮、跨章连续播放、点击段落选播、定时关闭以及高质量神经网络音色支持。
基于 Legado 原版听书能力与现代化 Web 体验标准，我们构建了纯 JVM 服务端 Edge-TTS / 自定义 HTTP TTS 双引擎与现代化 React 听书控制器。

## 2. 关键架构与实现方案

### 2.1 服务端 Edge-TTS 与自定义 HTTP 代理
- **纯 JVM WebSocket 协议握手**：通过 Ktor HTTP Client 连接 `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`，构造标准 SSML (`mstts:express-as`)，实时接收并解包二进制音频流。
- **高频音频块 LRU 缓存**：基于 MD5 哈希构建内存缓存池，热点章节音频命中缓存即刻返回。
- **动态 HTTP 源适配**：支持 Legado 风格模板变量 `{{speakText}}`、`{{speakSpeed}}`、`{{speakVoice}}`，兼容自建或第三方 TTS 服务。
- **API 接口**：
  - `GET /api/tts/voices`：列出预设高质量中文（普通话、台湾、粤语、东北话、陕西话）及英文发音人。
  - `POST /api/tts/speak` & `GET /api/tts/speak`：下发文本并直出 `audio/mpeg` 二进制流。

### 2.2 前端文本切分与分片引擎 (`ttsTextProcessor.ts`)
- **符号清洗与噪音过滤**：正则去除装饰边框（`===`、`---`）、特殊括号（`【】`、`「」`）以及连串标点。
- **智能分句状态机**：按中英文句子终止符（`。！？!?；;…\.\n`）将段落精准切分为朗读 Chunk，并维护 `paragraphIndex`、`sentenceIndex` 与 `globalIndex` 映射。

### 2.3 双模式音频播放引擎 (`ttsEngine.ts`)
- **`WebSpeechEngine`**：封装浏览器原生 `SpeechSynthesis`，具备 15 秒心跳保活与异常恢复机制。
- **`HttpAudioTtsEngine`**：基于 HTML5 Audio 与 Blob URL，具备滑动窗口并行预加载能力（播放句 $N$ 时后台并行预取句 $N+1$ 音频），实现 0ms 无缝衔接。

### 2.4 阅读器沉浸式交互联动 (`ReaderScreen.tsx` + `TtsPlayerBar.tsx`)
- **实时视口跟随与高亮**：
  - 滚动模式：自动调用 `scrollIntoView({ behavior: 'smooth', block: 'center' })` 确保当前朗读段落居中。
  - 翻页模式：根据段落水平偏移量自动平滑计算并切换至对应页码。
  - 段落高亮 (`.tts-active-paragraph`) 与正在发音的句子高亮 (`.tts-active-sentence`)。
- **点击段落即刻选播**：点击阅读区域任意段落即刻跳转从该段开始朗读。
- **跨章无缝连播**：本章读完自动翻页并预取下一章继续朗读。
- **系统级媒体集成**：
  - `MediaSession API`：锁屏界面/耳机线控切歌键（上一句/下一句、播放/暂停）与封面展示。
  - `Screen Wake Lock API`：朗读期间自动保持屏幕常亮。
- **睡眠定时器**：支持 15/30/45/60 分钟倒计时、读完本章停止、读完本段停止。

## 3. 验证情况
1. `npm --prefix web run check`：通过静态类型检查（0 错误）。
2. `npx tsx web/test/run-all.ts`：111 项前端单元测试全数通过（含 `tts.test.ts`）。
3. `npm --prefix web run build`：生产环境打包成功。
4. `./gradlew :server:test`：本地 JVM 单元测试全数通过（含 `EdgeTtsTest.kt`）。
