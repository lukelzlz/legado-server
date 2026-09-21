---
id: PROPOSAL-017
title: 移植轻阅读书源规则解析/内容管线，修复聚合书源（大灰狼）正文提取为空
status: implemented
author: Agent & User
date: 2026-09-20
---

# PROPOSAL-017: 移植轻阅读书源规则解析/内容管线，修复聚合书源正文提取为空

> **裁定记录（2026-09-20，用户确认）**：C2 = 语义补齐 + 移植 `RuleAnalyzer` 切分器（保留自主运行器）；
> C1 = 先实跑判定实际分支再定；C3 = 曾拟新增 `rawResult` 绑定。
>
> **实施结果（2026-09-20）**：主因已修复并验证，详见
> [`../sessions/SESSION-019-dagou-content-root-cause.md`](../sessions/SESSION-019-dagou-content-root-cause.md)。
> - ✅ **C1 已实跑判定**：`checkEnv()` 返回 `"改版"`，**不需要**实现 `java.qread`。
> - ✅ **C3 经实测推翻、已废弃**：正文路径 `result` 本就是字符串，**该问题不存在**，不引入 `rawResult`。
> - ⏸ **D4（`RuleAnalyzer` 切分器）未实施**：与本次症状无因果，需另行排期。
> - ⏸ **`java.createSymmetricCrypto` / `source.loginUi` 未补齐**：实测非本次阻塞项。

> **文档平面**：本文件位于**仓库级**文档平面（`legado-server/docs/`），因为它描述的是 `legado-server/**` 的技术变更。
> 工作区级索引见 `12\reader\docs\README.md`。

## 1. 业务背景与问题痛点

### 1.1 现象

使用真实书源《🍅大灰狼聚合5.9.5(vip完全版)》（`bookSourceUrl = 大灰狼融合VIP5.0`，样本见 §7）时：

- **搜索/详情/目录可用**（2026-09-17 已修过该源的搜索超时问题）；
- **正文报空**：章节可打开，但正文提取不到内容，服务端抛
  `RuleExecutionException("正文规则未提取到内容")`（`server/src/main/kotlin/io/legado/server/RuleRunner.kt:272`）。

### 1.2 为什么这个书源难

该源是**聚合源**，其规则不是简单 CSS/XPath，而是一整段程序：

- 正文地址是 `data:;base64,<载荷>,{…}` 参数载体，需先解码再交给规则（reader 已支持，见 `RuleRunner.dataUrlPayload`）；
- 正文规则形如 `<js>…约 300 行 JS…</js>$.content`，即**规则链**（JS 求值 + 后置 JSON 路径）；
- 同一段 JS 内依赖 **22 个 `java.*` 函数、8 个 `source.*`、5 个 `cookie.*`、多处 `book.*`/`chapter.*`**
  以及 jsLib 里 **44 个全局工具函数**（`getArguments`、`resolveShuqiToneId`、`paraForAndroid` 等）；
- JS 内有**多段 `try{…}catch{}` 的存在性探测**，用来判断运行在哪个阅读客户端（轻阅读/改版/苹果/安卓）。

### 1.3 为什么现有实现接不住

reader 的规则引擎是**自主实现的声明式引擎**（`RuleRunner` + `NodeValue`），与 Legado/轻阅读的
**通用规则分析器**（`AnalyzeRule` + `RuleAnalyzer` + `AnalyzeUrl` + `JsExtensions`）在语义上存在系统性差异。
本次只读核查（2026-09-20）量化出的差异如下。

## 2. 只读核查结论（事实与证据）

### 2.1 API 覆盖度：缺口其实很小

对 §7 的真实书源提取全部 `java.*` / `source.*` / `cookie.*` 调用，与 reader 已注册的桥接属性逐一比对：

| 类别 | 需求 | reader 已有 | **缺失/不完整** |
| :--- | :--- | :--- | :--- |
| `java.*` | 22 | 18 | **4**：`createSymmetricCrypto`、`lang`、`net`、`qread` |
| `source.*` | 7 | 6 | **1**：`loginUi` |
| `cookie.*` | 4 | 4 | 0 |
| `book.*` / `chapter.*` | 8 处 | 覆盖（`durChapterIndex`、`index`、`imageStyle` 等已注册） | 0 |

