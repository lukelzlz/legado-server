# ACCEPT-036 阅读器书签（添加 / 列表 / 跳转 / 编辑 / 删除 / 备份）

> 一次性验完：**小说阅读器**的书签全链路 + 备份导出带书签。
> 服务端：`E:\Desktop\legado` 起 8080；前端产物已打进 jar（无需另起 dev server）。

## 0. 准备

```powershell
cd E:\Desktop\legado
# 起服务（源码比 jar 新会自动重建；已有实例会被替换）
.\start-server.ps1 -Rebuild -StopExisting
# 健康检查
curl.exe -s -o NUL -w "%{http_code}`n" http://127.0.0.1:8080/healthz    # 期望 200
```

浏览器开 `http://127.0.0.1:8080`，用管理员密码登录（默认在 `start-server.ps1 -Password`）。

## 1. 底栏「添加书签」（手机宽度 / 窄窗口）

1. 书架点开任意一本**小说**（本地 TXT 也行，不依赖网络书源）。
2. 点正文中间唤出顶栏与底栏。
3. 底栏现在有 **5 格**：目录 / 换源 / 朗读 / **添加书签** / 设置，均匀分布。
4. 点「添加书签」→ 右上角出现 toast「已添加书签」。

✅ 判据：5 格均分、无挤压；toast 出现。

## 2. 顶栏「书签」按钮（在设置左边）

1. 顶栏右侧按钮依次为：换源 / **书签** / 阅读设置 / 朗读本章。
2. 点「书签」→ 弹出书签列表：标题是书名，副标题「共 N 条」，右上角有「添加书签」。
3. 空书的空态文案是「暂无书签」。

✅ 判据：书签按钮**在设置之前**；弹窗能开、条数正确。

## 3. 跳转 / 编辑 / 删除

1. 在正文里**滚到中间**，点弹窗内「添加书签」→ 列表出现第二条（章名 + 一段原文摘要 + 日期）。
2. 关闭弹窗，滚回顶部，再打开列表，**点这条书签** → 弹窗关闭，正文回到刚才那一屏。
3. 点条目右侧**铅笔**：行内出现「原文」「备注」两个输入框，填备注后点「保存」→ 列表里出现备注行。
4. 点条目右侧**垃圾桶** → 行内出现「确认删除这条书签？」+「删除 / 取消」；点「删除」→ 该条消失。

✅ 判据：跳转落点与加书签时是同一屏；备注保存后立刻可见；删除有二次确认。

## 4. 「书签」一级页面（左书右签 + 两边批量）

1. 顶栏（书库 / 发现 / **书架 / 书签** / 书源 / 订阅 / 规则 / 文件）点「书签」→ 进入 `#bookmarks`。
2. 左栏：每本**有书签的书**一行，显示封面（不在书架的书显示首字占位）、书名、作者、`N 条`；
   顶部有搜索与「批量管理」。左栏**不要求书在书架上**（导入备份里那些"书不在架"的书签也在）。
3. 点左边任意一本 → 右栏显示它的书签（章名 / 原文摘要 / 备注 / 日期），行内可编辑（原文+备注）与删除；
   右栏顶部还有「删除本书全部书签」。
4. **右栏批量**：点右栏「批量管理」→ 勾选（或全选）→「删除选中」→ 确认 →
   书签删光后**这本书会立刻从左栏消失**。
5. **左栏批量**：点左栏「批量管理」→ 勾选若干本书 →「删除所选书籍的书签」→ 确认 →
   这些书连同它们的全部书签一起消失，其它书不受影响。

✅ 判据：左栏只有"还有书签的书"；右栏只显示选中书的书签；任一侧删完，左栏对应书立即消失；
浏览器窗口缩到手机宽度时**右栏依然可见**（规则页那条 `display:none` 不能把书签页的右栏吃掉）。

## 5. 备份导入导出带书签

页面「WebDAV」→「立即导出」后打开导出的 zip 看 `bookmark.json`；或者一条命令跑完接口 + 归档核对：

```powershell
# 仓库自带脚本：加书签 → 列表 → 改备注 → 导出备份 → 在 zip 的 bookmark.json 里找它 → 删除 → 权限校验
cd E:\Desktop\legado\legado-server
.\scripts\verify-bookmark.ps1
# 换实例：$env:LEGADO_BASE_URL / $env:LEGADO_ADMIN_PASSWORD
```

期望输出（关键几行）：

```
ADD   id=178 chapterPos=1234 snippetMatch=True
ADD*  id=178 sameId=True
LIST  count=1 chapterNameMatch=True
EDIT  noteMatch=True chapterPosKept=True
EXPORT file=backup2026-11-01-web.zip bookmarks=48
ZIP   entries=48 found=True
ZIP*  nameMatch=True noteMatch=True pos=1234 time=1793512226067
DEL   first=204 again=404 afterCount=0
AUTH  read=401 (expect 401) write=401 (expect 401)
```

✅ 判据：`ZIP ... found=True`（新加的书签出现在导出的 `bookmark.json` 里）；
同一位置重复添加 `sameId=True`（不产生重复行）；删除后列表为空；未登录 401。
脚本跑完会自己删掉那条书签，不留垃圾。

## 6. 自动化验证（可选，一分钟）

```powershell
cd E:\Desktop\legado\legado-server
# 前端：阅读器书签（15 条）+ 「书签」页（13 条）纯函数 / 结构契约 / i18n
npx tsx web/test/bookmark.test.ts
npx tsx web/test/bookmark-page.test.ts
# 前端全量
npx tsx web/test/run-all.ts
# 服务端：书签 HTTP 契约 + 无作者去重 + 概览/批量删除 + 备份导出
# 另跑两条会被"书签改为全量落库"影响的备份用例：
$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.11_10"
.\gradlew :server:test --tests "io.legado.server.BookmarkRoutesTest" --tests "io.legado.server.BackupGroupAndBookmarkTest" --tests "io.legado.server.RealBackupGroupAndBookmarkTest"
```

真浏览器端到端（需要服务在跑 + Edge/Chrome）：

```powershell
$env:CHROME_BIN="C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$env:TEST_BASE_URL="http://127.0.0.1:8080"
$env:ADMIN_PASSWORD="<管理员密码>"
$env:SHOT_DIR="E:\Desktop\legado\_artifacts"
npx tsx web/test/e2e-bookmark-browser.ts
```

期望结尾：`ALL BOOKMARK E2E CHECKS PASSED`（13 步：阅读器内加/改/删/跳 9 步 +
「书签」页左书右签 4 步，含「书签删光后这本书从左栏消失」与「右栏在手机宽度仍可见」）。
脚本自己会清掉它造的联调书与书签，不会污染真实数据。

## 回滚

```powershell
git checkout -- web/src server/src
# 或直接切回 main 工作区并 .\start-server.ps1 -Rebuild
```
