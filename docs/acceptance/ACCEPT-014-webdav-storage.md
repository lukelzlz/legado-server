---
id: ACCEPT-014
title: 内置 WebDAV 服务端（数据目录 webdav 存储区）验收手册
status: pending
date: 2026-09-17
---

# ACCEPT-014: 内置 WebDAV 服务端验收手册

> 目标：验证 `/webdav` 可以被操作系统与第三方客户端直接挂载读写，且上传数据确实落在数据目录下的 `webdav/` 文件夹。
> 完成度：`[Tested 单测通过]`（待用户按本手册实操 → `[Accepted]`）。

---

## 0. 前置准备

### 0.1 Docker 方式启动（推荐）

```bash
docker build -f Dockerfile.server -t test-legado-server:latest .
docker rm -f test-legado 2>/dev/null || true
docker run -d --name test-legado -p 8080:8080 \
  -e ADMIN_PASSWORD=admin123456789 \
  -e LEGADO_SECURE_COOKIES=false \
  -v $(pwd)/.data:/data test-legado-server:latest
```

### 0.2 或本机 JAR / Gradle 方式

```bash
LEGADO_DATA_DIR=./.data ADMIN_PASSWORD=admin123456789 LEGADO_SECURE_COOKIES=false ./gradlew :server:run
```

### 0.3 连接参数

| 项目 | 值 |
| --- | --- |
| 地址（URL） | `http://127.0.0.1:8080/webdav` |
| 用户名 | 任意（例如 `legado`、`admin`，服务端不校验用户名） |
| 密码 | `ADMIN_PASSWORD`（本手册示例为 `admin123456789`） |

> 首次启动后数据目录会自动创建 `webdav/` 子文件夹；`.data/webdav`（容器内 `/data/webdav`）即存储区根目录。

---

## 1. 浏览器快速自查（协议连通性）

1. 浏览器打开 `http://127.0.0.1:8080/webdav/` → 弹出登录框，输入任意用户名 + 管理员密码。
2. 期望结果：返回 200 与一个深色目录清单页面（初始为空列表，仅显示路径标题）。

```bash
# 命令行等价验证（-u 用户名:密码）
curl -s -o /dev/null -w "%{http_code} %{header_json}" -u legado:admin123456789 \
  -X OPTIONS http://127.0.0.1:8080/webdav
# 期望：200，响应头包含 "dav": ["1, 2"]、allow 中含 PROPFIND/PUT/MKCOL、ms-author-via: DAV

# 未认证应返回 401 且带 WWW-Authenticate
curl -s -o /dev/null -w "%{http_code}\n" -X PROPFIND http://127.0.0.1:8080/webdav
# 期望：401

# 密码错误同样 401
curl -s -o /dev/null -w "%{http_code}\n" -u legado:wrong-password \
  -X PROPFIND -H "Depth: 0" http://127.0.0.1:8080/webdav
# 期望：401
```

---

## 2. Windows 资源管理器挂载（核心场景）

1. 打开「此电脑」→ 右键 → **映射网络驱动器**。
2. 文件夹填写 `http://127.0.0.1:8080/webdav`，勾选「使用其他凭据连接」。
3. 输入任意用户名 + 管理员密码。
4. 期望结果：驱动器成功挂载并显示为可写网络位置。
5. 在挂载盘内**新建文件夹** `books`，复制一个 TXT/EPUB 文件进去，重命名一次，再删除。
6. 期望结果：全部操作成功且无「无权限 / 不支持」报错。

> Windows 默认可能限制 Basic 明文认证与非安全站点：若挂载失败，请在 `regedit` 中确认
> `HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters\BasicAuthLevel = 2`，
> 并把 `http://127.0.0.1:8080` 加入「本地 Intranet」或信任站点；`FileSizeLimitInBytes` 决定单文件上限（默认约 50 MB）。

---

## 3. macOS Finder / 第三方客户端