> **结论**：这不是「从零移植 API」，而是「补齐 4+1 个函数 + 修正规则链求值语义」。

### 2.2 已确认的语义差异（按可信度排序）

| # | 差异 | reader 现状（定位） | 轻阅读/Legado 语义 | 可信度 |
| :--- | :--- | :--- | :--- | :--- |
| D1 | **JS 包装判定过宽** | `JsSandbox.kt:85` 用 `Regex("""\breturn\b""")` 匹配**整段脚本（含 jsLib）**，命中就整体包进 `(function(){…})()` | 应先单独求值 jsLib（只取函数定义），再**按括号深度判定「真正的顶层 return」**才包 IIFE | **高**（与仓库既有修复口径一致） |
| D2 | **环境探测函数缺失导致探测结果错位** | 缺 `java.qread`（探测轻阅读）、`java.lang`、`java.net` | 提供该函数使探测命中预期分支，而不是靠异常兜底 | 中—高（`java.qread` 语义归属需用户裁定，见 §5 待确认） |
| D3 | **无显式「原始字符串绑定」路径** | `<js>…</js>` 的 `result` 传的是**原生对象**（`NodeValue.value` → `RuleRunner.kt:784-789`） | 正文型 `data:` 载荷在部分规则里需以**原始字符串**绑定，否则 `String(...)`/字符串方法得到 `[object Object]` | 中（需实跑确认该源是否命中） |
| D4 | **规则切分能力弱** | 仅按子串切 `<js>` / `</js>`，不支持嵌套括号、引号内分隔符、`&&`/`\|\|` 歧义 | `RuleAnalyzer` 的 `chompRuleBalanced` / `chompCodeBalanced` 做**引号感知的括号深度切分** | 中 |
| D5 | **无 `result` 全局回读** | `JsSandbox.eval` 仅在返回 `undefined` 时回读全局 `result`（`JsSandbox.kt:124-129`） | Legado `evalJS` 的补全值语义更宽松，脚本末尾裸表达式（该源末尾正是裸 `data;`）必须取到 | 中 |

### 2.3 该源正文规则的关键片段（证据）

```javascript
// ruleContent.content 结尾（节选）
data = JSON.stringify({ content: content });
...
data;                    // ← 末尾是「裸表达式」，依赖求值补全值
</js>$.content             // ← 规则链：JS 求值结果再取 $.content
```

```javascript
// 其中的客户端探测（决定了后续 imageStyle 等分支）
function checkEnv() {
    try { java.qread(); return "轻阅读"; } catch (e) {}
    try {
        if (typeof java.reLoginView == 'function') return "改版";
        new Packages.io.legato.kazusa.utils.TimeoutCancellationException('');
        return "改版";
    } catch (e) {}
    try { java.deviceID(); return "苹果"; } catch (e) {}
    if (typeof source.loginUi == 'function') return "安卓";
    return "改版";
}
```

> 注意：`java.reLoginView` 在 reader 中**已存在**，因此第 2 个分支**返回而不抛异常**，探测判定为「改版」——
> 这与 §2.2 D2 的「靠异常兜底」描述需要一次实跑确认到底走哪个分支。

## 3. 目标与非目标 (Goals & Non-Goals)

### Goals

1. **修好正文**：使用真实书源样本，`大灰狼融合VIP5.0` 的章节正文能正确提取（非空、无 `[object Object]` 残留）。
2. **成体系移植**：把轻阅读的书源**规则解析与内容管线**能力移植进 reader，而不是打一次性补丁：
   - `RuleAnalyzer` 的引号感知括号深度切分；
   - 规则链（`<js>…</js>` + 后置路径）的通用求值语义；
   - jsLib 独立预求值 + 顶层 `return` 才包 IIFE；
   - 补全值语义。
3. **补齐 API**：`java.createSymmetricCrypto` / `java.lang` / `java.net` / `java.qread` / `source.loginUi`。
4. **可回归**：用真实书源固化为自动化测试，防止回归。
5. **不破坏既有能力**：非聚合书源（普通 HTTP + CSS/XPath 规则）行为不变。

