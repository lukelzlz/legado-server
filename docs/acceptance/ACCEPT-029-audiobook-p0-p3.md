# ACCEPT-029：有声书（方案 G2）实操验收手册

> 关联：[`PROPOSAL-029`](../proposals/PROPOSAL-029-audiobook-support-and-url-addressed-cache.md) ·
> [`ADR-029`](../decisions/ADR-029-audiobook-url-addressed-cache-and-range-streaming.md)
> 目标：让**没有历史上下文的人**照着做，就能确认「有声书端到端可用」这一件事。

---

## 0. 前置

| 项 | 值 |
| :--- | :--- |
| JDK | `$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.12_9"`（不设 Gradle 会直接报错） |
| 构建 | `.\gradlew.bat :server:fatJar` |
| 起服务 | `$env:ADMIN_PASSWORD="w71251478"; $env:LEGADO_SECURE_COOKIES=false; $env:LEGADO_DATA_DIR="$env:TEMP\legado-audio-accept"; java -jar server/build/libs/legado-server-0.1.0-all.jar` |
| 浏览器 | `http://127.0.0.1:8080`（明文 HTTP **必须** `LEGADO_SECURE_COOKIES=false`，否则浏览器不回传 Cookie ⇒ "登录后立刻掉线"） |

> ⚠️ 想先确认"没写错"再看效果，可跳过 §1 直接跑 §5 的自动化。**§2–§4 才是人工验收的重点。**

---

## 1. 快速自检（30 秒）

```powershell
cd C:\Users\w1593\Desktop\legado\_wt-sync
npm.cmd --prefix web run check                       # 前端类型检查
npx.cmd tsx web/test/run-all.ts                      # 前端全套（含 audiobook-player.test.ts）
$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.12_9"
.\gradlew.bat :server:test --rerun -x buildWeb --console=plain *> "$env:TEMP\srv.log"
node scripts/diff-test-failures.cjs                   # 与基线**逐条**比对失败集合（不看数量！）
```

判据：前端套件退出码 0；服务端 `diff-test-failures.cjs` 报告**零新增失败**。
（本机长期有一批 `sqlite 被占用` 的既有失败，**只看数量会误判**。）

---

## 2. P0 验收：音频书能入架、能解析出音频地址

1. 用真实 Legado 备份（含"听书"书）在 **WebDAV 设置页 → 导入备份**。
2. 打开浏览器控制台看导入摘要：
   - `books` 应包含音频书（**不再被跳过**）；
   - 摘要里的"已跳过音频听书 N"应**消失或为 0**。
3. `#shelf` 书架：音频书的封面右上角应显示 **`听书`** 徽标（蓝色）。
4. 点该书的封面 → 应进入**有声书播放器**（不是文字阅读器、不是漫画阅读器）。

**预期失败模式（用于区分"是谁的问题"）**：
- 进了文字阅读器 ⇒ `book_kind` 不是 `audio`（查库里 `select book_kind from book_shelf`）。
- 进去就提示"音频规则未提取到任何地址" ⇒ **书源规则问题**（正文规则没给出 `<audio src>` / URL / JSON 数组），不是服务端问题。

---

## 3. P1 验收：能播、能 seek、二次播放零上游请求

1. 进入播放器后**点中心大圆键**：应在 1~3 秒内开始出声（首次要服务端抓取整段）。
2. 拖动进度条到中间：应**立刻**继续播，**不得**卡死（Range 写错的典型症状）。
3. 打开 DevTools → Network，过滤 `audio`：
   - 首次：`200` 之后跟着一串 **`206 Partial Content`**（带 `Content-Range`）；
   - **退出再进这本书**：应看到 `200/206` 来自 **disk cache**（`from disk cache`），且**没有新的上游请求**。
4. 服务端侧不可见上游请求 ⇒ 在 `/api/audio/{key}` 的响应头里确认：
   `Accept-Ranges: bytes`、`ETag: "<key>"`、`Cache-Control: private, max-age=31536000, immutable`。

**手工 curl 复核（最硬的一条）**：

```powershell
# 先登录拿 Cookie 与 CSRF
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-WebRequest -Uri http://127.0.0.1:8080/api/auth/login -Method POST -WebSession $session `
  -ContentType 'application/json' -Body '{"password":"w71251478"}'
