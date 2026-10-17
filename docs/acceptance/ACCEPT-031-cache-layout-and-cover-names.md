---
id: ACCEPT-031
title: 缓存目录结构与封面命名验收手册（v2 可浏览布局 + v3 漫画根/封面改名）
proposal: PROPOSAL-031
adr: ADR-031
date: 2026-10-17
author: Agent
---

# ACCEPT-031: 缓存目录结构与封面命名验收手册

> 本手册自包含：任何新人或外部 Agent 从零照做即可跑通。
> 只涉及**磁盘上看得见的东西** —— 不需要读代码，也不需要真实书源联网（除非你要重新缓存）。

## 1. 快速开始与环境准备 (Get Started)

本机开发实例的约定（`start-server.ps1` 内置）：

| 项 | 值 |
| :--- | :--- |
| 数据目录 | `E:\Desktop\legado\_run-data`（`LEGADO_DATA_DIR`，默认 `-DataDir` 未传时即此） |
| 端口 | `8080`（`LEGADO_PORT`） |
| 明文 HTTP 联调 | `LEGADO_SECURE_COOKIES=false`（脚本已自动设） |
| 管理员密码 | 脚本默认 `w71251478`（`ADMIN_PASSWORD`） |

```powershell
# 1) 启动（脚本自动选 JDK 21、按需构建 fatJar、按需停掉占用端口的旧 java）
cd E:\Desktop\legado
.\start-server.ps1 -StopExisting -OpenBrowser
```

浏览器打开 <http://127.0.0.1:8080>，用管理员密码登录。

```powershell
# 2) 先把四个根目录看一遍（这是本次验收的主战场）
$d = 'E:\Desktop\legado\_run-data'
Get-ChildItem $d | Select-Object Name, Mode
```

**期望**：`audio`、`comic`、`covers`、`images`、`webdav` 五个目录 + `legado.sqlite*`。

---

## 2. 核心功能验收步骤 (Step-by-Step Verification)

### 用例 1：漫画/音频的目录能一眼读懂（Happy Path）

```powershell
$d = 'E:\Desktop\legado\_run-data'
Get-ChildItem "$d\comic" -Recurse -File | Select-Object -First 5 -ExpandProperty FullName
Get-ChildItem "$d\audio" -Recurse -File | Select-Object -First 5 -ExpandProperty FullName
```

**预期表现**（命名逐字符合 `书名 (20位哈希)/章节名 (20位哈希)/8位分段号.真实扩展名`）：

```
E:\Desktop\legado\_run-data\comic\让词条飞一会 (445cd76eb2b4bd301527)\第4话 劫男色,是认真的_ (66872a658b4853c41e2b)\00000000.jpg
E:\Desktop\legado\_run-data\audio\AB验证书 (81ed195424d1346c0dbd)\001 空屋 (fc29c2ddbf3290e3b06a)\00000000.mp3
```

逐项核对：

- [ ] 书目录名 = `书名 (20 位小写 hex)`，**括号是半角、哈希正好 20 位**；
- [ ] 章节目录名 = `章节名 (20 位小写 hex)`；
- [ ] 文件名 = **8 位补零**序号 + `.` + **真实扩展名**（`.jpg` / `.png` / `.mp3` …）；
- [ ] 目录按书聚在一起，**双击图片/音频能直接播放**（扩展名是真的）。

### 用例 2：封面有书名、有扩展名

```powershell
$d = 'E:\Desktop\legado\_run-data'
"封面总数 = " + (Get-ChildItem "$d\covers" -File).Count
"无扩展名文件数 = " + (Get-ChildItem "$d\covers" -File | Where-Object { $_.Name -notmatch '\.' }).Count
"符合 '书名 (20位哈希).扩展名' 的数量 = " + (Get-ChildItem "$d\covers" -File | Where-Object {
  $_.Name -match '^.+ \([0-9a-f]{20}\)\.[A-Za-z0-9]{1,5}$' }).Count
Get-ChildItem "$d\covers" -File | Select-Object -First 3 Name
```

**预期表现**（本机实测口径）：

```
封面总数 = 197
无扩展名文件数 = 0
符合 '书名 (20位哈希).扩展名' 的数量 = 197
Name
----
AB验证书 (81ed195424d1346c0dbd).jpg
从魔法少女开始独断万古 (ab493cb26f0ef03e30e1).jpg
让词条飞一会 (445cd76eb2b4bd301527).jpg
```

- [ ] **0 个无扩展名文件**（老布局是 `covers/<64位hex>`，无扩展名）；
- [ ] 扩展名只有 `.jpg` / `.png`（本机实测 55 jpg + 142 png）—— **不出现 `.bin`**
      （出现 `.bin` 说明 `content_type` 是历史占位值且**真实字节也认不出来**，需单独排查）；
- [ ] 操作系统能直接预览这些文件。

### 用例 3：**改中文名仍可读**（读取只认哈希，中文不参与定位）

> 这是本设计的核心承诺：中文只是"给人看的装饰"。

