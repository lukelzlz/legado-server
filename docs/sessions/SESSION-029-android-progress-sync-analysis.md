---
id: SESSION-029
title: 手机端阅读进度同步逻辑完整分析（为何网页进度总被覆盖）
date: 2026-09-27
author: Agent
tags: [progress-sync, legado-android, source-analysis, webdav, conflict-resolution]
---

# SESSION-029: 手机端进度同步逻辑完整分析

## 0. 结论先行

**Legado 手机端的进度同步是「本地优先、单向上推」，没有「远端更新则自动采纳」的常规路径。**

| 问题 | 答案 |
| :--- | :--- |
| 手机是否比较 `durChapterTime`？ | **完全不比较**（该字段只被原样读写，不参与任何判断） |
| 手机开书会覆盖文件吗？ | **会**，且有多条触发点 |
| 「两边取最新」在手机端能实现吗？ | **不能**，除非改手机端代码 |
| 服务端写新时间戳能赢吗？ | **不能**，手机不看时间戳 |

## 1. 数据模型（`data/entities/BookProgress.kt`）

```kotlin
data class BookProgress(
    val name: String,
    val author: String,
    val durChapterIndex: Int,
    val durChapterPos: Int,
    val durChapterTime: Long,     // 仅存储，不参与比较
    val durChapterTitle: String?
)
```

字段与我们的实现**完全一致**，序列化用 GSON。

## 2. 两个开关（`domain/model/settings/BackupSettings.kt`）

```kotlin
val syncBookProgress: Boolean = true,        // 「同步阅读进度」
val syncBookProgressPlus: Boolean = false,   // 「同步增强」
```

UI 文案（`strings.xml`）：
- `同步阅读进度` —— "进入退出阅读界面时同步阅读进度"
- `同步增强` —— "重新进入页面（息屏、后台返回等）或者网络变为可用时同步云端进度"

## 3. 三条同步路径（逻辑各不相同）

### 路径 A：`ReadBook.syncProgress()` — `ReadBook.kt:810`

```kotlin
fun syncProgress(newProgressAction, uploadSuccessAction, syncSuccessAction) {
    if (!syncBookProgress) return
    AppWebDav.getBookProgress(book).onSuccess { progress ->
        if (progress == null ||
            progress.durChapterIndex < book.durChapterIndex ||
            (progress.durChapterIndex == book.durChapterIndex &&
             progress.durChapterPos < book.durChapterPos)) {
            // ① 远端为空 或 远端更靠前 → 上传本地覆盖服务器
            AppWebDav.uploadBookProgress(book) { ... }
        } else if (progress.durChapterIndex > book.durChapterIndex ||
                   progress.durChapterPos > book.durChapterPos) {
            // ② 远端更靠后 → 只回调（弹窗让用户确认），不自动应用
            newProgressAction?.invoke(progress)
        } else {
            syncSuccessAction?.invoke()
        }
    }
}
```

**关键点**：
- 分支①的 `progress == null` 意味着**只要文件读不到（文件名不匹配/网络抖动）就直接覆盖**
- 分支②远端更新时**只弹提示**，不 `setProgress`
- **全程不看 `durChapterTime`**

### 路径 B：`ReadBookLoadDelegate.syncBookProgress()` — `ReadBookLoadDelegate.kt:286`

```kotlin
fun syncBookProgress(book, alertSync) {
    if (!syncBookProgress) return
    getReadingProgressUseCase.execute(book.name, book.author)?.toBookProgress()
        .onSuccess { progress ->
            progress ?: return@onSuccess
            if (progress.durChapterIndex < book.durChapterIndex || ...) {
                alertSync?.invoke(progress)          // 远端更靠前 → 只提示
            } else if (progress.durChapterIndex < book.simulatedTotalChapterNum()) {
                ReadBook.setProgress(progress)       // 远端更靠后 → 直接采纳 ✅
            }
        }
}
```

