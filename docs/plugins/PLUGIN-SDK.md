# Legado Server 插件 SDK

插件系统让第三方代码在不修改、不重新编译服务端的前提下扩展 **后端能力** 与 **Web 界面**：
往主页面加导航按钮与菜单项、提供整页界面、读写书架与书源、注册自己的 HTTP 端点，
或者对外提供一个完整的协议服务（WebDAV、OPDS 这类）。

---

## 1. 插件放在哪

插件放在 **数据目录下的 `plugins/`**，可以用环境变量覆盖：

```
<LEGADO_DATA_DIR>/plugins/<插件 id>/
```

| 环境变量 | 默认值 |
| --- | --- |
| `LEGADO_DATA_DIR` | `/data`（Windows 本地部署通常是 `.\ls_data`） |
| `LEGADO_PLUGINS_DIR` | `$LEGADO_DATA_DIR/plugins` |

一个插件就是一个文件夹：

```
<dataDir>/plugins/hello/
├── plugin.json      必填：清单，声明入口、权限与设置项
├── server.js        可选：后端脚本（Rhino 沙箱执行）
├── web.js           可选：前端 ES module（宿主注入 SDK）
├── lib/*.jar        可选：JVM 插件（ServiceLoader 加载）
├── assets/**        可选：前端静态资源，通过 /assets/ 访问
└── data/            运行期自动创建，插件私有的可写目录
```

> 本仓库**不随包分发任何示例插件**：插件目录里有什么，服务端就加载什么。把你写好的插件文件夹
> 放进这个目录，重载即可生效；升级服务端不会动你的插件（它们本来就在你的数据目录里）。

服务端启动时扫描目录；运行期也可以在「插件管理」页面里 **重载 / 启用 / 停用**，无需重启。

---

## 2. plugin.json

```json
{
  "id": "hello",
  "name": "你好插件",
  "version": "1.0.0",
  "description": "一句话说明插件做什么",
  "author": "your-name",
  "apiVersion": 1,
  "enabled": true,
  "server": "server.js",
  "web": "web.js",
  "jars": ["lib/hello-plugin.jar"],
  "mainClass": "com.example.HelloPlugin",
  "permissions": ["storage", "settings", "books.read"],
  "settings": [
    { "key": "greeting", "label": "打招呼语", "type": "text", "default": "你好", "hint": "提示文字" },
    { "key": "mode", "label": "模式", "type": "select", "default": "a",
      "options": [{ "label": "模式 A", "value": "a" }, { "label": "模式 B", "value": "b" }] }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | 必填。只允许小写字母、数字与 `. _ -`，且以字母或数字开头；同时是文件夹名与 URL 段 |
| `name` | 必填。界面显示名 |
| `version` | 前端用它做 `web.js` 的缓存失效，改动前端后请递增 |
| `apiVersion` | 宿主 API 版本，当前为 `1`；大于服务端支持的版本会被拒绝加载 |
| `server` | 后端脚本路径（Rhino，支持 ES6 语法子集） |
| `web` | 前端 ES module 路径 |
| `jars` | JVM 插件 jar 列表 |
| `mainClass` | 可选。显式指定 `LegadoPlugin` 实现；不填则用 `ServiceLoader` 自动发现 |
| `permissions` | 权限清单，见第 5 节。**未声明的能力会在调用时直接报错**，不会静默返回空数据 |
| `settings` | 设置项 schema，由「插件管理」页面自动渲染成表单 |

设置项字段：`key`（必填）、`label`、`type`（`text` / `password` / `number` / `boolean` / `select` / `textarea`）、
`default`、`options`（`select` 必填）、`hint`。

> `server`、`web`、`jars` 至少要有一项，否则清单会被判为无效。

---

## 3. 后端脚本 `server.js`

脚本在 **Rhino 沙箱** 里执行：禁用一切 Java 反射，只能通过全局对象 `legado` 与宿主交互。
整个脚本在插件专属的单线程上运行，因此模块级变量是安全的，但不要在插件里阻塞太久
（单次调用超过 60 秒会被判定为超时）。

```js
legado.log.info('插件已加载');

