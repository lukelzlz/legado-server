---
id: PROPOSAL-018
title: iOS PWA 更新机制修复、安全区适配与菜单项可见性优化
status: implemented # draft | review | accepted | implemented | rejected
author: Agent & User
date: 2026-09-24
---

# PROPOSAL-018: iOS PWA 更新机制修复、安全区适配与菜单项可见性优化

## 1. 业务背景与问题痛点

在 iOS Safari / PWA 独立桌面模式（Standalone）下，用户反馈存在以下三项关键体验与功能缺陷：

1. **PWA 无法更新 / 无更新提示**：
   - iOS WebKit 对后台 Service Worker 的唤醒与生命周期管理较严格，当前仅依赖 `updatefound` 事件与被动的 `focus`/`visibilitychange`，在 iOS PWA 从主屏幕冷启动或后台恢复时容易错失 SW 更新触发；
   - 缺乏手动触发更新检查的交互入口（如菜单内「检查更新」/版本号），当用户感知到服务端更新后无法主动刷新缓存。

2. **移动端页面死区（Safe Area）未生效，与顶部状态栏重叠**：
   - `index.html` 启用了 `apple-mobile-web-app-status-bar-style: black-translucent` 全屏覆盖；
   - 但 `web/src/styles.css` 中 `@media (max-width: 720px)` 媒体查询对 `.app-page-header` 硬编码了 `height: 56px; padding: 0 10px;`，覆盖了根规则中的 `padding-top: var(--safe-top)` 与 `height: calc(...)`；
   - 导致在所有移动设备（特别是带刘海/灵动岛的 iPhone）上，主导航顶栏贴顶 y=0，与系统时间、电量和药丸摄像头重叠打架。

3. **移动端功能菜单超到屏幕上方（“只能看到最下面一条”）与浅色主题文字不可见**：
   - **包含块捕获（Containing Block Trap）**：`.app-page-header` 声明了 `backdrop-filter: blur(16px)`，在 CSS 规范中会使父容器成为 `position: fixed` 的新包含块。移动端样式为 `.header-menu-dropdown` 设置了 `position: fixed; bottom: 0;`，导致其相对于 56px 高度的顶栏定位在 `bottom: 0`，整个抽屉被朝屏幕上方拉伸（-300px ~ 56px），除最底部一条外全部移出屏幕上边缘！
   - **文字颜色隐形**：`AppHeader.tsx` 中部分按钮内联硬编码了 `style={{ color: 'var(--text-color, #e6e8eb)' }}`。在「晓白」（`theme-light`）和「护眼」（`theme-paper`）浅色背景下，文字颜色回退为接近纯白的 `#e6e8eb`，导致对比度归零。

---

## 2. 目标与非目标 (Goals & Non-Goals)

### Goals (目标)
1. **移动端安全区全覆盖**：
   - 修复 `@media (max-width: 720px)` 下 `.app-page-header` 的安全区内边距与高度，确保在所有 iOS / Android 移动端均与状态栏完美隔离，保留 `env(safe-area-inset-top)` 和横屏左右安全区。
   - 检查并补齐书源、书架、订阅、规则、WebDAV 等主页面在移动端的顶部与底部安全区。
2. **菜单文字与色彩规范化**：
   - 移除 `AppHeader.tsx` 中所有 hardcoded 的 `--text-color: #e6e8eb` 内联样式，统一使用设计系统语义变量 `var(--ink)` / `var(--muted)`。
   - 优化移动端菜单弹窗/抽屉的最大高度与 `safe-area-inset-bottom` 适配，保证所有选项清晰可见并支持弹性滚动。
3. **iOS PWA 更新稳健化与主动更新**：
   - 优化 Service Worker 更新检测逻辑：在应用启动、页面前台激活（`visibilitychange` / `pageshow`）时主动调用 `reg.update()`；
   - 在功能菜单中增加应用版本信息与「检查更新」按钮，支持用户手动探测 SW / 前端版本变动并一键激活新缓存；
   - 优化 `needRefresh` 更新提示 Toast 的层级与安全区定位。

### Non-Goals (非目标)
- 不推倒现有 Service Worker 离线缓存策略与 Workbox 架构。
- 不引入重型第三方 PWA 客户端库，保持极简零依赖。

---

## 3. 核心用户故事 (User Stories)

- **Story 1（iOS 状态栏避让）**：
  - *作为* 在 iPhone 桌面打开「阅读」PWA 的用户，
  - *当我* 浏览书架、书库或进入任意功能页面时，
  - *我期望* 顶部导航栏自然避开顶部刘海/灵动岛和时间状态栏，有舒适的顶部边距，文字与图标不与系统状态栏重合。

- **Story 2（浅色主题菜单正常显示）**：
  - *作为* 使用「晓白」或「护眼」主题的用户，
  - *当我* 点击右上角菜单按钮展开功能列表时，
  - *我期望* 看到黑灰色清晰的「本地离线缓存管理」、「替换净化规则」、「WebDAV 文件服务」等所有菜单项，而非仅有一条退出登录。

- **Story 3（PWA 自动/手动更新）**：
  - *作为* PWA 用户，
  - *当我* 打开应用或在菜单中点击「检查更新」时，
  - *我期望* 系统能检测到服务端新构建发布的前端版本，弹出「发现新版本」提示，点击后秒级更新并重载最新代码。

---

## 4. 验收基准 (Acceptance Criteria)

- [ ] 移动端 `@media (max-width: 720px)` 下，`.app-page-header` 样式保持 `height: calc(56px + var(--safe-top))` 与 `padding-top: var(--safe-top)`。
- [ ] 「晓白」、「护眼」、「夜读」三套主题下，功能菜单内所有按钮文本颜色对比度均符合 WCAG 规范，无白色/近白色文本隐形 bug。
- [ ] 功能菜单提供版本状态与手动检查更新触发入口。
- [ ] 自动化测试与前端类型检查全部通过：`npm --prefix web run check` && `npx tsx web/test/run-all.ts`。
