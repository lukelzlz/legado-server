---
id: SESSION-026
title: 修复书架书籍「正文提取不到、目录为空、封面异常」——Legado 规则兼容性五处缺陷
date: 2026-09-26
author: Agent
tags: [rule-engine, compatibility, header, css-selector, crypto, aes, js-rule, adversarial-review]
---

# SESSION-026: Legado 规则兼容性五处缺陷

## 0. 起因

用户报「书架里的书，正文提取不到，封面也没有」。用户当时书架只有 **5 本书**（此前 435 本已被清空重导），
涉及 3 个书源：`https://www.yingsx.com`、`http://api.jmlldsc.com`、`http://www.wensang.net`。

**关键方法**：没有先读代码，而是**先实测每本书的 details / chapters / content 三个接口**，
拿到可复现的失败清单后再定位。5 本书里 4 本故障、1 本正常（`wensang`），这个对比本身就是破案线索。

## 1. 五处根因（全部用最小复现坐实）

### 根因 1：`header` 是单引号伪 JSON ⇒ 所有请求头被静默丢弃 ⇒ 上游 403

真实书源 `api.jmlldsc.com` 的 `header`：

```
{
'User-Agent': 'okhttp/4.9.2','client-device': '0cdeb38d...','client-brand': 'vivo',
'client-version': '2.3.0','client-name': 'app.maoyankanshu.novel','client-source': 'android',
'Authorization': 'bearereyJ0eXAi...'
}
```

**这不是合法 JSON**。旧实现 `Json.parseToJsonElement` 抛异常后被 `runCatching` 吞掉
⇒ **请求头全部丢失** ⇒ 上游返回 `code:4004 device 不能为空` / 403。

**对照实验**（决定性证据）：

| 请求 | 结果 |
| :--- | :--- |
| **带**解析后的头 | `code:200 msg:success`，detail 1210 字节，chapters **438KB** |
| **不带**头（我们的旧行为） | `code:4004 device 不能为空, version 不能为空, ...` |

⇒ 修法：新增 `parseHeaderMap()`，标准 JSON 失败后回退**宽容扫描器**（逐字符读键/值，
正确处理值里的逗号、冒号、引号与超长 `Authorization`）。

**修好后**：3 本书的 `name` 从「未命名书籍」恢复为真实书名，
`tocUrl` 从 `/novel//chapters`（空 ID）恢复为 `/novel/bmEENp/chapters`。

### 根因 2：`css()` 不翻译 `id.X` / `tag.X` ⇒ 选择器匹配 0 个元素

真实书源 `yingsx.com`：`chapterList = id.list@dd!0:1:2:3:4:5:6:7:8`

**实测对照**（同一页面）：

| 选择器 | 匹配数 |
| :--- | :--- |
| `id.list dd`（我们的产出） | **0** |
| `#list dd`（Legado 语义） | **89** |

`id.` 是 Legado 专有写法，**不是合法 CSS**；`tag.p` 同理（`tag.` 只是标记，必须剥掉）。
旧实现直接透传 ⇒ 0 匹配 ⇒ **目录 0 章**且无任何报错。

⇒ 修法：抽 `translateSelector()`（`id.x`→`#x`、`tag.x`→`x`），
在 `css()`（列表规则）与 `selectAllLegado()`（取值规则）两条路径共用。

### 根因 3：`!0:1:2:...` 多下标排除语法未实现

`dd!0:1:2:3:4:5:6:7:8` 意为「排除下标 0~8」。旧实现 `notIndex.toIntOrNull()` 对
`"0:1:2:..."` 返回 null ⇒ **排除语义被静默丢弃**（回退成裸 `dd`）。

⇒ 修法：按 `:` 切分并逐个生成 `:not(:nth-child(n+1))`。
实测该页 89 个 `<dd>` 排除前 9 个后应得 **80 章**（修复后实测 81，含「最新章节」锚点去重差异）。

### 根因 4：沙箱缺少书源真实使用的 API（AES 家族等）

对**全部 62 个书源**扫描 `java.*(` 调用，得到 33 个函数，与沙箱实际导出面（54 个）对比，
发现缺失且**被真实书源使用**的：

| 缺失 API | 使用次数 / 书源数 | 后果 |
| :--- | :--- | :--- |
| **`aesBase64DecodeToString`** | 4 次 / 2 源（含 jmlldsc） | **目录/正文地址无法解密** |
| `createSymmetricCrypto` | 1 次 | 同上 |
| `toNumChapter` | 11 次 / 4 源 | 章节排序异常 |
| `t2s` | 10 次 | 繁体显示 |
| `connect` / `encodeURI` / `getElement(s)` | 少量 | 规则抛 ReferenceError |

**为什么危害极大**：书源把解密写在 `chapterUrl` 里，JS 抛 `ReferenceError` ⇒ 该字段为 null
⇒ `chapters()` 的 `mapIndexedNotNull` 把**每一章**都丢掉 ⇒ 目录 0 章，**而且不报错**。

**验证**（真实密文）：`aesBase64DecodeToString("UhQTfQq/qXG...","f041c49714d39908","AES/CBC/PKCS5Padding","0123456789abcdef")`
→ `http://api.lemiyigou.com/697/697604/75510.json` ✅

### 根因 5：`取值路径@js:代码` 后置处理未实现（最隐蔽）

