# PROPOSAL-030 · 书籍变量（书籍级自定义变量）与听书朗读者切换

- **状态**：Implemented（本地已实现并通过单测；待验收）
- **关联**：[ADR-030](../decisions/ADR-030-book-variable-json-column-and-custom-key.md) · [ACCEPT-030](../acceptance/ACCEPT-030-book-variables.md) · [PROPOSAL-029](PROPOSAL-029-audiobook-support-and-cache-playback.md)（有声书体系，依赖本能力实现"切换朗读者"）
- **参考实现**：开源阅读 `legado-with-MD3`（本仓库的兼容目标）

---

## 1. 需求来源（用户原话）

> 「添加书籍变量设置栏，有声书可以用来切换朗读者……**书籍变量放在编辑书籍信息里**」
>
> 「**根据开源阅读的方法来**」

真实书源里也能直接读到这项能力的用途 —— `🍅大灰狼聚合5.9.30` 自带说明：

> 「书旗听书：登录书源后将模式切换为"听书"……默认音色为 multi_role 多角色对话；
> **详情页会显示本书可用音色及数字编号，右上角书籍变量填写编号即可切换**。」

对应的规则实现（该源真实代码）：

```js
let toneId = String(book.getVariable('custom'));   // ← 音色编号
if (toneId == 'null') { toneId = ''; }
...
if (!data || !book.variable || !base_url || !qtcookie) { java.log('缺少必要参数'); }
```

也就是说：**"切换朗读者"在服务端一直是静默失效的** —— 用户在界面上根本没有地方填，
规则里 `book.getVariable('custom')` 永远拿到空值，且**不报错**。

---

## 2. 现状与缺口

| 环节 | 现状（改动前） |
| :--- | :--- |
| 存储 | **无**。`book_shelf` 没有任何变量列，`source_login_state.source_variable` 只服务**书源**变量 |
| 沙箱 | `JsExecutionContext.bookVariables` 早已存在、`book.getVariable/setVariable` 早已导出，**但从来没有代码往里填** ⇒ 恒返回 `"null"` |
| `book` 对象 | `book.variable` **属性根本不存在**；`book.name/author/bookUrl/tocUrl` 也从未被填充（恒空串） |
| 接口 | 无任何读写书籍变量的端点 |
| 备份 | 导入**丢弃** `bookshelf.json` 的 `variable` 字段；导出也不写 ⇒ 换设备即丢设置 |

结论：这不是"新增一个花哨功能"，而是**补齐 Legado 既有协议里缺失的一环**。

---

## 3. 目标

1. 书籍变量可按书存储、可编辑、可被书源规则读到，**语义与开源阅读逐条一致**。
2. 编辑入口放在**「编辑书籍信息」弹窗**内（用户明确要求）。
3. 有声书可凭它切换朗读者/音色；小说/漫画同样可用（协议是通用的）。
4. 备份导入导出**带得走**。

## 4. Non-Goals

- **不做**"音色候选列表下拉框"：候选值由书源 JS 自行计算（如该源把可用音色列表拼进详情页文案），
  服务端解析它属于**源特定的脆弱耦合**。编辑形态照开源阅读——一个值输入框 + 用法提示。
- **不做**超大变量（Legado 的 `RuleBigDataHelp` 把 ≥10000 字符的值外置到文件）。本服务只做
  常规小值；超限由前端/接口长度约束兜住，不引入第二套存储。
- **不引入**按变量分派的服务端业务逻辑（服务端只负责存取，解释权在书源规则）。

---

## 5. 方案（对齐开源阅读）

### 5.1 存储形态：`book_shelf.variable` = 一个 JSON 对象字符串

开源阅读 `BaseBook`：

```kotlin
var variable: String?                      // JSON 字符串
override val variableMap by lazy { GSON.fromJsonObject<HashMap<String,String>>(variable).getOrNull() ?: hashMapOf() }
override fun putVariable(key: String, value: String?): Boolean { ...; variable = GSON.toJson(variableMap) }
fun putCustomVariable(value: String?) = putVariable("custom", value)
fun getCustomVariable(): String = getVariable("custom")
```

因此本服务：

- `book_shelf` 新增 `variable text` 列（幂等迁移），存**整张表**的 JSON；
- `RuleDataInterface.getVariable(key)` = `variableMap[key] ?: getBigVariable(key) ?: ""`
  ⇒ **键不存在返回空串**（不是 `"null"`）；
- `custom` 是**一等公民**：编辑弹窗只编辑它，服务端做**合并写入**，其余键原样保留。

> 为什么不做成独立的 `book_variable` 表：开源阅读把它作为 `Book` 实体的字段一路随书保存
> （`saveBook`），本服务的 `book_shelf` 正对应那张表；独立表会多一次 join，还会让"这本书在书架里
> 才有变量"这条天然约束变成需要额外维护的规则。

### 5.2 沙箱：`book` 桥接对象补全

| 成员 | 语义 |
| :--- | :--- |
| `book.variable` | 整张表的 **JSON 字符串**（空表 ⇒ **空串**，保持 `if (!book.variable)` 判空语义） |
| `book.variableMap` | 变量表对象（`book.variableMap['custom']`） |
| `book.getVariable(key)` | 取不到 ⇒ **空串** |
| `book.putVariable(key, value)` | `value == null` ⇒ 删除该键；否则写入 |
| `book.name/author/bookUrl/tocUrl/kind/type` | 书籍上下文（`type`：0=文本 1=音频 2=图像） |

规则执行期间对变量的修改**内存生效**，调用方在规则跑完后比对落库
（等价于 Legado「改完 `Book` 对象再 `saveBook`」）。

### 5.3 上下文注入范围

`RuleRunner.sourceContext()` 原先只装书源信息。现在按书注入 `BookContext`，覆盖四条链路：

`details` / `chapters` / `content` / `mangaImages`，以及离线的 `BookCacheService`、`MangaPrefetchService`
（**离线缓存必须与在线阅读同上下文**，否则"点开能看、后台缓存却取到别的版本"）。

### 5.4 接口

- `GET /api/bookshelf`、`GET /api/books/details` 透出变量（后者给 `variables` 键值表）；
- `PUT /api/bookshelf/info` 新增 `customVariable`：
  - **缺省（null）⇒ 不动**已有变量（"只改书名点保存"绝不能清掉音色）；
  - 传值 ⇒ 只写 `custom`，其余键保留。

### 5.5 备份

`bookshelf.json` 的 `variable` 字段**导入 + 导出**双向支持；导入时
`coalesce(excluded.variable, book_shelf.variable)` —— 备份里没有就**保留本机已有的**。

---

## 6. User Stories

1. 作为听书用户，我打开某本书的「编辑书籍信息」，在**书籍变量**里填上详情页提示的音色编号，
   保存后重新播放 → **朗读者已切换**（且不再需要清缓存）。
2. 作为听书用户，我只想改书名 → 保存后**音色设置不丢**。
3. 作为换设备的用户，我导入手机备份 → 音色设置**跟着回来**。
4. 作为书源作者，我的规则里 `book.getVariable('custom')` / `book.variable` / `book.variableMap`
   **都能拿到值**，且缺失键是空串而不是 `"null"`。

---

## 7. 验收要点

见 [ACCEPT-030](../acceptance/ACCEPT-030-book-variables.md)。核心可观察行为：

- 填 `custom=6` → 规则里读到的就是 `6`；
- 缺省保存 → 值不变；
- 导入/导出备份 → 值往返一致。
