# 设计走查：边界问题清单（2026-10-19）

> 范围：`src/main/java`（896 个 .java）、`AndroidManifest.xml`、`build.gradle`、`AppDatabase`、`providers.json`。
> 方法：静态阅读 + 计数（`grep` 口径见附录 A），**未运行构建与测试**（本机 shell 受限）。
> 结论：当前最该修的不是"功能没做完"，而是**边界没画**——安全边界、模块边界、并发边界、数据边界。
> 行号基于 2026-10-19 工作区快照；代码改动后请重新核对，**不要把行号当契约**。
> 相关：[ROADMAP](../ROADMAP.md) · [CHANGELOG](../CHANGELOG.md)

---

## 0. 摘要

| 编号 | 优先级 | 问题 | 影响 | 预估 |
|---|---|---|---|---|
| D1 | **P0** | 导出的 WebView 接受任意 URL，且无条件挂 JS 桥 | 任意应用可借页面读文件/题库 | 0.5–1 天 |
| D2 | **P0** | 一批 `exported=true` 组件未收口 | 配置页/调试页可被任意拉起 | 0.5 天 |
| D3 | **P0** | 口令明文入库 + 默认 `admin/123456` + 备份含数据库 | 凭据泄露、云端留存 | 1–2 天 |
| D11 | **P0** | 破坏性迁移 + 空 catch + 运行时改表 | 一次版本错配静默清库 | 2–3 天 |
| D4 | P1 | 全局允许明文 HTTP | API Key 在 http 下裸奔 | 0.5 天 |
| D6 | P1 | 上帝类（最大 11,691 行） | 改一处要读全文，回归面失控 | 持续 |
| D7 | P1 | 三套 Agent 循环并存，其中一套是死代码 | 取消/错误/统计语义不一致 | 1 周+ |
| D8 | P1 | 4 套协议方言塞在一个类里 | 每接一家服务商都改同一个文件 | 1 周 |
| D9 | P1 | 190 处裸 `new Thread` | 无取消、无生命周期、难测 | 持续 |
| D12 | P1 | 无 CI；曾把 25 条失败当"基线"（10-19 已清零为 407/0，但仍是本地手跑）| 回归信号失效 | 1–2 天 |
| D12b | P0 | **当晚复核：那个 407/0 本身也是假的**——没开 `includeAndroidResources`，11 个 Robolectric 类 128 条用例被整类跳过（记成 skipped）。真跑后 18 条红，逐条查修完才是 411/0/0 跳过 | "全绿"可以完全建立在没跑的用例上；把这一条并进了 D12 的"回归信号失效" | 已在 10-19 晚修 |
| D13 | P1 | 构建路径/签名口令硬编码 | 换机不可构建 | 1 天 |
| D5 | P2 | `Security.java` 假加密（+1 位移）无人调用 | 未来误用的地雷 | 0.5 天 |
| D10 | P2 | Hilt 只用了 7 个 `@Inject` | DI 名义存在，测试无法替换 | 持续 |
| D14 | P2 | 找回密码假成功 | 用户以为发了邮件 | 0.5 天 |
| D15 | P2 | 权限面与"题库 App"定位不符 | 信任与上架风险 | 需产品决策 |

**如果只做三件事**：D1+D2（收口导出面）、D3（删或真做登录）、D8（照 `ResponsesStreamParser` 的样子拆协议）。

---

## 1. 安全边界

### D1 导出的 WebView + 任意 URL + 无来源校验的 JS 桥 — P0

**现状（证据链）**

