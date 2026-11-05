# 空 catch 规模台账（2026-11-06）

这是"清理死代码"顺带出来的第二条账。起因是老归档分支里那笔"修 21 处空 catch"，我想知道它还剩多少意义——先把现状测准，再决定动不动手。**本文档只做规模与分档，没有改任何生产代码。**

## 0. 规模判定先给

不挡开发、不需要重写。真·空 catch 体 968 处，但**分布高度集中**：`app/ai` 一个模块 661 处（68%），前 10 个文件就占 291 处（30%）。值得动手的是启发式判出的 **217 处高危**里的一部分，而其中真正第一批该改的约 15 处（工具执行与结果回传路径）。分档是启发式，需要人确认，别当判据用。

## 1. 口径与工具

```bash
python docs/scripts/empty_catch_audit.py          # 汇总 + 分档样本
python docs/scripts/empty_catch_audit.py --json   # 全量 JSON（改一处跑一次，比对哪几处消失/新增）
```

判据：跨行状态机（注释与字符串/字符字面量整体屏蔽）找 `catch (` → 配平括号拿头 → 第一个代码层 `{` → 花括号配平拿体，再按体内容分五类。**语法感知的结果与之前的粗正则不同**：清理死代码那轮我在对话里给过"1007 处 / 243 文件"，那是粗口径（把只有注释的空体也算进去，同时漏掉跨行与带括号参数的形态），以本文件的 968 + 139 为准；`docs/dead-code-triage-2026-11-06.md` 里没有写入那个数。

分类结果（`src/main`，共 4477 个 catch）：

| 类别 | 数量 | 含义 |
| --- | --- | --- |
| 真·空体 | **968** | 里面一个字符的代码都没有 |
| 只有注释的空体 | 139 | 形如 `catch (X e) { // 忽略 }`，多数是有意为之 |
| 只打日志 | 953 | 已经留了痕迹，不在本轮问题范围 |
| 只有赋值 | 70 | 例如置 null / 记 flag |
| 有恢复动作 | 2347 | 正常兜底 |

## 2. 高危/中危/低危怎么分的

对 968 处真·空体，按所在方法名与文件路径打标签（正则关键词，不是数据流分析）：

- **高危 217**：方法/文件涉及写、持久化、执行、删除、导入导出、恢复、安装、清理（`save|write|insert|update|delete|exec|download|install|persist|commit|flush|copy|move|extract|remove|restore|import|export|create|rename|clear|purge` 等）——吞掉的是一次**有后果**的操作；
- **低危 128**：方法名属 UI 测量/回收/动画/格式化（`onDraw|measure|layout|release|destroy|close|hide|show|format|dp|toString|scroll|animate|click|touch` 等）——吞掉通常只是视觉降级；
- **中危 623**：其余，需要逐个看。

另一个更有信号量的切法是**变量名**：968 处里有 **901 处**变量名是 `ignored`/`ignore`/`ignored2`/`ignored0`（单 `ignored` 就 853 处）——那是作者主动声明"我知道我在吞"；而 **63 处拿着异常对象却什么都不做**（`e` 60 处、`th` 3 处），这类才是"写的时候没想吞、后来变成静默失败"的典型，建议优先随批 1/批 2 一起清（要么记日志，要么改名成 `ignored` 并补一句原因）。

捕获类型：`Throwable` 412、`Exception` 489、具体异常 67——`catch (Throwable) {}` 那 412 处意味着连 `Error`（OOM、StackOverflow）都吞，风险最大的一批应当先出自带 `Throwable` 的高危项。

## 3. 分布

模块（前 12）：`app/ai` 661、`app/ui` 124、`app/util` 38、`app/database` 27、`WebViewActivity` 23、`app/browser` 21、`SmartQuizApplication` 20、`app/theme` 17、`app/weather` 11、`app/webview` 11、`app/infra` 9、`app/model` 3。

文件（前 20，数字是真空体）：`ai/python/PythonToolManager.java` 66、`ui/activity/AIChatActivity.java` 52、`ai/python/NativeLayoutRenderer.java` 32、`database/AppDatabase.java` 27、`WebViewActivity.java` 23、`ai/tool/SystemResourceTool.java` 22、`SmartQuizApplication.java` 20、`ai/chat/status/ServiceStatusManager.java` 20、`ai/chat/media/AudioPlaybackManager.java` 16、`ai/agent/ToolResultInterpreter.java` 13、`ai/importing/v2/ImportMain.java` 12、`ai/engine/NpuEngineRouter.java` 11、`ai/gpu/GpuCapabilityDetector.java` 11、`ai/chat/component/HtmlCardView.java` 10、`ai/performance/PerformanceDashboardFragment.java` 10、`ai/service/AIService.java` 10、`ai/speech/TTSService.java` 10、`ui/activity/LogsActivity.java` 10、`ui/widget/WeatherBannerView.java` 10、`ai/tool/DatabaseTool.java` 9。

## 4. 高危样本（前 30，`file:line 方法() 捕获类型`）

```
app/MainActivity.java:81  onCreate()  catch Throwable
app/SimpleWebViewActivity.java:217  extractHtml()  catch Exception
app/SmartQuizApplication.java:151|156|160|172|179|184|199|209|212|224|231|238|245  onCreate()  catch Throwable   (13 处)
app/WebViewActivity.java:824  copyText()  catch Throwable
ai/agent/ToolExecutionOrchestrator.java:114  executeToolWithRecovery()  catch Exception
ai/agent/online/OnlineAgentEngine.java:472  execute()  catch Throwable
ai/agent/online/OnlineAgentEngine.java:2040  extractToolRegistryTarget()  catch Exception
ai/agent/online/OnlineAgentEngine.java:3258  deleteHistoryFile()  catch Exception
ai/agent/online/OnlineToolManager.java:599  executeTool()  catch Exception
ai/agent/online/mcp/McpManager.java:2006  removeRegisteredTools()  catch Exception
ai/agent/software/engine/UiInteractor.java:66|72  extractComponentId()  catch Throwable / Exception
ai/agent/software/engine/UiInteractor.java:279  execute()  catch Throwable
ai/bridge/ModelExecutionBridge.java:389|391|397  getLocalContextBudgetTokens()  catch Throwable   (3 处)
ai/bridge/ModelExecutionBridge.java:479  rebuildChatJsonHistoryFromExternal()  catch Exception
ai/bridge/ModelExecutionBridge.java:511  putLocalSampling()  catch Throwable
```

为什么这几组值得先看（不是"风格问题"，是它们**吞掉的正是你排查问题时想看的那一层**）：

- `OnlineToolManager.executeTool` / `ToolExecutionOrchestrator.executeToolWithRecovery` / `UiInteractor.execute` / `OnlineAgentEngine.execute`：工具执行路径上的空 catch，与你们那条红线"工具失败不得报成功"直接相邻——预检复用 `executeTool` 时尤其容易踩。
- `ModelExecutionBridge.getLocalContextBudgetTokens` 三连 `catch (Throwable)`：本地上下文预算取不到会**静默退回默认值**，表现就是"改了上下文没反应"，而这正是历史上排查过的那类症状。
- `OnlineAgentEngine.deleteHistoryFile`：删除失败被吞，与"删除必带 bak/tmp/round"的账对不上，落盘形状可能只剩一半。
- `SmartQuizApplication.onCreate` 13 处 `catch (Throwable)`：每一项都是一次初始化被静默放弃，启动后到底哪些子系统没起来，目前没有任何一处能看到。
- `AppDatabase` 27 处：数据库迁移/种子写入类失败被吞，等于允许"表结构与代码不一致"悄悄存在。

