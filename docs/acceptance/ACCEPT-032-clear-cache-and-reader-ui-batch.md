---
id: ACCEPT-032
title: 清除缓存三选弹窗与阅读器 UI 批次验收手册
proposal: PROPOSAL-032
adr: ADR-032
date: 2026-10-17
author: Agent
---

# ACCEPT-032: 清除缓存三选弹窗与阅读器 UI 批次验收手册

> 本手册自包含：任何新人或外部 Agent 从零照做即可跑通。
> 覆盖五件事：① 按书清缓存 + 范围三选弹窗；② 漫画目录抽屉的缓存栏；
> ③ 有声书底部功能条动态均分；④ 书架「小说」角标；⑤ 英/日界面不再冒中文。

## 1. 快速开始与环境准备 (Get Started)

```powershell
# 1) 启动（脚本自动选 JDK 21、按需构建 fatJar、按需停掉占用端口的旧 java）
cd E:\Desktop\legado
.\start-server.ps1 -StopExisting -OpenBrowser
```

| 项 | 值 |
| :--- | :--- |
| 数据目录 | `E:\Desktop\legado\_run-data`（`LEGADO_DATA_DIR`） |
| 端口 | `8080`（`LEGADO_PORT`） |
| 明文 HTTP 联调 | `LEGADO_SECURE_COOKIES=false`（脚本已自动设，否则登录后立刻掉线） |
| 管理员密码 | 脚本默认 `w71251478`（`ADMIN_PASSWORD`） |

> ⚠️ 若界面像是旧版：DevTools → Application → Service Workers → 注销并硬刷新
> （PWA 预缓存会留住旧 bundle）。

**准备三本书**（都在书架里）：一本**小说**、一本**漫画**、一本**听书**，
且至少都点开过一次以产生缓存。

---

## 2. 核心功能验收步骤 (Step-by-Step Verification)

### 用例 1：「清除缓存」在「移出书架」上面，红色同源

1. 书架 → 任一本书卡片 → 打开**书籍管理**弹窗。
2. **预期表现**：
   - 动作列表里「**清除缓存**」**紧挨在「移出书架」上面**；
   - 它的颜色、字号、行高与「移出书架」**完全一致**（都来自 `.manage-action-row.danger { color: var(--danger) }`）；
   - 副标题写着「清除本书已下载的图片、音频与正文缓存（含本机离线）」。

### 用例 2：范围三选弹窗（三个红色 + 中性取消）

1. 点「清除缓存」。
2. **预期表现**：弹出范围选择弹窗，标题「清除缓存」，副文案
   「将清除《书名》已下载的缓存；阅读进度、书架记录与封面不受影响，请选择清除范围。」
   下面是**四个**选项，顺序固定：

| # | 选项 | 配色 |
| :--- | :--- | :--- |
| 1 | 清除服务器缓存 / 清掉服务端已下载的图片、音频与正文，下次阅读会重新下载 | **红色** |
| 2 | 清除浏览器缓存 / 只清本机已下载的离线正文，不影响服务端 | **红色** |
| 3 | 清理服务器和浏览器缓存 / 一次清干净：服务端与本机一起清 | **红色** |
| 4 | 取消 / 不做任何清理，返回书籍管理 | **中性（不是红色）** |

- [ ] 前三个**红色**、第四个**中性**、取消在**最后**；
- [ ] 弹窗有遮罩，点遮罩/右上角 × 都能关；
- [ ] `Esc` 或点取消 ⇒ **什么都不删**；
- [ ] 夜间模式下弹窗**背景不透明、文字可见**（Portal 绑了 `theme-${theme}`，没有掉主题变量）。

### 用例 3：选「清除服务器缓存」⇒ 只动服务端

1. 先记录现状（服务端缓存**有**、本机离线正文**有**）：

```powershell
# 服务端三处缓存的占用（登录后带 cookie 调用；未登录会 401）
curl.exe -s "http://127.0.0.1:8080/api/books/images/cache/stats"
curl.exe -s "http://127.0.0.1:8080/api/audio/cache/stats"
```

2. 对某本书选「清除服务器缓存」。
3. **预期表现**：
   - Toast：**《书名》缓存已清除**；
   - 打开 DevTools → Network，只出现三个 `DELETE`：
     `/api/books/images/cache?sourceId=…&bookUrl=…`、
     `/api/audio/cache?sourceId=…&bookUrl=…`、
     `/api/bookshelf/cache?sourceId=…&bookUrl=…&clearData=true`（均 **204**）；
   - **没有**任何 IndexedDB 删除（Application → IndexedDB → `legado_offline_db` 的
     `chapters` 里该书的行**仍在**）；
   - **绝不出现**「本机离线正文」这种字样（该段根本没被执行）。

