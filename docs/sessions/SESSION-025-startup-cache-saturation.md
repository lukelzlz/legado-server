---
id: SESSION-025
title: 修复「源码启动报错/卡死」——启动全量缓存扫描打满服务（并完成历史书架封面补抓）
date: 2026-09-26
author: Agent
tags: [startup, book-cache, concurrency, performance, cover, regression]
---

# SESSION-025: 启动全量缓存扫描打满服务

## 1. 现象澄清：用户看到的「报错」其实不是报错

用户贴出的是这样一串日志：

```
[main] INFO - Application started in 4.151 seconds.
[DefaultDispatcher-worker-1] INFO - book cache failed: content://...epub, error=锟斤拷源锟斤拷锟斤拷锟斤拷
...（十余行同样的刷屏）
```

**关键澄清**：这些都是 `INFO` 级日志，**不是崩溃**。服务确实启动了。
但真正的问题是——**它启动了却无法响应任何请求**：

- `/healthz` 持续超时 **4 分钟以上**，多次实测最长等待 3 分钟仍无响应；
- 端口已 `LISTENING`，但请求全部超时（线程池被占满）；
- 数据库从 **13MB 膨胀到 343MB**。

`error=锟斤拷源锟斤拷锟斤拷锟斤拷` 是 GBK 控制台把 UTF-8 的「书源不存在」显示坏了，
它反映的是：**Android 的 `content://` 本地书路径在服务端必然不存在**，每次启动都在重复失败。

## 2. 三层根因

### 根因 A：`cacheRequests()` 无任何过滤，每次启动重下整架书
```kotlin
// 旧实现：select ... from book_shelf      ← 没有 where！
```
启动时 `BookCacheService.start()` 会把**书架上的每一本书**都当作待缓存任务。
实测用户库 435 本 → 435 个缓存任务。

### 根因 B：缓存任务无全局并发上限
`CACHE_CONCURRENCY = 4` 是**单本书内部**的并发。同时缓存 N 本书时，
实际并发 = **N × 4**。418 本书续做 ⇒ 上千并发网络请求争抢 `Dispatchers.IO`，
前台请求（阅读正文、搜索、封面）被彻底饿死。

### 根因 C：不区分书源类型，Android 本地书反复失败刷屏
`content://`（Android 文档路径）、`local://`（本项目本地导入）都不是服务端可抓取的地址，
却每次启动都被重新入队并失败。

## 3. 最终落地的正确解法

### 修复 1：`cacheRequests()` 只返回**真正需要续做**的书
```sql
select s.source_id, s.book_url, s.toc_url
from book_shelf s
join book_cache_status c on c.source_id = s.source_id and c.book_url = s.book_url
where c.state in ('pending', 'caching')        -- 只续做未完成的
  and s.book_url not like 'content://%'        -- 跳过 Android 本地书
  and s.book_url not like 'local://%'          -- 跳过本项目本地导入
```
语义回归正轨：这个函数**本来就叫「续做」（resume）**，而不是「全量重做」。

### 修复 2：增加**全局**并发闸门
```kotlin
/** 整机同时在飞的章节抓取上限，留裕量给前台请求 */
private val globalGate = Semaphore(MAX_GLOBAL_CHAPTER_FETCHES)  // = 8
```
放在真正的抓取点（`runner.content(...)` 外层）。无论排队多少本书，
同时进行的网络抓取恒定有界。

### 修复 3：续做分批错峰投放
`RESUME_BATCH_SIZE = 3` + `RESUME_BATCH_DELAY_MS = 300`，
避免启动瞬间几百个协程同时上场。

### 效果实测
| 指标 | 修复前 | 修复后 |
| :--- | :--- | :--- |
| `/healthz` 可响应耗时 | **> 240s（常驻超时）** | **5s** |
| `content://` 刷屏 | 每次启动十余行 | **0 行** |
| 启动期间稳定性 | 持续超时 | 连续 4 次探测全部 OK |

