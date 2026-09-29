---
id: SESSION-034
title: 书源分组管理面板 UI 改版（信息层级重排 + 转义作用域化的骨架重绘）
date: 2026-09-29
type: ui_polish
related:
  - docs/proposals/PROPOSAL-021-book-source-groups-and-group-scoped-search.md
  - docs/decisions/ADR-021-book-source-groups-string-column-and-search-scope.md
  - docs/sessions/SESSION-033-source-group-manager-and-no-auto-group-import.md
  - docs/acceptance/ACCEPT-021-source-groups-and-group-scoped-search.md
---

# 书源分组管理面板 UI 改版

## 1. 起因

`SESSION-033` 把「分组管理」抽成独立面板 `SourceGroupManagerModal`，为了不引入第二套视觉语言，
它**复用**了书架分组弹窗的骨架类（`.group-manage-*` / `.group-item-*` / `.group-list`），只补了
书源特有的选择区。结果是**能用但不好看**，真机截图（改版前）暴露四个具体问题：

| 观感问题 | 具体表现 |
| :--- | :--- |
| 头部信息被浪费 | 一行小字 `共 2 个分组 · 6 个书源（未分组 3）`，数字淹没在句子里，扫不到重点 |
| 三个区块没有层级 | 分组列表 / 目标分组行 / 书源勾选列表**视觉上等权**，看不出「上半看分组、下半归类书源」 |
| 操作区太吵 | 每行两个描边方框图标按钮、全选/全不选/反选三个独立描边按钮，边框线比内容还抢眼 |
| 归类目标行拥挤 | `把书源加入分组 目标 [select] [加入分组] [移出分组]` 挤在一行，标签小、控件高矮不齐 |

## 2. 改法（信息层级 + 视觉，不动行为）

**信息层级**（`SourceGroupManagerModal.tsx`）：

1. 头部右侧新增**概览数字块**（`分组 / 书源 / 未分组`）；`未分组 > 0` 时给强调色 —— 这三个数回答的是
   「现在什么状态」，比塞在一句话里更该被先看到。标题下的小字改成**描述用途**（"按分组收纳书源，搜书时即可按分组收窄范围"）而不是重复数字。
2. 正文拆成两个**显式分区**（`sgm-section`）：`已有分组`（管理）与 `把书源加入分组`（归类），各带一句用途提示；
   下半区整体做成一整块**浅底工作台卡片**（`.sgm-section.group-picker`），与上半区从背景色上就分得开。
3. 分组行加**首字标记**（`sgm-group-avatar`），名称 + `N 个` / `启用 M` 徽标保留原类名与文案（测试与验收手册都锁它们）。
4. 勾选列表里**已分组的徽标用主色**、`未分组` 保持灰色虚线 —— 扫一眼就知道哪些源还没归类。
5. 底部新增**选中摘要**（"已选中 N 个书源" / "勾选书源后可批量归类"），关闭按钮仍在右侧。

**视觉**（`web/src/styles.css`，新增一段 `/* 书源分组管理面板 */`）：

- 面板 `680px → 720px`、圆角 `14px → 18px`；头部/正文/底部内边距统一放宽。
- 行内图标操作改为**隐形按钮**（默认无边框、`--muted` 前景），hover 才浮出底色；删除按钮 hover 走 `--danger` 染色。
- 全选/全不选/反选收成**一个分段控件**（外框 + 内部分段）。
- 筛选框加放大镜图标（`.sgm-search` 绝对定位图标 + `padding-left` 让位）。
- 选中行加 `inset 3px 0 0 var(--accent)` **左侧强调竖条**。
- 空状态从一行字改成「图标 + 标题 + 说明」的 `sgm-empty`。

## 3. 两处必须记住的坑

### 3.1 覆盖共享骨架类必须挂作用域，否则会改脏书架弹窗

`.group-item-row` / `.group-item-info` / `.group-list` / `.group-item-count` 是**书架分组弹窗共用**的类
（`main.tsx:1998` 的书架分组行也在用 `group-item-count`）。因此本面板的所有重绘都写成
`.source-group-manager .group-item-row { … }` 这类**作用域选择器**，绝不直接改全局类。
只有 `.pick-item` / `.group-picker-*` / `sgm-*` 是本面板独有，才允许无前缀。

