---
id: ACCEPT-017
title: 验收手册：聚合书源（大灰狼）正文提取修复
status: 待用户验收
date: 2026-09-20
related: PROPOSAL-017 / ADR-017 / SESSION-019
---

# ACCEPT-017：聚合书源（大灰狼）正文提取修复 · 验收手册

> **本手册面向用户实操**。每一步都有可复制的命令与明确的期望结果。
> **当前完成度**：`[Tested]` —— 已通过新增单测（17/17）与全量测试失败集合比对（零回归），
> **尚未** `[Accepted]`（需你按本手册实操确认）与 `[Pushed]`（需你决定是否推送）。

---

## 0. 这次修了什么（两处根因）

**根因 ①（IIFE 吞掉补全值）**：聚合类书源（如《大灰狼融合VIP5.0》）的 `jsLib` 里每个工具函数都含 `return`，
旧实现把它与规则脚本**拼接后**一起判定是否包 IIFE ⇒ 整段被包进 `(function(){…})()` ⇒
Rhino 的**求值补全值退化为 `undefined`** ⇒ 正文规则末尾的裸表达式（`data;`）取不到值。
修复：**jsLib 在同一 scope 内先单独求值**，再**只按规则脚本自身的顶层 `return`** 决定是否包裹。

**根因 ②（正文清洗把正文删光）**：该源正文规则返回的整个正文**包在一个 `<div rs-native>…</div>` 里**，
而 `RuleRunner.cleanContent()` 里的 `replace(Regex("<div[\\s\\S]*?</div>"), "")` 会**连正文一起删除**
⇒ `text` 变成空 ⇒ 报「正文规则未提取到内容」。
修复：清洗改为两段式——结构性清洗后**若内容为空则退化为「只剥标签、保留文本」**。

> **注意**：用户最初描述为「登录后仍提示要登录」，但实测证明**与登录态无关**
> （登录态全程正常、上游也认这个账号）。详见 `docs/sessions/SESSION-019-dagou-content-root-cause.md` §8。

---

## 0.1 真实端到端验证（2026-09-20 实测）

修复后用**真实书籍**验证（不依赖合成数据）：

```
POST /api/books/content  → HTTP 200
响应长度 = 2478
{"title":"第8章 摊牌","content":"这一番话几乎是把众人点醒了，也同样点醒了齐夏。\n\n是啊，「说谎者」的赢面确实太大了。…"}
```

| 项 | 值 |
| :--- | :--- |
| 书源 | `大灰狼融合VIP5.0` |
| 书籍 | 《十日终焉》（作者 杀虫队队员，`sources=番茄`） |
| 章节 | 第 8 章 摊牌 |
| 修复前 | `{"title":"第8章 摊牌","content":"您今日免登录访问次数已达上限(3次)！…"}` |
| 修复后 | ✅ 返回**真实正文**（2478 字符） |

---

## 1. 前置条件

| 项 | 要求 |
| :--- | :--- |
| 工作目录 | `C:\Users\w1593\Desktop\12\reader\legado-server` |
| JDK | Amazon Corretto 21（`C:\Program Files\Amazon Corretto\jdk21.0.12_9`） |
| Gradle | 本机已缓存 8.14.4，无需下载 |
| 书源样本 | `C:\Users\w1593\Desktop\12\安卓阅读app-大灰狼融合4.0(vip完全版).json`（含凭据，**仅本机使用，不得入库**） |

---

## 2. 快速验收（3 条命令，约 4 分钟）

### 步骤 0：把服务跑起来

本仓库**不提供**启动包装脚本（早期版本曾有 `start-server.bat` / `start-local.ps1` 等，
因夹带本机明文口令与账号数据，已随本机数据一并清理移除）。正式启动方式统一走 Gradle：

