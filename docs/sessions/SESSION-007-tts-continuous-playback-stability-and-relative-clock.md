# 根治 TTS 连续播放 2 分钟时钟累积漂移死锁与缓冲弹性架构 (PROPOSAL-008 / ADR-008)

## 1. 核心背景与问题排查
在 PROPOSAL-007 引入服务端会话级单条连续 MP3 音频流后，线上实测暴露严重可用性缺陷：
- **故障现象**：用户在连续听书约 2 分钟（连续播放 40~50 个句子）时，播放器突然静音停滞，文字高亮卡住不再推进；而当用户手动在正文中点击其他句子时，播放器又重新恢复播放。
- **用户怀疑方向**：
  1. 微软 Edge-TTS 是否存在 HTTPS/WSS 限速或断连？
  2. 客户端或服务端的缓冲（Buffering）是否存在缺陷？
  3. 网络链接是否断开后无法重连？

## 2. 深入审查与根因定性
经过对 Edge-TTS 协议、Ktor 服务端与 Web 客户端的红蓝对抗审查：
1. **排查微软限速**：非主因。若触发微软 IP 限速或 429/403 封禁，手动切句也必定失败；切句立即可播证明微软服务正常。但每个分片建立独立 WSS 存在握手开销（800~1500ms）。
2. **排查缓冲深度**：原实现中 `ReaderScreen` 仅设置 `lookahead <= 2`（2 分片前瞻）。网文短句居多（1~2 秒/句），2 分片仅提供 2~3 秒音频缓冲，遇 Edge-TTS 网络抖动极易引发 Buffer Underrun 缓冲饥饿。
3. **致命根因：绝对时钟累积漂移与 180ms 硬容差死锁（Smoking Gun）**：
   - 服务端 `TtsSessionService` 使用 `Mp3DurationEstimator` 解析 MP3 帧长累加绝对时间：`audioCursorMs += audioStats.durationMs`，并在 `chunk_end` 下发 `audioEndMs`。
   - 前端 `HttpAudioTtsEngine` 使用声卡硬件时钟 `nowMs = audio.currentTime * 1000`。
   - MP3 编码包含 Encoder Priming 与 Padding 样本，且声卡重采样存在微小物理偏差（约 4~6ms/句）。
   - 在播到第 40~50 句（约 2 分钟）时：
     $$\text{累积偏差} = 45 \text{ 句} \times 4.5\text{ms} \approx 202\text{ms} > 180\text{ms}$$
   - 前端判定条件 `if (endMs !== null && nowMs + 180 < endMs) continue;` 永远为真！
   - 播放器音频播完进入静音，`audio.currentTime` 停止；但判定认为“还没播完”，`onEnd` 永远不发，下一句不触发，lookahead 彻底停滞，陷入**永久静音死锁**！
   - 点击切句触发 `resetSession()` 将时钟与会话重新归零，因此产生“刷新一下又好了”的假象。

## 3. 架构设计与重构实现 (ADR-008)

### 3.1 单句相对时钟动态锚定 (Anchor Resync)
- 服务端 `chunk_end` 事件同步下发当前分片单句时长 `durationMs`。
- 前端废弃跨句累加的全局绝对时钟对比，改为切句动态锚定：
  - 分片激活时记录 `anchorTimeMs = audio.currentTime * 1000`；
  - 截止判定：`nowMs >= anchorTimeMs + durationMs - 60`；
  - **切句瞬间时钟偏差自动归零，跨句累积漂移彻底为 0**，支持任意时长稳定连续收听。

### 3.2 停滞安全推进看门狗 (Stall Watchdog)
- 若当前分片已播至句尾（`nowMs >= anchorTimeMs + durationMs - 250`）且 `currentTime` 停滞超过 350ms，看门狗强制判定分片结束并触发 `onEnd`，彻底杜绝静音帧挂起。

### 3.3 前瞻缓冲深度扩容至 5 分片
- `ReaderScreen` 前瞻窗口扩大为 `lookahead <= 5` 滑动窗口，建立 15~20 秒高水位音频储备，消除短句风暴下的 Buffer Underrun。

### 3.4 播放器挂起自动拉活
- 监听 HTML5 `<audio>` 的 `stalled` 与 `waiting` 事件；
- 看门狗轮询检测到非主动暂停但处于停滞状态时，自动触发 `.play()` 唤醒底层解码管道。

### 3.5 服务端 WebSocket 异常单次重试
- `EdgeTtsService.synthesizeStream` 引入单次失败快速重试（更新 `muid` 与 `connectionId`），未发送音频帧前失败自动重试，提升偶发握手超时韧性。

## 4. 验证与测试结果
1. **前端自动化测试套件 (`npx tsx web/test/run-all.ts`)**：118 项全绿通过。
   - 验证 `Relative clock anchoring prevents deadlock over 60 consecutive drifted chunks`：模拟 60 句（累计 3000ms 漂移）连续收听，验证相对时钟锚定全部平稳推进，不再卡死。
   - 验证 `Stall watchdog forces onEnd when audio clock halts near chunk end`：验证时钟停滞时看门狗自动推进。
2. **服务端单元测试套件 (`./gradlew :server:test`)**：BUILD SUCCESSFUL。
   - 验证 `session emits chunk_end with durationMs and audioEndMs` 单元测试通过。
3. **类型检查与构建检查**：`npm --prefix web run check` 0 错误。
