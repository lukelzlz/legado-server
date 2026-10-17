# SESSION-049 · 书籍变量实现：一个"静默失效"了三年的能力，与它暴露出的沙箱静默回退

- **日期**：2026-10-16
- **类型**：Feat（含一个真实缺陷的定位）
- **关联**：[PROPOSAL-030](../proposals/PROPOSAL-030-book-variables-and-narrator-switching.md) · [ADR-030](../decisions/ADR-030-book-variable-json-column-and-custom-key.md) · [ACCEPT-030](../acceptance/ACCEPT-030-book-variables.md)

---

## 1. 起因：用户要"切换朗读者"

用户要求「添加书籍变量设置栏，有声书可以用来切换朗读者」，并明确两点约束：
**① 放在"编辑书籍信息"里；② 根据开源阅读的方法来。**

## 2. 定位：不是新功能，是缺失的一环

先在真实数据里取证（服务端上跑着的书源就是最好的样本）。`🍅大灰狼聚合5.9.30` 的
`bookSourceComment` 自带说明：

> 「书旗听书：……默认音色为 multi_role 多角色对话；**详情页会显示本书可用音色及数字编号，
> 右上角书籍变量填写编号即可切换**。」

它的真实规则里：

```js
let toneId = String(book.getVariable('custom'));      // ← 音色
if (toneId == 'null') { toneId = ''; }
...
if (!data || !book.variable || !base_url || !qtcookie) { java.log('缺少必要参数'); }
```

再回看本服务：

| 环节 | 实际状态 |
| :--- | :--- |
| `JsExecutionContext.bookVariables` | **存在**，默认 `mutableMapOf()` |
| 沙箱 `book.getVariable/setVariable` | **已导出** |
| 谁往里填？ | **没有任何代码** ⇒ 恒为 `"null"` |
| `book.variable` 属性 | **根本没定义** |
| `book.name/author/bookUrl/tocUrl` | **从未被填充**（`sourceContext()` 只装书源信息） |

⇒ 「切换朗读者」在服务端一直**静默失效**：用户没有入口填，规则读到空值，且**不报错**。

## 3. 实现：逐条对齐 `legado-with-MD3`

参照实现就在工作区里（用户提供），无需猜测：

- `BaseBook`：`var variable: String?` + `variableMap by lazy { GSON.fromJsonObject<HashMap<String,String>>(variable) }`；
- `RuleDataInterface`：`getVariable(key) = variableMap[key] ?: getBigVariable(key) ?: ""` ← **空串**；
- `BookInfoViewModel`：编辑组件就是**一个多行文本框（label=`variable`）+ 注释**，
  内容是 `book.getCustomVariable()`（即 `custom` 键），提示语
  `书籍变量可在 js 中通过 book.getVariable("custom") 获取`。

落地：`book_shelf.variable`（JSON 串列，幂等迁移）＋ `BookContext` 贯穿
`details/chapters/content/mangaImages` 与离线缓存 ＋ `customVariable` 合并写入 ＋ 备份双向。

## 4. 踩坑：**沙箱桥接漏挂载 = 规则静默回退**（本次最有价值的发现）

`book.variableMap` 我声明了对象、往里写了键值，**但忘了 `putProperty(book, "variableMap", ...)`**。
后果不是报错，而是：

- `typeof book.variableMap` → `'undefined'`；
- 书源里 `book.variableMap['custom']` → **抛 TypeError**；
- `<js>` 求值失败后，正文规则**自动回退**到"抓页面正文"分支 ⇒ 用户看到的是"规则没生效"，
  **日志里什么都没有**。

这正是 AGENTS.md 反复强调的「静默丢数据比抛错危险得多」。两个用例把它钉住了：

- `book exposes name author kind and json variable map`（断言 JS 侧真的读到值）
- `missing variable reads as empty string not the literal null`（断言 `typeof` 与空串语义）

**通用教训**：给沙箱桥接对象加成员时，**声明 ≠ 可见**；必须用"从 JS 侧读一次"的用例兜底，
因为漏挂载在宿主侧是合法的、在 JS 侧只是一个 `undefined`。

## 5. 验证

- `BookVariableTest` 12 用例 + `BookVariableRoutesTest` HTTP 闭环，全绿；
- 全量 `:server:test`：**523 用例 / 57 失败**，与干净基线（`04724f2`，**510 用例 / 57 失败**）
  **失败集合逐条比对完全一致** ⇒ 零回归（禁止只看失败数量的铁律照做）；
- 前端 `npm run check` 通过、`tsx web/test/run-all.ts` 238 用例全绿。

## 6. 未做（明确的 Non-Goals）

- 音色候选项下拉框（候选由书源 JS 自行计算，解析它属于源特定的脆弱耦合）；
- Legado 的超大变量外置（`RuleBigDataHelp`，≥10000 字符落文件）——本场景用不到；
- 并发离线缓存路径**不回写**规则里新写的变量（用副本避免竞态，已在 ADR 里记录该限制）。
