---
id: PROPOSAL-014
title: 内置 WebDAV 服务端与数据目录 webdav 存储区
status: accepted
author: Agent & User
date: 2026-09-17
---

# PROPOSAL-014: 内置 WebDAV 服务端与数据目录 webdav 存储区

## 1. 业务背景与问题痛点

1. **服务端缺少通用文件存取入口**：
   - 当前 Legado Server 只提供 JSON / WebSocket 形式的管理 API（书源、订阅、替换规则、书架）。用户想把手机里的书源包、备份 JSON、TXT/EPUB 文件、封面素材等放到服务端，只能依赖 SSH/SFTP、Docker volume 挂载或自行搭一套 WebDAV/NAS 服务，门槛高。
   - 服务端已经是「私有化云端书源中心」，但**没有面向文件的原生读写协议**，无法被 Windows 资源管理器、macOS Finder、RaiDrive、rclone、以及 Legado App 自带的 WebDAV 备份客户端直接挂载使用。
2. **缺少统一落盘目录约定**：
   - 数据目录（`LEGADO_DATA_DIR`）下已有 `legado.sqlite`（数据库）与 `covers/`（封面缓存），但没有一个「用户上传数据」的目录约定，第三方客户端写入的内容无处安放，也无法被 Docker 数据卷整卷备份。
3. **协议选型困扰**：
   - 若为文件存取自造一套 REST 上传接口，则每个客户端都需要专门适配；而 WebDAV 是操作系统与备份工具普遍内置的标准协议，挂载即用。

---

## 2. 目标与非目标 (Goals & Non-Goals)

### Goals
1. **内置 WebDAV 服务端**：在服务端进程内实现 WebDAV 协议入口 `/webdav`，无需额外进程、额外端口或额外容器。
2. **数据目录下的独立存储区**：数据目录下新增 `webdav/` 文件夹（`LEGADO_DATA_DIR/webdav`），所有客户端上传的数据原样落盘存放，随 Docker 数据卷一起持久化与备份。
3. **客户端即插即用**：支持 Windows 资源管理器「映射网络驱动器」、macOS Finder「连接服务器」、RaiDrive / Cyberduck / rclone / Legado App WebDAV 客户端等常见客户端读写（浏览、上传、下载、重命名、移动、复制、删除、新建文件夹）。
4. **复用既有鉴权**：沿用管理员密码作为 WebDAV 凭据，不引入第二套账号体系。
5. **安全落盘**：任何越界路径（`..` 穿越、反斜杠、空字节）均在解析阶段被拒绝，客户端无法写到数据目录之外。
6. **Web 端 WebDAV 设置页面（一级导航「文件」）**：
   - 展示服务状态、访问地址（一键复制）、认证方式、数据目录与已存数据量（文件数 / 目录数 / 占用空间）；
   - 内置 Windows / macOS / rclone / Legado App 四类客户端的接入指引与可复制命令；
   - 提供页面内文件管理：目录浏览（面包屑 + 子目录进入）、上传、下载、新建文件夹、删除；
   - 页面内的写操作复用 WebDAV 协议本身（`PUT` / `MKCOL` / `DELETE`），并以「会话 Cookie + CSRF 令牌」鉴权，无需在浏览器里输入密码。

### Non-Goals
- 不实现 WebDAV 的 `REPORT`、`ACL`、`SEARCH`、版本管理（DeltaV）等企业级扩展。
- 不实现死属性（dead properties）持久化：`PROPPATCH` 一律返回失败状态。
- 不做多用户 / 多租户与精细权限（只读子账号、目录级授权）。
- 设置页面不做在线预览、批量移动、拖拽排序等增强交互。

---

## 3. 核心用户故事 (User Stories)

- **Story 1（Windows 挂载投递文件）**：作为 Windows 用户，我在资源管理器里把 `http://<服务器>:8080/webdav` 映射为网络驱动器，输入任意用户名 + 管理员密码，即可像本地文件夹一样拖拽上传文件，文件出现在服务器的数据目录 `webdav/` 下。
- **Story 2（macOS / 第三方客户端）**：作为 macOS 或 RaiDrive / Cyberduck / rclone 用户，我通过「连接服务器」直接挂载该地址，浏览目录树、下载文件、新建文件夹、重命名与删除均正常工作。
- **Story 3（Legado App 备份落盘）**：作为 Legado Android 用户，我把 App 的 WebDAV 备份目标指向本服务端，备份文件被写入数据目录的 `webdav/` 中，随后可随 Docker 数据卷整体备份或迁移。
- **Story 4（浏览器快速自查）**：作为运维者，我用浏览器打开 `/webdav/` 就能看到目录清单与文件大小，便于确认上传结果，无需额外客户端。
- **Story 5（Web 设置页统一管理）**：作为管理员，我在 Web 端一级导航「文件」中就能看到 WebDAV 是否可用、访问地址是什么、已存了多少数据，复制现成的客户端接入命令，并直接在页面上传、建目录、下载与删除文件，不必再开资源管理器或敲 curl。
- **Story 6（移动端随手取文件）**：作为手机用户，我在移动端打开同一个页面即可浏览目录、下载书籍文件并清理不需要的内容。

---

## 4. 验收基准 (Acceptance Criteria)

- [ ] **协议基础**：`OPTIONS /webdav` 返回 `DAV: 1, 2`、`Allow` 与 `MS-Author-Via: DAV`；未认证请求返回 `401` 且带 `WWW-Authenticate: Basic`。
- [ ] **鉴权**：任意用户名 + 管理员密码可读写；密码错误与空密码均被拒绝；浏览器侧会话鉴权的写操作必须携带 CSRF 头（缺失返回 `403`）。
- [ ] **读写闭环**：`MKCOL` 建目录、`PUT` 上传/覆盖、`GET` 下载（含 `Range` 断点续传与 `416` 边界）、`HEAD` 取元信息、`MOVE` / `COPY`（含 `Overwrite` 语义）、`DELETE` 递归删除、`PROPFIND`（`Depth: 0/1`）目录枚举全部可用。
- [ ] **锁**：`LOCK` 返回 `opaquelocktoken` 与 `lockdiscovery`，`UNLOCK` 正确释放，令牌不匹配返回 `409`。
- [ ] **目录约定**：上传的文件确实落在 `LEGADO_DATA_DIR/webdav`（Docker 中即 `/data/webdav`）下，删除后磁盘同步清理。
- [ ] **安全**：`/webdav/../x`、`a\b` 等越界路径被拒绝，无法写出 `webdav/` 目录之外。
- [ ] **设置页面**：Web 端一级导航出现「文件」入口；页面展示服务状态与访问地址、四类客户端接入指引、已存数据统计；可浏览子目录（面包屑）、上传文件、新建文件夹、下载与删除；移动端视口无横向溢出。
- [ ] **自动化验证**：`./gradlew :server:test` 中 WebDAV 用例全部通过，`npm --prefix web run check` 与 `npx tsx web/test/run-all.ts` 全绿，且不引入既有用例回归。
