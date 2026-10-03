# ACCEPT-028：订阅源（RSS）验收手册

> 分支：`feat/rss-subscription`　｜　前置：[PROPOSAL-028](../proposals/PROPOSAL-028-rss-subscription.md)、[ADR-028](../decisions/ADR-028-rss-source-storage-and-evaluation.md)
> 本手册是**给用户实操用的**。请按 Step 1 → Step 5 走一遍；每一步都有「期望结果」，
> 不符合就是缺陷，请把现象回给我。

---

## 0. 先看这三条（决定你怎么判断"通过"）

1. **参照数据里 8 个源只有 1 个能抓文章**。其余 7 个（使用说明 / 源仓库 / 小说拾遗 / 导入 /
   Meow云 / 烏雲净化 / Yiove 书源仓库）在手机版里就是"打开网页"的链接收藏，**没有文章规则**。
   页面上它们会显示「仅打开网页」——这是**如实告知**，不是 bug。
2. **那唯一一个有规则的源（大灰狼书荒广场）上游已经挂了**：`api.langge.cf` 实测 DNS 指向
   `198.18.0.88`（保留网段，等于被黑洞），HTTP 502。所以**它抓不到文章是正常的**，
   关键看它有没有**如实报错**而不是给你一个空列表。
3. **判定回归看"失败集合"不看数量**：本机 `:server:test` 长期固定 57 个失败，
   全部是 Windows 上 SQLite 文件被占用的 `FileSystemException`（与本功能无关）。

---

## Step 1：启动服务

```powershell
E:\Desktop\legado\start-server.cmd
```

浏览器打开 <http://127.0.0.1:8080>，管理员密码 `w71251478`。

**期望结果**
- 能正常登录，顶栏出现 **「订阅源」** 入口（在「订阅」右边）。
- ⚠️ 顶栏现在有**两个**相关入口，别搞混：
  - **「订阅」** = 书源订阅（老功能，订阅一个书源 JSON 的 URL）；
  - **「订阅源」** = 本次新增的 RSS 订阅（#rss）。

---

## Step 2：从真实备份包导入（验证"像手机一样可以导入"）

### 方式 A：从 WebDAV 文件页导入整个备份包（推荐）

1. 顶栏 → **「文件」**（WebDAV 设置页）；
2. 找到/上传 `backup2026-09-30-PEPM00.zip`；
3. 执行**导入备份**。

### 方式 B：只导入订阅源 JSON（粘贴）

1. 顶栏 → **「订阅源」** → 右上角 **「导入」**；
2. 把 `rssSources.json` 的**整个数组**粘进文本框（脚本里已抽出该文件：
   `E:\Desktop\legado\.recon\rssSources.json`），或点 **「选择文件」** 选它；
3. 点 **「开始导入」**。

> 粘贴整个数组时前端只取**第一条**导入（导入器语义是"单条"）；要一次进 8 条请用方式 A。

**期望结果（方式 A）**
- 导入摘要里出现订阅源条数 **8**；
- 「订阅源」页左侧列表出现 **8 条**，其中：
  - `大灰狼书荒广场` 带 **「可抓取」** 标签；
  - 其余 **7 条**带 **「仅打开网页」** 标签；
  - `小说拾遗` 这类源即使 `sourceUrl` 是 `snssdk1128://…`（非 http）也能正常列出。

**期望结果（方式 B）**
- 左侧列表出现 **1 条** = `大灰狼书荒广场`，带「可抓取」标签；
- 切到它时右侧显示"文章列表"区域（而不是"仅打开网页"说明卡）。

---

## Step 3：刷新（验证核心抓取路径 + 失败如实回报）

### 3a. 抓不到时必须报错

1. 选中 `大灰狼书荒广场`；
2. 点右上角 **「立即刷新」**。

**期望结果**
- 弹出 **红色错误 toast**，文案形如 `刷新失败：<原因>`（原因通常是网络/上游相关，
  因为该源的上游已挂）；
- 左侧该源卡片下方常驻显示 **「最近失败：…」**；
- **反面判据**：如果它安静地显示"0 篇文章"而没有任何错误提示 → **这是缺陷，请报我**。

### 3b. 全部刷新语义

1. 点侧栏 **「全部刷新」**。

