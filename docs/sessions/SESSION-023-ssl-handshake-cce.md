---
id: SESSION-023
title: 修复大灰狼书源登录报错 SSLHandshakeException cannot be cast to java.lang.Error（红蓝对抗连带挖出三处缺陷）
date: 2026-09-26
author: Agent
tags: [rhino, sandbox, ssl, book-source, login, adversarial-review, regression]
---

# SESSION-023: 修复大灰狼书源登录报错（Rhino 受检异常强转 CCE 及其连带缺陷）

## 1. 现象与排查推演 (Investigation & Analysis)

### 用户报错原文
> 书源脚本执行失败：`class javax.net.ssl.SSLHandshakeException cannot be cast to class java.lang.Error` (javax.net.ssl.SSLHandshakeException and java.lang.Error are in module java.base of loader 'bootstrap')

出现在**点击大灰狼聚合书源登录按钮**时，前端红色 toast。

### 排查过程
1. **先复现，不猜**：用户未提供书源文件，先构造了「忠于真实特征」的合成聚合源
   （`bookSourceUrl` 为中文标识、`loginUrl` 为大段 JS、`jsLib` 用 `JavaImporter`+`with`）。
   结果**登录正常** ⇒ 合成源不足以复现，遂直接向用户索取确切报错文本。
2. **拿到精确报错后立即定性**：`cannot be cast to class java.lang.Error` 是
   **Rhino 的类型转换崩溃**，不是网络问题 ⇒ 排查方向锁定在 JS 沙箱桥接层。
3. **最小化复现**（`Repro4.java`）：手写一个 `BaseFunction` 子类，让受检的
   `SSLHandshakeException` 逃逸出去，**逐字复现**了同一句错误消息。
4. **定位调用链**：
   ```
   JS  java.ajax(url)
     -> JsSandbox.createJavaBridge 的 BaseFunction.call()
       -> RuleRunner.fetch -> fetchUrl
         -> HttpClient.send()         抛 SSLHandshakeException（受检）
         -> catch (IOException) 后原样 rethrow     ← 泄漏点
   ```
   `SSLHandshakeException extends SSLException extends IOException` 是**受检异常**；
   Kotlin **没有受检异常检查**，所以编译器不会阻止它逃逸——这正是该项目会踩到、
   而 Java 版本桥接不会踩到的根本原因（javac 会直接编译失败）。

### 为什么危害不止「报错难看」
用 `Verify.java` 做了**对抗性验证**，试图证伪「修好消息就够了吗」：

| 抛出形式 | JS 能否 `catch` |
| :--- | :--- |
| 受检异常直接冒泡 | ❌ 抛 CCE（引擎内部） |
| `RuntimeException` 包装 | ❌ **仍然漏过** |
| `Error` 子类 | ❌ **仍然漏过** |
| `Context.throwAsScriptRuntimeEx` | ✅ 可捕获，但 `e.name=InternalError` 且**丢失真实类型名** |
| **`ScriptRuntime.constructError`** | ✅ 可捕获，`e.name=Error`，**保留真实类型名** |

**关键结论**：CCE 在 JS 引擎内部抛出，**书源自己的 `try{...}catch(e){}` 抓不到** ⇒
聚合源赖以容错的多节点轮询与主备线路回退被**整体击穿**。这是本 bug 最隐蔽的危害，
也是「只把消息改好看」这类表面修复会漏掉的部分。

## 2. 尝试过的无效方案 (Failed Attempts & Why)

- **尝试 1：包成 `RuntimeException`**
  - *失败原因*：实测**依然会被 JS 的 `try/catch` 漏过**（见上表）。看似修好，实则容错仍未恢复。
- **尝试 2：包成 `Error` 子类**
  - *失败原因*：同样漏过。Rhino 只把「脚本可见错误对象」交给 JS catch。
- **尝试 3：`Context.throwAsScriptRuntimeEx`**
  - *部分有效*：可被捕获，但在生产沙箱设置
    （`initSafeStandardObjects()` + `ClassShutter{false}`）下，
    消息退化为 `InternalError: PKIX path building failed`，**丢掉了 `SSLHandshakeException` 类型名**，
    用户仍无法判断是证书问题还是线路问题。
- **尝试 4：写一个独立的 Kotlin 复现工程**
  - *失败原因*：独立编译 Rhino classpath 成本高；改为**直接在项目测试类里做诊断**，
    跑完即删，既省事又保证与产品环境完全一致。

## 3. 最终落地的正确解法 (Final Solution)

在 `RuleRunner.kt` 增加顶层函数（同包，供 `JsSandbox` 与 `JsSourceRunner` 共用）：

```kotlin
internal fun normalizeScriptThrowable(error: Throwable): Nothing {
    // 业务语义异常必须冒泡，不能被书源 catch 静默吞掉
    if (error is RuleExecutionException) throw error
    val detail = "${error.javaClass.name}: ${error.message ?: "无详细信息"}"
    throw ScriptRuntime.constructError("Error", detail) as Throwable
}
```

调用点（4 处，全部为 `java.*` 网络桥接）：`JsSandbox` 的 `ajax`/`post`/`get`
与 `RuleRunner.ajaxFunction` 的 `ajax`。

**为什么不包裹 `RuleExecutionException`**：该类是业务语义异常（如「未配置 searchUrl」
「上游返回 HTTP 404」），必须中断整条规则并冒泡到路由层转成明确提示；实测确认
「不包裹时它会如实逃逸出 JS catch」，正是期望行为——若包裹，书源的 catch 会把它
当成线路故障静默吞掉，反而掩盖真实配置问题。

