# PWA 渐进式应用与用户自主分段离线缓存实操验收手册 (ACCEPT-012)

> **关联提案**：[`docs/proposals/PROPOSAL-012-pwa-capabilities-and-fullscreen-drawer-adaptation.md`](file:///root/legado-server/docs/proposals/PROPOSAL-012-pwa-capabilities-and-fullscreen-drawer-adaptation.md)  
> **关联架构决策**：[`docs/decisions/ADR-012-vite-plugin-pwa-workbox-and-safe-area-layout.md`](file:///root/legado-server/docs/decisions/ADR-012-vite-plugin-pwa-workbox-and-safe-area-layout.md)

---

## 1. 验收环境与验证命令

### 最短自动化验证
```bash
# 1. 前端类型检查与全部 127 项自动化测试
npm --prefix web run check && npx tsx web/test/run-all.ts

# 2. 服务端 JVM 单元测试
./gradlew :server:test

# 3. 前后端综合一键验证
npm --prefix web run check && npx tsx web/test/run-all.ts && ./gradlew :server:test
```

---

## 2. 核心功能实操核验清单

### 场景一：PWA 桌面/移动端安装引导与独立视口体验
1. **核验步骤**：
   - 使用 Chrome/Safari/Edge 访问 Web 客户端首页；
   - 首次进入且处于非 Standalone 模式时，底部滑出轻量安装引导横幅，点击右上角导航菜单亦可看到 **「📱 安装到桌面 / 主屏幕」**；
   - 点击安装后，应用以独立 App 窗口运行，无浏览器多余地址栏；
   - 检查刘海屏或 iPhone 手机端：顶部状态栏与底部手势条正确适配 `safe-area-inset-*`，页面无多余橡皮筋下拉跳动。
2. **核验标准**：
   - Web App Manifest 包含应用图标、主题色与 Shortcuts（书架/搜索）；
   - Service Worker 正常注册激活，静态资源被 Workbox 智能缓存。

---

### 场景二：用户自主分段离线缓存与目录已下载标识
1. **核验步骤**：
   - 打开任意书籍进入沉浸式阅读器，点击顶部工具栏的 **「目录」**（或展开侧边抽屉）；
   - 目录抽屉顶部呈现 **「分段离线缓存」** 操作面板：
     - `后 50 章`
     - `后 100 章`
     - `全本离线`
     - `自定义范围（如 10 ~ 80）`
   - 勾选或点击 **「存至本设备（脱机离线）」** 并点击 **「下载后 50 章」**；
   - 观察下载进度条实时推进（4 并发滑动窗口分片抓取）。
2. **核验标准**：
   - 下载完成后，目录列表中所有已缓存章节标题左侧显示醒目的绿色小圆点（`●`）；
   - 已离线章节点击即可秒开，无需任何网络请求。

---

### 场景三：脱机断网离线阅读与进度静默队列同步
1. **核验步骤**：
   - 开启飞行模式（或在 Chrome DevTools Network 面板勾选 `Offline`）；
   - 重新刷新或重新打开应用，书架正常秒开显示本地快照，并标记离线可用书籍；
   - 点击已缓存的书籍，进入阅读器脱机翻页阅读若干章节（如从第 10 章读至第 15 章）；
   - 恢复网络连接（关闭飞行模式 / 切换回 `Online`）；
2. **核验标准**：
   - 前端监听 `online` 事件，静默触发 `flushOfflineProgress` 将离线进度队列批量提交至服务端；
   - 刷新服务端书架，最新阅读章节与百分比已成功同步。

---

### 场景四：本地离线存储配额管理与一键清理
1. **核验步骤**：
   - 在主界面右上角菜单中点击 **「💾 本地离线缓存管理」**；
   - 弹出「本地离线缓存管理」面板，直观展示当前设备已占用存储空间、书籍列表、各书已离线章节数与体积大小；
   - 支持单书清理（点击垃圾桶图标）或全部清空；
   - 支持开启/关闭「存储超限时自动淘汰最久未读离线内容 (LRU)」。
2. **核验标准**：
   - 点击清理单书后，IndexedDB 中该书正文即刻清除，目录绿点徽标自动重置；
   - 容量统计实时重新计算归零。

---

### 场景五：断网离线听书平滑降级
1. **核验步骤**：
   - 断网离线状态下点击开启 TTS 朗读；
   - 若当前使用的是网络引擎（如 Edge-TTS），系统自动弹出确认对话框提示已处于脱机状态，询问是否切换为本地 Web Speech 引擎；
   - 点击「确定并开始」，即刻无缝使用系统本地合成语音朗读正文。
2. **核验标准**：
   - 无报错，无白屏崩溃，脱机阅读与朗读均可完整工作。
