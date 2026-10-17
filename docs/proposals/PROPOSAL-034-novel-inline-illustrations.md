# PROPOSAL-034 · 书籍正文插图（服务端代理 + 落盘缓存 + 阅读器灯箱）

- **状态**：Implemented（本地已实现，单测 + 真实接口实测通过；待用户验收）
- **关联**：[ADR-033](../decisions/ADR-033-inline-illustration-proxy-and-cache-layout.md) · [ACCEPT-033](../acceptance/ACCEPT-033-inline-illustrations.md) · [PROPOSAL-028](PROPOSAL-028-manga-support-and-content-addressed-cache.md)（漫画图片链路：抓取收口与缓存思路与本案同源） · [PROPOSAL-031](PROPOSAL-031-browsable-cache-layout-and-readable-cover-names.md) / [ADR-031](../decisions/ADR-031-cache-namespace-comic-images-covers-and-move-only-migration.md)（落盘命名与根目录划分：`images/` 本是本项目留下的**插图预留根**）
- **实现分支**：`feat/novel-inline-illustrations`

---

## 1. 需求来源（用户原话）

> 「添加书籍正文插图功能」

一句话需求背后的真实痛点（读代码确认过）：**连载小说的正文里本来就有插图，服务端把它们全删了**。
`RuleRunner.cleanContent()` 曾经用 `Regex("<[^>]+>")` 把一切标签剥光，于是正文里的 `<img>` 与它的地址
一起消失 —— 阅读器里那一段就是"什么都没有"，用户既看不到图，也没有任何报错。

---

## 2. 现状与缺口（改动前，逐条读代码确认）

| 环节 | 改动前 | 后果 |
| :--- | :--- | :--- |
| `RuleRunner.cleanContent()` | 结构性/退化两条清洗路径都以 `Regex("<[^>]+>")` 收尾 | 正文插图 **100% 丢失**，且不可恢复（`raw_content` 也是清洗后的） |
| 服务端路由 | 只有漫画的 `GET /api/images/{key}`（key 是 URL 派生哈希，需先登记清单） | 小说插图**没有**任何取图入口 |
| `ServerConfig.illustrationCacheDirectory` | 已存在（`<data>/images`），启动时只保证目录存在，**不写入** | 目录常年空着，`images` 这个名字被 ADR-031 明确留给了"小说插图" |
| `BookCacheService` 离线缓存 | 只缓存正文文本 | 断网后即使正文在本地，图也必然没有 |
| 前端 | 正文按行渲染成 `<p>纯文本</p>` | 没有插图渲染、没有图片查看器（**当时仓库里没有任何灯箱组件**） |

三个**不能直接上**的朴素方案（都在实现前被否掉）：

1. **把上游地址原样留在正文里让浏览器直连** ⇒ 缺 `Referer`/Cookie 必裂图（图床防盗链），
   断网必瞎，还把用户 IP/UA 送给几十个图床（详见 ADR-033 §2.1）。
2. **清洗时把 `data:` 内联图也留下** ⇒ 服务端无法按魔数校验、无法限制体积，
   一张图能把正文缓存与 JSON 响应撑爆，且 `data:` 本身是 XSS 载体。
3. **复用漫画的 `manga_image_cache` 表** ⇒ 漫画的序号来自"整章图片清单"，
   插图的序号来自"正文里的出现顺序"，两套语义混表后任何一方的口径调整都会污染另一方。

---

## 3. 目标

1. **能显示**：正文里的 `<img>` 在阅读器里渲染成图片（受正文宽度约束、圆角、与排版一致）。
2. **能缓存**：首次显示由**服务端**带书源 `header` + 章节页 `Referer` + Cookie 抓取并落盘；
   之后同一张图**零上游请求**。离线缓存章节时顺手把该章插图也抓下来。
