# 秘塔联网搜索契约与模型可见面（2026-11-02）

`network_search` 的真实数据源是秘塔（Metaso）开放接口。这份文档只写**实测确认**的行为，
并把"模型看到的地方"逐条列出来——本仓库里同一套能力散落成九处文案，改参数不改文案就会出现
"教模型传一个必被服务端拒的字段"这类问题。

核实状态标记：`✓` = 真机（com.oilquiz.app，AgentDebugBridge 注入）跑到并读到响应；
`R` = 参考实现/官方示例读到，本机未单独验证；`✗` = 未验证，不要当事实用。

## 一、端点与请求体

| 能力 | 端点 | 请求体 | 状态 |
|---|---|---|---|
| 关键词搜索 | `POST https://metaso.cn/api/v1/search` | `{q, scope, includeSummary, includeRawContent:false, size:"<字符串>"}` | ✓ |
| 网页读取 | `POST https://metaso.cn/api/v1/reader` | `{url}`，header `Accept: text/plain` | ✓ |
| 智能问答 | `POST https://metaso.cn/api/open/search/v2` | SDK 的 `Query{question, lang, sessionId, stream, ...}` | ✓（返回 answer/references/balance） |

三条硬口径：

0. **计费按次：一次调用 3 积分，与 `size`/返回条数无关**（用户口径）。所以"多看几条"是免费的（同一次调用内调大 `size`），
   而**多问一次**才花钱。所有文案与失败处置都按这条写：一次问清楚、一次给够条数，不要连续换词重搜。
1. **`size` 传字符串**（`"5"`）。官方示例与社区实现都这么传，传数字同样能过，但保持一致少一类分歧。R
2. **没有翻页：`page` 不能出现在 body 里。** ✓ 实测带 `page`（连 `page=0` 也一样）服务端返回
   HTTP 200 + `{"errCode":1000,"errMsg":"请求参数错误"}`。以前把它当"支持翻页"写进 schema，
   模型照着传就永远拿到"参数错误"，而旧解析器**不看 errCode**，于是被判成"搜到 0 条"，
   表现为反复重搜（真机见过连续 5 次同参搜索 = 15 积分）。现在 `page` 已从所有可见面摘掉，
   代码里只保留一条 WARN：模型（或旧会话残留）传了就记下来并忽略。
3. **`includeSummary` 默认 true**。关掉后结果常常没有 `snippet`，只剩标题+链接，模型只能靠编。

### action 的选法（也是计费口径）

`ask` 是**默认答案通道**：一般提问它就够，返回结论+引用来源+`balance`。`search`/`read_url` 等只在
需要"一批来源/链接/原文"时用。**ask 出答案要几十秒是正常的**——它服务端边搜边生成。

## 二、`scope` 取值与响应字段

`scope` 六选一（`R`+部分 `✓`）：`webpage`（默认）/ `document` / `scholar` / `image` / `video` / `podcast`。

- 学术文献的键是 **`scholar`，不是 `paper`**。`normalizeScope` 把 `paper`（以及历史上的错误写法）映射到 `scholar`；
  其它非法值一律退回 `webpage`。
- 每个 scope 的结果数组键名不同：`webpages` / `documents` / `scholars` / `images` / `videos` / `podcasts`。
  解析器 `parseMetasoSearchResponse` 按这张表逐个试，再退到通用键 `results/data/searchResults/items/list/value`。
- **已真机验证**：`scope=scholar, size=3` → 200，`Parsed 3 search results`，条目是 arxiv 等论文源。
- `image/video/podcast` 的解析路径按同一张表实现，但**未单独真机验证**（`✗`）。第一次用到时先看
  logcat 的 `Parsed N search results`，为 0 就把响应体前缀打出来对照字段名。

**结果必须回显实际生效的 scope**（`SCOPE-ECHO`）：非法值会被静默退回 `webpage`，不回显的话模型会
以为"搜过论文库"并据此作答。组合动作（`search_and_read` / `smart_search`）内部会重建一次搜索参数，
`scope` / `include_summary` 现在显式透传（`SCOPE-THRU`），否则"搜论文"在组合路径上会降级成普通网页。

## 三、网页读取（reader）的真实返回