```powershell
# 磁盘自证：该书的图片/音频目录已消失
node -e "const{DatabaseSync}=require('node:sqlite');const db=new DatabaseSync('E:\\Desktop\\legado\\_run-data\\legado.sqlite',{readOnly:true});console.log('manga_image 行数 =',db.prepare('select count(*) c from manga_image').get().c);console.log('audio_cache 行数 =',db.prepare('select count(*) c from audio_cache').get().c);console.log('book_content_cache 行数 =',db.prepare('select count(*) c from book_content_cache').get().c);"
```

### 用例 4：选「清除浏览器缓存」⇒ 服务端**一个字都没动**

1. 先让某本书**在服务端有缓存、且本机有离线正文**。
2. 选「清除浏览器缓存」。
3. **预期表现**：
   - Toast 成功；
   - DevTools → Network：**三个服务端 `DELETE` 一次都没有发出**；
   - IndexedDB → `legado_offline_db` → `chapters`：该书的行**全部消失**；
   - 服务端磁盘上的 `comic/<该书目录>`、`audio/<该书目录>` **原样还在**。

```powershell
# 服务端目录仍在（把 <书名 (哈希)> 换成实际目录名）
Get-ChildItem 'E:\Desktop\legado\_run-data\comic' | Select-Object Name
```

4. 再选「清理服务器和浏览器缓存」⇒ 上面两边**一起**清（四步：图片 → 音频 → 正文 → 本机离线）。

### 用例 5：**只清这一本**（隔离性，最关键的一条）

1. 准备三本书：**同一书源的不同书** + **同一本书号但不同书源**（换源后的书）各一本。
2. 只对其中一本执行「清理服务器和浏览器缓存」。
3. **预期表现**：另外两本的
   - 服务端图片/音频目录**一字未动**；
   - 服务端正文缓存行**仍在**；
   - 本机 IndexedDB 里它们的离线正文**一字未动**。

```powershell
# 交叉核对：三本书的缓存行数在执行前后应只有目标那本变化
node -e "const{DatabaseSync}=require('node:sqlite');const db=new DatabaseSync('E:\\Desktop\\legado\\_run-data\\legado.sqlite',{readOnly:true});for(const r of db.prepare('select source_id, book_url, count(*) c from book_content_cache group by source_id, book_url').all())console.log(r.c, r.book_url.slice(0,60));"
```

### 用例 6：漫画目录抽屉的缓存栏（与小说同构）

1. 打开一本**漫画**（`bookKind=comic`）→ 顶栏点「**目录**」。
2. **预期表现**：目录抽屉**底部固定**着一栏四个按钮（不用滚动就能看到）：
   `本章` / `后 50 章` / `后 100 章` / `全本缓存`；
   下面一行小字：`已离线 N 章 · 绿点表示整章可离线阅读`。
3. 点「本章」⇒ **抽屉不关**，小字变成
   `正在后台缓存，目录里绿点表示该章已可离线阅读`，按钮全部**置灰不可点**（`.cache-range-btn:disabled`）。
4. 缓存跑完后，目录列表里对应章节出现**绿点**，小字回到 `已离线 N 章 · …`。
5. 再点「全本缓存」⇒ 该漫画**整本**进入队列（服务端 pin，GC 永不自动淘汰）。
6. **反向核对**：打开**设置抽屉** ⇒ 里面**没有**「离线缓存」这一节（功能被**搬走**了，不是复制两份）。

### 用例 7：有声书底部功能条**动态均分**

1. 打开一本**听书**（`bookKind=audio`）。
2. **预期表现**：底部功能条的按钮**铺满整行**，每个按钮**等宽**，**右侧没有空档**
   （改动前是写死 5 等分、只剩 4 个按钮 ⇒ 右侧空一大块）。
3. 打开 DevTools → Elements，选中 `.audiobook-bottom-controls`：
   - `display: flex`（**不是** `grid`）；
   - 每个**直接子元素**都是 `button.audiobook-feature`，且样式里 `flex: 1 1 0; min-width: 0`。
4. 把浏览器切到 414px 窄屏 ⇒ 按钮**仍然等宽**，长文案被截断（`…`）而不是把某一份撑宽。

### 用例 8：书架「小说」角标 + 三者互斥

