# SESSION-054 订阅源、规则订阅与一键导入（Issue #88）

日期：2026-11-10

## 1. 问题

订阅 `http://yuedu.miaogongzi.net/shuyuan/miaogongziDY.json` 后书源没有更新。

## 2. 根因

报告者提供的文件是开源阅读的订阅源：

```json
{ "sourceName": "喵公子书源管理", "sourceUrl": "http://yuedu.miaogongzi.net/gx.html",
  "singleUrl": true, "sortUrl": "首页::http://yuedu.miaogongzi.net/gx.html", "ruleArticles": "id.content@h3", … }
```

`SourceCodec.parse` 把 `sourceUrl` / `sourceName` 当书源字段别名 ⇒ 它被当成一条没有任何规则的书源落库，界面报「新增 1」。

## 3. 走过的弯路

第一版让服务端识别订阅源后自动抓 `gx.html`、抽链接、全部导入。两处问题：

1. **与上游行为不符**。上游是「订阅源入库 → 内置浏览器打开 → 用户点导入链接 → 预览勾选」，从不自动爬页面。
2. **识别用了字段启发式且写错了**：把 `searchUrl`、`ruleContent` 当作书源专有字段，而上游 `RssSource` 自己就有这两个字段。读上游 `jsonImportType` 后才发现规则只有一句：有 `bookSourceUrl` 是书源，否则有 `sourceUrl` 是订阅源。

教训与 AGENTS.md 既有条目一致：**对齐对端前先读对端源码**。

## 4. 最终方案

见 [`ADR-035`](../decisions/ADR-035-rss-source-as-first-class-entity-and-online-import-dispatch.md)。

## 5. 验证

| 项 | 结果 |
| :--- | :--- |
| 服务端全量 `:server:test` | 698 例，1 例失败：`NetworkSecurityTest` 需解析 `example.com`，沙箱无 DNS，main 上同样失败 |
| 前端 `tsc -b` + `run-all.ts` | 393 例全过 |
| 真实 Chromium 端到端 | 18/18 |

## 6. 未验证

开发沙箱不通外网，`yuedu.miaogongzi.net` 不可达：

- 没见过 `gx.html` 的真实内容，不确定其导入按钮是 `<a href>` 还是 `location.href` 跳转；后者代理层拦不到。
- 「网络导入」「订阅条目导入」「自动更新」对真实地址的拉取没有实测，只用注入的抓取函数做了单测。

## 7. 踩坑

- Kotlin 的块注释**可嵌套**：KDoc 里写 `/sources/import-url/*` 会开启一层新注释，把后面几千行代码吞掉，报错位置离真正原因很远。
- 无头浏览器默认英文区域，界面走了英文文案，中文断言全部假失败；端到端脚本要显式设 `locale: 'zh-CN'`。
- 弹窗有 `.18s` 淡入动画，出现即截图会得到半透明的画面，容易误判成主题变量丢失。
