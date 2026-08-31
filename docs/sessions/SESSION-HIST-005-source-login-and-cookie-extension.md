# SESSION-HIST-005: 书源全场景凭据获取、Chrome 扩展穿透同步与直接填入交互

- **会话日期**：2026-08-31
- **分支/提交**：`master` (`16df54c`)
- **议题**：书源全场景登录凭据获取（突破 HttpOnly / CSP / SOP 限制）、Chrome/Edge 扩展同步、万能 Cookie-Editor JSON 解析与无可视化表单源直显 Cookie 卡片交互

---

## 1. 现象与深度排查推演 (Investigation & Root Cause)

在书源生态中，存在大批需要网页登录的小说站点。在实现登录鉴权与 Token 传递时，遇到以下三大核心安全障碍：
1. **`HttpOnly` 标记隔离**：站点鉴权 Cookie（如 `session_id`, `auth_token`）标有 `HttpOnly`，网页内部的普通 JS、iframe 或 Bookmarklet 无法通过 `document.cookie` 读取。
2. **CSP (Content-Security-Policy) 与同源策略拦截**：小说站配置严格的 `connect-src` 规则，导致跨域 `fetch('http://127.0.0.1:8080')` 直接被浏览器安全机制阻断。
3. **混合内容 (Mixed Content)**：HTTPS 小说站向本地 HTTP 阅读服务回传数据时受浏览器混合内容策略拦截。
4. **部分书源无可视化 `loginUi`**：许多书源仅定义了 `loginUrl` 或需手工填入 Cookie，弹窗若只显示“未定义可视化表单”会导致交互断层。

---

## 2. 最终落地的工程级解法 (Final Solution)

### A. 专属轻量浏览器扩展 (Chrome / Edge Extension - Manifest V3)
- 源码位置：`extensions/chrome/`
- 机制：借助 `chrome.cookies.getAll({ url: tab.url })` 拥有浏览器特权，**100% 穿透 `HttpOnly`** 读取目标站全量 Cookie；扩展独立的 Background 上下文**彻底免疫目标站 CSP 与 Mixed Content 拦截**，实现秒级一键同步。

### B. 服务端万能 Cookie 解析引擎 (Universal Cookie Parser)
- 在 `Database.kt` 实现万能解析：
  - 自动识别并解析 **Cookie-Editor 导出的标准 JSON 数组**：`[{"name": "...", "value": "..."}]`；
  - 自动识别并解析 **Headers JSON 对象**（如 `{"Authorization": "...", "Cookie": "..."}`）；
  - 自动识别并解析 **Netscape HTTP 格式** 与 **标准 `k1=v1; k2=v2` 字符串**；
  - 自动按 host 合并增量 Cookie，且通过 `parseCookieString` 严格归一化避免脏数据。

### C. Ktor 路由规范与 DTO 序列化修复
- 在 `Models.kt` 建立 `@Serializable data class SourceLoginCookieResponse(val ok: Boolean, val message: String?, val count: Int)`，杜绝 `Map<String, Any>` 在 `kotlinx.serialization` 中的序列化异常。
- 注册 `POST /api/sources/{id}/login-cookie` 接口，支持 CORS 跨域请求与按 URL/Host 模糊路由书源，并增加详尽的容器应用日志 `call.application.log.info`。

### D. 前端无表单源直显 Cookie 输入卡片
- 在 `SourceLoginModal.tsx` 中，当 `loginUi` 为空时，直接在弹窗主体中嵌入可视化的 Cookie / Token 多行文本卡片，支持一键粘贴 Cookie-Editor JSON、直接保存、清除已存凭据与展示已持久化的登录头状态。

---

## 3. 沉淀的教训与部落知识 (Lessons Learned)

1. **`kotlinx.serialization` 严禁使用 `Map<String, Any>`**：Kotlinx Serialization 默认不支持非多态的 `Any` 类型值序列化。接口响应必须使用强类型 `@Serializable data class`。
2. **Cookie 写入 CookieJar 前必须强制归一化**：用户或外部插件可能传入原始 JSON 数组、JSON 对象或多段文本，如果直接写入 `jar[host]` 会导致 Cookie 格式被污染为 JSON 字符串。必须统一走 `parseCookieString` 提取 map 后再格式化为 `k1=v1; k2=v2` 存库。
3. **书源无可视化表单时的极简路径**：优先直出 Cookie / Token 输入框，减少二级菜单跳跃路径。
