# ACCEPT-037 `config/config.yaml` 配置方式验收

**适用版本**：本 PR（`feat/config-yaml`：`config/config.yaml` 配置方式）
**一句话**：把 jar 放好 → 第一次启动自动生成带注释的 `config/config.yaml` → 按注释改 → 重启生效；环境变量仍然优先。

---

## 0. 准备

```powershell
# 1) 目录（任意位置，示例用仓库根的 _cfg-accept）
$dir = "E:\Desktop\legado\_cfg-accept"
Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $dir | Out-Null

# 2) 把本 PR 打出来的 fat jar 放进去
Copy-Item E:\Desktop\legado\legado-server.jar "$dir\legado-server.jar"
```

> 本 PR 的 jar 由 `.\gradlew :server:fatJar` 产出，位于
> `legado-server\server\build\libs\legado-server-*-all.jar`。

## 1. 首次启动 → 自动生成默认配置

```powershell
cd $dir
$env:ADMIN_PASSWORD = "accept-pass"
$env:LEGADO_SECURE_COOKIES = "false"
$env:LEGADO_PORT = "8080"
& "C:\Program Files\Amazon Corretto\jdk21.0.11_10\bin\java.exe" -jar .\legado-server.jar
```

✅ 判据：
- 目录下出现 **`config\config.yaml`**（`data\` 也会自动建），且文件里**全是中文注释**；
- 控制台出现 `[config] 已生成默认配置文件：…\config\config.yaml（按注释修改后重启生效）`；
- 浏览器打开 `http://127.0.0.1:8080` 能登录（密码 `accept-pass`）。

## 2. 改文件 → 配置生效

1. 关掉进程；
2. 打开 `config\config.yaml`，改三处：

```yaml
server:
  port: 8123
auth:
  password: "cfg-pass-123"
  secure_cookies: false
```

3. **不要再设任何 `LEGADO_*` / `ADMIN_PASSWORD` 环境变量**，重新启动：

```powershell
cd $dir
Remove-Item Env:\ADMIN_PASSWORD, Env:\LEGADO_SECURE_COOKIES, Env:\LEGADO_PORT -ErrorAction SilentlyContinue
& "C:\Program Files\Amazon Corretto\jdk21.0.11_10\bin\java.exe" -jar .\legado-server.jar
```

✅ 判据：
- 控制台出现 `[config] 已加载配置文件：…（共 N 项；环境变量优先）`；
- 打开 **`http://127.0.0.1:8123`** 正常，`http://127.0.0.1:8080` 打不开；
- 用 `cfg-pass-123` 能登录（**数据已经初始化过时**，密码沿用数据库里的，不会改；要验证密码项请用**全新目录**）。

## 3. 环境变量优先（临时覆盖）

```powershell
cd $dir
$env:LEGADO_PORT = "8124"   # 文件里写的是 8123
& "C:\Program Files\Amazon Corretto\jdk21.0.11_10\bin\java.exe" -jar .\legado-server.jar
```

✅ 判据：监听 **8124**（`http://127.0.0.1:8124/healthz` 返回 `ok`），而 8123 关闭。

## 4. 启动脚本（可选，本机才有）

```powershell
# 首次（还没有 config.yaml）：脚本会把参数导出成 env，生成的配置会「记住」这些值
cd E:\Desktop\legado
.\run-jar.ps1 -Port 8125 -DataDir _cfg-run

# 再次（已有 config.yaml）：不带参数，端口应从文件读到 8125
.\run-jar.ps1
```

✅ 判据：第二次启动的日志里出现 `CONF: …\config\config.yaml  (edit this file instead of env vars)`，
且 `http://127.0.0.1:8125/healthz` 返回 200。

## 5. 自动化（可选，30 秒）

```powershell
cd E:\Desktop\legado\legado-server
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk21.0.11_10"
.\gradlew :server:test --tests "io.legado.server.ConfigFileTest" --console=plain
```

✅ 判据：`6 tests completed, 0 failed`。

## 6. 首次启动只生成配置（2026-11-14 起）

1. 换一个**全新空目录**，只放 jar；
2. 直接 `java -jar` → 控制台打印「已生成配置文件…**本次不启动服务**」，进程**退出**，
   `http://127.0.0.1:<端口>` 打不开（这是预期）；
3. 按注释改好端口/数据库/密码；
4. **再启动一次** → 这次才真正监听端口，`/healthz` 返回 ok。

✅ 判据：第一次启动**不监听端口**、只生成 `config/config.yaml`；第二次启动才对外服务。

## 7. 数据库类型（当前只有 sqlite 可用）

`config/config.yaml` 里：

```yaml
database:
  type: sqlite      # sqlite / mysql / postgres
```

- `type: sqlite`（默认）：用 `data.dir` 下的 `legado.sqlite`；
- 改成 `type: mysql` 或 `postgres` 后启动 → 会打印**目标连接串/账号**，并明确提示
  「暂未支持（当前版本仅 sqlite）」，然后退出（不会连错库、也不会静默跑 SQLite）。

✅ 判据：报错里能看到你配置的 host/port/库名与 user，且进程不监听端口。

## 回滚

`config/` 目录可以整目录删掉（下次启动会重新生成默认模板）；
想回到「只用环境变量」的老习惯：不建 `config/config.yaml` 即可（或删掉它）。
---
