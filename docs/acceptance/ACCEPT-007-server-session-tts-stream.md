---
id: ACCEPT-007
title: 服务端会话级连续 TTS 音频流与移动端后台稳定播放验收手册
proposal: PROPOSAL-007
date: 2026-09-05
author: Agent
---

# ACCEPT-007: 服务端会话级连续 TTS 音频流与移动端后台稳定播放验收手册

## 1. 快速开始与环境准备 (Get Started)

确保服务端及容器已处于最新部署状态（当前已部署在服务器 18082 端口）：

```sh
# 1. 容器运行状态核验
docker ps --filter "name=legado-tts-public-20260901"

# 2. 实时日志跟踪（观察会话创建与流式处理）
docker logs -f --tail 50 legado-tts-public-20260901
```

* **访问地址**：`http://lukeserver:18082`（或通过外网绑定域名访问 `http://djei.s.lukelzlz.top:18082`）
* **登录凭据**：管理员密码 `admin123456789`

---

## 2. 核心功能验收步骤 (Step-by-Step Verification)

### 用例 1：前台在线连续朗读与长短句无缝切换 (Happy Path & 2-Chunk Buffer)
1. 打开浏览器访问 `http://lukeserver:18082/#/reader`，打开任意书籍（如《我是大玩家》第 1 章）。
2. 点击顶部/底部控制栏的 **“听书 / 朗读”** 按钮（或右下角浮动条）。
3. 观察第一段及后续段落播放：
   * **预期表现**：
     * 首次点击后即刻建立会话并开始发声，控制台 Network 可见单一长连接 `GET /api/tts/session/<id>/audio`（`Content-Type: audio/mpeg`）与 `GET /api/tts/session/<id>/events`（SSE 流）。
     * 段落与句子之间连贯无静音断点，读到长段落（如第 4 段）不再出现停顿或卡死。
     * 正文绿色高亮光标实时跟随当前发音句子平滑滚动。

### 用例 2：移动端后台与锁屏持续听书 (Mobile Background Playback)
1. 在手机浏览器（Android Chrome / iOS Safari / PWA 模式）中打开阅读器并点击开始朗读。
2. 朗读正常播放后，按下手机电源键锁屏，或切换至手机桌面 / 微信等其他 App。
3. 保持在后台静听 1~2 分钟以上（跨越 5~10 个分句）。
4. **预期表现**：
   * 锁屏后声音持续播放不中断，锁屏控制中心（MediaSession）正常显示书籍标题与章节名。
   * 支持通过系统控制中心 / 耳机线控按键执行“暂停 / 继续”。

### 用例 3：跨章节自动连续朗读 (Cross-Chapter Auto Playback)
1. 在阅读器中跳转至章节末尾（或快进至章节最后一句）。
2. 让朗读自然读完当前章节的最后一句。
3. **预期表现**：
   * 读完当前章最后一句后，音频流平滑过渡播放下一章节第 1 句，不重新触发音频元素刷新或产生爆音。
   * 页面正文自动平滑加载并翻页至下一章，高亮光标自动定位至下一章首句。

### 用例 4：点选段落跳句与暂停 / 恢复控制 (Seek & Control)
1. 正在朗读过程中，点击正文中间的某一段落（或点击播放条的“上一句 / 下一句”按钮）。
2. 点击播放控制条的“暂停”按钮，等待 3 秒后再点击“继续播放”。
3. **预期表现**：
   * 点击新段落后，旧音频流立即中止，以目标句子为起点无缝重建流并开始发声，高亮光标精确定位至所点段落。
   * 暂停时音频停止，继续播放时从原位置恢复发声。

---

## 3. 预期接口与日志参考 (Expected Outputs)

### 会话创建与网络流
```http
POST /api/tts/session -> 200 OK
{
  "sessionId": "2689b25b-d87b-4c4e-8abc-db9b555f48d3",
  "audioUrl": "/api/tts/session/2689b25b-d87b-4c4e-8abc-db9b555f48d3/audio",
  "eventsUrl": "/api/tts/session/2689b25b-d87b-4c4e-8abc-db9b555f48d3/events"
}

GET /api/tts/session/.../audio -> 200 OK (Content-Type: audio/mpeg, streaming)
GET /api/tts/session/.../events -> 200 OK (Content-Type: text/event-stream)
```

---

## 4. 回归与边缘防御检查项 (Sanity & Edge Checks)

- [ ] **多租户与鉴权**：未登录或 CSRF 不合法时，会话创建与控制接口返回 401/403 阻断。
- [ ] **标点碎片防护**：遇到纯标点/引号切片（有效字符 < 2），不会向 Edge-TTS 发起空请求导致 416 崩溃。
- [ ] **内存资源回收**：用户退出听书或关闭标签页后，服务端 30 秒 Reaper 机制与断开事件确保无悬挂协程与内存泄漏。
- [ ] **自动化测试全绿**：`npm --prefix web run check && npx tsx web/test/run-all.ts && ./gradlew :server:test` 全部通过。
