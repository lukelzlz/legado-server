---
id: ADR-027
title: 阅读器多轨翻页动画管线、Pointer 触控跟手与 CSS3D/Canvas 拟真翻页架构
status: accepted
date: 2026-10-03
---

# ADR-027: 阅读器多轨翻页动画管线、Pointer 触控跟手与 CSS3D/Canvas 拟真翻页架构

## 1. 决策背景 (Context)
目前 Web 阅读器在分页模式下仅有单层 DOM 的静态切页。要支持“覆盖”、“滑动”、“仿真”、“滚动”、“渐隐”5 种不同机制的动画，并实现媲美原生 App 的高帧率“触控跟随拖拽（Follow-through dragging）”，需要重构分页阅读器的视口层叠结构与手势管理管线。

难点在于：
1. **触控手势跟手状态机**：跟手拖拽需要毫秒级响应，拖拽期间如果频繁触发 React `setState` 会引发频繁 Re-render，导致手势掉帧卡顿。
2. **多层虚拟页面调度**：翻页动作必须同时准备好“当前页（Current）”、“目标页（Target: 下一页或上一页）”甚至“底衬页（Backing）”，保证翻动中途露出的内容绝对精准无白屏。
3. **仿真翻页（Curl）技术选型**：纯 DOM 翻页无法简单实现非线性弯曲弧度，需要权衡 CSS 3D 变形与 2D Canvas/WebGL 贝塞尔曲面算法。

## 2. 裁定方案 (Decision)

### 2.1 翻页模式类型定义与持久化扩展
将 `ReaderPageMode` 升级为联合类型：
```typescript
export type ReaderPageTurnStyle = 'cover' | 'slide' | 'curl' | 'scroll' | 'fade'
```
在 `ReaderSettings` 中新增 `pageTurnStyle: ReaderPageTurnStyle`，默认 `'slide'`（或根据原 `pageMode === 'scroll'` 映射），无缝保持向下兼容。

### 2.2 双层视口容器 (Dual-Buffer Viewport Pipeline)
在分页渲染模式下，构建“底层页面（Back Layer）”与“顶层活动页面（Active Dragging Layer）”：
- **覆盖 (Cover)**：
  - 向后翻：底层为当前页，顶层从右侧 `translate3d(X, 0, 0)` 带着投影覆盖入场。
  - 向前翻：顶层为当前页，手指右滑时直接将顶层滑出揭开，露出底层的上一页。
- **滑动 (Slide)**：
  - 顶层和底层并排联动，整体容器平移 `translate3d(X, 0, 0)`。
- **渐隐 (Fade)**：
  - 不做物理位移，顶层当前页 `opacity: 1 - progress`，底层新页 `opacity: progress`。
- **仿真 (Curl)**：
  - 采用轻量化高效 CSS 3D 卷页折叠（结合 `transform-origin`、`perspective`、`rotateY` 以及动态双重渐变阴影 `linear-gradient`）或 Canvas 卷角投影，既保证纸张翻折的拟真立体感与背光反光，又避免离屏文本重渲染导致的清晰度损失。

### 2.3 高性能 Direct-DOM 触控跟手驱动 (Touch-Tracking Engine)
- **手势捕获**：基于 `PointerEvents`（支持触控屏单指拖拽与桌面鼠标轻扫拖拽）。
- **零 Re-render 跟手更新**：在 `pointermove` 过程中，**绝不调用 React `setState`**，而是直接通过 `ref` 操作两个图层的 DOM `style.transform` / `style.opacity`，保证拖拽跟手率始终锁定在 60~120 FPS 满帧。
- **惯性与决胜结算 (Settling)**：
  - 在 `pointerup` / `pointercancel` 时，根据位移比例（`deltaX / width > 0.2`）与滑动速度（`velocity > 0.4px/ms`）判断是否达成翻页。
  - 达成翻页时，为 DOM 添加带有 `cubic-bezier(0.2, 0.9, 0.3, 1.0)` 的 transition 平滑吸附至终点。
  - transition 结束（`transitionend`）后，仅触发一次 React 状态更新（`setPageIndex`），重置双层缓冲，完成切页。

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：引入大型第三方翻页库（如 StPageFlip / Turn.js）**
  - *否决理由*：此类库体积庞大（100KB+），且强依赖全局 DOM 操作或老旧技术，与 React 19 的 Virtual DOM 及响应式状态容易冲突；且大多只支持“仿真”单一效果，无法统一支撑覆盖、滑动、滚动、渐隐等多轨动画。违反工程复杂度惩罚原则。
- **备选方案 B：每次手势 `pointermove` 均触发 React `setState({ dragPercent })`**
  - *否决理由*：高刷屏幕上手势移动每秒产生 120 次事件，触发 120 次 React 完整组件树 Reconciliation，导致大章节文本大量计算，严重卡顿掉帧。必须采用 Direct-DOM 模式。

## 4. 后果与权衡 (Consequences & Trade-offs)
- **正面收益**：
  - 体验与 Legado 原生 Android 端及主流阅读 App 100% 对齐。
  - 覆盖、滑动、仿真、渐隐在手势下均有丝滑跟手与弹性回弹。
  - 核心逻辑高度自研收敛，零外部重量级依赖，体积轻量，纯硬件加速合成。
- **负面代价**：
  - 需要在分页组件中维护前一页、当前页、后一页的快速布局提取，保证换章交界处的平滑衔接。
