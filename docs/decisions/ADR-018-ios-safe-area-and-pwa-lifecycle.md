---
id: ADR-018
title: iOS PWA 安全区级联覆盖重构与 Service Worker 双轨更新生命周期
status: accepted # proposed | accepted | superseded | deprecated
date: 2026-09-24
---

# ADR-018: iOS PWA 安全区级联覆盖重构与 Service Worker 双轨更新生命周期

## 1. 决策背景 (Context)

1. **移动端 CSS 媒体查询安全区覆盖问题**：
   原有 CSS 中，在桌面与大屏下 `.app-page-header` 使用了 `var(--safe-top)` 计算高度与内边距，但在 `@media (max-width: 720px)` 下被声明为简写 `height: 56px; padding: 0 10px;`。由于媒体查询的特定权重，移动端直接丢失了 `padding-top: var(--safe-top)`，导致 iOS PWA 模式下顶栏与 iOS 状态栏（20px~59px safe area）重叠。
2. **移动端包含块捕获（Containing Block Trap）导致菜单整体跑出屏幕上方**：
   `.app-page-header` 带有 `backdrop-filter: blur(16px)`。根据 CSS 规范，这会使顶栏成为后代 `position: fixed` 的新包含块。移动端样式为 `.header-menu-dropdown` 设置了 `position: fixed; bottom: 0;`，导致其相对于顶部 56px 顶栏定位，整个弹层向屏幕上方反向伸出（-300px ~ 56px），只留最底部一条可见。
3. **主题变量与内联样式冲突**：
   菜单项混用了不存在的 `var(--text-color, #e6e8eb)` 内联样式，打破了全局设计系统的 `--ink` / `--muted` 调色板，在白色背景下文字对比度归零。
4. **PWA Standalone 模式下的更新不可见**：
   iOS Safari PWA 具有独立的生命周期，被挂起后 `setInterval` 与 `updatefound` 容易错过。需要配合 `pageshow` 事件及在设置菜单中提供显式更新检查。

---

## 2. 裁定方案 (Decision)

1. **CSS 安全区与包含块解耦（Portal / 脱离 Containing Block）**：
   - 将移动端 `.app-page-header` 的高度规范为 `height: calc(56px + var(--safe-top));`，内边距规范为 `padding: var(--safe-top) max(10px, var(--safe-left)) 0 max(10px, var(--safe-right));`。
   - 对移动端抽屉/浮层 `.header-menu-dropdown`，使用 React `createPortal` 挂载到 `document.body`（或通过脱离 backdrop-filter 容器）消除包含块陷阱，真正基于视口底部 `bottom: 0` 弹出，保留底边距 `calc(20px + var(--safe-bottom))`，最大高度 `max-height: calc(85vh - var(--safe-top));` 并开启 `overflow-y: auto`。

2. **样式设计系统统一**：
   - 彻底移除 `AppHeader.tsx` 里的 `style={{ color: 'var(--text-color, ...)' }}` 内联样式；
   - 菜单项统一定义在 `.menu-item-btn` / `.menu-logout-btn` 中，继承 `color: var(--ink)` 与 `hover: var(--surface-muted)`。

3. **双轨 Service Worker 更新架构（自动轮询 + 手动主动探测）**：
   - 自动轨：监听 `window.addEventListener('pageshow', ...)`、`visibilitychange`，在 PWA 从后台被切回时立刻执行 `registration.update()`；
   - 手动轨：在功能菜单中暴露当前状态及「检查更新」按钮，触发 `registration.update()`。若发现新 Worker 安装完成则弹出更新提示，若无更新则 Toast 提示「当前已是最新版本」。

---

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：修改 `apple-mobile-web-app-status-bar-style` 为 `default`（黑字白底系统栏）**
  - *否决理由*：`default` 会使状态栏强制为系统白底/黑底分块条，破坏沉浸式阅读与深色主题无缝体验；且无法解决刘海/灵动岛区域的安全区避让问题。
- **备选方案 B：引入第三方复杂 PWA 更新浮窗组件**
  - *否决理由*：增加包体积与多余抽象（违背工程整洁度与复杂度惩罚原则），当前极简原生 SW 机制完全可自闭环。

---

## 4. 后果与权衡 (Consequences & Trade-offs)

- **正面收益**：
  - 彻底解决 iOS 全系列机型（刘海屏、灵动岛、普通屏）下 PWA 状态栏遮挡与文字看不清的问题。
  - 用户可随时在菜单中一键检查更新，彻底解决“更新了服务端但手机客户端死活不变”的痛点。
- **负面代价**：
  - 需要更新现有的自动化测试与 Header / PWA 组件快照测试。
