---
id: SESSION-043
title: 替换净化规则批量管理、分组管理与操作按钮视觉体系化重构 (Issue #35)
date: 2026-10-03
author: Agent
tags: [replace-rules, batch-management, group-management, a11y, button-styles, e2e]
branch: feat/replace-rules-batch-and-groups
worktree: ../joyful-galileo-replace-rules
---

# SESSION-043: 替换净化规则批量管理、分组管理与操作按钮视觉体系化重构 (Issue #35)

## 1. 业务背景与问题现象 (Issue #35)
在 Issue #35 中，用户 `wfanan` 截取了「替换净化规则」页面详情卡片右上角的操作按钮组（红色方框圈出 `[ 停用 ]`、`[ 编辑 ]`、`[ 删除 ]`），并提出明确诉求：
> 优化下图的按钮  
> 添加批量管理和分组管理

### 核心痛点与对齐缺口
1. **书源页面（SourcesPage） vs 规则页面（ReplaceRulesPage）能力严重脱节**：
   - 书源侧栏顶部具备标准的三动作宫格（`[ 新建 ]`、`[ 批量管理 ]`、`[ 分组管理 ]`），支持勾选多选、吸底批量操作栏（批量启用/停用/导出/移动分组/删除），以及可视化分组归类工作台。
   - 规则页面过去仅有「全部启用/全部禁用」粗粒度按钮，缺少选择模式，无法对某批规则做精细化批处理。
   - 分组仅作为只读筛选药丸，缺少类似书源系统的分组改名、解绑删除与规则批量归类中心。
2. **详情卡片按钮视觉体验粗糙失衡**：
   - 详情卡片右上角的「停用/启用」按钮为无状态感知的 `.ghost-button`，与其他按钮视觉层级脱节，缺乏符合规范的次级卡片基类与微绿/中性反馈。

---

## 2. 架构设计与实现落地

### 2.1 详情卡片操作按钮重构
- 将详情区状态切换按钮升级为统一的次级按钮基类（`.secondary-button.rule-toggle-btn`），高度 34px、圆角 5px 与全局规范严格对齐；
- 赋予高对比度且优雅的状态感知：
  - 启用态（`.is-enabled`）：柔和微绿高亮（`color-mix(in srgb, var(--accent) 12%, var(--surface))`）；
  - 停用态（`.is-disabled`）：清爽的中性浅底；
  - 与 `[ 编辑 ]`、`[ 删除 ]` 形成清晰层级。

### 2.2 多选批量管理模式 (`ReplaceRulesPage.tsx`)
- 侧栏顶部对齐三动作按钮：`[ 新建 ]`、`[ 批量管理 ]`、`[ 分组管理 ]`；
- 进入批量模式时，列表项左侧浮出复选框（Checkboxes），页面底部平滑滑出吸底操作栏（`.source-batch-bar`）；
- 批量栏支持实时已选计数、全选/全不选/反选，提供：
  - **批量移动分组**（唤起 `ReplaceRuleBatchMoveModal`）；
  - **批量启用 / 批量停用**；
  - **导出选中**（仅导出已勾选的规则 JSON 文件）；
  - **批量删除**（带删除条数与名称摘要确认防护）。

### 2.3 可视化规则分组管理中心 (`ReplaceRuleGroupManagerModal.tsx`)
- 顶部呈现概览统计卡片：已有分组数、规则总数与未分组数；
- 组内列表支持**行内重命名**（回车保存、同名自动合并）与**软解绑删除**（仅清除组名归入未分组，绝不误删规则）；
- 内置「规则归类工作台」：选择已有分组或新建分组，支持在全量规则中即时搜索筛选，批量勾选一键归入。

### 2.4 服务端批量管道与分组持久化
- 新增 `POST /api/replace-rules/batch`（`action` 支持 `enable`、`disable`、`delete`、`set_group`，单一 SQLite 事务完成）；
- 新增 `GET /api/replace-rule-groups`（返回各分组规则数与启用数）、`PUT /api/replace-rule-groups/rename`、`DELETE /api/replace-rule-groups?name=`。

### 2.5 全栈四语国际化 (i18n)
- 完整补齐 `zh-CN`、`zh-TW`、`en-US`、`ja-JP` 全部新增词条。

---

## 3. 红蓝对抗审查 (Skeptic Review & Anti-Bloat)

- **Skeptic 挑刺 1：批量删除如果用户误点怎么办？**
  - *Defender 释疑*：批量删除按钮具备禁用守卫，点击时弹出带有前 3 条规则名称及总数的原生二次确认弹窗（“删除后不可恢复”），点击取消不执行任何写操作。
- **Skeptic 挑刺 2：删除分组是否会导致组内规则被连带删除？**
  - *Defender 释疑*：严格遵循 ADR-021/027 软解绑语义，执行 `UPDATE replace_rule SET group_name = null WHERE group_name = ? COLLATE NOCASE`，只清理 `group` 字符串列，规则本体完好无损。
- **Skeptic 挑刺 3：是否引入了多余的数据表或复杂度？**
  - *Defender 释疑*：零新表、零外键。直接复用已有的 `group_name` 字符串列，保证与 Legado 手机端导出的 `replaceRule.json` 双向 100% 兼容。

---

## 4. 验证基准与成果

1. **真实无头 Chrome 浏览器无障碍全流程 E2E**：
   - 登录系统 -> 导航至规则页 -> 验证侧栏三动作无障碍 title -> 新建两条带分组规则 -> 验证详情卡片按钮视觉高亮与切换 -> 批量多选全选 -> 批量停用/启用 -> 批量移动分组 -> 分组管理中心归类 -> 批量全选清理测试数据。**全部步骤 100% 通过**！
2. **前端测试套件**：`npm run check` 零类型错误，`run-all.ts` 206/206 全部通过。
3. **服务端单测**：`./gradlew :server:test` 全部通过（包含新增的 `ReplaceRuleBatchAndGroupTest`）。