- 无论是否要求格式化，`/api/v1/reader` 返回的都是 JSON：`{title, url, author, date, markdown, credits}`。
  ✓ 因此解析侧取 `markdown` 当正文，**不把整段 JSON 塞给模型**（旧实现是"JSON 套 JSON"，正文要模型自己拆）。
- **失败也是 HTTP 200**：读不了的页面（例：`https://metaso.cn/` 自身这类 SPA 首页）返回
  `{"errCode":4000,"errMsg":"未找到相关数据"}`。✓ 现在识别 errCode 并判成**工具失败**，
  文案写成"服务错误码 4000（未找到相关数据）"——写成 `失败(4000)` 时 2B 会读成"HTTP 400"并编出一段解释。
- 长正文按字符续读：`start_char` / `max_chars`，返回里带 `page:{total_chars,start_char,end_char,truncated,next_start}`。
- `format` 字段不用传（参考实现只传 `{url}`）。R

## 四、错误码

| errCode | 含义 | 我们的处置 |
|---|---|---|
| 0 / 缺省 | 正常 | 解析结果 |
| 1000 | 请求参数错误（实测由 `page` 触发） | 搜索：抛出错误；已不再发 `page` |
| 4000 | 未找到相关数据（reader 读不出该页） | reader：判失败 + 指路换来源 |
| 5000 | 服务端错误（本机 PC 直连时见过，不代表手机侧） | 抛出，交上层 |

HTTP 层：401 → "认证失败，请检查 API 密钥"；429 → "频率超限"。业务错误与 HTTP 错误分开报，
否则"额度用尽"会被读成"没搜到"。

### 4.1 定性只看码，不看耗时（2026-11-02 用户口径）

三个端点都用同一套取码/翻码：`peekMetasoErrorCode` / `extractMetasoErrorCode`（认 `errCode`/`errMsg`
与 `code`/`message` 两种键族）→ `metasoErrorText(code,msg)` 翻成"带下一步动作"的话。
**没有任何一处用耗时/等待时长去推断成败**——`metasoErrorText` 里已知码只写实测见过的
（1000/4000/5000），未知码原样带编号与消息，不编造含义：

| 码 | 翻给模型的下一步 |
|---|---|
| 1000 | 参数被拒：检查是否传了翻页参数、scope 是否合法、size 是否 1..50；**改正参数再发**，不要原样重试 |
| 4000 | 该目标服务端解析不了：换来源/换词；对同一目标重复请求没有意义 |
| 5000 | 服务端或额度问题：内置共享 Key 可能失效/额度用尽 → 引导到「设置 → API 配置 → 秘塔搜索」填自己的 Key；**先别重试** |
| 其它非 0 | 原样带码与消息 + 提醒"重试会再发一次请求，按次扣额度" |

HTTP 状态码同样定性（`peek` 拿不到业务码时才看它）：401/403 鉴权问题（重试无意义）、
429 限流（等一会儿，连续重发只会继续被限）。传输层异常（连接被掐/读超时）单独说明
"这是传输异常，不是服务端返回的业务码"。

**降级会二次扣费，所以按码限制**：`ask` 失败时只有 **404/405（问答端点不存在/不允许，属能力缺失）**
才自动补打一次 `search`（结果里 `mode=search_fallback` 并写明"回退同样算一次调用"）；
业务码错误、5xx、限流、传输异常都**不自动补打** —— 一次提问扣两次钱是最糟的结果。

### 4.2 超时：工具自己申报，三条执行路径共用

`NetworkSearchTool.executionTimeoutMs(args)`：`ask` 180 秒、其它 60 秒。
调用方按 `ToolTimeouts.effectiveMs = max(基线, 申报)` 取值，三条路径都已接：

| 路径 | 位置 | 之前 | 现在 |
|---|---|---|---|
| 在线 Agent | `OnlineToolManager.effectiveTimeoutMs` | 已用 ToolTimeouts ✓ | 自动拿到 180s |
| 对话页 native FC | `AgentService` | 已用 ToolTimeouts ✓ | 自动拿到 180s |
| 本地软件 Agent | `AgentLoopEngine.toolWaitMs` | **硬编码 15 秒**，不读申报 → ask 必被判"工具超时(15秒)" | 改用 `ToolTimeouts.effectiveMs`，并行批次取各调用的最大值 |

