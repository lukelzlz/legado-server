/**
 * 兼容入口：换源实现已抽到 `sourceSwitch.tsx`（弹窗与编排）
 * 与 `sourceSwitchRemap.ts`（重映射纯函数）。
 *
 * 保留这个文件是因为仓库里已有两处 import 指向它（`ReaderScreen` 与 `main.tsx`，
 * 以及既有单测 `web/test/SourceSwitchAndCache.test.ts`）；把它们一次性改掉会扩大本 PR 的
 * 改动面，而它们指向的**本来就是同一个实现**。新代码请直接从 `./sourceSwitch` 引入。
 */
export { SourceSwitchModal, matchBestChapter } from './sourceSwitch'
export type { SourceCandidate, SourceSwitchModalProps } from './sourceSwitch'
