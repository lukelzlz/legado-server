# SESSION-059 数据层真正支持 SQLite / PostgreSQL（自动建库 + 自动建表；MySQL 已移除）

- 日期：2026-11-14
- 分支：`feat/db-multi-engine`（PR #94，未合并）
- 关联：`DatabaseSpec.kt`、`SqlDialect.kt`、`DatabaseSchema.kt`（新增）、`Database.kt`、`Application.kt`、`ServerConfig.kt`、`ConfigFile.kt`
- 用户原话（本轮裁决）：「用户决定**彻底移除 MySQL 支持**，只保留 **SQLite（默认）+ PostgreSQL**」；
  历史意图（自动建库的来源）：「我的意思是如果配置的是 mysql 或者 pgsql，就自动初始化建库」
  ⇒ 自动建库/建表的语义保留给 PostgreSQL，MySQL 分支整体删除。

## 目标

`config/config.yaml` 里 `database.type: sqlite（默认）/ postgres`（`postgresql` 归一化为 `postgres`）。
选 postgres 时，服务**自己把库建出来、把表建出来**，不需要用户先执行 `create database` / 建表；SQLite 路径保持不变。

**MySQL 已移除**：配置里仍写 `database.type: mysql` 时必须**明确报错**（提示改用 sqlite/postgres）并**非 0 退出**，
绝不静默回落 sqlite —— 静默回落会让用户以为在连远端 MySQL，实际写进了本地新建的空库。

## 实现（282 处 SQL 一行未改）

| 文件 | 作用 |
| --- | --- |
| `DatabaseSpec.kt` | `DatabaseEngine`（仅 `SQLITE` / `POSTGRES`）+ `DatabaseSpec`（engine/path/jdbcUrl/user/password）+ **自动建库**：PG 连 `postgres` 库执行 `create database <db>` 并吞「已存在」；`MYSQL_REMOVED_MESSAGE` / `UnsupportedDatabaseTypeException` 收口「mysql 已移除」的明确报错；另含 JDBC 串拆解 |
| `SqlDialect.kt` | `SqlTranslator` + `DialectConnection`/`DialectStatement`：**唯一收口**包装 `prepareStatement`/`createStatement`，把 SQLite 写法翻成目标方言（SQLite 恒等） |
| `DatabaseSchema.kt` | `SQLITE_DDL` 为唯一权威来源，PG 变体由它生成（`bigint auto_increment→bigserial`、`real→double precision`、去 `collate nocase`；索引统一 `create index if not exists`） |
| `Database.kt` | 保留 `Database(path)`（sqlite，测试用）+ 新增 `Database(DatabaseSpec)`；`initialize()` 先建库再建表 |
| `Application.kt` | 删除「外部库直接拒绝」的守卫 → 打印 `[db] 数据库类型/目标`；`mysql` 配置 ⇒ `[config]` 一行明确报错 + `exitProcess(2)` |
| `ServerConfig.kt` | `databaseTypeOf` 只认 sqlite/postgres，`mysql` 抛 `UnsupportedDatabaseTypeException`；删 `database.mysql.*` 读取与 mysql 连接串拼接 |
| `ConfigFile.kt` | 默认模板删掉整个 `mysql:` 段，`type` 注释改为 `sqlite（默认）/ postgres` |
| `server/build.gradle` | 删除 `com.mysql:mysql-connector-j:9.1.0`（fatJar 从 44 MB 降到 40 MB） |

### 翻译规则（实测覆盖）

| SQLite | PostgreSQL |
| --- | --- |
| `insert … on conflict(c) do update set x=excluded.x` | 原样（PG 原生支持 `excluded`） |
| `insert … on conflict do nothing` | 原样 |
| `x = ? collate nocase` / `name collate nocase` | 两侧包 `lower()` |
| `order by name collate nocase` | `order by lower(name)` |
| `group by … collate nocase` | 只删不加（PG 要求表达式与 select 一致） |
| `pragma table_info(x)` | `information_schema.columns`（列名仍叫 `name`） |
| `pragma journal_mode = wal` | 跳过执行 |
| `json_extract(a,'$.b')` | `a::jsonb #>> '{b}'` |
| `real`（DDL / alter） | `double precision` |
| `integer` + `autoincrement` | `bigserial` |

### 顺带修掉的 3 处「只有 SQLite 才过」的 SQL（审查点）

- 2 处派生表缺别名（PG 报错）→ 补别名；
- 2 处 `GROUP BY` 里选了非聚合列（`s.name` / `b.book_author`）→ 换 `max(...)`/`min(...)`，语义等价（每组同值）。

