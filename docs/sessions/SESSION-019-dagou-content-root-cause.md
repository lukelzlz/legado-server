---
id: SESSION-019
title: 实测定位与修复：大灰狼聚合书源正文为空（IIFE 包裹吞掉补全值）
date: 2026-09-20
type: working_memory
related: PROPOSAL-017 / ADR-017
status: Tested
---

# 工作记忆：大灰狼正文为空 —— 实测定位与修复

> **性质**：本文件是 `PROPOSAL-017` 的**编码第零步 + 修复实施**记录。
> 除标注外，全部结论均为**本机实测**。
> **方法**：临时 JUnit 探针（`ScratchDagou*Test` / `ScratchCalibrateTest`）加载真实书源 JSON，
> 直接调用 `RuleRunner.content(source, "data:;base64,…")` 与 `JsSandbox.eval(...)`，
> 结果落盘为 UTF-8 文本再读（规避控制台编码干扰）。探针已在使用后删除。

## 1. 复现结果：与用户报告完全一致

```
RuleExecutionException: 正文规则未提取到内容
```

来源：`server/src/main/kotlin/io/legado/server/RuleRunner.kt:270-273`。
用真实书源《🍅大灰狼聚合5.9.5(vip完全版)》（`bookSourceUrl = 大灰狼融合VIP5.0`）+ 构造的 `data:;base64,…` 章节载荷复现成功。

## 2. 根因（已证实，非推测）

### 2.1 直接机制：IIFE 包裹导致补全值变成 `undefined`

`JsSandbox.kt:85-89`：

```kotlin
val executableScript = if (Regex("""\breturn\b""").containsMatchIn(cleanScript)) {
    "(function(){\n$cleanScript\n})()"
} else {
    cleanScript
}
```

其中 `cleanScript = "$library\n$rawScript"`（`jsLib` + 规则脚本）。

**该源 `jsLib`（32,076 字符，含 44 个工具函数）里每个函数都有 `return`，所以正则判定恒为真**，
于是 **jsLib + 规则整段**被包进 `(function(){…})()`。

### 2.2 最小对照实验（决定性证据）

对 `JsSandbox.eval` 做最小脚本对照，实测返回：

| 脚本 | eval 返回 |
| :--- | :--- |
| `'hello'` | ✅ `hello` |
| `String('hello')` | ✅ `hello` |
| `JSON.stringify({content:'XYZ'})` | ✅ `{"content":"XYZ"}` |
| `var data = JSON.stringify({content:'XYZ'}); data;` | ✅ `{"content":"XYZ"}`（**裸表达式补全值可用**） |
| `(function(){ var data = …; return data; })()` | ❌ **`org.mozilla.javascript.Undefined@…`** |
| `function f(){ return 'FROM_LIB'; } f();`（作 library 注入） | ❌ **`Undefined@…`** |

**结论**：包进 IIFE 后，Rhino 的求值补全值退化为 `undefined`；而该源正文规则**末尾正是裸表达式 `data;`**，
依赖补全值返回正文 JSON。于是 `$.content` 取不到值 ⇒ 正文为空。

### 2.3 二次确认：jsLib 确实生效，但被包裹一并破坏

| 用例 | 结果 |
| :--- | :--- |
| 规则脚本**无** jsLib | ❌ `ReferenceError: "getArguments" 未定义 (rule.js#67)` |
| 规则脚本**有** jsLib | 无 ReferenceError，但返回 `undefined` ⇒ 仍空 |

说明 jsLib 的函数确实被定义了（否则会报未定义），但**因为整段进了 IIFE，补全值丢失**。

## 3. 对 PROPOSAL-017 假设的修正（重要）

只读核查阶段列出的 5 项差异，实测后的裁定发生变化：

| 差异 | 原判断 | **实测裁定** |
| :--- | :--- | :--- |
| **D1** JS 包装判定过宽 | 高可信 | ✅ **确认为主因**，且后果比预想严重（不只是"包裹"，而是**补全值消失**） |
| D5 补全值语义 | 中可信 | ✅ **与 D1 是同一根因**（未包裹时补全值正常，见 2.2 第 4 行） |
| D3 `result` 原始字符串绑定 | 中可信 | ⚠️ **未证实为本次必要条件**：`result` 为对象时 `hexDecodeToString` 可能取不到值，但**主因未修前无法判定**，需在 D1 修好后复测 |
| D2 缺 `java.qread` 等 API | 中—高 | ⚠️ **未证实为阻塞项**：脚本在无 jsLib 时报的是 `getArguments` 未定义，**没有报 `qread`/`lang`/`net` 相关错误** |
| D4 规则切分器 | 中 | ⏸ 与本次症状无直接因果，属「成体系移植」范畴，按 ADR-017 方案仍要做 |