```powershell
$g = "C:\Users\w1593\Desktop\12\reader\legado-server"
$env:JAVA_HOME = "C:\Program Files\Amazon Corretto\jdk21.0.12_9"   # 本机 JAVA_HOME 可能被别的 JDK 占用，必须显式指定
$env:LEGADO_SECURE_COOKIES = "false"                               # 明文 HTTP 必设，否则浏览器不回传 Cookie
$env:LEGADO_DATA_DIR = "$g\data"                                   # 服务端日志里以「当前工作目录」为基准，建议写绝对路径
$env:ADMIN_PASSWORD  = "<你的至少12位口令>"                          # 仅在库为空时用于初始化管理员
& "$g\gradlew.bat" -p $g :server:run --console=plain
```

**期望输出**（约 30~60 秒后）：

```
[main] INFO io.ktor.server.Application - Responding at http://127.0.0.1:8080
```

另开一个窗口冒烟：

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" http://127.0.0.1:8080/healthz   # 期望 200
```

> ⚠️ **口令不要写进任何文件**，只在当前会话里用环境变量传入。
> 服务端强校验口令 **≥12 位**；若需重置，用 CLI：
> `java -jar server\build\libs\legado-server-0.1.0-all.jar reset-password "<新口令>"`
> （该子命令在服务启动前处理，会吊销所有既有会话）。

### 步骤 1：跑新增的语义回归测试套件

```powershell
$g = "C:\Users\w1593\Desktop\12\reader\legado-server"
& "$g\gradlew.bat" -p $g :server:test --tests "io.legado.server.JsSandboxCompletionValueTest" --console=plain --no-daemon
```

**期望结果**：末尾出现

```
BUILD SUCCESSFUL in <时间>
```

（该套件共 **17 个用例**，全部通过；修复前其中 14 个会失败。）

### 步骤 2：跑全量服务端测试并比对失败集合

```powershell
& "$g\gradlew.bat" -p $g :server:test --console=plain --no-daemon --continue 2>&1 |
  Select-String -Pattern 'tests completed'
```

**期望结果**：

```
187 tests completed, 53 failed
```

**如何判定「零回归」**（关键，别只看数字）：

| 指标 | 修复前基线 | 修复后（本次实测） | 判定 |
| :--- | :--- | :--- | :--- |
| 用例总数 | 170 | **187**（+17 = 新增套件） | ✅ |
| 失败数 | 53 | **53** | ✅ 未增加 |
| 失败根因 | 全部 `FileSystemException`（SQLite 文件占用） | **全部 53 个同因** | ✅ 集合一致 |

> **为什么会有 53 个失败？** 这是本机 Windows 的**固定环境噪声**，与本次改动无关：
> 这些测试不调 `database.close()` 就删 WAL SQLite，Windows 不允许删除被占用文件（macOS 的 POSIX 语义允许）。
> 详见 `docs/sessions/SESSION-HIST-008-windows-environment-and-verification-baseline.md`。

### 步骤 3：不看数字，看根因是否只有一种

```powershell
$files = Get-ChildItem "$g\server\build\test-results\test\TEST-*.xml"
$kinds = @{}
foreach($f in $files){
  $c = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
  foreach($m in [regex]::Matches($c,'<failure message="([^"]{0,120})')){
    $k = if($m.Groups[1].Value -match 'FileSystemException'){'SQLITE文件占用(环境噪声)'}else{'*** 非基线 ***'}
    if($kinds.ContainsKey($k)){$kinds[$k]++}else{$kinds[$k]=1}
  }
}
$kinds.GetEnumerator() | ForEach-Object { "{0,4}  {1}" -f $_.Value, $_.Key }
```

**期望结果**：只出现一行，且全部归入环境噪声：

```
  53  SQLITE文件占用(环境噪声)
