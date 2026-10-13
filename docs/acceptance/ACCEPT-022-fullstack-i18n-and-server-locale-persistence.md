# 验收手册 ACCEPT-022: 全栈国际化与语言服务端漫游持久化

> 状态：**待用户验收**（AI 侧已完成 `[Tested]`）
> 关联：`docs/proposals/PROPOSAL-022-fullstack-i18n-and-server-locale-persistence.md`、`docs/decisions/ADR-022-react-i18next-and-ktor-accept-language-resolution.md`、`docs/sessions/SESSION-035-fullstack-i18n-and-server-locale-persistence.md`

## 1. 交付目标概述

1. **四国语言全栈覆盖**：
   - 简体中文（`zh-CN`，系统默认）
   - 繁體中文（`zh-TW`）
   - English（`en-US`）
   - 日本語（`ja-JP`）
2. **前端语言即时切换与无缝体验**：
   - 在顶栏用户菜单内新增直观的 2×2 语言选择网格，点击任意语言无需刷新页面即可全局无感切换；
   - 导航标签（书架/书库/书源/订阅/规则/文件）、操作按钮、登录表单、搜索范围条和 Toast 提示均已完成国际化。
3. **服务端错误本地化与漫游持久化**：
   - 语言偏好设置存入 SQLite `app_setting`，登录时自动同步漫游，换机或跨浏览器均可保持语言一致；
   - 服务端根据请求头 `Accept-Language` 自动本地化 `ApiError` 响应文案（如密码错误、CSRF 缺失等），同时 `code` 严格保持英文标识不变；
4. **动态生态不可变**：
   - 小说书籍、目录、正文、书源规则与外部脚本保持原汁原味，不作任何机翻污染。

---

## 2. 自动化验证（最短命令集）

```bash
# 1. 前端类型检查与自动化测试（170 用例全绿，含 4 条本次新增）
npm --prefix web run check && npx tsx web/test/run-all.ts

# 2. 前端静态构建打包验证
npm --prefix web run build

# 3. 本次相关的服务端国际化用例（含 Accept-Language 匹配与路由落库）
./gradlew :server:test --tests "io.legado.server.I18nLocaleAndErrorTest" -x :server:buildWeb

# 4. 全量服务端自动化测试（比对基线零回归）
./gradlew :server:test -x :server:buildWeb

# 5. 真实浏览器端到端自动化验收测试（20 项断言）
npx tsx web/test/e2e-i18n-browser.ts
```

---

## 3. 手工 UI 验收步骤

### 场景 A：前端语言切换与界面响应
1. 启动服务（或本地 Docker 启动）并访问 Web 端 `http://127.0.0.1:8080/`。
2. 点击右上角功能菜单（汉堡按钮或用户头像），展开下拉面板：
   - 可以看到「界面语言 / Language」区域，呈现 `简体中文`、`繁體中文`、`English`、`日本語` 4 个选项按钮；
   - 当前语言按钮具备高亮选中状态。
3. 点击 **`English`**：
   - 顶栏导航即时变为 `Bookshelf`、`Discover`、`Sources`、`Feeds`、`Rules`、`Files`；
   - 搜索栏下方的分组筛选范围即时变为 `All Sources`、`Ungrouped`；
   - 操作按钮变为 `Import`、`Batch`、`Health Check` 等。
4. 点击 **`繁體中文`** 或 **`日本語`**：
   - 界面文字瞬间切换为繁体（如「書架」、「發現」、「檔案」）或日语（如「本棚」、「探索」、「ルール」）。

### 场景 B：语言偏好服务端持久化与跨设备漫游
1. 在当前浏览器中选择语言为 **`English`**。
2. 打开浏览器 DevTools 的 Network 面板，可以看到触发了 `PUT /api/settings/locale`，请求体为 `{"locale":"en-US"}`。
3. 按 `F5` 刷新页面，或者在新的无痕窗口打开并登录：
   - 页面进入后自动从服务端 `GET /api/settings/locale` 获取配置，并自动呈现为 `English` 界面，偏好成功漫游。