HTTP 层也要放宽：共享客户端 `readTimeout` 是 30 秒，底层会先掐断，所以走
`clientFor(ASK_TIMEOUT_MS)`（`NetworkUtil.getClient().newBuilder()` 派生，连接池仍共享，按档缓存）。
不这样做的话，工具层申报的 180 秒永远等不到。

### 4.3 按次付费 ⇒ 三条执行路径都不自动重试（2026-11-02）

"只读所以超时可以放心重试"这条推论对秘塔不成立：**超时不代表没被受理，而受理就计费**。
判据集中在 `ToolSideEffects`（单一写者，与缓存/副作用口径同类）：

```java
BILLED_PER_CALL_TOOLS = {network_search, smart_research}   // smart_research 内部也是秘塔调用
isBilledPerCall(name)            // 允许缓存（缓存恰恰在省钱），禁止自动重试
mayRetryOnTimeout(name)          // = 只读幂等 && !按次付费
```

三个消费者都改问它，口径一致：

| 路径 | 位置 | 改动 |
|---|---|---|
| 在线 Agent | `OnlineToolManager`（`MAX_RETRY=2`） | 超时分支加 `isBilledPerCall` → break，最多变成"没重试"而不是三次扣费 |
| 对话页 native FC | `AgentService.executeWithRetry` | 有副作用之后新增一档：按次付费直接返回"不自动重试，很可能已扣过一次额度" |
| 本地软件 Agent | `AgentLoopEngine` 的 `MAX_RETRIES` 同参重跑 | 判据从写死 `!"network_search".equals(...)` 换成 `mayRetryOnTimeout(toolName)` —— 以前 `smart_research` 照样会被原样重跑一次 |

失败文案也不再写"再试一次就好"：按次付费的超时话术是"这一次很可能已经扣过额度，要不要再来由你决定"。

### 4.4 发现通道（`tool_registry` 的 `call`）继承被代执行工具的申报

`tool_registry(list/search/get)` 只是看一眼，`call` 才是**替模型执行另一个工具**。
以前它自己没申报超时，于是经 `call` 执行的 `network_search(ask)` 只享受调用方基线（本地 15 秒），
表现就是"秘塔明明答得上来，却因等 15 秒被判失败，而这一次已经扣掉了"。
现在 `ToolRegistryTool.executionTimeoutMs(args)` 在 `action=call` 时解析 `tool`+`args`，
返回**内层工具自己的申报值**（Map / JSON 字符串两种 `args` 都认；读不到就退回基线，绝不因申报失败判死）。


Key 取值顺序（`resolveApiKey`）：扁平键 `Service.METASO_SEARCH` → `APIConfig.serviceType=metaso_search`
→ `bing_search`（历史） → `Category.SEARCH` 且 apiHost 指向秘塔 → 内置共享 key。
命中哪条会打进日志（`Metaso API key 来源: …`），排查"为什么扣我的额度"先看这行。

### 4.5 API 返回的数据不截断（2026-11-02 用户口径）

用户两句口径合起来是一条规则：**"api 返回的数据不要截断" + "返回的数据条数模型可以自己设置"**。
条数的决定权在模型的 `limit`（1..50）里，不在引擎里；引擎代砍等于**把已经花 3 积分买回来的条目丢掉**，
模型看不见那几条就用自己的知识补 —— 这是幻觉，也是白花钱。

落地（都改了，别再改回去）：

| 位置 | 以前 | 现在 |
|---|---|---|
| 本地引擎 `AgentLoopEngine.formatToolResultForModel` | 三档裁剪：数组只留前 8 条（`omitted:N`）、单字段 400 字、正文/总长 6000 字，超了改写成 `{"truncated":true,"data":"<前缀>"}` | **全部原样给模型**。`shrinkForModel`/`isBodyField` 与 `MAX_RESULT_ARRAY_ITEMS`/`MAX_RESULT_STRING_CHARS` 已删除；只在结果 > 提示线时补 `size_chars` + `note`（"本结果完整给出，未截断…"），并打 W 日志 |
| 本地引擎装不下时 | 静默裁数据 | `trimHistoryToFit` 只挤**更早的整条历史**（丢旧对话，不丢本次数据，要点仍缓存复用保前缀稳定）；若挤完仍超预算，安全网把**是谁撑爆的**写进退出归因：`<工具名> 返回了 N 字符（未截断），已超出本轮上下文预算` |
| 发现通道 `tool_registry` 的 `list(detail)/search/get` | 描述按字数硬切：40 / 60 / 参数 160 / 工具级 400，并带 `description_truncated` | 一律**全文返回**；`clipAtSentence` 与 `description_truncated` 一起下线。原先切 40/60 字正好把"默认用 ask""按次计费"切成半句，模型在发现通道里读到残句只能靠猜 |
| 在线 `OnlineToolManager` | 结果超 16K 时取前缀 + 落盘 | 保持"落盘 + 指针"（全文在文件里，数据没丢），这是它的不裁形式 |

