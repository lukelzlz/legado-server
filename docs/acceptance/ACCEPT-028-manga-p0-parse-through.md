# 验收手册 ACCEPT-028: 漫画链路 P0（解析打通 · 先能看）

> 对应设计稿：本仓库的 [PROPOSAL-028: 漫画（图片书源）端到端支持与内容寻址缓存体系](../proposals/PROPOSAL-028-manga-support-and-content-addressed-cache.md)
> （另有更细的施工图《方案E-详细设计-全链路与流程图》，为本机工作区文档、**不在仓库内**，故不做链接）
> 阶段：**P0 —— 解析打通**（P1 服务端字节缓存的前置）
> 状态：`[Tested 单测/检查通过]` —— **P0 主干**；PROPOSAL-028 §4.2 尚有 5 项待补（见 §1.1），
> 请勿据此认为提案里的 P0 已全量完成。等待用户按本手册实操后签字

## 1. 目标与验收范围

本阶段只解决一件事：**把漫画书源的"一章图片"解析成有序清单并显示出来**。

| 交付 | 落点 |
| :--- | :--- |
| 图片清单解析 | `RuleRunner.mangaImages()` + `splitImageUrls()`（不洗 HTML、保序、URL 补全、懒加载 `data-src` 兼容） |
| 书籍类型判定 | `BookKind` + `book_shelf.book_kind` 列 + 启动期幂等回填 |
| 清单接口 | `POST /api/books/images/manifest`（**P0 暂回上游 URL**，供前端直连） |
| 漫画阅读器 | `web/src/MangaReaderScreen.tsx`（纵向滚动 + 目录 + 章节切换 + 按图片序号的进度） |
| 书架标识 | 书架卡片右上角「漫画」徽标 |

**本阶段明确不做**（P1/P2/P3 或 Non-Goals，见设计稿 §16）：
服务端图片字节缓存、预取、GC 配额、PWA 整章离线、分页/双页/右到左、TTS 朗读、替换净化、换源。

### 1.1 与 [PROPOSAL-028 §4.2/§5](../proposals/PROPOSAL-028-manga-support-and-content-addressed-cache.md) 的对照 ⚠️

**提案里的 P0 比本次实现更宽**。下表逐条对齐，避免把「主干」误读成「全量」：

| PROPOSAL-028 §4.2 特性 | 本次 | 说明 |
| :--- | :--- | :--- |
| `bookSourceType == 2` 判定 | ✅ | 用独立 `BookKind` 枚举，**未**加 `ShelfKind.COMIC`（理由：`ShelfKind` 是「导入时要不要」的过滤枚举，漫画是要导入的；见设计稿修订 R4） |
| 懒加载属性提取 | ⚠️ 部分 | 已支持 `data-src` → `data-original` → `src`；**未**接入 `data-lazy-src` / `data-actualsrc`，优先级顺序也与提案不同 |
| `srcset` 响应式切分 | ❌ | 未实现 |
| JS 规则数组输出 | ⚠️ 部分 | `<js>` 返回数组由既有 `value()` 以 `\n` 连接后切分；**未**显式处理 NativeArray 逐项转换 |
| URL 专属 Options `url,{"headers":{…}}` | ❌ | 未提取（P1 的 Fetcher 需要它作为输入） |
| **`nextContentUrl` 链式翻页** | ❌ | **P0 关键缺口**：翻页型漫画源目前只能拿到第一页 |
| `replaceRegex` 作用于图片清单 | ❌ | 当前刻意不接替换净化 |
| 占位图 / 广告过滤（1×1 gif、<10px） | ❌ | 未实现 |
| 清单接口路径 | ⚠️ 不一致 | 本次为 `POST /api/books/images/manifest`；提案写的是 `POST /api/books/images` —— **需二选一后统一**（会影响 P1/P2 的前端与 SW 契约） |

**能力边界一句话**：**HTML 型漫画源现在可用；`nextContentUrl` 翻页型漫画源在补齐前只能看第一页。**

---

## 2. 自动化验证清单