### 场景 C：服务端错误消息多语言解析与请求头协同
1. 在切换为 `English` 状态下，触发一个服务端错误（如在登录页输入错误密码）：
   - 请求头自动携带 `Accept-Language: en-US`；
   - 服务端返回 401 响应：`{"code":"invalid_credentials","message":"Invalid username or password"}`；
   - 页面 Toast 弹出对应的英文提示，错误码 `invalid_credentials` 保持不变。
2. 切换回 `简体中文` 再次重试，返回文案切换为 `用户名或密码错误`。

### 场景 D：小说原数据不可变性检验
1. 打开任意一本书进入阅读器（`ReaderScreen`）：
   - 书籍名称、作者名、各章节目录标题及正文内容均保持书源原生语言，未发生机翻或错乱。

---

## 4. 关键核验清单

- [ ] 顶栏下拉菜单中呈现 4 种语言切换按钮（`zh-CN`, `zh-TW`, `en-US`, `ja-JP`）
- [ ] 点击任意语言，界面文本无刷新即时响应切换
- [ ] 页面刷新后能保持所选语言（`localStorage` 与服务端双向同步）
- [ ] `GET /api/settings/locale` 与 `PUT /api/settings/locale` 正常读写数据库
- [ ] 前端发出的请求统一携带 `Accept-Language` 头部
- [ ] 服务端 `ApiError` 响应文案随语言匹配，`code` 字段保持不变
- [ ] 书籍正文、目录与书源规则保持原文，未被意外改动
- [ ] 所有前端自动化测试与服务端测试套件均保持 100% 通过

---

## 5. AI 侧已完成的真实浏览器端到端取证（Chrome E2E）

已通过 Google Chrome 真实浏览器执行自动化端到端测试（`npx tsx web/test/e2e-i18n-browser.ts`），**20 项测试断言 100% 通过**：
1. **中文初始化**：验证导航包含 `书库`、`书架`、`书源`、`规则` 等；
2. **英语即时切换**：点击 `English`，顶栏导航即时变为 `Library`、`Bookshelf`、`Sources`、`Subscriptions`、`Rules`、`Files`，无需刷新；
3. **繁体中文即时切换**：点击 `繁體中文`，导航即时变为 `書庫`、`書架`、`訂閱`、`檔案` 等；
4. **日语即时切换**：点击 `日本語`，导航即时变为 `ライブラリ`、`本棚`、`ブックソース`、`購読`、`置換ルール`、`ファイル` 等；
5. **持久化与服务端漫游**：
   - 断言 `localStorage.getItem('legado-locale-v1') === 'ja-JP'`；
   - 断言 `GET /api/settings/locale` 返回 `{"locale":"ja-JP"}`；
   - 执行 `page.reload()` 刷新页面，验证依然保持日语环境；
6. **服务端错误响应本地化**（对齐 RFC 4647 请求头）：
   - `en-US`：`HTTP 401 {"code":"invalid_credentials","message":"Invalid password"}`
   - `ja-JP`：`HTTP 401 {"code":"invalid_credentials","message":"パスワードが正しくありません"}`
   - `zh-TW`：`HTTP 401 {"code":"invalid_credentials","message":"密碼錯誤"}`
   - `zh-CN`：`HTTP 401 {"code":"invalid_credentials","message":"密码错误"}`
   - 错误代码 `invalid_credentials` 保持严格一致；
7. **恢复测试**：切回 `简体中文` 并验证服务端持久化同步恢复为 `zh-CN`；
8. **截图存档**：
   - `e2e-i18n-zh-CN.png`
   - `e2e-i18n-en-US.png`
   - `e2e-i18n-zh-TW.png`
   - `e2e-i18n-ja-JP.png`
   - `e2e-i18n-restored-zh-CN.png`