**期望结果**
- 出现"刷新完成：N 个成功，M 个失败（原因）"这类**如实**汇总；8 个源里失败数 ≥ 1
  （大灰狼上游挂了）是正常的；
- 无「仅打开网页」的源因为无规则而崩；它们会走默认 RSS 解析，抓不到就返回 0 条
  （这 7 个源的地址本来就不是 feed）。

---

## Step 4：用一个**可用的**真源验证完整阅读链路（关键步骤）

因为参照数据里唯一有规则的源上游已挂，这里用一个**公开可用的 feed** 验证
"默认解析 + 未读/已读 + 只看未读 + 刷新不重置已读"全链路
（这条路径对**任何**无 `ruleArticles` 的订阅源都生效 —— 服务端会尝试标准 RSS/Atom 解析）。

1. 「订阅源」→ **「导入」**，粘贴下面这条（无 `ruleArticles`，走默认 feed 解析）：

```json
{"sourceUrl":"https://www.ruanyifeng.com/blog/atom.xml","sourceName":"阮一峰的网络日志","enabled":true}
```

2. 点 **「开始导入」** → 左侧选中它（标签是「仅打开网页」）；
3. 点右上角 **「立即刷新」**。

**期望结果**
- 抓到若干篇文章（该地址是标准 Atom feed），右侧「文章列表」出现条目；
- 文章前有绿色 **未读圆点**；源卡片上有 **未读数字徽标**；
- 点标题 → **新标签打开原文**，且该条圆点消失、未读数 -1；
- 开 **「只看未读」** → 已读的条目立刻消失；
- 点 **「全部已读」** → 未读数归 0；
- **再次「立即刷新」→ 已读的条目必须仍是已读**（不会被重新标成未读）。

> 若该地址当时不可达，换任意一个公开 RSS/Atom 地址即可；语义断言不变。
> 这条"刷新不重置已读"是最重要的语义，已由 `RssRoutesTest > 重复刷新必须保留已读标记` 锁死。

> 也可以完全在命令行验证默认解析能力（无需网络）：
> ```powershell
> cd E:\Desktop\legado\legado-server
> $env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.11_10"
> .\gradlew.bat :server:test --tests "*RssRuleParserTest*" --console=plain
> ```
> 其中 `无规则源走默认 RSS 解析` / `无规则源也要认 Atom` 就是这条链路的断言。


---

## Step 5：导出回手机（验证双向兼容）

1. 顶栏 → **「文件」** → 执行**导出备份**；
2. 下载产出的 `backup<日期>-<设备名>.zip`，用解压工具打开。

**期望结果**
- 包内**有** `rssSources.json`，且导出条目数 = 服务端订阅源数（方式 A 走了就是 8）；
- 每个条目都含这些**必写字段**：
  `sourceUrl` `sourceName` `sourceIcon` `enabled` `customOrder` `type` `articleStyle`
  `lastUpdateTime` `singleUrl` `cacheFirst` `preload` `enableJs` `showWebLog`
  `enabledCookieJar` `loadWithBaseUrl` `redirectPolicy`；
- `redirectPolicy` 的值是**字符串** `"ASK_CROSS_ORIGIN"`（不是数字）；
- 有值的可选字段（`sourceGroup` / `ruleArticles` / `jsLib` / `sortUrl` …）原样写出，
  没值的**不出现**（不是空串）；
- 导出的包整体**能被手机端 Legado 正常导入**（若你手边有手机版，请实测一次）。

---

## 一键复制的验证命令（不需要浏览器）

```powershell
cd E:\Desktop\legado\legado-server
$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.11_10"

# 1) 前端类型检查 + 全量前端用例（含新增 rss.test.ts）
npm --prefix web run check
npx tsx web/test/run-all.ts                      # 期望：tests 201 / pass 201 / fail 0

# 2) RSS 相关的服务端用例（无需网络，用录制响应）
.\gradlew.bat :server:test --tests "*RssRuleParserTest*" --tests "*RssRoutesTest*" --tests "*BackupRssSourcesImportTest*" --tests "*BackupExporterTest*" --console=plain

# 3) 全量服务端用例（判定回归必须比「失败集合」）
.\gradlew.bat :server:test --console=plain --continue
```

零回归比对（我已跑过，结果如下，可复现）：