## 5. 建议的处置顺序（等你点头再动，一批一提交一验证）

1. **批 1｜工具执行与结果回传**（约 15 处：`OnlineToolManager.executeTool`、`ToolExecutionOrchestrator`、`UiInteractor`、`OnlineAgentEngine.execute`、`ToolResultInterpreter` 里的真空体）：失败要么进结果对象的 error 字段，要么 `AILogger` W 级 + AUDIT 一行，禁止静默。这批收益最直接，因为它改变的是"能不能看出坏了"。
2. **批 2｜持久化与删除**（约 20 处：`deleteHistoryFile`、`AppDatabase` 27 处里的写路径、`ImportMain`、`McpManager.removeRegisteredTools`）：至少留一行审计日志，让"我以为删了/写了"可核。
3. **批 3｜启动初始化清单**（`SmartQuizApplication.onCreate` 那 13 处）：不要逐处打日志（会刷 `ai_logs`），改成收集成一份"本次启动未成功的子系统"列表，一行汇总 + 有异常才 W 级。这与你们"日志链路先证边界且默认精简"的口径一致。
4. **预算与采样**（`ModelExecutionBridge` 那几处）：吞掉即退默认值的地方，建议把"用了默认值"本身变成可见信息（状态面板/日志一行），不是加 try-catch。
5. **余下 623 中危 + 低危 128**：不改行为。只把 **63 处拿着 `e`/`th` 却什么都不做**的变量改名成 `ignored`（并在确属刻意时补一句原因注释），让下一轮扫描能自动把它们降级；纯风格性的"补齐注释"不做，那是噪声。

明确不做的事：不批量塞 `Log.d`（会把日志中心淹掉）；不改任何 catch 的语义去迎合某个统计数（统计是给人看的，不是 KPI）；一次改一大批而中间不编译不跑测试。每批改完跑全量基线（当前 1736 tests / 1 skipped / 0 failures）并核对 `empty_catch_audit.py` 的计数变化。

## 6. 局限（别把这些数字当判据）

分档是**关键词启发式**：方法名回扫最多 250 行、匿名内部类里的 catch 可能归到外层方法；`WRITE_HINT` 也会把文件路径里带 "create/update" 的无辜命中算进高危。所以第 2、4 节的名单是**候选队列**，不是结论；每处要不要改，得看它吞掉的那次操作有没有后果。`--json` 输出可以逐条复核，也是下一轮的基线（改完比对哪条消失了）。
## 7. 当轮改动记录（本文档写完后立刻动手的那批）

按"能修的就不要用 catch"改了一条批次的，改后重跑扫描器：**真·空体 968 → 920**，catch 总数 4477 → 4446（少掉 31 层 try），高危 217 → 191；`1736 tests / 1 skipped / 0 failures` 不变绿。分类如下：

| 改法 | 位置 | 说明 |
| --- | --- | --- |
| **删掉 try/catch**（调用本身抛不出来） | `OnlineToolManager.loadCache` 逐条恢复、`executeTool` 的 `getToolMeta`、`UiInteractor.extractComponentId` 的 Map 分支与 `progress()`、`ToolResultInterpreter` 4 个模板循环 | 全是 `opt*`/判型后取值/纯字段读；留着 catch 只会把"少了一条""没进度条"读成正常 |
| **改成不依赖异常的控制流** | `ToolResultInterpreter` 新增 `intOr` / `firstSignedIntOr` / `looksNumeric`（包级可见，便于测试） | 以前是 `parseInt` + 空 catch 当"这字段不是数字"用；AQI/温度/难度分布/计算结果五处改为显式判形，非数字与解析崩溃不再是同一种静默 |
| **删掉空 catch，改用返回值判断** | `OnlineAgentEngine.deleteHistoryFile` | 关键一条：`File.delete()` 返回 false 而不是抛，原代码把"没删掉"和"抛异常"一起吞；现在逐个检查返回值，残留 `.bak`/`.tmp` 会以 `[AUDIT]` 报出——漏删等于下次装载把"已清空"的历史复活 |
| **保留 catch，但必须可见** | `UiInteractor.getResult`/`execute` 工作线程、`OnlineAgentEngine` 的 tmp 清理、thinkingChain 兜底、`getToolIds` 注册表读取、`ModelExecutionBridge` 历史重建与采样兜底 | 这些点吞掉的分别是"工具崩了""清理没做""思考内容整段丢""只剩别名表""一条历史没进上下文"，都会伪装成正常降级，故一律补 W |
| **保留 catch 并注明理由** | `OnlineToolManager.tryRelaxedParse`、`OnlineAgentEngine.extractToolRegistryTarget`、`CACHE-PROBE` 观测块、`isAnyModelAvailable` 两个探针 | org.json/Gson 没有"不抛异常"的解析入口；这些位置"解析不动"就是正常返回值，不是被藏的故障 |
| **结构改造** | `SmartQuizApplication.onCreate` 的 13 处 + 一个后台线程点 | 改成 `boot("子系统名", …)` 收集清单，`onCreate` 末尾**一行**汇总 W（不是 13 条日志）；上报后晚到的异步失败逐条打，免得丢。清单标签是人工写的短句，不是从注释截的 |
| **顺手纠正的口径** | 同上 | org.json 的 `put` 和 `putOpt` **都**声明 checked JSONException（我先按"putOpt 不抛"改，编译直接打回），所以这几处只能保留 try；已把注释里的理由写成"为什么这层去不掉"，不留假声明 |
| **没动** | `ToolExecutionOrchestrator`（该类全仓零引用，是抽取一半的死副本） | 我先在里面加了日志，随即回滚：改一份没人跑的拷贝正是我这轮在消灭的东西。它归入第 7 节整类候选，跟着那批一起处置 |

未验：`boot()` 的清单只在"真有子系统失败"时才会输出，本轮没有构造失败；`SmartQuizApplication` 的改动需要真机开机一次确认没有把启动顺序改坏（尤其 NPU 致命信号消费必须仍排在后端偏好之前——这条顺序在原注释里写明，改写时保留了原语句顺序）。

## 8. 批 2a 改动记录（database 包：迁移不再静默）

改的是 `AppDatabase` 的 32 处（全仓最多空 catch 的文件之一）+ 顺手核了同包的 `DatabaseUpgradeManager`。改后重跑扫描器，四个数都对得上（用 HEAD 的 worktree 跑同一个脚本比对，不是拿上一轮的记录减）：

| 指标 | 批 2a 前 | 批 2a 后 | 差 |
| --- | --- | --- | --- |
| catch 总数（src/main） | 4446 | 4418 | -28（删 32 / 新加 1 / 另 3 处保留但改成有日志） |
| 真·空体 | 920 | 893 | -27 |
| 只写注释的空体 | 146 | 141 | -5 |
| 只写日志 | 962 | 965 | +3 |
| 高危 / 中危 / 低危 | 191 / 602 / 127 | 191 / **575** / 127 | 中危 -27 |