1. 在书架里挑一本**已经缓存过**的书（有封面、正文/图片/音频已下载）。
2. 把它的缓存目录**中文部分改成乱码**（**保留 `(哈希)` 原样**）：

```powershell
$d = 'E:\Desktop\legado\_run-data'
$book = Get-ChildItem "$d\covers" -File | Where-Object { $_.Name -match '^(.+) \(([0-9a-f]{20})\)(\..+)$' } | Select-Object -First 1
$old = $book.Name
$hash = [regex]::Match($old, '\(([0-9a-f]{20})\)').Groups[1].Value
$ext  = [System.IO.Path]::GetExtension($old)
$new  = "ZZZ乱码 ($hash)$ext"
Rename-Item $book.FullName $new
"改名前: $old"
"改名后: $new"
```

3. 回到浏览器，**硬刷新**该书的封面（或退出书架再进）。
4. **预期表现**：
   - 封面**照常显示**（服务端按 `(哈希)` 段扫到了改名后的文件，并把新的
     `file_path` **回填**进库）；
   - 若该书是漫画/音频，同样把它的书目录中文段改成 `ZZZ乱码`（保留 `(哈希)`），
     进章阅读/播放 ⇒ **照常命中**。

```powershell
# 命令行自证：定位不依赖中文 ⇒ 改名后这条路仍然 200
# （cookie 从浏览器 DevTools 取，或直接用 Basic 认证的 curl）
curl.exe -s -o NUL -w "%{http_code}`n" http://127.0.0.1:8080/api/covers/<该书的 cover_key>
```

### 用例 4：**删掉哈希标记即判缺失**（宁重下，绝不瞎猜）

1. 接着用例 3，把**已经改成乱码**的那个封面文件里的 `(哈希)` 段一并删掉：

```powershell
$d = 'E:\Desktop\legado\_run-data'
$f = Get-ChildItem "$d\covers" -File | Where-Object { $_.Name -like 'ZZZ乱码*' } | Select-Object -First 1
Rename-Item $f.FullName ($f.Name -replace ' \([0-9a-f]{20}\)', '')
"现在没有哈希标记: $((Get-ChildItem "$d\covers" -File | Where-Object { $_.Name -like 'ZZZ乱码*' }).Name)"
```

2. 回浏览器硬刷新该书的封面。
3. **预期表现**：服务端**重新回源抓取**封面并生成一个新的 `书名 (哈希).扩展名` 文件
   （而不是把那个没有哈希标记的文件当成缓存直出）。
   —— 这条正是"**判缺失，绝不返回错文件**"的可观察形态。
4. **收尾**：把用例 3 / 4 里手工改过的文件名恢复原状（或让它保持新生成的名字，都能正常工作）。

### 用例 5：v2 的文件是**搬过去的**（迁移语义核验）

> 只有在"从 v2 升级上来"的那次启动里才能看到这个证据；若已经跑过 v3，请用下面的**只读核对**代替。

**5a. 迁移日志（升级当次）**

```powershell
# 启动日志里应有这一行（本机实测口径）
# 缓存目录结构升级到 v3：漫画根 images -> comic (移动文件=49, 字节=12777092, 跳过=0, 失败=0) 封面改名(改名=196, 跳过=0, 失败=0)
```

- [ ] `移动文件` **> 0**（若是"清掉重下"，这里会是 0 而 `comic` 需要重新缓存）；
- [ ] `字节` 与旧 `images` 目录占用**量级一致**；
- [ ] `失败=0`；
- [ ] 封面 `改名` 数 + 孤儿封面 = 迁移前 `cover_cache` 行数（见下方 SQL）。

**5b. 版本号与搬移痕迹（只读核对）**

```powershell
# app_setting.cache_layout 必须是 v3
node -e "const{DatabaseSync}=require('node:sqlite');const db=new DatabaseSync('E:\\Desktop\\legado\\_run-data\\legado.sqlite',{readOnly:true});console.log('cache_layout =',db.prepare(`select value from app_setting where key='cache_layout'`).get()?.value);console.log('cover_cache 行数 =',db.prepare('select count(*) c from cover_cache').get().c);console.log('file_path 为空的行数 =',db.prepare(`select count(*) c from cover_cache where file_path is null or file_path=''`).get().c);"
```

**预期表现**（本机实测）：

```
cache_layout = v3
cover_cache 行数 = 197
file_path 为空的行数 = 0
```

- [ ] `cache_layout = v3`；
- [ ] **`file_path` 为空的行数 = 0**（迁移给每个封面都写了新路径）；
- [ ] `images/` 是**空目录**（文件已搬空，目录本身作为小说插图预留根**被保留**）：

```powershell
"images 下条目数 = " + (Get-ChildItem 'E:\Desktop\legado\_run-data\images' -Force).Count   # 期望 0
```

**5c. 哈希口径自证**（封面名里的哈希 == `sha256(sourceId + "\n" + bookUrl)` 前 20 位）

```powershell
node -e "const{DatabaseSync}=require('node:sqlite');const c=require('node:crypto');const db=new DatabaseSync('E:\\Desktop\\legado\\_run-data\\legado.sqlite',{readOnly:true});const rows=db.prepare('select s.name,s.source_id,s.book_url,c.file_path from book_shelf s join cover_cache c on c.cache_key=s.cover_key limit 5').all();for(const r of rows){const h=c.createHash('sha256').update(r.source_id+'\n'+r.book_url.trim()).digest('hex').slice(0,20);console.log((r.file_path.startsWith(r.name+' ('+h+').')?'OK  ':'BAD ')+r.file_path);}"
```

**预期表现**：5 行全部以 `OK  ` 开头。

### 用例 6：`images/` 是**小说插图预留根**（不是坏掉的目录）

```powershell
$d = 'E:\Desktop\legado\_run-data'
"images 存在 = $(Test-Path $d\images)   条目数 = $((Get-ChildItem $d\images -Force).Count)"
```

**预期表现**：`images 存在 = True   条目数 = 0`。

- [ ] 目录**存在**（启动时无条件创建，与迁移版本无关）；
- [ ] **条目数 0** —— 本轮**不实现插图写入**，空是正确的；
- [ ] 顺带确认 API 表面**没被改**：`/api/books/images/...` 仍然是漫画那套路由

```powershell
curl.exe -s -o NUL -w "images cache stats: %{http_code}`n" http://127.0.0.1:8080/api/books/images/cache/stats
# 期望 401（未登录）或 200（已登录）—— 关键是路由存在，不是 404
```

---

## 3. 预期日志参考 (Expected Log Output)

```log
# 升级当次（v2 → v3）
缓存目录结构升级到 v3：漫画根 images -> comic (移动文件=49, 字节=12777092, 跳过=0, 失败=0) 封面改名(改名=196, 跳过=0, 失败=0)

