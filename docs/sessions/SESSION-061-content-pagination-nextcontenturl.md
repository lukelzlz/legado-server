# SESSION-061 正文分页：沿 `ruleContent.nextContentUrl` 跨页拼接

- 日期：2026-11-17
- 分支：`fix/content-pagination`（基分支 `main@7dab613`）
- 关联：`server/src/main/kotlin/io/legado/server/RuleRunner.kt`（`content()`）、`server/src/test/kotlin/io/legado/server/ContentPaginationTest.kt`
- 来源：自建实例实测（书源 `www.dbxsd.com`、`www.wxscs.com`、`so.html5.qq.com`）

## 目标

把「一章拆成多页」的站点读全：按 `ruleContent.nextContentUrl` 逐页抓取并拼接，
且**不越过本章边界**、**不因分页链接自指而死循环**。

## 现状（为什么要改）

`content()` 只在开头 fetch 一次章节页，`nextContentUrl` 字段**读了却从不使用**：

```kotlin
val body = fetchUrl(url, mergedOptions, ...)   // 全书只此一次
```

后果是所有分页站点的每章都只有第一页，且**没有任何报错**——用户只看到"正文断在半截"。
更隐蔽的是 `nextContentUrl` 的写法 80% 是 `text.下一页@href`，而 `selectAllLegado()`
根本不认 `text.` 前缀（恒返回空集），所以即使有人给书源补了分页规则也取不到值。
两件事必须一起修，否则分页能力等于「写了也不生效」。

## 设计要点

### 1. 循环主体

`content()` 的抓取段改成一个 while：抓当前页 → 抽正文 → 求下一页候选 → 推进。
约束三条：`pageCount < 10`（防御性上限）、`visitedPages`（地址去重）、
`isSamePageSeries()`（同章节系列判定，见 §2）。

首页例外处理：`data:` 载荷（聚合源）没有"页"的概念，直接当唯一一页；
`title` / `replaceRegex` 恒以**首页**为准（与单页语义保持一致）。

### 2. 同一章节系列判定（防串下一章）

末页的分页容器往往和「下一章」链接在同一块 DOM 里，`.page a@href` 一命中就把
**下一章正文**拼进本章。因此只允许在"同一章节的页序列"内推进：

| 形态 | 判定 |
| :-- | :-- |
| `100.html` → `100_2.html` | 取文件名主干并剥掉 `_\d+` / `-\d+` 后缀，主干相等 ⇒ 同系列 |
| `x.html?page=2` | 两侧都带 query ⇒ 只比 `?` 之前的路径 |

这条闸门是"宁可不抓"的保守策略：判不出来就停，不会污染正文。

### 3. 已访问集合（防死循环）

分页链接指回自己、或 A ⇄ B 互指时，靠 `visitedPages` 截断，每个地址只抓一次。
单测 `self referencing next link does not loop` / `back and forth next links are visited once`
锁定「抓取次数」而不是只看结果，否则死循环会被页数上限掩盖。

### 4. 选项后缀必须拆开

部分书源把接口写成 `https://…/api/chapter,{"method":"POST","body":"{…}"}`。
旧实现把整串当 URL 交给 `HttpClient`，抛 `Illegal character in path`（真实书源实测）。
现在统一走 `splitUrlOptions()`：**纯 URL 进 `fetchUrl`，选项单独 `mergeOptions`**。
分页候选也照此处理——POST 分页接口的选项不能在第 2 页丢掉。

### 5. `text.XXX` 选择器

`selectAllLegado()` 增加 `text.` 前缀分支：按 `ownText()` 包含匹配选元素。
这是 `nextContentUrl` 最常见的写法，本 PR 内属于**硬依赖**而不是顺手改。

### 6. 首页语义

`title` 与 `replaceRegex` 只在首页求值 / 作用于拼接后的全文，
保证"首页标题就是本章标题"，不会因为后续页出现另一个 `<h1>` 而被覆盖。

## 证据

单测（离线造页，不打真实站点）：新增 `ContentPaginationTest` 7 条 ——
多页顺序拼接 / 末页不吞下一章 / 自指不死循环 / A⇄B 只抓一次 / `,{…}` 选项不入 URL /
无 `nextContentUrl` 只抓一次 / 标题与替换规则以首页为准。

| 项 | 结果 |
| :-- | :-- |
| `./gradlew :server:test --tests ContentPaginationTest` | **7 用例 0 失败** |
| `./gradlew :server:test`（全量） | **771 用例 / 0 失败 / 3 跳过**（`main@7dab613` 基线为 764 用例全绿，本次 +7） |
| 自建实例 `dbxsd` 同一章 | 拼页前 2365 字 → 拼页后 **4282 字**；末章 2365 → **10507 字**（5 页） |
| 自建实例 `wxscs` 同一章 | 1703 字 → **4282 字**（3 页） |
| 自建实例 `so.html5.qq.com`（无 `nextContentUrl`） | 4282 字，**与改动前一致**（不该多发请求的没多发） |
| 抽样 12 个分页书源 | 字数只增不减，无 `Illegal character in path`、无死循环 |

## 已知边界 / 未做

1. **页数上限硬编码 10**：正常章节 2–6 页，10 页足够；真遇到更长的书源需要时再提成配置。
2. **同系列判定是启发式**：只认「文件名主干 + `_\d+`」与「query 前路径」两种主流形态。
   像 `…/c/1.html` → `…/c/1/2.html` 这种路径式分页判不出来，会**少拼**（保守方向），
   不会污染正文。要覆盖就得引入"页面自证"（比如分页容器里回链首页），复杂度不划算。
3. **不处理"下一页"是纯 JS 跳转**：`onclick` 驱动的分页无从得知目标地址。
4. 分页失败的容错：某页抓取抛错会**整体失败**（不静默吞掉半章），
   与单页时代的错误语义一致；这比"拼一半给用户"更容易被发现。
