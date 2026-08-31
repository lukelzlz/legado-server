---
id: ACCEPT-005
title: 书源登录鉴权、Chrome 扩展一键同步与万能 Cookie 凭据导入验收手册
proposal: PROPOSAL-005
date: 2026-08-31
author: Antigravity
---

# ACCEPT-005: 书源登录鉴权、Chrome 扩展一键同步与万能 Cookie 凭据导入验收手册

## 1. 快速开始与环境准备 (Get Started)

确保本地开发服务或 Docker 容器已处于最新状态：
```sh
# 本地 Docker 容器重启与健康检查
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/index.html
# 预期输出: 200
```

---

## 2. 核心功能验收步骤 (Step-by-Step Verification)

### 用例 1：Chrome / Edge 扩展一键同步凭据 (穿透 HttpOnly 与 CSP)
1. 打开 Chrome 或 Edge 浏览器，访问 `chrome://extensions/`（或 `edge://extensions/`）。
2. 开启右上角 **「开发者模式」**，点击 **「加载已解压的扩展程序」**。
3. 选择项目根目录下的 [`extensions/chrome/`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/extensions/chrome/) 文件夹完成安装。
4. 打开任意需登录的小说站点（如已登录账号的目标站标签页）。
5. 点击浏览器右上角扩展栏的 Legado 图标，确认：
   - 自动识别当前标签页域名与对应的书源信息。
   - 点击 **「一键同步 Cookie (含 HttpOnly)」**。
6. **预期表现**：
   - 弹出页提示 `✅ 成功提取 X 个 Cookie (含 HttpOnly) 并同步至阅读服务器！`。
   - 运行 `docker logs test-legado --tail 20` 可见：`[INFO] cookie synchronized for source: ...`。

---

### 用例 2：无可视化表单源直显 Cookie 卡片与万能格式导入
1. 访问本地阅读器：[http://127.0.0.1:8080](http://127.0.0.1:8080)。
2. 进入 **「书源管理」**，在带有绿色 `登录` Tag 的书源中，选择一个无可视化 `loginUi` 的书源（或点击编辑器顶部的 `[登录]` 按钮）。
3. 观察弹窗主体：
   - **预期表现**：直接呈现 **🍪 粘贴 Cookie / Token 输入卡片**，无需跳转二级菜单。
4. 在文本框中粘贴 Cookie-Editor 导出的 JSON 数组（如 `[{"name":"test_token","value":"abc123"}]`）或标准键值对（`test_token=abc123; uid=99`），点击 **「保存 Cookie」**。
5. **预期表现**：
   - 弹出绿色 Toast 提示 `Cookie 已成功保存并关联到书源`。
   - 下方实时回显持久化的 `Login Header` 状态。

---

### 用例 3：可视化动态表单与 JS 沙箱登录交互
1. 在书源列表中选择包含动态 `loginUi` 表单的书源（如包含用户名、密码输入框与登录按钮）。
2. 填写表单数据并点击 **「登录」** 按钮。
3. **预期表现**：
   - 服务端 Rhino 沙箱正确执行 `source.putLoginHeader()` 与 `cookie.setCookie()`。
   - 页面弹出 `Toast` 反馈（如 `登录成功`），且表单状态或登录头同步落盘保存。

---

## 3. 预期日志参考 (Expected Log Output)

在执行 Cookie 扩展同步或接口调用时，容器日志输出清晰透明：
```log
[DefaultDispatcher-worker-6] INFO io.ktor.server.Application - cookie synchronized for source: http://app.app.quyuewang.cn, count: 2, url: http://app.app.quyuewang.cn
```

---

## 4. 回归与异常检查项 (Sanity & Edge Checks)
- [ ] 桌面端与移动端书源列表中 `登录` Tag 均能正常展示，不被书源名称溢出遮挡。
- [ ] Cookie-Editor JSON 导入与标准 Cookie 导入均能正确归一化，CookieJar 中无脏数据。
- [ ] 删除登录头 / 清空登录信息功能正常且安全。
- [ ] 单元测试套件全部绿灯（107 前端测试 + 128 服务端测试）。
