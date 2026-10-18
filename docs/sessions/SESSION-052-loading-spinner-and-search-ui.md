# 工作记忆：统一按钮加载微动画与防连击保护 (Issue #67) ＋ 搜索界面 UI 优化 (Issue #73)

- **会话编号**：`SESSION-052`
- **日期**：2026-10-18
- **相关 Issue**：
  - [#67 [Feature]: 在所有需要耗时响应与网络请求的交互位置统一增加加载动画与防连击保护](https://github.com/lukelzlz/legado-server/issues/67)
  - [#73 [Feature]: 搜索界面ui优化](https://github.com/lukelzlz/legado-server/issues/73)
- **分支**：`feat/loading-states-and-search-ui`

---

## 1. 目标与背景

1. **Issue #67 根因与需求**：
   - 当前在涉及后端耗时处理或外部网络请求的交互场景中（如「清除服务器缓存」、「换源」、「书籍编辑保存/联网补全」、「本地书重新解析/正文重洗」、「书源登录动作执行」、「网络导入与订阅同步」、「阅读器离线范围缓存」等），按钮点击后缺乏即时视觉微反馈（Spinner）；
   - 用户无法确认请求是否发出，偶发延迟时容易高频连击，向服务端并发发送多笔重复写入请求，浪费 SQLite I/O 与并发配额。
2. **Issue #73 需求**：
   - 优化书库主搜索框形态（高 48px、圆角 10px、聚焦环自适应主题色、右侧清空按钮、胶囊搜索按钮、空文本禁用）；
   - 搜索进行中复用通用 Spinner 指示器，按钮自动切换为常态可用的「停止搜索」。

---

## 2. 核心架构设计与改动清单

### 2.1 规范化通用按钮 Loading 规范与防连击保护 (Issue #67)
- **`web/src/styles.css`**：
  - 定义标准动画 `@keyframes btnSpin { to { transform: rotate(360deg); } }` 与 `.btn-spinner`（边框色自动继承 `currentColor`，适配白底、深色、危险红与柔和主题色）；
  - 声明 `btn-spinner-sm` (12px) 与 `btn-spinner-lg` (18px)；遵循 `@media (prefers-reduced-motion: reduce)`；
  - 声明按钮防连击锁：当按钮带有 `.is-loading` 或 `[aria-busy="true"]` 时，强制施加 `pointer-events: none !important; cursor: wait !important;`。
- **`web/src/ButtonSpinner.tsx`**：
  - 抽取高可复用、轻量且无障碍语义化的 `<ButtonSpinner />` 组件（`role="status"`, `aria-label="loading"`）。
- **关键异步交互场景全覆盖**：
  - **清除缓存范围弹窗 (`main.tsx:ClearCacheScopeModal`)**：维护 `clearingScope`，选中清理项右侧替换为 `ButtonSpinner`，所有选项加锁；
  - **换源弹窗 (`sourceSwitch.tsx`)**：正在切换的目标项渲染 `ButtonSpinner` 并显示“切换中...”，其它所有候选源按钮 `disabled={switchingSourceId !== null}` 互斥锁定；
  - **本地书重新解析与正文重洗 (`main.tsx:BookManageModal`)**：`recleaning` 与 `reparsing` 时右侧箭头即时替换为 `ButtonSpinner`；
  - **书籍编辑信息 (`main.tsx:BookInfoEditModal`)**：保存修改按钮、联网搜索补全、本地封面上传均内联 `ButtonSpinner` 与锁定；
  - **书源管理与登录 (`SourceLoginModal.tsx`)**：记录 `executingAction`，被触发的 action 按钮与头部保存并登录在执行期间展示 `ButtonSpinner` 并禁用；
  - **网络书源导入 (`NetworkImportModal.tsx`)**：拉取与确认按钮在 `fetching` / `committing` 期间内联 `ButtonSpinner` 并锁定；
  - **书源订阅 (`main.tsx:SubscriptionPanel`)**：全部同步与单项立即同步在 `busy` 期间内联 `ButtonSpinner` 并禁用；
  - **阅读器目录抽屉 (`ReaderScreen.tsx`)**：点击范围离线缓存（后50章/后100章/全本）即时转圈并禁用其它按钮防连击。

### 2.2 搜索界面 UI 优化 (Issue #73)
- **`web/src/SearchBox.tsx`**：
  - 将主搜索框抽取为独立纯组件（输入框高 48px、圆角 10px、居中放大镜、placeholder 保持不被冲淡）；
  - 输入有内容时展示右侧 `×` 清空按钮（`type="button"`，点击清空并回焦输入框）；
  - 支持键盘 `Esc` 清空回焦，`Enter` 严格走表单提交唯一路径；
  - 搜索请求进行中复用 `ButtonSpinner`（`library-search-spinner`），主按钮无缝切换为常态可用的「停止搜索」；
  - 移动端叠加 `safe-area` 保证全面屏不被截断。
- **`web/src/i18n/locales/*.json`**：
  - 四语（`zh-CN`, `zh-TW`, `en-US`, `ja-JP`）对齐 `clearSearch` 与 `searching` 键。

---

## 3. 验证与回归矩阵

- **前端静态检查与测试套件**：
  - `npm --prefix web run check`：TypeScript 编译零错误；
  - `web/test/loading-states.test.ts`：9 项全新回归断言全绿；
  - `web/test/search-box.test.ts`：11 项搜索框形态与交互断言全绿；
  - `npx tsx web/test/run-all.ts`：**372 tests, 372 passed, 0 failed**。
- **服务端单测**：
  - `./gradlew :server:test`：**BUILD SUCCESSFUL in 1m 57s**，零回归。