# 取一个 key（从播放器 Network 里的 /api/audio/<key> 复制，或直接看书架缓存的 stats）
$key = '<64 位 hex>'
# 206 + Content-Range
curl.exe -s -D - -o NUL -H "Range: bytes=0-1023" "http://127.0.0.1:8080/api/audio/$key"
# 416 + Content-Range: bytes */<size>
curl.exe -s -D - -o NUL -H "Range: bytes=999999999-" "http://127.0.0.1:8080/api/audio/$key"
```

判据：第一条 `206` 且 `Content-Range: bytes 0-1023/<size>`；第二条 `416` 且 `Content-Range: bytes */<size>`。

---

## 4. P2/P3 验收：配额、绿点、毫秒进度、离线

### 4.1 目录绿点与批量缓存
1. 播放器 → **设置抽屉 → 离线缓存 → 「本章」**：应弹"已加入离线队列"。
2. 稍等几秒打开**目录**：该章出现**绿点**，表示**整章音频都已落盘**（不是"有清单"）。
3. 点「全本缓存」：后台开始缓存（设置抽屉里的提示会变成"正在后台缓存"）。

### 4.2 磁盘不超配额
```powershell
# 缓存占用与配额水位
curl.exe -s -b cookies.txt "http://127.0.0.1:8080/api/audio/cache/stats"
```
判据：`totalBytes <= quotaBytes`（默认 20 GiB）；`diskBytes` 与 `totalBytes` **不应长期背离**
（背离 = 有孤儿文件）。

> 想快速验 GC：把配额临时改小重启 —— `$env:LEGADO_AUDIO_CACHE_MAX_GB="0.01"`（10 MiB）。
> 判据：**正在听的那一章 + 后方 5 章 + pinned 书**的音频**不能被删**，其余冷数据可被淘汰。

### 4.3 毫秒进度往返（P3 的核心）
1. 播到某处（例如 1:23.4），**退出播放器**。
2. 重新进入这本书：**必须回到刚才的毫秒位置**（不是回到章首）。
3. 断网（DevTools → Network → Offline）后再进：应显示**离线横幅**，且已缓存章仍能播。

---

## 5. 自动化验证

```powershell
# 服务端：Range/进度/GC/导入（都不触网）
$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.12_9"
.\gradlew.bat :server:test --tests "io.legado.server.Audio*" --tests "io.legado.server.BackupImportAudioTest" -x buildWeb --console=plain

# 前端：静态渲染 + SW 规则 + i18n + 预热并发
npx.cmd tsx web/test/run-all.ts

# 真实浏览器（需要先起服务，且书架里有有声书）：断言 readyState>=2 && duration>0、seek 206、断网可播
npx.cmd tsx web/test/e2e-audiobook-browser.ts
```

**浏览器 E2E 的判据是硬的**：
- `audio.readyState >= 2 && duration > 0`（**不接受**"接口 200 / 字节数正常"作为能播的证据）；
- seek 后位置落在 50% 附近且**没有 416**；
- `legado-audio` CacheStorage 非空；
- 断网后仍 `readyState >= 2`。

---

## 6. 回滚开关（出问题时的第一动作）

| 环境变量 | 作用 |
| :--- | :--- |
| `LEGADO_AUDIO_PREFETCH=false` | 关掉后台批量缓存（只按需抓当前播放的那一段） |
| `LEGADO_AUDIO_GC=false` | 关掉音频 GC（磁盘只增不减，排障用；**硬开关，force 也绕不过**） |
| `LEGADO_AUDIO_CACHE_MAX_GB=<n>` | 改配额（支持小数） |
| `LEGADO_MAX_GLOBAL_AUDIO_FETCHES=<n>` | 改整机并发（默认 3） |

> 关掉 `LEGADO_AUDIO_PREFETCH` 后**按需播放链路必须完全照常** —— 这是设计约束（预取是纯增强）。

---

## 7. 验收签字

- [ ] P0：音频书在书架上且带 `听书` 徽标，点开进播放器
- [ ] P1：能播、拖进度条不卡、二次进入零上游请求、`206/416` 头正确
- [ ] P2：绿点=整章就绪；配额不超；在听章节与 pinned 不被 GC 删
- [ ] P3：退出重进**回到毫秒**；断网可听已缓存章；锁屏（mediaSession）可控
- [ ] 回滚开关逐个试过至少一次

> 回复「验收通过 / LGTM」前，请确认上面每一项都**亲手做过**，而不是只看自动化结果。