# 封面改名时对不上书架的孤儿（留在原处，正常）
封面改名跳过（无书架引用，留在原处）：<64位hex key>

# 目标同名已存在（跳过，不覆盖）
封面改名跳过（目标已存在，未覆盖）：某书 (1a2b3c4d5e6f70819200).jpg
```

```log
# v1（散列分片）升级——这是"清掉重下"路径
缓存目录结构升级到 v3：清理旧布局 images(文件=N, 字节=M) comic(文件=N, 字节=M, 行=N) audio(文件=N, 字节=M, 行=N) 封面改名(改名=N, 跳过=N, 失败=N)
```

> ⚠️ 日志文件在 Windows 上是 **GBK(936)** 编码；用 PowerShell 读时必须
> `[System.Text.Encoding]::GetEncoding(936)`，否则中文全部乱码。

---

## 4. 回归与异常检查项 (Sanity & Edge Checks)

- [ ] 再次重启服务，**不再出现**迁移日志（v3 状态是 **no-op**，不扫盘、不查库）。
- [ ] 从 v3 重启后，缓存文件数量与字节数**一个都没变**（迁移幂等）。
- [ ] 手工改名过的目录（用例 3）在重启后**仍然可读**（无中文定位 + `file_path` 回填）。
- [ ] `data/covers` 里**没有** `tmp_*.part` 残留（原子落盘的临时文件不该留在盘上）。
- [ ] 上传一张新封面（编辑书籍信息 → 上传本地封面）⇒ 落盘名先是 `未命名 (<内容key>).<ext>`，
      保存后**归位**成 `书名 (书哈希).<ext>`（同一把 key）。
- [ ] 同一本书**换**一张封面 ⇒ 新名生效，旧 key **不会直出旧字节**（最多回源重下）。
- [ ] 书名为空/含 `/`、`:`、换行的书 ⇒ 目录名被清洗（非法字符变 `_`、换行折叠），
      **服务不报 `InvalidPathException`**。
- [ ] Windows 保留名（`CON` / `PRN` / `AUX` / `NUL` / `COM1..9` / `LPT1..9`）的书名 ⇒ 目录名追加 `_`。
- [ ] 内置下载路径拿到的非图片字节（谎报 `image/jpeg` 的 gzip）仍被拦住
      （报"封面内容不是可解码的图片"），**不落盘**。

---

## 5. 自动化验证（可不做，用于交叉确认）

```powershell
$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.11_10"
cd E:\Desktop\legado\_wt-all
# v3 布局与迁移
.\gradlew.bat :server:test --tests "io.legado.server.CacheLayoutV3Test" -x buildWeb
# v2 路径组装与清洗
.\gradlew.bat :server:test --tests "io.legado.server.CachePathLayoutTest" -x buildWeb
```

> 全量 `:server:test` 在本机长期固定失败若干条（Windows 删除被占用的 SQLite），
> **判定回归必须与干净基线的失败集合逐条比对，禁止只看失败数量**
> （见 [`SESSION-HIST-008`](../sessions/SESSION-HIST-008-windows-environment-and-verification-baseline.md)）。
