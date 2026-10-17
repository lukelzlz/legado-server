# SESSION-050 · 缓存布局 v2/v3、清除缓存三选、阅读器 UI 与 i18n 缺 key 的一批（含合并冲突消解套路）

- **日期**：2026-10-17
- **类型**：Feat / UI / Fix 批次（8 个功能提交 + 6 个合并）
- **关联**：[PROPOSAL-031](../proposals/PROPOSAL-031-browsable-cache-layout-and-readable-cover-names.md) · [ADR-031](../decisions/ADR-031-cache-namespace-comic-images-covers-and-move-only-migration.md) · [ACCEPT-031](../acceptance/ACCEPT-031-cache-layout-and-cover-names.md) · [PROPOSAL-032](../proposals/PROPOSAL-032-clear-cache-scope-dialog.md) · [PROPOSAL-033](../proposals/PROPOSAL-033-unified-reader-toc-and-shelf-kind-badges.md) · [ADR-032](../decisions/ADR-032-i18n-literal-keys-must-exist-in-all-locales.md) · [ACCEPT-032](../acceptance/ACCEPT-032-clear-cache-and-reader-ui-batch.md)

---

## 1. 本批是什么

一次把"用户随手提的六个小诉求"做进一个批次。它们的共同点是**都不改协议、只改落盘形态与 UI 入口**，
但每一件都各埋了一个"看起来对、边界会静默失效"的坑。

| 提交 | 分支 | 一句话 |
| :--- | :--- | :--- |
| `0fb31ec` | `feat/cache-browsable-layout` | 缓存落盘改为可浏览结构（书/章可读目录 + 8 位分段号），读取只认哈希与序号 |
| `eeef50a` | `feat/cache-browsable-layout` | 漫画根 `images` → `comic`；封面改为 `书名 (20位哈希).扩展名`；`images` 变小说插图预留根 |
| `a7a6730` | `feat/shelf-clear-cache` | 书籍管理弹窗新增「清除缓存」（只清这一本），红色与移出书架同源 |
| `14266b7` | `feat/shelf-clear-cache` | 「清除缓存」升级为范围三选弹窗（服务器/浏览器/两者，三个红色 + 中性取消） |
| `95895ab` | `feat/manga-toc-footer` | 漫画目录抽屉新增缓存栏（本章/后 50/后 100/全本），从设置抽屉**搬出** |
| `cf80389` | `feat/audiobook-bottom-equal-width` | 有声书底部功能条改为"有几个按钮就均分几份"（原先写死 5 等分） |
| `7029293` | `feat/shelf-novel-tag` | 书架补「小说」角标 + 修掉漫画/听书徽标在英日文下显示中文 |
| `55a4008` | `feat/i18n-missing-keys` | 补齐 65 个"源码在用但四语言都缺失"的 key + 加 parity 防复发断言 |

---

## 2. 缓存落盘：从"哈希分片"到"人能读懂"

用户原话（PROPOSAL-031 §1）已经写清了形态要求，实现上真正需要想明白的是三件事：

### 2.1 路径由**用户数据**拼出来 ⇒ 必须过 `cacheSafeName` 与 `resolve` 两道关

AGENTS.md 有一条铁律：`Path.resolve()` 对非法字符（书名里的换行、`/`、`:`）**会抛异常**，
不是返回一个不存在的路径。而本批的路径**第一次**由用户数据拼出来，所以：

- 拼装侧：`cacheSafeName()`（控制字符与 `\ / : * ? " < > |` → `_`、空白折叠、去尾点与空格、
  截断、空则 `未命名`、Windows 保留设备名追加 `_`）；
- 存储侧：`AtomicFileStore.resolve()` 再校验一次（拒 `..`/绝对路径/盘符/空字节/反斜杠，
  并断言 `normalize()` 后仍在根内）。

### 2.2 读取**只认哈希与序号** ⇒ 中文与扩展名都不参与

`findByNameFree(bookHash, chapterHash, segmentIndex)` 扫的是
「目录名以 `(hash)` 结尾」+「文件名以 `00000001.` 开头」。三级顺序：
`file_path`（快路径）→ 无中文实扫（命中**回填** `file_path`）→ 都 miss ⇒ 判缺失重下。

**这是"防止中文干扰"的完整落地**：用户手工改名、书改名、上游改标扩展名，都不会让缓存失联；
反过来**哈希标记没了就判不存在**（宁重下，绝不瞎猜一个文件塞给用户）。

