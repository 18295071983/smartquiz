# js_execute 工具新增 · 修复记录（2026-09-14）

> [!IMPORTANT]
> **本文件不是本项目代码/架构的一部分。**
>
> 它是一次性产出物的历史记录 —— 「极光时钟」HTML 经壳 APK 导出时的
> 调试、补丁与验收材料（2026-09-14/15/19）。保留仅供追溯，
> **不随项目维护，也不会被更新**。
>
> 壳能力的**现行权威文档**是 `APK_SOURCE_GUIDE.md`（壳源码指南）与
> `HTML_DESIGN_RULES.md`（HTML 生成规则），当前壳版本 v8.1。

---
**需求**（用户指令）：Agent 环境审计——手机端 Agent 总是说"不能渲染查看 js，只能查代码"，
与电脑端（Python+Node+浏览器）有环境差异；确认可行后新增 JS 执行能力。

## 一、环境审计结论（手机端 vs 电脑端）

| 能力 | 手机端 | 电脑端 |
|---|---|---|
| Python | ✅ Chaquopy 内嵌 CPython 3.10（+requests/bs4/matplotlib/pandas） | ✅ Python 3.14 |
| JS 执行 | ❌ **此前无引擎**（无 Node/Rhino/QuickJS） | ✅ Node v22 |
| HTML/JS 渲染 | ✅ WebView（JS 已开启） | ✅ 浏览器 |
| Shell | ❌ Android 沙箱 | ✅ |

根因：App 提示词并无"不能渲染 JS"声明——是 Agent 对环境事实的如实描述：没有 JS 引擎，
只能 file_reader 读代码文本。系统 WebView（V8/Chromium）本身是完整 JS 引擎，可复用。

## 二、新增 js_execute 工具

- **文件**：`src/main/java/com/oilquiz/app/ai/tool/JsExecuteTool.java`
- **原理**：后台创建隐藏 WebView → 加载内存引导页（注入 console 捕获 + eval 执行入口）→
  `evaluateJavascript` 执行用户代码 → 解码 {ok, value, logs, error} 返回
- **参数**：code（必填，JS 代码）、timeout（可选，1-30s 默认 8）
- **沙箱**：禁止 file/content 访问、禁弹窗/文件 URL、内存页 origin=null（fetch/XHR 被 CORS 拦）；
  每次执行新建 WebView 用完销毁；并发上限 2
- **注册**：AIToolManager（registerToolFactory + getToolDefinition 显式 schema）；
  OnlineToolManager 意图匹配（javascript/js代码/运行js/执行js/调试js 等）；
  使用速查表新增条目
- **本地引擎**：AgentLoopEngine 走 AIToolManager 全量，自动可用

## 三、验证状态（端到端）

- [x] assembleDebug 通过，装机 13:27:28（lastUpdateTime）
- [x] logcat 确认 "Tool lazily initialized: js_execute"，注册总数 59
- [x] **真机实测**（用户在手机上对话触发）：Agent 调用 js_execute
  执行 ES2020 代码（reduce/sort/matchAll/可选链/空值合并/BigInt），
  **75ms 成功**，console.log 6 行全捕获、对象/数组原样回传；
  Agent 自动渲染实测报告卡片并正确总结工具分工
- [x] 使用速查表自动重建，js_execute 条目已出现

## 四、一句话总结

> **新增 js_execute 工具（复用系统 WebView/V8 引擎），手机端 Agent 从"只能查 JS 代码"
> 变为可运行 JS 拿结果（含 console 输出），与 python_execute 平级；端到端真机实测通过。**

## 五、增强与修复（2026-09-14 晚，实测驱动）

### 5.1 JS 三坑修复

| # | 问题 | 修复 |
|---|---|---|
| 1 | 顶层 `return` 报 Illegal return statement | `buildJsScript` 检测行首 return 且 braceDepth==0 时，自动包成 `const __js_ret = (() => { ... })(); __js_ret;` 显式捕获返回值 |
| 2 | script_args 无参数时为 null（`script_args.a` 报错） | 无参数时注入 `{}`；特殊参数名用 `script_args['参数名']` 引号访问 |
| 3 | 并发上限 2 太低 | MAX_CONCURRENCY 2→4 |

### 5.2 能力增强（桥接坞）

