---
id: PROPOSAL-010
title: 替换净化规则引擎与社区规则库导入体系
status: accepted
author: Antigravity
date: 2026-09-12
---

# PROPOSAL-010: 替换净化规则引擎与社区规则库导入体系

## 1. 业务背景与问题痛点

在网文阅读生态中，由于源站防爬混淆（如反义词对调字典 `大丑 <-> 小丑`、`魔男 <-> 魔女`、`少萝茜 <-> 多萝茜`）、VIP付费章节“黑吃黑”二次采集、盗版站机械敏感词过滤以及源站广告注水，抓取到的书籍正文经常出现系统性错别字、乱码词和嵌入式牛皮癣广告。

目前 `legado-server` 仅支持书源自身定义的 `ruleContent.replaceRegex`，缺乏用户维度的全局/单书/单源替换净化规则管理与执行机制，无法消费 Legado 社区海量积累的精校替换规则库（如夜雨聆风、破冰、肥猫等规则订阅包）。

---

## 2. 目标与非目标 (Goals & Non-Goals)

### 核心目标 (Goals)
1. **深度兼容 Legado 替换净化协议**：
   - 完整支持 Legado 标准 `ReplaceRule` 数据结构（名称 `name`、匹配正则 `pattern`、替换内容 `replacement`、正则开关 `isRegex`、作用域 `scope`、排除范围 `excludeScope`、启停开关 `isEnabled`、排序优先级 `order` 与分组 `group`）。
   - 支持 Legado 替换规则中 `@js:` / `<js>` 高级脚本替换能力（在 Rhino JS 沙箱中安全执行复杂字典翻转）。
2. **多层级作用域精准匹配**：
   - **全局生效**（`scope` 为空）：适用于通用错别字、通用去广告规则。
   - **书籍级生效**（`scope` 匹配书名/作者）：如针对《宅魔女》的反义词字典，仅在阅读该书时激活，杜绝误伤其他书籍。
   - **书源级生效**（`scope` 匹配 `bookSourceUrl` 或源名称）：针对特定恶劣源站的专项清洗。
3. **服务端清洗管道闭环**：
   - 在 `RuleRunner.content()` 提取后自动应用匹配的净化规则；
   - 清洗后的干净正文直接沉淀到 `book_content_cache`，使**在线阅读、离线整书缓存、TTS 连续朗读、TXT/EPUB 导出**全部享受净化效果。
4. **规则导入导出与网络订阅**：
   - 支持从网络 URL 订阅/一键导入社区规则包（兼容直接导入 JSON 数组及 `{ data: [...] }` 包装）；
   - 支持本地 JSON 文件的批量导入与按需导出。
5. **现代 Web 客户端可视化管理与阅读器快捷净化**：
   - 提供独立的【替换净化】管理视图（搜索、启停、增删改查、排序、导入导出）；
   - 在阅读器设置抽屉中提供【本章/本书净化规则】快捷入口，支持用户遇到错别字时一键创建局部替换规则并秒级重载正文。

### 非目标 (Non-Goals)
- 本次不对书籍正文进行耗时巨大的 AI 大模型逐句实时润色（保持离线纯 JVM / 正则 / JS 毫秒级性能）。
- 不破坏现有 `book_content_cache` 存储格式，净化在入库与无缓存直出环节执行。

---

## 3. 核心用户故事 (User Stories)

### Story 1: 遇到反爬错别字一键精准修复 (单书反混淆)
- **场景**：用户在阅读《宅魔女》第 424 章时，发现正文中“小丑”被篡改为“大丑”、“多萝茜”被篡改为“少萝茜”。
- **操作**：用户打开阅读器菜单中的【替换净化】，添加一条针对当前书籍（`scope: 宅魔女`）的正则/JS替换规则。
- **预期结果**：点击保存后，当前章节正文瞬间刷新为干净修正后的正文；同时后台重新读取并缓存清洗后的章节。

### Story 2: 批量导入社区十万级精校规则库
- **场景**：用户复制了社区著名的“夜雨聆风替换规则”或“破冰精校库”订阅链接。
- **操作**：在管理面板点击【网络导入】，粘贴 URL 并点击同步。
- **预期结果**：系统自动拉取并解析 JSON，批量导入并去重，清晰展示规则总数与分类分组。

### Story 3: 全局去牛皮癣广告
- **场景**：部分转码站正文中夹带 `关注微信公众号 xxx` 或 `app下载地址` 等推广文本。
- **操作**：用户配置一条全局正则 `关注微信公众号[^\n]*` -> 空。
- **预期结果**：所有书籍章节在加载时自动剔除该广告行，阅读排版保持整洁。

### Story 4: TTS 听书与离线缓存同步受益
- **场景**：用户开启 TTS 服务端音频流听书或整书离线下载。
- **预期结果**：TTS 引擎合成的语音与离线下载的文本为已净化后的内容，不会念出“大丑阁上”或广告网址。

---

## 4. 架构设计与协议定义

### 4.1 数据模型与存储 (`replace_rule` 表)

```sql
CREATE TABLE IF NOT EXISTS replace_rule (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    group_name TEXT,
    pattern TEXT NOT NULL,
    replacement TEXT NOT NULL DEFAULT '',
    is_regex INTEGER NOT NULL DEFAULT 1,
    scope TEXT,
    exclude_scope TEXT,
    scope_title TEXT,
    is_enabled INTEGER NOT NULL DEFAULT 1,
    sort_order INTEGER NOT NULL DEFAULT 0,
    timeout_ms INTEGER NOT NULL DEFAULT 3000,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_replace_rule_enabled ON replace_rule(is_enabled, sort_order ASC);
```

### 4.2 REST API 契约

| 方法 | 路径 | 描述 |
| :--- | :--- | :--- |
| `GET` | `/api/replace-rules` | 获取替换规则列表（支持 `scope`, `group`, `q` 筛选） |
| `POST` | `/api/replace-rules` | 新建替换规则 |
| `PUT` | `/api/replace-rules/{id}` | 更新单条替换规则 |
| `DELETE` | `/api/replace-rules/{id}` | 删除单条规则 |
| `POST` | `/api/replace-rules/toggle` | 批量启用/禁用规则 |
| `POST` | `/api/replace-rules/import` | 批量导入规则（支持 JSON 文本或 `url` 订阅） |
| `GET` | `/api/replace-rules/export` | 导出指定或全部规则为 Legado 标准 JSON 格式 |
| `POST` | `/api/replace-rules/clean-preview` | 净化调试预览（传入规则与样例正文，实时预览清洗前后对比） |

---

## 5. 验收基准 (Acceptance Criteria)

- [ ] **服务端单测覆盖**：
  - 覆盖文本替换、正则捕获组 `$1` 替换、`@js:` / `<js>` 沙箱脚本替换。
  - 覆盖 `scope` / `excludeScope` 匹配与过滤逻辑。
  - 覆盖异常正则熔断（超时/死锁保护）。
  - 覆盖导入 Legado 官方导出格式与 BOM 头兼容。
- [ ] **端到端效果验证**：
  - 针对《宅魔女》反义词反爬样本进行替换测试，确认“大丑阁上”精准还原为“小丑阁下”。
  - 离线缓存与 TTS 朗读正文同步输出净化后文本。
- [ ] **Web 界面与交互验证**：
  - 前端类型检查与自动化测试全部通过（`npm run check` & `run-all.ts`）。
  - 在阅读器内可直观查看当前生效规则并一键创建新规则。