### 2.3 章节目录因哈希而**天生单射** ⇒ 删掉了冲突兜底

章节名是用户数据（可能重复、可能缺失），拿它当目录唯一标识就会两章互相覆盖，
而症状是「某些章节的图/音频变成别人的」这种极难归因的故障。加哈希后单射，
于是**刻意删掉**"同名冲突怎么办"的兜底代码。同理**不做**碰撞后缀 `(hash-2)`：
读取只认「哈希 + 章节序号 + 分段序号」，后缀不参与定位，加了也救不了 ⇒
**正解只有一个：让哈希足够长（20 位 = 80 bit）**。

### 2.4 v2 清掉重下、v3 **只移动**

`app_setting.cache_layout` 是判据。**最容易搞错的一条**：
"清掉重下"是针对 **v1 散列分片**的裁决（老文件读不到，留着是垃圾）；
**v2 → v3 只是根目录改名**（`images` → `comic`），布局本身没变 ⇒ **必须移动，用户零数据损失**。
移动的三条铁律：幂等 / 目标同名跳过（`Files.move` 刻意**不给** `REPLACE_EXISTING`）/ 单文件失败不阻断启动。

### 2.5 真实磁盘证据（本机 `_run-data`，实测）

```
comic\让词条飞一会 (445cd76eb2b4bd301527)\第4话 劫男色,是认真的_ (66872a658b4853c41e2b)\00000000.jpg
audio\AB验证书 (81ed195424d1346c0dbd)\001 空屋 (fc29c2ddbf3290e3b06a)\00000000.mp3
covers\让词条飞一会 (445cd76eb2b4bd301527).jpg
images\   ← 小说插图预留根，空
```

| 指标 | 实测值 |
| :--- | :--- |
| `covers/` 文件数 | **197**（55 `.jpg` + 142 `.png`） |
| `covers/` 无扩展名文件 | **0** |
| `covers/` 符合 `书名 (20位哈希).ext` | **197 / 197** |
| `app_setting.cache_layout` | **`v3`** |
| `cover_cache` 行数 / `file_path` 为空 | **197 / 0** |
| `cover_cache` 无书架引用的孤儿 | **0** |
| `book_shelf` 行数 | **394** |
| 哈希口径独立复算（前 5 本封面） | **5/5 与 `sha256(sourceId+"\n"+bookUrl)` 前 20 位一致** |

迁移当次日志（实测口径）：`移动文件=49, 字节=12777092, 跳过=0, 失败=0`、
`封面改名=196, 跳过=0, 失败=0`。**196 + 1 = 197** 正好等于 `cover_cache` 行数 ——
那个"1"是**孤儿封面留在原处**（`covers/<key>` 旧形态，读取侧最后一档兜底正需要它）。

### 2.6 封面命名容不下"两版" —— 这是**已知边界**，不是 bug

`书名 (书哈希).扩展名` 天生容不下同一本书的两版封面（换图 / 深度回源补抓）。
处理方式是「最后写入者拥有这个名字」：覆盖前把**别的 key** 记的 `file_path` 清掉
（`coverFilePathHolders` → `clearCoverFilePath`）⇒ 旧 key 只会"没有缓存"（回源重下），
**绝不直出另一版的字节**（有专门用例锁死）。

另外两个小坑：

- **扩展名映射当初漏了 `image/svg+xml`** ⇒ 本地书导入的 SVG 占位封面会说谎成 `.bin`。
  这条必须补（文件名说谎正是这套命名要消灭的东西）；
- **`adopt` 归位时不能走「内容类型 → 扩展名」映射** —— 手上只有现有文件名里的扩展名，
  再映射一次会把 `png` 当成未知类型变成 `.bin`（**实测踩过**）⇒ 于是拆出
  `coverCacheFileNameByExtension(name, hash, extension)` 这一支。

---

## 3. 清除缓存：把"范围正确性"做成**能被单测执行的表**

### 3.1 为什么必须抽成独立纯模块

`ShelfPage` / `BookManageModal` / 范围弹窗都住在 `main.tsx` 里，而该模块
① 顶部 `import './styles.css'`、② 模块顶层直接 `createRoot(...)` ⇒ **Node 侧无法 import**
（实测报 `Unknown file extension`）。于是把「作用域 → 精确要执行的清理步骤」抽成
`web/src/shelfClearCache.ts`（50 行纯表），**范围正确性才能被单测真正执行到**，
而不是只能对着 `main.tsx` grep 字符串。