```powershell
# Windows 本机必须先固定 JDK（AGENTS.md §3：JAVA_HOME 可能指向失效目录）
$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.12_9"
cd C:\Users\w1593\Desktop\legado\legado-server

# 1. 服务端编译
.\gradlew.bat :server:compileKotlin --console=plain

# 2. 本阶段新增的服务端用例（13 个，全绿才算过）
.\gradlew.bat :server:test --tests "io.legado.server.MangaImagesTest" --tests "io.legado.server.MangaManifestRouteTest" --console=plain

# 3. 前端类型检查 + 全量前端测试（含 manga-reader.test.ts）
npm.cmd --prefix web run check
npx.cmd tsx web/test/run-all.ts
```

**已实测结果（本机，2026-10-13）**：

| 项 | 结果 |
| :--- | :--- |
| `:server:compileKotlin` | BUILD SUCCESSFUL |
| `MangaImagesTest` | 12 用例 / 0 失败 |
| `MangaManifestRouteTest` | 1 用例 / 0 失败 |
| `npm run check` | exit 0 |
| `tsx web/test/run-all.ts` | 216 用例 / 0 失败 |

> ⚠️ **`./gradlew :server:test` 全量跑的判定口径**：本机长期存在一批
> `FileSystemException: *.sqlite: 另一个程序正在使用此文件` 的失败（测试删不掉被占用的
> SQLite，macOS 不暴露）。**禁止只看失败数量**，必须与干净基线 worktree 的**失败集合**逐条比对：
> ```powershell
> git worktree add ..\legado-baseline HEAD      # 干净基线（HEAD = 未含本阶段改动）
> # 两侧各跑一次全量，导出失败集合后 diff
> ```
> 本机实测：改动后 57 失败 vs 基线 57 失败，**集合逐条 diff = 0 差异**。

### 2.1 真实网络端到端冒烟（已在**运行中的服务**上实跑，不是注入响应）

用真实 HTTP 页面替代漫画源，验证「取页面 → 跑规则 → 补全地址 → 返回清单」整条链：

| 检查 | 结果 |
| :--- | :--- |
| 真实抓取 + 规则求值（`@css:img@src`） | `https://www.python.org/` → `total=1`，URL = `https://www.python.org/static/img/python-logo.png` |
| **图片真的能看**（等价于浏览器断言 `naturalWidth > 0`） | 该 URL 实测 `200 / image/png / 15770 bytes` |
| 多图保序（真实图片密集站） | `https://www.163.com/` → **28 张**，index 0..27 顺序正确，**非绝对地址残留 = 0** |
| 带 query 的图床地址没被拆坏 | `nimg.ws.126.net/?url=…%2F…&thumbnail=280y140&quality=100&type=jpg` 实测 `200 / image/jpeg / 45142 bytes` |
| 加书架写入类型并透出 | `POST /api/bookshelf` → `bookKind=comic`；`GET /api/bookshelf` → `bookKind=comic` |
| 未登录 / 未知书源 / 缺章节地址 | `401` / `404` / `400` |
| 上游连不上时的错误形态 | `502 source_execution_failed` + 真实原因（**不是** 500「服务器内部错误」） |

> 本机到 `wikipedia.org` / `example.com` 不通，故用可达的 `python.org` / `163.com` 替代。
> PowerShell 侧两个既有编码坑已按 AGENTS.md 核对：请求体中文要
> `-ContentType "application/json; charset=utf-8"` + `-InFile` 传 UTF-8 字节；
> 响应会被 PS 按 Latin-1 解码（`[Text.Encoding]::GetEncoding(28591).GetBytes(...)` 再按 UTF-8 还原即可）。
> 服务端存的字节是正确的（还原后为 `P0 漫画冒烟二`）——**验收时建议直接用浏览器**，绕开这两个工具坑。

**结论**：服务端整链已证明可用。你手工验收时只需聚焦两件机器无法替代的事——
**真实漫画源**的兼容性，以及**浏览器里的 UI 呈现**。

---

## 3. 手工与 UI 验收步骤（本阶段的核心，必须用**真实漫画书源**）

### 3.1 准备：导入一个漫画书源

1. 顶栏 →「书源」→ 导入，粘贴一个 `bookSourceType: 2` 的漫画书源 JSON。
2. 导入成功后，该书源出现在书源列表中（与小说书源同一套管理界面，无需额外操作）。

