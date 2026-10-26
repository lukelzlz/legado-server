---
id: ACCEPT-035
title: 大规模书源搜索范围选择器验收手册
proposal: PROPOSAL-035
date: 2026-10-27
author: Agent
---

# ACCEPT-035：大规模书源搜索范围选择器

## 已验证的核心流程

使用 Chrome 真实鼠标操作完成以下流程：

1. 打开书库页，确认“全部书源”默认激活。
2. 打开“选择书源”范围选择器。
3. 勾选两个分组。
4. 输入关键词搜索书源并勾选一个单独书源。
5. 点击“应用”。
6. 点击书库搜索按钮。
7. 确认 WebSocket 搜索请求同时携带两个分组和一个书源 ID。

实际请求范围：

```json
{
  "groups": ["E2E 分组 A", "E2E 分组 B"],
  "sourceIds": ["https://e2e-source-a.example"]
}
```

## 自动化检查

```sh
npm --prefix web run check
npx tsx web/test/run-all.ts
./gradlew :server:test --tests io.legado.server.SourceGroupTest --tests io.legado.server.SourceGroupRoutesTest
```

前端类型检查与 380 项前端测试通过；相关服务端范围解析、候选查询、HTTP/WS 路由测试通过。

完整服务端测试中仅有外部漫画源实时测试 `RealMangaSourceLiveTest` 因要求至少 5 张外部图片而失败，复跑结果一致，与本次搜索范围改动无关。

## 验收结论

用户已通过真实浏览器鼠标流程确认（LGTM）。
