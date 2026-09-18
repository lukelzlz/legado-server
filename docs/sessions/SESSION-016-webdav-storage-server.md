# 工作记忆归档：内置 WebDAV 服务端与数据目录 webdav 存储区 (SESSION-016)

> **关联提案**：[`docs/proposals/PROPOSAL-015-webdav-storage-server.md`](file:///root/legado-server/docs/proposals/PROPOSAL-015-webdav-storage-server.md)  
> **关联架构决策**：[`docs/decisions/ADR-015-webdav-server-class2-minimal.md`](file:///root/legado-server/docs/decisions/ADR-015-webdav-server-class2-minimal.md)  
> **关联验收手册**：[`docs/acceptance/ACCEPT-015-webdav-storage.md`](file:///root/legado-server/docs/acceptance/ACCEPT-015-webdav-storage.md)

---

## 1. 核心需求与背景

用户提出：**「添加 webdav 服务端功能，在数据目录下新建 webdav 文件夹存上传的数据」**。拆解为三条硬约束：

1. 服务端进程内提供 WebDAV 协议能力（不是 WebDAV 客户端、不是第三方外挂进程）。
2. 数据目录（`LEGADO_DATA_DIR`，Docker 中为 `/data`）下新建 `webdav/` 文件夹，作为客户端上传数据的落盘根目录。
3. 上传的数据原样存文件，便于随数据卷统一备份与迁移。

---

## 2. 关键架构设计与踩坑解决

### 1. Ktor 尾卡参数只捕获首段（本次最大坑）
- **问题**：最初把相对路径写成 `call.parameters["path"]`（配合 `route("/webdav/{path...}")`），单级路径正常，但多级路径被静默截断：`/webdav/a/b` 解析出的 `path` 竟然是 `a`，导致 `PUT /webdav/x/y/z.txt` 与 `MKCOL /webdav/x/y` 全部落到已存在的单级目录上并返回 `405 Method Not Allowed`（PUT 分支判定目标是目录、MKCOL 分支判定目标已存在）。
- **定位过程**：写临时探针测试打印 `request.uri`、`parameters["path"]` 与落盘目录树，确认 URI 完整而参数只有首段；再用最小路由应用复现（`/dav/a` → `a`，`/dav/a/b` → `a`）。
- **解决**：路径一律从原始请求 URI 推导，彻底不依赖尾卡参数：
  ```kotlin
  private fun ApplicationCall.davPath(): String = runCatching {
      request.path().removePrefix("/webdav").decodeURLPart()
  }.getOrDefault("").trim('/')
  ```
  同时保留 `/webdav` 与 `/webdav/{path...}` 两条注册（Ktor 中尾卡不匹配空尾段，根路径需要独立注册）。

### 2. Windows / macOS 客户端兼容性关键响应
- `OPTIONS` 必须返回 `DAV: 1, 2`、`Allow`（含全部方法）与 **`MS-Author-Via: DAV`**，否则 Windows WebDAV 重定向器按只读处理。
- `LOCK` 必须能对「尚不存在的文件」加锁并返回 `201` + `Lock-Token`，否则资源管理器新建文件流程失败。
- 目录属性用 `<D:resourcetype><D:collection/></D:resourcetype>` 表达，且目录不返回 `getcontentlength`。

### 3. 锁只登记、不强制互斥
- **问题**：若按 RFC 严格在 `PUT/DELETE` 时校验锁令牌并返回 `423 Locked`，客户端异常退出遗留的锁会让后续所有写入失败，单用户场景收益为负。
- **解决**：`LOCK` 返回合规令牌与 `lockdiscovery`，`UNLOCK` 校验令牌（不匹配 `409`），但写入操作不做锁校验（advisory 语义），与 nginx dav 模块行为一致。

### 4. 上传落盘的原子性与断点续传
- **PUT**：先写同目录 `.webdav-upload-*.part` 临时文件，`inputStream.copyTo` 完成后 `ATOMIC_MOVE + REPLACE_EXISTING` 落盘，避免网络中断产生半截文件被阅读器读到；同时自动补建父目录，兼容不做 `MKCOL` 的客户端。
- **GET**：手写单段 `Range` 解析（返回 `Satisfiable`/`Unsatisfiable`/整文件三种结果），用 `respondBytesWriter(contentType, status, contentLength)` 从 `FileChannel` 指定偏移流式下发，支持 `206` 与 `416`，无需为 Range 引入额外插件。

### 5. 鉴权复用管理员密码 + PBKDF2 缓存
- WebDAV 客户端只支持 HTTP Basic，`AuthService.verifyBasicAuthorization` 允许**任意用户名**、密码必须是管理员密码。
- PBKDF2 单次校验约百毫秒量级，而资源管理器打开一个目录会连发多个请求，因此把校验成功的 `Authorization` 头在内存中缓存 5 分钟（上限 32 条），密码重置后最长 5 分钟自然失效。

### 6. 越界路径三重拒绝
- URL 路径按 `/` 切段后拒绝 `..`、反斜杠与空字节，再 `normalize()` 并校验 `startsWith(root)`；`/webdav` 根目录禁止 `PUT/DELETE/MKCOL/MOVE/COPY`（返回 `403`），防止误删整个存储区。

### 7. Web 设置页面：状态 + 指引 + 文件管理三合一
- **需求追加**：用户在服务端能力落地后追加「添加 webdav 设置页面」。
- **入口**：Web 端一级导航新增第六个标签 **文件**（`#webdav`），同时在右上角菜单加入「WebDAV 文件服务」入口；`main.tsx` 的 `Page` 联合类型、`pageFromHash`、页面分支同步扩展。
- **页面结构**：① 状态卡（运行中 / 访问地址可复制 / HTTP Basic 说明 / 已存文件数与占用）；② 四类客户端接入指引（Windows、macOS、rclone、Legado App，均可一键复制并自动带上当前访问域名）；③ 文件管理（鼠标点选上传、多选、新建文件夹、面包屑进入子目录、下载、删除）；④ HTTPS 与 `client_max_body_size` 安全提示。
- **数据来源**：新增 `GET /api/webdav/info?path=` 返回 JSON（服务状态 + 当前目录条目），避免前端解析 `PROPFIND` XML；条目排序为「目录优先 + 名称升序」，占用统计跳过 `.webdav-upload-*.part` 临时分片。
- **写操作复用协议本身**：页面内上传/建目录/删除直接调用 `PUT` / `MKCOL` / `DELETE /webdav/...`，与外部客户端走同一条落盘代码路径，不新增平行 REST 写接口。
- **浏览器侧鉴权（关键设计）**：WebDAV 端点由「仅 Basic」升级为「Basic 或 会话 Cookie」双轨：
  - 外部客户端继续用 Basic（任意用户名 + 管理员密码），并保留 5 分钟校验缓存；
  - 浏览器会话鉴权的**读**操作（浏览/下载）直接放行，**写**操作必须携带 `X-CSRF-Token`，缺失或错误返回 `403`，配合 `SameSite=Strict` Cookie 双重防 CSRF；
  - 好处是页面无需保存或再次输入管理员密码。

---

## 3. 验证与通过状态
- **新增用例**：`server/src/test/kotlin/io/legado/server/WebDavRoutesTest.kt`（8 组：协议头与鉴权、读写闭环与 Range、锁生命周期、PROPPATCH 与越界路径、Range 解析边界、状态接口 `/api/webdav/info`、会话+CSRF 写入、目录约定）。
- **前端用例**：`web/test/webdav-settings.test.ts`（7 组：页面静态渲染、导航入口、客户端指引推导、容量/时间格式化、面包屑、路径编码与拼接）。
- **WebDAV 用例**：`./gradlew :server:test --tests "io.legado.server.WebDavRoutesTest"` -> PASS (8/8)。
- **真机浏览器验证**：`web/test/capture-webdav-page.ts`（puppeteer-core + Edge）实测通过 —— 导航高亮「文件」、状态卡显示 `运行中 / http://127.0.0.1:8080/webdav / HTTP Basic / 3 个文件 · 108 B`、四类指引齐全、根目录列出 `backup / books / readme.txt`、进入子目录后面包屑为 `根目录 / backup`、页面内上传后行出现且磁盘落盘、删除后行消失且磁盘同步清理；1440 与 390 视口均 `hasHorizontalOverflow: false`，控制台与页面错误为空。
- **前端检查**：`npm --prefix web run check` PASS（0 错误）；`npx tsx web/test/run-all.ts` PASS（134/134）。
- **服务端全量测试**：`./gradlew :server:test` -> 162 用例，52 失败，失败集合与改动前基线**完全一致**（均为 Windows 环境下测试清理临时 SQLite 文件时 `FileSystemException: 另一个程序正在使用此文件` 的历史遗留问题，与本次改动无关）。
- **打包验证**：`npm --prefix web run build` + `./gradlew :server:fatJar` 后复制到 `reader/legado-server-dist/legado-server.jar`，用 `start.bat`（`ADMIN_PASSWORD='<部署密码>'`）实测：`/healthz` 200、前端 200、密码登录 200、`OPTIONS /webdav` 返回 `DAV: 1, 2`，WebDAV 上传/下载/Range 正常，强杀重启后数据与密码均保留。
- **完成度状态**：`[Tested 单测/检查通过]` + `[Deployed 本地打包联调完成]`（等待用户按 ACCEPT-015 实操验收）。

---

## 4. 后续注意
- 公网部署必须走 HTTPS：Basic 凭据仅 Base64 编码，明文传输等同泄露管理员密码；建议反代层对 `/webdav` 单独限流，并按需调大 `client_max_body_size`。
- 存储占用统计为全量递归扫描，文件数量极大时状态接口耗时会线性上升（单用户场景可忽略）。
- 若后续需要多用户或只读子账号，只需扩展 `AuthService` 凭据表，WebDAV 路由、设置页面与存储层均无需改动。

---

## 5. 追加排障：用户报「没有选项卡」= Service Worker 预缓存导致界面仍是旧版

- **现象**：用户在新版打包并启动后反馈「没有选项卡」（导航上看不到新增的「文件」入口）。
- **定位**：`curl http://127.0.0.1:8080/` 拿到 `assets/index-D4IoN_ao.js` 并抓取该 bundle，确认其中的 `WebDAV 文件服务` / `客户端接入` / `rclone obscure` / `webdav-page` 字符串**全部存在** —— 说明服务端已经在返回新版前端；`index.html` 由 `StaticWeb.kt` 以 `no-store` 下发、静态资源为内容哈希文件名，HTTP 缓存不成立，因此唯一可能就是 **Service Worker 的 precache 仍在提供旧的 `index.html` + 旧 bundle**（`navigateFallback: '/index.html'`，连 `/#webdav` 也会被拦回旧应用壳）。
- **根因（两处缺陷）**：① `PwaManager` 只在**登录后的应用壳内**挂载，未登录时永远不会出现「发现新版本」提示；② SW 注册后没有任何主动更新检查，且当旧 SW 已经停在 waiting 态时不会再触发 `updatefound`，提示彻底丢失。
- **修复**（本次已实现并重新打包）：① `PwaManager` 同时挂载到登录页；② 注册成功后立即判断 `reg.waiting && navigator.serviceWorker.controller` 并弹出更新提示；③ 补一次即时 `reg.update()`，并在 `focus` / `visibilitychange` 与每 5 分钟主动检查更新；④ 清理定时器，避免泄漏。
- **用户侧一次性解法**：清站点缓存 / DevTools → Application → Service Workers → 注销，或直接用无痕窗口、换端口访问；用户确认清缓存后新版界面正常显示。
- **完成度**：`[Accepted 用户确认清缓存后可见]`，加固版 jar 已重新编译（`reader/legado-server-dist/legado-server.jar`），需重启服务端进程生效。