- **macOS Finder**：`Cmd + K` → 服务器地址 `http://127.0.0.1:8080/webdav` → 注册用户（任意用户名 + 管理员密码）→ 期望可读写。
- **RaiDrive / Cyberduck / WinSCP**：选择 WebDAV 类型，地址同上，期望可浏览、上传、下载、重命名。
- **rclone**（命令行）：

```bash
rclone config create legado webdav url=http://127.0.0.1:8080/webdav vendor=other user=legado pass=$(rclone obscure admin123456789)
rclone lsd legado:
rclone copy ./some-book.txt legado:books/
rclone ls legado:
```

---

## 4. 落盘与数据一致性验证

```bash
# 上传一个文件
curl -s -u legado:admin123456789 -T ./some-book.txt http://127.0.0.1:8080/webdav/books/some-book.txt -w "%{http_code}\n"
# 期望：201（首次）/ 204（覆盖已存在）

# 直接检查数据目录，文件必须真实存在且字节一致
ls -l .data/webdav/books/some-book.txt
cmp ./some-book.txt .data/webdav/books/some-book.txt && echo "内容一致"

# 目录枚举（Depth: 1）
curl -s -u legado:admin123456789 -X PROPFIND -H "Depth: 1" http://127.0.0.1:8080/webdav/books
# 期望：207，XML 中含 <D:href>/webdav/books/some-book.txt</D:href> 与 <D:getcontentlength>

# 断点续传（Range）
curl -s -u legado:admin123456789 -r 0-9 http://127.0.0.1:8080/webdav/books/some-book.txt -D - -o /dev/null | head -5
# 期望：206 Partial Content 且含 Content-Range: bytes 0-9/<总长度>

# 越界范围 → 416
curl -s -o /dev/null -w "%{http_code}\n" -u legado:admin123456789 \
  -H "Range: bytes=99999999-" http://127.0.0.1:8080/webdav/books/some-book.txt
# 期望：416

# 禁止写出 webdav 根目录之外
curl -s -o /dev/null -w "%{http_code}\n" -u legado:admin123456789 \
  -T ./some-book.txt "http://127.0.0.1:8080/webdav/../escape.txt"
# 期望：403（或 404），且数据目录外不会出现 escape.txt
```

---

## 5. 锁与写操作

```bash
# LOCK 获得令牌
curl -s -u legado:admin123456789 -X LOCK -H "Timeout: Second-600" \
  --data-binary '<?xml version="1.0"?><D:lockinfo xmlns:D="DAV:"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype><D:owner>tester</D:owner></D:lockinfo>' \
  -D - http://127.0.0.1:8080/webdav/books/locked.txt
# 期望：200/201，响应头 Lock-Token: <opaquelocktoken:...>，body 含 <D:lockdiscovery> 与 timeout

# 用错误令牌 UNLOCK → 409
curl -s -o /dev/null -w "%{http_code}\n" -u legado:admin123456789 -X UNLOCK \
  -H "Lock-Token: <opaquelocktoken:wrong>" http://127.0.0.1:8080/webdav/books/locked.txt
# 期望：409

# 用正确令牌 UNLOCK → 204
curl -s -o /dev/null -w "%{http_code}\n" -u legado:admin123456789 -X UNLOCK \
  -H "Lock-Token: <上一步的令牌>" http://127.0.0.1:8080/webdav/books/locked.txt
# 期望：204
```

> 说明：锁为登记式（advisory），服务端不会因锁存在而拒绝其它写入（避免客户端异常退出遗留锁导致无法上传）。

---

## 6. Docker 数据卷持久化验证

```bash
# 重启容器后数据仍在
docker restart test-legado
sleep 5
curl -s -u legado:admin123456789 -X PROPFIND -H "Depth: 1" http://127.0.0.1:8080/webdav/books | grep -c "some-book.txt"
# 期望：至少 1

# 宿主机的 .data/webdav 与容器 /data/webdav 一致
ls -l .data/webdav/books
```