// 注册一个需要登录才能访问的端点：/api/plugins/hello/r/ping
legado.route('GET', '/ping', function (req) {
  return { pong: true, at: Date.now() };
});

// 注册一个免登录端点（需要 routes.public 权限；务必自行鉴权）
legado.routePublic('GET', '/public', function (req) {
  return '公开内容';
});

// 读取书架 + 使用自己的设置
legado.route('GET', '/shelf', function () {
  var items = legado.books.listShelf();
  return { greeting: legado.settings.get('greeting', '你好'), count: items.length, items: items };
});

// 订阅宿主事件
legado.on('shelf.add', function (event) {
  legado.log.info('新书入库：' + event.payload.name);
});

// 定时任务（句柄可用于取消）
var handle = legado.schedule(60000, 3600000, function () { legado.log.info('每小时一次'); });

// 卸载时调用（可选）
function deactivate() {
  legado.unschedule(handle);
}
```

### 3.1 路由处理函数

收到 `req`：

| 字段 | 说明 |
| --- | --- |
| `method` / `path` | 请求方法与相对路径（相对于插件挂载点） |
| `query` | 查询参数对象，**同时包含** `{name}` 占位符捕获到的路径参数 |
| `headers` / `body` | 请求头对象与请求体字符串 |
| `remoteHost` / `authenticated` | 来源地址；是否携带了有效管理员会话 |
| `basePath` | 本插件的挂载前缀，例如 `/api/plugins/hello/r`，生成链接时请用它 |

返回值可以是：

| 返回 | 结果 |
| --- | --- |
| 字符串 | `200 text/plain` |
| 普通对象 | `200 application/json`（自动序列化） |
| `{ status, headers, contentType, body }` | 完整控制状态码与响应头 |
| `{ status, bodyBase64 }` | 返回二进制内容（Base64 解码） |

路由路径支持 `{name}` 单段占位符与 **结尾 `*` 通配**（`/*` 可匹配任意深度，用于 WebDAV 这类协议端点）。

### 3.2 `legado` 完整 API

```js
// 元信息
legado.pluginId, legado.pluginName, legado.pluginVersion, legado.apiVersion

// 日志
legado.log.debug|info|warn|error(message)

// 键值存储（持久化在服务端数据库，按插件隔离）
legado.storage.get(key) → string|null
legado.storage.set(key, value) / remove(key) / keys() → string[] / clear()
legado.storage.getJson(key) → object|null / setJson(key, value)

// 设置
legado.settings.all() → object
legado.settings.get(key, fallback)
legado.settings.update(obj) / replace(obj) → object

// 书架与阅读
legado.books.listShelf() / getShelfItem(sourceId, bookUrl)
legado.books.addToShelf(fields) / removeFromShelf(sourceId, bookUrl)
legado.books.setCompleted(sourceId, bookUrl, bool)
legado.books.getProgress(sourceId, bookUrl) / saveProgress(fields)
legado.books.getToc(sourceId, tocUrl)
legado.books.getCachedChapters(sourceId, bookUrl) / listCachedChapterUrls(sourceId, bookUrl)
legado.books.getCachedContent(sourceId, bookUrl, chapterUrl)
legado.books.requestOfflineCache(sourceId, bookUrl) / cancelOfflineCache(sourceId, bookUrl)
// 实时规则执行（走网络，较慢）
legado.books.search(sourceId, keyword) / fetchDetails(sourceId, bookUrl)
legado.books.fetchChapters(sourceId, tocUrl) / fetchContent(sourceId, chapterUrl, bookName)

// 书源
legado.sources.list(query) / get(id) / save(json) / remove(id)
legado.sources.setEnabled(id, bool) / export(ids)

// 订阅
legado.subscriptions.list(enabledOnly) / add(url) / remove(id)
legado.subscriptions.refresh(id) / refreshAll()

// 封面
legado.covers.cacheUrl(url) / contentType(key) / delete(key)

// 出站 HTTP
legado.http.get(url, headers) / post(url, body, contentType)
legado.http.request(method, url, headers, body, timeoutMs) → { status, headers, body }

// 鉴权
legado.auth.verifyAdminPassword(password) → bool

// 事件与定时
legado.events.emit(type, payload)     // 自定义事件必须以 plugin. 开头
legado.on(eventType, handler)         // 支持 "*" 订阅全部事件
legado.schedule(initialDelayMs, periodMs, fn) → handle
legado.unschedule(handle)
```

宿主事件类型（`legado.on` 可订阅）：`server.start`、`server.stop`、`plugin.loaded`、
`plugin.unloaded`、`shelf.add`、`shelf.remove`、`shelf.switchSource`、`source.import`、
`source.delete`、`progress.save`、`book.cache.start`、`book.cache.finish`。

---

## 4. 前端模块 `web.js`

`web.js` 必须是真正的 ES module，导出一个 `activate(sdk)` 函数；宿主在用户登录后动态导入它。
**不要 `import React`** —— 界面必须使用宿主注入的 `sdk.React`，否则会出现两份 React 运行时。

下面三种写法完全等价，宿主都接受，选你顺手的即可（文档示例统一用第一种）：

```js
export default function activate(sdk) {}          // 约定写法
export function activate(sdk) {}                   // 具名导出
export default { activate, deactivate }            // 默认导出对象
```

对应的 `deactivate` 可以是具名导出，也可以是默认导出对象上的方法。若一个模块都没有提供，
宿主会明确报 `web.js 未导出 activate 函数：请用 export default function activate(sdk) ...`。

```js
export default function activate(sdk) {
  const React = sdk.React
  const { useState } = sdk.hooks

  function HelloPage() {
    const [count, setCount] = useState(0)
    return React.createElement(
      sdk.ui.Card,
      { title: '你好' },
      React.createElement('p', null, '这是插件提供的页面'),
      React.createElement(sdk.ui.Button, { variant: 'primary', onClick: () => setCount(count + 1) }, '点了 ' + count + ' 次'),
    )
  }

  sdk.registerPage({ id: 'hello', title: '你好插件', icon: 'book', render: () => React.createElement(HelloPage) })
  sdk.registerNavItem({ id: 'hello', label: '你好', icon: 'book', page: 'hello' })
  sdk.registerMenuItem({ id: 'hello-toast', label: '打个招呼', icon: 'book', onClick: () => sdk.toast.success('你好！') })
}

export function deactivate() {}
```

### 4.1 SDK

| 成员 | 说明 |
| --- | --- |
| `sdk.plugin` | `{ id, name, version, description, author, directory }` |
| `sdk.React` / `sdk.hooks` | 宿主 React 运行时与 hooks（`useState`/`useEffect`/`useMemo`/`useCallback`/`useRef`/`useContext`/`useReducer`） |
| `sdk.ui` | `Button` `Icon` `Modal` `Card` `Spinner` `Empty` `PageHeader` `Field` `Table` |
| `sdk.api.get/post/put/del(path, body)` | 走宿主 REST 客户端，自动带凭据与 CSRF |
| `sdk.api.plugin(subPath, init)` / `pluginRaw` | 请求 `/api/plugins/<id>/r/<subPath>` |
| `sdk.settings.get()` / `update(values)` | 读写插件设置（对应管理页面的表单） |
| `sdk.storage.get/set/remove(key)` | 浏览器 `localStorage`，宿主自动加 `legado-plugin:<id>:` 前缀 |
| `sdk.toast.info/success/warning/error(msg)` | 宿主提示条 |
| `sdk.navigate(page)` | 跳转宿主页面或插件页面 |
| `sdk.pages` | 宿主页面常量：`library` `shelf` `sources` `subscriptions` `plugins` |
| `sdk.registerNavItem({ id, label, icon, title, page })` | 顶部导航按钮；`page` 省略时跳到 **同名页面** |
| `sdk.registerMenuItem({ id, label, icon, onClick })` | 右上角菜单项 |
| `sdk.registerPage({ id, title, icon, render })` | 整页界面，渲染在宿主外壳内容区，带错误边界 |

插件页面在地址栏里表示为 `#plugin:<插件 id>:<页面 id>`。

静态资源走 `GET /api/plugins/<id>/assets/<路径>`（需要会话）。

---

## 5. 权限

| 权限 | 授予的能力 |
| --- | --- |
| `storage` | 插件私有键值存储 |
| `settings` | 读写插件设置 |
| `books.read` | 书架、阅读进度、目录与离线缓存正文 |
| `books.write` | 增删书架条目、标记读完、触发/取消离线缓存 |
| `books.network` | 实时执行书源规则（`search` / `fetch*`） |
| `sources.read` / `sources.write` | 读 / 写书源 |
| `subscriptions.read` / `subscriptions.write` | 读 / 写订阅 |
| `covers` | 读取封面缓存 |
| `http` / `http.private` | 出站 HTTP 请求（默认禁止内网与回环地址以防 SSRF） |
| `auth` | 校验管理员密码 |
| `events` | 收发事件 |
| `schedule` | 注册定时任务 |
| `routes.public` | 注册 **免会话** 路由（供 WebDAV / OPDS 这类外部客户端使用） |

未声明的权限会在调用点抛出 `插件未声明权限: <name>`，并由宿主记录到插件错误信息里。

---

## 6. JVM（JAR）插件

需要协议级能力、流式 IO 或更高性能时，用 Kotlin/Java 编写 JVM 插件。只需依赖 `plugin-api` 模块：

```kotlin
class HelloPlugin : LegadoPlugin {
    override fun activate(context: PluginContext) {
        context.route("GET", "/hello") { request ->
            PluginResponse.json(mapOf("hello" to context.settings.string("greeting", "你好")))
        }
    }
    override fun deactivate() = Unit
}
```

再声明 ServiceLoader 文件（或改用 `mainClass`）：

```
src/main/resources/META-INF/services/io.legado.plugin.api.LegadoPlugin
```

jar 放进 `lib/` 并在 `plugin.json` 的 `jars` 里登记即可。JVM 插件与 JS 脚本可以共存于同一个
插件（`js+jar`），两者共享同一份权限与同一个 `PluginContext`。

`PluginContext` 提供与 JS 侧一一对应的能力：`logger` `storage` `settings` `events` `books`
`sources` `subscriptions` `covers` `http` `auth`，以及 `route` / `routePublic` / `on` / `schedule`。
跨边界的数据统一用 JSON 形状的 `Map<String, Any?>`，因此 `plugin-api` 不拉入任何第三方依赖，
插件 jar 可以非常小：核心契约 `plugin-api` 零第三方依赖，一个只做一件事的插件往往就是「一个类 + 一份
ServiceLoader 声明」。

---

## 7. 管理接口（供前端与管理脚本使用）

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/plugins` | 插件摘要列表（`id`/`name`/`version`/`runtime`/`enabled`/`loaded`/`permissions`/`settingsSchema`/`routes`/`error`/`directory`） |
| `GET /api/plugins/{id}` | 单个插件摘要 |
| `POST /api/plugins/reload` | 重新扫描插件目录（CSRF） |
| `POST /api/plugins/{id}/enable` \| `/disable` | 启用 / 停用（CSRF） |
| `GET` / `PUT /api/plugins/{id}/settings` | 读取 / 更新设置（`PUT` 需 CSRF；只接受清单里声明过的 key） |
| `GET /api/plugins/{id}/web.js` | 前端模块源码（`text/javascript`，需会话，不需要 CSRF） |
| `GET /api/plugins/{id}/assets/{path...}` | 插件静态资源（需会话） |
| `ANY /api/plugins/{id}/r/{path...}` | 转发到插件注册的路由；非 public 路由需要会话 |

---

## 8. 安全模型

- 插件是 **受信任的扩展**：它由管理员放进插件目录，因此默认假设其可信，但宿主仍然逐项校验权限，
  避免插件误用未声明的能力。
- `server.js` 运行在 Rhino 沙箱里，`ClassShutter` 拒绝一切 Java 类，标准库为 safe 版本，
  插件脚本无法接触 JVM 或文件系统。
- **JVM 插件不受沙箱限制**（它就是普通 Java 代码），安装前请自行审查来源。
- 出站 HTTP 默认走 SSRF 防护：拒绝解析到内网、回环与链路本地地址的域名；
  确实需要访问内网时才授予 `http.private`。
- `routes.public` 会让端点脱离会话保护，插件必须自己完成鉴权
  （典型做法是用 `auth.verifyAdminPassword` 校验 HTTP Basic 凭据）。

---

## 9. 调试与排查

1. **看服务端日志**：插件加载、激活失败、路由异常都会以 `[<插件 id>]` 前缀打印。
2. **看管理页面**：插件列表会显示 `error` 字段与注册到的路由清单。
3. **热重载**：改完 `server.js` / `plugin.json` 后点「重载插件」即可生效（`web.js` 请同步递增
   `version` 以便浏览器重新拉取）。
4. **常见失败**：
   - `缺少 plugin.json` / `插件 id ... 非法` —— 清单不合法，插件不会被注册；
   - `jar 中未找到 LegadoPlugin 实现` —— 忘了 ServiceLoader 声明或 `mainClass`；
   - `插件未声明权限: xxx` —— 在 `permissions` 里补上；
   - 前端页面空白 —— `web.js` 里出现了 `import ('react')` 之类的裸导入，必须改用 `sdk.React`；
   - `Cannot read properties of undefined (reading 'get')` —— 把 SDK 的成员放错了层级，最常见的是
     写成 `api.settings.get()`；**`settings` 是 SDK 顶层成员**（与 `api` 平级），正确写法是 `sdk.settings.get()`。
     注意 `web.js` 是未编译的原生 ES module，类型检查拦不住这类错误。
5. **编码**：`plugin.json` 与 `server.js` 允许带 UTF-8 BOM（服务端会剥离），但**不要**用会把
   UTF-8 当成本地编码读写的工具（如 Windows PowerShell 5.1 的 `Get-Content`/`Set-Content`）去改这些文件，
   中文会变成乱码并可能破坏 JSON 结构。推荐用支持 UTF-8 的编辑器。

---

## 10. 参考实现与最小骨架

本仓库不附带示例插件，下面两个骨架可以直接复制成起点。

**JS 插件**（`<dataDir>/plugins/hello/`）：

```json
{ "id": "hello", "name": "你好插件", "version": "1.0.0", "apiVersion": 1,
  "server": "server.js", "web": "web.js", "permissions": ["storage", "books.read"] }
```

```js
// server.js
legado.route('GET', '/ping', function (req) { return { pong: true } })
```

```js
// web.js
export default function activate(sdk) {
  sdk.registerPage({ id: 'hello', title: '你好', render: () => sdk.React.createElement('p', null, '你好') })
  sdk.registerNavItem({ id: 'hello', label: '你好' })
}
```

**JVM 插件**（`lib/` 放 jar，`plugin.json` 里用 `jars` 登记）：

```kotlin
class HelloPlugin : LegadoPlugin {
    override fun activate(context: PluginContext) {
        context.route("GET", "/hello") { PluginResponse.json(mapOf("hello" to "world")) }
    }
}
```

jar 中需要 `META-INF/services/io.legado.plugin.api.LegadoPlugin` 声明实现类（或在 `plugin.json` 里
显式写 `mainClass`）。这条路径由 `server/src/test/kotlin/io/legado/server/PluginSystemTest.kt` 中的
`jvm jar plugin is discovered through ServiceLoader and serves its routes` 用例覆盖：测试会在运行时
组装一个真实 jar 来验证 ServiceLoader 发现、类加载、路由与 `deactivate`。
