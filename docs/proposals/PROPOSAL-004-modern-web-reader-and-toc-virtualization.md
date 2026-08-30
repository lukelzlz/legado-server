---
id: PROPOSAL-004
title: 现代化 Web 阅读器、超大目录虚拟化与双栏宽屏排版
status: implemented
author: Legado Server Team
date: 2026-08-28
---

# PROPOSAL-004: 现代化 Web 阅读器、超大目录虚拟化与双栏宽屏排版

## 1. 业务背景与问题痛点
网络长篇小说往往包含数千甚至数万个章节（如 50,000+ 章长篇或同人合集）。
在传统的 Web 阅读器实现中，存在两个致命体验问题：
1. **开书请求级联风暴**：点击书籍时，前端一次性对聚合卡片中的 40+ 个候补书源发起详情与目录请求，造成网络拥塞与首屏长达数秒的白屏。
2. **超大目录 DOM 爆炸与掉帧**：全量渲染 5,000~50,000 个 DOM 目录节点导致浏览器内存暴增、滚动掉帧甚至标签页崩溃。
3. **大屏桌面端排版适配差**：在 2K/4K 宽屏显示器下，页面单列撑满或固定在手机比例，缺乏双栏翻页与自适应版心。

## 2. 目标与非目标 (Goals & Non-Goals)

### 核心目标 (Goals)
1. **单书源直连秒开与候补源懒加载 (Lazy Candidate Loading)**：开书时仅拉取主书源（`sources[0]`），其余候补书源保持 `idle` 状态；仅当用户展开源选择列表或切换书源时才按需拉取。
2. **大目录虚拟化滚动（`VirtualChapterList`）**：在可视区域内仅挂载约 25 个 DOM 节点，无论目录有 50 章还是 50,000 章，内存占用保持恒定，支持平滑定位与当前章节居中对齐。
3. **阅读器段落 Memoization 与滚动节流**：段落切分与样式计算采用缓存复用，滚动监听节流，防止重排与 Layout Thrashing。
4. **全端自适应双栏/宽屏与跨章逆向定位**：
   - 桌面端支持单栏/双栏自适应切换、版心宽度调节与侧边栏钉选。
   - 跨章逆向翻页（上一章）实现 `targetPosition = 'bottom'` 锚定状态机，确保在异步排版完成后精准定位到章节末尾。

### 非目标 (Non-Goals)
- 不在前端做重度复杂的富文本排版（如手写排版引擎），优先依赖现代化 CSS 现代特性与 CSS Columns 分栏。

## 3. 核心用户故事 (User Stories)

- **Story 1（秒开超长目录小说）**：作为用户，点击一本有 20,000 章的小说，阅读器在 0.3 秒内直出首章正文，打开目录弹窗时无任何卡顿，当前正在阅读的章节自动居中高亮。
- **Story 2（桌面端宽屏双栏阅读）**：作为 PC 用户，在 27 寸 4K 显示器上全屏阅读时，内容自动以优雅的左右双页/双栏形式呈现，支持左右键盘按键或点击翻页。
- **Story 3（逆向跨章连续翻页）**：作为用户，在阅读第 15 章开头时点击“上一章”或向上滑动，系统加载第 14 章并精准定位在第 14 章的最后一页，无缝衔接阅读上下文。

## 4. 技术实现架构 (Technical Architecture)

```mermaid
graph TD
    BookOpen[用户点击打开书籍] --> MainSourceOnly[仅加载主书源 sources[0]]
    MainSourceOnly --> RenderReader[毫秒级渲染阅读器 ReaderScreen.tsx]
    
    subgraph 虚拟化目录 VirtualChapterList
        TOCData[50,000+ 章节数组] --> WindowCalc[根据 scrollTop 计算可视窗口 startIndex...endIndex]
        WindowCalc --> Render25Nodes[仅渲染可视区 ~25 个 DOM 节点]
        Render25Nodes --> TopSpacer[Top Spacer 占位撑高]
        Render25Nodes --> BottomSpacer[Bottom Spacer 占位撑高]
    end

    subgraph 逆向翻页定位状态机
        PrevChapter[触发上一章] --> SetBottomFlag[targetPosition = 'bottom']
        SetBottomFlag --> FetchContent[加载正文]
        FetchContent --> LayoutDone[DOM / 分栏异步排版完成]
        LayoutDone --> AnchorScroll[精确执行 scrollToBottom / jumpToLastPage]
    end
```

## 5. 验收基准 (Acceptance Criteria)
- [x] 50,000 章节超长目录在 `npx tsx web/test/run-all.ts` 场景测试中虚拟化滚动时间 < 5ms。
- [x] 开书时网络抓包确认候补书源 API 请求为 0，仅主书源 1 次请求。
- [x] 跨章逆向翻页单测与 UI 交互均精准定位至上一章末尾。