**这条路径逻辑是正确的**（远端更新会采纳），但**只在 `syncBookProgressPlus == false` 时走**。

### 路径 C：`ReadBook.uploadProgress()` — `ReadBook.kt:783`

```kotlin
fun uploadProgress(toast, successAction) {
    book?.let { uploadingBook ->
        launch(IO) { AppWebDav.uploadBookProgress(uploadingBook, toast) { ... } }
    }
}
```

**无条件上传，不做任何比较。**

## 4. 触发点矩阵（开书 / 退出 / 翻页）

### 进入阅读界面 —— `ReadBookViewModel.kt:1618`

```kotlin
if (!BuildConfig.DEBUG) {                                   // 仅 Release 版
    if (syncBookProgressPlus) ReadBook.syncProgress()        // 路径 A
    else ReadBook.uploadProgress()                           // 路径 C ← 无条件覆盖！
}
```

> **注意**：`syncBookProgressPlus == false`（默认值）时，开书直接走**路径 C 无条件上传**。

### 进入阅读界面（另一处）—— `ReadBookLoadDelegate.kt:194`

```kotlin
if (ReadBook.chapterChanged) { ReadBook.chapterChanged = false }
else if (!(isSameBook && BaseReadAloudService.isRun) && ReadBook.inBookshelf) {
    if (syncBookProgressPlus) ReadBook.syncProgress({ host.sureNewProgress(it) })  // 路径 A
    else syncBookProgress(book)                                                   // 路径 B
}
```

### 退出阅读界面 —— `ReadBookViewModel.kt:1653`

```kotlin
uploadBookProgress(book)   // 走 loadDelegate → UploadReadingProgressUseCase → 无条件上传
```

### 网络恢复 —— `ReadBookViewModel.kt:1635`

```kotlin
if (syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData) {
    ReadBook.syncProgress(newProgressAction = { sureNewProgress(it) })   // 路径 A
}
```

### HTTP API（供 Web 端调用）—— `api/controller/BookController.kt`

```kotlin
suspend fun saveBook(postData)        { ...; AppWebDav.uploadBookProgress(book) }        // :234 无条件
suspend fun saveBookProgress(postData){ ...; AppWebDav.uploadBookProgress(bookProgress) } // :266 无条件
```

## 5. 为什么实测「我写 idx=99/15/77 都被覆盖成手机本地值」

两次对照实验 + 源码共同解释：

| 实验 | 我写入 | 手机开书后 | 解释 |
| :--- | :--- | :--- | :--- |
| 1 | `idx=15`（最新时间戳） | `idx=8` | 走路径 C（`syncBookProgressPlus=false`）→ **无条件上传本地** |
| 2 | `idx=99` | `idx=8` | 同上 |
| 2 | `idx=77` | `idx=29` | 同上 |

**关键佐证**：实验中服务端数据库是 `0`、文件被我改成 `99`，手机却写出 `8` ——
`8` 既不来自文件也不来自服务端，**只能来自手机本地 `Book` 表**。

即使你开了「同步增强」（走路径 A），分支②对"远端更靠后"也只是**弹窗提示**，
而手机随后的任何阅读动作（翻页/退出）都会触发上传，**最终还是覆盖**。

## 6. 结论与可选方案

### 现状
- 手机 → 文件：**畅通**（多条路径，主动推送）
- 文件 → 手机：**仅弹窗提示，不自动采纳**
- 服务端写什么都无法阻止被覆盖

### 方案对比

| 方案 | 可行性 | 代价 |
| :--- | :--- | :--- |
| 服务端写新 `durChapterTime` | ❌ 无效 | 手机不比较时间戳 |
| 服务端写"更靠后"的章节 | ❌ 无效 | 被无条件上传覆盖 |
| **改手机端**：让 `syncProgress` 比较 `durChapterTime`，远端更新则 `setProgress` | ✅ 从根上解决 | 需改 `ReadBook.kt` 并重新编译安装 APK |
| **服务端为准**：网页忽略进度文件，只用服务端数据库 | ✅ 改动最小 | 网页看不到手机的进度，手机也看不到网页的 |
| **加防倒退**：服务端检测进度倒退时告警 + 可恢复 | ✅ 治标 | 不解决"手机不读"的根本问题 |