## 4. 对抗审查连带挖出的另外两处缺陷

同一个 `createJavaBridge` 函数里，审查「47 个桥接方法」时又发现两处**独立**缺陷：

### 缺陷 2：`java.post` / `java.get` 的 `Map<String, Any>` 序列化崩溃
```kotlin
val opt = mapOf("method" to "POST", "body" to body, "headers" to headers)
val fullUrl = "$url,${Json.encodeToString(opt)}"   // 静态类型 Map<String, Any>
```
- **根因**：kotlinx 无法为多态 value 解析序列化器，抛
  `SerializationException: Serializer for class 'Any' is not found`（已用测试实证）。
- **加重情节**：该语句位于归一化 `try/catch` **之外** ⇒ 桥接**静默返回 null**，
  用户看不到任何错误。
- **与已有部落知识同源**：`AGENTS.md` 早已记录「Ktor 路由响应严禁 `Map<String, Any>`」，
  但没人想到**沙箱桥接内部**也有一处。
- **修法**：新增 `buildUrlOptionsJson()`，用 `buildJsonObject` 显式构造强类型 `JsonObject`。

### 缺陷 3：`java.get` 被同名属性静默顶掉，HTTP GET 能力是死代码
- **根因**：`createJavaBridge` 内先定义 `java.get(url, headers)`（HTTP GET，545 行），
  函数末尾又 `putProperty(api, "get", ...)` 覆盖为 session store 读取（757 行）。
  `NativeObject` 同名属性后者覆盖前者 ⇒ **HTTP GET 从未生效**。
- **发现方式**：异常矩阵测试中 `java.ajax`/`java.post` 均 CAUGHT，唯独 `java.get`
  返回 `no-throw`，与「代码看起来没问题」矛盾 ⇒ 逐一定位 `putProperty(api,"get")`
  才发现有两处。
- **修法**：合并为单一 `java.get`，按参数形态分派（`http(s)` 开头视为 HTTP 请求，
  否则读 session store，保持与 `java.put` 的既有配对语义），并删除重复定义。
- **附注**：`sessionStore` 在仓库内**无其它消费者**，但保留其语义以免破坏潜在书源用法。

## 5. 暴力测试与回归对照 (Brute-force & Regression)

### 新增回归测试 `ScriptThrowableNormalizationTest`（6 用例，全绿）
覆盖：① 不再抛 CCE；② 保留真实异常类型名；③ **书源主备回退恢复工作**；
④ `RuleExecutionException` 仍如实冒泡；⑤ 非受检异常可捕获且消息保留；
⑥ **8 种异常类型 × 3 个桥接方法（ajax/post/get）矩阵**，逐一确认无 CCE。

### 回归判定（按项目既定方法论：比「失败集合」而非数量）
| | 用例数 | 失败数 | 失败集合 |
| :--- | :--- | :--- | :--- |
| 干净基线 worktree（HEAD） | 199 | 53 | 基准 |
| 带修复 | **205** | **53** | **与基线完全一致** |

⇒ **零回归**，净增 6 个通过用例。53 个失败全部含
`FileSystemException: 另一个程序正在使用此文件` 签名，即 `AGENTS.md` 已记录的
Windows SQLite 占用既有失败。

### 端到端联调（真实服务 + 真实 TLS 故障）
构造登录脚本：主线路打 `https://expired.badssl.com/`（证书过期），catch 后回退
`https://example.com/`。实测结果：

```
主线路失败，切换备用：Error: javax.net.ssl.SSLHandshakeException: (certificate_expired)
                      PKIX path validation failed: java.security.cert.CertPathValidatorException: validity check failed
备用线路成功 len=559
```

- ✅ **CCE 消失**；✅ **JS catch 生效**；✅ **真实证书原因可见**；✅ **回退成功**。

## 6. 沉淀的教训与部落知识 (Lessons Learned)

1. **Kotlin 没有受检异常检查 ⇒ JS 桥接层必须显式归一化异常**。Java 版本会被 javac
   拦下，Kotlin 不会，所以这类泄漏只会在 Kotlin 实现里出现。
2. **「能被客户端 catch」与「消息好看」是两件事**，必须分别验证。只把异常包成
   `RuntimeException` 看似修好，实则 JS `catch` 仍漏过、容错依旧瘫痪。
3. **`AGENTS.md` 的「严禁 `Map<String, Any>`」规则适用范围比想象的广**——
   不止 Ktor 路由响应，**JS 桥接内部的选项拼接**同样是雷区。
4. **同名属性覆盖是静默的**。`NativeObject.putProperty` 重复赋值不会报警，
   一个 `java.get` 的能力就此消失且**长期无人发现**（HTTP GET 是死代码）。
   审查桥接对象时应**枚举属性名查重**。
5. **先要精确报错，再做合成复现**。首轮合成源复现失败（源特征不足），
   拿到用户原文报错后 10 分钟内定位到根因——**宁可直接问，不要猜着复现**。

## 7. 修改文件清单
| 文件 | 变更 |
| :--- | :--- |
| `server/src/main/kotlin/io/legado/server/RuleRunner.kt` | 新增 `normalizeScriptThrowable`；`ajaxFunction` 接入；补 `ScriptRuntime` import |
| `server/src/main/kotlin/io/legado/server/JsSandbox.kt` | `ajax`/`post`/`get` 接入归一化；`java.get` 合并去重；新增 `buildUrlOptionsJson` |
| `server/src/test/kotlin/io/legado/server/ScriptThrowableNormalizationTest.kt` | 新增（6 用例） |
| `AGENTS.md` | 部落知识 + 索引增量 |
