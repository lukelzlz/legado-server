# SESSION-057 配置方式改为 `config/config.yaml`

- 日期：2026-11-13
- 分支：`feat/config-yaml`（基于 main；独立主题，单独 PR）
- 关联：`ConfigFile.kt`（新增）、`ServerConfig.kt`、`Application.kt`、`server/build.gradle`

## 用户原话

> 「修改配置设置方式改为在jar运行文件夹生成config文件夹，里面存放config.yaml配置文件，
> 你先添加这个功能，开始前先写一个默认配置，包含文字注释，然后我再做修改形成模板」

## 之前是什么样

所有参数只有环境变量一条路（`LEGADO_PORT` / `ADMIN_PASSWORD` / `LEGADO_SECURE_COOKIES` / 各项缓存配额…）。
本地跑 jar 的人每次都要记一串 env；而容器部署又必须依赖 env —— 两者都不方便「让用户改一个文件」。

## 现在怎么做

1. 启动时在 **jar 所在目录**下找 `config/config.yaml`（`ConfigFile.jarDirectory()` 取自身 codeSource 位置，
   IDE/测试里回落到工作目录）；
2. **文件不存在就生成一份带中文注释的默认配置**，然后照默认值继续启动（不是报错退出）；
3. 优先级：**环境变量 > config.yaml > 内置默认值**。空串/留空视为「没配」，继续往下一层回落；
4. YAML 写坏了**绝不让服务起不来**：打印告警后回落 env/默认值。

### 关键设计：生成的模板要等于「实际生效值」

首次启动若带了环境变量（启动脚本会导出 `LEGADO_DATA_DIR` / `LEGADO_PORT` / `ADMIN_PASSWORD`），
生成的 `config.yaml` 会把**那些值**写进去（`ServerConfig.effectiveValues()` 反向渲染）。
否则会踩这个坑：第一次跑（脚本 export env）用 `_run-data` + 8080；第二次脚本不再 export（因为文件已存在）
就变成 `./data` + 8080，用户的库「凭空消失」。

### 默认模板（摘要，实际带全量中文注释）

```yaml
server:
  host: 0.0.0.0
  port: 8080
data:
  dir: ./data
  database:
auth:
  password: ""
  secure_cookies: true
cache:
  manga: { max_gb: 10, max_global_fetches: 6, prefetch: true, gc: true }
  audio: { max_gb: 20, max_global_fetches: 3, prefetch: true, gc: true }
cover:
  self_heal: true
```

注释里标了每项等价的环境变量名，方便与容器部署对照。

## 实现

- `org.yaml:snakeyaml:2.3` 解析（用户会手改文件，注释/嵌套/引号都必须支持）；
- `ConfigFile.flatten()` 把嵌套压成 `server.port` 这种扁平键，再交给 `ServerConfig.fromSources(fileValues, env)`；
- `ServerConfig.fromEnvironment()` **保留**为「只读 env」入口（测试与 `reset-password` 用它，
  避免「机器上恰好有 config.yaml」干扰它们读到的库/密码）；
- `main()` 改用 `ServerConfig.load()`，并在日志里打印「已生成/已加载 配置文件路径」与项数；
- 本地启动脚本 `run-jar.ps1` / `start-server.ps1`：**config.yaml 存在时不再导出 env**
  （否则 env 永远压过文件，文件形同废纸）；端口从文件里读，`-Port` / `-DataDir` / `-Password` 显式传时才作为 env 覆盖。

## 验证（真跑）

| 项 | 结果 |
| --- | --- |
| `ConfigFileTest`（新增） | 6/6：生成带注释模板、不覆盖用户改动、文件项生效、env 优先、坏 YAML 回落、留空等于没配 |
| 全新目录跑 jar | 自动生成 `config/config.yaml`，日志 `[config] 已生成默认配置文件` |
| 改文件（port=8123、password=cfg-pass-123） | 服务监听 **8123**，`POST /api/auth/login` 用文件里的密码 **登录成功** |
| 再带 `LEGADO_PORT=8124` 启动 | 监听 **8124**，8123 已关闭 ⇒ env 优先 |
| 用启动脚本首次运行 `-Port 8125` | 生成配置文件（port=8125、data.dir 为绝对路径、secure_cookies=false），服务在 8125 起来 |
| 用启动脚本**不带参数**再次运行 | 从 config.yaml 读到 8125 并正常启动（healthz 200） |

## 已知边界

- 配置文件**不支持热加载**（改完重启生效）——语义简单、也避免半改状态；
- `config.yaml` 不是备份/迁移对象：它只决定「进程怎么跑」，数据仍在 `data.dir` 里。
