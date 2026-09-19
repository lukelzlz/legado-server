---
id: ACCEPT-016
title: 本地图书（TXT/EPUB）导入与无缝阅读验收手册
status: pending # pending | accepted | rejected
proposal: PROPOSAL-016
date: 2026-09-19
author: Agent
---

# ACCEPT-016: 本地图书（TXT/EPUB）导入与无缝阅读验收手册

> **核心目标**：验证用户可将本地 `.txt` 和 `.epub` 电子书通过 Web 书架（点击或拖拽）一次性批量导入，系统自动探测编码、切分章节、生成/提取封面并入库；导入后可在 Web 端秒开阅读，享受目录跳转、进度持久化与 Edge-TTS 连续流式听书。
> **当前完成度**：`[Tested 单测全绿]`（待用户按本手册实操核验 $\rightarrow$ `[Accepted]`）。

---

## 0. 快速开始与环境准备 (Get Started)

### 0.1 方式 A：Docker 容器构建并启动（推荐）

```bash
docker build -f Dockerfile.server -t test-legado-server:latest .
docker stop test-legado 2>/dev/null || true
docker rm -f test-legado 2>/dev/null || true
docker run -d --name test-legado -p 8080:8080 \
  -e ADMIN_PASSWORD=admin123 \
  -e LEGADO_SECURE_COOKIES=false \
  -v $(pwd)/.data:/data test-legado-server:latest
```

### 0.2 方式 B：本地 Gradle 运行

```bash
LEGADO_DATA_DIR=./.data ADMIN_PASSWORD=admin123 LEGADO_SECURE_COOKIES=false ./gradlew :server:run
```

---

## 1. 核心功能验收步骤 (Step-by-Step Verification)

### 用例 1：Web UI 导入本地 TXT 小说 (Happy Path)
1. 浏览器打开 `http://127.0.0.1:8080`，输入管理员密码 `admin123` 登录。
2. 进入一级导航 **「书架」**。
3. 点击书架顶栏右侧的 **「导入本地」** 按钮，选择一个测试 `.txt` 文件（如 `《修仙传奇》作者：青云.txt`），或直接将 `.txt` 文件拖拽至书架页面。
4. **预期表现**：
   - 弹出 Toast 提示 `正在解析并导入 1 本本地书籍...`，随后提示 `成功导入 1 本本地书籍！`；
   - 书架立刻展示该书籍卡片，带有精致的 **「本地」** 标识徽标和自动生成的纯 SVG 艺术字封面；
   - 卡片上显示书名《修仙传奇》与作者“青云”。

### 用例 2：秒开阅读、TOC 目录虚拟化与进度记忆
1. 点击刚才导入的本地书卡片或点击「继续阅读」。
2. **预期表现**：
   - 阅读器秒开，正文规整排版展示；
   - 点击左上角目录图标，展开章节目录抽屉，正确展示由正则解析出的全部章节（如“第一章”、“第二章”等）；
   - 点击任意章节，瞬间平滑跳转至对应章节；
   - 刷新浏览器或退出阅读器重新进入，阅读进度（章节与滚动位置）精确恢复。

### 用例 3：本地书籍 Edge-TTS 连续流式朗读
1. 在阅读本地书籍时，点击顶栏右上角 **「耳机」** 图标（朗读）。
2. **预期表现**：
   - TTS 播放器底栏弹出，即刻流式朗读当前视口段落；
   - 正文段落随朗读高亮同步滚动；
   - 切换下一句或暂停/继续播放响应丝滑。

### 用例 4：EPUB 精排小说导入与内嵌封面抽取
1. 在书架再次点击「导入本地」，选择一个 `.epub` 电子书文件。
2. **预期表现**：
   - 成功解析入库，书架卡片展示 EPUB 内置高清封面图，元数据正确提取作者与书名；
   - 进入阅读器后，章节目录与 HTML 段落格式完整保留。

### 用例 5：本地书籍书架管理与彻底清理
1. 在书架卡片右下角点击 **「···」**（管理）。
2. **预期表现**：
   - 管理弹窗中已自动隐藏网络换源功能；
   - 点击「编辑书籍信息」，可自定义修改书名或作者并保存；
   - 点击「移出书架」，确认后书籍从书架移除，服务端 SQLite 缓存与磁盘原文件同步清理。

---

## 2. 命令行快速冒烟验证 (CLI Curl Test)

可直接运行以下命令测试接口：

```bash
# 1. 登录获取 Cookie 与 CSRF Token
CSRF_TOKEN=$(curl -s -c cookies.txt -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"password":"admin123"}' | grep -o '"csrfToken":"[^"]*' | cut -d'"' -f4)

# 2. 构造临时 TXT 文件并上传导入
cat << 'EOF' > /tmp/test_book.txt
第一章 启程
天地初开，万物生光。

第二章 探索
少年踏上了前行的征途。
EOF

curl -s -b cookies.txt -H "X-CSRF-Token: $CSRF_TOKEN" \
  -F "file=@/tmp/test_book.txt;filename=《星空彼岸》作者：辰星.txt" \
  http://127.0.0.1:8080/api/bookshelf/import-local

# 期望输出：
# {"total":1,"imported":1,"failed":0,"results":[{"filename":"《星空彼岸》作者：辰星.txt","success":true,"bookUrl":"local://...","name":"星空彼岸","author":"辰星","totalChapters":2}]}
```

---

## 3. 回归与边界核验清单 (Checklist)

- [ ] **编码兼容性**：UTF-8、UTF-8 BOM、GB18030/GBK 格式小说解析无乱码。
- [ ] **短篇无章节**：无标准章节标题的 TXT 可按自然段落智能分页展示，不崩溃。
- [ ] **生态兼容性**：原有网络书源检索、书架缓存与听书功能不受任何影响。
- [ ] **多文件批量上传**：一次性选择多个 `.txt` / `.epub` 文件批量上传全部成功。
