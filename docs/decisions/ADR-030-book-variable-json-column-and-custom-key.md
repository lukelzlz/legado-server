# ADR-030 · 书籍变量用「book_shelf 上的 JSON 字符串列 + custom 键」承载

- **状态**：Accepted
- **关联**：[PROPOSAL-030](../proposals/PROPOSAL-030-book-variables-and-narrator-switching.md) · [ACCEPT-030](../acceptance/ACCEPT-030-book-variables.md)

---

## 1. 背景

书源规则通过 `book.getVariable(key)` 读取**书籍级**自定义变量（典型用途：听书音色/朗读者、
按书切换线路）。本服务的沙箱早已导出该函数，但**没有任何存储**，因此恒为空值 ⇒ 该能力静默失效。

需要决定三件事：

1. 变量存在哪里（表结构）；
2. 对外/对 JS 用什么形态（结构化映射 vs JSON 字符串）；
3. 编辑写入的粒度（整表覆盖 vs 单键合并）。

## 2. 决策

### 2.1 存在 `book_shelf` 上，一个 `variable text` 列，内容是**整张表的 JSON 字符串**

```sql
alter table book_shelf add column variable text   -- 幂等：列已存在则跳过
```

形态：`{"custom":"6","线路":"B"}`；**空表写 NULL**（不是 `"{}"`）。

理由：

- **对齐开源阅读**（本仓库的兼容目标）：`legado-with-MD3` 的 `BaseBook` 就是
  `var variable: String?` + `by lazy { GSON.fromJsonObject<HashMap<String,String>>(variable) }`，
  且 `putVariable` 后 `variable = GSON.toJson(variableMap)`。字段名、类型、序列化时机全部照抄。
- **`book_shelf` 就是 Legado 的 `Book` 表**：其变量随 `saveBook` 持久化，语义等价。
  不另建表 ⇒ 不引入 join，也不产生"书不在书架但有变量"这种无主数据。
- **JSON 字符串而不是列/子表**：键名完全由书源自定义（`custom`、`线路`、任意中文键），
  列化必然要迁移表结构；子表则要新增一张表 + 一个 DAO 面，属于"为单一补丁增加抽象层"。

### 2.2 `custom` 键做成一等公民，编辑只写它

对齐 `Book.putCustomVariable(value)` / `getCustomVariable()`：

- `PUT /api/bookshelf/info` 的 `customVariable`：**缺省 = 不动**；传值 = **合并**写入该键。
- 由**服务端**做合并（而不是让前端提交整表 JSON）：`custom` 的语义在 Legado 里是定死的，
  服务端合并才能保证"编辑音色"永远不会误删用户其他的键。

### 2.3 JS 侧同时暴露 `variable`（JSON 串）、`variableMap`（对象）与 `getVariable`

- `getVariable(key)` 取不到 ⇒ **空串**（`RuleDataInterface`：`variableMap[key] ?: ""`），
  **不是** `"null"`；
- `book.variable` 空表 ⇒ **空串**，让 `if (!book.variable)` 这类判空成立
  （真实书源用它判"缺少必要参数"）；
- 变量表可变，规则里的 `putVariable` 在内存生效，由调用方在规则跑完后比对落库。

### 2.4 迁移与兼容

- 新增列由 `migrateBookshelf()` 幂等补齐（老库直接 `alter table`）；
- 三处书架 SELECT（`listBookshelf` / `getShelfBookByUrl` / `getBookshelf`）**必须同时**带上该列
  ——「表里有」不等于「接口能拿到」（历史教训：封面 `cover_url` 就是这样丢的）；
- 备份 `bookshelf.json` 的 `variable` 双向支持，导入用
  `coalesce(excluded.variable, book_shelf.variable)` 保护本机已改的值。

## 3. 备选方案与否决理由

| 备选 | 否决理由 |
| :--- | :--- |
| 独立 `book_variable(source_id, book_url, ...)` 表 | 多一次 join；与 Legado「变量属于 Book 实体」的模型不一致；出现"书不在书架却有变量"的无主行 |
| 为每个已知变量建列（`custom`、`tone_id`…） | 键名由书源任意定义，列化必然持续迁移；且 `custom` 之外的值会被拒之门外 |
| 前端提交并覆盖整表 JSON | 一次误操作即可抹掉全部变量；`custom` 的合并语义应由服务端保证 |
| 让 `getVariable` 保持返回 `"null"` | 与 `RuleDataInterface` 不一致；书源里 `== 'null'` 的兼容判断只是巧合，不能当成契约 |
| 实现 Legado 的超大变量外置（`RuleBigDataHelp`，≥10000 字符落文件） | 本场景（音色编号、线路名）用不到；引入第二套存储与 GC 不划算。如未来确有需求再单独立项 |

## 4. 影响

**正面**：有声书的"切换朗读者"从"填了也没用"变为可用；书籍级变量对全部书源形态
（声明式 + `mainJs`）一致可用；备份跨设备不再丢设置。

**代价 / 已知限制**：

- 单个变量值不做超大外置（见上表），接口层只做常规长度约束；
- `BookCacheService` 的**并发**正文抓取使用变量表**副本**（只读语义），该路径不回写变量
  —— 并发合并语义复杂，而离线缓存本身不需要产生变量；
- 规则在**详情/目录**阶段写入的变量会落库；在**并行离线缓存**阶段写入的不会。

## 5. 验证

- `BookVariableTest`（12 用例）：存储往返、空表归一化、脏 JSON 容错（含数字按字符串读入，
  对齐 Gson）、`custom` 合并保留其余键、三处 SELECT 透出、编辑接口三种语义、
  沙箱端到端读到值、缺失键空串、`book.variable/variableMap/name/author/kind/type` 可见、
  规则写入回写、备份导入导出往返、重导入不清空本机值。
- `BookVariableRoutesTest`：HTTP 契约闭环（加入书架 → 写 `custom` → 书架读回 → 改名不清空）。
- `./gradlew :server:test` 与干净基线的**失败集合**逐条比对（禁止只看数量）。