`grep -c "catch ("` 逐文件复核：`AppDatabase.java` 34 → 6（剩下的是 InterruptedException、建库失败、三处 close、一处 JSON 解析失败，全部带日志）；`DatabaseUpgradeManager.java` 7 → 7（它那 7 处本来就在 Log.e，问题不在吞异常，见下）。

**分档在这批上失效，值得记一笔**：32 处没有一处被算进高危，因为方法名都叫 `migrate()`，`WRITE_HINT` 关键词回扫认不出"这是改全库表结构"。实际后果是整库升级失败或表少一列，比名单里多数高危条目严重得多。第 6 节那句"分档是候选队列不是结论"在这里被证实了一次——**下一轮挑文件别只按高危名单挑，`database/`、`*Migrat*`、`*Dao`、`*Repository` 这类名字要单独捞一遍**。

改法与理由（`MigrationSchema` 是新增的公共探针，`addColumn` 先 `PRAGMA table_info` 判存在再 ALTER，异常一律不接）：

| 改法 | 位置 | 为什么 |
| --- | --- | --- |
| **异常当控制流 → 先查后改** | v19→v20 的 19 条、v20→v21 的 5 条、v21→v22 的 8 条 ALTER，共 32 层 try 里的一大部分 | 旧注释理由是"字段可能已存在"，但这层 catch 同时吞掉真故障。列没加上照样往下走，最后由 Room 迁移后的 schema 校验报 "Migration didn't work out"——结局同为失败，现场却全丢了。SQL 文案逐字未动 |
| **删掉永不成立的 DDL** | v17→v18 整条（2 处）；v18→v19 末尾 2 条 `CREATE INDEX ... ON questions(...)` | 实体表名是 `question`（单数，`Question.java:13`），`questions` 这个表从来没有过。v17→v18 那两条被 catch 吞着，所以它 8 个版本一直是静默 no-op；v18→v19 这两条**没包 catch**，任何低于 v19 的库升上来都在这里抛 SQLException、整条迁移失败。等价索引本就由 v19→v20 在正确的表列上建 |
| **刻意不"顺手修对"** | v17→v18 的 `ai_enhanced` / `ai_tags` | 全仓没有任何 Entity 字段或读写方引用它们（只有这两句 DDL 和 DatabaseUpgradeManager 的同款 DDL 提到）。真把列补上，Room 按实体算出的 schema 就和库里对不上，反而把本来能升级的旧库顶成"数据库不可用"。已改成显式空迁移 + 注释，要做 AI 字段得先加实体再走新版本 |
| **外键指向不存在的表** | `question_images` 的 `REFERENCES questions(id)`（两处：AppDatabase v18→v19、DatabaseUpgradeManager `migrateToV19`） | SQLite 建表时不校验 FK 目标，所以这两张表建出来了；Room 开着 foreign_keys，一旦真往里插就报 "no such table: main.questions"。改成 `question(id)`。**已经建过库的设备不会被改写**（`CREATE TABLE IF NOT EXISTS`），只是新库从此正确 |
| **数据拼接进 SQL** | v21→v22 摊平 extraOptions：`"UPDATE question SET " + key + " = ?"` | `key` 直接来自存量 JSON 的键名，拼进去什么就执行什么。改成先取 `PRAGMA table_info` 的列名当白名单，对不上的 key 记 W 跳过 |
| **Cursor 泄漏** | 同上：`cursor.close()` 原来写在 try 的最后一行 | 循环里抛异常就关不掉。改 try/finally |
| **空 catch 包住整段 → 显式判存在 + 计数上报** | 同上：外层 `catch (Exception ignore) {}`、内层 `catch (Exception ignore) {}` | 外层把"这库没有 extraOptions 列"（走过 DatabaseUpgradeManager 那条旧升级路）和"读着读着出错"当同一件事。现在缺列就明确跳过并留一条 W；JSON 解析失败与脏 key 各自计数、带样例 id 报，最后一句汇总——**有损失就让人看得见损失**，别把"迁移成功"报成数据全的 |
| **失败没被吞，但报成成功** | `DatabaseUpgradeManager.migrateToVersion` 的 `default:` | 原来是 `Log.w("未知目标版本，跳过") + break`，方法照样 `return true`，于是 `upgrade()` 把 `user_version` 抬到 21..25 却在屏幕上报"升级成功"——Room 那边 21+ 的迁移一条没跑，下次打开就是 schema 不符。改成 `return false`，走它本来就有的 `notifyFailed` + 回滚路径 |
| **close 失败静默** | `getDatabase` 两处、`destroyInstance` 一处 | 这三处**必须**留 catch（关不上也不能挡住丢弃旧实例，否则下次调用还在 `isOpen()` 上误判）。改成 W，并写清"这条连接可能还占着文件"——真出现 "database is locked" 时能对回这次失败 |
| **`System.out.println` 当日志用** | `getDatabase` 的成功/失败两行 | Android 里 System.out 不进 logcat 也不落 ai_logs；初始化失败改成 `AILogger.e`（这条一旦丢，调用方只是各自拿到 null，现场只剩"某些功能没数据"） |
| **新增测试** | `MigrationSchemaTest`（10 条，Robolectric + 真 SQLite） | 这个类的风险全在 SQL 文案上：`PRAGMA table_info(表名)` 写错不会编译报错，只会在用户升级那一刻炸。用例里既有"补完真能 INSERT 读回"，也有"已存在时必须什么都没执行"（再发一条冲突定义的 ALTER 就是 duplicate column），还有"表不存在必须抛"。10 条 0 skipped，其中一条先失败过一次：Robolectric 的异常消息是 "Cannot prepare statement, base error code: 1"，不含表名，所以断言只钉"抛了"不钉文案 |

### 这批查到但**没动**的三件事，等你拍板

1. **`fallbackToDestructiveMigration()` + 迁移表只从 v17 起**（`AppDatabase.java:99`，MIGRATIONS 数组首元素是 17→18）。任何 v≤16 的库走到 Room 这里没有迁移路径，会被**整库 drop 重建**：题目、成绩、错题、对话历史全没，且没有任何提示。要改就得先备份再告知，属于产品决定，我没自作主张动——把它改成"报错不重建"等于让这批设备直接起不来。
2. **`ocr_history` / `question_images` / `ai_usage_log` 三张表零消费者**：只有 DDL，全仓没有任何读写。它们不是死代码那种"没人调用"，是"建了张空表"。删的话要连 DatabaseUpgradeManager 里那份一起，并且存量库里表还在。归入 `docs/dead-code-triage-2026-11-06.md` 第 7 节那批整类候选一起处置。
3. **两套迁移引擎并存**：Room 的 `MIGRATIONS`（权威，17→25）和 `DatabaseUpgradeManager` + `DatabaseVersionChecker`（只覆盖到 20，且 `migrateToV20` 是"预留"空方法）。入口是 数据库管理 → 升级数据库（`DatabaseManagementActivity.java:368`）。实际上大概率碰不到坏路径：`upgrade()` 第一步就 `AppDatabase.getDatabase()` + `getWritableDatabase()`，Room 已经先把库升到 25 了，于是 `needUpgrade=false` 直接报"已是最新版本"。但这两套并存本身就是刚才那条 `default:` 假成功的土壤，要不要把旧的那套摘掉（保留备份/回滚）由你定。

