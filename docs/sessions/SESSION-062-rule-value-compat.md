# SESSION-062 取值规则兼容补齐：`||` 回退、多类名、负数索引、文本选择器

- 日期：2026-11-17
- 分支：`fix/rule-value-compat`（**堆叠在 `fix/content-pagination` 之上**：先合分页 PR，再合本 PR）
- 关联：`server/src/main/kotlin/io/legado/server/RuleRunner.kt`（`NodeValue.value()` / `selectAllLegado()`）、`server/src/test/kotlin/io/legado/server/RuleValueCompatTest.kt`
- 来源：自建实例实测（书源 `www.dbxsd.com`、`www.wxscs.com`）

## 目标

把「规则写对了却取不到值」这类**静默失败**补齐。四条写法在上游旧实现里全部恒返回空，
且日志里没有任何错误线索——用户只看到"目录 0 章""正文取不到""分页不生效"。

| 写法 | 旧行为 | 真实后果 |
| :-- | :-- | :-- |
| `A||B` 主备选择器 | 整串丢给 Jsoup/JsonPath | 恒空 ⇒ 目录/分页规则失效 |
| `class.a b`（多类名） | 当成 CSS 后代选择器 | 恒空 ⇒ 目录 0 章 |
| `a.-1` / `class.x.-1`（负数索引） | `coerceAtLeast(0)` 折成 `.0` | 永远取第一个 ⇒ 拿错链接 |
| `text.下一页@href` | 不认 `text.` 前缀（见 SESSION-061） | 恒空 ⇒ `nextContentUrl` 永远为空 |

## 设计要点

### 1. `A||B` 回退：切分必须引号/正则感知

书源里 `||` 有三种身份，只有第一种该切：

1. 规则分隔符：`text.下一章@href||class.m-page@a.-1@href`
2. JS 逻辑或：`@js: result == null || result == '' ? 'x' : result`
3. 规则内容的一部分：`##a||b##`（正则）或 `[data-t="a||b"]`（引号内）

`splitAlternatives()` 逐字符扫描，跟踪**引号状态**（含 `\` 转义）与 **`##` 正则段状态**，
只在两者之外切 `||`。JS 规则（`@js:` / `js:` / `<js>…</js>`）整体跳过，
判定复用文件级 `isJsRuleText()`——原先这套判定在 `RuleRunner` 里写着，为避免两处漂移抽成了单点。

### 2. 一个差点上线的坑：切不开时递归会栈溢出

初版写成「只要有 `||` 就走回退分支」。但 `##a||b##` 这种**切不开**的规则会返回
「只含一条、且与原文相同」的列表 ⇒ 再次 `value()` 同一条规则 ⇒ 无限递归 ⇒ `StackOverflowError`。
单测直接把它打出来了（`quoted pipes inside a selector are not split` 的第一版就是 `##a||b##` 形态）。

修法：只有**真的切开了**（多个分支，或单分支但已与原文不同）才走回退，
否则继续走原有的正常解析路径。这类"静默失败转成进程级崩溃"的改动必须由测试兜住，
不能靠肉眼 review。

### 3. 多类名 `class.a b`

规则作者把它当"同时具备两个类"。Jsoup 的 `getElementsByClass("a b")` 正好按空白拆成
多个类名做 **And** 匹配，而 CSS 选择器 `.a b` 是后代选择器 ⇒ 恒空。命中即走 `getElementsByClass`。

### 4. 负数索引

`a.-1` = 最后一个 a。正则放开到 `-?\d+`，取值时 `ordinal < 0` 走 `matches.size + ordinal`。
两个入口（`class.x.-1`、`tag.-1`）都要改，否则会出现"一种写法能用、另一种不能用"的诡异现象。

### 5. 文本选择器取祖先属性

`text.下一页` 命中的常是内层元素：`<a href="/p2.html"><button>下一页</button></a>`。
`button` 自身没有 `href`，属性在祖先上。只在**选择器链里出现过 `text.`** 时才向上找
（最多 4 层），普通 CSS 规则的取值语义完全不动——避免"顺手放宽"改坏别的书源。

## 证据

`RuleValueCompatTest` 9 条，全部离线造 HTML：

| 用例 | 锁定的行为 |
| :-- | :-- |
| 主备回退命中第二分支 / 全空返回 null | `A||B` 语义 |
| 只返回第一个非空分支 | 不做拼接 |
| `@js:` 里的 `||` 不被切 | JS 逻辑或 |
| 引号内的 `||` 不被切 | 切分闸门（同时覆盖栈溢出回归） |
| `class.col-md-6 text-center` 要求两个类都在 | 多类名 And 语义 |
| `a.-1` / `class.ch.-1` | 负数索引（两个入口） |
| `text.下一页@href` 取到祖先 `<a>` 的 href | 祖先查找 |
| 文本与属性同元素时直取 | 不破坏原语义 |

| 项 | 结果 |
| :-- | :-- |
| `./gradlew :server:test --tests RuleValueCompatTest` | **9 用例 0 失败** |
| `./gradlew :server:test`（全量） | **780 用例 / 0 失败 / 3 跳过**（在分页 PR 的 771 之上 +9） |
| 自建实例 `dbxsd` 目录 | 规则 `class.col-md-6@a` 修正后 **2285 章**（旧实现 0 章） |

## 已知边界 / 未做

1. **`||` 只做"取第一个非空"**：不支持 Legado 更少见的 `&&`（按序拼接）语义，本次不引入。
2. **负数索引不与 `!exclude` 语法交互**：`a!-1` 这类排除写法仍是原有行为，未做联合验证。
3. **祖先属性最多向上 4 层**：够覆盖 `a > button`、`a > span > i` 这类按钮装饰，再深就属于规则本身写得离谱。
4. 多类名只认 `class.` 前缀形态；`id.x` / `tag.x` 的多值写法不在本次范围。
