# REVIEW.md 修复说明

> 分支：`fix/review-findings` · 基线：main @ eaa3790 · 日期：2026-07-07
> 对应报告：[REVIEW.md](./REVIEW.md)（条目编号与报告一致）
> 测试：全量 1911 个测试通过（含新增回归测试 `ReviewFixesTest`、`IngressE2ETest`）

按用户要求，**会大幅改动代码结构或引入高复杂度的条目暂缓**，见文末[暂缓清单](#暂缓清单)。

---

## ⚠️ 行为变更（升级需知）

多数修复对用户透明，但以下 5 项属于**有意的行为变更**，依赖旧行为的代码需要调整：

| 变更 | 旧行为 | 新行为 | 迁移方式 |
|---|---|---|---|
| `ctx.json(字符串)` (4.3) | 字符串原样透传（产生非法 JSON） | 字符串按 JSON 字符串值序列化（加引号转义） | 透传预序列化 JSON 改用 `ctx.json(RawJson("..."))` |
| 宽松布尔 (4.2) | 非真值集合一律静默为 `false` | 仅接受 `true/1/yes/y/on` 与 `false/0/no/n/off`，其余 400 | 客户端传规范布尔值 |
| `Request.ip` (2.4) | 无条件信任 `X-Forwarded-For` 第一项（可伪造） | 默认忽略代理头；配置 `server.trustedProxyCount = N` 后从右向左剥离 N 层可信代理 | 反代部署下设置 `trustedProxyCount` |
| Cookie 值编码 (5.2) | 不编码（含空格/逗号产生非法 Set-Cookie，与文档不符） | 写侧百分号编码、读侧解码（与文档一致） | 无需改动；跨系统读取本框架 Cookie 者注意解码 |
| controller `@Use` 作用域 (1.20) | 注册为 basePath 前缀中间件，波及同前缀的无关路由 | 仅作用于该 controller 自己的路由（PerRoute） | 需要前缀语义的用 `app.use(prefix, mw)` |

另有小的 API 增补：`RawJson`（json 包）、`SseCloseReason.ServerShutdown`、`ServerConfig.trustedProxyCount`、`UrlPath.decodePath`。对 `SseCloseReason` 做穷尽 `when` 的代码需补分支。

---

## 阶段 1：路由与应用编排

- **1.1 🔴 入口二次 URL 解码 — 已修**。解码职责统一收归服务器层：`UrlPath` 各函数不再做百分号解码（Undertow 默认已对 requestPath 解码一次），与 `PathMatcher` 注释的既有约定一致；`TestClient` 通过新的 `UrlPath.decodePath()` 模拟"服务器解码一次"（不转换 `+`、保持 `%2F`/`%5C` 不解码，与 Undertow 行为对齐），保证测试与生产观察到相同路径。同时 `UndertowServer` 对 adapt 阶段异常（如 `..` 段）统一回 400 而非裸 500。E2E 验证：`100%2541`→`100%41`、`a+b` 保持、字面 `50%` 正常、`a%2Fb` 不再改变路径结构、`%2e%2e` 拒绝为 400（`IngressE2ETest`）。
- **1.2 🟠 零段通配符压制精确路由 — 已修**。`PathSegment.priority()`（长度加权折叠数值）替换为 `compareSpecificity()`：逐段字典序比较，段类型排序 Static > Complex > Param > **END** > Wildcard——"模式在此结束"高于"通配符吞零段"。不同长度模式只有在长模式以通配符结尾时才会同时命中，因此该排序恰好只影响这一场景；`should handle empty wildcard` 的零段匹配行为保持不变。HTTP 与 WS 路由匹配统一使用新比较器。
- **1.3 🟠 405 无 Allow / Retry-After 死代码 — 已修**。默认错误处理器对 `MethodNotAllowed` 输出 `Allow`，对 `TooManyRequests`/`ServiceUnavailable` 的 `retryAfter` 输出 `Retry-After`；`RateLimiter` 默认 429 现在带 `retryAfter`（按令牌恢复速率估算）。
- **1.4 🟡 `onError<Throwable>` 永不命中 — 已修**。查找循环改为检查到 Throwable 本身再停止。
- **1.5 🟡 直接继承 Throwable 被吞 — 已修**。`handleRequest` 的 `when` 增加 `else -> throw e`；服务器 root handler 增加兜底（记录日志 + 500），异常不再无声消失。
- **1.6 🟡 group 内条件中间件丢失前缀作用域 — 已修**。`RouteBuilder.use(predicate, mw)` 将 predicate 包装为 `前缀匹配 && predicate`。
- **1.7 🟡 mount 失败留脏状态 — 已修**。先 `MountNode.of` 校验，成功后再改 `parent`/`mountPath`。
- **1.8 ⚪ mount 环 — 已修**。mount 时沿 parent 链检查目标是否为自身或祖先。
- **1.9 ⚪ 等价路由静默遮蔽 — 已修**。注册时用 `sameShape()` 检测同 method + 等价形状的路由并 WARN（先注册者获胜的行为不变）。
- **1.12 🟡 零监听器仍分配事件 — 已修（热点部分）**。`EventBus.hasListeners()` 快速路径；中间件/handler/子应用三处热点 emit 在无监听器时跳过事件构造与 `measureTime`。`RequestReceived`/`ResponseReady` 保留（有承载功能的内置监听器）。
- **1.13 ⚪ mount 分支急切 405 扫描 — 已修**。改为所有 mount 尝试失败后惰性计算。
- **1.14 🟡 errorHandlers 非线程安全 — 已修**。改 `ConcurrentHashMap`。
- **1.15 🟡 错误处理器抛异常丢失原始异常 — 已修**。`handlerError.addSuppressed(e)`。
- **1.16 ⚪ 死字段 `Router.controllers` — 已删除**（相应测试改为验证 controller 路由真正注册成功）。
- **1.17 ⚪ `addController` 变量遮蔽 — 已修**。改名 `controller`。
- **1.18 ⚪ stop 后报错误导 — 已修**。`listen()` 先检查 `shuttingDown`，报"已停止不可重启"。
- **1.20 🟡 controller `@Use` 作用域过宽 — 已修（采纳代码内 TODO 的方向）**。`@Use` 中间件按该 controller 的每条路由注册为 PerRoute，不再波及同前缀的其他路由；`@WsUse` 保持 Prefix（WS 握手需要前缀语义）。

## 阶段 2：HTTP 消息模型

- **2.1 🟠 返回 Long 必 CCE — 已修**。按 `Number.toLong()` 比较范围后 `toInt()`。
- **2.2/4.3 🟡 `json(字符串)` 非法 JSON — 已修（行为变更）**。见上表；`json(null)` 由 `ResponseBody.Json.materialize` 显式输出字面 `null`（不再依赖 `?: "null"` + 透传的巧合）。
- **2.3 🟠 mount 父子不共享请求体 — 已修**。`body`/`forms`/`parts` 缓存移入 `Request.BodyCache`（构造参数，`copy()` 共享引用）；配合线程安全的 `OnceCell`（volatile + ReentrantLock），同时解决 3.7 的跨线程风险。父先读子后读（或反之）均拿到同一份缓存，回归测试覆盖。
- **2.4 🟠 `ip` 无条件信任 XFF — 已修（行为变更）**。新增 `ServerConfig.trustedProxyCount`（默认 0 完全不信任）。信任模式用标准算法：XFF 链 + 直连地址，从右剥离 N 层可信代理，取最右剩余项——单层可信代理下客户端自带的伪造前缀不再生效。
- **2.5 🟡 mount 回退泄漏响应头 — 已修**。`createSubContext` 深拷贝 Headers；成功路径仍由 `Response.merge` 显式回写。
- **2.6 🟡 Result 的 Int body 被二次解释为状态码 — 已修**。`Result` 分支内数字 body 一律按 JSON 载荷处理（`Result.ok(42)` → 200 + `42`；`Result.ok(204)` 不再改状态码）。
- **2.7 🟡 `FilePart.save("裸文件名")` NPE — 已修**。`target.parent?.let { createDirectories }`。
- **2.8 ⚪ 新建 Response 被忽略 — 已修**。`result !== ctx.response` 时应用其 status/headers/body。
- **2.9 ⚪ 每 Context 读 `config.jsonMapper` — 已随 3.2 修复**（首批并发不再重复建 mapper）。
- **2.11 ⚪ 杂项 — 部分修复**：`getState` KDoc 更正（泛型擦除下 null 值不抛 NPE）；`Request.json` 的 IOException 归类经复核为误报（`text()` 在 try 之外，传输错误本就不会被归为 400），未改；SSE `Connection: keep-alive` 头与 cookie secure 默认值见暂缓清单。
- **2.12 🟡 校验错误未结构化输出 — 已修**。默认 JSON 错误体对 `ValidationException` 附加 `errors` 字段。
- **2.13 ⚪ — 部分修复**。304 分支现在回带 `Last-Modified` 与 `Cache-Control`；Range 支持见暂缓清单。

## 阶段 3：服务器层与实时通信

- **3.1 🟠 WS setup 抛异常连接永久泄漏 — 已修**。「从 `activeWsConnections` 移除」的 onClose 回调在 `createConnection` 加入集合后**立即注册**，任何阶段的 close（含 setup 失败的提前 close）都能触发清理。
- **3.2 🟡 `Config.jsonMapper` 无锁 get-or-create — 已修**。volatile 字段 + ReentrantLock（Loom 友好），`jackson {}` 扩展与 setter 共用同一把锁。
- **3.3 🟡 关闭不通知 SSE — 已修**。`UndertowServer` 跟踪活动 `SseConnection`，shutdown 在关闭 executor 前广播 `close(SseCloseReason.ServerShutdown)`（新增枚举值），`while (!conn.isClosed)` 型 handler 可协作退出。
- **3.5 🟡 keep-alive 2 线程池饥饿 — 已修**。调度线程只负责触发，阻塞的 `comment()` 投递到一次性虚拟线程执行；`keepAlivePinging` 标志保证上一次心跳未完成时跳过本次（卡死客户端不再堆积任务、不再拖累其他连接）。
- **3.6 🟡 Bytes 响应无 Content-Length — 已修**。显式 `setResponseContentLength`（用户已设置时不覆盖），大响应不再退化为 chunked。E2E 验证 64KB 响应带 Content-Length。
- **3.7 ⚪ lazyLoom NONE 跨线程风险 — 已修**（并入 2.3 的 `OnceCell` 方案）。
- **3.8 ⚪ WS close 与 send 锁契约不一致 — 已修（文档化）**。`WsChannel` 接口契约改为如实描述："send 之间串行、close 可与 send 并发，实现须容忍"（Undertow 的 blocking sender 满足）。
- **3.9 ⚪ 杂项 — 已修**：`idleTimeout` clamp 防溢出；WS 心跳改 `nanoTime()` 单调时钟（NTP 回拨不再误杀连接）。

## 阶段 4：参数绑定与 DI

- **4.1 🟠 `convertTo` 仅 6 种标量 — 已修**。扩充至与 OpenAPI SchemaGenerator 一致的全集：UUID、BigDecimal/BigInteger、LocalDate/LocalTime/LocalDateTime/Instant/OffsetDateTime/ZonedDateTime/Date（ISO-8601）、Short/Byte/Char、枚举（按 name，大小写不敏感兜底）；转换失败统一 `TypeConversionFailed` → 400。另外 `Path<不支持类型>` 与 `Query<List<不支持类型>>` 在**注册期**直接报错（fail-fast），不再等到首个请求 500。
- **4.2 🟠 宽松布尔静默 false — 已修（行为变更）**。见上表；`on`（HTML 复选框）现在为 true。
- **4.4 🟡 DI 循环依赖报错晦涩 — 已修**。ThreadLocal 解析栈检测同线程循环，报完整链路（`A -> B -> A`）；同时工厂不再运行于 `ConcurrentHashMap.computeIfAbsent` 内部（嵌套 compute 是未定义行为），改为 per-key ReentrantLock，保持"工厂恰好执行一次"的既有保证（既有并发测试通过）。
- **4.5 🟡 Java 空参数名报错滞后 — 已修**。`Query`/`Form` 的标量与 List 形态在 build 期校验参数名非空（与 Path/Header/Cookie 一致）。
- **4.11 ⚪ Scanner 不扫继承的注解方法 — 已修**。`ControllerScanner` 沿继承链收集方法（过滤 bridge/synthetic）；覆写链上**优先取带注解的最派生声明**——Java 方法注解不被继承，未重新标注的覆写不会让基类路由消失（反射调用仍虚分派到覆写实现），重新标注的覆写则完全取代基类声明（不会重复注册路由/中间件）。`SchemaGenerator` 同样沿继承链收集 DTO 字段（子类遮蔽字段优先）。回归测试覆盖四种场景。
- **4.6 🟡 每请求 `callBy` 反射开销 — 已修**。build 期判定无 optional 参数的 handler（绝大多数）走位置化 `call(*args)` 快路径，省去每请求的 KParameter Map 分配与默认值掩码开销；有默认参数的仍走 `callBy`。
- **4.7 ⚪ extractorFactoryCache 非线程安全 — 已修**。`ConcurrentHashMap.computeIfAbsent`。
- **4.8 ⚪ `printStackTrace` 吞异常 / 重载选错 — 已修**。解析失败抛 `IllegalArgumentException` 并携带原始 cause；用 SerializedLambda 的方法签名（JVM descriptor）精确匹配重载。
- **4.9 ⚪ `Parameter.isNullable()` 疑似死代码 — 复核后保留**。grep 确认无调用点，但它是文档化的 Java 可空性约定入口，删除属清理性取舍，留待作者定夺（一行 `@Suppress` 无维护成本）。

## 阶段 5：中间件、HTTP 工具与外围

- **5.1 🟠 头名校验过严致 500 — 已修**。校验放宽为完整 RFC 9110 token 集（仍拒绝控制符/分隔符/CRLF，保留响应侧防注入）；入口解析对个别非法头名跳过而非使整个请求失败。E2E 验证 `X-My.Header` 正常。
- **5.2 🟡 Cookie 值不编码与文档不符 — 已修（行为变更）**。见上表。`validateValue` 相应放宽（分号经编码后无歧义）；解码失败的外来 Cookie 原样保留。
- **5.4 ⚪ 反代后 HSTS 永不发出 — 已修**。`trustedProxyCount > 0` 时按 `X-Forwarded-Proto` 推导 `scheme`/`isSecure`（默认不信任，防伪造），`SecurityHeaders` 的 HSTS 随之在反代部署下生效。E2E 覆盖。
- **5.5 🟡 OpenAPI spec 每请求全量重建 — 已修**。按"全树路由计数"做指纹缓存，路由集不变时直接返回缓存。
- **5.7 ⚪ 杂项 — 已修**：`resolveFileStream` 的 contextClassLoader null 兜底；CORS 的 `Vary: Origin` 对非通配符配置恒定输出（含 origin 被拒时，防共享缓存串味）、`Allow-Methods/Headers/Max-Age` 仅在预检响应发出（实际响应只发 `Expose-Headers`）；RateLimiter 默认 429 带 Retry-After（Reset 头语义保留，见暂缓）。

---

## 暂缓清单

以下条目按用户要求暂缓（会大幅更改代码结构 / 引入高复杂度 / 属新功能需求，建议单独立项）：

状态更新（2026-07-07 第二轮，按作者决定）：

| 条目 | 内容 | 状态 / 决定 |
|---|---|---|
| 1.10 / 1.11 | 路由匹配热路径优化（split 复用、索引） | **不做**：性能优化类，当前无瓶颈 |
| 1.19 / 1.22 | WS 与 HTTP 中间件时序、尾斜杠策略、复杂段非回溯语义、单次解码约定 | **已完成**：README / README-zh 已补充（路由约定小节 + WS 时序小节 + 生产建议） |
| 1.21 | 自动 HEAD / 自动 OPTIONS | **待办**：本 PR 提交后新开独立 PR |
| 2.10 | Context/Request/Response 改普通 class | **暂不动**：data class 隐患点已消除大半，后续要改也方便 |
| 2.11(部分) | SSE `Connection: keep-alive` 对 h2 不合法 | **暂不动**（当前仅 HTTP/1.1 无实际影响）；h2 改造思路见 PR 讨论。cookie `secure`/`httpOnly` 默认值确定**不改** |
| 2.13 / 5.8 | Range / 206 断点续传 | **待办**：本 PR 提交后新开独立 PR |
| 3.4 | SSE `close()` 可能被卡死的 write 拖住 | **已提 issue [#25](https://github.com/cymoo/colleen/issues/25)**（含两个候选方案：XNIO WRITE_TIMEOUT / close 与 write 解耦） |
| 3.10 | 请求处理超时 | **待办**：本 PR 提交后新开独立 PR |
| 3.11 | HTTP/2 / TLS 配置入口 | **设计取舍，不做**：轻量框架定位，TLS/h2 交由 Nginx 等反代终结；已写入 README 生产建议（含 `trustedProxyCount` 配合说明） |
| 4.10 | 校验自动接入提取管线 | **待讨论**：属 API 设计取舍，说明见 PR 讨论 |
| 4.11 | Scanner 不扫继承的注解方法 | **已修**（见阶段 4） |
| 5.3 | after-next 设头对流式响应无效 | **复核为误报，不提 issue**。已用真实服务器验证：`use { next(); ctx.response.header(...) }` 设置的头在 SSE 响应中正常出现。原因是本框架中 SSE/Stream body 只是"描述"，实际写出发生在 `UndertowServer.writeResponse`——即整条中间件链结束**之后**，因此 after-next 设头总是先于任何字节写出。review 的推断（"handler 在 next() 内已开始写出"）不符合本框架的执行模型 |
| 5.7(部分) | RateLimiter `X-RateLimit-Reset` 语义 | **保留现语义并文档化**：令牌桶下"桶满时刻"是 window-reset 的合理对应且单调有信息量；"何时可重试"已由 429 的 Retry-After 承担；改动只会破坏两个刻意编写的测试。已在代码处注释明确语义 |
| 5.9 | 响应压缩中间件 | **不做（暂缓）**：性能优化类，后续再议 |

---

## 验证

- `mvn -pl colleen test`：**1911 个测试全部通过**（0 失败 0 错误）。
- `examples` 模块随框架变更编译通过。
- 新增回归测试：
  - `ReviewFixesTest`（20 例）：1.2/1.3/1.4/1.5/1.6/1.7/1.8/2.1/2.3/2.5/2.6/2.7/2.8/2.12/4.1/4.3/4.4。
  - `IngressE2ETest`（10 例，真实 Undertow）：1.1（二次解码 4 种形态 + 编码点段 400）、5.1（含点头名）、5.4（X-Forwarded-Proto）、3.6（Content-Length）、1.3（线上 Allow 头）。
  - 另在 `UrlPathTest`/`ExtractorTest`/`PathMatcherTest`/`RequestTest`/`CookieTest` 补充了 decodePath、严格布尔、specificity、trustedProxyCount、Cookie 编码往返等用例。
- 既有测试中约 20 处断言随行为变更同步更新，每处均在测试内注释了变更理由。
- 备注：`ColleenE2ETest` 的一个用例原使用默认端口 8000，与本机无关进程冲突，已改为固定测试端口（与本次修复无关的测试健壮性问题）。
