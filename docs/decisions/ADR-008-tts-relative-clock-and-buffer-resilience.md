---
id: ADR-008
title: TTS 单句相对时钟锚定、前瞻扩容与停滞看门狗架构
status: accepted
date: 2026-09-05
---

# ADR-008: TTS 单句相对时钟锚定、前瞻扩容与停滞看门狗架构

## 1. 决策背景 (Context)

在 ADR-007 实现了会话级连续音频流后，线上测试发现长时间收听（约 2 分钟、40~50 分片）时必然卡死。
排查确定：服务端全局递增的 `audioCursorMs` 与前端 `currentTime` 存在无法避免的编码填充与声卡重采样微小物理偏差（4~6ms/句），跨句线性累加后在 2 分钟时突破 180ms 判定阈值，造成 `onEnd` 永久无法到达的死锁。同时 2 分片前瞻深度在短对话时抗网络抖动能力脆弱。

## 2. 裁定方案 (Decision)

### 2.1 单句相对时钟锚定（Anchor Resync）
- 服务端在 `chunk_end` SSE 事件中增加输出 `durationMs`（分片理论有效时长）。
- 前端 `HttpAudioTtsEngine` 放弃全局绝对时间戳比对，改为**相对时钟追踪**：
  - 每个分片从队列激活为当前播放项时，记录当前的 `anchorAudioTimeMs = audio.currentTime * 1000`；
  - 该分片的截止条件为：`nowMs >= anchorAudioTimeMs + durationMs - tolerance`（单句容差设为 60ms）；
  - **核心收益**：每个分片独立计算，前一分片的任何微小物理偏差在切句瞬间直接重置归零，**累积漂移永远为 0**！

### 2.2 停滞推进看门狗（Stall Watchdog）
- 当 `nowMs >= anchorAudioTimeMs + durationMs - 150ms` 且 `currentTime` 停滞超过 300ms（或者下一个分片已经在音频流中就绪到达）时，看门狗强制判定当前分片播放结束并触发 `onEnd`，杜绝一切静音帧死锁。

### 2.3 前瞻缓冲深度扩充至 5 分片
- `ReaderScreen` 中向 `HttpAudioTtsEngine` 的预取深度从 `lookahead <= 2` 调整为 `lookahead <= 5` 滑动窗口。
- 保证随时处于 15~20 秒的高水位音频储备，消除短句场景下的 Buffer Underrun。

### 2.4 播放器挂起恢复（Player Wakeup）
- 监听 HTML5 `<audio>` 的 `stalled` 事件与 `waiting` 事件；
- 当新音频帧到达或看门狗轮询检测到非用户暂停但处于 stalled/waiting 状态时，自动触发 `.play()` 唤醒底层解码管道。

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：无脑放大硬容差（如放大到 2000ms）**
  - *否决理由*：治标不治本。放大容差只是将卡死时间从 2 分钟拖延到 10 分钟或 20 分钟，本质依然是线性发散的累积误差；且过大的容差会导致每句话提前 1~2 秒切句，吞字严重。
- **备选方案 B：引入 Web Audio API AudioContext.decodeAudioData 进行前端纯客户端解码拼接**
  - *否决理由*：严重违反工程复杂度惩罚原则。iOS Safari 对后台标签页的 Web Audio API 会静默暂停 AudioContext，而原生 `<audio>` 标签是唯一能享受锁屏媒体中心和后台平稳播放的通道。

## 4. 后果与权衡 (Consequences & Trade-offs)

- **正面收益**：
  - 彻底拔除累积时钟漂移病灶，支持 10 小时以上不间断稳定连播；
  - 5 分片前瞻极大提升抗抖动弹性；
  - 代码改动轻量集中，保持架构优雅。
- **负面代价**：
  - 5 分片预取在跳章或大幅拖动进度条时，废弃的已合成未播分片略微增加（约 3~4 个短句音频，数 KB~数十 KB），完全在可接受范围内。
