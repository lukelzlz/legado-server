---
id: ADR-035
title: 订阅源作为独立实体入库，一键导入按类型分发，不自动跟随页面链接
status: accepted # proposed | accepted | superseded | deprecated
date: 2026-11-10
---

# ADR-035: 订阅源作为独立实体入库，一键导入按类型分发，不自动跟随页面链接

## 1. 决策背景（Context）

Issue #88 暴露出本服务把订阅源（`RssSource`）误当书源。第一版修复尝试「识别订阅源 → 服务端抓入口页 → 抽取导入链接 → 全部导入」，与上游行为不一致且用户无法挑选，已废弃。本 ADR 记录对齐上游后的取舍。

## 2. 裁定方案（Decision）

### 2.1 类型判别照抄上游，删除 `sourceUrl` 书源别名

上游 `jsonImportType` 的规则是：有 `bookSourceUrl` ⇒ 书源；否则有 `sourceUrl` ⇒ 订阅源。`SourceCodec.parse` 不再把 `sourceUrl` / `sourceName` 当书源字段，遇到「有 `sourceUrl` 而无 `bookSourceUrl`」直接拒收并说明这是订阅源。

- **不采用字段特征启发式**（如看有没有 `sourceIcon` / `singleUrl`）。上游 `RssSource` 自身就有 `searchUrl`、`ruleContent`，任何「书源专有字段」清单都会误判。
- 代价：旧版以 `sourceUrl` 作主键的书源不再被接受。按仓库「不保留向后兼容性」原则接受。

### 2.2 订阅源独立成表，内置浏览器会话用 `rss::` 命名空间

新表 `rss_source`，主键为 `sourceUrl`（与上游一致），`payload` 原样保存以便备份回放。

内置浏览器（`WebViewProxy`）的票据、Cookie、代理路径都以一个字符串 id 为键。订阅源复用同一套路由，id 取 `rss::<sourceUrl>`：

- 不新建第二套代理路由（否则两条代码路径）。
- 前缀隔离了订阅源与书源的 Cookie 与票据，即使两者 URL 相同也不串号。
- 入口地址按上游 `getSingleUrl`：`sortUrl` 第一项（`名称::地址` 取地址），否则 `sourceUrl`。

### 2.3 一键导入：代理层只负责「认出并回报」，分发在宿主

`injectBootstrap` 的捕获阶段点击监听从只认 `yuedu://booksource/importonline` 扩为两个协议的全部形态，回报 `{url, kind}`。类型到动作的映射放在前端 `onlineImportRoute`：

| kind | 去向 |
| :--- | :--- |
| `bookSource` | 既有的预览勾选弹窗（票据两步式，ADR-023） |
| `rssSource` / `replaceRule` / `httpTts` | `POST /api/online-import` 先 `dryRun` 回报类型与条数，确认后落库 |
| `auto` 或缺省 | 服务端按内容判别后再走上面两条 |
| 其它 | 提示暂不支持 |

非书源类型用「dryRun + 再拉一次」而不是票据缓存：这三类集合通常很小，换来的是服务端无状态；内容仍只由服务端抓取，客户端无法绕过 SSRF 与体积上限。

### 2.4 规则订阅：手动走预览，自动更新直接落库

`source_subscription` 加 `name` / `type` / `auto_update` / `update_interval`（小时）。「导入」按钮走 2.3 的同一入口；定时器每 10 分钟检查一次，只更新「开启自动更新且已到间隔」的条目。

- 既有行的列默认值取「每 6 小时自动更新的书源订阅」，即加列前的实际行为。
- 订阅类型与内容不符时（典型：把订阅源地址订成书源）**报失败并说明实际类型**，不记成「成功 0 条」。

## 3. 已知限制

- 页面用 `location.href = 'legado://…'` 跳转时代理层拦不到（仓库既有限制，语言层面无法改写 `location` 赋值）。
- `rss_source` 表与 Cookie 以 `sourceUrl` 为键；订阅源改了 `sourceUrl` 即视为新条目（与上游一致）。