仍然保留的一处**显式可续读**分页（不是静默截断，留台账）：`read_file` 的自动合并
（`autoContinueReadIfLarge`，120 行/页、最多 3 页、合并 ≤ 2×6000 字符，超限在结果里写"可用 `start_line=N` 继续"）。
它切的是**工作区文件**，且告诉模型怎么把剩下的拿回来；要一并放开就说一声。

锁住这组的不变量：`MetasoBillingAndDiscoveryTest.engineNeverClipsToolResultData` /
`registryNeverClipsDescriptions`（断言 `shrinkForModel(`、`MAX_RESULT_ARRAY_ITEMS`、
`out.substring(0, MAX_TOOL_RESULT_LENGTH)`、`clipAtSentence(`、`put("description_truncated"` 都已不存在，
且 `size_chars` 与 `OVERSIZE-SAY` 归因在位）。

### 4.6 哪些 action 真的扣额度（手机实测补的口径，2026-11-02）

用户在自己手机上让模型"把每个 action 都试一遍"，一轮跑下来暴露三件事，都在这侧：

| action | 实际走哪条路 | 扣额度 | 备注 |
|---|---|---|---|
| `ask` | open search v2（服务端） | ✓ 1 次 | 默认答案通道 |
| `search` | `/api/v1/search` | ✓ 1 次 | 要一批来源时才用 |
| `read_url` | `/api/v1/reader` | ✓ 1 次 | 服务端解析，可能回 4000 |
| `search_and_read` / `smart_search` | 内部各调一次 `search` + 本地抓正文 | ✓ **只算搜索那一步** | 抓正文是 Jsoup，不另计 |
| `get_webpage` / `extract_info` / `summarize` / `get_dynamic_content` / `smart_read` | 本地 Jsoup + 正则/统计 | ✗ 不扣 | 重试这些不花钱 |

这条边界现在写在六处可见面里（工具顶层描述、`@Action`、ToolDefinition、`AgentService` 清单、两份提示词、使用指南），
理由很直接：模型不知道哪些是免费的，就会**因为怕扣额度而不敢改正参数重试**，
也会在本地就能做的动作上绕去 `read_url` 白花钱。缺参文案也统一补了"（这次没有发出请求，不扣额度）"。

三个实测抓到的缺陷（都已修）：

1. `summarize` 四连失败：它要的是 `results`（或 `url`），而缺参文案让它补 `query` —— 模型照做永远不对。
   现在按真实必填项说，并**新增单页形态**：只给 `url` 就本地抓该页再摘要（以前根本不支持）。
2. `smart_read` 报"需要 results"：解析只认 Java `List`，模型把上一步的数组按 JSON 字符串贴进来就被拒。
   现在 `parseResultsArg` 收三种形态：真数组、JSON 字符串、以及连外层 `{query,count,results:[…]}` 一起传进来的整段返回；
   `summarize` 同用一个解析器（原来是裸强转，字符串直接 `ClassCastException` → "生成摘要失败"）。
3. `search_and_read` 名不副实：不传 `detailIndex` 时只回清单 + "请带 detailIndex"，等于白搭一轮推理。
   现在默认读前 3 条正文（本地抓取，不额外扣费），要指定某一条才用 `detailIndex=N`。
   `extract_info` 的描述也改成实话：它返回页面元数据（标题/meta/各级标题/切片/内链/分类/关键词），
   **不是语义抽取** —— 要从页面拿某个具体答案应该 `read_url` 拿正文或 `ask` 提问。

