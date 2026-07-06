# Colleen 框架代码 Review 报告

> Review 日期：2026-07-06 · 版本：v0.4.7 (main @ 2220b20) · 范围：`colleen/src/main`（~14.8k 行）
> 维度：BUG / 性能 / Clean Code / 缺失功能 · 严重级：🔴 Critical · 🟠 High · 🟡 Medium · ⚪ Low
> 每个发现附置信度（已验证 = 有复现用例或完整调用链推理；待确认 = 建议作者复核）

**状态：完成**（5 个阶段全部 review 完毕；标注"已验证"的发现均有可运行复现用例或完整调用链推理。复现用例已跑完并从仓库删除，基线测试保持全绿。）

## 目录

- [执行摘要与修复优先级](#执行摘要与修复优先级)
- [阶段 1：路由与应用编排](#阶段-1路由与应用编排)（Router.kt / Colleen.kt / CoreTypes.kt）
- [阶段 2：HTTP 消息模型](#阶段-2http-消息模型)（Context / Request / Response / Exception / Event）
- [阶段 3：服务器层与实时通信](#阶段-3服务器层与实时通信)（Undertow* / ws / SseConnection / Config / InputStreamFactory）
- [阶段 4：参数绑定与 DI](#阶段-4参数绑定与-di)（Extractor / Validator / ServiceContainer / Scanner / json）
- [阶段 5：中间件、HTTP 工具与外围](#阶段-5中间件http-工具与外围)（middleware / util.http / openapi / TestClient）

---

## 执行摘要与修复优先级

整体印象：这是一个**架构清晰、实现相当扎实**的框架。虚拟线程+洋葱中间件+类型化提取的设计一致；并发原语（OrderedExecutor TOCTOU 复检、RingBuffer 有界背压、WS 关闭回调晚注册立即触发、RateLimiter CAS 令牌桶）读下来大多正确；安全中间件（路径遍历双重防护、HMAC 签名 cookie、常量时间比较）达到生产水准。测试约 31.5k 行、覆盖较全。

主要问题集中在**请求入口的路径/头解码**、**几处错误处理的语义漏洞**、以及**绑定层与文档承诺不一致**。按严重级：

**🔴 Critical（1 项，建议优先修）**
- [1.1] 请求路径入口二次 URL 解码：破坏 `%`/`+`/`%2F` 语义，字面 `%` 直接 500。E2E 已复现。

**🟠 High（8 项，特定条件下错误行为）**
- [1.2] 零段通配符 + 长度优先级压制精确路由 · [1.3] 405 无 Allow 头 / retryAfter 死代码
- [2.1] 返回 `Long` 必 CCE 500 · [2.3] mount 父子共享请求体，一侧读完另一侧拿到空流 · [2.4] `ip` 无条件信任 XFF 可伪造（限流/审计可绕过）
- [3.1] WS handler setup 抛异常导致连接永久泄漏在 activeWsConnections
- [4.1] `Path<UUID>`/`Query<LocalDate>` 等请求期 500（且与 OpenAPI 承诺不一致） · [4.2] 宽松布尔 `on`→false 静默
- [5.1] 头名校验过严，合法头名致入口 500

**🟡 Medium（约 20 项）**：详见各阶段，含 1.4/1.5（错误吞没）、2.5/2.6/2.7、3.2/3.3/3.5/3.6、4.3/4.4/4.6、5.2 等。

**⚪ Low（约 20 项）**：clean code、一致性、文档化取舍。

### 建议的修复批次

1. **第一批（入口正确性，都是小改动、影响面大）**：1.1（去掉二次解码）、5.1（放宽头名校验）、1.5（补 `else -> throw`）、2.7（FilePart 父目录判空）、2.1（`Number.toInt()`）。
2. **第二批（错误/HTTP 语义）**：1.3（405 Allow + Retry-After）、1.4（onError\<Throwable\>）、1.6（group 条件中间件作用域）、1.7（mount 校验顺序）、4.1（扩充 convertTo 并统一 400）。
3. **第三批（并发/资源，需较仔细）**：3.1（WS 连接泄漏）、2.3/2.5（mount 请求体与响应头共享，data class copy 语义）、3.2（jsonMapper 无锁）、3.3（SSE 优雅关闭）。
4. **第四批（安全加固与功能）**：2.4/5.4（trustProxy 与 XFF/XFP）、4.2（布尔严格化）、5.2（cookie 值编码）、HEAD/OPTIONS 自动化、Range 支持。

> 注：本轮仅产出报告，未改动任何框架代码。每条 High 及以上均给出了 file:line、触发条件与修复建议。

---

## 阶段 1：路由与应用编排

文件：`Router.kt`、`Colleen.kt`、`CoreTypes.kt`（关联读取：`Exception.kt`、`UrlPath.kt`、`UndertowRequestAdapter.kt`）

### BUG

#### 1.1 🔴 请求路径在入口被二次 URL 解码，且 `+` 被解码为空格（已验证：E2E 复现 repro6a/6b + repro2）
- 位置：`colleen/src/main/kotlin/io/github/cymoo/colleen/server/undertow/UndertowRequestAdapter.kt:73` + `util/http/UrlPath.kt:21`
- 实测（真实 `listen` 服务器）：客户端请求 `/echo/100%2541`（期望参数 `100%41`）→ 响应 `100A`（二次解码）；`/echo/50%25`（字面 `50%`）→ 服务器抛 `URLDecoder: Incomplete trailing escape (%) pattern` → 500；TestClient `/echo/a+b` → 参数 `a b`。
- `UndertowRequestAdapter.adapt` 对 `exchange.requestPath` 调用 `UrlPath.normalize`，而 Undertow 的 `requestPath` **默认已经完成一次百分号解码**（`DECODE_URL=true`）。`UrlPath.normalize` 内部又用 `URLDecoder.decode` 再解一次，产生三类后果：
  1. **二次解码**：客户端发送 `/echo/100%2541`（期望参数 `100%41`），经两次解码变成 `100A`——参数值被破坏。
  2. **`+` → 空格**：`URLDecoder` 是 `application/x-www-form-urlencoded` 语义，路径中合法的字面 `+`（RFC 3986 允许）被替换成空格。`/echo/a+b` 的路径参数变成 `a b`。
  3. **字面 `%` 崩溃**：客户端合法发送 `/echo/50%25`（即字面 `50%`），Undertow 解码后为 `50%`，二次解码时 `URLDecoder` 因残缺转义序列抛 `IllegalArgumentException` → 500（应为正常匹配或 400）。
  4. **%2F 走私**：Undertow 默认不把 `%2F` 解码为路径分隔符，但二次解码会把它变成 `/` 并重新参与分段，使 `a%2Fb` 与路由段 `a/b` 相匹配，改变路径结构（安全相关）。
- 注：`PathMatcher` 中的注释（Router.kt:396-400）明确写着“segments 已由底层服务器解码，不要再次解码”，与 adapter 实际行为直接矛盾，可佐证这是无意引入。`RouterTest` 的 `should not decode normalized path twice` 只测了 Router 层，未覆盖 ingress。
- 修复建议：ingress 使用“仅分段、不再解码”的规范化（拒绝 `.`/`..`、去空段即可）；把解码职责完全交给 Undertow（或显式配置 `DECODE_URL`），并为字面 `%`、`+`、`%2F` 增加 E2E 用例。

#### 1.2 🟠 通配符路由可零段匹配 + 长度加权优先级，导致精确路由被更长的通配符路由压制（已验证：repro1，`GET /users` 命中 `/users/{path...}`）
- 位置：`Router.kt:291-302`（priority）、`Router.kt:425-430`（Wildcard 零段匹配）
- `PathSegment.priority` 按 base-4 逐段折叠，模式越长数值越大：`/users` = 3，`/users/{path...}` = 3×4+0 = 12。而通配符允许匹配**零个**剩余段（`RouterTest.should handle empty wildcard` 确认这是有意行为）。两者叠加：同时注册 `GET /users` 和 `GET /users/{path...}` 时，请求 `GET /users` 会命中**通配符**路由（12 > 3），精确路由被静默遮蔽。
- 现有优先级测试只覆盖同段数竞争（exact>param>wildcard），未覆盖跨段数竞争。
- 修复建议：优先级比较先按“显式匹配的段数”对齐（或规定 wildcard 至少匹配 1 段，`/users` 交给显式路由；Express 5 / Spring `**` 均要求非空），至少保证任何静态精确匹配优于零段通配符。

#### 1.3 🟠 405 响应缺少 `Allow` 头；`retryAfter` 字段是死代码，`Retry-After` 头从未发出（已验证：repro4 allowHeader=null + grep 全库无读取点）
- 位置：`Colleen.kt:850-873`（handleErrorByDefault）、`Exception.kt:92/128/216`
- `RouteMethodNotAllowed.allowedMethods` 计算出来后在默认错误处理中被丢弃，RFC 9110 要求 405 必须携带 `Allow`。同样，`TooManyRequests.retryAfter`、`ServiceUnavailable.retryAfter` 全库无任何读取点——构造参数给用户造成"会生效"的错觉。
- 修复建议：`HttpException` 增加可选 `headers` 或在默认处理器中对这三类异常特判输出 `Allow` / `Retry-After`。

#### 1.4 🟡 `onError<Throwable>` 永远不会命中（已验证：repro3，fired=false）
- 位置：`Colleen.kt:911-918`（findErrorHandler 循环条件 `current != Throwable::class` 在检查前就把 Throwable 排除）
- API 允许注册 `onError<Throwable>`（reified T : Throwable），但查找循环到 Throwable 即停止，注册的处理器静默失效。
- 修复建议：循环改为包含 Throwable，或注册时拒绝 Throwable 并提示用 `Exception`。

#### 1.5 🟡 直接继承 `Throwable`（非 Exception/Error）的异常被静默吞掉（已验证：repro5，响应留在 `ResponseBody.Unset`）
- 位置：`Colleen.kt:773-787`（handleRequest 的 `when` 只有 `is Error`、`is Exception` 两个分支，无 else）
- handler 抛出自定义 `class Foo : Throwable()` 时两个分支都不匹配，`when` 语句静默穿透，响应未物化、错误无日志。实测响应停留在 `ResponseBody.Unset`：真实服务器上 writeResponse 因 materializedBody 为 null 抛错 → 回落到 `writeInternalError` 500，且**原始异常从未被记录**。
- 修复建议：加 `else -> throw e`（交由统一错误处理与日志）。

#### 1.6 🟡 `RouteBuilder.use(predicate, middleware)` 在 group 内注册为全局中间件，丢失前缀作用域（已验证：repro7，组外路由也执行了组内条件中间件）
- 位置：`Router.kt:1297`
- `group("/api") { use({...}) {...} }` 期望条件中间件只作用于 `/api`，实际 `app.use(predicate, mw)` 注册为全局 Conditional，`/api` 之外的路由也会执行。与相邻的 `use(middleware)`（会带 prefix）行为不一致。
- 修复建议：包装 predicate 为 `prefix 匹配 && predicate`，或在 RouteBuilder 上移除该重载。

#### 1.7 🟡 `mount` 校验失败会留下脏状态，之后无法再正确挂载（已验证：repro8，失败后 child.parent 已被置，重试合法前缀报 already mounted）
- 位置：`Colleen.kt:609-623`
- `app.mountPath = prefix; app.parent = this` 在 `MountNode.of(prefix, app)`（校验 prefix 全静态段）**之前**执行。若 prefix 含参数导致抛异常，child 已被标记为已挂载，重试合法前缀时报 "already mounted"。
- 修复建议：先 `MountNode.of` 校验，成功后再改 parent/mountPath。

#### 1.8 ⚪ 互相 mount 可形成环，`fullMountPath` 死循环
- 位置：`Colleen.kt:609-623`、`Colleen.kt:109-118`
- `a.mount("/x", b)` 后 `b.mount("/y", a)` 仍能通过校验（只检查目标的 parent），形成 parent 环；此后访问 `fullMountPath` 的 `generateSequence(this){it.parent}` 无限循环。
- 修复建议：mount 时沿 parent 链检查环。

#### 1.9 ⚪ 重复/等价路由注册无告警，后注册者被静默遮蔽
- 位置：`Router.kt:1010-1023`（同优先级取先注册者）
- 注册两个 `GET /users/{id}` 与 `GET /users/{name}`（形状等价）无任何提示，第二个永不可达。Javalin 等框架在注册期报错。
- 修复建议：注册时检测 method+segments 形状冲突，至少 WARN。

### 性能

#### 1.10 🟠 每次请求对同一路径反复 split/分配：O(中间件数+路由数+挂载数) 次
- 位置：`Router.kt:376`（`PathMatcher.match` 内 `UrlPath.splitNormalized(requestPath)`）
- 请求路径在一次请求中被重复分段：每个候选路由的 `matchesPath`、每个 Prefix/PerRoute 中间件的 `match`、每个 mount 的 `match`、`executeMount`、404/405 时 `findAllowedMethods` 的**再次全表扫描**都各自 split 一次并分配 List。百路由应用一次请求可产生数百次相同字符串的 split。
- 修复建议：请求进入 Router 时 split 一次，向下传递 `List<String>`；`findAllowedMethods` 结果可与主匹配循环合并。

#### 1.11 🟡 路由匹配是全表线性扫描，无按 method/首段的索引
- 位置：`Router.kt:1010-1023`
- `findBestMatchedRoute` 遍历所有路由且不能提前终止（要找最高优先级）。中小型应用可接受，但与 1.10 叠加后路由数增长时热路径成本线性上升。
- 修复建议（可选）：按 method 分桶 + 静态首段哈希预筛；或在注册期按优先级降序排序，命中即停。

#### 1.12 🟡 事件系统在零监听器时仍然每请求分配多个事件对象并 emit
- 位置：`Router.kt:1212-1214/1246-1252`、`Colleen.kt:801-815`、`Event.kt:265-274`
- 每请求至少分配 RequestReceived/ResponseReady/ResponseSent + 每个中间件 2 个 + handler 2 个事件对象，每次 emit 做 2 次 ConcurrentHashMap 查找（可 bubble 时还要查父级），即使没有任何监听器。`measureTime` 的 nanoTime 调用同样无条件发生。
- 修复建议：EventBus 增加 `hasListeners(Class)` 快速路径，调用点先判断再分配；或事件改懒构造。

#### 1.13 ⚪ 405 检查在 mount 分支中被急切计算
- 位置：`Router.kt:946`
- `findAllowedMethods(ctx.path)`（全表扫描）在尝试 mounts **之前**执行，而多数请求会被第一个 mount 成功处理，白付一次全扫。移到循环失败后惰性计算即可。

### Clean Code

#### 1.14 🟡 `Colleen.errorHandlers` 用非线程安全的 `mutableMapOf`，与其他注册容器不一致
- 位置：`Colleen.kt:161`
- 路由/中间件/挂载用 CopyOnWriteArrayList、EventBus 用 ConcurrentHashMap，唯独 errorHandlers 是普通 HashMap。若用户在 `listen()` 之后（其他线程）调用 `onError`，读线程可能看到不一致结构。统一为 ConcurrentHashMap 即可消除隐患。

#### 1.15 🟡 自定义错误处理器抛异常时，原始异常从响应路径中丢失
- 位置：`Colleen.kt:830-836`
- handler 异常 e 交给自定义处理器，处理器又抛 handlerError 时，默认处理器只处理 handlerError；原始 e 只在 `ExceptionCaught` 事件中出现过，若无监听器则无任何日志。建议 `handlerError.addSuppressed(e)` 或分别记录两者。

#### 1.16 ⚪ `Router.controllers` 是只写不读的死字段（grep 全库确认）
- 位置：`Router.kt:792`、写入点 `Router.kt:844`
- 且写入发生在 `ControllerScanner.scan` 校验之前，scan 抛异常会留下脏条目。直接删除或改为校验后写入。

#### 1.17 ⚪ `Router.addController` 中 `val obj = controllerMeta.obj` 遮蔽同名参数
- 位置：`Router.kt:851`
- 参数 `obj` 与局部 `obj` 同名遮蔽，易误读。另有代码内自留问题注释（Router.kt:854-855：controller 级中间件注册为 Prefix 会影响同前缀的无关路由，作者自问"Use PerRoute instead?"）——该行为差异建议尽快定案并写入文档（见 1.20）。

#### 1.18 ⚪ `stop()` 后 `running` 永远为 true，错误信息误导
- 位置：`Colleen.kt:926-943`（shutdown 未复位 running）、`Colleen.kt:707`
- 生命周期是一次性的（stop 后不可再 listen），但再次 `listen()` 报 "Server is already running"，实际是"已停止不可重启"。要么支持重启（复位标志、清理监听器），要么把报错语义改对。

#### 1.19 ⚪ WS 升级请求与 HTTP 中间件的时序差异未文档化
- `isWebSocketUpgrade`（Router.kt:1060）在 HTTP 中间件收集**之前**短路，全局 HTTP 中间件（日志、限流等）对 WS 握手完全不可见，只有 wsUse 系列生效；HTTP 路径参数在 handler 前才注入 ctx，而 WS 路径参数在中间件链前注入（Router.kt:1081 vs 1241）。两者都是合理设计，但值得在 README 中说明。

### 缺失功能

#### 1.20 🟡 controller 级 `@Use` 中间件的作用域过宽（代码内已有 TODO 注释）
- 位置：`Router.kt:854-860`
- `@Use` 注册为 basePath 前缀中间件：两个 controller 挂在同一前缀时，A 的鉴权中间件会作用于 B 的路由。建议改为 PerRoute 注册（controller 自己的每条路由），语义更符合直觉。

#### 1.21 🟡 无自动 HEAD（由 GET 派生）与自动 OPTIONS
- HEAD 请求到只注册了 GET 的路径返回 405，主流框架（Javalin/Ktor/Spring/Express）都会自动以 GET 逻辑响应 HEAD（不写 body）。OPTIONS 同理可自动回 `Allow`。当前 `findAllowedMethods` 已具备所需信息，实现成本低。

#### 1.22 ⚪ 路由层缺少的常见能力（按需求酌情补）
- 尾斜杠策略可配置（当前隐式等价：normalize 去空段，`/users/` ≡ `/users`，行为合理但未文档化）；
- 路由级"名称/反向生成 URL"；
- 复杂段参数的“首次分隔符”匹配语义（Router.kt:454-462 文档有说明，但 README 未提及非回溯特性，如 `{a}x{b}` 匹配 `xaxb` 会失败）。

---

## 阶段 2：HTTP 消息模型

文件：`Context.kt`、`Request.kt`、`Response.kt`、`Exception.kt`、`Event.kt`

### BUG

#### 2.1 🟠 handler 返回 `Long` 必然 ClassCastException
- 位置：`Response.kt:262-267`
- `is Int, is Long ->` 分支内执行 `result as Int`：返回 `Long` 时，值在 100..599 范围内直接 CCE → 500；范围外则报误导性的 "Invalid HTTP status code"。`fun count(): Long` 这类 handler 无法工作。
- 修复建议：`(result as Number).toInt()`；同时重新考虑 2.6 的设计问题。

#### 2.2 🟡 `ctx.json("<字符串>")` 输出未加引号的原始字符串（非法 JSON）；`json(null)` 靠巧合才正确（已验证：复现用例 p2_2 / p4_json）
- 位置：`Response.kt:354-368`（Json.materialize）+ `json/JacksonMapper.kt:64-68`（toJsonString 对 String 原样透传）
- 实测 `ctx.json("hello")` → `application/json` 但体为 `hello`（应为 `"hello"`）。而 `ctx.json(null)` 因 `data = value ?: "null"`（Response.kt:357）叠加 String 透传，**恰好**输出合法字面 `null`——属"靠巧合正确"，一旦替换为会正确转义字符串的 JsonMapper 就会输出 `"null"`。
- 详见阶段 4 的 [4.3]，同一根因。修复建议：区分 RawJson 与普通字符串，普通 String 走 `writeValueAsString`。

#### 2.3 🟠 mounted 子应用与父级不共享请求体缓存：一侧读过 body，另一侧再读会拿到已关闭的空流
- 位置：`Context.kt:629-636`（createSubContext 用 `request.copy(path=...)`）+ `Request.kt:61-69`（body 是实例级 lazyLoom，且 `stream.use{}` 读完即关）
- `Request.copy()` 生成全新 lazy 委托但共享同一个 `stream`。父级中间件先 `ctx.text()`（读完并**关闭**流），子应用 handler 再 `subCtx.json<T>()` → 对已关闭流 readBytes → 异常或空。反向顺序同样成立。`forms`/`parts` 同理。
- 修复建议：body 缓存放到可共享的持有者（如包装类/显式传递），或 createSubContext 改为包装原 Request 仅覆盖 path。

#### 2.4 🟠 `Request.ip` 无条件信任 `X-Forwarded-For` 且取第一个条目，可被客户端伪造
- 位置：`Request.kt:312`、`Request.kt:371-390`
- 任何客户端都能自带 `X-Forwarded-For: 1.2.3.4` 伪造 `ctx.request.ip`。即使部署在可信代理后，XFF 的**第一个**条目仍是客户端可控的（代理只是追加）；正确算法是从右往左剥离可信代理。RateLimiter 默认按 IP 限流（keyExtractor 用 ip），可被该头直接绕过；日志与审计同样被污染。
- 修复建议：增加 `trustProxy` 配置（默认不信任，回退 remoteAddr）；信任模式下按"从右向左跳过可信代理"取值。

#### 2.5 🟡 mount 回退尝试会泄漏失败子应用写入的响应头
- 位置：`Context.kt:632`（`response.copy()` 共享同一个 Headers 实例——data class copy 只复制引用）+ `Router.kt:948-970`（404 时继续尝试下一个 mount）
- 子响应与父响应共享 Headers 对象：子应用 A 的中间件设置了响应头后抛 NotFound 回退，这些头仍留在父响应上，最终随 B 的响应发出。status/body 是按值复制不受影响，唯独 headers 泄漏，行为不一致。
- 修复建议：createSubContext 深拷贝 Headers（或 merge 时才回写头）。

#### 2.6 🟡 `Result` 的 Int body 会被二次解释为状态码
- 位置：`Response.kt:298-304`（`applyHandlerResult(result.body)` 递归）+ `Response.kt:262`
- `Result.ok(42)`（想返回 JSON 数字 42）→ 递归时 42 走 `is Int` 分支 → require(100..599) 失败 → 500。`Result.ok(204)` 则会把状态从 200 悄悄改成 204。
- 修复建议：递归应用 body 时跳过"数字=状态码"的解释；或整体重新考虑 Int→status 的映射设计（返回业务数字的 handler 无法工作，见 2.1）。

#### 2.7 🟡 `FilePart.save("文件名")`（无父目录）抛 NPE
- 位置：`Request.kt:460-461`
- `Paths.get("upload.bin").normalize().parent == null` → `Files.createDirectories(null)` NPE。相对当前目录保存文件是常见用法。
- 修复建议：`target.parent?.let { Files.createDirectories(it) }`。

#### 2.8 ⚪ 返回一个**新建的** `Response` 对象会被静默忽略
- 位置：`Response.kt:236`（`if (result is Response) return`）
- 该分支本意是"handler 返回 ctx.response 时无需处理"，但用户 `return Response(...)` 构造的新响应也被无声丢弃（得到 200/204 空响应）。建议：若 `result !== ctx.response` 则报错或应用它。

### 性能

#### 2.9 ⚪ `Context.jsonMapper` 每次构造 Context 都读一次 `config.jsonMapper`
- 位置：`Context.kt:59`
- 依赖 Config 的无锁 get-or-create（详见阶段 3 的 Config 分析）；启动初期并发请求可能各自创建 JacksonMapper。请求稳定后无成本，合并到 Config 侧修复。

### Clean Code

#### 2.10 🟡 `Context`/`Request`/`Response` 都是 data class，copy/equals 语义被内部机制依赖且对用户暴露
- `createSubContext` 依赖 `copy()` 的浅拷贝语义（正是 2.3/2.5 两个 bug 的根源）；`Response.copy()` 共享 Headers、`materializedBody`（类体属性）不参与 copy——这些微妙语义散落在使用点。对外暴露的 `copy()/equals()` 对这类有身份、有可变状态的对象没有意义。建议改普通 class + 显式的内部复制方法，把"哪些字段共享、哪些复制"写在一处。

#### 2.11 ⚪ 杂项
- `Context.getState` 文档声称 null 值会抛 NPE，实际因泛型擦除会静默返回 null（`Context.kt:110-119`）；
- `Request.json()` 把底层 IOException 也归类为 400 InvalidStructure（`Request.kt:204-212`），IO 错误与格式错误建议区分；
- SSE 响应设置 `Connection: keep-alive`（`Response.kt:154`）对 HTTP/2 不合法（h2 禁止 Connection 头），建议由服务器层按协议决定；
- `Response.cookie()` 的 `secure`/`httpOnly` 默认 false，文档自己推荐 true——安全默认值建议翻转或至少加 lint 提示。

---

## 阶段 3：服务器层与实时通信

文件：`UndertowServer.kt`、`UndertowRequestAdapter.kt`、`OrderedExecutor.kt`、`UndertowWsHandler.kt`、`UndertowWsChannel.kt`、`UndertowSseWriter.kt`、`ws/*`、`SseConnection.kt`、`Config.kt`、`util/Kotlin.kt`、`util/InputStreamFactory.kt`

> `OrderedExecutor`（含 TOCTOU 复检）、`RingBuffer`（有界背压）、WS 关闭回调"晚注册立即触发"三处并发实现读下来是正确的，值得肯定。以下是发现的问题。

### BUG

#### 3.1 🟠 WS handler 建立阶段抛异常时，连接永久泄漏在 `activeWsConnections` 中（已验证：代码顺序）
- 位置：`UndertowWsHandler.kt:104-108`（add 在前、失败即 `return`）vs `:303-311`（负责移除的 `onClose` 在后注册）
- `createConnection` 先把连接加入 `activeWsConnections`；`invokeUserHandler` 若抛异常则 `connection.close()` 后**提前 return**，而真正把连接从集合移除的 `onClose{ activeWsConnections.remove(...) }` 在 `registerCloseHandlers` 里、位于 return 之后，从未注册。
- 影响：任何在 setup 阶段抛异常的 WS handler（如握手后校验失败 `throw`）都会留下一个永不清除的 ghost 连接，持续占用 `maxConnections` 名额并持有 WsConnection 引用。反复触发可耗尽连接上限、造成内存泄漏。
- 修复建议：在 `createConnection` 时（或 close 时无条件）保证移除；例如把 `activeWsConnections.remove` 放进 `connection.onClose` 于 add 之后立即注册，或在 `invokeUserHandler` 失败分支显式 remove。

#### 3.2 🟡 `Config.jsonMapper` 无锁 get-or-create，字段非 volatile（已验证：读代码）
- 位置：`Config.kt:128-139`
- `jsonMapperInstance` 是普通可空字段，getter 做 check-then-act。服务启动初期并发首批请求各自 `ctx.jsonMapper`（`Context.kt:59`）→ 可能创建多个 JacksonMapper 实例，且字段无 volatile 存在可见性风险。JacksonMapper 构造相对重（KotlinModule + JavaTime）。
- 修复建议：改 `by lazy`（ReentrantLazy）或 double-checked volatile；替换 setter 也走同一把锁。

#### 3.3 🟡 服务优雅关闭不对 SSE 连接发送关闭信号，与 WS 处理不对称
- 位置：`UndertowServer.kt:157-170`（有 `wsHandler.closeAllConnections()`，但 SSE 只 `sseExecutor.shutdown()` 后 `shutdownNow()`）
- 长驻 SSE handler（`while(true){ send; sleep }`）在关闭时得不到"连接要关"的信号，只能等 `shutdownNow()` 的线程中断——阻塞式 write/flush 未必响应中断，可能拖到 `shutdownTimeout` 才被强杀。缺少类似 WS 的"广播关闭所有活动 SSE 连接"。
- 修复建议：跟踪活动 SseConnection（如 WS 那样的集合），shutdown 时先 `close(GoingAway)` 广播。

#### 3.4 ⚪ SSE `close()` 可能在卡死客户端上阻塞，拖住关闭线程
- 位置：`SseConnection.kt:184-202` + `141-158`
- `close()` 持 `lock` 调 `writer.close()`；若此刻 keepAlive 的 `comment()` 正持锁阻塞在 flush（TCP 卡死），`close()` 需等其释放锁，最长到 idleTimeout。`cancel(false)` 不中断运行中的任务。
- 修复建议：flush 设写超时，或 close 不与 write 竞争同一把锁（用独立的关闭标志短路 write）。

### 性能

#### 3.5 🟡 `SseKeepAliveScheduler` 全局单例仅 2 线程，阻塞式 `comment()` 可致 JVM 级 keep-alive 饥饿
- 位置：`SseConnection.kt:216-243`
- 所有 app、所有 SSE 连接的 keep-alive 共享同一个 2 线程调度池；`comment()` 是**阻塞** flush（带背压）。少数卡死客户端占满这 2 个线程，其余所有 SSE 连接的心跳投递被拖延。
- 另：该单例的 `shutdown()` 从未被任何地方调用（服务 stop 不触及它），2 个虚拟线程随 JVM 常驻（daemon，影响小）；但它一旦 shutdown 便置 `closed=true` 永久拒绝调度——若同 JVM 重启 app 会抛"already shutdown"。
- 修复建议：改为每 server 一个调度器（随 stop 关闭）并按连接数适当扩容；或 keep-alive 走各自的 SSE executor。

#### 3.6 🟡 `writeResponse` 的 Bytes 分支不设 Content-Length，超过 8KB 缓冲的响应退化为 chunked
- 位置：`UndertowServer.kt:364-366`
- 已完全物化的 `RawResponseBody.Bytes` 长度已知，却直接 `outputStream.write` 不显式设 `Content-Length`。Undertow 仅当内容能装进 8KB 缓冲时才回填长度，较大响应被迫用 chunked 传输，增加开销且对某些客户端不友好。
- 修复建议：Bytes 分支显式 `exchange.setResponseContentLength(bytes.size.toLong())`。

#### 3.7 ⚪ `lazyLoom` 默认 `NONE`（非线程安全 lazy）用于 Request.body/forms/parts
- 位置：`util/Kotlin.kt:67-73` + `Request.kt:61/239/285`
- 单线程请求内正确。但若把 `request` 传给异步消费者（SSE/WS handler 捕获 request、或跨 sub-app 与父 ctx 并发访问 body），`NONE` 模式的 lazy 无内存屏障，可能重复初始化或读到半初始化值。与阶段 2 的 2.3（sub-app 共享 body）叠加风险更高。
- 修复建议：对可能被跨线程访问的字段使用 ReentrantLazy（SYNCHRONIZED 模式）。

### Clean Code

#### 3.8 ⚪ `WsConnection.close()` 在 `sendLock` 外发送关闭帧，与"channel 非线程安全、并发全由 WsConnection 管控"的契约不符
- 位置：`WsConnection.kt:475-483`（注释说明为避免持锁做阻塞 IO）+ 接口契约 `WsConnection.kt:80-81`
- `send()` 持 `sendLock` 调 `channel.sendText`，`close()` 不持该锁直接 `channel.close`；理论上二者可并发访问声明为"非线程安全"的 channel。实际 `WebSockets.*Blocking` 是线程安全的，故风险低，但违背了类自己声明的契约，属实现与文档不一致。
- 修复建议：要么让 close 也串行到 sendLock（接受阻塞），要么在契约里注明 channel 的 send/close 必须各自线程安全。

#### 3.9 ⚪ 杂项
- `UndertowServer.buildServer` 的 `idleTimeout.toInt()`（Config.kt idleTimeout 是 Long ms）在 >Int.MAX 时溢出（约 24.8 天），极端配置下静默截断（`UndertowServer.kt:209`）；
- WS 心跳基于 `System.currentTimeMillis()`（墙钟），NTP 回拨可能造成误判超时（`UndertowWsHandler.kt:212/218`），高精度场景建议 `nanoTime`；
- `UndertowSseWriter` 在构造时即 `exchange.outputStream.bufferedWriter()`，而 SSE 实际在 sseExecutor 线程写——跨线程持有 exchange 输出流依赖 Undertow 的 dispatch 语义，建议补一条注释说明为何安全（当前无说明，读者易疑虑）。

### 缺失功能

#### 3.10 🟡 无请求处理超时（handler 卡死会永久占用线程/连接）
- 虚拟线程模型下单个卡死 handler 不致耗尽平台线程，但会永久占用一个连接与 exchange，且无上限保护。生产框架通常提供 per-request 超时。README 的 Production Notes 建议核实是否有替代方案。

#### 3.11 ⚪ 无 HTTP/2、无 TLS 配置入口
- `buildServer` 只 `addHttpListener`，没有 `addHttpsListener`/证书/ALPN 配置口。纯内网或反代后可接受，但作为"生产可用"框架建议至少提供 TLS 配置或在文档中明确交由反向代理终结。

---

## 阶段 4：参数绑定与 DI

文件：`Extractor.kt`、`Validator.kt`、`ServiceContainer.kt`、`Scanner.kt`、`JavaLambdaCompat.kt`、`json/*`、`util/TypeRef.kt`

> 绑定层的"注册期构建闭包 / 执行期纯 lambda"分层、`ParamMeta` 缓存设计良好；DI 容器的 ConcurrentHashMap + computeIfAbsent 单例语义正确。以下是发现的问题。

### BUG

#### 4.1 🟠 `convertTo` 只支持 6 种标量，`Path<UUID>`/`Query<LocalDate>`/`Query<BigDecimal>` 等在请求期抛 500（已验证：复现用例 p4_1）
- 位置：`Extractor.kt:1171-1203`（convertTo 的 `else -> throw IllegalArgumentException`）
- 支持集仅 String/Int/Long/Double/Float/Boolean。`app.get("/u/{id}", ::h)` 中 `h(id: Path<UUID>)` **注册期通过**（build 只取 rawClass，不转换），但请求到达时 `convertTo(UUID)` 落入 `else` 抛 `IllegalArgumentException`。该异常**不是** `ExtractionException`，故 `executeRoute` 的 `catch(ExtractionException)`（Router.kt:1253）不拦截 → 变 500 而非 400。
- **与 OpenAPI 不一致**：`SchemaGenerator.typeToSchema`（SchemaGenerator.kt:84-97）明确为 UUID/BigDecimal/LocalDate/BigInteger 生成 schema（format uuid/date 等），即文档承诺这些类型可绑定，但绑定器无法产出。
- 修复建议：扩充 `convertTo` 覆盖 UUID、BigDecimal/BigInteger、LocalDate/LocalDateTime/Instant、枚举（按 name/toString）、Short/Byte/Char；不支持的类型统一抛 `TypeConversionFailed`（→400）而非 IllegalArgumentException（→500）。

#### 4.2 🟠 宽松布尔转换永不失败，HTML 复选框值 `"on"` 被静默解析为 false（已验证：复现用例 p4_2）
- 位置：`Extractor.kt:1213-1215`（toLenientBoolean）
- 仅 `true/1/yes/y` 为真，**其余一律 false 且从不报错**。实测 `?flag=maybe`→false、`?flag=on`→false。`on` 正是 HTML `<input type=checkbox>` 选中时提交的值——用户勾选复选框却得到 `false`，且无任何错误提示。与 Int 转换失败即抛错的行为不一致。
- 修复建议：严格模式（仅接受明确的 true/false/1/0，其余抛 `TypeConversionFailed`），或至少纳入 `on`/`t`/`f`/`no`/`n` 并在文档中明示接受集。

#### 4.3 🟡 `ctx.json(<String>)` 输出未加引号的原始字符串，产生非法 JSON（已验证：复现用例 p4_json）
- 位置：`json/JacksonMapper.kt:64-68`（`toJsonString(String)` 原样返回）+ `Response.kt:357`
- `ctx.json("hello")` → Content-Type `application/json` 但响应体是 `hello`（无引号），不是合法 JSON（应为 `"hello"`）。设计意图是让"已序列化的 JSON 字符串"能透传，但代价是任何字符串值都无法作为 JSON 字符串正确输出。
- 附带：`ctx.json(null)` 目前**恰好正确**输出字面 `null`——但仅因为 `?: "null"`（Response.kt:357）叠加了上面的字符串透传特性；一旦换用会正确转义字符串的 JsonMapper，`json(null)` 会输出 `"null"`。属"靠巧合正确"，脆弱。
- 修复建议：区分"原始 JSON 片段"（引入 `RawJson` 包装类型）与普通字符串值；普通 String 走正常 `writeValueAsString` 加引号。

#### 4.4 🟡 循环依赖的单例服务抛 `ConcurrentHashMap` 递归异常而非清晰的循环依赖错误
- 位置：`ServiceContainer.kt:127`（`factories[key] = { instances.computeIfAbsent(key){ factory() } }`）
- A→B→A 的单例：解析 A 时在 `computeIfAbsent(A)` 内部又递归 `computeIfAbsent(A)`，JDK 抛 `IllegalStateException: Recursive update` 或行为未定义。开发者拿到的是底层 ConcurrentHashMap 报错，而非"检测到循环依赖 A→B→A"。
- 修复建议：解析栈跟踪（ThreadLocal set），进入前检测重入即抛带链路的清晰异常。

#### 4.5 🟡 Java 参数无 `-parameters` 时名称为空，报错信息滞后
- 位置：`Extractor.kt:1026-1030`、`962-966`
- Java `cx(Method)` 路径下若未用 `-parameters` 编译且未加 `@Param`，`getParamName` 返回空串；`Path`/`Header`/`Cookie` 的 `requireParamName` 在 build 期报错（好），但 `Query`/`Form`/`Json` 不校验名字，空名会在运行期产生意外的空 key 提取。建议所有基于名字的提取器统一在 build 期校验非空。

### 性能

#### 4.6 🟡 KFunction handler 每请求 `fn.callBy(map)` + 分配 KParameter 映射，是反射热路径
- 位置：`Extractor.kt:82-99`
- 每请求为每个 handler 构建 `buildMap<KParameter, Any?>` 再 `callBy`。`KCallable.callBy`（走默认值掩码/装箱/数组构造）显著慢于位置化 `call(*args)`。对无默认参数的 handler（绝大多数），可在 build 期判定"无 optional 参数"后改用位置化 `fn.call(instanceArg, *valueArgs)`，省去每请求的 Map 分配与 callBy 开销。
- 修复建议：build 期计算 `anyOptional = valueParams.any{ it.isOptional }`；无 optional 时走 `call` 快路径，仅在有默认参数时用 callBy。

#### 4.7 ⚪ `extractorFactoryCache` 是非线程安全 `HashMap`，靠"仅构建期写入"的约定
- 位置：`Extractor.kt:773-789`
- 注释称"仅注册期主线程调用"。实际 `getExtractorFactory` 也在**运行期**错误路径被调用（`buildResolver` 的 missing 分支 Extractor.kt:751），但那时对应类型已在注册期入缓存 → 命中读、不改结构，故当前安全。风险点：运行期动态注册新提取器类型的路由（多线程）会并发 `getOrPut` 结构性修改 HashMap，可能损坏/死循环。建议直接用 ConcurrentHashMap，成本可忽略。

### Clean Code

#### 4.8 ⚪ `JavaLambdaCompat.findImplMethod` 用 `printStackTrace()` 吞异常
- 位置：`JavaLambdaCompat.kt:106-110`
- 解析失败时 `e.printStackTrace()` 直接打到 stderr 并返回 null，随后在调用点抛"Cannot find method for lambda"，丢失原始 stacktrace 上下文。应改用 logger 记录原因或把原异常作为 cause 传递。另 `firstOrNull{ it.name == implMethodName }` 对重载方法只取第一个，方法引用指向重载方法时可能选错重载。

#### 4.9 ⚪ `Parameter.isNullable()`（Extractor.kt:1133）疑似死代码
- `@Suppress("UNUSED_PARAMETER")` 且 Java 提取路径不做可空性解析；请核实是否仍被 OpenAPI 引用，否则删除。

### 缺失功能

#### 4.10 🟡 校验（Validator）未接入提取管线，需 handler 手动调用
- 位置：`Validator.kt` 全文；无自动触发点（已验证：grep 无外部 validate/expect 调用）
- DTO 绑定后不会自动校验，`Query<UserDTO>`/`Json<CreateReq>` 的字段约束需在 handler 里显式 `expect{}`。主流框架多支持声明式校验（JSR-380 或注解）。当前设计是显式取舍，但建议至少提供"提取后自动校验实现了某接口的 DTO"的可选钩子。

#### 4.11 ⚪ Scanner 只扫 `declaredMethods`，不含继承的注解方法
- 位置：`Scanner.kt:67`、`SchemaGenerator.kt:172`（buildObjectSchema 同样只 declaredFields）
- controller 继承基类的 `@Get` 方法、DTO 继承基类的字段都不会被发现。若不打算支持继承体系，应在文档中明示。另 `declaredMethods` 顺序不保证，影响路由注册顺序与同优先级 tie-break（见 1.9）。

---

## 阶段 5：中间件、HTTP 工具与外围

文件：`middleware/*`、`util/http/*`、`util/ResolvedResource.kt`、`openapi/*`、`TestClient.kt`

> 安全敏感中间件质量普遍不错：`ServeStatic` 路径遍历用 canonical 前缀 + `normalizePath` 双重防护；`SignedCookie` HMAC 绑定 cookie 名 + 常量时间比较 + 密钥轮换；`BasicAuth` 常量时间比较 + 假密码防用户枚举；`RateLimiter` 无锁 CAS 令牌桶 + 概率清理。以下是发现的问题。

### BUG

#### 5.1 🟠 `Headers.validateHeaderName` 拒绝含 `.`/`+` 等合法 tchar 的头名，入口解析时使整个请求 500
- 位置：`util/http/Headers.kt:97-102`（正则 `^[a-zA-Z0-9_-]+$`）+ `UndertowRequestAdapter.kt:216-222`（extractHeaders 对每个请求头 `add`）
- RFC 9110 的 header field-name 是 token，合法字符包含 `. + ! # $ % & ' * ^ ` | ~` 等。当前正则只放行字母数字和 `-`/`_`。客户端发送一个合法但含 `.` 的头名（如 `X-My.Header: v`）时，`extractHeaders → add` 抛 `IllegalArgumentException`；该异常发生在 `adapt` 阶段（早于 requestHandler），未被任何 try 捕获 → 整个请求 500（与 1.1 的字面 `%` 崩溃同一传播路径）。
- 修复建议：入口解析放宽为完整 RFC token 集，或对非法头名跳过而非抛错；校验只在**响应**写入端做（防注入）。

#### 5.2 🟡 `Cookie.buildSetCookie` 不对 value 编码，与文档"自动 URL 编码"不符，含空格/逗号的值产生非法 Set-Cookie
- 位置：`util/http/Cookie.kt:63-97`（注释 line 55/`Response.kt:170` 均称 value 会被编码）
- 实际只 `validateValue`（仅拒控制符和 `;`）后原样 `append("$name=$value")`。`ctx.cookie("k","hello world")` → `Set-Cookie: k=hello world`（RFC 6265 的 cookie-value 不允许空格/逗号未加引号），部分客户端会截断或拒绝。SignedCookie 因用 base64url 值不受影响，但普通用户 cookie 会踩坑。
- 修复建议：要么真的对 value 做百分号编码（并在 parse 端解码），要么把文档改为"不编码，调用方自负"并放宽/收紧 validateValue 以匹配。

#### 5.3 ⚪ 安全响应头/CORS 头在 `next()` 之后设置，对已提交的流式/SSE 响应无效
- 位置：`middleware/SecurityHeaders.kt:27-47`、`middleware/NoCache`、`middleware/Sunset` 等"after next 设头"型中间件
- 对流式/SSE/WebSocket 响应，handler 在 next() 内已开始写出，之后再设的响应头不会生效（materialize/写入已发生或 body 已是 Stream）。这类响应静默失去安全头。建议对流式响应给出告警或在 before 段设置可预知的安全头。

#### 5.4 ⚪ `SecurityHeaders` 的 HSTS 依赖 `isSecure`，TLS 由反代终结时永不发出
- 位置：`SecurityHeaders.kt:45` + `UndertowRequestAdapter.kt:85`（isSecure = scheme=="https"）
- 反向代理终结 TLS 时 Undertow 看到的是 http，`isSecure=false`，HSTS 头永不发送——恰恰是最需要 HSTS 的生产部署。与 2.4 的 trustProxy 缺失同源。建议信任代理时依据 `X-Forwarded-Proto` 判定。

### 性能 / Clean Code

#### 5.5 🟡 OpenAPI spec 每次请求全量重建，无缓存
- 位置：`Colleen.kt:672-675`、`openapi/SpecBuilder.kt:48`
- 每次 `GET /openapi.json` 都递归遍历所有路由/挂载 + 反射生成全部 schema。路由集在启动后基本不变，文档端点却每请求重算。建议首次构建后缓存（路由集不可变时）。低频端点，优先级中低。

#### 5.6 ⚪ `Headers` 用普通 `mutableMapOf`（非线程安全）
- 位置：`util/http/Headers.kt:12`
- 与 `Response` 的"非线程安全"契约一致，单请求内安全。但 `Response.copy()`（data class）在 mount 场景**共享**同一 Headers 引用（见 2.5），跨 ctx 写入会相互影响。修复归入 2.5/2.10。

#### 5.7 ⚪ 杂项
- `ResolvedResource.resolveFileStream`（ResolvedResource.kt:54）用 `contextClassLoader` 未做 null 兜底，某些容器/线程下可能 NPE（ServeStatic 侧有兜底，sendFile 侧没有）；
- `Cors` 在实际请求（非 OPTIONS）响应上也设 `Access-Control-Allow-Methods/Headers`（无害但非常规），且 origin 不被允许时不加 `Vary: Origin`，共享缓存可能跨 origin 串味（Cors.kt:82-83, 71-74）；
- `RateLimiter` 的 `X-RateLimit-Reset` 计算的是"桶充满"时间而非"下一个令牌可用"时间，语义与惯例略有出入（RateLimiter.kt:74-76）；
- `TestClient` 走 `app.handleRequest` 直连，不经 UndertowServer 层——因此**测不到** 1.1（入口二次解码）、5.1（头名校验致 500）、writeResponse 关流、graceful shutdown 等服务器层行为；这类问题需 E2E（真实 `listen`）覆盖。建议补充少量端到端测试。

### 缺失功能

#### 5.8 🟡 静态文件/`sendFile` 无 Range（断点续传/206）支持
- 位置：`middleware/ServeStatic.kt`、`Context.sendFile`（Context.kt:560）
- 视频/大文件拖动、断点下载需要 `Accept-Ranges: bytes` 与 206 Partial Content。当前一律整流返回。

#### 5.9 ⚪ 无响应压缩（gzip/deflate/br）
- 框架层无内容编码协商与压缩中间件。可交由反代，但作为一体化框架建议提供可选压缩中间件（注意与流式/SSE 的交互）。

### 缺失功能

#### 2.12 🟡 `ValidationException.errors` 结构化信息未进入默认错误响应
- 位置：`Colleen.kt:865-871`
- 默认 JSON 错误体只有 status/code/message，字段级 errors map 被压平成单行字符串。客户端拿不到结构化校验错误，除非用户自己注册 onError。建议默认处理器特判 ValidationException 输出 `errors` 字段。

#### 2.13 ⚪ `sendFile`/静态文件无 Range（断点续传）支持；304 响应未回带 `Last-Modified`
- 位置:`Context.kt:560-588`
- 大文件下载/视频拖动场景需要 `Accept-Ranges`/206。304 分支（Context.kt:571-574）应继续携带验证器头。另外 `resource.open()` 在响应构建期即打开流，若之后中间件替换 body，该流无人关闭（与阶段 3 的 writeResponse 关流策略一并核实）。
