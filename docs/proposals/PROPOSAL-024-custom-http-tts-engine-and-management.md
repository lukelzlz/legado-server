---
id: PROPOSAL-024
title: 自定义 HTTP 朗读引擎体系与 Legado 规范管理互通
status: accepted
author: Agent & User
date: 2026-10-01
---

# PROPOSAL-024: 自定义 HTTP 朗读引擎体系与 Legado 规范管理互通 (Issue #25)

## 1. 业务背景与问题痛点
当前系统的听书朗读（TTS）功能支持微软 Edge-TTS 与浏览器本地 Web Speech，而在自定义 HTTP 方面仅在阅读设置里提供了单个 URL 的简易输入框：
1. **无法管理多个自定义音源**：用户拥有多个第三方 TTS（如百度语音、阿里云语音、自建 TTS 等）时，只能反复覆盖同一个 URL，无法灵活切换。
2. **缺乏与 Legado 生态互通能力**：开源阅读 Android 平台拥有成熟丰富的 `httpTTS.json` 规则生态与导入导出标准，当前系统无法导入和导出该格式。
3. **缺少连通性测试与参数管理**：用户配置新 TTS 时无法单项试听验证连通性，且缺少针对请求头、ContentType、Legado 语法（如 `url,{options}` 与 `{{speakText}}` 等沙箱变量）的完整支持。

## 2. 目标与非目标 (Goals & Non-Goals)

### Goals
- **Legado 规范兼容**：完全兼容开源阅读 `httpTTS.json` 的模型结构（`id`, `name`, `url`, `header`, `contentType`, `concurrentRate`, `loginUrl`, `loginCheckJs`, `loginUi`, `enabledCookieJar` 等）。
- **服务端持久化与漫游**：在服务端 SQLite 数据库建立 `http_tts` 表，提供标准的 REST API（获取列表、增删改、批量导入、导出），实现跨终端无缝漫游。
- **独立 TTS 管理中心**：在阅读器听书设置弹窗及相关入口提供「TTS 管理」按钮，点击弹出管理面板，支持列表展示、快速启用/停用、新增/编辑、删除、单项/全量导出 JSON、本地 JSON 文件导入或粘贴文本导入。
- **在线试听与连通性测试**：在编辑弹窗与管理列表提供即时“试听测试”功能，输入测试文本即可调用服务端合成试听，并给出准确的错误提示。
- **模板语法与沙箱执行**：支持 Legado 标准的 `url,{options}` 语法及模板变量（`{{speakText}}`, `{{speakSpeed}}`, `{{speakVoice}}` 及 `{{java.encodeURI(...)}}` 等 Rhino JS 表达式求值），在服务端直接发起上游请求，彻底免疫前端跨域 CORS 限制。
- **朗读设置整合**：改造 `TtsSettingsModal` 中的引擎选项，支持平滑选择 Edge-TTS、系统 WebSpeech 以及已启用的自定义 HTTP TTS，朗读时自动路由。

### Non-Goals
- 不修改已经成熟稳定的 Edge-TTS WebSocket 握手协议与前端单句相对时钟锚定逻辑。
- 不引入重型前端音频混音库，保持 HTML5 Audio 与轻量流式管线。
- 不支持依赖 Android 原生 JNI 本地动态链接库的专有离线语音包。

## 3. 核心用户故事 (User Stories)

- **Story 1（导入与生态互通）**：
  作为 Legado 忠实用户，我从手机端导出了 `httpTTS.json`，在 Web 阅读器的 TTS 管理面板中点击“导入”，选择该文件后，系统一次性识别并批量保存所有 HTTP TTS 规则，列表中清晰展示各个引擎名称与状态。
- **Story 2（自由配置与试听调试）**：
  作为用户，我想接入自己部署的私有 TTS（例如 ChatTTS 或 CosyVoice），在管理面板中点击“新建 TTS”，填入名称、URL 与请求参数，点击“测试发音”，系统立刻播放测试文本并显示成功提示；若参数错误则给出清晰的上游报错。
- **Story 3（无缝切换朗读）**：
  作为听书爱好者，在阅读器打开朗读设置，在引擎选项或列表中直接点选刚刚导入的“百度语音”或“阿里云语音”，随后点击开始朗读，系统即刻使用该引擎合成并流畅发音，体验与原生 Edge-TTS 一致。
- **Story 4（导出备份与多端同步）**：
  作为跨设备用户，我在网页端调试好了满意的语音接口，在管理面板点击“全部导出”，下载合规的 `httpTTS.json` 文件并导入手机端阅读，两端无缝兼容。

## 4. 验收基准 (Acceptance Criteria)

- [ ] 后端通过 `gradlew :server:test` 单元测试，涵盖 HTTP TTS 表的增删改查、Legado `httpTTS.json` 的序列化/反序列化及 `{{...}}` 变量求值。
- [ ] 前端通过 `npm --prefix web run check` 与 `npx tsx web/test/run-all.ts`。
- [ ] 导入标准的 Legado `httpTTS.json`（包含百度、阿里云、Edge-TTS 等样本）可 100% 正确解析入库。
- [ ] 在 TTS 管理面板中可正常进行新建、编辑、删除、试听、导入、导出。
- [ ] 阅读器听书设置中可以选中自定义 HTTP TTS 并正常朗读。