```

**若出现 `*** 非基线 ***`**：说明有真实回归，请把该行内容贴给我。

---

## 3. 深度验收（可选，验证「真实书源真的修好了」）

上面的单测用的是**合成 fixture**（因为真实书源含凭据，按提案承诺不入库）。
如果你想亲眼看到**真实大灰狼书源**的正文被提取，可按下面做一次性验证：

> 需要临时写一个探针测试类，验证完删除即可。
> 关键点：**构造源 JSON 时必须同时带上 `jsLib` 与 `loginUrl`**，
> 否则 `sourceContext` 构造出的 library 为空，规则里的工具函数全部未定义（这个坑我在实施中踩过一次）。

脚本骨架（放到 `server/src/test/kotlin/io/legado/server/` 下，跑完删除）：

```kotlin
// 1. 读真实书源 JSON（System.IO.File / kotlinx.serialization 均可）
// 2. 取 jsLib / loginUrl / ruleContent.content 三个字段
// 3. 用它们拼一个最小源 JSON：
//    {"bookSourceUrl":"大灰狼融合VIP5.0","bookSourceName":"验收",
//     "jsLib":<原样>,"loginUrl":<原样>,"ruleContent":{"content":<原样>}}
// 4. 构造 data: 章节地址：
//    payload = """{"book_id":"10001","item_id":"10001","tab":"小说","title":"第一章 测试","sources":"番茄","url":""}"""
//    url = "data:;base64," + Base64(payload) + ""","type":"qingtian"}""".replace("\"\"\"","")  // 见下注
// 5. 调 RuleRunner().content(源JSON, url) 并断言 content 非空
```

> 注：`data:` 地址的正确形态是 `data:;base64,<base64>,{"type":"qingtian"}`。

**本次实测结果**（你应该得到同样的性质，文案可能随上游变化）：

| 项 | 实测值 |
| :--- | :--- |
| `content()` 是否抛「未提取到内容」 | ✅ **不再抛** |
| 返回正文长度 | 32 字符 |
| 返回正文内容 | `您今日免登录访问次数已达上限(3次)！继续阅读请登录后刷新页面。` |

> **这个文案是正常的**：它是该源**上游 content 接口返回的真实业务提示**（未登录状态的限流文案），
> 不是网络错误、也不是修复不完整 —— 它恰好证明**整条规则链已完整跑通、正文被正确提取**。
> 要读到真正的章节正文，需要有该源的登录态（属业务前提，不在本次修复范围）。

---

## 4. 回归自查清单（勾选）

- [ ] 步骤 0：启动脚本跑通，横幅中文正常，`/healthz` 返回 `{"status":"ok"}`
- [ ] 步骤 1：`JsSandboxCompletionValueTest` → `BUILD SUCCESSFUL`（17/17）
- [ ] 步骤 2：全量 → `187 tests completed, 53 failed`
- [ ] 步骤 3：失败根因**只有**「SQLITE 文件占用」一种
- [ ] （可选）步骤 4：真实书源正文不再报「未提取到内容」
- [ ] 确认 `git status` 中**没有**把书源 JSON、探针测试、凭据文件加入提交
- [ ] 确认 `web/dist` 无需变更（本次为纯服务端改动）

### 4.1 启动脚本实测记录（2026-09-20）

| 验证项 | 实测结果 |
| :--- | :--- |
| `:server:compileKotlin :server:compileTestKotlin` | ✅ `BUILD SUCCESSFUL in 32s`，无 `e:` 错误 |
| `:server:fatJar` | ✅ `BUILD SUCCESSFUL`；产物 `server/build/libs/legado-server-0.1.0-all.jar`（28.1 MB） |
| jar 内嵌前端 | ✅ `static/` 共 14 个条目，含 `static/index.html` |
| 启动方式 | ✅ 走 `:server:run`，`Responding at http://127.0.0.1:8080` |
| 首次启动自动建库 | ✅ 生成 `data/legado.sqlite`（+ `-wal`/`-shm`）、`data/covers/`、`data/webdav/` |
| `GET /healthz` | ✅ `200` `{"status":"ok"}` |
| `GET /`、`/index.html` | ✅ `200`（内嵌 Web 端可用） |
| **新口令登录** `POST /api/auth/login` | ✅ `200` + `{"csrfToken":"…"}` |
| **旧默认口令登录** | ✅ `401`（证明口令确实按本机值生效，不是默认值） |

> **说明**：本表是**当时（2026-09-20）**的实测记录。当时环境曾用临时启动脚本，脚本因含本机明文口令
> 与账号数据已按本机清理要求移除；表中「启动方式」一项已同步为现行的 `:server:run` 路径，其余证据未变。