### 3.2 表驱动 > 三个 `if`

四个清理动作三选一，最容易出的错是"改了一条分支忘了另一条" ⇒ **串范围**
（点"只清浏览器"却把服务端缓存也删了）。所有调用点都从 `SCOPE_STEPS` 派生 ⇒ 范围只在一处定义。
`stepsForScope` 对**未知作用域返回空数组**：它是唯一决定"删什么"的地方，
**宁可不删、绝不越界删**。

### 3.3 记账必须是"循环内唯一 catch 里的唯一 push"

三步/四步串行、各自 `try/catch` 记账，最后只报「全成功」或「点名失败的那几段」。
因为标签是**由 step 派生的三元**、push 只有一处 ⇒ 选「服务器」时
**结构上不可能**出现"本机离线正文"字样（不是靠文案纪律，是靠代码结构）。

### 3.4 刻意**不接**整机全清

弹窗里禁止 `clearAllOffline` / `evictLruOfflineBooks` / `getOfflineStorageStats`
（"没有全清分支"守卫保留）—— 整机清理归「离线缓存管理」页，两个入口语义不许混。

---

## 4. UI 批次：三个"很小但会静默失效"的改动

### 4.1 漫画缓存栏：**搬**，不是复制

`reader-drawer-left` 内、章节列表**之后**加 `<footer className="drawer-footer">` +
`cache-range-btn-grid` 四个按钮；同时把「离线缓存」整节从**设置抽屉搬走**。
测试用「**全页 `.cache-range-btn` 恰好 4 个**」来证明"是搬不是复制" ——
这个断言比"设置抽屉里没有"更强，因为它同时挡住了"复制一份"和"漏搬"。

点击**不关抽屉**（footer 内不出现 `setActiveDrawer`）：这样后台缓存跑起来时列表里的绿点当场可见，
正是用户要的"像小说的那个一样"。绿点图例**必须留着** —— 它是"哪些章已可离线读"的唯一解释。

### 4.2 底部均分：**不要**用 `grid-auto-columns: 1fr`

原实现 `grid-template-columns: repeat(5, 1fr)` 写死 5 列，而"缓存"按钮已被迁进目录抽屉
⇒ 只剩 4 个按钮、第 5 列空着、右侧一条空档。

改法是**动态均分**而不是"把 5 改成 4"：`display: flex` + `.audiobook-feature { flex: 1 1 0; min-width: 0 }`。

**为什么不用 `grid-auto-columns: 1fr`**：它的实际语义是 `minmax(auto, 1fr)`，
那个 `auto` 最小值**与内容相关**，长文案仍可能把某一份撑宽（**隐性失效**）；
`flex-basis: 0` + `min-width: 0` 让每份的基准宽度与内容无关 ⇒ 均分是硬保证。

溢出保护（`max-width:100%` + `text-overflow: ellipsis`）要加在**子 `span`** 上：
flex 容器自身的 `text-overflow` 不生效，且图标/数值与文案**两个** span 都要被锁住。

### 4.3 角标：抽成**唯一产出点**，顺带修一个用户可见 bug

`ShelfKindTag.tsx`（20 行）是漫画/听书/小说三种角标的**唯一产出点**，三者**互斥**
⇒ 任一卡片右上角最多一个角标（避免两个条件同时命中而叠标签）。
抽出来的理由是可测性：`main.tsx` 在 Node 里 import 会直接崩（同 §3.1）。

**顺带修掉**：`shelf.comicTag` / `shelf.audioTag` **不在任何 locale 文件里**，
只靠 `t(key,'漫画')` 的中文默认值兜底 ⇒ **en-US / ja-JP 用户看到的徽标一直是中文**。
补齐四语（`Comic`/`Audio`/`Novel`、`漫画`/`朗読`/`小説`）。

---

## 5. i18n 缺 key：一个"永远不报错"的缺陷类别

### 5.1 缺陷形态

`t('shelf.novelTag', '小说')` 在 key 缺失时 **i18next 静默回退到中文默认值** ⇒
英文/日文用户看到中文界面，**没有任何日志、没有任何异常**。

独立重扫（提交 `55a4008` 当时）：44 个源码文件、1540 个 `t()` 调用点、
**1173** 个去重字面量 key ⇒ **四语言全缺失 65 个**（仅部分语言缺失 0，动态拼接 key 2 处）。