未验：**v17→v25 这条升级链本身没跑过**。手上没有低于 25 的库，`exportSchema = false` 也让 Room 的 `MigrationTestHelper` 用不了（它要 schema JSON）。本轮能保证的是：ALTER 的 SQL 文案逐字未动、新增的 `MigrationSchema` 有真 SQLite 的 10 条用例、以及那条一定会抛异常的旧 DDL 已被删掉。真要把升级链钉死，得造一个 v20 的库 dump 放进 androidTest——那是另一件事，需要设备。

## 9. 批 2b 改动记录（对话历史/原子写/导入/下载/公共存储）

五个文件，都是"写完就算成了"的那类。改后同一脚本重跑（"前"列取 `git worktree` 的 HEAD 副本，不是记录相减）：

| 指标 | 批 2b 前 | 批 2b 后 | 差 |
| --- | --- | --- | --- |
| catch 总数（src/main） | 4418 | 4410 | -8 |
| 真·空体 | 893 | 863 | -30 |
| 只写注释的空体 | 141 | 140 | -1 |
| 只写日志 | 965 | 971 | +6 |
| 只赋值 | 70 | 71 | +1 |
| 有恢复动作 | 2349 | 2365 | +16 |
| 高危 / 中危 / 低危 | 191 / 575 / 127 | **165** / 571 / 127 | 高危 -26 |

31 处离开空体/注释池 = 8 处整层删掉 + 23 处改成"有日志/有返回值判断"。这批终于落到高危名单上了（上一批 32 处一条都没算高危）。逐文件（空体+注释）：`AtomicFileText` 2→1、`ChatHistoryManager` 7→0、`ImportMain` 12→1、`ModelDownloadManager` 7→0、`PublicStorageWriter` 6→1；剩下的 3 条是刻意保留的注释型（见下）。

**基线：`1746 tests / 0 failures / 0 errors / 1 skipped` 不变绿。这批没加新测试**——AtomicFileText 已有 5 条、ChatHistoryManager 有 3 个测试类覆盖正常路径，但下面这几条新行为要构造磁盘故障才能验，本轮没构造，属于未验项。

| 改法 | 位置 | 为什么 |
| --- | --- | --- |
| **假成功，改成真判据** | `PublicStorageWriter.delete` | MediaStore 那条只要**没抛异常**就 `any = true`，而 `ContentResolver.delete()` 是返回行数的，没匹配到就返回 0。于是"文件名对不上、一行都没删"被报成删除成功；调用方（`TermuxEnvInstaller` 清结果文件、卸 env）据此以为清干净了，残留文件下一个任务还会被当成新结果读。现在按 `fileDeleted || rows>0` 判，两处都没动就 W |
| **失败被报成正常复用** | `ImportMain` 断点恢复（687 原处） | `jsonToMap` 解析失败返回**空表**，代码照样 `mapping = 空表` 并打印"断点恢复：复用已推理映射，从第 N 行续导"——那是拿空映射套每一行，表现为"这个文件一道题都没有"，且从第 N 行起连重来的机会都没有。现在先解出映射、空表就明确放弃续导（`resumeParse=false`，重新做映射并从第 0 行导入），走的是"缓存映射与表头不一致就放弃缓存"同一判据 |
| **异常当控制流 → 显式判形 + 计数** | `ImportMain.applyFillBatch` 的 difficulty | `Integer.parseInt` + 空 catch：LLM 回"中等"/"easy"（正常输入）和被吞掉的真故障走同一条路，整列难度静默不填而界面显示"填充完成"。现在 `sv.matches("[-+]?\\d{1,9}")`（源码两个反斜杠，运行时是 `\d`）判过再 parse（判过之后 parse 抛不出来），非数字单独计数、批末一条汇总 |
| **逐行吞掉 → 汇总可见** | 同方法外层 `catch (Exception ignored)` | 一行的填充结果整份作废。原来"批量跑完一题没填"和"全部填好"在界面上一样。现在 rowErrors + 第一条原因，批末一条 W（不是 N 条） |
| **备份拿不到不再演成安全** | `AtomicFileText.write` / `copyTo` | rename 到 .bak 失败后退化成复制，复制也失败就**什么兜底都没有**，紧接着照样覆盖主文件。原来 copyTo 返回 void、异常空 catch。现在返回 boolean，拿不到备份就 W 明说"这次覆盖没有回退点"。类注释里两处承诺也改了：备份与主 rename 之间主文件是短暂不存在的（"不丢全部"成立，"旧文件保持原样"不成立） |
| **"读不到"与"没有"分开** | `AtomicFileText.readText`、`PublicStorageWriter.size` | 两个都把 IOException 塌成 null/0，而调用方（含 `exists()`＝size>0、TermuxEnvInstaller 的"已导出"、BackupManager 的备份检查）按"没有"处理。返回值形状本轮不动（一动要拖三个调用方），但路径 + 原因必须留 W，两条路都失败时合并成一条 |
| **空 catch 里其实没异常可抛** | `ChatHistoryManager` 的 trimHistoryTrash / deleteSessionHistoryFiles / deleteTrashedHistoryForSession / parseTrashTimestamp | listFiles 失败返回 null、`File.delete()` 失败返回 false、getName 不抛——catch 兜的从来不是异常，而是"没删掉"这件事，于是它把真正需要知道的东西一起吃掉。现在按返回值/存在性判断，`deleteRecursively` 返回残留文件数，清空回收站走 AUDIT，`parseTrashTimestamp` 改 `parse(String, ParsePosition)`（"格式不对"本来就是正常分支，不该用 checked ParseException 撑着那层 try） |
| **会导致误删的吞异常** | `ChatHistoryManager.sweepOrphanHistoryFiles` | 这批里后果最重的一条：读回收站包在 `catch (Throwable ignored)` 里，读不出就当"回收站是空的"，`known` 少一项就多一个还能恢复的会话被当孤儿删掉历史。现在目录不存在才继续，读不出直接不扫并 W（宁可漏扫，不可误删） |
| **下载侧的"成功但索引没记上"** | `ModelDownloadManager` 的 onComplete / writeSha256Sidecar / isModelDownloaded / getFileNameFromUrl / finally 三条 close | 记不上索引 = 以后改名自愈认不出这个文件，界面上就是"明明下过还要再下一个 4GB"；sidecar 没写成 = 以后每次校验整包重算；URL 解不出文件名退回 `model.gguf` = sidecar 与索引都对不上号。三处 finally 关流合并成 `closeQuietly(closeable, 说明)`，关不上要说清关的是什么 |
| **保留 catch 并注明理由** | `AtomicFileText` 的 fsync、`PublicStorageWriter.readTail` 第一条、`ImportMain` 4 处 `JSONObject.put`、`emitLog2` | fsync 有些文件系统不支持（不影响原子性语义）；readTail 第一条是**故意往下走**（换 URI 再读一次，两条都失败由第二条报）；put/putOpt 都声明 checked JSONException（批 1 编译打过我一次，别再凭印象拆），且 key/value 形状都不在它会拒的范围里；emitLog2 兜的是"日志器自己坏了"，改成退到 logcat 而不是吞掉 |

### 这批查到但没动的