## 4. 连带完成：历史书架封面补抓

排查中发现另一个事实：**封面补抓逻辑上线之前导入的书，`cover_key` 全为空**
（用户 435 本中 434 本无 key）。上一轮（`SESSION-024`）修好了「导入时会补抓」，
但**已存在的历史数据不会自动修复**。

因此补了一个一次性接口 `POST /api/bookshelf/refresh-covers`：

```kotlin
// 语义与既有 /bookshelf/reclean 对齐；留空参数=整架补抓
// 用 Semaphore(6) 限并发，避免封面（单张可达数 MB）把带宽吃光
```

**在用户真实数据上实测**：
```json
{ "total": 428, "refreshed": 390, "failed": 38, "skipped": 7 }
```
| 指标 | 补抓前 | 补抓后 |
| :--- | :--- | :--- |
| 有本地封面副本 (`coverKey`) | **0** | **391** |
| `covers/` 目录文件数 | 1 | **390** |
| `/api/covers/<key>` | — | **HTTP 200，366KB 图片** |

（38 本失败属正常：部分图床链接已失效或防盗链，这些书仍走 `coverUrl` 直连兜底。）

## 5. 一次自我纠错（值得记录）

排查封面能否直连时，我用一个**手动截断的 URL** 去请求，得到 **403**，
一度判断为「图床防盗链，浏览器也加载不了，URL 回退无效」。

随后改用**从数据库里取出的完整 URL** 重测，结果全部 **HTTP 200、约 1MB PNG**。

⇒ **教训：验证外部资源可达性时，必须用系统里真实存储的完整值，**
**不要用手敲/截断的样本**。否则会把「自己构造错了」误判成「外部服务拒绝」，
进而得出错误结论（差点让我去改一个本来没必要改的设计）。

## 6. 回归对照（按项目方法论：比失败集合而非数量）
| | 用例数 | 失败数 | 失败集合 |
| :--- | :--- | :--- | :--- |
| 干净基线 worktree | 199 | 53 | 基准 |
| 带全部修复 | **208** | **53** | **逐条一致** |

⇒ **零回归**。53 个失败全部为已记录的 Windows SQLite 占用既有失败。

## 7. 沉淀的教训与部落知识 (Lessons Learned)

1. **`INFO` 级刷屏 ≠ 崩溃，但也绝不能忽略**。用户报「启动报错」，
   实际是「启动了但完全不可用」——**必须实际探测接口**，别只看日志级别就下结论。
2. **「续做」类函数必须有过滤条件**。`cacheRequests()` 无 `where` 是本次事故的总根源：
   一个名字叫 resume 的函数干着「全量重做」的事，且每次重启都重来。
3. **并发要区分「单任务内」与「全局」**。`Semaphore(n)` 加在单个任务内部，
   当任务数量本身无界时，等于没限流——**限流必须加在全局资源入口**。
4. **迁移/长跑系统要留「数据修复入口」**。修复了「新数据不再出错」不等于
   「老数据自动好了」；对已落库的坏数据，应提供一个幂等的一次性修复接口。
5. **验证外部资源必须用真实完整值**（见第 5 节自我纠错）。

## 8. 修改文件清单
| 文件 | 变更 |
| :--- | :--- |
| `server/.../Database.kt` | `cacheRequests()` 改为只续做未完成、跳过本地书源 |
| `server/.../BookCacheService.kt` | 新增 `globalGate` 全局并发闸门；续做分批错峰 |
| `server/.../Routes.kt` | 新增 `POST /api/bookshelf/refresh-covers` |
| `server/.../Models.kt` | 新增 `CoverRefreshRequest` / `CoverRefreshResponse` |

## 9. 遗留建议
- 38 本封面抓取失败的书可考虑重试机制（当前依赖前端 `coverUrl` 直连兜底）。
- 数据库已涨到 343MB（前期失控写入所致），建议评估 `VACUUM` 回收空间。