1. 书架页观察每张卡片**右上角**。
2. **预期表现**：
   - **小说**卡片 ⇒ 深绿角标「小说」；
   - **漫画**卡片 ⇒ 橙棕角标「漫画」；
   - **听书**卡片 ⇒ 蓝色角标「听书」；
   - **任何卡片右上角最多只有一个角标**（不会叠两个）；
   - 「本地」角标在**左上角**，与右上角的类型角标互不干扰。

### 用例 9：英/日界面**不再出现中文文案**

1. 顶栏菜单 → 语言 → 切到 **English**（或 **日本語**）。
2. **预期表现**（本批修掉的用户可见 bug）：

| 位置 | en-US 期望 | ja-JP 期望 |
| :--- | :--- | :--- |
| 书架小说角标 | `Novel` | `小説` |
| 书架漫画角标 | `Comic` | `漫画` |
| 书架听书角标 | `Audio` | `朗読` |
| 漫画目录底栏 | `This chapter` / `Next 50` / `Next 100` / `Cache All` | 日本語 |
| 漫画底栏提示 | `{{count}} chapters offline · a green dot means…` | 日本語 |
| 清除缓存弹窗（四处） | 全英文 | 全日本語 |

- [ ] 以上位置**一个中文都不剩**；
- [ ] 切到 **zh-TW** 是繁體（不是简体）；
- [ ] 小说正文、目录、书源规则**不受语言切换影响**（正文不可变）。

---

## 3. 预期日志参考 (Expected Log Output)

```log
# 清缓存是同步的、无长任务；正常只有请求行
[eventLoopGroupProxy-4-1] INFO io.legado.server.Application - 200 OK: DELETE /api/books/images/cache
```

```log
# 有声书/漫画后台缓存（用例 6 点「全本缓存」后）
[GcWorker] INFO ... 音频缓存 GC 巡检：占用 x.xx GiB / 20 GiB
```

> ⚠️ 本机服务日志是 **GBK(936)** 编码。PowerShell 用 `-Encoding UTF8` 读会乱码，
> 需 `[System.Text.Encoding]::GetEncoding(936)`。

---

## 4. 回归与异常检查项 (Sanity & Edge Checks)

- [ ] 清缓存后**阅读进度、书架记录、封面**全部**不受影响**（弹窗文案就是这么承诺的）。
- [ ] 清「正文缓存」后，**目录仍在**（`book_toc_cache` 不被删）⇒ 打开书不会重新拉目录。
- [ ] 清缓存后书架的**缓存进度条回到 0**（前端清完会 `load()` 拉最新状态，不停在旧值）。
- [ ] 缓存**正在进行中**时打开弹窗 ⇒ 按钮 `disabled`，遮罩点不动（防重复提交）。
- [ ] 部分步骤失败（可临时断网造）⇒ Toast 是 **warning** 并**点名**失败的那几段，
      而不是笼统的"失败"；成功的那几段**不会**被重复执行。
- [ ] 漫画缓存栏在**非漫画**书（小说/听书）里**不出现**。
- [ ] 听书播放器的底部功能条：打开播放设置抽屉、睡眠定时、倍速弹窗都**照常**工作。
- [ ] 书架里 `bookKind` 缺失（老数据）⇒ 显示「小说」角标而不是空白。
- [ ] 切换语言后**不需要刷新**页面（i18n 立即生效）；刷新后语言**仍是**所选语言（服务端漫游持久化）。

---

## 5. 自动化验证（可不做，用于交叉确认）

```powershell
cd E:\Desktop\legado\_wt-all
npm --prefix web run check                                  # 类型检查，期望 exit 0
npx tsx web/test/run-all.ts                                 # 前端全套，期望全绿
```

定点跑单组（把 `--test-name-pattern` 留给需要时用）：

```powershell
npx tsx --test web/test/ShelfClearCache.test.ts             # 16 用例：范围模型 + 弹窗结构 + 记账
npx tsx --test web/test/manga-toc-footer.test.ts            # 5 用例：缓存栏在目录抽屉 / 是搬不是复制 / 四语
npx tsx --test web/test/audiobook-bottom-equal-width.test.ts# 4 用例：无写死列数 / flex:1 1 0 / 溢出保护
npx tsx --test web/test/shelf-kind-tag.test.ts              # 7 用例：三角标互斥 / 四语文案 / CSS 几何
```

```powershell
# i18n parity（注意：该用例目前只在 feat/i18n-missing-keys 分支上，见 ADR-032 的状态提示）
cd E:\Desktop\legado\_wt-pr-i18n
npx tsx --test web/test/i18n-parity.test.ts                 # 4 用例：四语存在 / 非空 / 扫描器自检 / 反向 parity
```