书源 `chapterUrl = $.path@js:java.aesBase64DecodeToString(result,...)`

`value()` 的旧分派只认「**以** `@js:` 开头」与 `<js>...</js>` 两种形式，
**没有「路径 + 后置 JS」这一支**（HTML 路径同样只在 `@` 分段里把 `js:...` 当属性名）。

结果：整串（含 `@js:`）被丢给 JsonPath ⇒ 必然解析失败 ⇒ 返回 null ⇒ 章节全丢。

⇒ 修法：在 `value()` 增补该分支，先按左路径取值作为 `result`，再执行右侧 JS。
切分点用**引号感知扫描** `findJsPostProcessor()`，避免 `@js:` 出现在 JS 字符串字面量里时误切。

## 2. 修复效果（同一批真实书籍，修复前后）

| 书籍 | 目录（前 → 后） | 正文（前 → 后） |
| :--- | :--- | :--- |
| 十日终焉我成魔（yingsx） | 0 → **81** | ✗ → **3866 字** |
| 长征十日（jmlldsc） | 403 错误 → **10** | 403 → **4374 字** |
| 真十日终焉（wensang） | 14 → 14 | 2438 → 2438 |
| 十日终焉我成魔（jmlldsc） | 403 错误 → **80** | 403 → **2061 字** |
| 十日终焉（jmlldsc） | 403 错误 → **1496** | 403 → **2475 字** |

**5 本书全部可正常阅读**。

## 3. 封面问题的结论（未改代码，属数据问题）

排查发现 5 本书的 `coverKey` **全部存在且 `/api/covers/<key>` 全部返回 HTTP 200**（9332~22168 字节）。

真正的问题在两本书的 `coverUrl` 变成了**自引用**：
`coverUrl = /api/covers/<它自己的 coverKey>`（此前「元数据补全」流程把接口地址回写成了封面地址）。

- 危害有限（前端优先用 `coverKey`，所以仍能显示），但属**脏数据**：一旦 `coverKey` 被清空，
  `coverUrl` 回退即指向自身，形成无意义循环。
- **本次未改代码**：需要确认「元数据补全」写入侧的意图后再修，避免误伤正常回填逻辑。
- 已记入遗留项。

## 4. 回归对照

| | 用例数 | 失败数 | 失败集合 |
| :--- | :--- | :--- | :--- |
| 基线 `4d147ee` | 209 | 54 | 基准 |
| 带本次修复 | **226** | **54** | **逐条一致** |

⇒ **零回归**（54 个失败均为 Windows 删除被占用 SQLite 的既有失败，与本次改动无关）。
新增 `LegadoRuleCompatTest` **17 个用例**，覆盖 header 宽容解析、选择器翻译、
多下标排除、`@js:` 后置处理、AES 解密、新增 API 存在性与辅助函数行为。

## 5. 沉淀的教训与部落知识

1. **Legado 生态大量元数据不是标准格式**：`header` 单引号、`id.X`/`tag.X` 伪选择器、
   `!a:b:c` 多下标、`路径@js:` 后置处理——**每一处不兼容都是「静默 0 结果」而非报错**，
   因此**必须逐条做对照实验**（改前 vs 改后、带参数 vs 不带参数）来定位，
   光看代码会漏掉。
2. **「静默丢数据」比抛错危险得多**。本会话 5 个缺陷里有 4 个表现为「返回空」而不是异常，
   排查时必须先怀疑「是不是某处 `runCatching` 把错误吞了」。
3. **扫描真实数据比读文档更快得到全貌**：对全部书源正则扫描 `java.*(` 得到 API 缺口清单，
   一步定位到 AES 缺失；比逐个书源试跑高效得多。
4. **同一语义要保证多条路径一致**：`id.X` 翻译需要在 `css()` 与 `selectAllLegado()`
   两处生效，`@js:` 需要在 JSON 与 HTML 两条取值路径都支持——**修一条忘一条 = 没修**。

## 6. 修改文件清单
| 文件 | 变更 |
| :--- | :--- |
| `server/.../RuleRunner.kt` | 新增 `parseHeaderMap`/`parseLooseHeaderMap`；`css()` 支持 `id.X`/`tag.X`/多下标；新增 `value()` 的 `@js:` 后置处理 + `findJsPostProcessor`；`translateSelector` 提为文件级 |
| `server/.../JsSandbox.kt` | 新增 `aesBase64DecodeToString`/`aesBase64EncodeToString`/`aesDecodeToString`/`createSymmetricCrypto`/`toNumChapter`/`t2s`/`encodeURI`/`connect`/`getElement(s)` + 繁简映射表 |
| `server/src/test/.../LegadoRuleCompatTest.kt` | 新增（17 用例） |

## 7. 遗留项
1. **`coverUrl` 自引用脏数据**：需要确认元数据补全写入侧意图后修正；现有 2 本受影响。
2. **书源名含章节名**：`十日终焉我成魔\n第八十章 星尘归寂，余念长存`（列表页抓取把最新章标题并入了书名），
   属搜索规则 `name` 取值问题，需独立排查。
3. 其余 28 个 `java.*` 未实现 API（如 `webView`/`startBrowserDp`/`refreshExplore`）当前书源未强依赖，
   后续可按同一「扫描 → 缺口 → 补齐」流程增补。