## 证据（真跑，2026-11-14）

| 项 | 结果 |
| --- | --- |
| 单测子集 | `--tests "*Db*" + ConfigFileTest + BookmarkRoutesTest` = **33 用例 0 失败**（`MultiEngineDbTest` 13、`ConfigFileTest` 10、`BookmarkCoverDbTest` 6、`BookmarkRoutesTest` 4） |
| `grep -ri mysql server/src` | 只剩「已移除」的提示文案与 2 条断言用例，无代码分支/依赖（逐条见下） |
| `type: mysql` 启动实测 | stderr 一行 `[config] mysql 支持已移除，请改用 sqlite 或 postgres（database.type: mysql 不再支持）`，**退出码 2**，不监听端口 |
| PostgreSQL 192.168.1.68:5432（user **wfanan**） | `[db] 数据库类型：postgres；目标：jdbc:postgresql://192.168.1.68:5432/legado（user=wfanan）` → `[db] 已连接目标库：legado（postgres）` → `[db] 建表完成：**22 张表 / 23 个索引**`；库首次不存在时由 `create database "legado"` 自动创建 |
| HTTP 冒烟 | **34/34 passed**（healthz / 登录 / 书架读写 / 书签 / `app_setting` upsert / `json_extract` / 分组聚合 / 进度 upsert / 多路 list） |
| 重启幂等 | `create table if not exists` + `create index if not exists` ⇒ 重复启动不报「已存在」，数据仍在 |
| SQLite 零回归 | 翻译层在 sqlite 下是**恒等变换**（单测锁定）；既有 SQLite DDL 一字未改 |

## 保留的 mysql 字样（为什么可以保留）

- `DatabaseSpec.kt`：`MYSQL_REMOVED_MESSAGE` 与 `DatabaseEngine.of("mysql")` 的报错分支 —— 没有它就没有「明确报错」；
- `ServerConfig.kt`：`databaseTypeOf("mysql")` 抛 `UnsupportedDatabaseTypeException` —— 配置入口的唯一拦截点；
- `Application.kt`：main 捕获该异常并打印一行 + `exitProcess(2)`；
- `DatabaseSchema.kt` / `SqlDialect.kt`：各一行注释说明「MySQL 支持已移除」；
- 测试：`MultiEngineDbTest` / `ConfigFileTest` 各一条「mysql 必须明确报错」断言。

## 已知边界 / 后续

1. **PG 唯一约束大小写**：`book_group.name` 在 PG 是大小写敏感 unique（SQLite 是 `collate nocase`）；应用层 `lower()` 重名拦截仍在，DB 层若要齐平需补表达式唯一索引。
2. **PG 实测修掉的两类 SQL**：`order by <select 别名> collate nocase` 在 PG 会被翻成 `lower(别名)` 而报 column does not exist（`listSourceGroups` 改为 `min(trim(..))` 分组 + 裸别名排序）；`coalesce(c.chapter_count,0)` 这类非聚合列在 GROUP BY 里 PG 直接拒绝（漫画/音频缓存统计改为 `coalesce(max(...),0)`）。
3. **配置键修正（历史小 bug）**：模板写 `database.sqlite.file`，而旧解析读 `data.database` ⇒ 本次改为**读 `database.sqlite.file` 并兼容旧键**（`data.database` 仍可用）。
## 追加：分组名大小写语义以 SQLite 为准

用户明确「按 sqlite 的逻辑来」⇒ PostgreSQL 上补齐 `book_group` 名字的大小写不敏感唯一：

- **DDL**：PG 保留列级 `unique(name)`（`importBookGroups` 的 `on conflict(name)` 必须能匹配到普通唯一约束），**追加**
  `create unique index if not exists book_group_name_ci on book_group (lower(name))`；
- **建索引容错**：库里若已有 `News`/`news` 这类重复，建索引会失败 ⇒ 只对这一种情况**打 warning 并跳过**（附排查 SQL），其它 DDL 错误照旧抛出；
- **导入**：`importBookGroups` 写库前按 `lower(name)` 读出现有分组，大小写变体映射到库里既有写法（不新建、不改用户分组名），避免新索引把整包导入搞失败；
- **建组**：`createBookGroup` 的「先 count 再 insert」竞态窗口现在被数据库拦住，`isUniqueViolation` 翻成「分组「X」已存在」，不再漏成 500；
- **实测**：PG 真机建 `News` 成功、再建 `news` 返回 `group_exists` 友好提示；`[db] 建表完成：22 张表 / 24 个索引`；冒烟 34/34。