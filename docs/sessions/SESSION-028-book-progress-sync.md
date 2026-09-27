---
id: SESSION-028
title: 新增手机端书籍进度双向同步（bookProgress 文件夹）
date: 2026-09-27
author: Agent
tags: [progress-sync, bookProgress, webdav, feature, path-traversal, chapter-alignment]
---

# SESSION-028: 书籍进度双向同步（bookProgress）

## 0. 需求

用户在「文件/设置」页配置**进度文件夹名**（手机端备份里 `legado` 下是手机数据，
`bookProgress` 里是各书的进度文件）。要求：

1. 网页打开某本书时，**同步读取数据库与该文件夹里这本书的进度**，按更新的那条来；
2. 网页每往下一章，**自动回写到 JSON 进度文件**。

## 1. 真实数据结构（实测）

```
webdav/
├── legado/                     ← 手机端备份自带一层
│   ├── background/
│   ├── bookProgress/           ← 进度文件在这里
│   │   ├── 长征十日_安南十八子.json
│   │   └── 十日终焉我成魔第八十章 星尘归寂，余念长存_梁灼安.json
│   └── books/
```

文件格式（`<书名>_<作者>.json`）：

```json
{
  "author": "梁灼安",
  "durChapterIndex": 4,
  "durChapterPos": 0,
  "durChapterTime": 1790465002215,
  "durChapterTitle": "第5 章 ：再考心智我甘心受辱",
  "name": "十日终焉我成魔\n第八十章 星尘归寂，余念长存"
}
```

**关键约束**：文件里只有章节**标题**，没有 URL，而书源给的标题格式可能不同。

## 2. 设计决策

### 2.1 章节对齐：index 为主 + 标题规范化匹配为辅

单用 `durChapterIndex` 会在换源后错位（章节数不同）；单用标题又会因空白/全半角/标点差异找不到。
因此按优先级：

1. `durChapterIndex` 在范围内**且**该章标题规范化后相似 → 用它
2. 按规范化标题**全表查找**（先相等，再包含）
3. 都不中 → 用 `durChapterIndex` 兜底并**夹到合法范围**

`normalizeForMatch()` 去掉所有空白与常见标点、全角转半角、统一小写，
使「第5 章 ：再考心智我甘心受辱」与「第5章：再考心智我甘心受辱」视为同一个。

### 2.2 冲突消解：比 `durChapterTime`

与既有 `reading_progress` 导入逻辑一致——取时间戳更新的那个：
- 文件更新 → 写回数据库并采用该章节
- 数据库更新 → 保持数据库

### 2.3 配置存服务端

前端设置都在 localStorage，但**这个配置是服务端读文件用的**，
因此新增轻量 `app_setting(key,value)` 表持久化。

### 2.4 写文件失败绝不阻塞阅读

进度文件只是**镜像**，权威数据永远是本地 SQLite。所有读写都 `runCatching` 包住。

## 3. 踩到的三个坑（都有回归测试锁定）

### 坑 1：默认路径少了 `legado` 一层 ← 端到端才发现

最初默认写 `bookProgress`，结果：

- 真实文件在 `webdav/legado/bookProgress/`，**读不到**（`fileFound=false`）
- 写入时**另建了一个空的** `webdav/bookProgress/`

表现极具误导性：「文件明明就在那儿，却读不到」。
修法：默认值改为 `legado/bookProgress`，并**支持多级子目录**。

### 坑 2：`Path.resolve()` 对非法字符**抛异常**，不是返回不存在的路径

书名含换行时（用户数据里真实存在）：

```
java.nio.file.InvalidPathException: Illegal char <\n> at index 7
```

`findFile()` 的候选循环里，**第一个未清洗的候选就会抛异常中断整个查找**，
后面真正匹配的候选永远试不到。

修法：候选循环里 `runCatching { dir.resolve(name) }.getOrNull() ?: continue`。

### 坑 3：Kotlin 普通字符串里的 `"\\r"` 是字面量 `\r`，不是回车

`sanitizeFileComponent` 原本写 `Regex("[\\r\\n/\\\\:*?\"<>|]")`，
在 Kotlin 中 `"\\r"` 是**两个字符**（反斜杠 + r），所以**真实换行根本没被去掉**。
修法：改用原始字符串 `Regex("""[\r\n/\\:*?"<>|]""")`。

