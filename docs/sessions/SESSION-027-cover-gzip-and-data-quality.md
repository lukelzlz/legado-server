---
id: SESSION-027
title: 修复书架封面全部空白——gzip 未解压落盘，兼修 coverUrl 自引用与书名换行污染
date: 2026-09-26
author: Agent
tags: [cover, gzip, content-encoding, pwa, service-worker, data-quality, e2e-browser]
---

# SESSION-027: 书架封面「HTTP 200 却空白」

## 0. 起因

用户报「现在书架里的 5 本书也没封面」。

**关键矛盾**：此前我用接口实测过，5 本书的 `coverKey` 全部存在、
`/api/covers/<key>` 全部返回 **HTTP 200 且 9332~22168 字节**。
**"接口正常"和"浏览器能看"是两件事**——这个矛盾本身就是最强线索。

## 1. 定位过程（先实测，再怀疑）

依次排除了数据层、鉴权、CSP、前端逻辑、CSS、构建产物、Service Worker 预缓存，
全部正常。于是改用**真实浏览器**（`puppeteer-core` + Edge）复现，一次拿到决定性证据：

```
[resp] 200 image/jpeg bytes=0          ← <img> 加载：状态 200，但 0 字节
BROKEN ... complete=true natural=0x0   ← 5 张封面全部无法解码

同一 URL 用 fetch() 测：
{"status":200,"type":"image/jpeg","size":22168}   ← 22168 字节，看起来"正常"
```

**注意这个陷阱**：`fetch()` 只报告字节数，所以它"通过"了；
只有 `<img>` 的 `naturalWidth` 才反映**能否解码**。

### 真正的根因：gzip 未解压就落盘

用十六进制看真实字节，一眼看到问题：

```
Content-Type: image/jpeg
前 4 字节  : 1f 8b 08 00     ← 这是 GZIP 魔数，不是 JPEG！
解压后     : ff d8 ff e0     ← 才是真正的 JPEG
```

`HttpResponse.BodyHandlers.ofByteArray()`（`CoverCache.download`）**不会自动解压**，
而上游图床对 `.jpg` 也会返回 `Content-Encoding: gzip`：

| 上游 | Content-Encoding | 落盘魔数 | 结果 |
| :--- | :--- | :--- | :--- |
| `api.jmlldsc.com` | **gzip** | `1f 8b 08 00` | ❌ 坏 |
| `www.wensang.net` | (无) | `ff d8 ff e0` | ✅ **好** |
| `api.lfdapengu.com` | **gzip** | `1f 8b 08 00` | ❌ 坏 |

**「唯一那本封面正常的书，恰好是唯一不 gzip 的上游」**——这是一个天然对照组，
直接锁死了结论。

## 2. 修复

### 2.1 按魔数解压（而非信任 `Content-Encoding` 响应头）

在 `CoverCache` 落盘前的**唯一收口**处做兜底解压：

```kotlin
private fun decompressIfNeeded(raw: ByteArray): ByteArray {
    val gzipMagic = (raw[0].toInt() and 0xff) == 0x1f && (raw[1].toInt() and 0xff) == 0x8b
    if (!gzipMagic) return raw
    return runCatching { GZIPInputStream(raw.inputStream()).use { it.readAllBytes() } }.getOrDefault(raw)
}
```

**为什么按魔数而不是按响应头**：上游会谎报 `Content-Encoding`，
此时强行解压反而会把**好数据弄坏**。魔数判断对真实图片是无操作，天然安全。

### 2.2 修复已有的坏缓存（幂等就地修复）

历史坏文件的内层是**完好的 JPEG**，因此无需重新下载：用 `.repair.tmp` +
`ATOMIC_MOVE` 原子就地解压即可（避免运行中的服务读到半截文件）。

实测：`repaired=5  alreadyOk=0  failed=0`。

### 2.3 一个被我自己的测试抓到的过度设计

最初我把「魔数强校验」加在了**所有**写入路径上，结果 **6 个既有用例回归**
（`CoverCacheTest` / `BackupImportTest` / `LocalBookParserTest`），
因为它们通过 `fetcher` 接缝注入的是**假图片字节**。

**复杂度惩罚的教训**：这条校验是**网络信任边界**上的防御，
不该施加到进程内注入的接缝上。最终设计：

- **解压**：所有路径生效（对真实图片是无操作）
- **魔数强校验**：仅内置下载路径（`fetcher == null`）

