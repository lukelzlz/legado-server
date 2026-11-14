# ACCEPT-038 MySQL / PostgreSQL 自动建库建表

**适用版本**：`feat/db-multi-engine`（数据层多引擎支持）
**一句话**：`config/config.yaml` 里把 `database.type` 改成 `mysql` 或 `postgres`，服务自己建库 + 建表 + 跑起来；默认 sqlite 不变。

---

## 0. 准备

```powershell
# 用本 PR 构建的 jar
cd E:\Desktop\legado\legado-server
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk21.0.11_10"
.\gradlew :server:fatJar --console=plain

# 独立目录验收（不影响 8080 的生产实例）
$dir = "E:\Desktop\legado\_artifacts\accept-mysql"
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

## 2. 改成 MySQL 并第二次启动（自动建库 + 建表）

编辑 `$dir\config\config.yaml`：

```yaml
server:
  port: 8252
data:
  dir: <$dir>\data
database:
  type: mysql
  mysql:
    host: 192.168.1.68
    port: 3306
    database: legado
    user: root
    password: "w71251478"
auth:
  password: "accept-pass"
  secure_cookies: false
```

```powershell
cd $dir
& "$env:JAVA_HOME\bin\java.exe" -jar .\legado-server.jar     # 前台看日志，Ctrl+C 结束；
# 或后台常驻：Start-Process 用 run-jar 的方式（本目录直接 java -jar 也行）
```

✅ 判据（日志）：
- `[db] 数据库类型：mysql；目标：jdbc:mysql://192.168.1.68:3306/legado…（user=root）`
- 库不存在时：`[db] 目标库 legado 不存在 → 已自动创建（utf8mb4）`
- `[db] 建表完成：22 张表 / 23 个索引`
- `Application started` + `Responding at http://127.0.0.1:8252`

✅ 判据（真库）：

```powershell
# 任选一种：MySQL 客户端 / JDBC 探针
# mysql -h192.168.1.68 -uroot -pw71251478 -e "show tables from legado"
# 期望 22 张表：app_setting … illustration_cache
```

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
在网页里（`http://127.0.0.1:8252`）登录后**加一本书 / 加一条书签**，刷新仍在 ⇒ 说明真落 MySQL。

## 4. 重启幂等

再启动一次，日志应为：

```
[db] 已连接目标库：legado（mysql）
[db] 建表完成：22 张表 / 0 个索引（已存在索引跳过 23 个）
```

✅ 判据：不重复建表、不报「已存在」，数据仍在。

## 5. PostgreSQL（当前被密码挡住）

同样把 `database.type` 改 `postgres`、填对 host/port/库名/user/password：

- 密码正确时：`[db] 目标库 legado 不存在 → 已自动创建` → 建表 22 张 → 正常启动；
- 密码错误时（当前 192.168.1.68 就是这种）：`[db] 自动建库失败（库=legado）：FATAL: password authentication failed for user "postgres"`，进程非 0 退出（**不会**假装成功）。

## 6. 回滚 / 回到 SQLite

把 `database.type` 改回 `sqlite`（或整行删掉）即可，SQLite 文件仍是 `data.dir` 下的 `legado.sqlite`；
也可以在启动时用环境变量临时覆盖而不改文件：

```powershell
$env:LEGADO_DB_TYPE = "sqlite"; & "$env:JAVA_HOME\bin\java.exe" -jar .\legado-server.jar
```

## 已知边界

- **MySQL 主键列 255 字符**：超长 URL（`data:;base64` 聚合源）在 STRICT 模式会写失败，需改「URL 哈希做键」（独立任务）；
- **PG 的 `book_group.name` unique 大小写敏感**（应用层仍有 lower() 重名拦截）。
