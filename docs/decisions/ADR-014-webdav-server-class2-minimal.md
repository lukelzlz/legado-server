---
id: ADR-014
title: 进程内 WebDAV 服务端选型、Basic 鉴权与最小 Class 2 锁实现
status: accepted
date: 2026-09-17
---

# ADR-014: 进程内 WebDAV 服务端选型、Basic 鉴权与最小 Class 2 锁实现

## 1. 决策背景 (Context)

PROPOSAL-014 要求在既有 Ktor 服务端进程内提供 WebDAV 能力，并把上传数据落在数据目录下的 `webdav/` 文件夹。围绕「用什么实现、怎么鉴权、锁怎么做、路径怎么解析」有若干互斥方案，需一次性裁定，避免后续反复重构。

约束条件：
- 服务端为纯 JVM + Ktor（CIO 引擎），无 Servlet 容器，不得引入 Android 依赖（AGENTS.md §1）。
- 客户端生态极其挑剔：Windows WebDAV 重定向器、macOS Finder、Legado App（Sardine 客户端）对响应头与状态码极为敏感。
- 工程原则要求「选择能够完全满足当前需求的最简单实现」，警惕抽象层与胶水层。

---

## 2. 裁定方案 (Decision)

### 2.1 实现方式：自研最小 WebDAV 处理器（不引入 Servlet 型 WebDAV 库）
- **决策**：在 `WebDavServer.kt` 中直接用 Ktor 路由实现 Class 1 + 最小 Class 2 的 WebDAV 方法集（`OPTIONS / PROPFIND / PROPPATCH / GET / HEAD / PUT / DELETE / MKCOL / COPY / MOVE / LOCK / UNLOCK`）。
- **理由**：主流 Java WebDAV 实现（Milton、Jackrabbit WebDAV、nginx dav）均依赖 Servlet 容器或作为独立模块运行，与 Ktor CIO 组合需要额外适配层；而本需求只涉及文件系统读写与少量 XML，自研主体约 550 行即可完整覆盖，无胶水层成本。

### 2.2 挂载路径与目录约定
1. 路由同时注册 `/webdav` 与 `/webdav/{path...}`，两者共用同一组处理器，根路径与子路径行为一致。
2. `ServerConfig` 新增 `webDavDirectory`：默认 `LEGADO_DATA_DIR/webdav`，启动时由 `WebDavStorage` 创建目录（`Files.createDirectories`）。
3. **相对路径一律由原始请求 URI 推导**：`request.path().removePrefix("/webdav").decodeURLPart().trim('/')`，而不是使用路由 `{path...}` 参数。
   - **原因**：Ktor 3.4 的尾卡（tailcard）参数在 `call.parameters` 中**只捕获首段**（实测 `/webdav/a/b` 得到 `a`），直接使用会导致多级路径被静默截断为单级路径（本项目实测一度表现为「PUT 多级路径返回 405」）。该实现细节已同步写入 AGENTS.md 部落知识库。

### 2.3 鉴权：HTTP Basic + 管理员密码 + 短时校验缓存
1. WebDAV 客户端普遍只支持 HTTP Basic，故 `AuthService` 新增 `verifyBasicAuthorization(header)`：**用户名任意**（便于各类客户端填写账号），密码必须是管理员密码（复用 PBKDF2 校验与 `app_user` 表）。
2. 校验成功的 `Authorization` 头在内存中缓存 5 分钟（上限 32 条），避免浏览器/资源管理器每个文件操作都触发一次 PBKDF2 计算。密码重置后缓存最长 5 分钟自然失效。
3. 不引入第二套账号体系：不新增用户表、不加目录级权限。

### 2.4 锁：登记式最小 Class 2（不做强制互斥）
1. `LOCK` 返回合规的 `Lock-Token` 响应头与 `lockdiscovery` XML，令牌格式为 `opaquelocktoken:<uuid>`；非空 body 建新锁，空 body 视为 RFC 4918 续租。
2. `UNLOCK` 令牌不匹配返回 `409 Conflict`；锁可以落在尚不存在的资源上（创建空文件并返回 `201`），以兼容 Windows「新建文件」流程。
3. **写入操作不强制校验锁**：`PUT / DELETE / MOVE / COPY` 不因锁存在而返回 `423 Locked`。单用户私有服务下强制互斥的价值低于兼容性价值——客户端异常退出遗留的锁会让后续写入全部失败，而 nginx dav 等主流最小实现同样不强制锁。

### 2.5 语义取舍
| 场景 | 决策 | 依据 |
| --- | --- | --- |
| `PROPFIND` 无 `Depth` 头 | 视为 `infinity`（目录返回 `403 propfind-finite-depth`） | RFC 4918 §9.1；与 nginx dav 一致，避免无界递归扫描 |
| 目录集合属性 | 不返回 `getcontentlength`，`getcontenttype` 为 `httpd/unix-directory` | Windows 资源管理器按 `resourcetype` 判定目录 |
| `getlastmodified` / `creationdate` | 分别输出 RFC 1123 与 ISO 8601 | 客户端解析兼容性 |
| `PROPPATCH` | 统一返回 `207` + `403` propstat | 不支持死属性，回复失败优于 500/404 |
| `GET` 目录 | 返回极简 HTML 目录清单 | 便于浏览器自查，不影响 WebDAV 客户端 |
| `GET` 文件 | 支持单段 `Range`（`206` / `Content-Range` / `Accept-Ranges`），越界 `416` | 断点续传与大文件/媒体预览 |
| `PUT` | 先写同目录 `.webdav-upload-*.part` 临时文件再 `ATOMIC_MOVE` 落盘 | 避免半截文件被阅读器读到；自动补建父目录 |
| 根目录 | `PUT / DELETE / MKCOL / MOVE / COPY` 作用于 `/webdav` 根时返回 `403` | 防止误删整个存储区 |
| `Destination` 越界 | 非本服务 `/webdav` 前缀返回 `502 Bad Gateway`，源目标相同或目标位于源内部返回 `403` | RFC 4918 §9.8/§9.9；自包含复制会无限递归 |

