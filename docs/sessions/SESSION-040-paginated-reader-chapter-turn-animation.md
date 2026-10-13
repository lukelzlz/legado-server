---
id: SESSION-040
title: 横向分页跨章翻页动画优化与隔离审查 (PR #31)
date: 2026-10-03
author: Agent & wfanan
tags: [reader, pagination, animation, worktree, doc-review]
---

# SESSION-040: 横向分页跨章翻页动画优化与隔离审查 (PR #31)

## 1. 现象与根因分析 (Investigation & Root Cause)

- **缺陷现象**：
  在横向分页阅读模式（`paginate`）下，读者读到本章最后一页往后翻时，直观感受是新章节内容换上了，但整个页面动画却“倒退”回了第一页（从右向左弹回），方向与手势相反，极具违和感。

- **代码根因**：
  分页位移由内层轨道计算：
  ```jsx
  <div className="reader-paginated-track" style={{ transform: `translateX(-${pageIndex * stride}px)` }}>
  ```
  轨道自身定义了平滑过渡：
  ```css
  .reader-paginated-track {
    transition: transform 0.28s cubic-bezier(0.25, 1, 0.5, 1);
  }
  ```
  跨章翻页时，`changeChapter` 先触发换章，新章正文加载并量算分页后，`measurePagination` 把 `pageIndex` 从本章末页（例如 N=5）瞬时重设为 `0`。由于轨道带有 `0.28s` 的 CSS 过渡，浏览器会播放一段从 `-5*stride` 倒退平移到 `0` 的过渡动画。

---

## 2. 核心架构解法 (Architecture & Solution)

PR #31 提出了正交双层解耦方案：

### (1) 内层轨道瞬时归位
在跨章期间给内层轨道添加 `.is-turning` 类名，显式声明 `transition: none`：
```css
.reader-paginated-track.is-turning { transition: none; }
```
当新章内容就位、`pageIndex` 归零时，轨道瞬时完成位置重置，不触发任何反向动画。

### (2) 外层容器方向性滑入
外层增加 `.reader-chapter-turn` 过渡层，由三段状态机驱动（`pending` → `in` → `null`）：
- **向前翻（上一章）**：`.is-in.is-prev` 触发 `@keyframes reader-turn-in-prev`，从左侧 `translateX(-100%)` 顺滑滑入；
- **向后翻（下一章）**：`.is-in.is-next` 触发 `@keyframes reader-turn-in-next`，从右侧 `translateX(100%)` 顺滑滑入；
- 动画耗时 200ms，动画结束通过 `onAnimationEnd` 将状态复位为 `null`。

---

## 3. 关键边界防御与红蓝对抗要点 (Defensive Engineering)

1. **阻断排版闭包依赖（`chapterTurnPendingRef`）**：
   若将 `chapterTurn` 状态放入 `measurePagination`，每次切章过渡都会导致回调重建与 `useLayoutEffect` 重复排版。通过引入 `chapterTurnPendingRef`，在量算完成时触发 `setChapterTurn('in')`，阻断了状态依赖环。
2. **4 秒超时看门狗**：
   若新章正文因网络超时或上游源失效未能就绪，排版永远不会发生。加入 `window.setTimeout(..., 4000)` 兜底，超时自动清除 `pending` 状态，避免内层轨道永久带着 `transition: none` 导致翻页过渡丢失。
3. **减弱动画（`prefers-reduced-motion`）事件完备性**：
   减弱动画模式下**严禁写 `animation: none`**（否则浏览器不派发 `animationend` 事件导致状态机卡死），必须写 `animation-duration: 1ms`，既满足用户的减弱动画诉求，又保证了 DOM 事件回调链正常触发与状态正常复位。
4. **CSS 类名契约反漂移单测**：
   在自动化测试 `ReaderPagination.test.ts` 中直接读取真实的 `styles.css`，断言 `chapterTurnClassName` 产生的所有选择器、关键帧方向（`+100%` vs `-100%`）与 `transition: none` 真实存在，杜绝死配置。

---

## 4. 经验沉淀与部落知识 (Tribal Knowledge)

- **[阅读器/翻页动画] 跨章翻页采用双层解耦架构，内层轨道抑制过渡防倒退，外层容器沿手势滑入**：横向翻页跨章时 `pageIndex` 从末页瞬设为 0 会导致轨道反向平移动画（弹回第一页）。必须在跨章期间给内层轨道添加 `transition: none`，并由外层容器播放沿手势前进方向（下一章从 `+100%` 滑入、上一章从 `-100%` 滑入）的过渡动画。
- **[CSS/无障碍] prefers-reduced-motion 下依赖 animationend 推进状态机的容器必须设 duration: 1ms 严禁设 none**：CSS 中若设置 `animation: none`，浏览器不会派发 `animationend` 事件，依赖该事件将状态复位的 React 状态机将被永久卡死；必须使用 `animation-duration: 1ms` 兼顾无障碍极速生效与事件回调完备。
- **[Git/工程安全] 审查外部 PR 必须在独立 git worktree 中执行**：本地主工作区常常带有未提交的代码改动或草稿，直接 `git checkout` 会导致本地改动被覆盖、工作区污染甚至构建产物冲突。必须使用 `git worktree add ../<dir> <branch>` 在纯净独立环境中测试与审查。
