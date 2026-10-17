---
id: ACCEPT-030
title: 书籍变量与听书朗读者切换验收手册
proposal: PROPOSAL-030
adr: ADR-030
date: 2026-10-16
author: Agent
---

# ACCEPT-030: 书籍变量与听书朗读者切换验收手册

> 本手册自包含：任何新人或外部 Agent 从零照做即可跑通。
> 全程只需要一个能播放有声书的书源（示例用 `🍅大灰狼聚合`）。

## 1. 快速开始与环境准备 (Get Started)

```powershell
# 1) 从本 PR 分支构建并启动（脚本会自动选 JDK 21、按需构建 fatJar）
#    刻意用 8090：8080 上可能正跑着有声书（PR #46）的实例，别互相顶掉。
cd E:\Desktop\legado
git -C _wt-bookvar rev-parse --abbrev-ref HEAD          # 期望：feat/book-variables
.\start-server.ps1 -RepoRoot E:\Desktop\legado\_wt-bookvar -Port 8090 -OpenBrowser
```

```powershell
# 2) 不想用脚本时（等价的手工路径）
$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.11_10"
cd E:\Desktop\legado\_wt-bookvar
.\gradlew.bat :server:fatJar
$env:ADMIN_PASSWORD="w71251478"; $env:LEGADO_SECURE_COOKIES="false"; $env:LEGADO_PORT="8090"; $env:LEGADO_DATA_DIR="E:\Desktop\legado\_run-data"
& "$env:JAVA_HOME\bin\java.exe" -jar server\build\libs\legado-server-0.1.0-all.jar
```

浏览器打开 <http://127.0.0.1:8090>，用管理员密码登录。

> ⚠️ 若界面像是旧版：DevTools → Application → Service Workers → 注销并硬刷新（PWA 预缓存会留住旧 bundle）。

## 2. 核心功能验收步骤 (Step-by-Step Verification)

### 用例 1：填音色编号 → 朗读者真的换了（Happy Path）

1. 书架里点一本**听书**书（如《十日终焉》，来源为 `🍅大灰狼聚合`；书籍详情里会列出
   「本书可用的书旗音色」及**数字编号**）。
2. 打开该书的 **「编辑书籍信息」** 弹窗（书架卡片菜单 → 编辑书籍信息）。
3. 在 **书籍变量** 输入框填入音色编号（例：`3`），点保存。
4. **预期表现**：
   - 提示「《十日终焉》信息已更新」；
   - 重新进入阅读器/播放器播放该章 → **朗读者已切换**（不再是默认 `multi_role` 多角色）；
   - 不再需要清缓存（后端 `book.getVariable('custom')` 每次执行规则都读最新值）。

### 用例 2：只改书名不得清掉音色（关键回归）

1. 接着用例 1，再次打开「编辑书籍信息」，**只改书名**，不碰书籍变量输入框，保存。
2. **预期表现**：书架出现新书名；**再次打开弹窗，书籍变量框里仍是 `3`**。

```powershell
# 命令行对照（服务端语义：缺省=不动，传值=合并写入）
curl.exe -s -b $cookie http://127.0.0.1:8080/api/bookshelf | Select-String '"variable"'
```

### 用例 3：清空输入框 → 回到默认音色

1. 打开弹窗，把书籍变量清空（留空），保存。
2. **预期表现**：保存成功；播放时回到书源的默认音色（服务端不再给出 `custom` 值）。

### 用例 4：换设备/重装后设置带得走（备份往返）

1. 设置 → 备份，导出一份备份包（`bookshelf.json` 里每条书应带 `variable` 字段）。
2. 清空数据目录另起一个实例，导入该备份包。
3. **预期表现**：书籍变量**跟着回来**；打开弹窗仍是 `3`，播放仍是同一朗读者。

## 3. 预期日志参考 (Expected Log Output)

```log
# 正常：无 "缺少必要参数"、无 JS 异常
[main] INFO io.legado.server.Application - Application started in 3521 ms
```

```log
# 反例（改动前/未填变量时的典型形态）——如果你看到它，说明变量没生效：
java.log('缺少必要参数')            # 书源 JS 自身打的日志
```

## 4. 回归与异常检查项 (Sanity & Edge Checks)

- [ ] 小说（非听书）书籍的编辑弹窗同样显示书籍变量栏，保存不报错。
- [ ] 老数据库升级后**不报错**（`variable` 列由幂等迁移补齐，无需手工干预）。
- [ ] 手改过/写坏的 `variable` JSON 不会让书架打不开（应退化为"没有变量"）。
- [ ] 书源规则里 `book.getVariable('不存在的键')` 得到**空串**（不是字符串 `null`）。
- [ ] 导入一份**没有** `variable` 字段的旧备份，不会清掉本机已设置的音色。
- [ ] 备份导出文件里能看到 `"variable": "{\"custom\":\"3\"}"`。

## 5. 自动化验证（可不做，用于交叉确认）

```powershell
$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.11_10"
cd E:\Desktop\legado\_wt-bookvar
.\gradlew.bat :server:test --tests "io.legado.server.BookVariableTest" --tests "io.legado.server.BookVariableRoutesTest" -x buildWeb
```