```powershell
cd E:\Desktop\legado
# 基线（RSS 改动之前，detached worktree @ 397a208）
node .recon\failures.cjs "<baseline>\server\build\test-results\test" ".recon\fail-baseline.txt"
# 本分支
node .recon\failures.cjs "E:\Desktop\legado\legado-server\server\build\test-results\test" ".recon\fail-rss.txt"
Compare-Object (Get-Content .recon\fail-baseline.txt) (Get-Content .recon\fail-rss.txt)
```

**实测结论（2026-10-03）**：

| | 基线 `397a208` | 本分支（rebase 到已合并的 `main` 之后） |
| :--- | ---: | ---: |
| 用例总数 | 451 | 458（+7 全部通过） |
| 失败数 | 57 | 57 |
| 失败**集合** | — | **逐条完全一致（Compare-Object 双向为空）** |

57 个失败全部是 `java.nio.file.FileSystemException: *.sqlite: 另一个程序正在使用此文件`
（Windows 上测试删不掉被占用的 SQLite，`SESSION-HIST-008` 已记录），与本功能无关。

---

## 核验清单（请逐条打勾）

- [ ] 顶栏出现「订阅源」入口，且与「订阅」（书源订阅）是两个独立页面
- [ ] 导入备份包后订阅源列表出现 8 条
- [ ] `大灰狼书荒广场` 标注「可抓取」，其余 7 条标注「仅打开网页」
- [ ] 非 http 的 `sourceUrl`（`snssdk1128://…`、`http@js:…`）也能正常列出、不被拒
- [ ] 刷新失败时**有明确错误提示**（不是静默 0 条）
- [ ] 源卡片上能看到「最近失败：…」
- [ ] 「全部刷新」给出「成功 N / 失败 M」的如实汇总
- [ ] 无规则的源**也能点「立即刷新」**（走标准 RSS/Atom 解析），且有「打开网页」按钮
- [ ] 抓到文章时有未读圆点 + 未读徽标；点开原文后自动标已读
- [ ] 「只看未读」过滤生效
- [ ] **重复刷新后已读仍是已读**
- [ ] 「仅打开网页」的源有说明卡 + 「打开网页」按钮，不是空白列表
- [ ] 导出的 zip 含 `rssSources.json`，必写字段齐全，`redirectPolicy` 是字符串
- [ ] 导出的包能被手机端导入
- [ ] `npm --prefix web run check` 与 `npx tsx web/test/run-all.ts` 全绿
- [ ] `:server:test` 失败集合与基线一致（零回归）

---

## 已知限制（如实告知，不是待办）

1. **无法用活源做端到端抓取验收**：参照数据里唯一有规则的源
   `大灰狼书荒广场` 的上游 `api.langge.cf` 已不可达（DNS 命中 `198.18.0.88` 保留网段）。
   规则求值改由 `RssRuleParserTest`（13 用例，录制响应驱动真实规则串）覆盖。
2. **未做**：`loginUrl`/`loginUi` 网页登录、`injectJs`/`shouldOverrideUrlLoading` WebView 注入、
   文章正文抓取（`ruleContent`）、`ruleNextPage` 翻页。
   参照数据里这些要么依赖 Android WebView（无头环境无能力），要么字段本身不存在。
3. **无规则源在 UI 上**：保留「仅打开网页」标签与说明卡，但**「立即刷新」对它是可用的**
   —— 若该地址其实是个标准 feed，默认 RSS/Atom 解析就能抓到文章。
4. Kindle 极简版 `simple/rss.html` 的三个老接口（`/getRssSources` 等）**没有实现**，
   该页面保持原状（此前也一直是 404）。

---

## 风险与未验证项

| 风险 | 状态 |
| :--- | :--- |
| 真实源规则在**活的上游**上是否端到端可用 | **未验证**（上游已挂）；已用录制响应覆盖求值逻辑 |
| 手机端能否读回我们导出的 `rssSources.json` | **未验证**（需真机） |
| 上游返回 GBK 且未在 URL 里声明 charset 时是否乱码 | **未验证**；`RuleRunner.fetchUrl` 只认 URL 尾部的 `,{"charset":…}`，不解析 `Content-Type` 的 charset（既有行为，非本次引入） |
| 超过 2MiB 的 feed 响应 | **未验证**；`RuleRunner` 有 `MAX_BODY_BYTES = 2MiB` 上限（既有行为） |
