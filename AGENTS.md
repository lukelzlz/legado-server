# Repository Guidelines & Constitutional Conventions

## 1. Project Core Mission & Objective

> **核心项目定位**：将 **Legado（开源阅读 Android 平台应用）** 完整迁移与重构为可在标准服务器与 Docker 容器中独立运行的高性能**无头后端（Ktor + Kotlin JVM + SQLite）**与现代化**Web 客户端（React 19 + TypeScript + Vite）**。

### 关键迁移与设计原则
1. **彻底解耦 Android 平台依赖**：严禁在 `server/` 或 `web/` 模块中引入 Android Framework 原生组件（如 Activity、Context、Room、Android WebView、Android UI 等），必须使用跨平台的纯 JVM 技术栈与标准 Web API 替代。
2. **服务端无头（Headless）化架构**：服务端作为独立的中心服务，承载书源规则解析与执行沙箱（Rhino JS + Jsoup + JsonPath）、多书源并发搜索、离线书籍与封面缓存、订阅同步、PBKDF2/Session 鉴权与 SQLite 数据持久化。
3. **深度兼容书源生态**：完全复用并兼容 Legado 现有丰富的书源协议与规则定义，确保现有网络书源可在服务端正确、安全且高效地解析。
4. **现代化 Web 阅读体验**：Web 端作为跨平台终端，提供大章节目录虚拟化、单书源直连秒开、离线缓存进度同步及沉浸式阅读器交互。

---

## 2. 仓库边界与文档驱动规范 (Repository Boundary & Cleanliness)

本项目严格执行**文档驱动开发（Doc-Driven Development）**与工程整洁度控制：

- **严禁随地大小便**：严禁在项目根目录或业务源码目录中散落 `PLAN.md`, `NOTES.md`, `TODO.md`, `temp/`, `plan/` 等临时或未受管文档。
- **文档统一收敛机制**：
  - 需求与功能提案 (PRD) 归入 [`docs/proposals/`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/)
  - 架构决策记录 (ADR) 归入 [`docs/decisions/`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/)
  - 原始推演、历史排错与工作记忆归入 [`docs/sessions/`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/)
  - 用户实操验收手册与验证清单归入 [`docs/acceptance/`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/acceptance/)
- **工程复杂度惩罚**：严禁为了单一补丁随意增加无意义的抽象层、胶水层或重复工具类。优先采用满足当前需求的最简、最直接解法。

---

## 3. 最短验证命令集 (Shortest Verification Commands)

AI 在修改代码后，必须优先执行本节定义的最短、最精准命令自测：

```sh
# --- 1. 前端类型检查与自动化测试套件 ---
npm --prefix web run check
npx tsx web/test/run-all.ts

# --- 2. 服务端本地 JVM 单元测试 ---
./gradlew :server:test

# --- 3. 前后端综合一键验证 ---
npm --prefix web run check && npx tsx web/test/run-all.ts && ./gradlew :server:test

# --- 4. 本地 Docker 容器构建与热更新验证 (用户常用测试基准) ---
docker build -f Dockerfile.server -t test-legado-server:latest .
docker stop test-legado 2>/dev/null || true
docker rm -f test-legado 2>/dev/null || true
docker run -d --name test-legado -p 8080:8080 \
  -e ADMIN_PASSWORD=admin123 \
  -e LEGADO_SECURE_COOKIES=false \
  -v $(pwd)/.data:/data test-legado-server:latest
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/index.html
```