- **`resumeParse` 为真但 `bp.mappingJson` 本身为空**：走 else 分支（缓存/推断）却仍从断点行号续导。本轮只处理"有 JSON 但读不出"，因为"空 mappingJson 但缓存命中"是正常组合，要不要一并放弃续导属于导入策略，等你说。
- **`ChatHistoryManager` 与 `AtomicFileText` 各有一套 `readText`**，`deleteRecursively` 在 `DouyinDownloaderTool`、`PipInstallTool`、`SystemResourceTool`、`ChatHistoryManager` 里各有一份私有实现。收敛成一个工具类是重构，不混进"消静默"这批。
- **本轮没加测试**。要钉的三条新行为（回收站读不出→不扫、备份拿不到→仍写但有 W、断点映射读不出→重新导入）都需要构造故障；前两条可用一个假的 listFiles/只读目录做，第三条要喂坏 JSON——按批 2c 之后统一安排，还是现在就补，你说一句就行。

## 10. 批 2c 改动记录（Agent 工具/校验/下载侧）

八个文件：`DatabaseTool`、`ExcelTool`、`BlobDownloadManager`、`BrowserDownloader`、`ImportValidator`、`SqlFileImporter`、`FileUriUtils`、`ModelDownloadActivity`。这批的特点是**结果给模型看**，所以"静默"直接等于"模型以为自己成功了"。

| 指标 | 批 2c 前 | 批 2c 后 | 差 |
| --- | --- | --- | --- |
| catch 总数（src/main） | 4410 | 4392 | -18 |
| 真·空体 | 863 | 829 | -34 |
| 只写注释的空体 | 140 | 138 | -2 |
| 只写日志 | 971 | 979 | +8 |
| 有恢复动作 | 2365 | 2375 | +10 |
| 高危 / 中危 / 低危 | 165 / 571 / 127 | **145** / 557 / 127 | 高危 -20 |

三批累计（基线取批 1 之后那次）：**真·空体 920 → 829，catch 4446 → 4392，高危 191 → 145**。八个文件里现在只剩 3 处注释型空体，都是刻意保留的兜底（`BrowserDownloader.extensionForMime` 的 MimeTypeMap 缺项、`ImportValidator` 防线 1/2 的"这层就是要往下走"）。

| 改法 | 位置 | 为什么 |
| --- | --- | --- |
| **对模型的假成功** | `DatabaseTool.getTableSchema` 的 `COUNT(*)` | 空 catch 让"这条 COUNT 没跑成"和"这张表一行都没有"在结果里长成同一个 `row_count: 0` + `status: success`，模型据此判断"题库是空的"。现在结果里带 `row_count_unknown`/`row_count_error`，并把 Cursor 的 close 挪进 finally |
| **parseInt 异常当分支 → 显式判形 + 回给模型** | `DatabaseTool.addQuestions`(5 处) 与 `bulkImport`(3 处) | `difficulty`/`points`/`timeLimit`/`sortOrder`/`parentId` 各配一份 `try { parseInt } catch (Exception ignored) {}`：模型交 `"difficulty": "中等"` 时字段带默认值入库、工具回"成功添加 N 道"，它下一批还这么写。现在统一走 `setIntField/setLongField`（正则先判形、限位数顺手挡溢出），没生效的字段进 `coerced_fields` 并拼进 message，同时一条 W。两套重复的 setter 块也收敛成同一处 |
| **Workbook/Cursor/Reader 关不上无声** | `ExcelTool` 6 处 `workbook.close()`、`SqlFileImporter` 3 处、`BlobDownloadManager.closeQuietly`、`FileUriUtils` 2 处 | 抽成各自的 `closeWorkbook/closeQuietly` 并在关不上时报出。`BlobDownloadManager` 那条最实在：flush/close 失败＝`.part` 尾部没落盘，之后它会被当完整文件搬进下载目录 |
| **半截缓存文件当成功返回** | `FileUriUtils.copyUriToCacheFile` | `os.close()` 失败还返回路径，下游 OCR/解析拿着截断内容报一个看不出根因的错。现在 close 失败就返回 null（这次选择直接失败），并把原因写进日志——按"假成功比没反应更糟"的口径改 |
| **interrupt 被吃掉** | `ModelDownloadActivity` 两处重试里的 `Thread.sleep` | 空 catch 让"页面已经不要这份结果了"继续睡完再发一次请求，回调落回已销毁的视图。现在交还 interrupt 并停止重试 |
| **异常当防御** | `ImportValidator.repairJson` 尾逗号修复 | `String.replaceAll` 每次调用重新编译模式，而模式是写死的字面量——运行期唯一可能的 `PatternSyntaxException` 就是"模式本身写错"，那种情况该在类加载时就炸。改成两个预编译 `Pattern` 常量，那层 try 自然就没东西可接了 |
| **三道防线全失败无现场** | `ImportValidator.parseStructuredOutput` | 防线 1/2 的 catch 是"往下走"，留着并写明；但三道全走完只 `return null`，事后只能看到"导入没结果"。现在报一条带原文前 200 字（压掉换行）的 W——"模型输出了非结构化文本"这类问题只有这一处能留下证据 |
| **下载文件名与落点无声降级** | `BrowserDownloader` 5 处、`ModelDownloadActivity.getFileNameFromDownloadUrl` | 公共 Downloads 放不下就改存 App 专属目录、Content-Disposition 解不出就按 URL 猜、同名避让没跑成就按原名、URL 解码失败就用未解码尾段——每一条都会变成用户看不见的差异（"下载里找不到文件""乱码文件名""下载失败"）。都补了带输入值的原因 |
| **过期缓存清扫无声** | `BlobDownloadManager` 构造里的 `.part` 清扫 | 顺手改成统计"删掉几个/删不掉几个"，删不掉才报；`dir.mkdirs()` 失败也从无声变成一句（否则要等到建临时文件失败才发现） |

未验：这批同样**没加新测试**；`DatabaseTool` 的两个新结果键（`row_count_error`、`coerced_fields`）只由全量单测保证不破坏既有断言。真机上值得点一次的是"用字符串型 difficulty 调 add_questions"——看模型是不是真能收到那句"没按提交值入库"。

## 11. 批 3 改动记录（"拿着 e/th 却什么都没做"这一族清零）

这批的对象不是"空体"本身，而是**变量名自称要用异常、体里却一行没做**那种形态：它比 `ignored` 更可疑，因为 `ignored` 至少承认"我要吞"。扫描开始时 src/main 有 **65 处**，现在 **10 处，且全在 `AILogger2`**（`@Deprecated`，全仓只有它自己和 `AILogger` 提到过它）。

| 指标 | 批 3 前 | 批 3 后 |
| --- | --- | --- |
| catch 总数（src/main） | 4392 | 4392（这批**没删任何 try**，只改名/补日志/补注释） |
| 高危 / 中危 / 低危 | 145 / 557 / 127 | 141 / 553 / 127 |
| 变量名分布里 `e`/`th`/`t` 命名的空体 | 65 | **10**（全在废弃类 AILogger2） |
| 基线 | 1746 tests / 0 failures / 0 errors / 1 skipped | 同 |

处置分三种，逐处判：

