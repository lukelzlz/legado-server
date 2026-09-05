---
id: ACCEPT-008
title: TTS 连续播放稳定性、相对时钟锚定与缓冲弹性架构验收手册
proposal: PROPOSAL-008
date: 2026-09-05
author: Agent & User
---

# ACCEPT-008: TTS 连续播放稳定性、相对时钟锚定与缓冲弹性架构验收手册

## 1. 快速开始与环境准备 (Get Started)

### 选项 A：本地前后端源码开发环境验证 (推荐开发联调)
```sh
# 终端 1：启动 Ktor 服务端
./gradlew :server:run

# 终端 2：启动 Web 前端开发服务器
npm --prefix web run dev
# 浏览器打开 http://localhost:5173 访问阅读器
```

### 选项 B：本地 Docker 容器热更新验证 (标准生产镜像)
```sh
# 重新构建并启动测试容器
docker build -f Dockerfile.server -t test-legado-server:latest .
docker stop test-legado 2>/dev/null || true
docker rm -f test-legado 2>/dev/null || true
docker run -d --name test-legado -p 8080:8080 \
  -e ADMIN_PASSWORD=admin123 \
  -e LEGADO_SECURE_COOKIES=false \
  -v $(pwd)/.data:/data test-legado-server:latest

# 验证服务健康并访问 http://127.0.0.1:8080
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/index.html
```

---

## 2. 核心功能验收步骤 (Step-by-Step Verification)

### 用例 1：长时间连续收听（突破 2 分钟死锁瓶颈，验证 5~10 分钟稳定播放）
1. 登录 Web 阅读器，从书架或搜索打开任意一本有较多短句/对话的小说章节。
2. 点击顶部工具栏的**听书图标（耳机）**开启 TTS 朗读。
3. 确认听书设置中引擎为 **Edge-TTS**（如“晓晓”或“云希”）。
4. 静止放置收听，保持自然播放超过 **3 分钟（观察超过 50 个分片/句子）**。
5. **预期表现**：
   - 音频持续平滑发音，句子逐句高亮向下滚动；
   - 彻底打破原先 2 分钟（40~50 句）必死锁卡死的规律；
   - 无需手动点击其他句子刷新，一路连续平稳朗读。

### 用例 2：短句/对话风暴抗抖动验证（5 分片前瞻测试）
1. 找到小说中连续出现简短人物对话的段落（如连续多句“好。”“快走！”“怎么可能？”）。
2. 观察控制台 Network 面板与播放器状态。
3. **预期表现**：
   - 服务端持续保持 5 个分片在队列中预合成；
   - 短句连续切换时声音无断粮卡顿，过渡自然顺畅。

### 用例 3：手动切句重锚定验证
1. 听书过程中，在正文中任意点击跳选其他句子。
2. **预期表现**：
   - 播放器瞬间销毁旧流并在新句子上建立新会话，基准时钟重新归零锚定；
   - 绝无报错弹窗（如 `AbortError` 或 `interrupted by pause` 已被静默过滤）。

---

## 3. 自动化回归测试一键复核 (Automated Verification)
```sh
# 执行前后端最短验证套件（全绿预期）
npm --prefix web run check && npx tsx web/test/run-all.ts && ./gradlew :server:test
```

---

## 4. 验收清单与核对确认 (Acceptance Checklist)
- [ ] 连续播放 3~5 分钟以上不出现无故静音卡死。
- [ ] 短句密集处无 Buffer Underrun 卡顿。
- [ ] 控制台无未捕获的音频异常，无错误弹窗。
