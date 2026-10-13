---
id: ADR-027
title: 替换净化规则批量事务契约与字符串列分组状态机架构
status: accepted
date: 2026-10-03
---

# ADR-027: 替换净化规则批量事务契约与字符串列分组状态机架构

## 1. 决策背景 (Context)
在 Issue #35 中，用户要求在替换规则页面提供「批量管理」和「分组管理」，并优化详情卡片按钮。
技术选型需要权衡：
1. 后端是沿用分散的 `/toggle`、`/delete-batch`，还是构建统一的批量管道？
2. 分组是新建独立数据表，还是复用类似书源系统的 `group` 字符串列 + nocase 状态机？

## 2. 裁定方案 (Decision)
1. **统一批量事务端点**：
   - 后端新增 `POST /api/replace-rules/batch`，请求载荷形如 `{ action: "enable" | "disable" | "set_group" | "delete", ids: string[], group?: string | null }`。
   - 所有批量操作在单一 SQLite 事务内完成，返回 `{ affected: number }`。
2. **分组管理复用字符串列与解绑语义**：
   - 不新建 `replace_rule_group` 表（避免过度设计与迁移包袱）。
   - 新增 `GET /api/replace-rule-groups`：`SELECT trim("group") as name, count(*) as total, sum(case when is_enabled then 1 else 0 end) as enabled FROM replace_rules WHERE "group" IS NOT NULL AND trim("group") != '' GROUP BY trim("group") COLLATE NOCASE`。
   - 新增 `PUT /api/replace-rule-groups/rename`：将旧组名整体更新为新组名（自动合并）。
   - 新增 `DELETE /api/replace-rule-groups?name=`：仅执行 `UPDATE replace_rules SET "group" = NULL WHERE "group" = ? COLLATE NOCASE`，**软解绑绝不删规则**。
3. **前端状态机与无侵入架构**：
   - 提取 `ReplaceRuleGroupManagerModal`，复用成熟的归类工作台交互；
   - 详情卡片操作按钮重构：将 `.ghost-button` 统一改造为具有明确状态反馈的次级按钮基类，对齐 `.primary-button` / `.secondary-button` 全局规范。

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)
- **备选方案 A：独立建立 replace_rule_group 数据库表与外键级联**
  - *否决理由*：Legado 原始生态的 `replaceRule.json` 导出格式只包含纯字符串 `group` 字段。引入外键表会导致导入导出需要额外反查映射，并在删除分组时极易触发误删外键的灾难性缺陷。
- **备选方案 B：前端纯本地循环发起单条更新**
  - *否决理由*：当用户选定 100 条规则时，并发发起 100 次 HTTP 请求会压垮连接池并引发 SQLite 锁竞争。必须提供原子化的服务端批量接口。

## 4. 后果与权衡 (Consequences & Trade-offs)
- **正面收益**：前后端架构与书源（Source）、书架（Shelf）批量/分组模式 100% 同构，代码直观、可维护性高、零冗余包袱。
- **负面代价**：需在 `Database.kt` 中补充事务性批量更新与分组统计方法，并补齐自动化回归测试。
