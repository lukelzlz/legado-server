# ACCEPT-038 SQLite / PostgreSQL 自动建库建表（MySQL 已移除）

**适用版本**：`feat/db-multi-engine`（数据层双引擎支持）
**一句话**：`config/config.yaml` 里把 `database.type` 改成 `postgres`，服务自己建库 + 建表 + 跑起来；默认 `sqlite` 不变；`mysql` 已移除，写了会**明确报错并非 0 退出**。

---

## 0. 准备

```powershell
# 用本 PR 构建的 jar
cd E:\Desktop\legado\legado-server
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk21.0.11_10"
.\gradlew :server:fatJar --console=plain

# 独立目录验收（不影响 8080 的生产实例）
$dir = "E:\Desktop\legado\_artifacts\accept-pg"
Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $dir | Out-Null
Copy-Item (Get-ChildItem .\server\build\libs\legado-server-*-all.jar | Select-Object -First 1).FullName "$dir\legado-server.jar"
```

## 1. 首次启动：只生成配置（不会连库）

```powershell
cd $dir
& "$env:JAVA_HOME\bin\java.exe" -jar .\legado-server.jar
```

✅ 判据：出现 `[config] 首次启动：已生成配置文件 …\config\config.yaml`，进程退出、不监听端口。

## 2. 改成 PostgreSQL 并第二次启动（自动建库 + 建表）

编辑 `$dir\config\config.yaml`：

```yaml
server:
  port: 8252
data:
  dir: <$dir>\data
database:
  type: postgres
  postgres:
    host: 192.168.1.68
    port: 5432
    database: legado
    user: wfanan
    password: "w71251478"
auth:
  password: "accept-pass"
  secure_cookies: false
```

```powershell
cd $dir
& "$env:JAVA_HOME\bin\java.exe" -jar .\legado-server.jar     # 前台看日志，Ctrl+C 结束
```

✅ 判据（日志，2026-11-14 真机实测）：
- `[db] 数据库类型：postgres；目标：jdbc:postgresql://192.168.1.68:5432/legado（user=wfanan）`
- 库已存在：`[db] 已连接目标库：legado（postgres）`；库不存在时：`[db] 目标库 legado 不存在，尝试自动建库 → 已自动创建数据库：legado（postgres）`
- `[db] 建表完成：22 张表 / 23 个索引`
- `Application started` + `Responding at http://127.0.0.1:8252`

✅ 判据（真库）：`legado` 库里应有 **22 张表**（`app_setting` … `illustration_cache`）与 **23 个索引**。

## 3. 功能冒烟

```powershell
$base = "http://127.0.0.1:8252"
# 登录（拿到 Cookie）
curl.exe -c cookies.txt -H "Content-Type: application/json" --data-binary '{"password":"accept-pass"}' "$base/api/auth/login"
# 书架
curl.exe -b cookies.txt "$base/api/bookshelf"
# 健康
curl.exe "$base/healthz"
```

✅ 判据：登录 200（有 `csrfToken`）；`/api/bookshelf` 200；`/healthz` = `{"status":"ok"}`。
在网页里（`http://127.0.0.1:8252`）登录后**加一本书 / 加一条书签**，刷新仍在 ⇒ 说明真落 PostgreSQL。

> 一键冒烟脚本：`E:\Desktop\legado\_artifacts\pg-smoke\smoke.ps1 -Base "http://127.0.0.1:8253"`，
> 2026-11-14 真机实测 **34/34 passed**。

## 4. 重启幂等

再启动一次，日志应为：

```
[db] 已连接目标库：legado（postgres）
[db] 建表完成：22 张表 / 23 个索引
```

✅ 判据：不重复报错（DDL 是 `create table/index if not exists`），数据仍在。

## 5. MySQL 已移除（必须明确报错）

把 `database.type` 写成 `mysql`：

```yaml
database:
  type: mysql
```

```powershell
cd $dir
& "$env:JAVA_HOME\bin\java.exe" -jar .\legado-server.jar
echo $LASTEXITCODE
```

✅ 判据（2026-11-14 实测）：
- 一行明确的错误：`[config] mysql 支持已移除，请改用 sqlite 或 postgres（database.type: mysql 不再支持）`
- 退出码 **2**（非 0），**不会**静默回落 sqlite、不会监听端口。

## 6. 回滚 / 回到 SQLite

把 `database.type` 改回 `sqlite`（或整行删掉）即可，SQLite 文件仍是 `data.dir` 下的 `legado.sqlite`；
也可以在启动时用环境变量临时覆盖而不改文件：

```powershell
$env:LEGADO_DB_TYPE = "sqlite"; & "$env:JAVA_HOME\bin\java.exe" -jar .\legado-server.jar
```

## 已知边界

- **PG 的 `book_group.name` unique 大小写敏感**（应用层仍有 `lower()` 重名拦截）；
- **PG 版本建议 12+**（`jsonb #>>` 与 `create index if not exists` 均为常态能力）。