### Non-Goals（本次坚决不做）

- ❌ 不迁移 `book/` 子模块的**本地书籍**解析（TXT/EPUB/UMD/CBZ）——reader 已有 `LocalBookParser`。
- ❌ 不迁移 TTS/听书链路、RSS、替换净化引擎（reader 已有等价实现）。
- ❌ 不引入 Flutter/Android 任何依赖（违反仓库 ADR-001 纯 JVM 约束）。
- ❌ 不做「与轻阅读客户端完全同构」的兼容——reader 是服务端，不是 App 客户端。
- ❌ 不改变 `data:` 参数载体的既有编码约定（已工作，只做兼容增强）。
- ❌ 不重构 `RuleRunner` 的整体架构（保持自主引擎，只补齐语义缺口）。

## 4. 核心用户故事 (User Stories)

- **Story 1（正文可读 · 主诉）**：作为读者，我在书架打开《大灰狼》任一章节，正文**正常显示**（非空、无 JS 源码泄漏、无 `[object Object]`），可以连续翻章阅读。

- **Story 2（聚合源通用受益）**：作为使用者，除大灰狼外的其他聚合源（番茄/七牛/书旗/塔读等聚合规则）正文也能正常提取，
  证明修复的是**通用语义**而不是为该源写死。

- **Story 3（诊断可解释）**：作为维护者，当正文仍提取失败时，服务端返回的异常信息**能指出具体失败环节**
  （是 jsLib 求值失败、规则链后置路径失败、还是 API 缺失），而不是笼统的「未提取到内容」。

- **Story 4（普通书源零回归）**：作为维护者，我跑服务端全量测试时，**失败集合与干净基线逐条一致**
  （本机存在固定基线失败，判定口径见 ADR-002 与 `SESSION-HIST-008`）。

- **Story 5（自动化回归）**：作为维护者，新增的回归测试**直接使用真实书源 JSON**（或其脱敏子集）断言正文提取结果，
  无需人工搭环境即可在 CI 复现。

## 5. 待确认决策（开工前需用户裁定）

| # | 决策点 | 选项 | 影响 |
| :--- | :--- | :--- | :--- |
| C1 | **`java.qread()` 的语义归属** | (a) 不实现，让探测落到「改版」分支；(b) 实现为返回 `1`（**等价宣称自己是轻阅读**）；(c) 实现为返回 `1` 但仅用于规则兼容层并显式注释 | 该源规则为「轻阅读」写了专门分支；(b) 会走轻阅读分支，可能依赖更多轻阅读独有 API；(a) 走「改版」分支，与 reader 的自主引擎定位一致。**建议 (a)** |
| C2 | **移植深度** | (a) 只修语义缺口（D1/D2/D5）+ 补 API；(b) (a) + 移植 `RuleAnalyzer` 切分器（D4）；(c) 全量移植规则分析器族 | 用户已选「整套移植」，但 (c) 等价于重写 `RuleRunner`，风险与工作量最大。**建议 (b)**：语义与切分器都移植，运行器仍用 reader 自主引擎 |
| C3 | **`result` 绑定策略** | (a) 保持现状（原生对象）；(b) 新增「原始字符串绑定」路径并按规则特征选择；(c) 两者都传（新增 `rawResult`） | 影响 D3。**建议 (c)**：新增 `rawResult` 绑定，零破坏、可回退 |

## 6. 验收基准 (Acceptance Criteria)

- [ ] **AC-1**：用 §7 真实书源，通过服务端接口成功获取 `大灰狼融合VIP5.0` 的任一章节正文，长度 > 0 且不含 `[object Object]`、不含 `<js>` 原始脚本片段。
- [ ] **AC-2**：新增服务端回归测试（Kotlin），**直接加载 §7 书源 JSON** 的规则，断言正文提取结果非空且含预期中文文本。
- [ ] **AC-3**：`RuleAnalyzer` 切分器单测覆盖：引号内分隔符、嵌套括号、`&&`/`||`、转义字符、不闭合时抛错。
- [ ] **AC-4**：jsLib 含 `return` 的场景下，规则脚本末尾裸表达式（如 `data;`）的**补全值能被取到**（D1/D5 的定向单测）。
- [ ] **AC-5**：补齐 4+1 个 API 后，`checkEnv()` 在该源上下文中的返回分支被测试固化（记录实际分支，避免静默漂移）。
- [ ] **AC-6**：普通书源（如 `69主站.json` 类 CSS 规则源）行为不变——全量测试失败集合与干净基线**逐条一致**。
- [ ] **AC-7**：`npm --prefix web run check` 通过（若涉及前端则必须；纯服务端改动可豁免但需说明）。