| 处置 | 数量 | 位置与理由 |
| --- | --- | --- |
| **改名 + 补日志**（吞掉的是一件有后果的事） | 15 | `AILogger` 两个视觉日志回调（监听者炸了会把整条日志一起吞掉）；`LlamaHelper.isEngineReady`（探测抛异常＝报"未就绪"，可能把已就绪报成没就绪）、`chatStop`/`chatClear`（没调到 native＝这一轮停不下来、KV 没清）；`GpuAdaptiveTuner`（拿不到 profile 就按 5f 猜值调速）；`MessageAttachmentAdapter`（缩略图解不出只剩空白）；`ToolGuideFlowFlag`（开关没写进 prefs，下次启动回默认档）；`AgentDebugActivity.saveTrace`（trace 没落盘＝这轮调试信息丢了）；`ApiConfigActivity`（数不出条目却显示 0 条）；`OnlineInferenceService.preResolveHost`；`NetworkSearchTool` 两处（key 读不到按没配处理、结果卡片没搭起来只回文字）；`OnlineModelConfigDialog`（查不到上下文上限却沿用猜测值） |
| **改名 + 注释/类文档**（读不到就是这一族的答案，不该报） | 40 | `GpuCapabilityDetector` 15 处（sysfs/属性/反射/外部命令四种探测，读不到＝不支持或未知；真机每次启动都会命中若干回，逐条报 W 会把日志中心变成探测清单）；`AILogger` 5 处 org.json `put`（checked 异常去不掉，只少显示几个字段）；`AppLogger` 3 处（"这行不是时间戳格式"是常态）；`McpLoopbackReceiver`（accept 超时是等待窗口的常态）；`PerformanceMonitor` 温度、`AICrashHandler` 的 /proc、`SessionEventPayload.safePut`、`NetworkSearchTool` 响应体不是 JSON、`SystemResourceTool` 包名对上但没装、`AccessibilityHelper` 两处反射、`GarbledTextFixer` 字段可能不存在、`WebViewLifecycleManager` 对已死 WebView 操作、`OCRActivity`/`WeatherDetailActivity`/`WebViewFilePreviewActivity`/`ApiConfigActivity`/`ModelFileSelector` 各一 |
| **顺手修真问题**（与上面两类重叠，不另计数） | 2 处行为 + 1 处命名 | `PerformanceMonitor:121` 采样间隙 `Thread.sleep` 的空 catch 吃掉了 interrupt → 交还标记；`AIServiceStatusActivity` 读 `gpuLayers` 失败仍走 else 分支，界面写死"(CPU模式)"——把"没读到"说成"确定没用 GPU 加速"，改成三态（未知/加速 N 层/CPU 模式）；`McpLoopbackReceiver` 的超时分支明确成 `timeoutTryAgain` 以免下次被当成漏处理 |

命名约定写进了 `GpuCapabilityDetector`/`AppLogger` 的类注释：**变量叫 `probeMissed`/`notThisFormat` 这类名字＝刻意吞掉的探测失败，从"待查信号"里出去；还叫 `e`/`th` 的＝没人看过。** 于是这个扫描口径以后可以继续用（第 8 节那条"定案用编译器当裁判"之外的第二个抓手）。

### 剩下的 10 处怎么处置，等一句话

`AILogger2`（`util/AILogger2.java`，10 处空体）的类注释自己写着 `@deprecated Use AILogger`，而全仓引用只剩两处：它自己和 `AILogger`。也就是说这 10 处的正解不是逐条修，而是**决定这个废弃类切没切干净**：没有活调用方就整包删（连带 10 处），有就把调用点迁到 `AILogger`。按第 4 节的口径（死链默认保留、报账等拍板），我没自己删。

## 12. 批次台账（到本轮为止，接得上的入口）

| 批 | 提交 | 范围 | 关键数字（改后） |
| --- | --- | --- | --- |
| 1 | `db49df32` | 工具执行/上下文/boot 路径 | 真·空体 968→920，catch 4477→4446，高危 217→191 |
| 2a | `0d22eaf1` | `AppDatabase` 迁移 + `MigrationSchema` + `DatabaseUpgradeManager` | 空体+注释 1054→1022（该包清零），新增 10 条 Robolectric 用例 |
| 2b | `3232ae4c` | 原子写/对话历史/导入引擎/模型下载/公共存储 | 真·空体 893→863，高危 191→165 |
| 2c | `f435b21d` | Agent 工具（DatabaseTool/ExcelTool）+ 校验 + 浏览器下载 | 真·空体 863→829，高危 165→145 |
| 3 | `a6e41874` | `e`/`th` 命名的空体一族 | 65→10（全在废弃 AILogger2），高危 145→141 |

现在的全仓状态（`python docs/scripts/empty_catch_audit.py`）：**catch 4392｜真·空体 821｜只写注释 137｜只写日志 983｜只赋值 71｜有恢复 2380｜高危 141／中危 553／低危 127**；基线 **1746 tests / 0 failures / 0 errors / 1 skipped**；APK 已装机并回读校验：`答题宝-debug-2.0.apk` 与设备 `base.apk` 的 md5 同为 `cdad6677a1857449778328a84b3ebbf9`（`adb install -r -g` Success）。

**还没做的**：批 4（中危 553 与低危 127 的按模块扫，判据同前：有副作用的动作失败必须可见，纯展示兜底可保留但要写明为什么吞）。这轮没动它们，一是量大、二是它们绝大多数是 `ignored` 自称的刻意吞掉——收益比前三批低得多。挑文件时记得第 8 节那条教训：**别只按高危名单挑**，`*Migrat*`/`*Dao*`/`*Repository*`/`*Writer*`/`*Store*` 这类名字要单独捞一遍，本批里后果最重的两条（回收站读不出就误删历史、断点映射读不出还报"复用成功"）都不在高危名单上。

**等你拍板的三件**（第 8、10、11 节各一处）：`fallbackToDestructiveMigration` 对 v≤16 旧库的整库重建无提示；三张零消费者的表（`ocr_history`/`question_images`/`ai_usage_log`）+ 两套并存的迁移引擎；`AILogger2` 这个 @Deprecated 类（只剩 10 处空体，没活调用方）是整包删还是把调用点迁完。

## 13. 批 4a（高危族起头：交互决策卡 + 联网工具）

从剩下的高危 141 里先切了后果最直接的两个文件，13 处：

| 文件 | 处数 | 吞掉的是什么 |
| --- | --- | --- |
| `AgentInteractiveDecisionHandler` | 6 | 交互决策卡的 payload 少键：表头清单、`missingCount`、成/重/败统计、按字段分组的缺失数、payload 合并与转 Map。这些卡是**用户点确认的依据**——少一项就是让用户在信息不全的情况下批导入，而且界面看起来一切正常。全部改成 `*Dropped/*Missed` + `AILogger.w` |
| `JsExecuteTool` | 7 | `timeout` 参数不是数字就按默认档（模型要的秒数没生效）；cookie/登录态取不到就**不带登录态**发请求（拿回来可能是登录页而不是数据，模型却当正常内容用）；请求头没设上；`Set-Cookie` 没接住。6 处补 W，1 处（对已死 WebView 的 stopLoading/destroy）只改名——那条按浏览器红线就该吞 |

基线 1746/0/0/1 skipped 不变；装机件 md5 `69bc0bc0772d9dfae9e267cf7bffabdc`（与设备 `base.apk` 回读一致）。