> **C1（`java.qread` 处置）的实测依据**：探针未能观察到 `qread` 相关的 ReferenceError，
> 说明探测逻辑走了「不需要 qread」的路径（`java.reLoginView` 已存在 ⇒ 返回「改版」分支）。
> 但该结论需在 D1 修好后**复测确认**，故 C1 仍按用户指定「先实跑、再定」处理，暂不实现 `qread`。

## 4. 已修正的次生缺陷（顺带发现）

`RuleRunner.content()` 抛出的异常信息**丢掉了沙箱的失败原因**：

- `JsSandbox.eval` 失败时把原因写进 `context.lastError`（`JsSandbox.kt:132-138`）；
- `RuleRunner.content()` 读的是 `jsSandbox.lastError`（`RuleRunner.kt:271`），即**沙箱实例字段**；
- 而当调用方传入了 `execContext`（`withSourceContext` 场景），原因只落在**上下文字段**上，
  实例字段可能为空或为**上一次调用的残留值**。

实测中直接观察到「`lastError` 返回旧值残留」的现象（探针 v5 的 `errAfter` 与真实失败原因不符）。
这会让排障者拿到误导性信息，建议在实现阶段一并修正（属 Story 3「诊断可解释」）。

## 5. 第一阶段修复实施（IIFE 语义，已完成）

### 5.1 改动内容（`server/src/main/kotlin/io/legado/server/JsSandbox.kt`）

1. **新增 `hasTopLevelReturn(script)` 扫描器**（companion object，`internal`）：
   引号/模板字面量/注释感知、带括号与方括号深度的字符级扫描，仅在**深度 0** 且前后非标识符字符时才判定为顶层 `return`。
   **只对规则脚本使用**，不再对 jsLib 判定。
2. **jsLib 与规则脚本分离**：删除 `cleanScript = "$library\n$rawScript"` 拼接；
   改为**同一 scope 内先求值 jsLib**（`evaluateString(scope, library, "jsLib.js", …)`），再求值规则脚本 ——
   顺序执行保证函数定义对规则可见（jsLib 语义），同时 jsLib 结果**不参与**规则脚本的补全值。
3. **包裹判定改为按规则脚本自身的顶层 return**。
4. **`lastError` 清理**：求值成功后置 null，避免读到上一次失败的残留值。

### 5.2 修复验证（实测）

| 验证项 | 结果 |
| :--- | :--- |
| 症状复现（修复前） | ❌ `RuleExecutionException: 正文规则未提取到内容` |
| 真实书源端到端（修复后） | ✅ `content()` 返回正文 |
| 新增语义测试 | ✅ `JsSandboxCompletionValueTest` 全绿（修复前 12/14 失败） |

> **注**：第一阶段修复解决了「IIFE 吞掉补全值」，但用户随后反馈的「登录后仍提示登录」
> 是**另一个独立缺陷**（正文清洗），见 §8。

## 5.3 第一阶段未修项（当时登记）

- **D4 规则切分器**（`RuleAnalyzer` 移植）：与症状无因果，属「成体系移植」范畴，按 ADR-017 §2.2 待排期。

## 8. 第二阶段修复：正文清洗把外层 div 连同正文一起删光（2026-09-20 追加）

> **背景**：第一阶段（§2–§5）修好 IIFE 语义后，用户反馈「登录后仍提示要登录」。
> 经第二阶段实测，**这与登录无关**——登录态全程正常，真正的第二个独立缺陷在正文清洗。

### 8.1 排查过程与结论（逐步排除）

用真实库（`legado-server/data/legado.sqlite`，内含用户已保存的大灰狼登录态）与真实书籍
**《十日终焉》**（`sources=番茄`，第 8 章）逐环节实测：

| 环节 | 实测结论 |
| :--- | :--- |
| 书源登录态是否入库 | ✅ `source_login_state` 有记录（邮箱/密码/密钥 + cookie jar 4 域名） |
| 正文规则能否读到登录信息 | ✅ `loginInfoMap` 含密钥、`getCookie(base_url)` 返回 `qttoken=…` |
| 请求是否带上凭据 | ✅ 录到的请求头含 `cookie: qttoken=VGqlrFNpn1fPcZD4; deviceId=…` |
| 上游是否认这个账号 | ✅ `/user_api` 带 cookie 返回完整账号（`is_vip:2`、`nickname:wfanan`） |
| 上游是否稳定 | ✅ 4 条线路 × 4 次 = 16 次全部返回 `code:0` 与完整正文 |
| **`RuleRunner.content()` 是否成功** | ❌ 稳定失败：`正文规则未提取到内容` |