> **Windows 本机执行注意（详见 [`docs/sessions/SESSION-HIST-008`](file:///root/legado-server/docs/sessions/SESSION-HIST-008-windows-environment-and-verification-baseline.md)）**：
> - JDK 固定用 **Amazon Corretto 21**；`JAVA_HOME` 未持久化时需前缀 `$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.12_9"`；
> - `./gradlew :server:test` 在本机**长期固定 52 个失败**（测试删不掉被占用的 SQLite，macOS 不暴露）。**判定回归必须与「干净基线 worktree 的失败集合」逐条比对，禁止只看失败数量**；
> - 前端依赖缺失时先 `npm --prefix web install --ignore-scripts`（esbuild 的 postinstall 会被拦截），再跑 `check` / `tsx test/run-all.ts`；
> - 明文 HTTP 联调统一带 `LEGADO_SECURE_COOKIES=false`，否则浏览器不回传 Secure Cookie、表现为「登录后立刻掉线」。

---

## 4. 完成度状态阶梯 (Completion States)

AI 与人类协作时必须明确当前达到的完成度阶梯，严禁混淆概念：
1. `[Modified 本地改动]`：代码已编写完成，但尚未执行测试。
2. `[Tested 单测/检查通过]`：已通过本地最短单测（`gradlew :server:test`）与静态类型检查（`npm run check` / `tsx run-all.ts`）。
3. `[Deployed 容器/服务联调完成]`：已部署/更新至本地 Docker 容器并启动，且日志与接口无报错。
4. `[Accepted 用户签字确认]`：用户已根据验收手册实操验证并明确回复“验收通过/LGTM”。
5. `[Pushed 提交与推送]`：代码已提交至 Git 并推送到远端仓库。

---

## 5. 部落知识库与历史踩坑 (Tribal Knowledge)

基于历史会话全量扫描提炼的架构潜规则与避坑指南：

- **[书源解析/兼容] `bookSourceUrl` 允许任意非空唯一字符串**：Legado 书源生态中部分聚合或定制书源（如 `大灰狼融合VIP5.0`）使用自定义中文或标识作为 `bookSourceUrl`，服务端严禁粗暴强制要求 `http(s)://`；同时前端与服务端导入均需兼容 UTF-8 BOM 编码及 `{ data: [...] }` / `{ sources: [...] }` / `{ bookSources: [...] }` 等外层包装结构。
- **[SQL/Kotlin] 严禁使用可空列做存在性 Elvis 判断**：在 JDBC / SQLite 结果集提取中，务必区分“字段值为 NULL”与“数据行不存在”。例如 `SELECT cover_key FROM book_shelf`，若书籍无封面则字段为 `NULL`，直接 `rs.getString(...) ?: return null` 会误判书籍不存在。
- **[前端/Form] 按钮显式声明 `type="button"`**：表单内的所有辅助操作按钮（如停止搜索、清空、排序切换）必须显式标注 `type="button"`，否则点击会触发 HTML 表单默认 `submit` 事件导致搜索意外重启。
- **[翻页/排版] 跨章逆向翻页定位守卫**：从章节开头回翻到上一章时，必须携带 `targetPosition = 'bottom'` 标记，且必须在 DOM/分栏异步排版完成后再执行末尾定位，严禁在未完成排版前盲目计算滚动高度。
- **[性能] 缓存优先直出与流式防抖**：进入阅读器时优先命中本地 `BookCacheService` 离线缓存分片，避免等待全量远程 TOC；流式搜索推送高频数据时前端需保持批量节流合并渲染。
- **[凭据/序列化] 万能 Cookie 解析与强类型 DTO**：服务端 CookieJar 存库前必须通过 `parseCookieString` 统一归一化为 `k1=v1; k2=v2` 格式，杜绝存入原始 JSON 数组脏数据；Ktor 路由响应严禁使用非多态的 `Map<String, Any>`，必须使用 `@Serializable data class`。
- **[TTS/朗读] Edge-TTS 协议与 Chrome 假死守卫**：Edge-TTS WebSocket 通信中 SSML 必须严格做 XML 特殊字符转义（`&`, `<`, `>`, `"`, `'`），并且 WebSocket 通信块必须加 `try-catch(abort)` 彻底规避超时句柄悬挂；浏览器端 `SpeechSynthesis` 在无心跳朗读超过 15 秒时会被 Chrome 自动静默冻结，前端必须保持定时短暂停与恢复的看门狗循环。
- **[TTS/朗读] Edge-TTS WebSocket 握手版本必须与 Chromium 同步更新**：Edge-TTS 连接头中 `Sec-MS-GEC-Version`（如 `1-143.0.3650.75`）与 `User-Agent` 中的 Chrome 版本号必须保持一致；同时须携带随机 `Cookie: muid=<16字节大写hex>` 头，否则 WebSocket 握手被微软服务端拒绝导致合成静默失败（返回 0 字节音频）。版本信息参考 `edge-tts` Python 包的 `constants.py`。
- **[TTS/朗读] 孤立标点切片导致 ERR_REQUEST_RANGE_NOT_SATISFIABLE 的三层防御**：TTS 分句正则可能将中文对话引号 `"` 切为孤立碎片，发送空/纯标点文本至 Edge-TTS 会返回 0 字节音频，前端 `URL.createObjectURL(0字节blob)` 后浏览器发出 Range 请求，得到 HTTP 416 崩溃。**必须在三处同时加守卫**：① `splitSentences` 过滤去标点后有效字符 `< 2` 的碎片；② `HttpAudioTtsEngine.speak/prefetch` 调用 `isEffectiveText()` 判断，无效时 `setTimeout(onEnd,0)` 跳过；③ 服务端 `EdgeTtsService.synthesize` 检测去标点后有效字符 `< 2` 直接返回 `ByteArray(0)` 不请求上游。
- **[TTS/朗读] 跨章连播状态与正文加载竞态守卫**：切章（`changeChapter`）时必须同步将 `loadedChapterUrl` 置空并清除旧 `content`，连播 `useEffect` 必须严格校验 `loadedChapterUrl === chapter?.url` 且使用 `playTtsChunkRef.current` 调用最新闭包，杜绝切章瞬间误读上一章旧正文；正文段落点击选播严格守卫 `if (!ttsActive) return`，防止普通阅读点选误触发朗读。
- **[TTS/朗读] 单句相对时钟锚定（Anchor Resync）、600ms 尾部静音看门狗与 5 分片前瞻**：原绝对时钟累加（`audioCursorMs`）在播放约 2 分钟（40~50 分片）后，由于 MP3 Priming/Padding 样本与声卡重采样微小物理偏差累积超过 180ms 触发静音死锁；章节末尾 Edge-TTS 音频常包含 300~500ms 尾部静音帧，播放器在最后一两句易在距时长 300~400ms 处停止推进。解决方案：① 服务端 `chunk_end` 显式下发单分片 `durationMs`；② 前端切句时动态锚定 `anchorMs = audio.currentTime * 1000`，单句相对判定 `nowMs >= anchorMs + durationMs - 60`，跨句累积漂移彻底归零；③ 配备停滞看门狗（Stall Watchdog），放宽窗口至 `nowMs >= anchorMs + durationMs - 600`，停滞 > 350ms 强制推进 `onEnd`，杜绝章末短分片尾部静音卡死；④ `ReaderScreen` 前瞻缓冲扩大至 5 分片（`lookahead <= 5`），并在距离章末 5 句内提前预载下一章正文，避免断流卡顿；⑤ 监听 `<audio>` 的 `stalled` 事件与 `waiting` 状态，并在非暂停停滞 > 500ms 时自动调用 `.play()` 唤醒底层解码管道。
- **[TTS/朗读] HTML5 audio.play() 暂停打断与 AbortError 守卫**：浏览器原生规范中，当调用 `audio.pause()`、重置 `src` 或切章重置时，正在 pending 的 `audio.play()` Promise 会被浏览器自动 reject 抛出 DOMException (`AbortError: The play() request was interrupted by a call to pause(). https://goo.gl/LdLk22`)。这属于用户主动暂停或切流，必须在 `HttpAudioTtsEngine`（play catch、reportError、isPaused 状态跟踪）与 `ReaderScreen`（onError 回调）中多层静默拦截 `AbortError` 与 interrupted 关键词，严禁向用户弹窗报错。
- **[TTS/朗读] 开启朗读时视口首个完整可见段落智能对齐**：当用户在阅读中途点击开启 TTS 朗读时，阅读器会基于当前排版视图模式（滚动模式避开顶部 56px 导航栏、翻页模式限定分栏视口内）精准计算视口内第一个完整可见的段落（或章节标题 `<h1>`），并以此为朗读起始分片，避免每次开启都跳回章首或历史断点的突兀体验。
<<<<<<< HEAD
- **[TTS/朗读] 长音频流首帧 144 字节静音垫底与 X-Accel-Buffering: no 反代防缓冲**：连续音频流与 SSE 进度通道必须显式声明 `X-Accel-Buffering: no` 与 `Cache-Control: no-cache, no-transform` 穿透 Nginx 缓冲；服务端在客户端建立音频流连接瞬间先下发 144 字节合规 LAME MP3 静音帧（Silence Preamble），使浏览器 `<audio>` 秒入就绪态并即刻激活系统控制中心（MediaSession）。
- **[TTS/流式广播] 多连接探针与会话生命周期解耦**：浏览器 HTML5 `<audio>` 建立流式连接时常先发起探针（Probe）请求探测 MIME/Range，紧接着发起正式播放流。服务端朗读音频广播流严禁使用单消费者 `Channel` 或单连接原子互斥锁（否则第二次连接抛 `IllegalStateException("音频流已连接")` 触发 HTTP 500）；必须采用 `MutableSharedFlow(replay = 16)` 广播管道，且单连接断开（CancellationException）严禁误触发 `close("audio_disconnected")` 销毁全局会话，确保探针与主播放流平滑流转。
- **[反向代理/沙箱] Iframe 隔离严禁开启 `allow-same-origin`**：反向代理第三方不可信 Web 页面时，iframe 必须禁用 `allow-same-origin`，将其置于 opaque origin（`null`）下，杜绝被代理页面的恶意 JS 触碰宿主 DOM 与会话。
- **[沙箱/安全] `JavaImporter` 必须采用安全空替身模式**：Legado 书源中大量 `jsLib` 工具库使用 `with (JavaImporter(...))` 组织代码。沙箱严禁开启真实 Java 反射（防止 RCE 漏洞），必须返回 `importClass`/`importPackage` 均为 no-op 的安全空替身，既规避 `ReferenceError` 崩溃，又确保纯沙箱环境安全。
- **[Cookie 管理] Set-Cookie 存库前必须剥离指令属性**：上游响应中的 `Path=/; HttpOnly; SameSite=Lax; Max-Age=3600` 等指令属性若直接整串入库，会导致后续作为客户端请求头 `Cookie:` 发送时将属性一并带出引发上游 400 报错。
- **[HTTP 规范/Ktor] 巨型 Data URL 必须转由服务端托管避免请求行超限**：书源 JS 生成的自包含 Base64 网页动辄数万字符，直接拼入 iframe URL 会触发 Ktor 8192 字符上限报 400，必须先通过 POST 上传服务端内存托管，前端仅引用短 key。
- **[CI/构建] Gradle Wrapper 必须保持官方 distributions 下载源**：`gradle-wrapper.properties` 中的 `distributionUrl` 若配置为国内镜像（如腾讯云 `mirrors.cloud.tencent.com`），在 GitHub Actions 等海外 Runner 环境中会出现网络超时（Connection timed out）导致 CI崩溃，必须始终保持官方 `https://services.gradle.org/distributions/` 地址。
- **[替换规则/沙箱] Rhino JS 沙箱顶级 return 包装与反爬反义词对调字典**：Legado 生态中的 `@js:` 替换规则普遍使用 `return map[result] || result` 组织代码。Rhino 沙箱在顶层执行 `return` 时会抛 `return not in function` 语法错误，沙箱必须检测并在必要时将代码包装进 `(function(){ ... })()` 匿名闭包执行；同时替换净化必须在 `RuleRunner.content()` 与 `BookCacheService` 离线下载落库前执行，避免脏文本污染持久化缓存，同时使后续 TTS 朗读自动获得清洗后的正文。
  > **补充（2026-09-20，实测）**：包裹判定的**适用范围**与**实现方式**同样关键，两者都曾出错并导致聚合源正文为空：
  > ① **绝不能把 jsLib 与规则脚本拼接后再判定**——聚合源 jsLib 里每个工具函数都含 `return`，拼接判定恒真 ⇒ 整段被包进 IIFE；
  > ② **Rhino 在 IIFE 下的求值补全值会退化为 `undefined`**，而聚合源正文规则常以裸表达式（`data;`）结尾并依赖该补全值 ⇒ 正文取空。
  > 正确做法：**jsLib 在同一 scope 内先单独求值**（只建立函数定义，其结果不参与补全值），
  > 再**仅按规则脚本自身的顶层 `return`**（引号/注释/模板字面量感知的括号深度扫描）决定是否包裹。
  > 回归测试：`server/src/test/kotlin/io/legado/server/JsSandboxCompletionValueTest.kt`（17 用例）。
- **[沙箱/诊断] `eval` 成功时必须清空 `lastError`，否则调用方读到上一次失败的残留**：旧实现只在失败时写 `lastError`、成功路径不清空，排障时会拿到与本次无关的旧错误而误判（实测复现）。成功分支须显式置空。
- **[聚合源/正文] `<js>` 求值为 null 时 `NodeValue.value` 会回退成「中间值」⇒ 坏书源可能静默把 `data:` 载荷当正文**：规则脚本因缺 jsLib 等抛 `ReferenceError` 时，`RuleRunner.content()` **不报错**，而是把 `data:;base64,…` 解码后的载荷原文当正文（`content()` 仅在 `text.isBlank()` 时才抛「未提取到内容」）。用户会看到 `{"book_id":…}` 之类原始载荷却无任何提示。**尚未修复**（改动波及所有书源的 `<js>` 回退语义，需独立立项）：排障时若正文「像 JSON」，应优先怀疑此处而非网络。
- **[正文清洗] 删 `<div>` 的正则会把「整体包一层 div」的正文删光**：聚合源（如大灰狼）的正文规则常返回 `<div …>正文…</div>` 整体一层 HTML，而 `RuleRunner.cleanContent()` 里的 `replace(Regex("<div[\\s\\S]*?</div>"), "")` 会**连正文一起删除** ⇒ `text` 为空 ⇒ 报「正文规则未提取到内容」。修复：清洗改为两段式——结构性清洗后**若内容为空则退化为「只剥标签、保留文本」**（div/p 仅当换行分隔）。回归用例见 `JsSandboxCompletionValueTest`（外层 div 保留文本 / 结构性 div 仍被删除）。
- **[排障] 「提示要登录」不等于「丢了登录态」**：该文案可能来自**上游接口的 `msg`**——上游仅在**未带有效 cookie** 时才回这句。判定时必须先做**对照实验**（带 cookie vs 不带 cookie 调同一接口），再核对本项目实际发出的请求头，**不要凭提示语下结论**。实测对照（大灰狼）：带 cookie → `获取内容失败: 内容为空`；不带 cookie → `您今日免登录访问次数已达上限…请登录后刷新页面`。另有 `data:;base64,…` 聚合源的 `source_login_state` 可通过 `sqlite3` 只读查询 `login_info`/`cookie_jar` 自证登录态是否入库。
- **[交互/导航] 核心系统能力一级导航呈现与阅读器抽屉收敛**：全局核心管理能力（书源、订阅、替换净化规则）必须在一级导航栏设立独立入口；而在沉浸式阅读器中，顶栏严格保持极简（目录、换源、设置、朗读），辅助净化规则统一收敛进「阅读设置」抽屉，杜绝顶栏拥挤。
- **[PWA/离线缓存] 渐进式离线缓存与脱机阅读架构**：① Service Worker 缓存静态资源与应用壳，排除 `/api/tts/stream` 等实时长音频流；② 正文离线支持按「后50章/后100章/全本/自定义」4 并发切片下载，IndexedDB 存储纯净文本；③ 脱机断网期间阅读进度写入本地队列，网络恢复（`online` 事件）时静默 Flush 同步；④ 目录列表对已离线章节实时打绿点徽标（`●`）；⑤ 全面适配 `safe-area-inset-*` 与 `overscroll-behavior: none`，消除 iOS 橡皮筋下拉与刘海遮挡。
- **[GitHub/贡献者] Force Push 历史孤立对象导致首页 Contributors 残留**：早期导入或 Force Push 覆盖分支后，GitHub 后端 Git 裸仓库仍残留旧 Commit 悬挂对象，导致仓库首页侧边栏聚合了历史 70+ 位幽灵贡献者，而 Insights 图表仅遍历有效 HEAD 正常显示。通过将远程默认分支切换为 `main`（`gh api repos/:owner/:repo/branches/master/rename -f new_name=main`）并更新本地跟踪与 CI 触发分支，可强制 GitHub 后台重构索引并清除悬挂贡献者。
- **[WebDAV/Ktor] 尾卡 `{path...}` 参数只捕获首段，WebDAV 路径必须从请求 URI 推导**：Ktor 3.4 中 `route("/webdav/{path...}")` 的 `call.parameters["path"]` 对 `/webdav/a/b` 只返回 `a`（实测），直接用会导致多级路径被静默截断为单级，表现为 `PUT /webdav/x/y/z.txt`、`MKCOL /webdav/x/y` 莫名返回 `405`（落到已存在的单级目录上）。必须改为 `request.path().removePrefix("/webdav").decodeURLPart().trim('/')`；且尾卡不匹配空尾段，根路径 `/webdav` 需单独注册一条路由。
- **[WebDAV/客户端兼容] 三个必答响应头与最小 Class 2 锁**：① `OPTIONS` 必须返回 `DAV: 1, 2`、完整 `Allow` 与 `MS-Author-Via: DAV`，否则 Windows WebDAV 重定向器按只读处理；② `LOCK` 必须支持对「尚不存在的文件」加锁并返回 `201` + `Lock-Token: <opaquelocktoken:uuid>`，否则资源管理器新建文件流程失败；③ 锁只做登记与续租，`PUT/DELETE` 严禁因锁返回 `423`（客户端异常退出遗留的锁会让写入永久失败，nginx dav 同样不强制）；④ 目录必须用 `<D:resourcetype><D:collection/></D:resourcetype>` 表达且不返回 `getcontentlength`。
- **[WebDAV/数据落盘] 上传原子化、Range 手写与根目录保护**：`PUT` 先写同目录 `.webdav-upload-*.part` 再 `ATOMIC_MOVE + REPLACE_EXISTING`，避免半截文件被阅读器读到；`GET` 用 `respondBytesWriter(contentType, status, contentLength)` 配合 `FileChannel.position(offset)` 手写单段 Range（`206`/`416`），无需额外插件；相对路径须拒绝 `..`、反斜杠与空字节并 `normalize()` 后校验 `startsWith(root)`；`/webdav` 根目录禁止 `PUT/DELETE/MKCOL/MOVE/COPY`。WebDAV 鉴权走 HTTP Basic（用户名任意 + 管理员密码 PBKDF2），成功结果须按 `Authorization` 头短时缓存，否则客户端每个文件操作都会触发一次 PBKDF2。
- **[WebDAV/浏览器侧鉴权] 会话 Cookie + CSRF 双轨，页面写操作复用协议本身**：WebDAV 端点必须同时接受 HTTP Basic（外部客户端，天然免疫 CSRF）与**会话 Cookie**（Web 设置页，页面无需接触密码）。会话鉴权下**读**操作（浏览/下载）放行即可，**写**操作（`PUT/DELETE/MKCOL/MOVE/COPY/LOCK/PROPPATCH`）必须校验 `X-CSRF-Token`，缺失或错误返回 `403`，与 `SameSite=Strict` Cookie 构成双重防线。设置页的上传/建目录/删除**必须复用 `PUT`/`MKCOL`/`DELETE` 协议方法**，严禁另建平行 REST 写接口（否则出现两条落盘代码路径）；页面只读状态走 `GET /api/webdav/info?path=` JSON（目录优先排序、占用统计跳过 `.webdav-upload-*.part`），避免前端解析 `PROPFIND` XML。
- **[PWA/更新] 自托管升级后「界面还是旧版」= Service Worker 预缓存，必须双向兜底**：`registerType: 'prompt'` 下新 SW 会停在 waiting 态，而旧的 precache 会继续把 `navigateFallback` 的 `index.html` 与旧 bundle 返回给浏览器——用户会表现为「新增的导航入口/页面完全不存在」，即使服务端已经返回了新的资源（可用 `curl` 抓 `/` 观察 `assets/index-*.js` 哈希与 bundle 内容自证）。三层兜底：① `PwaManager` 必须在**登录页也挂载**（否则未登录用户永远看不到更新提示）；② 注册成功后立即判定 `reg.waiting && navigator.serviceWorker.controller` 并弹出更新提示（覆盖「上次错过提示」的历史遗留 SW）；③ 页面 `focus` / `visibilitychange` 与每 5 分钟主动 `registration.update()`，避免长期打开的标签页停留在旧版本。排障时让用户用 DevTools → Application → Service Workers → 注销 + 清站点数据，或直接用无痕窗口/换端口验证。
- **[测试/Puppeteer] tsx + `page.evaluate` 内禁止声明局部函数**：`tsx`（esbuild keepNames）会把被求值函数内的箭头函数/函数声明包成 `__name(...)`，而 `__name` 只存在于 Node 侧，导致浏览器内抛 `ReferenceError: __name is not defined`。`page.evaluate` / `$$eval` 的回调里严禁 `const fn = () => ...` 这类局部函数声明，应改为在 Node 侧多次调用 `$$eval`/`$eval` 组合结果。
- **[前端/无头渲染] 组件渲染期严禁直接取 `location`**：`replace-rules`、WebDAV 等页面在 `web/test` 中以 `renderToStaticMarkup` 做静态渲染断言，组件内若直接读 `location.origin` 会在 Node 环境抛错；必须封装 `currentOrigin()`（`typeof location === 'undefined'` 时返回空串）后再使用。
- **[测试/Windows] 服务端 52 个既有失败的真实根因：判定回归必须比「失败集合」而不是数量**：`./gradlew :server:test` 在本机长期固定 52 个失败，异常均为 `FileSystemException: *.sqlite: 另一个程序正在使用此文件`。根因是这些测试**从不调用 `database.close()`** 就在 `finally` 里删 SQLite（WAL 还有 `-wal`/`-shm`），Windows 不允许删除被占用文件，而 macOS 的 POSIX 语义允许，故原始开发环境永不暴露。**判定自己是否引入回归的唯一正确姿势**：起干净基线 worktree 跑同一套测试并逐条比对失败集合（历史实测：基线 154 用例/52 失败 vs 带新功能 160~162 用例/同样 52 失败且集合完全一致 ⇒ 零回归）。详见 [`SESSION-HIST-008`](file:///root/legado-server/docs/sessions/SESSION-HIST-008-windows-environment-and-verification-baseline.md)。
- **[插件/契约] 跨端契约必须用「真实模块导出面」验证，不能只靠文档一致**：插件宿主读 `loaded.activate`（具名导出），而契约文档与示例写的是 `export default function activate(sdk)`（默认导出挂在 `loaded.default`）→ 表现为「web.js 未导出 activate 函数」。定位手法是用 node **真实 import 被服务端托管的模块**打印导出面。同类问题：SDK 的 `settings` 是**顶层成员**（与 `api` 平级），`sdk.api` 只有 `get/post/put/del/plugin/pluginRaw`，示例里写 `api.settings.get()` 必然 `undefined.get`。
- **[插件/健壮性] 清单解析必须兼容 UTF-8 BOM，接口返回体必须带 charset**：Windows PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会写 BOM，使 `plugin.json` 开头多出 `\uFEFF` 导致清单解析失败、全部插件被卸载（重载接口返回 `{"reloaded":0}`）；仓库侧要既修文件也修解析器（与 Legado 书源导入的 BOM 兼容同源）。另外返回 `application/json` 时若省略 `charset=utf-8`，Windows PowerShell 会按 Latin-1 解码让中文变成乱码。
- **[部署/HTTP] 明文 HTTP 必须显式 `LEGADO_SECURE_COOKIES=false`，拦截者是浏览器而非服务端**：默认配置下服务端照常服务、登录也返回 200 并下发 `legado_session`，但该 Cookie 带 `Secure`，在 `http://` 下浏览器（以及 PowerShell 会话）**不会回传**，表现为「登录成功但立刻又是未登录」。局域网明文自建必须设为 `false`；公网走 HTTPS 反代并保持 `true`；命令行验证改用 `curl` 可绕开该干扰。
- **[文档/分支] 插件分支的文档编号与 master 撞号，合并前必须重编号**：`feat/plugin-system` 分支上的 `PROPOSAL-011-plugin-system-and-extension-points.md`、`ADR-011-plugin-runtime-dual-js-jar-and-trust-model.md`、`SESSION-012-plugin-system-implementation.md` 与 master 上已占用的 011/012（替换规则引擎、替换规则一级页面）冲突，合并时须整体顺延（015+），否则索引自相矛盾。历史细节见 [`SESSION-HIST-007`](file:///root/legado-server/docs/sessions/SESSION-HIST-007-plugin-system-and-api-doc.md)。
- **[本地书籍/虚拟书源] `loc_book` 虚拟书源与解析入库一体化**：本地上传的电子书（TXT/EPUB）使用标准 `sourceId = "loc_book"` 与 `local://<book_id>` 路径，解析后目录与正文直接写入 `book_toc_cache` 与 `book_content_cache`，使阅读器与 Edge-TTS 能够无差别透明调用，杜绝针对本地书籍另起炉灶建立平行阅读接口；TXT 编码按 BOM -> UTF-8 Strict -> GB18030 Strict 梯度探测，无明确章节时按固定字数自然换行降级切分。
- **[WebDAV/备份导入] Zip 纯内存流式按需读取与 Zip 炸弹防护**：导入备份包时严禁解压落盘，必须使用 `ZipFile` 按需匹配目标文件名流式读取入内存，并设定单条目（64MB）与整包（256MB）解压体积上限，同时彻底规避 Zip-Slip 与 Zip 炸弹。
- **[数据迁移/书架] 书架 `origin` 必须经 `normalizeSourceId` 归一化**：Legado App 的书架记录常携带带有 `##注解` 的书源 URL，落库前必须调用 `SourceCodec.normalizeSourceId` 剥离注解以与 `book_source` 主键对齐，杜绝书籍悬空。
- **[数据迁移/进度] 跨端阅读进度写入必须带 `excluded.updated_at >= reading_progress.updated_at` 防回退守卫**：导入备份进度时严禁无条件覆盖，必须以更新时间戳为守卫防止旧备份冲掉新进度；同时 Android 的字符偏移量 `durChapterPos` 不得强塞入服务端的百分比 `scroll_position`。
- **[工具/DSH] 历史会话挖掘（`/doc-init` 专用）**：DSH 会话记录位于 `%APPDATA%\dsh-desktop\harness\sessions\<工作区slug>\<session-id>\session.jsonl.zstd`，格式为**多帧 zstd**（一帧一条 JSONL）：`zstdDecompressSync` 只能解出第一帧（会话头），必须按魔数 `28 B5 2F FD` + 帧头/块头扫描帧边界后逐帧解压（Node 流式 zstd 解压器不支持拼接帧，会报 `Unknown frame descriptor`）；**切勿把会话内容交给 PowerShell 管道格式化（会 OOM）**，应让 Node 脚本写报告文件后再读。记录类型：`user/message`（`data.content[].text`）、`assistant/message`（`data.message.content[]`，内含 `tool-call` 项）、`tool/call`（`data.name` + `data.arguments` JSON 字符串）、`tool/result`、`todo/write`（`data.todos` 直接揭示工作范围）、`session/title`。
- **[环境/JDK] 本机 `JAVA_HOME` 可能指向失效目录，Gradle 会直接报错而非回退 PATH**：实测（2026-09-21）`JAVA_HOME` 残留为 `C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\`，而该目录内**没有 `bin\java.exe`**（环境被卸载或半安装），Gradle 报 `ERROR: JAVA_HOME is set to an invalid directory` 并终止（连 `compileKotlin` 都到不了），而 `java -version` 走 PATH 仍是正常的 Corretto 21。**解法**：每次构建前显式设 `$env:JAVA_HOME="C:\Program Files\Amazon Corretto\jdk21.0.12_9"`；排查时用 `Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe')` 验证，**不要因为 `Test-Path $env:JAVA_HOME` 为真就认为它可用**。
- **[Git] 本机 git 传输到 github.com 会被重置，但 REST API 稳定可用（2026-09-21 复现并更新旧结论）**：`git push` / `git ls-remote` / `curl` 打 `github.com/.../*.git/info/refs` 均失败（`Empty reply from server`、`Recv failure: Connection was reset`、`Failed to connect to github.com:443 ... Could not connect to server`），连试 4 次无一成功、也非代理配置问题（无 `http.proxy`/系统代理）；而 `https://api.github.com` 连续 3 次调用全部成功。**注意此现象是间歇性的**：同一次会话里曾有一次 `git ls-remote` 意外成功，因此**单次成功不足以证明通道已恢复，单次失败也不宜立刻放弃**。另：无 token 时 `/user` 返回 **401**，故 API 侧只能读公开仓库，**推送与建 PR 仍必须有凭据**。结论：远端信息优先走 REST API；推送失败时不要拿本地旧 ref 当「上游没变化」的依据。
- **[容器/云原生] 阿里云计算巢与 ECI 部署**：ROS 模板必须包含完整 VPC/安全组声明、ECI 容器组规格与数据持久化挂载；国内推荐使用阿里云个人镜像加速源。
- **[书架/分组与批量管理] 分组删除软解绑、预检重名与批量事务原子性**：删除 `book_group` 记录前必须在同一事务中先执行 `UPDATE book_shelf SET group_name=null WHERE group_name=? COLLATE NOCASE`，严禁级联删除组内图书与阅读进度；新建与重命名分组须显式执行 `SELECT count(*) ... COLLATE NOCASE` 预校验拦截重名并返回友好提示；批量改组/标记/删除须在单一事务中执行。
- **[构建/静态资源] Gradle 自动触发 buildWeb 与无条件打包 web/dist**：本地构建 JAR 时 Gradle 必须通过 `buildWeb` 任务自动执行 `npm run build`，且 `processResources` 须无条件引入 `web/dist`，严禁使用配置期 `if (file(...).exists())` 导致无预编译产物时打出无静态资源的空 JAR（引发 404 Not Found）；严禁在 `server/src/main/resources/` 遗留陈旧静态资源；本地未传 `LEGADO_DATA_DIR` 且 `/data` 不可写时安全降级至 `./data`。

---

## 6. 标准文档体系索引 (Proposals, Decisions & Sessions Index)

### 需求与功能提案 (PRD Proposals)
| 编号 | 标题 / 议题 | 关联文档 | 状态 |
| :--- | :--- | :--- | :--- |
| PROPOSAL-001 | Legado 规则执行引擎无头化迁移与 Ktor 服务端架构 | [`docs/proposals/PROPOSAL-001-headless-rule-engine-and-server-migration.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-001-headless-rule-engine-and-server-migration.md) | Implemented |
| PROPOSAL-002 | 多书源高并发流式搜索与多维智能排序引擎 | [`docs/proposals/PROPOSAL-002-multi-source-streaming-search-and-smart-ranking.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-002-multi-source-streaming-search-and-smart-ranking.md) | Implemented |
| PROPOSAL-003 | 整书离线缓存、断点续传与跨书源书架持久化 | [`docs/proposals/PROPOSAL-003-offline-book-caching-and-breakpoint-resume.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-003-offline-book-caching-and-breakpoint-resume.md) | Implemented |
| PROPOSAL-004 | 现代化 Web 阅读器、超大目录虚拟化与双栏宽屏排版 | [`docs/proposals/PROPOSAL-004-modern-web-reader-and-toc-virtualization.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-004-modern-web-reader-and-toc-virtualization.md) | Implemented |
| PROPOSAL-005 | 支持书源登录、凭据持久化与动态 LoginUI 交互 | [`docs/proposals/PROPOSAL-005-book-source-login-and-credential-storage.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-005-book-source-login-and-credential-storage.md) | Implemented |
| PROPOSAL-006 | 现代化 TTS 朗读引擎与沉浸式听书体验 | [`docs/proposals/PROPOSAL-006-tts-engine-and-immersive-reading-experience.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-006-tts-engine-and-immersive-reading-experience.md) | Implemented |
| PROPOSAL-007 | 服务端会话级连续 TTS 音频流与移动端后台稳定播放 | [`docs/proposals/PROPOSAL-007-server-session-tts-stream-and-mobile-background-playback.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-007-server-session-tts-stream-and-mobile-background-playback.md) | Implemented |
| PROPOSAL-008 | TTS 连续播放稳定性、相对时钟锚定与缓冲弹性架构 | [`docs/proposals/PROPOSAL-008-tts-continuous-playback-stability-and-drift-compensation.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-008-tts-continuous-playback-stability-and-drift-compensation.md) | Implemented |
| PROPOSAL-009 | TTS 播放管线穿透、反向代理防缓冲与首帧静音垫底优化 | [`docs/proposals/PROPOSAL-009-tts-stream-buffering-proxy-and-gesture-unlock.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-009-tts-stream-buffering-proxy-and-gesture-unlock.md) | Accepted |
| PROPOSAL-010 | 替换净化规则引擎与社区规则库导入体系 | [`docs/proposals/PROPOSAL-010-replace-rules-engine-and-community-purification.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-010-replace-rules-engine-and-community-purification.md) | Accepted |
| PROPOSAL-011 | 替换净化规则升级为一级独立页面与阅读器设置抽屉集成 | [`docs/proposals/PROPOSAL-011-first-class-replace-rules-page-and-reader-settings-integration.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-011-first-class-replace-rules-page-and-reader-settings-integration.md) | Accepted |
| PROPOSAL-012 | 完整 PWA 渐进式 Web 应用能力、用户自主正文分段离线缓存与沉浸式全屏抽屉适配 | [`docs/proposals/PROPOSAL-012-pwa-capabilities-and-fullscreen-drawer-adaptation.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-012-pwa-capabilities-and-fullscreen-drawer-adaptation.md) | Accepted |
| PROPOSAL-013 | 移动端全面屏死区安全区深度适配与替换净化规则 UI 体系化重构 | [`docs/proposals/PROPOSAL-013-mobile-safe-area-and-rules-ui-redesign.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-013-mobile-safe-area-and-rules-ui-redesign.md) | Accepted |
| PROPOSAL-014 | 书源批量整理、分组维护与轻量连通性健康体检体系 | [`docs/proposals/PROPOSAL-014-book-source-batch-management-and-health-check.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-014-book-source-batch-management-and-health-check.md) | Accepted |
| PROPOSAL-015 | 内置 WebDAV 服务端与数据目录 webdav 存储区（含 Web 端「文件」设置页面） | [`docs/proposals/PROPOSAL-015-webdav-storage-server.md`](file:///root/legado-server/docs/proposals/PROPOSAL-015-webdav-storage-server.md) | Tested & Deployed |
| PROPOSAL-016 | 本地图书导入与无缝阅读（TXT/EPUB 解析、智能分章与书架集成） | [`docs/proposals/PROPOSAL-016-local-book-import-txt-epub.md`](file:///root/legado-server/docs/proposals/PROPOSAL-016-local-book-import-txt-epub.md) | Tested |
| PROPOSAL-017 | 移植轻阅读书源规则解析/内容管线，修复聚合书源（大灰狼）正文提取为空 | [`docs/proposals/PROPOSAL-017-port-qingyue-rule-engine-and-fix-aggregate-content.md`](PROPOSAL-017-port-qingyue-rule-engine-and-fix-aggregate-content.md) | Implemented |
| PROPOSAL-018 | 书架分组管理与批量操作机制 | [`docs/proposals/PROPOSAL-009-bookshelf-grouping-and-batch-management.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-009-bookshelf-grouping-and-batch-management.md) | Accepted |
| PROPOSAL-019 | 支持 Kindle / 墨水屏版 Web UI (Simple-Web) 直连标准 REST API | [`docs/proposals/PROPOSAL-010-kindle-simple-web-ui.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/proposals/PROPOSAL-010-kindle-simple-web-ui.md) | Implemented |

### 架构决策记录 (ADR)
| 编号 | 决策标题 | 关联文档 | 状态 |
| :--- | :--- | :--- | :--- |
| ADR-001 | 彻底解耦 Android 原生平台，采用 Ktor + Kotlin JVM + Rhino JS 沙箱 | [`docs/decisions/ADR-001-pure-jvm-ktor-and-rhino-sandbox.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-001-pure-jvm-ktor-and-rhino-sandbox.md) | Accepted |
| ADR-002 | SQLite 读写分离、WAL 模式与原子状态计数优化 | [`docs/decisions/ADR-002-sqlite-wal-pooling-and-in-memory-counters.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-002-sqlite-wal-pooling-and-in-memory-counters.md) | Accepted |
| ADR-003 | 开书级联请求消除：单书源秒开 + 候补源懒加载 + TOC 虚拟滚动 | [`docs/decisions/ADR-003-lazy-candidate-loading-and-toc-virtualization.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-003-lazy-candidate-loading-and-toc-virtualization.md) | Accepted |
| ADR-004 | 阅读器跨章反向翻页定位状态机与异步排版守卫 | [`docs/decisions/ADR-004-cross-chapter-navigation-state-machine.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-004-cross-chapter-navigation-state-machine.md) | Accepted |
| ADR-005 | 书源登录鉴权、动态 LoginUI 驱动与凭据状态持久化 | [`docs/decisions/ADR-005-book-source-login-ui-and-session-state.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-005-book-source-login-ui-and-session-state.md) | Accepted |
| ADR-006 | 双模式 TTS 引擎、分片预缓冲与视口高亮联动架构 | [`docs/decisions/ADR-006-dual-tts-engine-and-audio-streaming-architecture.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-006-dual-tts-engine-and-audio-streaming-architecture.md) | Accepted |
| ADR-007 | 服务端会话级连续 MP3 音频流与独立进度事件通道 | [`docs/decisions/ADR-007-session-scoped-continuous-tts-audio-stream.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-007-session-scoped-continuous-tts-audio-stream.md) | Accepted |
| ADR-008 | TTS 单句相对时钟锚定、前瞻扩容与停滞看门狗架构 | [`docs/decisions/ADR-008-tts-relative-clock-and-buffer-resilience.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-008-tts-relative-clock-and-buffer-resilience.md) | Accepted |
| ADR-009 | TTS 音频流首帧静音垫底与反向代理穿透架构 | [`docs/decisions/ADR-009-tts-stream-resilience-and-zero-latency-preamble.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-009-tts-stream-resilience-and-zero-latency-preamble.md) | Accepted |
| ADR-010 | 替换净化执行管道选型、作用域匹配与沙箱安全 | [`docs/decisions/ADR-010-replace-rules-pipeline-and-scope-matching.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-010-replace-rules-pipeline-and-scope-matching.md) | Accepted |
| ADR-011 | 替换规则升级为主导航一级页面与阅读器设置抽屉模块化收敛 | [`docs/decisions/ADR-011-first-class-replace-rules-navigation-and-reader-settings.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-011-first-class-replace-rules-navigation-and-reader-settings.md) | Accepted |
| ADR-012 | 采用 vite-plugin-pwa 构筑双层用户可控离线缓存体系与 Safe-Area 沉浸式安全区适配 | [`docs/decisions/ADR-012-vite-plugin-pwa-workbox-and-safe-area-layout.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-012-vite-plugin-pwa-workbox-and-safe-area-layout.md) | Accepted |
| ADR-013 | 全面屏安全区变量统一继承体系与替换规则设计系统化重构 | [`docs/decisions/ADR-013-safe-area-layout-and-rules-design-system.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-013-safe-area-layout-and-rules-design-system.md) | Accepted |
| ADR-014 | 书源批量事务管道、轻量并发探针与浮动管理状态机 | [`docs/decisions/ADR-014-source-batch-operations-and-lightweight-probe-pipeline.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-014-source-batch-operations-and-lightweight-probe-pipeline.md) | Accepted |
| ADR-015 | 进程内 WebDAV 服务端选型、Basic 鉴权与最小 Class 2 锁实现 | [`docs/decisions/ADR-015-webdav-server-class2-minimal.md`](file:///root/legado-server/docs/decisions/ADR-015-webdav-server-class2-minimal.md) | Accepted |
| ADR-016 | 本地图书（TXT/EPUB）解析引擎、虚拟书源与解析入库一体化架构 | [`docs/decisions/ADR-016-local-book-parsing-and-storage-architecture.md`](file:///root/legado-server/docs/decisions/ADR-016-local-book-parsing-and-storage-architecture.md) | Accepted |
| ADR-017 | 保留自主规则引擎，以「语义补齐 + 切分器移植」承接轻阅读书源管线 | [`docs/decisions/ADR-017-retain-self-engine-and-port-semantics.md`](ADR-017-retain-self-engine-and-port-semantics.md) | Accepted |
| ADR-018 | 书架分组存储模型、状态联动与原子批量操作设计 | [`docs/decisions/ADR-009-bookshelf-grouping-schema-and-batch-mutation.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-009-bookshelf-grouping-schema-and-batch-mutation.md) | Accepted |
| ADR-019 | Simple-Web 极简前端直接适配标准 REST API 与静态资源内置架构 | [`docs/decisions/ADR-010-kindle-simple-web-and-legacy-api-compatibility.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/decisions/ADR-010-kindle-simple-web-and-legacy-api-compatibility.md) | Accepted |

### 工作记忆与历史推演归档 (Sessions Chronicle)
| 日期 / ID | 类型 | 标题 / 议题 | 关联文档 | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| 2026-08-15 | Hist | 开书延迟、正文离线缓存吞吐与目录性能重构 | [`docs/sessions/SESSION-HIST-001-reading-performance-and-cache.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-HIST-001-reading-performance-and-cache.md) | Pushed |
| 2026-08-30 | Hist | 阅读器跨章反向翻页定位与桌面端双栏/宽屏排版 | [`docs/sessions/SESSION-HIST-002-reverse-chapter-navigation-and-desktop-layout.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-HIST-002-reverse-chapter-navigation-and-desktop-layout.md) | Pushed |
| 2026-08-29 | Hist | 跨书源封面与正文融合、书架空值判定与搜索中断修复 | [`docs/sessions/SESSION-HIST-003-multi-source-fusion-and-shelf-null-handling.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-HIST-003-multi-source-fusion-and-shelf-null-handling.md) | Pushed |
| 2026-08-30 | Init | 初始化标准文档体系与历史会话挖掘继承 (`/doc-init`) | [`docs/`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/) | Accepted |
| 2026-08-30 | Fix | 统一登录界面品牌 Logo 与设计语言微调 | - | Pushed |
| 2026-08-31 | Feat | 配置 GitHub Actions 自动编译与分发可执行 JAR 及分发包 | - | Pushed |
| 2026-08-31 | Feat | 支持书源登录鉴权、动态 LoginUI 与凭据状态持久化 | [`docs/sessions/SESSION-HIST-004-book-source-login-implementation.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-HIST-004-book-source-login-implementation.md) | Pushed |
| 2026-08-31 | Fix | 修复书源导入解析（支持自定义标识/BOM/外层包装），优化交互反馈 | - | Pushed |
| 2026-08-31 | Feat | 书源全场景凭据获取、Chrome扩展穿透同步与直接填入交互 | [`docs/sessions/SESSION-HIST-005-source-login-and-cookie-extension.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-HIST-005-source-login-and-cookie-extension.md) | Pushed |
| 2026-08-31 | Fix | 修复 Actions Release 滚动发布时 Tag 未同步移动到最新 Commit 的问题 | - | Pushed |
| 2026-08-31 | Feat | 现代化 TTS 朗读引擎与沉浸式听书体验 | [`docs/sessions/SESSION-HIST-006-tts-engine-and-immersive-reading-experience.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-HIST-006-tts-engine-and-immersive-reading-experience.md) | Pushed |
| 2026-08-31 | Fix | 更新 Edge-TTS 握手协议至 Chrome 143，添加 muid Cookie 修复连接鉴权 | - | Pushed |
| 2026-08-31 | Fix | 防御后引号切片导致 0 字节 blob 引发 ERR_REQUEST_RANGE_NOT_SATISFIABLE | [`docs/acceptance/ACCEPT-TTS-001.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/acceptance/ACCEPT-TTS-001.md) | Pushed |
| 2026-08-31 | Fix | 修复 TTS 跨章连播正文加载时序竞态与未开启朗读时点击误触发 | - | Pushed |
| 2026-09-05 | Docs | 新增阿里云计算巢一键秒级部署入口与使用说明 | - | Pushed |
| 2026-09-05 | Feat | 服务端会话级连续 TTS 音频流与移动端后台稳定播放 | [`docs/acceptance/ACCEPT-007-server-session-tts-stream.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/acceptance/ACCEPT-007-server-session-tts-stream.md) | Accepted & Pushed |
| 2026-09-05 | Fix | 优化 Actions Release 发布逻辑，发布前清理旧 release 确保时间戳刷新并追加构建时间 | - | Pushed |
| 2026-09-05 | Fix | 修复 CodeQL 扫描告警：补充 CI 权限声明并过滤封面图片协议防 XSS | - | Pushed |
| 2026-09-05 | Fix | 过滤 audio.play() 暂停与切流打断错误，消除 pause 打断时的 Toast 警告提示 | - | Pushed |
| 2026-09-05 | Fix | 根治 TTS 连续播放约 2 分钟时钟漂移累积死锁与章末静音停滞：单句相对锚定、600ms看门狗与5分片前瞻 | [`docs/acceptance/ACCEPT-008-tts-continuous-playback-stability.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/acceptance/ACCEPT-008-tts-continuous-playback-stability.md) | Accepted & Pushed |
| 2026-09-05 | Feat | 开启 TTS 朗读时自动对齐当前视口最上方首个完整可见段落 | [`docs/acceptance/ACCEPT-008-tts-continuous-playback-stability.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/acceptance/ACCEPT-008-tts-continuous-playback-stability.md) | Accepted & Pushed |
| 2026-09-09 | Fix | TTS 播放管线穿透、反向代理防缓冲与首帧静音垫底优化 | [`docs/sessions/SESSION-008-tts-buffering-proxy-and-silence-preamble.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-008-tts-buffering-proxy-and-silence-preamble.md) | Accepted & Pushed |
| 2026-09-09 | Fix | 根治浏览器探针并发锁（500）与连接断开误自毁：SharedFlow 广播解耦 | [`docs/acceptance/ACCEPT-009-tts-stream-buffering-proxy-and-multi-stream-resilience.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/acceptance/ACCEPT-009-tts-stream-buffering-proxy-and-multi-stream-resilience.md) | Accepted & Pushed |
| 2026-09-12 | Feat | 内置浏览器反向代理登录与复杂聚合书源生态兼容 (#2) | [`docs/sessions/SESSION-010-source-webview-login-and-aggregate-compat.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-010-source-webview-login-and-aggregate-compat.md) | Accepted & Pushed |
| 2026-09-12 | Fix | 恢复 Gradle Wrapper 官方下载源，修复 Actions 境外构建超时 | - | Pushed |
| 2026-09-12 | Feat | 替换净化规则引擎、反爬混淆还原与Rhino沙箱执行管道 | [`docs/sessions/SESSION-011-replace-rules-engine-and-anti-crawler-restoration.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-011-replace-rules-engine-and-anti-crawler-restoration.md) | Accepted & Pushed |
| 2026-09-15 | Feat | 替换净化规则升级为一级独立页面与阅读器设置抽屉集成 | [`docs/sessions/SESSION-012-first-class-replace-rules-and-reader-settings.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-012-first-class-replace-rules-and-reader-settings.md) | Accepted & Pushed |
| 2026-09-14 | Init | 环境搭建（winget 装 Git、Corretto 21）、克隆并启动 legado-server、生成可见窗口 `start-legado.cmd` | [`docs/sessions/SESSION-HIST-008-windows-environment-and-verification-baseline.md`](file:///root/legado-server/docs/sessions/SESSION-HIST-008-windows-environment-and-verification-baseline.md) | Archived |
| 2026-09-15 | Feat | 插件系统（服务端）：`plugin-api` 轻量契约模块、JAR(ServiceLoader)/JS 双运行时、宿主 API 与 `/api/plugins*` 路由 | [`docs/sessions/SESSION-HIST-007-plugin-system-and-api-doc.md`](file:///root/legado-server/docs/sessions/SESSION-HIST-007-plugin-system-and-api-doc.md) | 分支未合并 |
| 2026-09-15 | Feat | 插件系统（前端）：插件宿主、插件 SDK、插件管理页、动态导航项与 hash 路由 | [`docs/sessions/SESSION-HIST-007-plugin-system-and-api-doc.md`](file:///root/legado-server/docs/sessions/SESSION-HIST-007-plugin-system-and-api-doc.md) | 分支未合并 |
| 2026-09-16 | Docs | 生成 `reader/API.md`（79 个应用端点 + 5 条静态路由 + 插件 10 端点，刻意放在仓库外） | [`docs/sessions/SESSION-HIST-007-plugin-system-and-api-doc.md`](file:///root/legado-server/docs/sessions/SESSION-HIST-007-plugin-system-and-api-doc.md) | Done |
| 2026-09-16 | Fix | 插件系统四类排错：activate 导出契约不一致、`api.settings` 误用、`Map<String,Any>` 序列化 500、`plugin.json` BOM 导致插件全卸载 | [`docs/sessions/SESSION-HIST-007-plugin-system-and-api-doc.md`](file:///root/legado-server/docs/sessions/SESSION-HIST-007-plugin-system-and-api-doc.md) | 分支未合并 |
| 2026-09-16 | Fix | 定位并证实服务端 52 个既有测试失败的根因（Windows 删除被占用 SQLite），确立「干净基线 worktree 比对失败集合」的回归判定方法论 | [`docs/sessions/SESSION-HIST-008-windows-environment-and-verification-baseline.md`](file:///root/legado-server/docs/sessions/SESSION-HIST-008-windows-environment-and-verification-baseline.md) | Archived |
| 2026-09-15 | Feat | 完整 PWA 渐进式能力、用户自主正文分段离线缓存与沉浸式全屏抽屉适配 | [`docs/sessions/SESSION-013-pwa-and-offline-caching-architecture.md`](file:///root/legado-server/docs/sessions/SESSION-013-pwa-and-offline-caching-architecture.md) | Accepted & Pushed |
| 2026-09-16 | Feat | 移动端全面屏死区安全区深度适配与替换净化规则 UI 体系化重构 | [`docs/acceptance/ACCEPT-013-mobile-safe-area-and-rules-ui-redesign.md`](file:///root/legado-server/docs/acceptance/ACCEPT-013-mobile-safe-area-and-rules-ui-redesign.md) | Accepted & Pushed |
| 2026-09-17 | Feat | 书源批量整理、分组维护与轻量连通性健康体检体系 | [`docs/sessions/SESSION-015-book-source-batch-management-and-health-check.md`](file:///root/legado-server/docs/sessions/SESSION-015-book-source-batch-management-and-health-check.md) | Accepted & Pushed |
| 2026-09-17 | Quickfix | 修复大灰狼等聚合书源因默认上游节点（v5.czyl.cf）下线导致的搜索超时报空 | - | Pushed |
| 2026-09-17 | Quickfix | 重置默认分支为 main 并同步 CI/CD 流水线，清除 GitHub 历史悬挂贡献者缓存 | - | Pushed |
| 2026-09-17 | Feat | 内置 WebDAV 服务端与 Web 端「文件」设置页面，数据目录新增 webdav 文件夹存放上传数据 | [`docs/acceptance/ACCEPT-015-webdav-storage.md`](file:///root/legado-server/docs/acceptance/ACCEPT-015-webdav-storage.md) · [`docs/sessions/SESSION-016-webdav-storage-server.md`](file:///root/legado-server/docs/sessions/SESSION-016-webdav-storage-server.md) | Tested & Deployed |
| 2026-09-18 | Init | `/doc-init` 增量：挖掘 DSH 历史会话（5 份可用会话 / 18,577 帧 / 67 条用户诉求 / 1,060 次工具调用），补录插件系统时代与 Windows 验证基线，新增 HIST-007/008 两部历史归档并增量更新 AGENTS 索引与部落知识 | [`docs/sessions/SESSION-HIST-007-plugin-system-and-api-doc.md`](file:///root/legado-server/docs/sessions/SESSION-HIST-007-plugin-system-and-api-doc.md) · [`docs/sessions/SESSION-HIST-008-windows-environment-and-verification-baseline.md`](file:///root/legado-server/docs/sessions/SESSION-HIST-008-windows-environment-and-verification-baseline.md) | Local（未推送） |
| 2026-09-18 | Feat | 支持书架自定义分组与批量管理（SQLite持久化、安全降级、多选浮动操作栏） | [`docs/sessions/SESSION-008-bookshelf-grouping-and-batch-management.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-008-bookshelf-grouping-and-batch-management.md) | Accepted & Pushed |
| 2026-09-19 | Feat | 支持 Kindle / 墨水屏版 Web UI (Simple-Web) 直连标准 REST API | [`docs/sessions/SESSION-009-kindle-simple-web-and-legacy-api.md`](file:///Users/zhangran/Documents/antigravity/joyful-galileo/docs/sessions/SESSION-009-kindle-simple-web-and-legacy-api.md) | Accepted & Pushed |
| 2026-09-19 | Fix | 修复本地构建 JAR 缺少前端静态资源报 Not Found：自动关联 buildWeb、无条件引入 web/dist 并优化数据目录降级 | - | Accepted & Pushed |
| 2026-09-19 | Feat | 本地图书（TXT/EPUB）导入解析、虚拟书源与书架无缝集成（智能编码探测、正则分章、纯 JVM EPUB 抽取、SVG 艺术封面） | [`docs/acceptance/ACCEPT-016-local-book-import-txt-epub.md`](file:///root/legado-server/docs/acceptance/ACCEPT-016-local-book-import-txt-epub.md) · [`docs/proposals/PROPOSAL-016-local-book-import-txt-epub.md`](file:///root/legado-server/docs/proposals/PROPOSAL-016-local-book-import-txt-epub.md) · [`docs/sessions/SESSION-017-local-book-import-txt-epub.md`](file:///root/legado-server/docs/sessions/SESSION-017-local-book-import-txt-epub.md) | Tested |
| 2026-09-20 | Feat | WebDAV「文件」页支持导入 Legado 备份包（书源/替换规则/书架/阅读进度）、纯内存流式解析与防回退守卫 (PR #6) | [`docs/sessions/SESSION-018-webdav-legado-backup-import.md`](file:///root/legado-server/docs/sessions/SESSION-018-webdav-legado-backup-import.md) | Accepted & Pushed |
| 2026-09-20 | Fix | 修复聚合书源（大灰狼）正文为空 **两处根因**：① jsLib 与规则脚本分离求值、按规则脚本自身顶层 `return` 决定 IIFE 包裹、补全值语义恢复、`lastError` 成功即清空；② `cleanContent()` 删 `<div>` 会把「整体包一层 div」的正文删光，改为清洗为空时退化为只剥标签。新增 `JsSandboxCompletionValueTest`（19 用例） | [`docs/proposals/PROPOSAL-017-port-qingyue-rule-engine-and-fix-aggregate-content.md`](docs/proposals/PROPOSAL-017-port-qingyue-rule-engine-and-fix-aggregate-content.md) · [`docs/decisions/ADR-017-retain-self-engine-and-port-semantics.md`](docs/decisions/ADR-017-retain-self-engine-and-port-semantics.md) · [`docs/sessions/SESSION-019-dagou-content-root-cause.md`](docs/sessions/SESSION-019-dagou-content-root-cause.md) · [`docs/acceptance/ACCEPT-017-aggregate-content-fix.md`](docs/acceptance/ACCEPT-017-aggregate-content-fix.md) | Accepted & Pushed |
| 2026-09-22 | Docs | 根据最新 Git Commit 全面同步更新 README.md（增补本地书籍导入、Legado备份还原、WebDAV服务、Edge-TTS音频流、PWA脱机阅读、替换规则等）并独立生成全量英文版 README_EN.md | - | Accepted & Pushed |
| 2026-09-24 | Quickfix | 修复 GitHub CodeQL 扫描 3 处告警（字符串转义、URL子串检查与封面图XSS过滤） | - | Accepted & Pushed |

---

## 7. Project Structure & Module Organization

This repository is dedicated to the standalone Legado Server ecosystem: the standalone backend server and the web client.

- **`server/`**: Standalone headless backend server built with Ktor (`io.legado.server`), Kotlin JVM, and SQLite. Handles source parsing and execution (`RuleRunner` with Jsoup, JsonPath, and Rhino JS engine), authentication/sessions, book and cover caching, source subscription synchronization, and API routing.
- **`web/`**: Web reader and management UI (`legado-server-web`) built with React 19, TypeScript, and Vite. Communicates with `server/` APIs to manage sources, search books across sources, debug rules, and read books.

---

## 8. Coding Style & Naming Conventions

- **Kotlin**: Four-space indentation, idiomatic Kotlin constructs. Match nearby code for brace placement, imports, and null handling. Use PascalCase for classes and files (`BookCacheService.kt`), camelCase for functions and properties.
- **TypeScript / React**: Modern functional components with hooks, strict TypeScript types, and organized CSS in `web/src/styles.css`.
- **Security & Reliability**: Standalone server must enforce authentication, PBKDF2 password hashing, session cookies, CSRF protection, and sandboxed JS evaluation.

---

## 9. Testing Guidelines

- Add or update focused regression tests for any behavior changes (e.g. `RuleRunnerTest.kt`, `DatabaseTest.kt`, `CoverCacheTest.kt`, `BookCacheServiceTest.kt`).
- Prefer deterministic unit tests in `src/test/`.
- Ensure all relevant test suites pass before submitting changes (`./gradlew :server:test`, `npm run check`, `npx tsx web/test/run-all.ts`, etc.).

---

## 10. Commit & Pull Request Guidelines

- Recent commits use concise Chinese imperative summaries or conventional commit format (e.g. `优化翻页交互：从章节开头回翻定位至上一章末尾`, `fix(web): 修复点击停止搜索误触表单提交导致重新发起搜索的问题`).
- Keep commits focused and describe the user-visible change or module scope.
- Never commit passwords, signing credentials, API tokens, or local `.env` secrets.

---

## 11. Engineering Principles

- 不保留向后兼容性。应删除过时的路径，而不是添加兼容层、回退机制或迁移方案。
- 选择能够完全满足当前需求的最简单实现。避免引入臆测性的抽象、配置和间接层。
- 以分层方式逐步构建系统。先实现能够端到端运行的最小版本，再在已有可用产品的基础上逐项增加新能力。绝不要为了尚未完成的复杂设计而牺牲一个可正常运行的产品。
- 保持组件模块化，并清晰分离各项职责（服务端解析、前端交互、数据持久化各司其职）。
- 当成熟且维护良好的库能够降低整体复杂度或提高可靠性时，应优先使用。除非有明确理由，否则不要重复实现常见功能。