> 65 而不是"上一轮报的 63"：独立重扫后多出的 2 个正是
> `shelf.comicTag` / `shelf.audioTag`（被书架角标改动引用、仍四语言缺失）——
> **照抄上一轮的数字会漏掉最新的引用**，必须重扫。

### 5.2 防线：`web/test/i18n-parity.test.ts`（4 用例）

① 每个字面量 key 在四语言都存在（失败信息逐条给 `语言 key (used at 文件:行)`）；
② 每个 key 四语言都是**非空字符串**；③ **扫描器自检**（`USED_KEYS.size > 1000`，防正则坏掉整组空过）；
④ **反向 parity**（不允许某 key 只在部分语言存在 —— 否则会回退回 zh-CN 再出中文）。

locale 文件是 **import** 进来的，校验的正是应用实际打包的那份资源。
动态拼接 key（当前 2 处，都在 `TtsSettingsModal.tsx:244`）**刻意排除**并写进注释：
静态不可验证，算进来只会产生假失败；将来新增动态 key 必须自带 inline fallback。

**负向验证已实测**：临时删掉 `ja-JP` 的 `manga.retry` / `manga.noChapters` ⇒
测试 exit 1、**3 个用例失败**，输出精确指出 `ja-JP manga.noChapters (used at MangaReaderScreen.tsx:192)`
⇒ 断言确实非空洞。

### 5.3 ⚠️ 集成状态（**本批最需要记住的一条**）

`feat/i18n-missing-keys` 被"合并"进集成分支时，**合并提交 `20f2f02` 实际上只有一个父提交**
（`parents=ab1c9d7`，`git log -1 --format=%p` 可验），而它的 message 写着
"Merge branch 'feat/i18n-missing-keys' into all-prs"。
后果：**四语 locale 的 65 个 key 回填进去了，但 `web/test/i18n-parity.test.ts`（233 行）
与 `run-all.ts` 的登记没有进**（`git ls-tree all-prs web/test/` 里查不到该文件）。

⇒ **教训：合并提交的 message 不能当证据，必须验 `%p` 与 `git ls-tree <branch> <path>`。**
"我合过了"和"那份改动真的在目标分支上"是两件事。

---

## 6. 合并冲突消解套路（本批真实踩到）

一次把 8 个功能提交合进 `all-prs`，冲突集中在四类文件。**套路**：

| 冲突面 | 处理方式 | 为什么 |
| :--- | :--- | :--- |
| `web/dist/*`（构建产物） | **手改无用** ⇒ 重新 `npm run build` 覆盖 | dist 是编译产物，手工解冲突只会得到一个"谁也没编译过"的中间态 |
| `AGENTS.md`（索引与部落知识） | **取并集** | 两侧各写了不同的行/条目，取并集才是完整事实 |
| `locale JSON` | **取一侧整体 + 脚本补另一侧独有键** | 逐行 merge 会打乱键顺序与 CRLF；脚本补键要**断言锚点唯一** + `JSON.parse` 校验 |
| `run-all.ts`（手工维护的 import 列表） | **取并集** | 这是"手工维护的列表"，git 常常自动合并成功，但**必须核对两侧的每一行都在**（本批就漏了 `i18n-parity` 的登记，见 §5.3） |

当两侧各是一件事时**都保留**，并在合并后跑 `compileKotlin`（或 `npm run check`）验证。

本批真实执行的一例（`20f2f02` 的 message 原文）：

> 冲突解决：4 个 locale 文件整体取 i18n 分支那一侧（含全部 65 个新 key，且
> `shelf.comicTag` / `shelf.audioTag` 的取值已与已并入的 #52 对齐），再用脚本补上 #52 独有的
> `shelf.novelTag`（小说/小說/Novel/小説）——即两侧的并集。`web/test/run-all.ts` 由 git 自动合并
> （三处 import 都在）。

---

## 7. 验证与数字

### 7.1 前端

| 提交 | run-all | 说明 |
| :--- | :--- | :--- |
| 基线 `6602fc9` | **250** | 本 checkout 的干净基线（**不是** `_wt-all` 的数字：那边多注册了几个测试文件） |
| `cf80389`（底部均分） | 254 | +4 |
| `55a4008`（i18n parity） | 254 | +4（`250 → 254`） |
| `7029293`（小说角标） | 257 | 基线 250 → 257 |
| `a7a6730`（清缓存第一版） | 261 | +11 |
| `14266b7`（范围三选） | **267** | 本轮净增 6（`ShelfClearCache.test.ts` 10 → **16** 用例） |
| `95895ab`（漫画缓存栏） | 255 / 261 | 各自 base 上 +5 |

