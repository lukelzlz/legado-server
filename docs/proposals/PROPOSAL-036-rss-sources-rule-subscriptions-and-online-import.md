---
id: PROPOSAL-036
title: 订阅源入库与打开、规则订阅、一键导入协议（对齐开源阅读「订阅」页）
status: implemented # draft | accepted | implemented
date: 2026-11-10
---

# PROPOSAL-036: 订阅源入库与打开、规则订阅、一键导入协议

## 1. 背景

Issue #88：在「书源订阅」里添加 `http://yuedu.miaogongzi.net/shuyuan/miaogongziDY.json` 后书源没有更新。

该地址是开源阅读的**订阅源**（`RssSource`）而不是书源集合：只描述一个网页入口，手机端要在内置浏览器里打开页面、再点页面上的一键导入按钮才会导入书源。本服务没有订阅源这个概念，且 `SourceCodec` 把 `sourceUrl` / `sourceName` 当作书源字段别名，于是订阅源被当成一条没有任何规则的书源静默落库。

## 2. 目标

对齐 `LegadoTeam/legado`（核对版本 `330e606`）「订阅」页的三块能力：

| 能力 | 上游对应 | 本次范围 |
| :--- | :--- | :--- |
| 订阅源入库与打开 | `RssSource`、`RssFragment.openRss` | 导入（地址 / 文件 / 备份）、宫格展示、启停、删除、在内置浏览器中打开入口页 |
| 一键导入协议 | `OnLineImportActivity` | `legado://import/{type}?src=` 与 `yuedu://{host}/importonline?src=`；支持书源、订阅源、替换规则、朗读引擎与自动判别 |
| 规则订阅 | `RuleSub`、`RuleSubActivity` | 名称、类型（书源 / 订阅源 / 替换规则）、自动更新与间隔；「导入」先预览再确认 |

## 3. 不做的事

- **不自动抓取订阅源页面并跟随其中的链接**。上游没有这个行为，用户也无从挑选。
- **文章型订阅源**（`ruleArticles` 等文章规则、文章列表与阅读、收藏、已读记录）。非 `singleUrl` 的订阅源目前只能打开其主页。
- 脚本型 `sortUrl`（`@js:` / `<js>`）：需要订阅源的 JS 运行环境，退回 `sourceUrl`。
- 上游支持而本服务没有对应功能的导入类型（主题、字典规则、目录规则、高亮规则、自动任务、阅读配置、加入书架）：提示「暂不支持」。
- `silentUpdate`：无头服务端没有界面可弹预览，自动更新一律直接落库，故不设该开关。

## 4. 验收

见 [`ACCEPT-036`](../acceptance/ACCEPT-036-rss-sources-rule-subscriptions-and-online-import.md)。
