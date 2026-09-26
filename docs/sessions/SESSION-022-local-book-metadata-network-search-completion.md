---
id: SESSION-022
title: 本地书籍编辑弹窗内网络搜索补全信息与多层弹窗治理
date: 2026-09-26
author: Antigravity
tags: [local-book, metadata, stream-search, alternate-sources, modal-layering, e2e-testing]
---

# SESSION-022: 本地书籍编辑弹窗内网络搜索补全信息与多层弹窗治理

## 1. 现象与需求背景 (Investigation & Analysis)
- **核心诉求**：用户导入本地 TXT/EPUB 电子书后，由于文件名规范不一或文件本身不包含网络元数据，书架书籍常出现缺少真实作者、封面仅为文字占位 SVG、无备选书源等情况。用户期望在不改变本地书籍私有正文的前提下，能通过网络搜索一键补全作者、精美封面及关联备选书源。
- **排查与现状分析**：
  - 本地书籍在服务端 `book_shelf` 表中 `source_id = 'loc_book'`，且其章节由 `LocalBookParser` 托管；
  - 服务端已有完善的 WebSocket 流式搜索通道（`/api/sources/stream-search`）与备选书源持久化字段（`alternate_sources`）；
  - `BookInfoEditModal` 原本仅支持手动输入文本与单个 URL 封面，缺少一键检索与选用备选能力。

## 2. 尝试过的无效方案与踩坑推演 (Failed Attempts & Why)
- *尝试方案 1*：直接在书架卡片管理菜单新开一个独立的全屏搜索页面或模态窗。
  - *失败原因*：割裂了用户修改书名/作者/封面的统一入口，造成多处入口冗余，违反极简设计原则；且在搜索后仍需跳转回原弹窗保存，体验繁琐。
- *尝试方案 2*：在 `BookManageModal` 内直接作为子元素渲染 `BookInfoEditModal` 并堆叠两层 `.modal-backdrop`。
  - *排查过程*：在真实 Chrome E2E 截图测试时发现，外层 `BookManageModal` 带有 `animation: fadeIn` 与 `transform` 动画，形成了独立的层叠上下文（Stacking Context）。当子弹窗同样带有 `.modal-backdrop` 时，内部元素与外层管理卡片产生幽灵叠加，导致关闭内层弹窗后外层遮罩拦截了书架底层的阅读按钮点击。
  - *正确解法*：在 `BookManageModal` 中实施条件分支渲染——当 `editingInfo === true` 时直接返回 `<BookInfoEditModal ... />`，彻底解耦两层模态框，并在保存或关闭时清爽切回。
- *尝试方案 3*：本地书籍封面为空的判定使用 `!item.coverKey`。
  - *排查过程*：本地书籍导入时，服务端 `LocalBookParser` 会自动为其生成一张带首字与绿色渐变的兜底 SVG 封面，并赋予其一个随机 `coverKey`。因此 `item.coverKey` 永远为真。
  - *正确解法*：判定本地书是否推荐联网补全时，结合 `!item.author || candidateCovers.length === 0` 作为智能推荐守卫，命中时高亮展示「推荐」徽标。

## 3. 最终落地的正确解法 (Final Solution)
1. **服务端模型与持久化扩展**：
   - `BookshelfInfoUpdateRequest` 新增 `alternateSources: List<SearchResult>? = null`；
   - `Database.updateBookshelfInfo` 在更新书架信息时，若提供了 `alternateSources` 则序列化为 JSON 写入 `book_shelf.alternate_sources` 列；
   - 编写单元测试验证持久化与幂等性。
2. **前端弹窗内嵌流式检索面板**：
   - 在 `BookInfoEditModal` 顶部新增「联网搜索补全信息」可折叠面板；
   - 本地书籍缺少作者或网络封面时展示绿底 `推荐` 徽标；
   - 展开即自动以书名检索所有已启用书源，复用 `api.streamSearch` 展示搜索进度条与流式命中候选卡片；
   - 候选卡片提供「选用」按钮：一键将候选的作者、网络封面反填到表单中，并将候选书源全部收集并入 `alternateSources`；
   - 封面预览区域下方自动展示「从备选书源选择封面」横向列表，支持自由切换备选封面。
3. **安全与生命周期防御**：
   - 组件卸载与面板收起时自动 abort/close WebSocket 流；
   - 对外链封面统一经过 `sanitizeImageUrl` 校验协议白名单；
   - 单击阅读与数据更新仅针对元数据，本地书籍私有正文及分卷绝对无损。

## 4. 沉淀的教训与部落知识 (Lessons Learned)
- **[弹窗架构/React] 严禁在带动画的 `modal-backdrop` 内部直接嵌套另一层 `modal-backdrop`**：CSS 中的 `animation` / `transform` 会自动创建独立的层叠上下文。嵌套弹窗必须在父级以平级条件分支（`if (editingInfo) return <BookInfoEditModal ... />`）的形式挂载，避免双层遮罩冲突、幽灵穿透与层级错乱。
- **[本地书籍/元数据] 本地导入电子书自带 SVG 占位封面，不可用 `coverKey` 空值判断**：本地书导入后系统必分配一个文字版 SVG 占位封面并落库 `cover_key`。推荐补全元数据的判定必须以作者为空或候选封面集为空为准。
- **[流式搜索/弹窗] 模态弹窗内嵌 WebSocket 搜索必须挂载卸载看门狗**：弹窗可能随时被用户点击 Backdrop、按 ESC 或取消关闭，必须在 `useEffect` 清理函数与 `handleSave/handleClose` 中同步执行 `socketSub.close()`，杜绝孤立长连接在后台静默跑满并发。
