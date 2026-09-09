---
id: ACCEPT-009
title: TTS 播放管线穿透、首帧静音垫底与多连接弹性容错验收手册
proposal: PROPOSAL-009
decision: ADR-009
date: 2026-09-09
author: Agent
---

# ACCEPT-009: TTS 播放管线穿透、首帧静音垫底与多连接弹性容错验收手册

## 1. 快速开始与环境准备 (Get Started)

确保当前服务已启动或处于最新状态（若使用后台常驻 JVM 服务）：
```sh
# 1. 确认服务端正在运行 (默认 8090 端口)
curl -s http://127.0.0.1:8090/index.html | head -n 5

# 2. 或若以 Docker 方式测试：
docker build -f Dockerfile.server -t test-legado-server:latest .
docker stop test-legado 2>/dev/null || true
docker rm -f test-legado 2>/dev/null || true
docker run -d --name test-legado -p 8090:8080 \
  -e ADMIN_PASSWORD="Legado@2026#Secure" \
  -e LEGADO_SECURE_COOKIES=false \
  -v $(pwd)/.data:/data test-legado-server:latest
```

---

## 2. 核心功能验收步骤 (Step-by-Step Verification)

### 用例 1：Web 端沉浸式开书与 TTS 朗读秒开
1. 打开浏览器访问 `http://<服务器IP>:8090`，使用密码 `Legado@2026#Secure` 登录。
2. 在书架中点击已预载的书籍《诡秘之主》（已预载 1447 章，秒开进入）。
3. 滚动到任意段落，点击阅读器右上角的 **TTS 朗读** 按钮（或按快捷键 `T`）。
4. **预期表现**：
   - 音频立即启动播放，无首句等待静音阻塞；
   - 朗读开始时，阅读器自动计算并高亮视口内最上方可见段落；
   - 播放流畅推进，无 500 报错或卡顿崩溃。

### 用例 2：浏览器双连接探针（Range/Probe）与断开容错
1. 运行自动化模拟测试脚本（模拟浏览器 Probe 探测与主音频流并发）：
   ```sh
   node scratch/test_tts_stream_live.mjs
   ```
2. **预期表现**：
   - 输出 `Audio stream HTTP status: 200`；
   - 输出 `X-Accel-Buffering: no` 且首帧瞬时收到 144 字节静音垫底；
   - 探针断开连接后，主音频流持续接收合成音频字节（无 500 `IllegalStateException: 音频流已连接` 报错）；
   - 输出 `✅ TTS Stream Live E2E test completed successfully!`。

### 用例 3：全量回归与稳定性测试套件
1. 运行前后端最短自动化测试套件：
   ```sh
   npm --prefix web run check && npx tsx web/test/run-all.ts && ./gradlew :server:test
   ```
2. **预期表现**：
   - 前端 120 项单元测试全部通过（120 passed, 0 fail）；
   - 服务端 JVM 单元测试全部通过（BUILD SUCCESSFUL）。

---

## 3. 预期日志参考 (Expected Log Output)

```log
[DefaultDispatcher-worker-1] INFO io.ktor.server.Application - authentication succeeded from localhost
[DefaultDispatcher-worker-2] INFO io.ktor.server.Application - tts session created: id=51dde299-5f16-42ae-b87e-62f60d363b69
[DefaultDispatcher-worker-3] INFO io.ktor.server.Application - audio stream connected (shared flow replay 16)
[DefaultDispatcher-worker-4] INFO io.ktor.server.Application - chunk c1 synthesized (5616ms)
```

---

## 4. 回归与异常核验项 (Sanity & Edge Checks)

- [x] **[反代防缓冲]** `X-Accel-Buffering: no` 与 `Cache-Control: no-cache, no-transform` 响应头正确透传。
- [x] **[首帧静音垫底]** 音频流连接瞬间下发 144 字节合法 LAME MP3 静音帧，浏览器 `<audio>` 秒入 readyState。
- [x] **[多流容错]** 采用 `MutableSharedFlow` 替代单通道与互斥锁，彻底杜绝浏览器探针请求导致的 500 异常。
- [x] **[会话解耦]** 单连接探针断开不影响会话生命周期，音频与 SSE 进度流保持稳定续播。
