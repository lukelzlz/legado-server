---
id: ADR-017
title: 保留自主规则引擎，以「语义补齐 + 切分器移植」承接轻阅读书源管线
status: accepted
date: 2026-09-20
---

# ADR-017: 保留自主规则引擎，以「语义补齐 + 切分器移植」承接轻阅读书源管线

> 关联提案：[`PROPOSAL-017`](../proposals/PROPOSAL-017-port-qingyue-rule-engine-and-fix-aggregate-content.md)
> 上游约束：[`ADR-001`](ADR-001-pure-jvm-ktor-and-rhino-sandbox.md)（纯 JVM + Rhino 沙箱，禁 Android 依赖）
> **裁定记录（2026-09-20，用户确认）**：C2 采用 §2 方案；C1 先实跑判定（**结论见 §3 备选 A**）；
> **C3 经实测推翻原前提、已废弃**（见 §2 第 4 条与 §3 备选 D）。

## 0. 实测后的修正（2026-09-20，重要）

本 ADR 立项时的三项不确定性，经实跑全部有了结论（实测全文见
[`../sessions/SESSION-019-dagou-content-root-cause.md`](../sessions/SESSION-019-dagou-content-root-cause.md)）：

| 原假设 | 实测结论 |
| :--- | :--- |
| D1「JS 包装判定过宽」 | ✅ **确认为唯一主因**，且后果比预想严重：不是「包了一层」，而是**补全值退化为 undefined** |
| D5「补全值语义」 | ✅ 与 D1 **同一根因**，非独立问题 |
| D2「缺 `java.qread` 等 API」 | ❌ **不是本次阻塞项**（见 §3 备选 A） |
| D3「需原始字符串绑定」 | ❌ **问题不存在**：正文路径 `result` 本就是字符串（`NodeValue.json(payload)`），实测 `hexDecodeToString` 工作正常 ⇒ **C3 废弃** |
| D4「规则切分器」 | ⏸ 与本次症状无因果，属成体系移植范畴，按 §2 方案保留实施 |

## 1. 决策背景 (Context)

需要用《大灰狼融合VIP5.0》这类**聚合书源**的规则修复正文提取为空（详见 PROPOSAL-017 §1）。
这类规则是一整段依赖 22 个 `java.*`、8 个 `source.*` 与 44 个 jsLib 工具函数的程序，
并广泛使用 `<js>…</js>$.path` 规则链与客户端环境探测。

reader 现有实现是**自主声明式引擎**（`RuleRunner` + `NodeValue` + `JsSandbox`），
而轻阅读是 **Legado 通用规则分析器**（`AnalyzeRule` / `RuleAnalyzer` / `AnalyzeUrl` / `JsExtensions`）。
只读核查（2026-09-20）显示：reader 的 API 覆盖已达 18/22，真正的差距在**求值语义**与**规则切分**，
不在 API 数量。因此「怎么承接」是本 ADR 要定的问题。

## 2. 裁定方案 (Decision)

**保留 reader 的自主规则引擎，只移植轻阅读中缺失的语义与切分能力。**

### 2.1 已实施（2026-09-20，`JsSandbox.kt`）

1. **新增「顶层 `return`」判定器** `hasTopLevelReturn(script)`：引号/模板字面量/注释感知 +
   括号/方括号深度扫描，仅在深度 0 且前后非标识符字符时判定为顶层 `return`。
2. **jsLib 与规则脚本分离**：取消 `"$library\n$rawScript"` 拼接；
   改为**同一 scope 内先求值 jsLib**（只建立函数定义），再求值规则脚本，
   使 jsLib 结果**不参与**规则脚本的补全值。
3. **包裹判定仅针对规则脚本自身**：`hasTopLevelReturn(rawScript)` 为真才包 `(function(){…})()`。
4. **`lastError` 清理**：成功求值后置 null，消除「读到上一次失败残留」的误导。

### 2.2 计划内但未实施

5. **移植 `RuleAnalyzer` 的引号感知括号深度切分器**（`chompRuleBalanced` / `chompCodeBalanced` 语义），
   替代当前「按子串定位 `<js>` / `</js>`」的朴素切分 —— 与本次症状无因果，需另行排期。

运行器（`RuleRunner` / `NodeValue`）的架构**不变**，仅在其内部替换切分与求值实现。

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

### 备选 A：把 `java.qread()` 实现为返回 `1`

- **做法**：让该源的 `checkEnv()` 探测命中「轻阅读」分支，走源作者为轻阅读写的专用路径。
- **否决理由**：
  1. **语义撒谎**：`java.qread()` 的 Legado 语义是「判断客户端是否为轻阅读」；reader 是 legado-server，
     返回 `1` 等于向书源自称是另一个客户端。该源的「轻阅读」分支会进一步依赖轻阅读独有 API，等于被迫追平另一个客户端的实现面。
  2. **不可维护**：后续每个探测「我是谁」的书源都会要求 reader 冒充某个客户端，兼容层会无限膨胀。
  3. **与 ADR-001 的定位冲突**：reader 的定位是**独立服务端**，不是 App 客户端的仿真器。