1. `WebViewActivity` 声明 `exported="true"`（[AndroidManifest.xml:317](../src/main/AndroidManifest.xml#L316)），无 intent-filter，任意应用可 `startActivity`。
2. `url` 直接取自 Intent 并原样加载（[WebViewActivity.java:113](../src/main/java/com/oilquiz/app/WebViewActivity.java#L113)、[WebViewActivity.java:398](../src/main/java/com/oilquiz/app/WebViewActivity.java#L398)）。
3. 页面加载时无条件注册全部桥：`AndroidUtil` / `AndroidClipboard` / `AndroidFile` / `AndroidDatabase` / `AndroidAdvanced` / `Android`（[WebViewActivity.java:2073](../src/main/java/com/oilquiz/app/WebViewActivity.java#L2073)），**不校验当前页面的 origin**。
4. 桥里有同步返回数据的方法：`AndroidFile.readFile(path)` 白名单覆盖 Downloads / Documents / Pictures / 应用 `filesDir`（[JSFileInterface.java:30](../src/main/java/com/oilquiz/app/webview/js/JSFileInterface.java#L30)、[JSFileInterface.java:77](../src/main/java/com/oilquiz/app/webview/js/JSFileInterface.java#L77)）；`AndroidDatabase.getQuestionById()` / `getStatistics()` 同步返回 JSON（[JSDatabaseInterface.java:141](../src/main/java/com/oilquiz/app/webview/js/JSDatabaseInterface.java#L141)）。

**为什么算设计问题**：桥的信任边界是"页面"，但页面的来源从未被约束；导出又把"谁能打开这个页面"交给了系统里任意应用。这不是某个方法的疏漏，是**没有定义"受信页面"这个概念**。

**修复方向**（三条一起，缺一不可）

- `exported=false`；外部打开网页走无桥的 `SimpleWebViewActivity` 或自定义 Tab；
- 桥只在受信 origin 上挂：`onPageStarted` 先 `removeJavascriptInterface` 全部，再按 scheme/host（`file:///android_asset`、`http://localhost`、导出目录）决定是否重新挂；
- 调试入口（`DebugLayoutInjectActivity` 等）改 `debug` 变体 manifest overlay，或加 signature 级权限（`DebugKeyReceiver` 已是正确示范，见 [AndroidManifest.xml:107](../src/main/AndroidManifest.xml#L107)）。

**验收**：用外部 Intent 打开任意 `https://` 页面时，页面内 `typeof AndroidFile === "undefined"` 且 `AndroidDatabase` 不可达；应用内导出 HTML / 题目渲染页原有能力不回退。

> 本文不记录可直接复用的攻击载荷，修复后由回归用例覆盖。

### D2 其余导出组件 — P0/P1

| 组件 | 位置 | 风险 | 处理 |
|---|---|---|---|
| `ApiConfigActivity` | [Manifest:296](../src/main/AndroidManifest.xml#L296) | 任意应用可拉起 API 配置页；界面暴露程度未逐行验证 | `exported=false` |
| `DebugLayoutInjectActivity` | [Manifest:160](../src/main/AndroidManifest.xml#L160) | 接受任意 layout JSON 渲染原生控件，可伪造登录/授权弹窗 | 移入 debug 变体或 signature 权限 |
| `StreamTestReceiver` | [Manifest:451](../src/main/AndroidManifest.xml#L451) | 任意应用可触发流式测试逻辑 | 删除或加权限 |
| `AgentDebugBridge` | [Manifest:117](../src/main/AndroidManifest.xml#L117) | 外部 agent 执行通道（据注释有 token + 默认关） | 保持默认关，改 signature 权限更稳 |
| `DemoChatShellActivity` | [Manifest:178](../src/main/AndroidManifest.xml#L178) | 演示壳可被外部拉起 | `exported=false` |
| `AIImportActivity` / `ImportGuideActivity` / `TemplateImportActivity` / `QuestionRepairActivity` | [Manifest:251](../src/main/AndroidManifest.xml#L251)、[258](../src/main/AndroidManifest.xml#L258)、[261](../src/main/AndroidManifest.xml#L261)、[295](../src/main/AndroidManifest.xml#L295) | 均标着 `TODO: 真机测试完成后改回 exported=false`，至今未改 | 按 TODO 收口 |

**验收**：`aapt dump xmltree` 或 `dumpsys package` 中除 `MainActivity` 与确有跨应用需求的组件外，`exported` 全为 false 或带 signature 权限。

### D3 账号与口令 — P0

**现状**

- 登录 SQL：`SELECT * FROM user WHERE email = ? AND password = ?`，明文比对（[UserDao.java:30](../src/main/java/com/oilquiz/app/database/UserDao.java#L30)）。
- 注册直接 `user.setPassword(password)` 入库（[UserRepository.java:62](../src/main/java/com/oilquiz/app/repository/UserRepository.java#L62)）。
- 首启自动创建 `admin` / `123456` 并置 `isLoggedIn=1`（[SmartQuizApplication.java:821](../src/main/java/com/oilquiz/app/SmartQuizApplication.java#L821)）。
- `allowBackup="true"`（[Manifest:84](../src/main/AndroidManifest.xml#L84)）且备份规则 include 整个 `database` 域（[data_extraction_rules.xml:5](../src/main/res/xml/data_extraction_rules.xml#L5)）。

**为什么算设计问题**：产品是本地单机题库，却做了一套"账号系统"的表象；既没有服务端校验，也没有本地凭据保护，反而引入了默认口令和备份泄露面。要么承认"不需要账号"，要么按真账号对待，中间态最差。

**修复方向**

- 首选：删掉登录/注册/找回，改成设备内"档案"（多用户用 profile 表，不做口令）；
- 若保留：PBKDF2/Argon2 + 每用户 salt（迭代次数随版本可升级），删默认账号，备份规则排除 `database` 或对备份加密。

**验收**：`adb shell` 拉出的 DB 中无明文口令；首次启动不预置账号；恢复备份后无需旧口令即可进入（或明确要求重设）。

### D4 全局明文流量 — P1

`<base-config cleartextTrafficPermitted="true">`（[network_security_config.xml:5](../src/main/res/xml/network_security_config.xml#L5)）。注释的理由是"Agent 传入的媒体链接多为 http"，但这条配置同时放开了**所有**域名的明文，包括用户配置的 API `base_url`。Key 走 http 时中间人可直接拿走。

**修复方向**：默认 false；已列出的百炼 OSS 域名与 `localhost` 保留白名单；Agent 媒体链接走"按 URL 单独校验 + 仅媒体域"的例外，不放开全局。

**验收**：非白名单 `http://` 请求被 `CleartextNotPermittedException` 拒绝，图片/音频卡片功能不回退。

### D5 `Security.java` 的假加密 — P2

`encrypt` 是字符 +1 位移（[Security.java:29](../src/main/java/com/oilquiz/app/infra/Security.java#L29)），`hashPassword` 无盐、异常时**返回明文**（[Security.java:19](../src/main/java/com/oilquiz/app/infra/Security.java#L19)）。当前全仓无调用点（已 grep 确认），属于"没人用的地雷"。

**修复方向**：删除，或替换为 `ApiKeyCipher` 同级的真实实现；不要留着"看起来能用"的加密 API。

---

## 2. 模块边界

### D6 上帝类 — P1

| 文件 | 行数 | 混在一起的东西 |
|---|---:|---|
| `AIChatActivity` | 11,691 | UI、流式渲染、工具编排、权限、附件、语音、NPU 分流 |
| `AIService` | 5,100 | 本地模型生命周期、推理队列、引擎切换 |
| `PythonToolManager` | 4,959 | Python 运行时、工具注册、UI 组件、播放器 |
| `OnlineInferenceService` | 4,606 | HTTP、4 套协议方言、能力探测、上下文窗口缓存、token 统计 |
| `NativeLayoutRenderer` | 4,189 | JSON → 原生 View 全套控件 |
| `WebViewActivity` | 3,533 | WebView、JS 桥、题库预加载、文件重定向 |
| `OnlineAgentEngine` | 2,884 | Agent 循环、工具调度、流式渲染、错误恢复 |
| `SystemResourceTool` | 2,634 | SSH、Shell、文件、进程、媒体 |
| `AIToolManager` | 2,114 | 工具注册、schema 生成、执行编排 |

**为什么算设计问题**：这些类里同时存在"传输/协议/领域/呈现"四层，任何一层变化都要动同一个文件；测试只能测到最外层的静态方法，所以覆盖率自然上不去。HANDOVER 里 `AIChatActivity` 还是 10,165 行，说明它仍在增长。

**修复方向**：按**接缝**拆，不重写。先拆"有明确输入输出、可 JVM 测试"的部分（协议 codec、payload builder、usage 解析），再拆 UI 的 presenter/controller；`OnlineInferenceService` 的拆法见 D8。

**验收**：单文件 ≤ 1,500 行；新增一家服务商只改 `codec/` 目录，不改 Activity。

### D7 三套 Agent 循环并存 — P1

- `OnlineAgentEngine`（在线，`AgentRouter` / `AgentSession` / `AgentDebugBridge` 在用）；
- `AgentLoopEngine`（`AgentSoftwareLayer` 用，面向软件操作）；
- `AgentExecutionEngine`（全仓**没有任何 `new`**，死代码；`OnlineAgentEngine` 的注释明确写"不依赖 AgentService、AIService、UnifiedAgentEngine 或 InferenceRouter"）。

**为什么算设计问题**：同一个"计划→调工具→回填结果"的循环有三份实现，取消、超时、重试、usage 统计各写各的。上一次 Responses 排障里"工具被执行两遍"那类问题，根因就是这类重复没有单一事实来源。

**修复方向**：保留一个循环骨架（建议以 `OnlineAgentEngine` 为基底），传输差异下沉为 `AgentTransport` 策略（chat / responses / anthropic / local）；删除 `AgentExecutionEngine`；`AgentLoopEngine` 若确属不同产品能力（操作手机），在文档里写清边界而不是复制循环。

**验收**：全仓只有一处 `Thread`/取消信号驱动 agent 循环；`grep "class .*Engine"` 的 agent 类不超过 2 个。

### D8 协议方言没有真正抽象 — P1

`OnlineInferenceService` 同时实现 OpenAI chat、Responses、Anthropic、Ollama 四套请求构造与流解析，还夹带能力探测（真发请求试 vision / function calling / web search）、上下文窗口探测与缓存、思考档位、输出上限推算。

对照正例：Responses 已经把事件语义抽到 `ResponsesStreamParser`（36 例测试）、请求形状抽到 `ResponsesPayloadBuilder`（18 例），失败还有"未吐字即回落 chat"的安全网——这正是应该推广的模式。

**修复方向**：`ChatTransport`（连接/重试/SSE 读取）+ `ChatCodec` 四个实现（`OpenAiCodec` / `ResponsesCodec` / `AnthropicCodec` / `OllamaCodec`）+ `CapabilityProbe` + `UsageParser`。`generateStream` 只剩编排。

**验收**：`OnlineInferenceService` 降到 1,000 行以内；每个 codec 有独立的 fixture 测试；新增协议只加一个文件。

### D9 并发模型 — P1

全仓 **190 处 `new Thread(...)`**；Repository 在 Room 之上再包 raw thread + `Future`（[UserRepository.java:22](../src/main/java/com/oilquiz/app/repository/UserRepository.java#L22)）；`DatabaseManager` 自己维护线程池并在 shutdown 后重建（[DatabaseManager.java:38](../src/main/java/com/oilquiz/app/database/DatabaseManager.java#L38)）；协程只在 Compose 局部使用。

**为什么算设计问题**：没有统一的 scope 就没有统一的取消。生成中断、页面退出、任务切后台，各自靠 flag 与 `isCancelled` 兜；线程也无法在测试里被替换成同步执行器。

**修复方向**：一个 `AppScope`（`SupervisorJob + Dispatchers.IO`）或共享命名线程池；Repository 改成 `suspend`（Room 自带执行器）；"停止生成"经 `Job.cancel()` 传播到 OkHttp `Call.cancel()`。新代码禁止裸 `new Thread`。

**验收**：新增代码无裸 `new Thread`；停止生成在 100ms 内真正中断 IO（而非只改 UI 状态）。

### D10 DI 名义存在 — P2

Hilt 已接入，但全仓只有 7 个 `@Inject`（集中在 `AIChatViewModel`），其余是 `getInstance(Context)` 单例 + 静态持有 Context。

**修复方向**：二选一，别停在中间——要么把核心 manager 改成构造注入并让 Hilt 管生命周期，要么移除 Hilt 减少构建成本。新写的业务类一律构造注入。

---

## 3. 数据边界

### D11 破坏性迁移 + 空 catch + 运行时改表 — P0

**现状**

- `addMigrations(...)` 同时开了 `fallbackToDestructiveMigration()`（[AppDatabase.java:97](../src/main/java/com/oilquiz/app/database/AppDatabase.java#L97)），`exportSchema = false`（[AppDatabase.java:39](../src/main/java/com/oilquiz/app/database/AppDatabase.java#L39)）。
- 迁移体里大量空 catch（[AppDatabase.java:340](../src/main/java/com/oilquiz/app/database/AppDatabase.java#L340) 起），且**表名写错的一侧已经定位**：`Question` 实体的 `tableName` 是 `question`（单数，[Question.java:13](../src/main/java/com/oilquiz/app/model/Question.java#L13)），而 17→18 的两条 `ALTER TABLE questions ...`（[AppDatabase.java:157](../src/main/java/com/oilquiz/app/database/AppDatabase.java#L157)、[162](../src/main/java/com/oilquiz/app/database/AppDatabase.java#L162)）打在一张不存在的表上，异常被 `catch (Exception e) { /* 字段可能已存在，忽略错误 */ }` 吞掉——注释猜的原因是"字段已存在"，真实原因是 `no such table`。更糟的是 18→19 里两条 `CREATE INDEX IF NOT EXISTS ... ON questions(...)`（[AppDatabase.java:210-211](../src/main/java/com/oilquiz/app/database/AppDatabase.java#L210)）**没有包 try/catch**，而 `questions` 这张表在整个 schema 里从未被创建（全仓只有这两处迁移与 `DatabaseUpgradeManager` 引用它，三处都不建表）——按 SQLite 语义它应当直接抛错让 18→19 失败，而 `fallbackToDestructiveMigration()` 只在"没有迁移路径"时兜底、迁移体抛错时是崩溃。这条**需要在真机/`MigrationTestHelper` 上复现一次再定性**（本机没跑过升级），但它足以说明"迁移失败会静默"不是假设。
- `DatabaseFieldManager` 允许运行时 `ALTER TABLE` 加列（删除/修改已禁），`DatabaseTool` 又允许 Agent 直接改 schema。

**为什么算设计问题**：题库 App 的数据库是用户资产（错题、成绩、笔记）。当前设计里"schema 错了"的默认后果是清库，"迁移失败"的默认后果是静默继续——两个默认都选错了方向。

**修复方向**

- `exportSchema = true` + 提交 schema JSON + `MigrationTestHelper` 覆盖 24→25 起的每一跳；
- 去掉 `fallbackToDestructiveMigration`（至少 debug 崩溃、release 保留但上报）；
- 空 catch 改为"记录 + 计数"，迁移里只容忍"列已存在"这一种已知错误（用 `PRAGMA table_info` 先判断，不靠异常）；
- 扩展字段收进 JSON 列或 EAV 表，禁止运行时物理加列。

**验收**：仓库里有 schema JSON 与迁移测试；`grep -c "catch (Exception e) {}" AppDatabase.java` 为 0；删表/改表路径在代码评审中被拒。

---

## 4. 工程边界

### D12 测试与 CI — P1

没有 `.github/`（无 CI）。CHANGELOG 长期把 **342 tests / 25 failed** 当"既有基线"记（10-18 三条记录都是 25）。**2026-10-19 复跑并清零**：`testDebugUnitTest --rerun` 当时记为 **36 个类 / 407 tests / 0 failed**。

> **10-19 晚复核：这个 407/0 也不作数，而且它把 D12 的判断加强到了难看的程度。**
> `build.gradle` 没开 `testOptions.unitTests.includeAndroidResources`，
> Robolectric 于是跑在 legacy resources mode（Android P 之后不受支持），
> 只要钉了 `sdk=33` 就**整类跳过**——Gradle 记成 `skipped`，既不是 failure 也不是 pass。
> 11 个类 128 条用例当天上午一条都没跑，而我只核对了 `failures="0"`。
> 开齐配置后真跑的 132 条里 18 条红，其中 2 处是真生产缺陷
> （`TemplateManager` 首建/补建模板时不刷新 `templateMap`，`getTemplateById` 恒 null；
> `scene_mistake` 描述承诺"包含错误次数"却漏了 `incorrectCount` 字段），
> 10 条是测试环境没套清单主题（`ThemeColors` 对主题语义色走 `?attr`，解析不到按设计返回 0），
> 4 条是 `PreviewRenderBridgeTest` 自带 `@Config(sdk = P)` 与 minSdk 31 冲突。
> 全部修完：**411 tests / 0 failed / 0 skipped**。
> 当时那 25 条红的根因也订正了：不是"没钉 API 等级"，
> 而是缺 `includeAndroidResources` ——老 android-all 镜像里没有 `Context.getColor(int)`
> 正是 legacy 模式跑在低档位的表征。
> **结论比原来更硬：在这个仓库里，"绿灯"本身必须先证明用例真跑过（数 skipped），
> 否则它既不能证明坏了、也不能证明没坏。**
> 测试仍集中在工具类与 parser，`OnlineInferenceService` 只有 parser 级覆盖。

**为什么算设计问题**：红灯常驻等于回归信号失效。上次 `delta` 被当 `JsonObject` 的 bug 能长期躺在主干，与"失败是常态"直接相关。

**修复方向**：GitHub Actions 跑 `testDebugUnitTest` + "不得新增失败"门禁；为 codec 增加 fixture 测试（原始事件流冻成文件）；确实要长期挂起的失败用例才 `@Ignore("tracking #N")`，让绿=真绿。

**再加一条同类机制：测试框架的 API 等级也要钉。** 本次清零的 13 条红就出在这里——应用 `targetSdk=36` 超出 Robolectric 4.10.3 的支持上限（33），**不显式钉等级时它不报错，而是静默退到本地缓存里更老的 android-all 镜像**，那批框架类连 `Context.getColor(int)`（API 23 才有）都没有，于是 `ThemeColors.get()` 的兜底分支在单测里抛 `NoSuchMethodError`，表现成"资源/导出类用例成片红"，看起来像生产代码坏了。修法是 `src/test/resources/robolectric.properties` 写 `sdk=33`（已提交）。这类坑的共同点是：**harness 与目标平台不匹配时没有响亮失败**，所以 CI 里除了"不得新增失败"，还应断言跑测所用的 SDK 等级与依赖版本是显式的。

**验收**：`main` 上单测全绿（已知问题以显式 `@Ignore` 标注并带链接）；PR 必须过 CI。

### D13 构建可复现性 — P1

- 硬编码 `D:\Android\Sdk\cmake\...\ninja.exe`（[build.gradle:104](../build.gradle#L104)）、`C:\Users\xiaocong\...\Python310\python.exe`（[build.gradle:126](../build.gradle#L126)）、`file:///D:/qzq/smartquiz/python-wheels`（[build.gradle:197](../build.gradle#L197)）；
- release 签名口令是字面量 `"password"`（[build.gradle:250](../build.gradle#L249)）；
- 700 行构建脚本里混了三段"DSH 修复"自愈补丁（Chaquopy pip、llama.cpp 编码），应用配置与构建修复耦合。

**修复方向**：路径进 `local.properties` / 环境变量并给出缺省报错；签名读 `keystore.properties`（gitignore）；把补丁移到 `scripts/` 或 init script，构建脚本只做应用配置；版本号/pin 统一。

**验收**：新机器按 `SETUP_GUIDE` 不改文件即可 `assembleDebug`；`grep -n "C:\\\\Users" build.gradle` 为空。

---

## 5. 产品边界

### D14 找回密码假成功 — P2

`UserRepository.resetPassword()` 只查邮箱，注释写"这里应该发送密码重置邮件"，实际什么都不做（[UserRepository.java:93](../src/main/java/com/oilquiz/app/repository/UserRepository.java#L93)），界面却提示"重置邮件已发送"（[ForgotPasswordActivity.java:59](../src/main/java/com/oilquiz/app/ui/activity/ForgotPasswordActivity.java#L59)）。

**修复方向**：随 D3 一起删；若保留，改成"本地重置"（验证设备锁/旧口令）并去掉邮件文案。

### D15 权限面与定位 — P2

`MANAGE_EXTERNAL_STORAGE`、`QUERY_ALL_PACKAGES`、`REQUEST_INSTALL_PACKAGES`、`ACCESS_BACKGROUND_LOCATION`、读写联系人、`SYSTEM_ALERT_WINDOW` 等（[AndroidManifest.xml:16](../src/main/AndroidManifest.xml#L16) 起），与"答题宝"的定位不匹配；这也是"工具箱"能力（SSH、投屏、装 APK、Termux）带来的结构性后果。

**修复方向**：这是产品决策，不是纯技术债。要么把工具箱拆成独立 APK/插件（权限隔离），要么在设置里显式说明每项权限对应的功能；上架前必须逐项给出理由。

---

## 6. 已经做对的部分（契约，别回退）

| 设计 | 位置 | 为什么值得保留 |
|---|---|---|
| 协议由配置表决定 | `ProviderConfigManager.agentUsesResponses` / `ChatProtocol` | 调用方不写 `endsWith("/responses")`，新增服务商只改配置 |
| Responses 事件解析独立 | `ResponsesStreamParser`（36 例） | 不依赖 Android，可 JVM 测试，顺序/去重有锁 |
| 请求形状集中 | `ResponsesPayloadBuilder`（18 例） | 图片/工具/system 只在一处拼 |
| API Key 加密 | `ApiKeyCipher` + `keystoreAvailable()` | Keystore AES-GCM，且能区分"真加密/退化明文" |
| 失败回落 | 文本流与工具流"未吐字才回落 chat" | 新协议敢放量的前提 |

这三条正是解药：**边界 + 单一事实来源 + 可测**。D6/D8 的拆法就是把这个模式复制到其余协议。

---

## 7. 建议顺序

1. **D1 + D2**（收口导出面）——公开仓库 + 现成攻击面，收益/成本最高；
2. **D3**（删或真做登录）——顺手解决 D14、备份泄露；
3. **D11**（迁移可测 + 去掉静默清库）——用户数据不可再生；
4. **D4**（明文流量）——半天；
5. **D12**（CI + 绿基线）——没有它，后面所有拆分都无法证明"没拆坏"；
6. **D8 → D6**（先协议 codec，再 UI presenter）——按"被改频率"排，不按行数；
7. **D7 / D9 / D10**（循环统一、并发收敛、DI 收口）——随拆分逐步做，不单独开大工程；
8. **D13 / D15**（构建与产品边界）——发布前必须收口。

**拆分顺序的经验法则**：先拆"有明确输入输出、能在 JVM 里断言"的（codec、builder、parser），后拆"需要真机验证"的（Activity、渲染）；每拆一步，测试数应上升而不是持平。

---

## 8. 未验证 / 存疑（不要当结论引用）

- `ApiConfigActivity` 被拉起后是否直接显示完整 Key，未逐行读完 1,319 行；
- `AgentDebugBridge` 的 token 强度、"默认关闭"的实际实现未逐行验证，仅依据注释与清单；
- 云备份是否真的会把 `database` 域带走，取决于系统备份策略与 `backup_rules.xml`，本文只依据 `data_extraction_rules.xml` 的 include；
- 空 catch 计数只统计单行 `catch (...) {}`，多行空块未计，真实数量更高；
- ~~25 个失败用例的具体名单未在本机复跑，引用自 CHANGELOG 记录~~（10-19 已复跑：根因与逐条判断见 `docs/ui/HANDOVER.md` §7 与 CHANGELOG 顶部 10-19 一节；当晚复核更正——407/0 那次有 128 条 Robolectric 用例被静默跳过，真跑并修完是 **411 tests / 0 failed / 0 skipped**）；
- `AgentExecutionEngine` "无调用点"基于 `grep "new AgentExecutionEngine"` 与类名引用，若有反射调用则结论不成立；
- 所有行号基于 2026-10-19 的工作区（含未提交改动），提交后可能偏移。

---

## 附录 A 度量口径

| 指标 | 值 | 口径 |
|---|---:|---|
| Java 源文件 | 896 | `glob src/main/java/**/*.java` |
| 测试类 | 30+ | `glob src/test/**/*.java` |
| 裸 `new Thread(` | 190 | `grep -c "new Thread(" src/main/java`（含带名字的 `new Thread(r, "x")`） |
| 单行空 catch | 182（放宽到任意异常类型与空白后为 243）| `grep "catch \(Exception \w*\) \{\s*\}"` |
| `@Inject` | 7 | 全部在 `AIChatViewModel` |
| 最大文件 | 11,691 行 | `AIChatActivity.java` |
| 单测基线 | 411 / 0 failed / **0 skipped** | 10-19 晚 `testDebugUnitTest --offline --rerun`（37 个类）。当天早些时候记的 407/0 不作数：`includeAndroidResources` 没开，11 个 Robolectric 类 128 条用例被整类跳过 |

> 复跑方式：上述 grep 均可直接重放；行数用 `(Get-Content file).Count`。这些数字用于横向比较趋势，不作为精确 KPI。