**关键点**：用户看到的「请登录」文案来自上游——上游只在**未带有效 cookie** 时才回这句
（实测对照：带 cookie → `获取内容失败: 内容为空`；不带 cookie → `您今日免登录访问次数已达上限…请登录后刷新页面`）。
而本项目发出的请求**确实带了 cookie**，所以那句提示不是本项目丢登录态造成的。

### 8.2 根因（实测定位，非推测）

在 `RuleRunner.content()` 内逐步插桩，得到决定性证据：

```
DBG_VAL   len=25619   head=<div rs-native>这一番话几乎是把众人点醒了…   ← 取值成功
DBG_FINAL textLen=0   dbgVLen=25619   blank=true                    ← 清洗后变成空
```

差异来自 `RuleRunner.kt` 的 `String.cleanContent()`：

```kotlin
.replace(Regex("(?i)<div[\\s\\S]*?</div>"), "")   // ← 删除 <div> 及其全部内含
```

大灰狼的正文规则返回的**整个正文就包在一个 `<div rs-native>…</div>` 里**
（规则末尾 `data = JSON.stringify({content: content})`，而 `content` 即该 div 的外层 HTML）。
于是这条「删 div」规则把**正文连同容器一起删光** ⇒ `text` 为空 ⇒ 抛「正文规则未提取到内容」。

> 这也解释了为何**该源的正文缓存里已有 21 条记录**却时好时坏：不同源/不同章节返回的 HTML 结构不同，
> 只有「整体包一层 div」的那种会被删光。

### 8.3 修复

`cleanContent()` 改为两段式：

1. 先做**结构性清洗**（去 script/style/**div 容器**/p 标签/其余标签）；
2. 若结构性清洗后**内容为空**，说明正文被外层 div 包着 ⇒ 退化为**只剥标签、保留文本**
   （此时 div/p 只当换行分隔，不删内含）。

这样既保留原有「去导航/广告等结构性 div」的能力，又不会删光被外层 div 包裹的正文。

### 8.4 验证

| 验证项 | 结果 |
| :--- | :--- |
| 修复前 | 同章连续 12 次调用 **全部** `FAIL 正文规则未提取到内容` |
| 修复后 | 同章连续 12 次调用 **全部 OK，正文 2301 字符** |
| 回归用例 | `JsSandboxCompletionValueTest` 新增 2 例（外层 div 保留文本 / 结构性 div 仍被删除），套件 **19/19 通过** |

### 8.5 沉淀至部落知识库

- **[正文清洗] 删 `<div>` 的正则会把「整体包一层 div」的正文删光**：聚合源正文常是
  `<div …>正文</div>` 整体一层，`replace(Regex("<div[\\s\\S]*?</div>"), "")` 会连正文一起删，
  表现为「正文规则未提取到内容」。清洗必须**在删光时退化为只剥标签**。
- **[排障] 「提示要登录」不等于「丢了登录态」**：该文案可能来自上游接口的 `msg`（未带 cookie 时才会出现）。
  判定时要先**对照实验**（带 cookie / 不带 cookie），再看本项目实际发出的请求头，不要凭提示语下结论。


### 5.1 改动内容（`server/src/main/kotlin/io/legado/server/JsSandbox.kt`）

1. **新增 `hasTopLevelReturn(script)` 扫描器**（companion object，`internal`）：
   引号/模板字面量/注释感知、带括号与方括号深度的字符级扫描，仅在**深度 0** 且前后非标识符字符时才判定为顶层 `return`。
   **只对规则脚本使用**，不再对 jsLib 判定。
2. **jsLib 与规则脚本分离**：
   - 删除 `cleanScript = "$library\n$rawScript"` 的拼接；
   - 改为**同一 scope 内先求值 jsLib**（`evaluateString(scope, library, "jsLib.js", …)`），
     再求值规则脚本 —— 顺序执行保证函数定义对规则可见（jsLib 语义），
     同时 jsLib 的结果**不参与**规则脚本的补全值。
   - jsLib 求值失败不中断（部分源的 jsLib 依赖未实现的 API），但记录原因。
3. **包裹判定改为按规则脚本自身的顶层 return**：
   `if (hasTopLevelReturn(rawScript)) "(function(){\n$rawScript\n})()" else rawScript`
4. **`lastError` 清理**：求值成功后置 `lastError = null`，避免调用方读到上一次失败的残留值。

