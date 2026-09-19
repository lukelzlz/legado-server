# 验收手册 ACCEPT-010: Kindle / 墨水屏版 Web UI (Simple-Web) 直连标准 REST API

> **对应需求提案**：[`PROPOSAL-010`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-010-kindle-simple-web-ui.md)  
> **架构决策**：[`ADR-010`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-010-kindle-simple-web-and-legacy-api-compatibility.md)  
> **当前状态**：`Ready for Acceptance`

---

## 1. 验证目标

验证 Kindle / 墨水屏极简版 Web UI (`simple-web`) 及前端直连标准 REST API 的完整功能：
1. **静态资源内嵌托管**：服务在 `/simple/`（或重定向 `/kindle`）提供原生极简电子书架、阅读器、搜索与 RSS 界面。
2. **标准 REST API 通信**：Simple-Web 前端 JS 直接调用 `/api/bookshelf`、`/api/books/chapters`、`/api/books/content`、`/api/reading-progress`、`/api/search` 等，支持 CSRF 与 Session 鉴权。
3. **双向切换入口**：
   - 现代版 React Web UI：右上角菜单提供“Kindle / 墨水屏版”链接。
   - Kindle 极简版 Web UI：底部菜单栏提供“现代Web版”链接，点击平滑切回 `/`。
4. **自动化测试套件**：前端类型与测试（122/122）、后端 JVM 单元/集成测试全绿通过。

---

## 2. 最短自测验证命令 (One-liner Verification)

```sh
# 1. 前后端全量自动化测试验证
npm --prefix web run check && npx tsx web/test/run-all.ts && ./gradlew :server:test

# 2. 本地构建并运行服务
./gradlew :server:run
```

---

## 3. Step-by-Step 实操验收步骤

### 步骤 1：访问 Kindle / 墨水屏版入口与重定向
1. 打开浏览器访问 `http://127.0.0.1:8080/kindle`。
2. **预期结果**：
   - 自动 302 重定向跳转至 `http://127.0.0.1:8080/simple/`。
   - 页面展示极简黑白高对比度的 Legado 书架（Simple Web）。

### 步骤 2：验证标准 REST API 与书架展示
1. 在 `http://127.0.0.1:8080/simple/` 中，书架应自动调用 `/api/bookshelf` 获取服务端书架并渲染图书卡片。
2. 点击任意图书卡片，进入 `reader.html?bookUrl=...&sourceId=...` 极简阅读器。
3. **预期结果**：
   - 阅读器通过 `/api/books/chapters` 获取目录，通过 `/api/books/content` 获取正文。
   - 翻页与点击正常，阅读进度通过 `PUT /api/reading-progress` 自动持久化回服务端。

### 步骤 3：验证搜索与书源切换
1. 在 Simple-Web 顶部或菜单点击“搜索”。
2. 输入书名进行搜索。
3. **预期结果**：
   - 调用 `/api/search` 返回搜索结果列表，可点击加入书架或直接阅读。

### 步骤 4：验证双向切换
1. 在 Simple-Web 阅读器或书架底部菜单中，点击“现代Web版”。
   - **预期结果**：页面平滑跳转回 `http://127.0.0.1:8080/` 现代 React 客户端。
2. 在现代 React 客户端右上角点击菜单下拉框，点击“Kindle / 墨水屏版”。
   - **预期结果**：页面平滑跳转到 `http://127.0.0.1:8080/simple/`。

---

## 4. 验收签字检查单

| 序号 | 验证项 | 预期表现 | 状态 |
| :--- | :--- | :--- | :--- |
| 1 | `/kindle` 重定向 | 访问 `/kindle` 自动 302 跳转至 `/simple/` | [ ] |
| 2 | Simple-Web 离线静态资源 | HTML/CSS/JS 均由内置 JAR 资源直出，无需外网 CDN | [ ] |
| 3 | `/api/bookshelf` / `/api/reading-progress` | 书架与阅读进度正常同步至 SQLite 数据库 | [ ] |
| 4 | 后端纯粹无 Legacy 路由 | 废弃旧版 API 路由已完全移除，后端 100% 统一 REST 规范 | [x] |
| 5 | 双向无缝跳转 | 现代 Web ↔ Simple Web 自由切换 | [ ] |
| 6 | 自动化测试 | 前端 122 项 + 后端全部单测通过 | [x] |