3. **能离线**：断网 + 清浏览器缓存后，只要服务端缓存还在，图片照样能显示（走服务端直出）。
4. **能放大**：点击图片打开灯箱（全屏黑底 + `object-fit: contain`），点空白/×/Esc 关闭。
5. **安全**：落到前端的 HTML 里只允许**安全的 `<img>`**（判据见 §5.1）；
   浏览器**永远不直连上游**。
6. **可运维**：落盘沿用 v3 可浏览命名 `images/<书名 (20位书哈希)>/<章节名 (20位章节哈希)>/<8位序号>.<真实扩展名>`，
   **读取只认两个短哈希 + 序号**。

## 4. Non-Goals

- **不做插图配额 / GC**：`images/` 目前只增不减；清理由「清除缓存（服务器）」按书触发。
  （配额与滑窗 GC 的既有实现是给漫画/音频用的，插图量级小得多，本轮不引入第二套 GC。）
- **不做"同一章图片的独立清单接口"**：不需要 —— 正文本体就是清单（顺序即序号）。
- **不把老缓存正文里的插图"找回来"**：改动前缓存的 `raw_content` 里插图**已经被删掉**，
  属于不可逆信息丢失。重洗（`/api/bookshelf/reclean`）只能重跑替换规则，不会重新联网取正文；
  想看图需要重新打开该章（在线）或重新缓存该章。
- **不处理 PDF/CBZ 之类"整页图片"的电子书**（那是漫画链路 / 本地书解析的职责）。
- **不改漫画与有声书的任何接口/语义**。
- **不做图片压缩/转码**：字节原样落盘（`Content-Type` 以真实魔数为准）。

---

## 5. 方案要点

### 5.1 清洗：先"摘走"再"放回"

`cleanContent()` 现在先把**安全的插图**替换成独占一行的占位标记（`@@LEGADO-ILLUSTRATION-n@@`），
文本清洗（结构性删 `div`/`script`/`style`、段落换行、实体反转义）照旧跑完，
最后按行把占位标记换回**我们自己重建**的 `<img src="…" alt="…">`。

**什么算安全（写入侧判据，`IllustrationMarkup.safeTag`）**：

| 判据 | 决定 | 理由 |
| :--- | :--- | :--- |
| 协议 | 只留 `http(s)`；相对地址按**章节页**补全，补不出来就丢 | 只有绝对 http(s) 才能被服务端代理抓到字节 |
| `data:` | **丢弃** | 服务端无法核对魔数/体积；正文缓存与 JSON 响应会被撑爆；本身是 XSS 载体 |
| `javascript:` / `vbscript:` | **丢弃** | 直接是注入载荷 |
| `file:` / `blob:` / `about:` / `chrome:` | **丢弃** | 服务端取不到，浏览器侧又会绕过代理 |
| 属性 | **只重建 `src` / `alt`**（自转义），其余一律不保留 | 上游的 `onerror`/`onload`/`style`/`srcset` 一个都进不来 |
| 懒加载地址 | `data-src` > `data-original` > `src` | 与 `RuleRunner.splitImageUrls`（漫画）同一口径，兼容懒加载站 |

> 安全边界的本质：**不要"过滤上游标签"，而是"用解析结果重新生成标签"**。
> 前者永远漏一个属性就是一个 XSS；后者上游属性根本没有机会出现。

### 5.2 代理路由（唯一取图入口）

```
GET /api/books/images/illustration?sourceId=&bookUrl=&chapterUrl=&url=&chapterIndex=
```

- 会话鉴权（401 未登录）；参数缺失/非法 URL ⇒ **400** 明确报错；
- 命中：`url_hash = sha256(sourceId + "\n" + url)` 行 `ready` **且文件确实在** ⇒ 直出
  （`ETag = sha256(字节)`、`Cache-Control: private, max-age=31536000, immutable`、支持 `If-None-Match` ⇒ 304）；
- 未命中：走**既有抓取收口** `MangaImageFetcher`（书源 `header` + 登录 Cookie + **章节页 Referer**、
  全局限流、gzip 魔数解压、**图片魔数校验**、单图 ≤ `ImageBytes.MAX_BYTES`(5 MiB)）→ 落盘 → 直出；
