---
id: ACCEPT-TTS-001
title: 现代化 TTS 朗读引擎与沉浸式听书体验验收手册
proposal: PROPOSAL-006
adr: ADR-006
date: 2026-08-31
author: Agent
status: Accepted ✅
---

# ACCEPT-TTS-001: 现代化 TTS 朗读引擎与沉浸式听书体验验收手册

## 1. 快速开始与环境准备

```sh
# 一键全量验证（前端类型检查 + 单测 + 服务端单测）
npm --prefix web run check && npx tsx web/test/run-all.ts && ./gradlew :server:test

# 本地 Docker 构建与热更新
docker build -f Dockerfile.server -t test-legado-server:latest .
docker stop test-legado 2>/dev/null || true
docker rm -f test-legado 2>/dev/null || true
docker run -d --name test-legado -p 8080:8080 \
  -e ADMIN_PASSWORD=admin123 \
  -e LEGADO_SECURE_COOKIES=false \
  -v $(pwd)/.data:/data test-legado-server:latest

# 验证服务已就绪
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/index.html
```

---

## 2. 核心功能验收步骤

### 用例 1：听书启动与悬浮控制条（Happy Path）
1. 打开任意一本书籍，进入阅读器。
2. 点击顶部导航栏（或移动端底部）的"听书/朗读"按钮。
3. **预期效果**：
   - 底部滑入毛玻璃悬浮胶囊控制条（第 N/M 段，播放/暂停/上下句/上下章按钮）。
   - 开始朗读章节标题，随后进入第一段。
   - 正在朗读的段落出现淡绿色光晕背景（`.tts-active-paragraph`）。
   - 当前句子呈现重点文本高亮（`.tts-active-sentence`）。

### 用例 2：视口跟随与点击段落跳转
1. 等待朗读推进多段或点击控制条"下一句"。
2. **预期效果**：阅读界面平滑滚动，始终保持正在朗读的句子位于屏幕舒适视野中心。
3. 点击阅读区域内任意其他段落。
4. **预期效果**：朗读光标立即跳到该段并从头发音，视口自动对齐。

### 用例 3：高音质 Edge-TTS 音色切换
1. 点击控制条设置图标，打开调音面板。
2. 引擎选择"微软 Edge-TTS"，发音人选"云希 (男声·沉稳磁性)"，语速调至 1.25x。
3. **预期效果**：切换后下一句即刻使用高清神经网络音色播放，无卡顿断音。
4. 亦可通过 API 验证：
   ```sh
   # 快速接口验证（需先登录获取 Cookie）
   curl -s -o /tmp/tts-test.mp3 -w "size=%{size_download} status=%{http_code}\n" \
     -X POST http://127.0.0.1:8080/api/tts/speak \
     -H "Content-Type: application/json" \
     -b "legado_session=<your-session>" \
     -d '{"text":"你好，欢迎使用听书功能","voice":"zh-CN-YunxiNeural","rate":0}'
   # 预期：status=200，size > 10000（字节）
   ```

### 用例 4：引号/特殊标点容错（Regression）
1. 找到包含中文书名号或对话引号的段落，如：`"梵妮学姐，您没事吧？"`
2. 让 TTS 朗读到该段并越过引号结束位置继续朗读下一段。
3. **预期效果**：
   - 不出现 `ERR_REQUEST_RANGE_NOT_SATISFIABLE` 控制台错误。
   - 朗读连贯流畅，孤立引号被静默跳过，不引发 TTS 中断或崩溃。

### 用例 5：睡眠定时器与跨章连播
1. 调音面板中选择睡眠定时"读完本章停止"。
2. **预期效果**：控制条上出现对应标签；本章最后一句读完后自动停止并弹出 Toast。
3. 开启"读完本章自动连播"，让当前章读完。
4. **预期效果**：自动无缝切换到下一章，从新章第 0 句继续朗读，进度落盘同步。

### 用例 6：锁屏/耳机媒体控制（MediaSession）
1. 开启 TTS 朗读后，锁屏手机或在系统通知栏查看。
2. **预期效果**：显示书名与章节名，耳机播放/暂停键生效。

---

## 3. 预期日志参考

```log
# 服务端 Edge-TTS 合成成功日志
[DefaultDispatcher-worker-N] INFO io.ktor.server.Application - authentication succeeded from 127.0.0.1

# 正常无日志噪音（空文本被静默守卫跳过，不会触发 Edge-TTS 请求）
```

---

## 4. 验收结论

| 用例 | 结果 | 备注 |
| :--- | :--- | :--- |
| 用例 1：听书启动与控制条 | ✅ 通过 | 用户实操确认 |
| 用例 2：视口跟随与点击跳播 | ✅ 通过 | 用户实操确认 |
| 用例 3：Edge-TTS 音色切换 | ✅ 通过 | Chrome 143 协议修复后正常 |
| 用例 4：引号容错（416 防御） | ✅ 通过 | 用户上报后已修复并确认 |
| 用例 5：睡眠定时器与跨章连播 | ✅ 通过 | 用户实操确认 |
| 用例 6：MediaSession 锁屏控制 | ✅ 通过 | 用户实操确认 |

> **用户签字确认**：用户于 2026-08-31 明确回复"LGTM"，验收通过。
> **交付状态**：`[Accepted & Pushed]` — commit `7911cf1`，已推送至 `master`。
