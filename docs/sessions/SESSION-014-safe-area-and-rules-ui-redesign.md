# SESSION-014: 移动端全面屏死区深度适配与替换净化规则 UI 体系化重构

## 1. 任务背景与核心痛点
- **移动端全面屏死区严重遮挡**：在 iPhone（灵动岛/刘海屏）及主流全面屏 Android 机型上，由于 CSS 媒体查询在 `@media (max-width: 720px)` 下覆盖重置了 `reader-header` 的高度为 `56px` 与 `padding: 0 10px`，导致系统状态栏图标（时间、信号、电池）直接重叠在正文标题与顶栏按钮上；底部快捷条 `mobile-reader-nav` 缺少安全区底部内边距，紧贴系统手势条（Home Indicator）造成触控冲突；
- **全局顶栏 safe-area 被覆盖**：全局 `app-page-header` 中 `padding: 0 clamp(...)` 写在 `padding-top: var(--safe-top)` 之后，导致属性覆盖失效；
- **替换净化规则页面与全局 UI 完全脱节**：旧版 `ReplaceRulesPage.tsx` 与 `ReplaceRulesModal.tsx` 充斥了大量硬编码深色内联样式（`color: #fff`, `background: rgba(255,255,255,0.06)`, `background: #4f46e5` 等），在 Light（浅色）和 Paper（羊皮纸）主题下文字与背景混成一体无法阅读；且在手机端排版挤在一起，体验极差。

---

## 2. 实施细节与架构改进

### 2.1 全局全面屏 Safe-Area 变量继承与防覆盖契约
1. **统一安全区变量计算**：
   - 修复 `.app-page-header`：`height: calc(62px + var(--safe-top))`, `padding-top: var(--safe-top)`, `padding-left/right: max(clamp(16px, 4vw, 48px), var(--safe-left/right))`；
   - 修复 `.reader-header`：在全分辨率及 `@media (max-width: 720px)` 媒体查询下均保持 `height: calc(56px + var(--safe-top))` 与 `padding-top: var(--safe-top)`；
   - 修复 `.mobile-reader-nav`：`height: calc(58px + var(--safe-bottom))`, `padding-bottom: var(--safe-bottom)`；
   - 修复 `.reading-content`：滚动模式顶部内边距计算为 `calc(clamp(64px, 8vw, 88px) + var(--safe-top))`，底部内边距为 `calc(48px + var(--safe-bottom))`；
   - 修复 `.reader-paginated-wrap` 与 `.reader-paginated-viewport`：分页模式容器高度注入 `100dvh` 并上下内嵌 safe-area insets；
   - 修复 `.reader-drawer` 与抽屉/模态框：底部统一注入 `max(12px, var(--safe-bottom))` 避让底部虚拟手势条。

### 2.2 替换净化规则设计系统化 (Design System Tokens)
1. **全面接入设计系统 CSS 变量**：
   - 剔除所有硬编码颜色与深色内联样式，统一采用 `var(--surface)`、`var(--surface-muted)`、`var(--canvas)`、`var(--ink)`、`var(--muted)`、`var(--line)`、`var(--accent)` 等色彩令牌，自适应 Light、Paper、Dark 三大主题；
2. **响应式双模交互（Desktop 左右分栏 + Mobile 折叠卡片流）**：
   - 桌面端（>=768px）：左侧高密度规则列表 + 右侧卡片式规则详情与实时沙箱比对工作区；
   - 移动端（<768px）：垂直流式折叠卡片（Accordion Flow），点击卡片就地展开匹配/替换正则、生效范围、启用开关与编辑/删除操作，并在页面底部提供轻量测试沙箱；
3. **组件与表单体系化重构**：
   - `ReplaceRulesPage.tsx` 与 `ReplaceRulesModal.tsx` 统一样式类名（`.rules-group-pill`、`.rule-list-card`、`.rule-status-badge`、`.rule-tag`、`.rule-modal-card`、`.rule-form-*`）。

---

## 3. 验证与交付
- **静态类型与自动化测试**：
  - `npm --prefix web run check`：0 错误 0 告警；
  - `npx tsx web/test/run-all.ts`：127/127 测试用例全部通过；
  - `./gradlew :server:test`：全量通过；
  - `npm --prefix web run build`：生产环境打包成功。