> 这三个坑的共同点：**都不是逻辑错误，而是「看起来对」的写法在边界上失效**，
> 且都只在真实数据上暴露。单测必须用真实文件名（含换行）才能覆盖。

## 4. 实现

### 后端
| 文件 | 变更 |
| :--- | :--- |
| `Database.kt` | 新增 `app_setting` 表 + `getSetting`/`setSetting`；新增 `getChapterTitle`（从目录缓存回查标题） |
| `BookProgressSync.kt` | **新增**：目录解析与路径穿越防护、文件名候选与归一化匹配、原子写、`alignChapter` 对齐 |
| `Routes.kt` | `GET/PUT /api/progress-sync/settings`、`POST /api/progress-sync/merge`；`PUT /api/reading-progress` 顺带写文件 |
| `Models.kt` | 新增 DTO；`ReadingProgress` 增加可选 `chapterTitle` |
| `Application.kt` | 注入 `BookProgressSync(config.webDavDirectory, database)` |

### 前端
| 文件 | 变更 |
| :--- | :--- |
| `api.ts` | 新增 3 个接口 + 类型；`saveProgress` 支持传 `chapterTitle` |
| `WebDavSettingsPage.tsx` | 新增「阅读进度同步」区块（文件夹名输入 + 保存 + 状态徽标） |
| `main.tsx` | 打开书时调用 `mergeProgress` 合并，失败静默回退数据库进度 |
| `ReaderScreen.tsx` | `persist()` 带上章节标题 |
| `styles.css` | 新区块样式 |

## 5. 验证

### 真实数据端到端（你的实际 bookProgress）
| 书籍 | fileFound | 合并结果 | 来源 |
| :--- | :--- | :--- | :--- |
| 长征十日 | ✅ | idx=3 | database |
| 十日终焉我成魔（含换行书名） | ✅ | **idx=5** | **file** |
| 真十日终焉 | ❌（无进度文件） | — | database |

**注意第二行**：书名含换行（手机端去掉了换行命名），验证了归一化匹配兜底有效，
并正确从手机进度取到 `第5 章 ：再考心智我甘心受辱`。

**回写验证**：模拟翻到 index=3 →
`webdav/legado/bookProgress/长征十日_安南十八子.json` 的 `durChapterIndex` 变为 3、
标题同步更新，且**手机端写入的 `name`/`author` 被保留**、没有误建多余目录。
（验证后已把该文件恢复为你手机的原始值 index=0。）

### 回归
| | 用例数 | 失败数 | 失败集合 |
| :--- | :--- | :--- | :--- |
| 基线 `be178d0` | 259 | 54 | 基准 |
| 带本功能 | **283** | **54** | **逐条一致** |

⇒ **零回归**。前端 `npm run check` 0 错误、`tsx run-all.ts` **146/146**。

新增测试 **25** 个：`BookProgressSyncTest`（24，含路径穿越、非法字符、
多级目录、标题对齐、原子写、时间戳保留）+ `progress-sync-settings.test.ts`（1，UI）。

## 6. 沉淀的教训

1. **「看起来对」的写法会在边界失效**：`"\\r"` 不是回车、`resolve()` 会抛异常、
   默认路径少一层——三者都不是逻辑错误，只能靠**真实数据端到端**暴露。
2. **文件类功能必须做路径穿越防护**，且要断言**最终解析结果**落在根内
   （只清洗输入字符串不够，规范化后的校验才是最终防线）。
3. **镜像数据失败绝不能阻塞主流程**：进度文件写失败只是少一次同步，
   不该让用户的阅读进度保存失败。
4. **保留对端字段**：回写 JSON 时沿用手机端写入的 `name`/`author`/`durChapterPos`，
   只更新我们负责的字段，避免把两端的命名风格互相污染。

## 7. 遗留项
- 若手机端新增 `durChapterPos` 的语义变化（字符偏移 vs 百分比），需要再对齐一次。
- 当前只在「翻章」与「保存进度」时回写；若要求「滚动到某位置也立刻落盘」，
  可复用同一条链路（`saveProgress` 已带标题）。
