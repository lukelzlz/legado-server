---
id: ACCEPT-033
title: 书籍正文插图验收手册（显示 / 代理缓存 / 离线 / 灯箱）
proposal: PROPOSAL-034
adr: ADR-033
date: 2026-10-17
author: Agent
---

# ACCEPT-033: 书籍正文插图验收手册

> 本手册自包含：3 分钟内按顺序点完即可。**只涉及"看得见的图"与"磁盘上的文件"**。
> 想联网验证一支真实图床的字节，第 4 步给了命令行做法。

## 1. 快速开始 (Get Started)

本机开发实例约定（沿用 `start-server.ps1`）：

| 项 | 值 |
| :--- | :--- |
| 数据目录 | `E:\Desktop\legado\_run-data` |
| 端口 | `8080`（明文 HTTP 必须 `LEGADO_SECURE_COOKIES=false`） |
| 管理员密码 | `w71251478` |

```powershell
cd E:\Desktop\legado
.\start-server.ps1 -StopExisting -OpenBrowser
```

浏览器打开 <http://127.0.0.1:8080>，登录。

---

## 2. 三步验收 (Step-by-Step Verification)

### 用例 1：正文里的插图能显示了（Happy Path）

1. 书架里打开一本**正文带插图**的小说（图文混排的连载源，例如带插画的同人/图文作品）。
2. 翻到有插图的章节。

**预期表现**

- [ ] 图片出现在正文里对应的位置（原来那里是空白）；
- [ ] 图片**不超出正文宽度**，圆角 + 与上下段落有间距；
- [ ] 夜间模式下图片边界清楚（有细边框，不会糊在黑底上）；
- [ ] 同一行里"文字 + 图 + 文字"都在，且**点插图不会翻页**。

### 用例 2：点击放大（灯箱）

1. 点击正文里的任意一张插图。

**预期表现**

- [ ] 全屏黑底，图片完整显示（不裁切，`contain`）；
- [ ] 右上角有 `×`；
- [ ] 点周围黑底、或点 `×`、或按 `Esc` ⇒ 关闭，回到原来的阅读位置。

### 用例 3：服务端缓存落盘（这条最关键）

```powershell
$d = 'E:\Desktop\legado\_run-data'
Get-ChildItem "$d\images" -Recurse -File | Select-Object -First 5 -ExpandProperty FullName
```

**预期表现**（命名逐字符合 `书名 (20位书哈希)/章节名 (20位章节哈希)/8位序号.真实扩展名`）：

```
E:\Desktop\legado\_run-data\images\某某书 (3f9c21ab7d0412e8c9f0)\第12章 插画 (a1b2c3d4e5f60718293a)\00000000.jpg
```

- [ ] 目录名带 `(20 位小写 hex)`、文件名是 **8 位补零序号 + 真实扩展名**；
- [ ] **双击图片文件能直接用系统看图器打开**（说明落盘的是真实图片字节，不是 gzip/错误页）；
- [ ] `comic\` 下**没有**新增这批插图（两套命名空间不混）。

### 用例 4：离线 / 清浏览器缓存后仍能显示

1. 在**同一章**再打开一次（已缓存 ⇒ 服务端零上游请求，秒开）。
2. 断开网络（或把 DevTools 的 Network 切成 Offline），刷新页面重新进这一章。
3. 正文来自离线缓存，插图应当**仍然显示**（走服务端 `images/` 直出）。

**预期表现**

- [ ] 断网后图片依然显示；
- [ ] 断网后新进入**没缓存过**的章节：正文报错/加载失败是预期；已缓存的章不受影响。

### 用例 5：失败时的表现（别显示裂图图标）

用一个必然抓不到图的地址（或断网时打开未缓存章节的图）：

```powershell
# 需要先登录，拿 cookie；这里只看状态码与文案
curl.exe -s -o NUL -w "%{http_code}`n" "http://127.0.0.1:8080/api/books/images/illustration?sourceId=x&chapterUrl=http%3A%2F%2Fa%2Fb&url=not-a-url"
```

**预期表现**

- [ ] 返回 **400**（非法地址），不是 200；
- [ ] 前端该位置显示**虚线占位框 + 「插图加载失败」文案**，不是浏览器裂图图标；
- [ ] 服务端日志有一行 `illustration fetch failed: …` 的具体原因。

---

## 3. 命令行深挖（可选，验证"真的是图片字节"）

```powershell
# 1) 登录并保存会话
$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$body = @{ password = 'w71251478' } | ConvertTo-Json
Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/auth/login' -Method POST -Body $body -ContentType 'application/json' -WebSession $s | Out-Null

# 2) 取一张插图的真实字节（把 url 换成某章正文里那张图的真实地址）
$u = [uri]::EscapeDataString('https://example.com/illustration.jpg')
$src = [uri]::EscapeDataString('https://source.example')
$chap = [uri]::EscapeDataString('https://source.example/chapter/1')
$book = [uri]::EscapeDataString('https://source.example/book/1')
$r = Invoke-WebRequest -WebSession $s -Uri "http://127.0.0.1:8080/api/books/images/illustration?sourceId=$src&bookUrl=$book&chapterUrl=$chap&url=$u"
$r.RawContentStream.ToArray()[0..3] | ForEach-Object { $_.ToString('x2') }
```

**预期**：前几个字节是图片魔数 —— `ff d8 ff` = JPEG、`89 50 4e 47` = PNG（**不是** `1f 8b` 的 gzip，
也不是 `<html`）。响应头应含 `ETag` 与 `Cache-Control: … immutable`；带 `If-None-Match` 再请求一次应得 **304**。

---

## 4. 回归清单（改这块代码时必跑）

```powershell
$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.11_10"
.\gradlew.bat :server:test --tests "io.legado.server.NovelIllustrationContentTest" `
  --tests "io.legado.server.IllustrationCacheTest" --tests "io.legado.server.IllustrationRouteTest" -x buildWeb
npm.cmd --prefix web run check
npx.cmd tsx web/test/run-all.ts
```

- [ ] 三个服务端测试类全绿；
- [ ] `npm run check` exit 0；
- [ ] `run-all` **fail 0**（该仓 run-all 的退出码基线是 1，判定按 `fail` 数）。

## 5. 已知边界（验收时请勿当成 bug）

- **老章节的插图找不回来**：改动前缓存的正文里插图已被删掉（不可逆）。重新在线打开该章即可恢复。
- 单图 **> 5 MiB** 会被拒绝（502）；单章离线预热**只抓前 20 张**，其余在线按需下载。
- `images/` **没有配额/GC**：只增不减，需要清理时用书架「清除缓存 → 服务器」。
- 少数书源把插图地址藏在 JS 变量里、或本身返回 `data:` 内联图 ⇒ 这些源仍然没有插图（正文文字正常）。