### 2.6 Web 设置页面与浏览器侧鉴权
1. **一级导航入口**：Web 端新增 `#webdav` 页面（导航标签「文件」），与书源、订阅、规则同级，作为 WebDAV 的统一下载/上传与状态入口。
2. **页面数据来源**：新增 `GET /api/webdav/info?path=<相对路径>`，一次返回服务状态（`/webdav` 路径、磁盘目录、文件数、目录数、总占用）与指定目录的条目列表（目录优先、名称排序）。相比让前端解析 `PROPFIND` 的 XML，JSON 接口更贴近 Web 端既有约定，也避免维护第二套 XML 解析。
3. **浏览器侧写操作复用 WebDAV 协议本身**：设置页的上传 / 新建文件夹 / 删除直接调用 `PUT` / `MKCOL` / `DELETE /webdav/...`，不另建平行的 JSON 写接口 —— 保证「页面写入」与「客户端写入」走完全相同的落盘代码路径。
4. **鉴权双轨制**：WebDAV 端点同时接受
   - **HTTP Basic**（外部客户端：任意用户名 + 管理员密码，无需 CSRF，因为浏览器不会自动附带 Basic 凭据，天然免疫 CSRF）；
   - **会话 Cookie**（Web 设置页：读操作直接放行；`PUT/DELETE/MKCOL/MOVE/COPY/LOCK/PROPPATCH` 等写操作必须携带 `X-CSRF-Token`，缺失或错误返回 `403`）。
   这样浏览器无需在页面内保存或输入密码，同时杜绝跨站伪造写入；会话 Cookie 本身为 `SameSite=Strict`，构成第二层防线。
5. **文件统计排除临时分片**：`webdav` 目录的占用统计忽略 `.webdav-upload-*.part` 中间文件，避免上传过程中的半成品污染统计数字。

---

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：引入 Milton / Jackrabbit WebDAV 等服务端库**
  - *否决理由*：均面向 Servlet 生态，需在 Ktor 中模拟 Servlet 上下文（额外依赖 + 适配层 + 生命周期错位风险），违背「严禁为单一补丁增加胶水层」的工程原则。
- **备选方案 B：只提供自研 REST 文件上传接口（不实现 WebDAV 协议）**
  - *否决理由*：操作系统文件管理器、RaiDrive、rclone、Legado App WebDAV 备份均无法直接对接，用户仍需自行写脚本，未解决「即插即用」的核心痛点。
- **备选方案 C：把数据存进 SQLite（BLOB）而非独立文件夹**
  - *否决理由*：违背「数据目录下新建 webdav 文件夹存上传的数据」的明确要求；大文件经 SQLite 中转既拖慢数据库又难以被外部工具直接读取与备份。
- **备选方案 D：WebDAV 使用独立的第二密码 / 独立端口**
  - *否决理由*：增加配置面与遗忘密码的运维负担；管理员密码 + Basic 已满足单用户场景，且复用既有 PBKDF2 与重置密码 CLI。
- **备选方案 E：设置页面另建一套 JSON 上传 / 删除 / 列目录 REST 接口**
  - *否决理由*：与 WebDAV 协议能力完全重叠，会形成两条落盘代码路径（一致性风险、测试面翻倍）；改用「JSON 只读状态接口 + 复用 WebDAV 写方法」的组合，既满足 React 渲染需要，又保证落盘逻辑唯一。
- **备选方案 F：设置页面用 Basic 凭据直接请求，或在页面内让用户再次输入密码**
  - *否决理由*：把管理员密码暴露在 JS 内存与请求头里，且与「已登录会话」重复；改用会话 + CSRF 双轨鉴权后，浏览器侧完全不需要接触密码。

---

## 4. 后果与权衡 (Consequences & Trade-offs)

- **正面收益**：
  - 服务端一次性获得操作系统级文件接入能力，挂载即用，无需额外组件与端口。
  - 上传数据天然落在 `LEGADO_DATA_DIR/webdav`，随数据卷统一持久化、备份与迁移。
  - 鉴权、密码重置、HTTPS 反代等既有能力全部无缝复用。
  - Web 端「文件」页面把「这个地址填什么、怎么连、存了多少、怎么删」四件事收敛到一个入口，无需记忆协议细节。
- **代价与风险**：
  - 自研协议处理器需自行维护；已在 `WebDavRoutesTest` 中固化 8 组端到端用例（协议头、鉴权、读写闭环、Range、锁、越界拒绝、状态接口、会话+CSRF 写入）。
  - 锁不强制 → 多客户端并发写同一文件时可能互相覆盖；单用户私有服务下可接受。
  - Basic 凭据等同于管理员密码明文传输（Base64 非加密）→ **公网部署必须走 HTTPS 反向代理**；同时建议在反代层为 `/webdav` 单独限流。
  - 会话鉴权开放了 WebDAV 写入 → 依赖 CSRF 头校验与 `SameSite=Strict` Cookie 双重防护，安全边界与既有 `/api` 写接口一致。
  - 存储占用统计为全量递归扫描 → 文件量极大时状态接口耗时会线性上升（单用户场景可忽略）。
- **后续可扩展点**：如未来需要多用户/只读子账号，可在 `AuthService` 增加凭据表，而不必改动 WebDAV 路由与存储层。
