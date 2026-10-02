---
id: ADR-026
title: 连续滚动模式采用章节流追加与视口虚拟窗口架构
status: accepted
date: 2026-10-02
---

# ADR-026: 连续滚动模式采用章节流追加与视口虚拟窗口架构

## 1. 决策背景 (Context)

原有的连续滚动阅读模式仅维护单个 `chapterIndex` 与单个 `content`，当读者滚动到一章末尾时，滚动条受限于当前章节内容高度无法继续向下，必须依靠点击换章按钮重新将视口跳转回新章节顶部。
这种模式打断了读者的沉浸感，与用户习惯的 Twitter / 社交媒体瀑布屏流式加载体验存在明显差距。

## 2. 裁定方案 (Decision)

在前端 Web 客户端（`web/src/ReaderScreen.tsx`）中重构连续滚动模式的渲染模型：

1. **章节流数据结构（Stream State）**：
   维护一个响应式章节流 `streamChapters: StreamChapterItem[]`，每个条目包含：
   ```ts
   type StreamChapterItem = {
     index: number
     chapter: Chapter
     content: string
     paragraphs: string[]
     loading: boolean
     error?: string
   }
   ```
2. **滚动预载与静默追加（Scroll Sentinel & Infinite Append）**：
   在流的末端挂载一个轻量 Sentinel 哨兵元素（或基于 `scrollY + innerHeight >= scrollHeight - 800`）。当进入预检阈值时，自动触发并发发起的预载流水线，直接将下一章作为新条目追加到 `streamChapters` 末尾，实现无缝瀑布流。
3. **滚动视口锚定与虚拟窗口（Active Chapter & Virtual Windowing）**：
   利用 `IntersectionObserver` 监听各章节的根节点（`section[data-chapter-index]`），动态得出当前在视口中最占据主要面积的激活章节（`activeChapterIndex`）：
   - 动态更新当前章节标题、进度百分比、底栏滑块及页面标题；
   - 进度防抖保存至服务端。
   - 当流中章节数量超过保留阈值（如 > 4 章）时，对远离视口的章节段落以固定测量高度（`style={{ minHeight }}`）占位，保护 DOM 节点规模。
4. **平移分页模式保持单章模型（Mode Isolation）**：
   平移分页模式依赖精准的多栏排版计算（`calculatePaginationLayout`），继续沿用经典的单章分页模型，与流式滚动严格解耦。

## 3. 备选方案与权衡 (Trade-offs & Alternatives Considered)

- **备选方案 A：直接把所有章节文字拼成一个超级长字符串 `content = c1 + "\n" + c2`**
  - *否决理由*：极其粗糙。段落与章节边界模糊，无法单独为不同章节渲染标题，无法做虚拟化卸载，且单章段落索引与 TTS 映射完全混乱。
- **备选方案 B：引入第三方长列表库（如 react-window / virtua）**
  - *否决理由*：第三方虚拟列表库强制要求单项高度或固定容器滚动，破坏浏览器的原生平滑滚动、安全区适配与长按选词交互，带来极高的复杂度惩罚。
- **选定方案：自主受控章节流（Stream List of Chapter Sections）**
  - *入选理由*：零外部依赖，完全契合浏览器原生 `window.scrollY`，与既有 CSS 变量和多语言系统 100% 融合，章节交界处天然支持精美分隔与标题呈现。
