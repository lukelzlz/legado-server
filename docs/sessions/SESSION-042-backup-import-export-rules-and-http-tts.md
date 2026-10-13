---
id: SESSION-042
title: 备份导入导出补全替换规则与 HTTP TTS 并修复大小写静默失败
date: 2026-10-03
author: Agent
tags: [backup, webdav, httptts, replacerule, zip, case-sensitivity]
branch: pr34
worktree: ../joyful-galileo-pr34
---

# SESSION-042: 备份导入导出补全替换规则与 HTTP TTS 并修复大小写静默失败

## 1. 现象与根因剖析 (Investigation & Analysis)
- **初始缺陷**：
  1. 备份导出时仅包含 `bookGroup.json`, `bookmark.json`, `bookshelf.json`, `bookSource.json` 4 项，用户在 Web 端维护或手机端同步的 `replaceRule.json`（替换净化规则）与 `httpTTS.json`（自定义 HTTP 朗读引擎）未被纳入导出备份，导致在设备间迁移时配置丢失；
  2. 导入解析时，`readSection(zip, entries, fileName)` 旧实现为：
     ```kotlin
     val entry = entries.firstOrNull { it.name.substringAfterLast('/').lowercase() == fileName } ?: return null
     ```
     包内文件名被转成全小写（如 `"httptts.json"`），但未对传入的 `fileName` 转小写。当调用方传入带驼峰的文件名（如 `"httpTTS.json"`）时，`"httptts.json" == "httpTTS.json"` 判定恒为 `false`，导致对应 section **静默返回 null**，整段数据被完全跳过且控制台与界面**毫无报错**。

## 2. 最终落地的正确解法 (Final Solution)
- **双边小写归一化比对**：
  在 `BackupImporter.kt` 中统一使用 `val wanted = fileName.lowercase()`，消除 Zip 打包端与调用端大小写风格差异导致的静默匹配失败。
- **备份导出补齐 6 项文件**：
  在 `BackupExporter.kt` 补齐 `replaceRule.json` 与 `httpTTS.json`：
  - `replaceRuleEntry` 字段名严格对齐真实 Legado 备份（`backup2026-09-30-PEPM00.zip`），并将规则 `id` 智能转为数字类型（手机端兼容）；
  - `httpTtsEntry` 字段名严格对齐真实备份，并额外保留服务端特有的 `jsLib` 字段以防回环丢失。
- **双端全量测试护航**：
  - 新增 `BackupHttpTtsImportTest` 锁定真实备份格式的入库行为；
  - 同步更新 `BackupExporterTest` 与 `WebDavRoutesTest` 的 6 项导出断言。

## 3. 沉淀的教训与部落知识 (Lessons Learned)
- **[备份导入/Zip] 文件名比较必须双侧小写归一化，单侧小写会导致驼峰文件名静默返回 null**：
  在从 Zip 文件中按需抓取条目时，若只把 ZipEntry 的名称转成小写，却直接与大小写混合的请求文件名比对，会导致条件恒为假。表现为整类数据（如 TTS、书源）完全未导入且系统不报任何异常。必须使用 `it.name.substringAfterLast('/').lowercase() == fileName.lowercase()`。