### 若要改手机端，最小改动点

`ReadBook.kt:810` 的 `syncProgress()` 分支②，把"只回调"改为"比较 `durChapterTime` 后采纳"：

```kotlin
} else if (progress.durChapterIndex > book.durChapterIndex ||
           progress.durChapterPos > book.durChapterPos) {
    // 建议：远端确实更新（durChapterTime 更大）时直接采纳，而不是只弹提示
    newProgressAction?.invoke(progress)
}
```

但**仅改这一处不够** —— 还要处理 `ReadBookViewModel.kt:1622` 的
`ReadBook.uploadProgress()`（无条件上传），否则开书瞬间又覆盖回去。

## 6.5 最终决定：手机不动，服务端对齐手机规则

用户决定：**不改手机端，把服务端的取值逻辑改成和手机一样。**

### 具体改动（`Routes.kt`）

**冲突消解改为与手机 `syncBookProgress`（`ReadBookLoadDelegate.kt:297`）完全一致**：

```kotlin
val fileWins = when {
    dbIndex == null -> true                       // 本地无进度 → 用文件的
    fileIndex > dbIndex -> true                   // 远端更靠后 → 采纳
    fileIndex < dbIndex -> false                  // 远端更靠前 → 保留本地
    else -> remoteHasPosition && !localHasPosition // idx 相同 → 比 pos
}
```

即「**取更大的 idx；idx 相同则取更大的 pos**」。

**同时把比较依据从 `durChapterTime` 换成 `idx`/`pos`** —— 这是关键修正：
实测云游异世界 `326 → 329 → 328 → 323`，用户回翻时**时间戳更新但 idx 倒退**，
若按"时间戳更新就采纳"，网页进度会跟着往回跳。

### 写入方向：**不做防护，允许倒退**

用户明确要求：**手机倒退 → 同步倒退**。

因此 `syncProgressToFile` 照当前进度写，不做"只增不减"的保护 ——
与手机 `syncProgress`（写入时无防护）语义一致。

> 注：手机是「**读的时候比对、写的时候照写**」，我们两条路径也分别对齐，不矛盾。

### 真实观测（云游异世界，用户操作）

| 时刻 | idx | 说明 |
| :--- | :--- | :--- |
| 09:25:45 | 326 | |
| 09:26:20 | 329 | 读到第 330 章 ✅ 前进 |
| 09:28:02 | 328 | 回翻一页 ⚠️ **时间戳更新、idx 倒退** |
| 09:29:18 | 323 | 继续回翻 |

这组数据证明：**手机既不比较时间戳，也不保护回退**，
所以服务端只能按 `idx` 对齐、并接受倒退。

### 验证
- 新增 `ProgressMergeRuleTest`（12 用例）：取大值、idx 相同比 pos、
  **真实序列 `326→329→328` 不误判**、明确锁定「写入允许倒退」
- 回归：290 → **302** 用例，失败 **54 → 54 集合逐条一致** ⇒ 零回归

## 7. 沉淀的教训

1. **跨端同步必须两端都实现"冲突消解"**：只在一端做时间戳比较，
   另一端无条件推送，等价于没做比较。设计同步功能时**先确认对端的写入策略**，
   不要假设它"会读"。
2. **实测实验要选"对端绝不可能产生的值"**：本次用 `idx=99`/`77` 这种极端值，
   一次就能判定"是覆盖还是采纳"，比观察普通进度可靠得多。
3. **`progress == null` 触发写入是危险设计**：读失败被当成"远端没有"，
   直接用本地覆盖，会把网络抖动放大成数据丢失。