**高危还剩 128 处**，按文件集中度排：`AIChatActivity` 14、`SystemUIComponentTool` 9、`ServiceStatusManager` 7、`HtmlCardView` 6、`TermuxEnvInstaller` 6、`SystemResourceTool` 5，其余零散。下一批建议就按这个顺序继续（同样是"用户/模型据此做决定"的字段与动作），而不是去扫中危那 553 处——中危绝大多数是 `ignored` 自称的刻意吞掉，先把高危清零收益更大。

## 14. 三件待拍板项已定并落地（本轮）

1. **v≤16 的旧库整库重建不再无声**：新增 `LegacyDatabaseGuard`，在 Room 打开前用只读方式读
   `PRAGMA user_version`；低于 `LOWEST_MIGRATABLE_VERSION=17` 就先把 `.db` 连 `-wal`/`-shm`
   一起复制到 `filesDir/db_rebuild_backup/<时间>/`（WAL 模式下只拷主文件等于拷一份旧的），
   顺手 `SELECT COUNT(*) FROM question` 报"里面有 N 道题"，然后 AUDIT 一条 + 一次性 toast 说清
   备份在哪、旧数据不会自动回来。`fallbackToDestructiveMigration()` **保留**——改成报错等于让
   这批设备起不来；现在的口径是"重建照做，但先留一份可恢复的原件并告诉用户"。
   `DatabaseVersionChecker.MIN_SUPPORTED_VERSION` 从写死的 1 改为引用同一个常量：
   原来它宣称"v5 也能强制升到 25"，而那条路其实是重建，两处口径以后只能一起改。
2. **三张零消费者的表不再建**：`AppDatabase` 的 v18→v19 与 `DatabaseUpgradeManager.migrateToV19`
   里的 `ocr_history`/`question_images`/`ai_usage_log` DDL 全删（第 2 处随第 3 条整段删除），
   18→v19 从此是一条**有意的空迁移**，只剩版本号。**存量库里的表保持原样**：为空表引入一次
   版本迁移不值得，且 Room 的校验看不见这些非实体表。
3. **旧的那套迁移引擎摘掉，只留 Room 一个权威**：`DatabaseUpgradeManager` 的
   `migrateToVersion`/`migrateToV19`/`migrateToV20` 整段删除（3971 字符），`upgrade()` 的第 5 步
   改为"由 Room 执行迁移 + 验证实际版本"，版本对不上就抛异常走它已有的 `notifyFailed` + 回滚。
   备份/回滚/列备份/清理这些真有用的能力全部保留，`数据库管理 → 升级数据库` 按钮的去处不变——
   它现在是"备份好再让 Room 升，并核对结果"，不再自己写 `user_version`。上一轮那条
   `default:` 假成功的土壤（两套引擎并存）就是这么消掉的。

未验：`LegacyDatabaseGuard` 这条保护路径**没跑过**——手上没有 v<17 的库，本机设备已是 v25，
所以"备份+提示"只有代码与日志形状，实际效果要等一个真旧库（或造一个 v16 的库 dump）才算数。
`upgrade()` 改造后同样只在"Room 没升到位"时才会走异常分支，正常态是 needUpgrade=false 直接报
"已是最新版本"，与原行为一致。基线 1746/0/0/1 skipped；装机件 md5 `9a5e6f18a3ce03c08be79e828aa03944`。

## 15. 高危清完 + AILogger2 整类删除

**高危 141 → 26**（真·空体 821 → 700，catch 4392 → 4386）。做法：115 处补 `AILogger.w`（类名 · 说明 · 方法名 · 异常），20 处形态不敢自动插语句的只改名 `stepFailed`，`HtmlCardView` 对已死 WebView 的 `goBack` 按浏览器红线保持静默只改名。剩下 26 处＝未插日志的那 20 处 + 6 处边角，性质都是"已改名、有原因、只是没打日志"，不是没人看过。

**踩坑两次，都当场纠正**（值得记，因为这是第 8 节同一条判据的回放）：第一版批量脚本对不是 `catch (X ignored) {}` 的形态也做截尾替换，11 个文件括号失衡，编译报"有 catch 没有 try"。用**HEAD 与工作树的 `{`/`}` 差必须一致**这条结构判据把 11 个文件全量回退，再用只做安全形态（下一行必须是单独一行 `}`）的第二版补上；收尾用 `git diff` 数 catch 行的净增/净减＝128/128 相抵，确认没重复也没丢。

**`AILogger2` 已整类删除（670 行）**：全仓零引用（只剩 `AILogger` 里两句"从 AILogger2 迁移"的注释），且它连 `LogLevel`/`LogCategory` 都是**自带一份副本**——和 `AILogger`、`infra/AppLogger` 里的那两份同名枚举各活各的，三份并存。可视化那套（`VisualLogEntry`/`getVisualInstance`/`logThinking`/`logToolCall`/`logAgentStep`）迁进 `AILogger` 的副本才是活的，所以删除不丢任何能力；过期注释同步改掉并写明"别再往回加"。

### 顺带查出来的：可视化日志这一套也没有调用方

删 AILogger2 时按同一判据查了 AILogger 侧的引用面：**除两个日志类自身之外，`VisualLogEntry` / `getVisualInstance` / `logThinking` / `logToolCall` / `logAgentStep` 在 src/main 里一个调用都没有**。也就是说日志中心现在走的是 `getAllLogs`/`getLogsByLevel` 那条文本路线，可视化那半（含 `VisualLogEntry.toMarkdown`、`generateTimelineMarkdown`、`generateStatsMarkdown`、监听器表）是留着但没人驱动的。这一条我**没顺手删**：它要连着核对测试引用面与 `LogsActivity` 的实际取数路径，属于第 7 节那批"整类候选"的处置，不该在批量改日志的同一轮里做。要做的话判据照旧——先报"零调用 + 无写方"，再决定是接上还是删。

## 16. 批 4c + 可视化日志处置：高危清零，AILogger 瘦掉 376 行

**高危 26 → 0。** 上一批"只改名 stepFailed"的 26 处全部补上可见反馈：19 处直接 `AILogger.w`，
2 处高频流式路径（`TokenStatsBar` 的两条 + `AIChatActivity.updateStreamingTokenStats`）加了一次性
闩锁字段（`phaseReadWarned`/`streamTagWarned`/`cityBroadcastWarned`）——每 tick 都跑的路径不能一次
答案刷几百行。现在全仓状态：**catch 4377｜真·空体 670｜只写注释 133｜只写日志 1128｜有恢复 2375；
高危 0｜中危 543｜低危 127**。

补日志时点到几处后果，不是随手加噪：`ToolResultStore.save` 失败＝后续轮次回读不到这次工具结果；
`TaskStateTracker.clearAll/clearSession` 失败＝新对话/删会话后任务清单跨会话续行（正是"多日任务幻觉"
那条根因）；`nativeGetContextSize` 读不到＝上下文记账退回请求值，KV/余量算的是它；附件缓存与视频
下载的输出流没关好＝落盘可能是半截的，下游只会报一个看不出根因的错；城市变更广播没发出＝别的页
停在旧城市。

