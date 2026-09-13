---
id: SESSION-011
title: 替换净化规则引擎、反爬混淆还原与Rhino沙箱执行管道
date: 2026-09-12
author: Agent
tags: [replace-rules, anti-theft, crawler-obfuscation, rhino-sandbox, regex, sqlite]
---

# SESSION-011: 替换净化规则引擎、反爬混淆还原与 Rhino 沙箱执行管道

## 1. 现象与排查推演 (Investigation & Analysis)
- **初始现象**：使用部分小说抓取站（如起点等盗版镜像源）阅读《宅魔女》等作品时，正文中出现大量文字颠倒混淆问题（例如“大丑”代替“小丑”、“魔男”代替“魔女”、“少萝茜”代替“多萝茜”、“阁上”代替“阁下”、“眼外”代替“眼里”等）。
- **根因推演**：
  - 盗版抓取源在 VIP 章节部署了成对反义词反爬混淆（服务端将成对高频词对调，前端用 JS 动态翻转）。纯爬虫抓到的正文是未翻转的生肉。
  - 需要在无头服务端建立与 Legado 规范完全兼容的 **替换净化规则引擎（ReplaceRule Engine）**，在抓取入库与阅读响应管道中实施清洗。
- **架构决策与设计**：
  - 服务端在 `RuleRunner.content()` 与 `BookCacheService` 离线缓存入库前执行 `ContentProcessor.processContent`，使在线阅读、整书离线缓存、TTS 听书全链路一次性受益。
  - 支持纯文本、正则捕获组 `$1` 与 `@js:` / `<js>` 沙箱动态脚本。
  - 采用 Rhino JS 沙箱执行字典映射（`const map = {...}; return map[result] || result;`），微秒级单次正则扫描完成全局精准翻转。

## 2. 尝试过的无效方案与踩坑 (Failed Attempts & Why)
- *尝试方案 1*：编写多条两两互换的正则规则（如第一步 `小丑->临时占位符`，第二步 `大丑->小丑`，第三步 `临时占位符->大丑`）。
  - *失败原因*：规则链膨胀严重，且多轮顺序替换极易产生二次误伤与性能开销。
- *尝试方案 2*：Rhino JS 沙箱直接执行脚本字符串 `const map = ...; return map[result] || result;`。
  - *失败原因*：Rhino 在顶层作用域遇到 `return` 语句会抛出 `SyntaxError: return not in function` 导致求值中断。
  - *正确解法*：在 `JsSandbox.eval` 中自动检测 `\breturn\b`，若存在则包装为立即执行匿名函数 `(function(){\n$cleanScript\n})()` 执行。
- *尝试方案 3*：单元测试中使用 JUnit 5 (`org.junit.jupiter...`) 注解。
  - *失败原因*：项目整体配置为 JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`)，导致 `compileTestKotlin` 报无法解析符号。统一回归 JUnit 4。

## 3. 最终落地的正确解法 (Final Solution)
1. **纯 JVM 规则引擎 (`ContentProcessor.kt`)**：
   - 支持包含作用域 (`scope`) 与排除作用域 (`excludeScope`)，支持逗号/换行分隔的多书名与书源 URL 匹配。
   - 正则匹配支持正则分组替换与 `@js:` 动态计算。
2. **沙箱增强 (`JsSandbox.kt`)**：
   - 自动识别脚本中的 `return` 语句并包装为 IIFE，安全注入 `result`, `bookName`, `title` 变量。
3. **数据库与数据通道 (`Database.kt`, `RuleRunner.kt`, `BookCacheService.kt`)**：
   - 新增 `replace_rule` 表及索引，支持单条/批量 CRUD、启停切换、按作用域秒级过滤、批量导入/导出（兼容 Legado 社区 JSON 规则包格式）。
4. **现代化 Web 管理与阅读器集成 (`ReplaceRulesModal.tsx`, `ReaderScreen.tsx`)**：
   - 顶部菜单独立管理中心，支持多维过滤、网络订阅 URL 导入、JSON 文本导入与实时测试沙箱。
   - 阅读器工具栏一键配置当前书专用规则，保存后视图即刻刷新。

## 4. 沉淀的教训与部落知识 (Lessons Learned)
- **[替换规则/净化] Rhino JS 沙箱顶级 return 包装与反爬反义词对调字典**：Legado 生态中的 `@js:` 替换规则普遍使用 `return map[result] || result` 组织代码。Rhino 沙箱在顶级执行 `return` 时会报 `return not in function` 语法错误，沙箱必须检测并在必要时将代码包装进 `(function(){ ... })()` 匿名闭包执行。
- **[替换规则/管道] 缓存前置清洗原则**：替换净化规则必须在 `RuleRunner.content()` 与 `BookCacheService` 离线下载落库前执行，避免脏文本污染离线缓存，同时使后续 TTS 朗读自动获得清洗后的正文。