### 4.7 在线"联网搜索"的预搜索闸门（2026-11-02）

勾选联网后，`OnlineInferenceService.applySearchToPrompt` 以前对**每一条**消息先打一次
`preSearch(..., limit=5)`（固定扣 3 积分），成功还顺手抑制官方联网注入。等于**每家服务商都先烧一次我们的额度**，
哪怕它自己就能在服务端搜。现在两道闸，顺序不能换：

1. **官方优先（按端点判断）**：配置表声明了 `services.webSearch` 且当前端点就是它支持的那个
   （`getWebSearchEndpoint(apiUrl)` 匹配）→ 不预搜索，交给服务端执行。
   参考 DeepSeek 的做法校准过这条判据：它 **Chat Completions 没有内置联网参数**
   （`web_search_options`/`enable_search` 实测都不生效），只有 Responses 端的 `web_search` 工具
   （且目前仅部分模型），所以"有 param 就跳过"是错的，必须**按端点**判断；不合端点才回落到自有搜索。
2. **意图闸**：官方用不上时，只有问题本身要实时信息才预搜索；否则交给模型自己调
   `network_search`（默认 ask）。词表按用户业务挑（时间词、行情/价格、天气、新闻赛事、政策与"最新/最近/现在"），
   并先排掉"处理已有文本"的任务（翻译/润色/解释/写代码——里面出现"今天""天气"是在引用原文）。
   判漏的代价只是模型自己再搜一次（多一轮推理），判宽的代价是每条普通提问都白烧一次额度，
   所以**宁可窄**。

复现与验证：`OnlineSearchGateTest` 锁住两组正反例与"三条调用点都带 `apiUrl`"。

## 五、模型可见面清单（改参数必须逐条对账）
| # | 通道 | 位置 | 本次状态 |
|---|---|---|---|
| 1 | 本地/在线注入的 tools schema | `AIToolManager.getToolDefinition("network_search"/"webpage_reader")` | ✓ 去 `page`、加 `scope/include_summary/start_char/max_chars`、`limit` 写明 1..50 且"不支持翻页" |
| 2 | 工具注解（注册表目录、`tool_registry(list/search)` 的来源） | `NetworkSearchTool` / `WebPageReaderTool` 的 `@Tool/@Action/@Param` | ✓ 同上；action 描述补"要结论用 ask、要列表用 search"、read_url 会回服务错误码 |
| 3 | `tool_registry(get)` 返回的完整 schema | 走第 1 条的 `ToolDefinition` | ✓ 自动同步（这就是它权威的原因） |
| 4 | native FC 路径的工具清单 | `AgentService.registerToolSchema(...)` | ✓ 同步（含 `search` 别名与 `webpage_reader` 的分段参数） |
| 5 | 本地系统提示词 | `AgentLoopEngine.buildFcSystemPrompt` | ✓ 去掉"别传 page"这种点名不存在参数的说法，改成"没有翻页，调大 limit"；补 scope/ask-vs-search/分段续读 |
| 6 | 在线提示词与使用指南 | `OnlinePromptBuilder.buildKnowledgeStrategySection`（2.5 条）、`AIToolUsageGuide`（10.1 条 + 工具一览表） | ✓ 同一口径 |
| 7 | 工作区指南（模型可用 read 工具读到的速查表，**两份拷贝**） | `AgentWorkspace` "核心工具速查" | ✓ 两行都改（只改一份会漂） |
| 8 | 引导流资产（UI 引导参数） | `assets/tool_guide_flow.json` | ✓ 复核：本来就没写 `page`，`fixedParams` 只带 `action/limit`，不需要动 |
| 9 | 工具自带的参数说明表（`AgentToolView` 与 `AIToolCreatorTool` 会读出来当"工具说明"） | `NetworkSearchTool.getParameterDescriptions()` | ✓ 本轮补修：这张表还留着已删除的 `get_weather`/`location`，且没有 ask/scope/分段参数 —— 与真正下发的 schema 是两套说法 |