**可视化日志那半整块删除（AILogger 1033 → 657 行）**：查尽引用面后确认 src/main、src/test、
src/androidTest 里除本类自身**零引用、零生产者**，且文本线（`writeToFile` + `LogEntry` +
`getAllLogs/getLogsByLevel` + `OnLogListener`）与 Room 的 `log_entry` 表才是日志中心真正在走的路
（`LogsActivity` 实测取数：`getAllLogs()` + `logEntryDao().getRecentLogEntries(1000)`）。删掉的是
`VisualLogEntry`/`LogLevel`/`LogCategory`/`OnVisualLogListener`/`startStep`·`updateStepProgress`·
`completeStep`·`logThinking`·`logToolCall`·`logAgentStep`·`logVisual`·`getVisualInstance`·
`generateTimelineMarkdown`·`generateStatsMarkdown` 等 40 多个成员及其字段与构造函数；顺手清了因此
变成未用的 7 个 import。原位置留了注释写明"要上步骤可视化请接 `AgentProgressBus`，别在本类里复活
第二套"——同一句话对它自己说第二次：更早的 `util/AILogger2` 是第二套，昨天删的，今天是第三套。

**同批删除 `ai/agent/ToolExecutionOrchestrator`（243 行）**：它自己的类注释就写着"没有任何调用点"，
`AIChatActivity` 里 `executeToolWithRecovery`/`retryCompositeStepExecution` 两份**活的同名实现**才是
真实链路；接线还要先把"自动解读 + 逐工具成功消息"再删一遍（注释里已经警告过），留着就是一份会
走歪的第二副本。已并入 `docs/dead-code-triage-2026-11-06.md` 第 7 节处置记录。

**这轮自己的坑（第三次踩同一类）**：批量插语句两次把代码改坏——一次是截尾用 `rfind('{')`
（一行里同时有 `try {` 和 `catch {` 时切错括号，5 处编译报"找不到符号"），一次是按旧行号定位
（前面的插入把行号推走了）。两次都 `git checkout --` 回退，改成**用正则锚定 catch 子句本身**
+ **按内容而不是行号定位** + **每文件花括号差与改前必须一致**的断言重做。还漏了两个文件因为
`'AILogger' in src` 这种字符串判断误判成"已导入"——判据要问编译器：编译过了才算。

未验：这些新日志都要对应异常真发生才出现，本轮没构造故障；三条闩锁路径（流式 token 条、城市广播）
真机各点一次能看到"只报一次"的效果。基线 1746/0/0/1 skipped。

## 17. 自我纠错：批 3 声称的 15 条日志里有 7 条没落盘

复核中危池时用 grep 逐条去**文件里找那段文本**，发现批 3 的 15 处"补日志"里只有 8 处真的写进了源码，
7 处只完成了改名、日志行根本没落：`ToolGuideFlowFlag.notStored`、`MessageAttachmentAdapter.thumbnailFailed`、
`GpuAdaptiveTuner.noProfile`、`LlamaHelper.probeFailed`、`OnlineInferenceService.badHost`、
`NetworkSearchTool.keyUnreadable` / `cardSkipped`。批 3 的提交信息写着"15 条新日志"，那句是错的。

根因不是编译——脚本里多形态分支有一支把插入位置算错后静默什么也没做，**编译器只证明语法，不证明内容**。
现在这 7 处已重做并逐条 grep 复核（每处 1 次命中），真·空体 670→667、只写日志 1128→1135，
基线 1746/0/0/1 skipped。往后这批的验收口径加一条：**补完日志要按文本 grep 回来，不能只看 BUILD SUCCESSFUL**。

## 18. 批 5：偏好/开关落盘这一族，两处改成"失败就不报成功"

分析中危池时按语义动词捞出 14 处"写偏好被吞"，这批全处理完了：11 处补 `AILogger` 警告，2 处**改返回契约**，1 处（`ToolGuideFlowFlag`）在 §17 里已补。现在 `swallow_semantic.py` 的 S1 队列是 **0**，真·空体 667→654、只写日志 1135→1145，基线 1746/0/0/1 skipped。

**改了契约的两处**（光补日志不够——那两处失败后仍有人被当成"办成了"）：

- `PrivacyConsent.accept/revoke` 改成返回 `boolean`。`isAccepted()` 每次读 prefs（不是内存标记），所以写失败时它仍是 false、下次会重弹，**方向是安全的**，但同意凭证没留下来——事后答不出"这个人什么时候同意的哪一版"。三个调用点都接住了：`MainActivity` 与 `PrivacyConsentActivity` 在 false 时提示"同意状态没能保存，下次启动会再问一次"；`AboutLicensesActivity` 的撤回更危险（用户以为撤回了，记录仍是已同意），false 时**直接 return 不跳转**，明说"撤回没生效，当前仍记录为已同意"。
- `SystemResourceTool.shellMode`：`setGuardEnabled` 改成返回 boolean 并读回校验，写不进或读回不一致就用 `AIToolResult.fail` 回给模型——"当前仍是 readonly/full，别按已切换去规划后续命令"。原来这段是无条件 `"status": "success"` + 读回值，写失败时模型会拿着假档位继续推理。注释里写清了读回的边界：`apply()` 内存视图立刻更新，所以读回抓得住"抛异常/被拒"，抓不住"进程当场被杀导致磁盘没落"；要防那层得换 `commit()`，但 commit 返回 true 也不等于磁盘已 sync。

**只补日志的 11 处**：五个渲染 flag 的 `setEnabled`（`ToolGuideFlowFlag`/`ToolResultCardBinder`/`HtmlSubmitFlag`/`PartsRendererFlag`/`ReasoningWebRenderFlag`/`MarkdownStreamTailFlag`——都是"翻了开关，下次启动回默认档"）、`ComponentDisplay.setDefaultTier`（设置页显示已切换）、`UserModelParamsManager.syncGpuToModelStateCache`、`WallpaperStore.ensureBuiltin`（内置壁纸生成失败会从选择列表里静默消失）、`WeatherBannerView` 城市/坐标缓存写、`SmartQuizApplication.recordJavaCrashForAttribution`（崩溃归因没落盘＝下次启动无法把崩溃归到那次模型上）。这些站点原来的变量名是 `ignored`，现在都叫 `notStored`——**名字仍是档位标签**：带日志的站点用说实话的名字。

**常驻判据落到工具里**：新增 `docs/scripts/swallow_semantic.py`，复用关键词扫描器的解析结果（不留两套解析器），按"吞掉的语句有没有副作用/落盘出口"分 S1~S4，并**自动排除已有反馈的站点**——所以它的输出就是"还没人看过的队列"，而不是重复报已处理项。当前队列：S1 0、S2 3（其中 `WeatherBannerView:439` 实际是 prefs *读*，被 `load*` 动词误命中——这就是"分档是队列不是结论"的活样本）、S3 195、S4 564。以后每轮先看 S1/S2，S3 抽查，S4 默认保留。

**这轮又验证了一次自己的验收口径**：前一次批量写入因为 `io.open(...).write()` 没有 close，脚本中途异常退出时**缓冲区里的内容直接丢了**——脚本报"落盘了"而文件其实没变。现在一律 `with io.open(...)` 写、写完**再读回来断言文本存在**。这条也是 §17 那条教训的第二半：不只是"grep 回来"，写文件本身也要保证刷得下去。
