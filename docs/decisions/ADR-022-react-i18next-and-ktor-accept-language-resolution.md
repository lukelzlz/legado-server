---
id: ADR-022
title: 采用 react-i18next 与 Ktor Accept-Language 解析的全栈国际化架构 (Fullstack i18n with react-i18next and Ktor Accept-Language Resolution)
status: accepted
date: 2026-09-30
---

# ADR-022: 采用 react-i18next 与 Ktor Accept-Language 解析的全栈国际化架构

## 1. 决策背景 (Context)

Legado-server 原本仅针对中文用户设计，所有 Web UI 文本、弹窗提示及服务端返回的 `ApiError.message` 均深度硬编码了简体中文。为了使系统走向国际化并支持全球更多华语、英语及日语读者，需要确定一套清晰、健壮且长期稳定的全栈国际化架构规范：

1. **前端多语言选型**：是采用零外部依赖的手写 React Context 字典，还是引入工业级成熟方案 `react-i18next`？
2. **语言状态与偏好持久化**：用户选择的语言偏好存在哪里？如何在多端多设备间自动漫游？
3. **服务端错误响应多语言**：后端如何感知客户端的目标语言？如何本地化返回错误文案且绝对不破坏既有的错误码断言？
4. **静态 UI 与动态抓取内容的边界**：网络书源返回的书名、作者、章节名、小说正文是否需要参与翻译？

---

## 2. 裁定方案 (Decision)

1. **前端采用 `react-i18next` + `i18next` 生态**：
   - 依赖项：引入 `i18next` 与 `react-i18next`；
   - 结构规范：在 `web/src/i18n/` 下按标准 JSON 文件组织（`zh-CN.json`, `zh-TW.json`, `en-US.json`, `ja-JP.json`），首发支持简体中文、繁體中文、英语、日语四种基准语言；
   - 词条采用结构化命名空间键值管理（如 `header.shelf`, `reader.fontSize`, `webdav.backupSuccess`），杜绝魔术字符串；
   - 资源首屏静态内联打包，避免离线 PWA 模式下因异步加载语言包失败导致界面白屏或文案错乱。
2. **服务端持久化复用 `app_setting` 表，实现多端无缝漫游**：
   - 语言偏好落库使用 `Database.setSetting("locale", value)`，对应已有的 `app_setting` 表（`key text primary key, value text not null, updated_at integer not null`），零新增表、零 SQL migration 风险；
   - 提供 `/api/settings/locale` 的 `GET` 与 `PUT` 接口；
   - 用户登录后前端自动从服务端同步持久化的 `locale`，换浏览器或无痕模式登录后立即可继承原有的语言习惯；未登录时降级使用浏览器的 `localStorage` 与 `navigator.language`。
3. **服务端 `ApiError` 本地化：保持 Code 稳定，动态解析 Message**：
   - 数据契约：`ApiError(val code: String, val message: String)` 中，`code` 保持 100% 不变（如 `invalid_text`, `csrf_invalid`, `not_found`），确保前端与已有测试的机器可读性不变；
   - 服务端提取客户端请求头 `Accept-Language`（前端 `api.ts` 拦截器自动注入当前活跃语言），结合服务端内置词典 `ServerMessages` 映射为对应的目标语言文案；
   - 无匹配或未知语言时，一律安全回退到原预设的默认文案（Fallback safe）。
4. **严格界定静态 UI 与动态抓取内容的不可侵犯边界**：
   - 仅对系统固有 UI（按钮、表单、提示、对话框、帮助说明）与系统级错误进行国际化；
   - 网络抓取的第三方小说正文、章节标题、书籍作者、书源名称及规则脚本绝对保持原样，坚决不做机器翻译，保障阅读内容的原汁原味与无头解析性能。

---

## 3. 备选方案与否决理由 (Alternatives Considered & Why Rejected)

- **备选方案 A：零外部依赖自研手写 React Context 简易字典**
  - *否决理由*：虽然满足极致的包体积控制（增量仅 ~2KB），但在处理复杂的插值（`{{count}} chapters`）、复数形式、多模块嵌套命名空间及后续扩展第三方贡献词条时，手写实现将不断膨胀为轮子。`react-i18next` 经过社区数十万项目检验，生态完善，且整体引入仅 ~40KB，符合生产级工程选型权衡。
- **备选方案 B：仅前端拦截 `ApiError.code` 翻译，服务端保持全中文**
  - *否决理由*：这会导致直接通过 cURL、Postman、Kindle 墨水屏端或未来移动客户端请求接口时，收到的仍是中文报错；且前端容易与后端脱节，产生大量的未定义 code 遗漏。在服务端支持 `Accept-Language` 是标准 RESTful API 的最佳实践。
- **备选方案 C：在数据库中为用户表新增 `locale` 列或新建独立表**
  - *否决理由*：违背工程复杂度惩罚原则。系统已有用于存储系统与服务级键值的 `app_setting` 表，并且已有 `getSetting` / `setSetting` 方法。直接使用 `key = "locale"` 既简单又无迁移风险。
- **备选方案 D：为小说正文接入在线机器翻译（如 Google Translate / DeepL API）**
  - *否决理由*：违反 Legado-server 的核心定位。正文机翻会带来高昂的网络开销、延迟与费用，且破坏离线缓存与排版一致性。书籍正文必须保持源文本真实性。

---

## 4. 后果与权衡 (Consequences & Trade-offs)

- **正面收益**：
  - 彻底摆脱纯中文界面的局限，为简中、繁中、英语、日语四地读者提供一致母语体验；
  - 架构清晰、模块解耦，未来新增小语种（如德语、法语、韩语）只需增加一个 JSON 文件与服务端词典项；
  - 跨设备体验大幅提升，服务端落盘使得读者无需在每个新设备上重复设置语言；
  - 遵循 HTTP 标准协议规范（`Accept-Language`），为后续开放 API 打下坚实基础。
- **负面代价与工程注意点**：
  - 前端包体积增加约 40KB（gzip 后约 12KB），需确保不影响首屏秒开体验；
  - 全量组件抽离文本的工程量较大，需要细致地对所有 modal、toast 与页面进行穷举替换；
  - 服务端必须做好 `Accept-Language` 头的解析容错（如 `zh-CN,zh;q=0.9,en;q=0.8` 的权重拆分或标准前缀匹配），防止非法请求头导致解析崩溃。