---

## 7. 回归与安全确认

- [ ] Web 端既有功能不受影响：登录、书源管理、搜索、阅读、朗读、替换规则均正常。
- [ ] `http://127.0.0.1:8080/` 静态前端正常返回（WebDAV 路由未抢占静态资源路径）。
- [ ] 未认证访问 `/webdav` 一律 401，不泄露任何目录信息。
- [ ] `.data/webdav` 之下不存在任何 `*.part` 残留（上传中断除外，重启后可手动清理）。

---

## 8. Web 端 WebDAV 设置页面验收（一级导航「文件」）

### 8.1 自动浏览器冒烟（可选，CI/本地均可跑）

```bash
cd web
CHROME_BIN='C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe' \
LEGADO_PASSWORD=admin123456789 \
npx tsx test/capture-webdav-page.ts
```

脚本会用真实 Chromium 内核浏览器登录、进入 `#webdav`、校验导航高亮、状态卡、四类客户端指引、目录列表、子目录面包屑、页面内上传与删除，并输出桌面（1440 宽）与移动（390 宽）视口的布局指标与截图（默认输出目录：`%TEMP%\legado-webdav-shots`）。期望输出中 `errors` 为空数组、`hasHorizontalOverflow` 均为 `false`。

### 8.2 手工验收步骤

1. 登录 Web 端，确认一级导航出现第六个入口 **文件**，点击后地址变为 `#webdav`。
2. **状态卡**：
   - 「服务状态」显示 `运行中`；
   - 「访问地址」显示 `http://<当前访问域名>:8080/webdav`，点击「复制地址」提示复制成功；
   - 「认证方式」显示 `HTTP Basic` 与「用户名任意填写，密码即当前登录密码」；
   - 「已存数据」显示文件数 / 总占用与目录绝对路径（与 `.data/webdav` 实际内容一致）。
3. **客户端接入**：四张卡片（Windows 资源管理器 / macOS Finder / rclone / Legado App 备份）均可展开查看步骤与命令，点击「复制」能拿到以当前访问域名为前缀的地址或 rclone 命令。
4. **文件管理**：
   - 点击「新建文件夹」→ 输入名称 → 列表出现新目录；同时 `.data/webdav/<名称>` 真实存在。
   - 点击「上传文件」→ 可多选上传 → 列表出现文件且大小/时间正确；`curl` 或资源管理器下载内容一致。
   - 点击目录名进入子目录 → 面包屑显示 `根目录 / 子目录`，点击「根目录」可返回。
   - 点击「下载」→ 浏览器直接下载该文件（无需再输入密码）。
   - 点击「删除」→ 确认后列表移除，磁盘文件同步删除（删除目录会连带其内容）。
5. **移动端**：在 390px 宽视口下页面无横向滚动条，状态卡单列堆叠，文件行操作按钮换行显示且可点。
6. **边界提示**：删除一个不存在的目录（可在另一个标签页先删掉再回到页面点击）应给出错误提示而不是静默失败。

---

## 9. 验收结论

| 项目 | 结论 | 备注 |
| --- | --- | --- |
| 浏览器自查 | ☐ 通过 / ☐ 不通过 | |
| Windows 映射驱动器 | ☐ 通过 / ☐ 不通过 | |
| 第三方客户端（rclone 等） | ☐ 通过 / ☐ 不通过 | |
| 落盘一致性（.data/webdav） | ☐ 通过 / ☐ 不通过 | |
| 锁与 Range | ☐ 通过 / ☐ 不通过 | |
| 数据卷持久化 | ☐ 通过 / ☐ 不通过 | |
| Web 设置页面（状态/指引/文件管理） | ☐ 通过 / ☐ 不通过 | |
| 既有功能回归 | ☐ 通过 / ☐ 不通过 | |