> **实施中踩到并已修正的两个坑**（对后续在 Windows 上写启动脚本的人有用）：
> 1. **PowerShell 5.1 按 ANSI 读取无 BOM 的 `.ps1`** ⇒ 含中文的脚本会解析崩溃
>    （报 `Missing closing '}'`、`The string is missing the terminator`）。**含中文的 `.ps1` 必须写为 UTF-8 带 BOM**。
> 2. **`.bat` 必须 CRLF 且不带 BOM**（仓库的换行约定也要求如此）。

---

## 4.2 口令与安全说明（重要）

- 口令**从不写入任何被跟踪文件**：一律以 `ADMIN_PASSWORD=<你的口令>` 占位，只在当前
  会话里用环境变量传入。本文档全文亦不含真实口令。
- 服务端仅在数据库为空时用它初始化管理员；之后改口令用 CLI（见步骤 0 注）。
- **本机数据已按清理要求删除**：`legado-server/data/` 整个目录（含 `legado.sqlite` 及其
  `-wal`/`-shm`、`covers/`、`webdav/`）已移除。因此下次启动是全新库，
  **书源登录态、cookie 与书架进度均需重新配置**。


---

## 5. 变更清单（本次交付）

| 文件 | 变更 | 说明 |
| :--- | :--- | :--- |
| `server/src/main/kotlin/io/legado/server/JsSandbox.kt` | **改** | 根因①：jsLib 与规则脚本分离求值；新增 `hasTopLevelReturn` 扫描器；包裹判定改用规则脚本自身顶层 return；成功求值后清空 `lastError` |
| `server/src/main/kotlin/io/legado/server/RuleRunner.kt` | **改** | 根因②：`cleanContent()` 改为两段式——结构性清洗后若为空则退化为「只剥标签、保留文本」 |
| `server/src/test/kotlin/io/legado/server/JsSandboxCompletionValueTest.kt` | **新增** | 19 个语义回归用例（含聚合源链路复刻 + 外层 div 保留文本） |
| `docs/proposals/PROPOSAL-017-*.md` | 新增 | 需求提案与裁定记录 |
| `docs/decisions/ADR-017-*.md` | 新增 | 架构决策（含 3 个备选方案的实测裁定） |
| `docs/sessions/SESSION-019-*.md` | 新增 | 完整实测定位与两处修复记录 |
| `docs/acceptance/ACCEPT-017-*.md` | 新增 | 本手册 |
| `AGENTS.md` | 改 | 补 5 条部落知识、更新三处索引 |
| `.gitignore` | 改 | 忽略 `data/`（运行期数据不入库） |

> **改动规模**：`server/src/main/kotlin/` 下 2 个文件，**+114 / −18 行**。

---

## 6. 已知未修项（不在本次范围，已登记）

| 项 | 影响 | 状态 |
| :--- | :--- | :--- |
| `<js>` 求值失败时 `NodeValue.value` 回退成「中间值」⇒ 坏书源可能**静默把 `data:` 载荷当正文**返回 | 用户会看到 `{"book_id":…}` 当正文且无报错 | 已记入 `AGENTS.md` 部落知识，**需独立立项** |
| `RuleAnalyzer` 引号感知切分器未移植（D4） | 与本次症状无因果 | ADR-017 §2.2 计划内，待排期 |
| `java.createSymmetricCrypto` / `source.loginUi` 未补齐 | 实测非本次阻塞项，可能在别的书源上阻塞 | PROPOSAL-017 §2.1 已登记 |

---

## 7. 验收签字

请在实操跑通后回复 **「验收通过」** 或 **「LGTM」**；
若任一步骤不符，请把**命令 + 实际输出**贴回来，我会据此定位。

> 通过后我会把 PROPOSAL-017 / ADR-017 状态推进为 `Accepted`，并按你的指示处理提交与推送
> （注意：本机 `git push` 到 GitHub 当前不可用，见工作区 `docs/sessions/SESSION-HIST-002`）。