### 3.2 加书架并确认「漫画」徽标

3. 用该书源搜索一部漫画 → 点「加入书架」。
4. 回到「书架」：
   - ✅ 该卡片右上角出现 **「漫画」** 徽标（说明 `book_kind=comic` 已落库）。
   - ❌ 若没有徽标 → 该书源**没有声明 `bookSourceType: 2`**，见 §5 已知局限。

### 3.3 打开漫画并确认「先能看」

5. 点击该书的封面进入阅读器：
   - ✅ 进入的是**纵向滚动的漫画阅读器**（不是文字阅读器、不是一堆 URL 文本）。
   - ✅ 图片按顺序自上而下渲染。
   - ✅ 顶栏显示「书名 + 当前话标题」，右上角显示 `当前张 / 总张数`。
6. 点击顶栏「目录」图标：
   - ✅ 左侧抽屉打开，列出全部章节，支持关键词过滤，点击可跳章。
7. 滚动到底部 / 点底栏「下一章」：
   - ✅ 章节切换正常，滚动位置回到本章顶部。
8. 退出阅读器再重新进入：
   - ✅ 进度回到上次看到的那一张附近（差 1 张以内）。

### 3.4 关键记录项（**直接决定 P1 的请求头策略**）

9. 观察是否有**裂图/加载失败**。若有，逐项记录到下面这张表（P0 的目的之一就是采集它）：

| 书源 | 是否需要 Referer | 是否需要 Cookie | 备注（图床域名 / data-src 懒加载 / 相对路径等） |
| :--- | :--- | :--- | :--- |
| | | | |

- 直连上游时浏览器带的是**本服务自己的来源**，且当前实现显式使用了
  `referrerPolicy="no-referrer"`——**防盗链严格的站点在 P0 必然裂图，这是预期行为**。
- P1 的服务端抓取会补上 `Referer = 章节页地址` + 书源 `header` + `Cookie`，届时这些问题应消失；
  所以**这些记录本身就是 P1 的验收输入**，务必填写。

---

## 4. 数据自证（排障第一步永远是查数据，不是读代码）

```powershell
# 默认数据目录 ./data（Docker 挂载时为 /data）
sqlite3 .\data\legado.sqlite "select book_kind, count(*) from book_shelf group by book_kind;"
sqlite3 .\data\legado.sqlite "select name, book_kind from book_shelf where book_kind='comic';"
```

预期：漫画书的 `book_kind` 为 `comic`；其余为 `novel` 或空（空值在读接口里按 `novel` 返回）。

---

## 5. 已知局限（P0 范畴内，不是缺陷）

| 现象 | 原因 | 何时解决 |
| :--- | :--- | :--- |
| 部分图裂 | 直连上游 + 防盗链（需 Referer/Cookie） | **P1**（服务端抓取统一补头） |
| 首屏轻微跳动 | 无宽高占位 | **P1**（manifest 带真实宽高） |
| 断网看不了 | 无字节缓存 | **P1**（服务端 L1 缓存）/ **P3**（SW 整章离线） |
| 每次打开都回源 | 无缓存 | P1 |
| **不声明 `bookSourceType` 的漫画源会走文字阅读器** | 判定依赖该字段（Legado 生态里经常缺失） | **P1** 增加「手动切换书籍类型」入口（设计稿决策 D5）；临时可用下方 SQL 兜底 |

临时兜底（仅限自用，改完刷新页面）：

```powershell
sqlite3 .\data\legado.sqlite "update book_shelf set book_kind='comic' where book_url='<该书的 bookUrl>';"
```

---

## 6. 回滚方式

本阶段是**纯增量**：新增 `book_kind` 可空列、新增一条只读路由、新增前端组件与分派分支。
回滚只需恢复到上一提交；`book_kind` 列残留不会影响既有小说链路（缺省按 `novel` 处理）。

---

## 7. 验收门禁

**在用户按 §3 实操并明确回复「验收通过 / LGTM」之前，本阶段一律不得标记为
`Accepted`，也不得对外宣称已完成。** 未通过时的反馈请附 §3.4 的记录表。
