# 历史归档：Windows 环境搭建、测试基线方法论与 HTTP/Secure-Cookie 实测

> **来源会话**：`session-09fe8981-c5e7-4d21-b148-a15a886e7785`（2026-09-14 ~ 09-16，主会话）与本次 `SESSION-016` 的复现
> **核心价值**：本项目在 **Windows** 上跑测试/联调时反复遇到同一批「看起来像回归、其实与改动无关」的失败；本归档给出**根因**与**可复制的判定方法**，避免每次改动都被 52 个红叉误导。

---

## 1. Windows 本机环境基线

| 项 | 结论 |
| --- | --- |
| 包管理 | 机器原本没有 Git，使用 `winget install --id Git.Git -e --source winget --accept-package-agreements --accept-source-agreements --disable-interactivity` 安装 |
| JDK | 曾装 JDK 25 后改为 **Amazon Corretto 21**：`C:\Program Files\Amazon Corretto\jdk21.0.12_9`；`JAVA_HOME` 未持久化时，命令需显式前缀 `$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.12_9"` |
| 免构建运行 | 官方 Release 的 **fat JAR 可直接运行**（前端与依赖已内嵌，只需 Java 17+）；`gradlew` 仅用于「验证源码可编译」 |
| 启动方式 | 用户**明确要求不要静默启动**：用 `start-legado.cmd` 弹可见 cmd 窗口（标题如 `Legado Server 8080`），便于实时看 Ktor 日志 |
| npm | 前端依赖安装需注意 `esbuild` 的 `postinstall` 被拦截（安装时用 `--ignore-scripts`），装完仍要 `npm run build` 验证产物 |
| Gradle 下载 | `services.gradle.org` 曾慢到 ~2KB/s：**用 GitHub 的 gradle-distributions 源手动喂 wrapper 缓存**，绝不修改仓库里的 `gradle-wrapper.properties`（CI 必须保持官方源） |
| 控制台编码 | Windows PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会写 **BOM**，会破坏 `plugin.json` 等清单解析；写 JSON/配置优先用 `[System.IO.File]::WriteAllText(..., UTF8Encoding($false))` |
| 中文输出 | 服务端返回体若省略 `charset`，PowerShell 会按 Latin-1 解码导致中文乱码；自研接口应显式 `charset=utf-8` |

---

## 2. 金标准：52 个既有测试失败的真实根因

- **现象**：`./gradlew :server:test` 在本机长期呈现 **52 个失败**（154 用例时 52 失败；加入新用例后 160/162 用例仍是 52 失败），异常统一为：
  ```
  java.nio.file.FileSystemException: ...\Temp\*.sqlite: 另一个程序正在使用此文件，进程无法访问。
  ```
- **根因**：这些测试**从不调用 `database.close()`**，而是在 `finally` 里直接 `Files.deleteIfExists(sqlite 文件)`；Windows 不允许删除仍被占用（且 WAL 还有 `-wal`/`-shm`）的文件，而 **macOS 的 POSIX 语义允许删除已打开的文件**，所以项目原始开发环境永远不会暴露。
- **判定方法论（必须照做）**：
  1. 起一个**干净基线 worktree**（改动前的 HEAD）跑同一套测试；
  2. 对比**失败集合**而非只看失败数量：基线 154 用例 / 52 失败 vs 带新功能 160 用例（含 6~8 个新增用例）/ 同样 52 失败且**集合完全一致** ⇒ 零回归；
  3. 结论写入会话/文档，避免下一次又把平台问题误判为自己的回归。
- **次级影响**：任何新增的数据库相关测试只要照抄这个 `finally` 清理模式，也会"继承"这 52 个失败中的一员；新测试最好显式 `close()` 或在清理上做尽力而为处理。
- **复现证据**：2026-09-17 的 WebDAV 会话再次独立踩到同一现象（162 用例 / 52 失败，与基线完全一致），详见 [`SESSION-016`](./SESSION-016-webdav-storage-server.md)。

---

## 3. HTTP 明文访问与 `LEGADO_SECURE_COOKIES` 实测

- **误区**：以为「用明文 HTTP 访问会被服务端拒绝」。**实测结论：服务端不拦 HTTP**，默认配置（`LEGADO_SECURE_COOKIES` 未设置，即 `true`）下：
  ```
  POST /api/auth/login  -> 200 OK
  Set-Cookie: legado_session=...; Max-Age=604800; Path=/; Secure; HttpOnly; SameSite=Strict
  GET /index.html       -> 200
  GET /healthz          -> 200
  ```
- **真正的拦截方是浏览器 Cookie 策略**：带 `Secure` 的 Cookie 在 `http://` 下不会被浏览器回传 ⇒ 表现为「登录成功但下一秒又是未登录」。PowerShell 的 `Invoke-WebRequest` 会话同样会因此登不上。
- **正确做法**：
  - 局域网 / 明文 HTTP 自建场景：`LEGADO_SECURE_COOKIES=false`（本项目 `start.bat` / `start.sh` / 验收手册都已默认设为 `false`）；
  - 公网：走 HTTPS 反向代理并保持 `true`；
  - 命令行验证接口时可改用 `curl`（不受浏览器 Cookie 策略影响）来绕开该干扰。

---

## 4. 历史阻塞与最终通路（PR / 凭据）

- **当时的阻塞**（2026-09-16）：无 `gh` CLI、无 SSH key、无 `GITHUB_TOKEN`；git `user.name/user.email` 为空（连 commit 都无法署名）；`git ls-remote/fetch` 到 `github.com` 连接重置（而 `curl` 打 `api.github.com` 却 200）。最终以 **导出 patch（45 文件 / 1.4MB，含 `web/dist`）** 兜底。
- **现在已解决的通路**（2026-09-17 实测）：
  1. 凭据：Windows 凭据管理器（GCM）中已存 GitHub 账号 **`wfanan`** 的凭据；`git credential fill` 可取到（**切勿把 token 打印/落盘**）；
  2. 权限：`wfanan` 对 `lukelzlz/legado-server` **无写权限**（直接 push 403），必须先推 **fork**：`github.com/wfanan/wfanan`（即被重命名的 fork，parent 指向 `lukelzlz/legado-server`）；
  3. 建 PR：API `POST /repos/lukelzlz/legado-server/pulls`，`head="wfanan:<branch>"`；请求体必须用 **UTF-8 无 BOM** 写文件后 `curl --data-binary @file`（用 PowerShell `Get-Content -Raw` 读 UTF-8 文件会按 ANSI 解码成乱码，导致 `422 Invalid request`）；
  4. 提交前必扫密钥：`git grep -n -I "<secret>"` 至少覆盖已暂存内容（本项目曾把部署密码写进文档与测试脚本，已在提交前替换为占位符）。
- **仓库约定**：`web/dist` 是**被跟踪的构建产物**，功能提交需连同重新构建的 dist 一起提交（历史提交 `0c9d4eb`、`cc6a9b5` 均如此）。

---

## 5. 沉淀到部落知识的条目

1. 52 个服务端测试失败 = Windows 删除已占用 SQLite 文件，属**既有平台问题**；判定必须用「干净基线 worktree + 失败集合比对」。
2. 明文 HTTP 部署必须显式 `LEGADO_SECURE_COOKIES=false`，拦截者是浏览器而非服务端。
3. Windows 下写配置/JSON 用无 BOM UTF-8；自研接口返回体补 `charset=utf-8`。
4. Gradle wrapper 下载慢时喂缓存，**不改仓库 distributionUrl**。
5. 无写权限时的 PR 通路：fork + API 建 PR，凭据只走 GCM，不落盘不打印。