失败建议（还有一条容易漏的可见面）：`AgentLoopEngine.getAlternativeTool(tool, action)` 现在按`(工具, action)` 给建议——`read_url` 失败指向"换来源 / 用 `tool_registry` 的 call 调 `webpage_reader`"，
`search` 失败指向"换关键词 / 调大 limit"。不再直接点名可能没注入的工具：native PEG 按本轮 tools 数组
逐字枚举工具名，建议一个模型生成不出来的名字比不建议更糟。

这份清单不是靠人记住的：`src/test/java/com/oilquiz/app/ai/tool/MetasoSearchContractTest.java`
锁住了第 1/2/4/5/6/7/8 条的文本口径与"body 里确实不发 page"（源码扫描 + 反射调用真实提示词构造器），
`MetasoBillingAndDiscoveryTest` 锁住计费与执行引擎侧（不自动重试、按次不重跑、发现通道继承申报超时、
数据与描述都不裁），`LocalThinToolsTest` 锁住工具描述与 schema 的其他不变量。
新增参数时先补这几条测试，再改文案。

同一条测试还锁三句**必须出现在每一处可见面**的话（少一处就会漂）：
`默认用 ask`（答案通道）、`按次 … 与条数无关`（计费口径，防止模型"多看几条就多搜几次"）、
`慢不是失败 / 慢不等于失败`（防止把 ask 的正常耗时当故障去重试）。

## 六、参考实现（不要闭门造车的依据）

- `meta-sota/metaso-sdk`（官方 SDK 线）：base `https://metaso.cn/api/open`，搜索是 `POST /search/v2`
  + `Query{question,lang,sessionId,stream,...}`，返回 answer/references/balance —— 这是我们 `ask` 走的那条。
- `HundunOnline/mcp-metaso`（社区 MCP）：base `https://metaso.cn/api/v1`，
  `SCOPE_RESULT_MAPPING = {webpage: webpages, document: documents, scholar: scholars, image: images, video: videos, podcast: podcasts}`，
  `size` 传字符串，reader 只传 `{url}` + `Accept: text/plain`，**没有 page**。

搜索结果的字段名、`size` 类型、reader 的最小 body 都取自这两处，并用真机响应复核后再落地。

## 七、复现与验证姿势

```bash
# 通道已在 SharedPreferences 里持久化打开（run-as 可读回 token）
adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_EXEC \
  -n com.oilquiz.app/.infra.AgentDebugBridge \
  --es token <token> --es engine local --ei max_tokens 2048 \
  --es prompt "调用network_search工具，action填search，query填大语言模型综述，scope填scholar，size填3。汇报条数。"
```

- prompt 里**不要出现 ASCII 空格**：adb 会把命令交给设备 shell 二次切词，带空格的句子会被截断成
  前 14 个字（踩过两次）。
- 看这三行日志定性：`Metaso Search Response Code`（HTTP）、`Parsed N search results`（解析）、
  `Tool network_search OK|FAIL`（引擎判定）。
- 200 但 0 条时，`Metaso empty-results body(...)` 会打出响应前缀，按它核对字段名，别猜。

## 八、已知边界（别当已解决）

- 额度**按次**扣（一次调用 3 积分，与条数无关）。`testApiKey`（"测试连接"）发的是一次真实搜索，
  因此也扣 3 积分 —— `size=1` 只是让响应最小，**不省钱**；UI 需把这点告知用户。
- 内置共享 key 可能过期/被限流；用户配了 key 才不走它。日志会写命中哪条来源。
- `ask`（open search v2）只有 `question/model` 一类参数，**没有** scope/size/摘要开关；要 scope 就得用 `search`。
- `ask` 慢是常态（几十秒）。判定只看码与状态码，不看耗时；本地执行路径的等待时长由工具申报（见 4.2）。
- 本机（PC）直连 `api.metaso.cn` 不通，一切结论以手机侧为准。
- **还有一条隐藏的按次消耗**：在线侧"联网搜索"勾选后，`OnlineInferenceService.applySearchToPrompt`
  会在模型回答**之前**先调一次 `NetworkSearchTool.preSearch(...)` 把结果拼进 prompt
  （`docs` 里也叫"自有工具优先"）。这条不走工具循环、不受"按次不重试"约束，
  勾选状态下每条需要联网的请求都固定烧一次额度。要不要保留预搜索、或改成只在意图明确要实时信息时触发，
  属产品口径，留给用户拍板。