### 3.2 「改了前端却看到旧界面」有**两条**独立成因，别只怀疑 Service Worker

既有部落知识都在说 PWA 预缓存（`SESSION-027` 的 SW 死配置、`[PWA/更新]` 那条），本次踩到的是**构建侧**的另一条：

> 服务端是 `staticResources("/assets", "static/assets")` / `respondResource("static/index.html")`
> ——**静态资源来自 classpath（jar 内）**。所以源码模式（`gradlew :server:run`）或 `installDist` 起的实例，
> 只改 `web/src` **永远不会**生效；必须重跑 `gradlew :server:installDist`（其 `buildWeb`/`processResources`
> 才会把新的 `web/dist` 打进 jar）并**重启进程**。

本次取证就是按这个顺序走的：`npm --prefix web run build` → `gradlew :server:installDist` → 重启实例 → 截图。

## 4. 验证与取证

| 项 | 命令 / 方式 | 结果 |
| :--- | :--- | :--- |
| 类型检查 | `npm --prefix web run check` | 通过（exit 0） |
| 前端测试 | `npx tsx web/test/run-all.ts` | **166 / 166 通过**（改版前 164；新增 2 条结构锁） |
| 真机 UI 断言 | `npx tsx web/test/e2e-source-groups.ts`（本机 Edge，端口 18083 真实实例） | **16 / 16 全绿**（重构 JSX 后回归通过） |
| 真机截图 | 1440×960 / 414×900 / 夜间模式 | 无横向溢出（`body.scrollWidth == clientWidth == 718`）；夜间模式 `--ink` 为 `#eef1ec`、`--surface` 为 `#292e2b`，**主题变量未丢失** |

新增的 2 条静态渲染断言锁的是**结构**而不是像素：
`header stats, sections and group avatar are present`（概览/分区/首字标记/底部摘要）、
`groupInitial never renders undefined`（空分组名兜底成空串）——它们防的是「下次改版把信息层级悄悄丢掉」。

## 5. 沉淀到部落知识库的条目

1. **改前端但界面没变，先分清「构建侧」与「缓存侧」**：源码模式实例的静态资源在 jar 内，改 `web/src` 必须重跑 `:server:installDist` 并重启；只调 PWA 缓存会白忙。
2. **复用共享骨架类做改版，覆盖必须加作用域**：`.group-*` 是书架弹窗共用的，重绘一律写 `.source-group-manager .xxx`。
3. **面板里的「概览数字」比一句小字更有用**：状态数字（分组/书源/未分组）抽出来做数字块，同时把数字从描述句里删掉，避免同一信息出现两遍。
4. **PS 5.1 的 `Get-Content -Raw` 会按 GBK 解码无 BOM 的 UTF-8 文件**：用它读出中文再发 HTTP（如 GitHub REST API）会得到 422/500，必须用 `[IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)`。同一个文件的字符串长度会骗人（实测 2570 vs 2214）——**长度不一致就是命中了**。

## 6. 提交与 PR（交付记录）

| 项 | 值 |
| :--- | :--- |
| 分支 | `feat/source-groups-and-group-scoped-search`（基于上游 main `020e633`，无需 rebase） |
| 提交 | `f18428a`（作者 `wfanan <wfanan@users.noreply.github.com>`），41 files / +3164 / -301 |
| 推送 | `git push fork feat/...` → `wfanan/legado-server`（本机 git 传输本次一次成功） |
| PR | [#10](https://github.com/lukelzlz/legado-server/pull/10) → `lukelzlz:main`，mergeable=clean |
| CI | `CI Test & Build` ✅ success、`Build Legado Server JAR` ✅ success |

> **CI 结果顺带解决了本机的不确定性**：`ci.yml` 会在 Linux 上跑 `./gradlew :server:test` + 前端 `check`/`test/run-all.ts`，
> 两条 workflow 都 success ⇒ 服务端测试在 Linux 上**全绿**，本机那 8 条 `FileSystemException` teardown 失败**确认为环境噪声**，
> `ApiRoutesHttpTest` 被顶替的断言也已由 CI 覆盖。本机 PowerShell 是 **5.1**（无 `gh` CLI），PR 全程走 REST API + 凭据管理器取 token。