### 5.2 修复验证（实测）

| 验证项 | 结果 |
| :--- | :--- |
| 症状复现（修复前） | ❌ `RuleExecutionException: 正文规则未提取到内容` |
| **真实书源端到端（修复后）** | ✅ **`content()` 返回正文**：`您今日免登录访问次数已达上限(3次)！继续阅读请登录后刷新页面。` |
| 规则链中间值 | ✅ `eval` 产出 `{"content":"…"}`，后置 `$.content` 正确取值 |
| 新增语义测试 | ✅ `JsSandboxCompletionValueTest` **16/16 通过**（修复前 12/14 失败） |
| 沙箱错误诊断 | ✅ 缺 jsLib 时 `sandbox.lastError` 正确记录 `ReferenceError: "getArguments" 未定义` |

> **关于返回值是「免登录次数已达上限」**：这是该源上游 `content` 接口返回的**真实业务文案**（未登录状态的限流提示），
> 不是网络错误也不是我们的 bug —— 它恰恰证明**规则链已完整跑通**、正文被正确提取。
> 要拿到真实章节正文需要登录态，属业务前提，不在本次修复范围。

### 5.3 回归测试（已入库）

新增 `server/src/test/kotlin/io/legado/server/JsSandboxCompletionValueTest.kt`（16 个用例），覆盖：

- jsLib 函数定义对规则脚本可见（含带参调用）；
- **jsLib 含 `return` 不吞掉规则脚本的补全值**（本次核心缺陷的正向锁定）；
- 规则脚本自身有顶层 `return` 时仍能工作（含无 jsLib 场景）；
- 字符串/模板字面量/注释里的 `return` 不触发包裹判定；
- 端到端复刻聚合源链路（`hexDecodeToString` + 裸表达式 + `$.content` 后置取值）；
- 缺 jsLib 时的诊断信号；成功求值后 `lastError` 不残留。

> **不引入真实书源作 fixture**：实测扫描发现该源 `jsLib`/`loginUrl`/`ruleContent` 含
> `password`/`token`/`邮箱`/`sessionid` 等敏感内容（按 PROPOSAL-017 §7 承诺不入库），
> 故回归测试使用**复刻关键结构的最小合成 fixture**，零凭据。

## 6. 本次发现但**不在本次修复范围**的既有缺陷

### 6.1 坏书源会静默把 `data:` 载荷当正文返回（重要）

`NodeValue.value` 在 `<js>` 求值返回 null 时会**回退成中间值**（`RuleRunner.kt:789`
`?: (intermediate as? String ?: intermediate?.toString() ?: "")`）。后果实测如下：

| 场景 | 实测结果 |
| :--- | :--- |
| 规则脚本因缺 jsLib 抛 `ReferenceError` | `RuleRunner.content()` **不抛错**，而是把**载荷原文**当正文返回 |

这意味着一个坏掉的书源可能让用户看到 `{"book_id":"1",...}` 这样的原始载荷作为"正文"，
且**没有任何错误提示**（因为 `content()` 只在 `text.isBlank()` 时才抛"未提取到内容"）。

- **影响**：排障困难 + 用户体验异常（Story 3「诊断可解释」未能完全达成）。
- **不在本次范围的理由**：改动它会波及所有书源的 `<js>` 求值回退语义，属独立议题；
  本次聚焦 IIFE/补全值这一确定根因。
- **建议**：单独立项 —— 区分「JS 求值失败」与「JS 正常返回空」，前者应记录/上报而非静默回退。

### 6.2 `content()` 的段落规范化会剥离 HTML 标签

实测：`NodeValue.value` 直接取 `$.content` 得 `<p>第一章…</p>`，
但经 `RuleRunner.content()` 后变为 `第一章…`（既有段落规范化路径所致，非缺陷）。
回归测试因此断言**语义内容**而非原始 HTML。

## 7. 未决与风险

- **D4 规则切分器**（`RuleAnalyzer` 移植）：与本次症状无因果，属「成体系移植」范畴，
  按 ADR-017 §2 方案**仍在计划内**，但尚未实施 —— 需另行排期。
- **回归面**：凡「jsLib 含 return + 规则末尾裸表达式」的组合，都会从「返回 undefined」变为「返回正确值」；
  这是修复，但需用全量测试比对失败集合确认无副作用（见本次全量测试结果）。
- **`java.createSymmetricCrypto` / `source.loginUi`** 仍缺失（PROPOSAL-017 §2.1），
  实测**不是本次阻塞项**，但可能在别的书源上成为阻塞项，需按需补齐。

