---
id: ADR-013
title: 全面屏安全区变量统一继承体系与替换规则设计系统化重构
status: accepted
date: 2026-09-16
---

# ADR-013: 全面屏安全区变量统一继承体系与替换规则设计系统化重构

## 1. 决策背景 (Context)

随着现代智能手机（如 iPhone 14/15/16 灵动岛、全面屏 Android 设备）普及，Web 应用直接以 `viewport-fit=cover` 渲染时，若 CSS 层级中缺少规范的 `env(safe-area-inset-*)` 变量处理或在媒体查询中被粗暴覆盖，会导致物理死区（刘海、打孔、药丸屏、底部手势条）严重遮挡关键 UI 和文字。

同时，此前新增的「替换净化规则」独立页面（PROPOSAL-011）采用了大量临时内联深色硬编码样式，未遵循全局设计令牌（Design Tokens），在多主题（Light/Paper/Dark）和移动端屏幕下出现了严重的视觉与布局割裂。

---

## 2. 裁定方案 (Decision)

### 2.1 全局安全区计算契约 (Safe-Area Inset Contract)
1. **统一根变量注入**：
   ```css
   :root {
     --safe-top: env(safe-area-inset-top, 0px);
     --safe-bottom: env(safe-area-inset-bottom, 0px);
     --safe-left: env(safe-area-inset-left, 0px);
     --safe-right: env(safe-area-inset-right, 0px);
   }
   ```
2. **顶底组件防覆盖规范**：
   - 导航栏与顶栏（`.app-page-header`, `.reader-header`）：统一高度公式为 `calc(56px + var(--safe-top))`，`padding-top: var(--safe-top)`。媒体查询 `@media (max-width: 720px)` 严禁粗暴写死 `height: 56px` 或覆盖清空 `padding-top`。
   - 移动端阅读底栏（`.mobile-reader-nav`）：高度为 `calc(58px + var(--safe-bottom))`，`padding-bottom: var(--safe-bottom)`。
   - 阅读器视口容器（`.reading-content`, `.reader-paginated-viewport`）：正文首行内边距动态叠加 `var(--safe-top)` 与 `var(--safe-bottom)`。
   - 抽屉与模态卡片（`.reader-drawer`, `.modal-card`）：底部统一注入 `padding-bottom: max(16px, var(--safe-bottom))`。

### 2.2 替换净化规则页面设计系统化 (Design System Tokens)
1. **样式去内联化**：剥离所有直接写在 JSX `style={{...}}` 中的颜色与间距，统一写入 `styles.css` 类选择器中。
2. **主题变量统一继承**：
   - 背景：`var(--surface)` / `var(--surface-muted)` / `var(--canvas)`
   - 文字：`var(--ink)` / `var(--muted)`
   - 边框与分割线：`var(--line)`
   - 主色调与高亮：`var(--accent)` / `var(--accent-soft)` / `var(--accent-ink)`
3. **移动端折叠卡片化架构**：
   - 在窄屏下将规则条目渲染为自包含的可展开折叠卡片（Accordion Card），单卡片内集成启用状态、作用域、正则高亮与快速沙箱调试，极大提升移动端操作流畅度。

---

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：仅通过 JS 在 ReaderScreen 动态测量 `window.screen` 并注入内联样式**
  - *否决理由*：JS 计算容易引发初始渲染闪烁（FOUC），且无法优雅兼容横竖屏旋转和浏览器全屏状态切换。纯 CSS `env(safe-area-inset-*)` 由浏览器原生合成线程管理，性能最高且无闪烁。
- **备选方案 B：规则页面在移动端做成独立路由跳转的子页面**
  - *否决理由*：增加了前端路由与状态维护复杂度，折叠流式卡片（Accordion Flow）即可完美满足查看、编辑与沙箱调试的全部诉求。

---

## 4. 后果与权衡 (Consequences & Trade-offs)

- **正面收益**：
  - 全面屏移动端沉浸感与可读性大幅跃升，彻底根除遮挡问题。
  - 替换净化规则页面在 Light、Paper、Dark 三种主题下视觉完全统一，精致度与易用性大幅提升。
- **负面代价/注意事项**：
  - 需要在不同尺寸的模拟器/移动端分辨率下严格验证翻页模式（Paginated）的分栏高度计算，确保文字不因安全区预留而产生多余的上下滚动条。
