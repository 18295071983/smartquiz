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