- 抓取失败/上游返回 HTML/超大 ⇒ **502**（带明确文案），**绝不返回空 200**；
- `data:`/`javascript:` 等非 http(s) 地址 ⇒ 400（服务端永不代理）。

### 5.3 序号：正文顺序定死，永不改写

一章内的序号 = 该图在**正文里的出现顺序**，在"正文取回时"登记一次
（`POST /api/books/content` 与 `BookCacheService` 都登记）。
已登记的序号 `on conflict do nothing` —— 源站改版导致顺序变化时只是新图追加到末尾，
**老图的「序号 ↔ 文件」映射保持不变**。这是"绝不返回错图"的前提：
两张图共用一个序号 ⇒ 后写的覆盖先写的 ⇒ 读者看到别人的图（比裂图难查得多）。

拿不到正文清单时（老缓存/首次直抓）在**进程内闸门**里取 `max(segment_index)+1` 追加，
并发进来也不会撞号。

### 5.4 离线：缓存章节时顺带抓图

`BookCacheService` 缓存每一章正文后，按登记的序号把该章插图抓下来落盘：
- **单张失败只计数 + 记首条原因**，绝不影响"这一章正文已缓存"的判定；
- 单章封顶 `MAX_ILLUSTRATIONS_PER_CHAPTER = 20` 张（超出部分在线读时按需落盘）；
- 日志口径：`book cache completed: … failures=N, 插图=X（失败 Y，首条原因：…）`。

### 5.5 前端

- 正文渲染时把 `<img src>` **重写**为 `illustrationProxyUrl(context, upstreamUrl)`
  （相对路径、同源，不依赖 `location.origin`），并带 `loading="lazy"`、`referrerPolicy="no-referrer"`；
- 一行里"文字 + 图 + 文字"都能渲染，**段落下标不变**（TTS 高亮靠它对齐）；
- 插图用 `<button>` 包住 ⇒ 键盘可达，且 `isInteractiveReaderTarget()` 本来就视 `button` 为交互元素
  ⇒ **点插图不会误翻页**（不需要给手势逻辑加特例）；
- 加载失败渲染**占位块**（虚线框 + 文案），不显示浏览器的裂图图标；
- 点击打开灯箱（`position: fixed`，不 Portal —— `.reader-workspace` 无 `transform`/`backdrop-filter`），
  点空白/×/Esc 关闭；Esc 用**捕获阶段**监听（点图后焦点在按钮上，通用键盘处理会提前 return）；
- 滚动模式下图片晚到会改变文档高度 ⇒ `onLoad` 触发一次 tick，让既有的"同帧几何补偿"重跑，
  避免读者眼前的文字跳动（`.reading-scroll-window` 关闭了 `overflow-anchor`）。
- TTS 输入用 `stripIllustrations(paragraphs)` 剔除插图标签（只删标签、不删段落，否则高亮会整体错位）。

---

## 6. 验收与回归

- 服务端新增 3 个测试类（共 13 例）：`NovelIllustrationContentTest`(6)、`IllustrationCacheTest`(5)、
  `IllustrationRouteTest`(2)；前端新增 `inline-illustrations.test.ts`(8，已登记进 `run-all.ts`)。
- **非空洞自证**（每条都对着"缺陷版"代码验证过会红）：
  1. 把清洗退回"不摘插图" ⇒ `NovelIllustrationContentTest` 2 条红；
  2. 代理路由去掉章节页 `Referer` ⇒ `IllustrationRouteTest` 红；
  3. 关掉 `BookCacheService` 的插图预热 ⇒ `IllustrationCacheTest` 的离线用例红；
  4. `loading="lazy"` 改成 `eager` ⇒ 前端渲染断言红；
  5. 去掉 `stripIllustrations(paragraphs)` ⇒ 前端"接线"断言红。
- 真实验收步骤见 [ACCEPT-033](../acceptance/ACCEPT-033-inline-illustrations.md)。