## 7. 真实书源样本（关键输入）

| 项 | 值 |
| :--- | :--- |
| 文件 | `C:\Users\w1593\Desktop\12\安卓阅读app-大灰狼融合4.0(vip完全版).json`（157,895 B，UTF-8） |
| 结构 | 顶层 JSON 数组，1 个元素 |
| 书源名 | `🍅大灰狼聚合5.9.5(vip完全版)` |
| bookSourceUrl | `大灰狼融合VIP5.0` |
| 分组 | `大灰狼聚合` |
| 关键字段 | 有值：`jsLib`、`loginUrl`、`searchUrl`、`exploreUrl`、`ruleBookInfo`、`ruleContent`、`ruleToc`、`ruleSearch`、`ruleExplore`、`header`、`loginUi` |
| ruleContent | `content`（约 300 行 JS + `</js>$.content`）、`imageStyle = full` |

> ⚠️ **提交前必须脱敏**：该源含 `loginUrl` 与可能的凭据逻辑。回归测试**只保留规则与 jsLib 的最小必要子集**，
> 不得把真实账号、Cookie、Token 写入仓库（遵守仓库 AGENTS.md §10）。

## 8. 风险与对策

| 风险 | 对策 |
| :--- | :--- |
| 移植 `RuleAnalyzer` 触及所有规则的切分路径，可能回归普通书源 | 先加**切分器单测**锁定行为，再切换调用点；用全量测试比对失败集合 |
| 该源依赖 44 个 jsLib 工具函数，其中部分可能依赖未实现的 API | 实跑后逐个补；`java.log` 输出作为诊断手段（已实现） |
| 服务端测试在本机有固定失败基线（本项目实测 170 用例/53 失败，全部为 SQLite 文件占用） | 判回归**只比对失败集合**，不看数量；详见工作区 `docs/sessions/SESSION-HIST-002` |
| 大灰狼的上游线路不稳定（历史上 `v5.czyl.cf` 下线导致搜索超时） | 回归测试**不依赖真实网络**：构造 data: 载荷 + 固定 JS 输入，断言提取结果 |

## 9. 上线与验证路径

1. `[Modified]` 语义修补 + API 补齐 + 单测；
2. 跑 `:server:test` 并与干净基线比对失败集合（`[Tested]`）；
3. `:server:fatJar` → 独立端口启动 → 用真实书源实跑「搜索→详情→目录→正文」→ 抓正文实证（`[Deployed]`）；
4. 产出 `ACCEPT-017` 验收手册交用户实操（`[Accepted]`）。

---

## 附：本次只读核查的证据定位

| 结论 | 证据 |
| :--- | :--- |
| 正文空异常抛点 | `server/src/main/kotlin/io/legado/server/RuleRunner.kt:270-273` |
| 聚合源 data: 载荷解码 | `RuleRunner.kt:112-130` |
| 规则链（`<js>` + 后置路径）实现 | `RuleRunner.kt:778-794` |
| JS 包装判定过宽 | `server/src/main/kotlin/io/legado/server/JsSandbox.kt:85-89` |
| 补全值/全局 result 回读 | `JsSandbox.kt:123-131` |
| 既有修复口径（jsLib 预求值 + 括号深度） | `legado-server/AGENTS.md` 2026-09-19 Quickfix 索引行 |
| 相关既有测试 | `server/src/test/kotlin/io/legado/server/{RuleRunnerTest,RuleRunnerExtendedTest,RuleRunnerAdvancedFeaturesTest,SourceCodecTest,WebViewProxyTest}.kt` |
