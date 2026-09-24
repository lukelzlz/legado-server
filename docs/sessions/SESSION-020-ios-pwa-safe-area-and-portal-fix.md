---
id: SESSION-020
title: 修复 iOS PWA 安全区被覆盖、Backdrop-Filter 包含块陷阱与主题色彩隐形
date: 2026-09-24
author: Agent
tags: [pwa, ios, safe-area, css-containing-block, react-portal, sw-update]
---

# SESSION-020: 修复 iOS PWA 安全区被覆盖、Backdrop-Filter 包含块陷阱与主题色彩隐形

## 1. 现象与排查推演 (Investigation & Analysis)

### 现象 1：手机端功能菜单超出屏幕上方，只露出最底部一行按钮
- **排查推演**：
  1. 手机端 `@media (max-width: 720px)` 下，`.header-menu-dropdown` 定义了 `position: fixed; bottom: 0; left: 0; right: 0;`，意图作为底部抽屉弹出。
  2. 但其父容器 `.app-page-header` 上声明了 `backdrop-filter: blur(16px)`（以及 `-webkit-backdrop-filter: blur(16px)`）。
  3. **W3C 规范**：任何带有 `filter`、`backdrop-filter`、`transform` 或 `perspective` 的元素均会为其子代创建一个全新的包含块（Containing Block），捕获所有的 `position: fixed` 元素。
  4. 导致 `.header-menu-dropdown` 的 `bottom: 0` 对齐的是 56px 高的顶栏底部边缘，整个 350px 高的面板被向上反向拉伸（坐标从 -300px 到 +56px），除最底下一条外全被裁切在手机屏幕上方！

### 现象 2：手机端顶栏与 iOS 状态栏（灵动岛/刘海/时间）重叠
- **排查推演**：
  1. `:root` 中已定义 `--safe-top: env(safe-area-inset-top, 0px)`，在桌面端 `.app-page-header` 使用了 `height: calc(62px + var(--safe-top))` 与 `padding-top: var(--safe-top)`。
  2. 但在 `@media (max-width: 720px)` 媒体查询中，`.app-page-header` 被硬编码重写为 `height: 56px; padding: 0 10px;`，直接冲掉了 `var(--safe-top)`。
  3. 在 iOS 独立全屏 PWA 模式（`black-translucent`）下，页面顶栏直接贴顶 y=0，与系统状态栏完全重叠。

### 现象 3：浅色模式下菜单多项功能文字不可见
- **排查推演**：
  1. `AppHeader.tsx` 内联样式使用了 `style={{ color: 'var(--text-color, #e6e8eb)' }}`。
  2. 系统全局 CSS 变量使用的是 `--ink` 而非 `--text-color`。
  3. 在「晓白」和「护眼」浅色白底背景下，文字回退至接近白色的 `#e6e8eb`，导致文字隐形，用户误以为选项丢失。

### 现象 4：iOS PWA 无法收到更新
- **排查推演**：
  1. iOS Safari 在 PWA Standalone 模式下具有独立的挂起机制，后台被动 `updatefound` 容易错过。
  2. 缺乏前台激活（`pageshow` / `online`）的主动探测，且菜单中无手动触发通道与版本 Toast 反馈。

---

## 2. 最终落地的正确解法 (Final Solution)

1. **React `createPortal` 脱离包含块限制**：
   - 在 `AppHeader.tsx` 中使用 `canUseDOM ? createPortal(menuDropdownContent, document.body) : menuDropdownContent`，将菜单抽屉与遮罩直接挂载至 `document.body`。
   - 彻底摆脱 `.app-page-header` 的 `backdrop-filter` 包含块限制，手机端 `position: fixed; bottom: 0` 准确对齐视口底部，并配置 `max-height: calc(85vh - var(--safe-top)); overflow-y: auto;`。
2. **安全区级联保留**：
   - 修正 `@media (max-width: 720px)` 中 `.app-page-header` 的样式，保留 `height: calc(56px + var(--safe-top)); padding-top: var(--safe-top); padding-left: max(10px, var(--safe-left)); padding-right: max(10px, var(--safe-right));`。
3. **设计系统语义色彩重构**：
   - 移除所有 `--text-color: #e6e8eb` 内联样式，统一使用 `.menu-item-btn`，继承 `color: var(--ink)` 与 `hover: var(--surface-muted)`。
4. **双轨 PWA 更新机制**：
   - `PwaManager.tsx` 增加 `pageshow` 与 `online` 事件监听，并导出 `checkForAppUpdate()`。
   - `AppHeader.tsx` 菜单中新增「检查应用更新」交互按钮与 Toast 实时反馈。

---

## 3. 沉淀的教训与部落知识 (Lessons Learned & Tribal Knowledge)

- **[CSS/包含块] `backdrop-filter` 会破坏 `position: fixed` 的视口锚定**：任何放在带 `backdrop-filter` 或 `transform` 容器内的弹窗/抽屉（即便写了 `position: fixed`）都会被局限在该容器内。全局弹层与抽屉必须通过 `createPortal(..., document.body)` 挂载到根节点。
- **[CSS/移动端媒体查询] 严禁在 `@media` 中简写覆盖根级 `safe-area`**：移动端媒体查询调整 `height` / `padding` 时，必须保留 `var(--safe-top)` / `var(--safe-bottom)` 计算式，杜绝硬编码 `padding: 0` 冲掉全面屏死区。
