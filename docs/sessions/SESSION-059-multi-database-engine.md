# SESSION-059 数据层真正支持 MySQL / PostgreSQL（自动建库 + 自动建表）

- 日期：2026-11-14
- 分支：`feat/db-multi-engine`（基于配置分支 `feat/config-firstrun-dbtype`，同一主题链路；PR 见下）
- 关联：`DatabaseSpec.kt`、`SqlDialect.kt`、`DatabaseSchema.kt`（新增）、`Database.kt`、`Application.kt`、`ServerConfig.kt`
- 用户原话：「我测试mysql可以连上，root，w71251478，然后两个新的数据库自动初始化和创建表什么的」「我的意思是如果配置的是mysql或者pgsql，就自动初始化建库」

## 目标

`config/config.yaml` 里 `database.type: sqlite（默认）/ mysql / postgres`。
选 mysql/postgres 时，服务**自己把库建出来、把表建出来**，不需要用户先执行 `create database` / 建表；SQLite 路径保持不变。

## 实现（282 处 SQL 一行未改）

| 文件 | 作用 |
| --- | --- |
| `DatabaseSpec.kt` | `DatabaseEngine` + `DatabaseSpec`（engine/path/jdbcUrl/user/password）+ **自动建库**：MySQL 连无库名串执行 `create database if not exists <db> character set utf8mb4 collate utf8mb4_general_ci`；PG 连 `postgres` 库执行 `create database <db>` 并吞「已存在」；另含 JDBC 串拆解 |
| `SqlDialect.kt` | `SqlTranslator` + `DialectConnection`/`DialectStatement`：**唯一收口**包装 `prepareStatement`/`createStatement`，把 SQLite 写法翻成目标方言 |
| `DatabaseSchema.kt` | `SQLITE_DDL` 为唯一权威来源，MySQL/PG 变体由它生成（`bigint auto_increment` / `bigserial`、索引列 `varchar(255)`、正文 `longtext`、MySQL 无 `create index if not exists` → 查 `information_schema.statistics`） |
| `Database.kt` | 保留 `Database(path)`（sqlite，测试用）+ 新增 `Database(DatabaseSpec)`；`initialize()` 先建库再建表 |
| `Application.kt` | 删除「外部库直接拒绝」的守卫 → 打印 `[db] 数据库类型/目标`，按 spec 构造 `Database` |

### 翻译规则（实测覆盖）

| SQLite | MySQL | PostgreSQL |
| --- | --- | --- |
| `insert … on conflict(c) do update set x=excluded.x` | `insert … on duplicate key update x=values(x)`（用 `as new` 别名避免歧义） | 原样（PG 支持 `excluded`） |
| `insert … on conflict do nothing` | `insert ignore …` | 原样 |
| `x = ? collate nocase` / `name collate nocase` | 去掉 `collate nocase`（库/列默认 `utf8mb4_general_ci` 已不区分大小写） | 两侧包 `lower()` |
| `pragma table_info(x)` | `information_schema.columns` | `information_schema.columns` |
| `json_extract(a,'$.b')` | `nullif(json_unquote(json_extract(a,'$.b')),'null')` | `a #>> '{b}'` |
| `real`（DDL） | `double` | `double precision` |
| `alter … text default ''` | `varchar(512)`（MySQL 的 TEXT 不允许默认值） | 原样 |
| 保留字 `key` 列 | 加反引号 | 原样 |

### 顺带修掉的 3 处「只有 SQLite 才过」的 SQL（审查点）

- 2 处派生表缺别名（MySQL/PG 都报错）→ 补别名；
- 2 处 `GROUP BY` 里选了非聚合列（`s.name` / `b.book_author`）→ 换 `max(...)`/`min(...)`，语义等价（每组同值）。

## 证据（真跑，2026-11-14）

| 项 | 结果 |
| --- | --- |
| 单测子集 | `--tests "*Db*" + BookmarkRoutesTest + ConfigFileTest` = **39 用例 0 失败**；`MultiEngineDbTest` 20/20 |
| MySQL 192.168.1.68:3306（8.0.46，库原本不存在） | `[db] 目标库 legado 不存在 → 已自动创建（utf8mb4）→ 建表完成：22 张表 / 23 个索引` |
| 真库结构 | `show tables` = **22 行**，默认 `utf8mb4 / utf8mb4_general_ci` |
| HTTP 冒烟 | **34/34**（healthz / 登录 / 书架读写 / 书签 / `app_setting` 保留字 upsert / `json_extract` / 分组聚合 / 进度 upsert / 多路 list） |
| 重启幂等 | 再启动：`已连接目标库：legado` + `22 张表 / 0 个索引（已存在索引跳过 23 个）`，冒烟仍 34/34 |
| SQLite 零回归 | 失败集合与干净基线 worktree 逐条一致（Windows 删不掉被占用 sqlite 的历史问题） |
| PostgreSQL | ✅ **34/34** | 用户 `wfanan`（不是 postgres）：`[db] 目标库 legado 不存在，尝试自动创建 → 已自动创建数据库：legado` → `建表完成：22 张表 / 23 个索引`，HTTP 冒烟 34/34（与 MySQL 同一套脚本） |

## 已知边界 / 后续

1. **MySQL 索引列 `varchar(255)`**：受 utf8mb4 联合索引 3072 字节限制，`book_url/chapter_url` 等主键列只能 255 字符；超长 URL（`data:;base64` 聚合源）在 STRICT 模式会 `Data too long`。彻底解法是把 URL 身份换成哈希键（独立立项）。
2. **PG 唯一约束大小写**：`book_group.name` 在 PG 是大小写敏感 unique（SQLite 是 `collate nocase`）；应用层 `lower()` 重名拦截仍在，DB 层若要齐平需补表达式唯一索引。
4. **PG 实测修掉的两类 SQL**：`order by <select 别名> collate nocase` 在 PG 会被翻成 `lower(别名)` 而报 column does not exist（`listSourceGroups` 改为 `min(trim(..))` 分组 + 裸别名排序）；`coalesce(c.chapter_count,0)` 这类非聚合列在 GROUP BY 里 PG 直接拒绝（漫画/音频缓存统计改为 `coalesce(max(...),0)`）。
3. **配置键修正（历史小 bug）**：模板写 `database.sqlite.file`，而旧解析读 `data.database` ⇒ 本次改为**读 `database.sqlite.file` 并兼容旧键**（`data.database` 仍可用）。