- **Promise/async 结果捕获**：`__run` 检测返回值是 Promise → 注册 then/catch → `AndroidBridge.done` 回传；Java 侧同步/异步统一等待，永不 resolve 的 Promise 由 timeout 兜底
- **受限网络桥 `window.__http`**：`get(url)/post(url, body)`，返回 **Promise**（可 await），解析后为 `{ok,status,body}` 对象；仅 http/https、**6s 超时**（低于默认执行超时 8s，避免慢网顶穿）、响应 ≤512KB；内部为原生同步调用，多次调用串行累计耗时
- **受限文件桥 `window.__fs`**：`read/write/list/delete/exists`，返回 **Promise**（可 await），解析后为对象（与 `__http` 约定统一）；仅限 Agent 工作区 `files/` 内；相对路径拒 `..` 穿越、绝对路径 canonical 强校验越界拒绝、文件 ≤512KB、写自动建父目录、删目录需空
- **console 多方法**：log/error/warn/info 全捕获

### 5.3 引擎边界（实测口径）

- 真·WebView 环境（window/document/navigator 均在），非 Node（process/require undefined）；localStorage 抛 SecurityError（沙箱隔离符合预期）
- **现代机型实测支持 ES2020+**：可选链 `?.`、空值合并 `??`、BigInt、WeakRef、structuredClone、`.at()`、replaceAll、findLast 均可用（Chromium 143/V8）；老机型 WebView 较低时可能缺失，跨机型稳妥写法仍建议 ES6
- 无 Java 互操作（Java 包/类不可用，非 Rhino/Nashorn）
- **顶层 return 已自动兼容**：检测到行首 return 且 braceDepth==0 时，自动包成 `const __js_ret = (() => { ... })(); __js_ret;` 显式捕获返回值——`return 值;` 可直接用（js_execute 与动态工具两条路径均已实现）
- **localStorage 共享面已关**：`setDomStorageEnabled(false)`（null origin 页面跨 WebView 实例可能共享存储，关闭零成本减面）

### 5.4 安全边界

- 系统数据 / App 内部敏感数据（题库库、对话历史、用户记忆）不可达：沙箱禁 file/content 访问、origin=null 拦 CORS
- 剩余风险（设计取舍）：工作区用户数据外传（`__fs.read` + `__http.post` 组合）、模型生成代码被 prompt 注入诱导、WebView 引擎自身漏洞

### 5.5 验证

- [x] assembleDebug 通过，装机（2026-09-14 晚，含全部桥接坞与修复）
- [x] 真机脚本化探针（`js_execute_bridge_test_report_0914.md`）确认：文件坞写→读回一致真落盘；网络坞 status 200 取回真实响应；ES2020+ 实测可用
- [x] 修复项：`__fs.list()` 无参不再拼入 "undefined"；`__http` 返回 Promise 对象形态；网络超时 6s；schema 与实测口径一致
- [x] **复测闭环**（`js_execute_bridge_test_report_0914.md` §六）：原 5 问题全部复测通过 ✅；新增 ⑥ `__fs` 约定不统一 → 已统一为 Promise→对象；⑦ 顶层 return 实测未生效 → 已修复（js_execute 路径补 IIFE 自动包装）并装机，文档口径已统一
- [x] 当前使用约定（统一）：**`__http` 与 `__fs` 均返回 Promise，`await` 后为对象**

## 六、并发健壮性修复（2026-09-19，回归清单驱动）

背景：桥测试报告 §6.3/§6.4 实测发现——单批并发上限 4 路，但超限时**静默丢值且仍回「执行成功」**，且多次超限后桥接状态污染（单个轻量脚本在首个 await 悬停，需冷却）；描述未声明并发上限（对照 create_dynamic_tool 已写明 2 路报错）。

| 项 | 修复 |
|---|---|
| 超限行为 | 并发闸改为 `tryAcquire(2s)` 等待后**明确报错**「JS 执行并发已满（同时最多 4 路）」，绝不静默丢值 |
| 结果完整性 | runJs 对桥接结果做校验：rawResult 为空/解析失败 → 明确报「桥接结果丢失/解析失败（可能是并发超限或 WebView 异常）」，不再假装「执行成功」 |
| 描述同步 | 四份描述（js_execute / ToolDefinition / logic 参数 / 在线 schema）全部补写：**单批 ≤4 路、脚本内桥接调用串行 await、勿 Promise.all 打桥、超限明确报错** |
| 状态自愈 | 每次执行独立 WebView + 独立桥实例，用完 destroy；WebView 初始化失败/超时/悬停均有明确报错兜底 |

回归验收（T1-T6）：5 路应返回明确错误；4 路 × 脚本内 6 次串行 await 应 4/4 正常回值；超限后单跑轻量脚本应正常。