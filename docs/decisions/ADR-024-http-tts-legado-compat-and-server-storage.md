---
id: ADR-024
title: 自定义 HTTP TTS 采用 Legado 规范兼容模型、服务端 SQLite 持久化与 Rhino 模板求值
status: accepted
date: 2026-10-01
---

# ADR-024: 自定义 HTTP TTS 采用 Legado 规范兼容模型、服务端 SQLite 持久化与 Rhino 模板求值

## 1. 决策背景 (Context)
Issue #25 要求支持自定义 HTTP 朗读引擎，实现多引擎管理、编辑、测试、导入导出与开源阅读（Legado）`httpTTS.json` 生态互通，并在阅读器朗读设置中支持自由切换。

需要确定的关键架构点包括：
1. **数据模型与协议格式**：是否独立定义新格式，还是直接复用 Legado 原生 `httpTTS.json` 契约？
2. **持久化层选型**：存放在浏览器客户端 localStorage 还是服务端 SQLite？
3. **合成请求与模板渲染位置**：在前端直接 fetch 请求上游第三方，还是在服务端执行模板求值与 HTTP 发送？

## 2. 裁定方案 (Decision)

### 2.1 数据模型：对齐 Legado 原生 `httpTTS.json` 字段规范
定义 `HttpTts` 数据结构：
```kotlin
@Serializable
data class HttpTts(
    val id: Long, // 兼容 Legado 的数字型 ID (毫秒时间戳或负整数)
    val name: String,
    val url: String,
    val header: String? = null,
    val contentType: String? = null,
    val concurrentRate: String? = null,
    val loginUrl: String? = null,
    val loginCheckJs: String? = null,
    val loginUi: String? = null,
    val jsLib: String? = null,
    val enabledCookieJar: Boolean = false,
    val lastUpdateTime: Long = System.currentTimeMillis(),
)
```
无论是单条导出还是批量导出，导出 JSON 完全对齐 Legado 手机端规范，两端即刻免转换互通。

### 2.2 持久化层：服务端 SQLite 新增 `http_tts` 表
在 `server/Database.kt` 中创建表：
```sql
CREATE TABLE IF NOT EXISTS http_tts (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    header TEXT,
    content_type TEXT,
    concurrent_rate TEXT,
    login_url TEXT,
    login_check_js TEXT,
    login_ui TEXT,
    js_lib TEXT,
    enabled_cookie_jar INTEGER NOT NULL DEFAULT 0,
    last_update_time INTEGER NOT NULL
);
```
提供标准 REST API：
- `GET /api/http-tts`: 获取已配置的所有 HTTP TTS 列表
- `POST /api/http-tts`: 新增或更新（按 id upsert）
- `DELETE /api/http-tts/{id}`: 删除指定 TTS
- `POST /api/http-tts/import`: 批量导入 `httpTTS.json`（支持更新或追加）
- `POST /api/http-tts/test`: 连通性测试合成接口，接收文本与 TTS 配置，返回合成后的音频流或错误描述

### 2.3 合成引擎与模板渲染：统一收敛在服务端执行
**Why 服务端渲染与代理？**
1. **彻底免疫前端跨域 CORS 拦截**：大部分第三方自建或云服务 TTS 接口（如百度语音、小众自建服务）未配置开放的 `Access-Control-Allow-Origin`，若前端直接发起 fetch 会直接被浏览器跨域拦截而不可用。
2. **完整支持 Legado JS 沙箱宏与高级语法**：Legado 的 `url` 与 `body` 普遍采用 `{{java.encodeURI(...)}}`、`{{(speakSpeed + 5) / 10 + 4}}` 等 Rhino JS 表达式。服务端现有的 `JsSandbox` 与 `RuleRunner.splitUrlOptions` 已经具备完善的执行环境与安全沙箱，直接注入 `speakText`, `speakSpeed`, `speakVoice` 即可完美执行并替换。
3. **复用已有的音频缓冲与会话流通道**：服务端合成后可直接以音频流响应，前端统一通过 HTML5 Audio 播放。

### 2.4 前端界面交互与弹窗集成
1. **朗读设置弹窗 (TtsSettingsModal)**：
   - 引擎选项中展示：内置 Edge-TTS、系统 Web Speech、以及已配置启用的自定义 HTTP-TTS 列表。
   - 提供「⚙️ TTS 管理」入口按钮。
2. **TTS 管理面板 (HttpTtsManagerModal)**：
   - 列表视图：展示各 HTTP TTS 名称、URL、更新时间及操作（编辑、测试、删除、导出）。
   - 导入/导出栏：支持上传 `.json` 文件导入、文本粘贴导入、全部导出为 `httpTTS.json`。
   - 编辑/新建弹窗：支持名称、URL（含 Legado `,{"method":...}` 选项提示）、ContentType、Header 等常用参数的编辑与即时试听。

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：仅保存在前端 localStorage 中并在前端请求**
  - *否决理由*：受制于浏览器同源策略（CORS），绝大部分 HTTP TTS 接口在前端 fetch 会直接报跨域错误；且跨设备、跨无痕窗口数据会丢失。
- **备选方案 B：私有新 JSON 格式**
  - *否决理由*：用户手头已积累了 Legado 的规则文件，要求转换格式将大幅提升使用摩擦，违背生态高度兼容的设计宗旨。

## 4. 后果与权衡 (Consequences & Trade-offs)
- **正面收益**：
  - 与 Legado 生态 100% 互通，无缝导入导出。
  - 多设备数据同步漫游，管理简单直观。
  - 彻底规避跨域问题，支持复杂表达式模板解析。
- **风险与防线**：
  - 服务端请求外部 TTS 须防范 SSRF 与超时，设置超时时间与响应体积限制。
