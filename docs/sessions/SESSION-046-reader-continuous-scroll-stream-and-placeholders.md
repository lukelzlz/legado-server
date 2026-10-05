---
id: SESSION-046
title: 审查与加固 PR #42：连续滚动阅读统一为连续流 + 等高占位架构
date: 2026-10-05
author: Agent
tags: [reader, continuous-scroll, virtual-scroll, placeholder, ios-webkit, pr-review, worktree]
branch: feat/reader-continuous-scroll-stream
worktree: ../joyful-galileo-pr42
---

# SESSION-046: 审查与加固 PR #42：连续滚动阅读统一为连续流 + 等高占位架构

## 1. 现象与需求背景 (Investigation & Analysis)

- **用户报障 (Issue #40)**：
  1. 连续滚动时存在明显滑动跳动感；
  2. 读完一章结尾继续往下滚进入下一章后，**再往上滑看上一章内容时，会直接跳回上一章开头**，无法停留在上一章末尾。
- **历史尝试分流与定调转向**：
  - 前序 PR #41 曾尝试通过 UA 嗅探分流为 `keep`（WebKit 只追加不摘章）与 `window`（Blink 三章平移窗口）双策略；
  - 用户在 Issue #40 中明确拍板定调：“**算了都改成连续流**”；
  - PR #42 彻底废弃内核分流与双策略，删除 `readerScrollWindow.ts` 与 `readerScrollStrategy.ts`，将两端收敛为**唯一一套基于等高占位的连续流引擎**。

---

## 2. 核心架构与正确解法 (Architecture & Solution)

### 2.1 等高占位块 (Equal-Height Placeholders)
- 视口上方最多保留 `SCROLL_KEEP_ABOVE = 3` 章正文，更上方的章节收拢为 `<div data-scroll-placeholder="true" className="reading-scroll-placeholder" style={{ height: ...px, containIntrinsicSize: ...px }} />`。
- 占位块高度**严格使用正文在收起前量出的精确像素高度**；占位块样式声明 `contain: layout style paint; overflow: hidden;`，完全脱离浏览器的样式重排与重绘管道。
- **零补偿根因**：因为等高占位精确撑住了被收起章节的原有高度，**文档总高度自始至终不发生任何收缩或坍塌**，彻底免除了对浏览器脆弱滚动锚定和应用层繁复 `scrollTo` 补偿的依赖。

### 2.2 向上滚回即时还原 (Synchronous Unfolding)
- 向上滑回已收起的章节时，`planPlaceholders` 判定目标进入保留区（`toText`）。
- 若正文在预加载缓存（`preloadedContentRef`）中，**同一帧内同步还原为正文字符串**；由于正文高度与占位块高度完全一致，视口不发生任何跳变。

### 2.3 双向缓冲预载 (Bi-directional Buffer)
- 落实用户诉求：“不要只加载看的这一章节，上下加载 2 章”。
- 以当前可视章节为中心，上下各预载 `SCROLL_NEIGHBOUR_BUFFER = 2` 章，使正文在跨过章界前已在本地内存就绪，彻底避免跨章瞬间出现骨架/空白导致的高度抖动。

### 2.4 WebKit 排版右侧留白修复
- 移除 `.reading-content p` 上的 `text-wrap: pretty`：该属性在 WebKit 引擎下会导致中文段落提前换行（行盒 266px / 段落 365px，右侧反常留白约 40px）。
- 中文正文依赖原生 `text-align: justify` 即可实现完美的左右对齐。

---

## 3. 曾尝试过的无效方案与踩坑教训 (Failed Attempts & Pitfalls)

1. **三章窗口摘章平移法**：
   - *失败原因*：在跨章那一帧从视口上方硬性移除上一章，导致文档高度一帧内暴跌数千像素。浏览器底层强制钳制 `scrollY`，使得任何延后计算的 layout effect 补偿全部失效。
2. **内核双策略分支 (PR #41)**：
   - *失败原因*：UA 嗅探容易将 Edge/安卓 WebView（含 AppleWebKit 字符串）误判，且维护两套截然不同的挂载模型造成严重的架构膨胀。
3. **未测章节 100vh 骨架占位陷阱**：
   - *失败原因*：未量过高度的章节若以 100vh（约 900px）撑在文档上方，读者向上滚被量到真实高度（约 8500px）时，文档一帧暴涨数千像素，读者正在看的内容被整体向下推走，观感正是“直接跳到上一章开头”。
   - *正解*：未量过高度的章节严禁使用 0 高度占位，且收起估算必须取最近已测邻居（`estimateChapterHeight`）。
4. **JS `slice(-keepAbove)` 边界 Bug**：
   - 当 `keepAbove = 0` 时，`array.slice(-0)` 等价于 `array.slice(0)`，导致全量保留而不是清空。必须显式守卫 `keepAbove <= 0 ? [] : above.slice(...)`。

---

## 4. 验证与审查结论 (Verification & Verdict)

- **类型检查与前端单测**：
  - `npm --prefix web run check`：通过。
  - `npx tsx web/test/run-all.ts`：**208 项全绿**。
- **服务端 JVM 单测**：
  - `./gradlew :server:test`：全绿，零回归。
- **联调运行验证**：
  - 本地 worktree 实例 `http://127.0.0.1:8080` 正常响应，静态资源正确返回最新 bundle `index-Bb0xyR1P.js`。
- **复杂度与整洁度**：
  - 删除了 `readerScrollWindow.ts`、`readerScrollStrategy.ts`，代码逻辑大幅精简，完全符合复杂度惩罚原则。
