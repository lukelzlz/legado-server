---
id: SESSION-012
title: 替换净化规则升级为一级独立页面与阅读器设置抽屉集成
date: 2026-09-15
author: Antigravity
tags: [replace-rules, ui-refactor, reader-settings, navigation, frontend]
---

# SESSION-012: 替换净化规则升级为一级独立页面与阅读器设置抽屉集成

## 1. 现象与需求推演 (Investigation & Analysis)
- **初始痛点**：
  - 用户反馈原替换净化规则入口隐藏过深（仅在右上角汉堡菜单折叠项及阅读器顶栏小图标中），难以直观发现与统一管理；
  - 阅读器顶栏功能图标过多（返回、目录、书名、替换净化、换源、设置、朗读），在大屏及移动端显得拥挤且视觉噪点多。
- **架构决策 (ADR-011)**：
  - 将「替换净化规则」升级为主导航栏一级独立页面（与「书库」、「书架」、「书源」、「订阅」平级），支持全量规则列表、分类筛选、批量启停、订阅导入导出及沙箱实时测试预览；
  - 阅读器顶栏移除独立的「替换净化」按钮，将阅读过程中的替换净化配置完全收敛进「阅读设置」抽屉，保持顶栏极简。

---

## 2. 踩坑与排错记录 (Obstacles & Solutions)
- **踩坑 1：TypeScript 严格模式下的可空字段类型不匹配 (`null` vs `undefined`)**
  - *现象*：`ReplaceRule` 接口中 `scope?: string; excludeScope?: string` 为可选字段，表单初始状态若赋值为 `null` 会触发 TS2322 编译报错。
  - *解法*：新建规则初始状态统一设为 `undefined`，输入框 `onChange` 事件使用 `e.target.value || undefined` 进行优雅回退。
- **踩坑 2：API 导入导出方法名映射**
  - *现象*：页面初版尝试调用 `api.exportReplaceRules()` 与 `api.importReplaceRules()`，但底层实际提供的是轻量前端导出（`Blob + JSON.stringify(rules)`）及分别对应的 `importReplaceRulesUrl(url)` 与 `importReplaceRulesText(text)`。
  - *解法*：导出改用浏览器纯前端 Blob 下载触发；导入根据单选状态（URL / 文本）分流调用 `importReplaceRulesUrl` 与 `importReplaceRulesText`，完全复用现有可靠后端契约。
- **踩坑 3：Icon 图标名称与统一规范**
  - *现象*：部分按钮误传 `"trash"` 或 `"x"` 图标名，触发 `IconName` 联合类型检查错误。
  - *解法*：统一更正为 `icons.tsx` 中声明的 `"close"` 图标及语义化按钮文案。

---

## 3. 最终落地的正确解法 (Final Solution)
1. **主导航独立页面 (`web/src/ReplaceRulesPage.tsx`, `web/src/AppHeader.tsx`, `web/src/main.tsx`)**：
   - 主导航栏新增「规则」Tab 及 `#rules` 哈希路由支持；
   - 包含分组胶囊标签快速筛选、搜索关键字实时过滤、全量/按组启停、订阅导入导出及实时正则 / `@js:` 沙箱测试面板。
2. **阅读器设置抽屉收敛 (`web/src/ReaderScreen.tsx`)**：
   - 移除顶栏独立的「替换净化」图标按钮；
   - 在「阅读设置」抽屉中增加「内容净化 / 替换净化规则」入口区块；
   - 点击唤起针对当前书籍及书源的 `ReplaceRulesModal`，保存后自动重新拉取正文并重新排版渲染。

---

## 4. 沉淀的教训与部落知识 (Tribal Knowledge)
- **[前端/导航] 一级功能入口规范**：具有独立数据集合、跨书籍全局生效且支持增删改查的核心系统能力（如书源、订阅、替换规则），必须在一级导航拥有平级入口，杜绝仅藏在二级菜单中导致用户认知断层。
- **[阅读器/交互] 顶栏极简与抽屉模块化收敛**：阅读器顶栏应专属于高频阅读控制（目录、换源、设置、朗读），辅助性配置项（排版、字体、主题、净化规则）应统一收敛进「阅读设置」右侧抽屉，减少阅读干扰。