> **基线的口径要写清楚**：同一个提交在 `6602fc9` worktree 上是 250、
> 在 `_wt-all` 上是 257 —— **"257 来自 _wt-all"这种话必须自己先核实**，
> 否则会把别人的测试文件算进自己的增量。

### 7.2 服务端

| 项 | 数字 |
| :--- | :--- |
| `0fb31ec`（v2 布局）全量 `:server:test` | **593 用例 / 57 失败**，与基线 `510/57` 失败集合逐条一致 |
| `eeef50a`（v3 布局）全量 `:server:test` | **602 用例 / 57 失败 / 2 跳过**，与基线（`510/57`，`main@04724f2`）`Compare-Object` **双向差集为空** |
| 新增服务端用例 | `CachePathLayoutTest` **22** + `CacheLayoutV3Test` **9** |

> 总数 510 → 602 不是回归：基线跑在 `main` 上，而本 PR 的基准是 `integration/all-prs`
> （已含 #45/#46 等测试套件）。

### 7.3 新增前端用例

| 文件 | 用例数 |
| :--- | :--- |
| `web/test/ShelfClearCache.test.ts` | **16** |
| `web/test/shelf-kind-tag.test.ts` | **7** |
| `web/test/manga-toc-footer.test.ts` | **5** |
| `web/test/audiobook-bottom-equal-width.test.ts` | **4** |
| `web/test/i18n-parity.test.ts` | **4**（见 §5.3 的集成状态） |

---

## 8. 部落知识（本批新增，已同步进 AGENTS.md）

1. **缓存命名空间与迁移**：`audio|comic/<书名 (20位哈希)>/<章节名 (20位哈希)>/<8位分段号>.<真实扩展名>`；
   `images/` 只作小说插图预留；`covers/<书名 (20位哈希).ext>`；读取一律按哈希定位；
   `cache_layout` v2 = 清掉重下、**v3 = 移动**。
2. **身份 vs 展示属性**：缓存身份必须来自不可变唯一键；`chapter_index`/标题这类展示属性**不能当身份**
   —— 用元数据拼路径必须证明单射或给冲突兜底，否则是**静默覆盖**。
3. **i18n 默认值陷阱**：`t('key','中文默认值')` 在四语言缺 key 时**静默回退成中文**；
   靠 `i18n-parity` 断言守住（含**扫描器自检**防整组空过）。
4. **前端组件可测性**：`main.tsx` 无法在 Node 里 import ⇒ 想对某块 UI 做真实渲染断言，
   必须把它抽成独立模块（`ShelfKindTag.tsx` / `shelfClearCache.ts`）。
5. **断言必须非空洞**：新写的断言要对着"修复前"的代码跑一遍确认真的红（本批三次实测）。
6. **验证部署的判据要对**：只改 JS 别拿 CSS 哈希比对；产物里中文常被转义成 `\uXXXX`，grep 中文会假阴性。
7. **运维提醒**：本机服务日志是 GBK(936)；`:server:test` 回归基线取自 `%TEMP%\full-baseline.log`
   （`main@04724f2` = 510 用例/57 失败），判定永远比**失败集合**不比数量。
8. **合并多个 PR 的冲突消解套路**：见 §6。
9. **GitHub 推送**：git 传输是间歇性的；不可用时改用 REST Git Data API 重放提交
   （blobs → tree(base_tree) → commit → ref），且**所有 SHA 必须完整 40 位**。

---

## 9. 未做 / 待确认

- **插图写入链路**未实现（`images/` 恒为空，见 [ADR-031 §2.1](../decisions/ADR-031-cache-namespace-comic-images-covers-and-move-only-migration.md)）；
- **孤儿封面清理**未做（数学上改不了名，且读取侧正需要 `covers/<key>` 这一档兜底）；
- **漫画换源**未接线（本批只做缓存栏，刻意）；
- `docs/decisions/ADR-028` 里那句 `data/legado-server/images/<hash[0:2]>/<hash>` 是 **v1 的历史记录**，
  改动前就已过时（PR #50 也未动它）—— 本轮**按"只报告"执行，未改动该文件**；
- `i18n-parity.test.ts` 尚未并进 `all-prs`（§5.3），**合入前 ACCEPT-032 §5 的对应命令只能在
  `_wt-pr-i18n` worktree 上跑**。