## 3. 顺带修复的两个数据质量问题

### 3.1 `coverUrl` 自引用

5 本书的 `coverUrl` 全被写成了 `/api/covers/<它自己的 coverKey>`。
成因：编辑弹窗在无外部 URL 时回退 `api.cover(coverKey)`，把本服务地址当"外部封面地址"存了回来。

- **服务端**（权威）：`isSelfCoverReference()` 在 `updateBookshelfInfo` / `saveBookshelf` 两处剥离
- **前端**：`handleSave` 不再提交 `/api/covers/*`
- **存量数据**：清空 5 条自引用（`coverKey` 保留，封面不受影响）

### 3.2 书名混入章节名

某书源的书架名是 `"十日终焉我成魔\n第八十章 星尘归寂，余念长存"`
（目录页规则把「最新章节标题」一并取进了 `name`）。

新增 `sanitizeBookName()`：仅取第一行、折叠空白、剥离书名号/引号。
**刻意不做**「按`第X章`截断」——会误伤本身就叫《第X章》的书名（已用测试锁定该设计决定）。

### 3.3 Service Worker 死配置

`vite.config.ts` 的封面缓存规则写的是 `/^\/api\/book\/cover/`，
而真实路由是 `/api/covers/<sha256>`——**该路由根本不存在**，规则永不命中。
已改为 `/^\/api\/covers\//`。

## 4. 验证

| 层次 | 手段 | 结果 |
| :--- | :--- | :--- |
| 字节 | 魔数检查 | `ff d8 ff e0`（真 JPEG） |
| 浏览器 | `img.naturalWidth` | 5/5 全部 `> 0`（189x272 / 225x300 / …） |
| 回归 | 失败集合对照 | 239 → **259** 用例，失败 **54 → 54 集合一致** ⇒ 零回归 |
| 前端 | `npm run check` / `run-all.ts` | 0 错误 / **145/145** |

新增测试 20 个：`CoverCacheGzipTest`（10）+ `ShelfDataSanitizeTest`（12），
以及 `web/test/e2e-cover-browser.ts`（真实浏览器断言 `naturalWidth > 0`）。

## 5. 沉淀的教训与部落知识

1. **「接口 200 + 字节数正常」不能证明资源可用**。二进制资源（图片/字体/音视频）
   必须验证**能否解码**。发现"封面空白但接口 200"时，第一步就该看**前几个字节的魔数**。
2. **`HttpResponse.BodyHandlers.ofByteArray()` 不解压**，而图床对 `.jpg` 也可能返回
   `Content-Encoding: gzip`。凡是"下载二进制后落盘"的代码都要显式处理压缩。
3. **`fetch()` 的 `size` 会骗人**：它只数字节，不解码。判断图片是否可用必须用
   `<img>` 的 `naturalWidth`（或用真实浏览器跑 E2E）。
4. **上游行为不一致时，故障的"对照组"价值极高**：5 本里恰好 1 本正常，
   而它是唯一不 gzip 的上游 ⇒ 一步锁定根因。
5. **防御要加在正确的信任边界上**：把网络侧的强校验施加到进程内接缝，
   会拒掉合法用例（本次实测回归 6 例）。**解压可以全局做，强校验只能放在边界。**

## 6. 修改文件
| 文件 | 变更 |
| :--- | :--- |
| `server/.../CoverCache.kt` | `decompressIfNeeded`（魔数解压）+ 下载路径魔数校验 + `looksLikeImage` |
| `server/.../Database.kt` | `isSelfCoverReference` 剥离自引用；入库前 `sanitizeBookName` |
| `server/.../RuleRunner.kt` | 新增 `sanitizeBookName`（两条 `BookDetails` 路径共用） |
| `web/src/main.tsx` | 保存时不再提交 `/api/covers/*` 作为外部封面地址 |
| `web/vite.config.ts` | 修正 SW 封面缓存规则（死配置 → 真实路由） |
| `server/src/test/.../CoverCacheGzipTest.kt` | 新增（10 用例） |
| `server/src/test/.../ShelfDataSanitizeTest.kt` | 新增（12 用例） |
| `web/test/e2e-cover-browser.ts` | 新增（真实浏览器解码断言） |

## 7. 遗留项
- 若后续接入更多"下载并落盘"的二进制资源（字体、音频），应复用 `decompressIfNeeded`
  与魔数校验，避免同类问题再次出现。