- **✅ 实测判定（已执行，2026-09-20）**：在该源上下文实跑 `checkEnv()`，返回 **`"改版"`**：
  `java.qread()` 抛 `TypeError` → `typeof java.reLoginView == 'function'` 为真 → 直接返回「改版」。
  且规则执行期间**未出现** `qread`/`lang`/`net` 相关的 ReferenceError。
  ⇒ **C1 结论：不实现 `java.qread`（也不实现 `java.lang`/`java.net`），探测自然落到「改版」分支即可**，
  该分支所需能力 reader 均已具备。这与本备选方案的初始判断一致。

### 备选 D：新增 `rawResult` 原始字符串绑定（原 C3）

- **做法**：在 `<js>` 求值时额外绑定 `rawResult`，供需要字符串形态的规则使用。
- **✅ 否决理由（实测后废弃）**：原判断「正文 `data:` 载荷需要原始字符串绑定」**经实测不成立**。
  正文路径的绑定链是 `dataUrlPayload()` → `NodeValue.json(payload)` → `NodeValue.value()`，
  其中 `json` 字段保存的就是**载荷字符串本身**；实测 `String(java.hexDecodeToString(result))`
  得到正确载荷（长度 89），而非 `[object Object]`。
  ⇒ 该问题不存在，**新增 API 属无用死知识**，违反仓库「不做臆测性抽象」原则，故不实施。
  （此前列出的「中可信」在实测中被证伪，这正是坚持「先实跑再编码」的价值。）

### 备选 B：全量移植轻阅读 `AnalyzeRule` 族，替换 `RuleRunner`

- **做法**：把 `AnalyzeRule` / `AnalyzeByJSoup` / `AnalyzeByXPath` / `AnalyzeByJSonPath` / `AnalyzeByRegex` /
  `AnalyzeUrl` / `JsExtensions` 整族搬进来，废弃现有声明式引擎。
- **否决理由**：
  1. **等于重写核心**：`AnalyzeUrl`（26 KB）+ `JsExtensions`（36 KB）+ `AnalyzeRule`（31 KB）承担了
     网络请求、并发限速、浏览器代理、加密、规则四种模式等全部职责，与 reader 已有的 Ktor/OkHttp、
     `WebViewProxy`、`BookCacheService`、`RuleRunner` 大量重叠，合并冲突面极大。
  2. **违反复杂度惩罚**：仓库 AGENTS.md §2 明令「严禁为单一补丁增加无意义抽象层」；全量替换是为一个源的
     症状重写整条链路。
  3. **回归面不可控**：reader 的所有既有书源能力（含已验证的聚合源、本地书、TTS 正文清洗）都挂在
     现有运行器上，整体替换会让回归判定失去基线意义。
- **采用**：拒绝。但**吸收其算法**（切分器、求值语义），这正是本 ADR 的方案。

### 备选 C：只在 `RuleRunner.content()` 里为该源写特例分支

- **做法**：检测 `bookSourceUrl == "大灰狼融合VIP5.0"` 走专用代码。
- **否决理由**：硬编码书源标识是典型技术债；且同类聚合源（番茄/七牛/书旗/塔读聚合）共享同一套规则语义，
  特例无法复用，症状会在下一个聚合源上重演。
- **采用**：拒绝。

## 4. 后果与权衡 (Consequences & Trade-offs)

### 正面收益

- **改动面可控**：不动 `RuleRunner`/`NodeValue` 的对外接口，只在内部替换切分与求值实现；
  普通书源的 CSS/XPath 路径完全不受影响。
- **通用受益**：修的是语义缺口，同类聚合源一并受益（PROPOSAL-017 Story 2）。
- **可回退**：切分器与求值语义都能通过单测锁定；若出现回归可快速定位到单点。
- **零新增依赖**：不引入 Android、不引入新三方库，符合 ADR-001。

### 负面代价与风险

| 代价/风险 | 缓解 |
| :--- | :--- |
| 两套语义长期并存，读者需要在文档中理解「reader 语义 vs Legado 语义」的边界 | 在 `legado-server/AGENTS.md` 补一条部落知识，写明差异与判定口径 |
| 括号深度切分器是字符级状态机，边界情况多（引号、转义、嵌套、不闭合） | 先写切分器单测（PROPOSAL-017 AC-3）覆盖这些边界，再切换调用点 |
| 该源 jsLib 含 44 个工具函数，可能仍有个别依赖未实现 API | 用 `java.log` 诊断逐步补齐；回归测试**不依赖真实网络**（构造 data: 载荷） |
| 无法保证一次就把正文修对（缺口 D3/D5 的可信度仅为「中」） | 明确把「实跑定位」作为编码第一步（见下） |

### 明确的不确定性（诚实声明）

本 ADR 的方案建立在**只读核查**之上。以下两点**尚未经实跑证实**，编码第一步必须先用真实书源复现定位：

1. `checkEnv()` 在 reader 中**实际**走哪个分支（`java.reLoginView` 已存在，可能返回「改版」而非抛异常）；
2. D3（`result` 是否需要原始字符串绑定）在本源上是否真实命中。

若实跑结果与假设不符，**应先修正本 ADR 再继续编码**，不得带着未验证的假设往下写。

## 5. 验证方法

- 切分器：纯函数单测（无网络、无沙箱）；
- 求值语义：`JsSandbox` 定向单测（jsLib 含 `return` + 规则末尾裸表达式）；
- 端到端：用真实书源 JSON 的规则片段 + 构造的 `data:` 载荷，断言正文非空；
- 回归：`:server:test` 失败集合与干净基线**逐条比对**（本机固定基线见工作区 `SESSION-HIST-002`）。
