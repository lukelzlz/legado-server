---
id: SESSION-041
title: 审查、加固与合入 PR #33：修复阅读进度退出再进跳章、连续滚动恢复落点与备份完结判定
date: 2026-10-03
author: Agent
tags: [reader, progress, scroll, backup, review, merge]
branch: main
pr: 33
---

# SESSION-041: 审查、加固与合入 PR #33：修复阅读进度退出再进跳章、连续滚动恢复落点与备份完结判定

## 1. 背景与审查上下文 (Context)
外部 PR #33（作者：`wfanan`）提交修复：
`fix(reader): 修复阅读进度「退出再进入往回跳好几章」+ 恢复位置不再停在上一章`。
按照 `doc-review` 宪法守卫，开辟独立工作树 `../joyful-galileo-pr33` 进行纯净检出与深度对抗审查。

PR #33 初始包含 3 个 Commit：
1. `f7f0588e`: `fix(reader): 切章后进度不再写「旧章 + 新位置」的错配，退出再进入不再跳章`
2. `b03f05e9`: `fix(reader): 恢复位置真正落位（不再停在上一章），往上滚也能接上前一章`
3. `98710af1`: `fix(backup): 导入不再判定「已读完」，只导书籍与进度；完结与否照导入`

---

## 2. 深入审查推演与红蓝对抗 (Skeptic Audit)

### 2.1 Commit 1 (`f7f0588e`) 审查结论：【通过 / 设计精妙】
- **现象**：滑块或连点下一章切到 100 章后立刻退出，服务端保存的却是第 2 章。
- **根因**：`currentRef.current.chapter` 过去只在正文异步到达后由 `applyContent` 写入；而翻页/滚动等实时交互在不断更新 `currentRef.current.position`。切章后若正文尚未返回（如慢速网络或大章节耗时 2~10s），此时触发保存（防抖 1.2s、点返回书架、页面切到后台），写入服务端的便是「旧章节 + 新位置」的致命错配。
- **解法**：增加以 `chapter` 为依赖的快速响应 `useEffect`：只要 `chapter.index` 变更且不同于当前 ref，立即将 `currentRef.current` 锁定到 `{ chapter, position: 0 }`。
- **向下兼容与边界防线**：在连续滚动模式的 `shiftScrollWindow` 路径中，由于已提前将 ref 置为 target 章，该 effect 判定 `current?.chapter.index === chapter.index` 自动跳过，完美保留了平移所需的视口位置补偿。

### 2.2 Commit 3 (`98710af1`) 审查结论：【通过 / 业务语义对齐】
- **现象**：从开源阅读备份导入书籍后，很多书莫名其妙被标成了“已读完”，且再次导入会覆盖用户自己手动标的阅读状态。
- **根因**：开源阅读书源生态中，`kind` 描述的是小说连载状态（如“连载中” vs “已完结”），代表作者是否写完；而本系统书架表中的 `completed` 字段表示“**读者是否已读完**”（标记后按钮变“重新阅读”、进度显示“已读完”）。原实现粗暴地将 `kind.contains("完结")` 映射为 `completed`，不仅语义完全张冠李戴，且 UPSERT 使用 `completed = excluded.completed` 无条件覆盖了用户的手工标记。
- **解法**：导入解析时恒置 `completed = false`，数据库 `importLibrary` 冲突时保留库内原值 `completed = book_shelf.completed`，新增图书默认未读完（0）。

### 2.3 Commit 2 (`b03f05e9`) 审查结论：【挑出首末章假死高危缺陷并推动修复】
- **原 PR 实现**：
  为解决连续滚动在恢复位置时相邻章未入 DOM 导致 `scrollTo(目标)` 被浏览器钳制为 0 的问题，PR 引入了 `requestAnimationFrame` 循环重试机制，并定义：
  ```tsx
  const expected = Math.min(3, currentBook.chapters.length)
  const rendered = document.querySelectorAll('.reading-scroll-window > .reading-content').length
  const complete = rendered >= expected
  const reached = desired === null || (complete && window.scrollY + 2 >= desired)
  if (!reached && frames < 600) rafId = window.requestAnimationFrame(retry)
  ```
- **Skeptic 挑刺发现的高危 Bug**：
  1. **首末章 10 秒死锁抢锁（Scroll-Lock）**：
     在连续滚动视口中，**首章（`chapterIndex === 0`）没有上一章**，视口内最多只能渲染 `[当前章, 下一章]` 共 2 段；同理**末章（`chapterIndex === length - 1`）没有下一章**，最多也只能渲染 2 段。
     然而 `expected` 被死板地硬编码为 `Math.min(3, currentBook.chapters.length)`（对于常规多章节书籍恒等于 3）。
     导致首章或末章进入时：`rendered <= 2`，`complete = 2 >= 3` **恒为 false**！
     `retry` 将直接跑满 **600 帧（按 60Hz 屏幕整整 10 秒钟）**！在这 10 秒内，每一帧都在死循环调用 `window.scrollTo({ top: targetScroll, behavior: 'auto' })`！
     用户在首章或末章打开书籍后如果试图滑动屏幕，视口每 16ms 就会被强行拽回顶部，表现为**极其严重的假死/冻结 10 秒**！
  2. **缺少用户主动操作的即时让路机制**：
     在重试期间，若用户已手动触摸滑动（touch/wheel/pointerdown），重试定时器未做任何监听，仍会持续执行 `scrollTo` 与用户抢夺视口滚动条。

---

## 3. 作者反馈与落地闭环 (Author Feedback & Final Merge)

- 评审者通过 GitHub Review 直接向 PR #33 提出了详尽的分析与建议修复代码。
- 作者快速响应并完全采纳评审意见，推送了 Commit `cc0723a`：
  1. **首末章段数动态判定**：`expected = 1 + (hasPrev ? 1 : 0) + (hasNext ? 1 : 0)`；
  2. **手势与滚轮主动让路守卫**：注册 `wheel`, `touchstart`, `pointerdown`（`once: true`），读者介入立即 `cancelRetry()`，组件卸载与重试结束均有双保险清理；
  3. **测试跨平台适配与打包产物**：`web/test/e2e-progress-consistency.ts` 补充了 macOS Chrome 与 Edge 路径，重新打包并更新 `web/dist`。
- 本地在隔离环境执行全量验证（单测、类型检查、构建、JVM 单元测试全部全绿通过）。
- 评审通过（Approved），在 GitHub 执行 PR Merge，主分支 Fast-Forward 顺利合并并完成清理闭环。

---

## 4. 验证结果 (Verification)

- **前端类型与单测**：
  - `npm --prefix web run check`：0 错误通过；
  - `npx tsx web/test/run-all.ts`：**199 / 199 全部通过（100% PASS）**；
- **服务端编译与测试**：
  - `./gradlew :server:test`：全量单测与 Jacoco 测试通过（`BUILD SUCCESSFUL`）。
