# 交接文档：本地 Agent 推理「预处理越来越慢」问题

> 交接对象：豆包
> 项目：答题宝（`com.oilquiz.app`）· 本地推理引擎 llama.cpp / GenieX NPU / 在线 HTTP 三分支
> 交接日期：2026-10-07
> 当前状态：**已修复并实测通过，代码已提交（含后续 P0/P1/P2 上下文满截断修复见第九节；对话页上下文大小/使用量暴露见第十节）**

---

## 一、结论速览（先看这段）

用户报告：**本地 Agent 多轮对话时，「预处理」越来越慢**，一轮要 8~16 秒。

**根因（两个独立缺陷叠加）**：

1. **工具定义（tools schema）渲染在 prompt 最前面，但它的顺序不确定** —— 依赖 `HashMap` / `ConcurrentHashMap` 的迭代顺序，上游工具集又来自"意图识别 / 工具选择 / 动态注入"，顺序无契约保证。顺序一变，**整个 KV 前缀全部作废**。
2. **每轮推理前会「原地改写」已进入历史的消息** —— `AgentLoopEngine.trimHistoryToFit()` 里有一"渐进压缩工具结果"步骤，按预算压力把同一条旧工具结果反复重裁成 4000 → 2500 → 1200 字符。**文本一变，token 序列就变，前缀即断。**

**为什么一次前缀断裂 = 全量重算**：本项目模型 Qwen3.5 是 **attention + recurrent(SSM) 混合架构**，`llama_memory_seq_rm()` 的部分前缀截断**必然失败**（`n_rs_seq=0`，开启需约 1.2GB recurrent-state 快照，实测 256 时触发内核 OOM 杀进程）。所以 KV 缓存退化成 **"全有或全无"**：只要有一处 token 不同，从该点之后全部重算。

**修复后实测**：

```
Round tools schema: 8 tools, len=6354, hash=44e76836    ← 每轮完全一致
KV-STATS: plans=10  inc=8  part=1  full=1  hit=90.0%
Prefill: 26 / 40 / 48 / 88 / 140 / 220 tokens → 0.25~1.0 秒
```

修复前同场景：`Prefill: 2942 tokens → 8.34s`、`3300 → 9.90s`、`4729 → 16.25s`。

---

## 二、环境与构建（必读，否则跑不起来）

| 项 | 值 |
|---|---|
| 工作目录 | `D:\qzq\smartquiz` |
| `JAVA_HOME` | `D:\jdk-21`（`gradle.properties` 里 `org.gradle.java.home=D:/jdk-21`）|
| Gradle 用户目录 | `D:/Gradle/Home`（`systemProp.gradle.user.home`）|
| 构建 | `.\gradlew.bat :assembleDebug --console=plain` |
| 单测 | `.\gradlew.bat :testDebugUnitTest` |
| APK | `build\outputs\apk\debug\答题宝-debug-2.0.apk` |
| 安装 | `adb install -r 'build\outputs\apk\debug\答题宝-debug-2.0.apk'` |
| 启动 | `adb shell monkey -p com.oilquiz.app -c android.intent.category.LAUNCHER 1`<br>（`AIChatActivity` 是 `exported=false`，`am start` 会被拒）|

**⚠️ 两个坑**：

- **测试基线是「277 用例 / 25 失败」，不是全绿。** 这 25 个是既有失败（OnlinePromptBuilderAssembly 4、StreamingUpdateManager 5、AIServiceState 1、AIToolManager 2、ExportTemplate 1、resource 6、PreviewRenderBridge 4、viewmodel 2）。**只要仍是 277/25 就是零回归**，不要试图去修这 25 个。
- **`gradlew` 会把 APK 构建和单测混在一个命令里时返回非 0**（因为单测有既存失败）。判断"编译是否成功"要看 `BUILD SUCCESSFUL`，不要看退出码。

**设备**：SM8850（骁龙 8 Elite Gen 5）/ Adreno 840 / Hexagon HTP v81。
真实模型在 `/data/user/0/com.oilquiz.app/files/ai_models/`：`Qwen3.5-0.8B-Q4_0.gguf`（483MB，24 层）、`Qwen3.5-2B-Q4_0.gguf`（1.2GB，24 层）。

---

## 三、当前配置（实测读取）

`shared_prefs/ai_config.xml`：
```
local_agent_enabled = true     ← 走本地 Agent
agent_enabled       = true
optimization_mode   = 1        ← BALANCED → 12288 上下文
```

`shared_prefs/npu_engine_prefs.xml`：
```
npu_enabled = false            ← 当前用 llama.cpp（不是 NPU）
npu_model   = applib/Qwen3.5-2B-Q4_0
```

> 注：调试期我曾用 `adb shell run-as com.oilquiz.app sed -i s/false/true/ ...npu_engine_prefs.xml` 切 NPU，后来又切回 false。**当前是 false。**

---

## 四、本次已做的代码改动（未提交）

### 1. `AgentLoopEngine.buildToolsJson()` —— 工具名排序
**文件**：`src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java`（约 1852 行起）

原来 `for (String name : toolNames)` 按传入顺序构建。现改为先 `Collections.sort(ordered)` 再遍历。
**理由**：该 JSON 渲染在 prompt 前缀（代码原作者已注明"tools 渲染在 system 前缀，跨轮集合一致 → system 字节稳定 → KV 前缀可增量"），但上游 `selectedTools` / `activeTools`（`LinkedHashSet`，动态注入会追加）顺序**无契约保证**。排序后无论上游怎么重排，输出逐字节一致。

### 2. `AIToolManager.getOpenAIToolDefinitions()` —— 排序
**文件**：`src/main/java/com/oilquiz/app/ai/tool/AIToolManager.java`

原来遍历 `toolFactories.keySet()`（`HashMap`）+ `dynamicTools.values()`（`ConcurrentHashMap`）。现两条都先取 key 列表、`Collections.sort` 再构建。**中途注册新工具触发 rehash 会重排 HashMap 迭代顺序**，这就是"每轮 schema 变"的机制。另外把日志从 `Returning N OpenAI tool definitions` 增强为带 `len=` + `hash=`。

### 3. `AIToolManager.getToolsAsOpenAIFormat()` —— 同上排序
同一文件，另一处出口，原来也是直接遍历哈希表。

### 4. `AIToolManager` 字段 —— `toolFactories` / `toolClasses` 改 `LinkedHashMap`
```java
this.toolFactories = new java.util.LinkedHashMap<>();   // 原 HashMap
this.toolClasses   = new java.util.LinkedHashMap<>();   // 原 HashMap
```
**注意**：`dynamicTools` 保持 `ConcurrentHashMap` **不要改**（并发安全优先，顺序已由调用处排序保证）。`initializedTools` / `toolLastUseTime` 也没动。

### 5. `AgentLoopEngine.trimHistoryToFit()` —— 删除「渐进压缩工具结果」
**这是最关键的语义修复。** 原代码（已删）：
```java
// 1) 压缩工具结果（最占空间），保留最近完整，旧结果按新旧渐进收缩
java.util.List<Integer> toolIdx = ...;   // 收集 content.length() > 1200 的 tool 消息
int toolTier = 0;
for (int idx = toolIdx.size()-1; idx >= 0 && total > budgetTokens; idx--) {
    int cap = toolTier == 0 ? 4000 : (toolTier == 1 ? 2500 : 1200);
    toolTier++;
    if (m.content.length() > cap) {
        trimmed.set(toolIdx.get(idx), new ChatMessage(m.role, truncate(m.content, cap)));
        total = countTokensSafe(serializeHistory(trimmed)) + schemaTokens;
    }
}
```
**为什么必须删**：它在**原地改写已进入历史的消息**。同一条旧工具结果随对话增长被反复改短 → 文本变 → token 序列变 → KV 前缀每轮都断 → 每轮全量 prefill。**工具结果在入库时已由 `MAX_TOOL_RESULT_LENGTH` 一次性定稿**（见 `history.add(new ChatMessage("tool", truncate(resultStr, MAX_TOOL_RESULT_LENGTH), tc.id, true))`），所以这一步纯属有害无益。

**保留**的部分：超预算时**只从头部丢弃最旧消息**（`trimmed.remove(1)` 循环 + 生成"历史要点"）。丢弃让窗口滑动、剩下的序列仍连续，不破坏前缀稳定性。

**新增了一条设计约束注释**在方法 KDoc 上：**本方法必须保持"只增不改"性质**。

### 6. 加了两处 schema 指纹日志（用于验收）
```
AgentLoopEngine: Initial tools: N tools, schema len=..., hash=...
AgentLoopEngine: Round tools schema: N tools, len=..., hash=...
AIToolManager:   Returning N OpenAI tool definitions, len=..., hash=...
```
**跨轮比对 `hash=` 是否恒定，就是判断"tools 前缀是否稳定"的直接手段。**

### 7. 顺带完成的（同一个"前缀稳定性"主题，已验证）
- **`GgufMeta` 改走 llama.cpp 官方 GGUF API**（`gguf_init_from_file`），替换手写的 `RandomAccessFile` 解析器（原实现有 buffer 偏移 bug 导致 `EOFException` → 静默返回 null → 层数取不到）。
- **`ResourceConfig` 层数修正**：原来在模型加载**之前**问 `LlamaHelper.getModelMeta()`（那是"上一次已加载模型"的元数据，此时为空），落到 `estimateTotalLayers(483)` 的 `if (modelSizeMB < 500) return 22`，**把 24 层的模型当成 22 层**。现改为直接读 GGUF `blockCount`（需传**绝对路径**——调用方 `AIService` 原来只传 `modelFile.getName()`，`File.isFile()` 为 false 会静默跳过）。
- **`n_gpu_layers` 语义差 1**：llama.cpp 的 `n_gpu_layers` **含输出层**（见 `src/llama.cpp/src/llama-model.cpp:1912`：`n_repeating = n_gpu; if (n_repeating > 0) n_repeating--;`，`max_backend_supported_layers = n_layer_all + 1`）。所以"全量"必须写 `totalLayers + 1`。
- **`MAX_GPU_LAYERS` 36 → 64**（Java `ResourceConfig` 与 native `native-lib.cpp` 同步）：原值参照"Qwen3-4B 共 36 层"，但 36 是重复层数，全量需 37，被 36 卡住仍留 1 层在 CPU。
- **效果**：`offloaded 25/25 layers to GPU`（原 `22/25`、3 层在 CPU）；模型加载 4s→**2s**；GPU warmup batch 1.47s→**0.85s**。**用户实测确认"推理速度明显快了"。**

### 8. 模板层修复（保留，但注意它不是本次"越来越慢"的根因）
**文件**：`src/main/cpp/native-lib.cpp`，函数 `chatTemplateForKvReuse()`

Qwen3/Qwen3.5 官方 chat template 有已知缺陷（上游 issue：[QwenLM/Qwen3#1826](https://github.com/QwenLM/Qwen3/issues/1826)）：assistant 消息按"是否最后一条"分两支渲染，`enable_thinking=false` 时**只有最后一条**会补空 `<think>\n\n</think>\n\n`，历史里的不补 → 同一段历史两轮渲染不同 → KV 前缀失配。

**修法**：不改模型文件，用 `common_chat_templates_init(model, chatTemplateForKvReuse(model))` 的覆盖参数传入"打了补丁的模板"——给历史分支补一条 `enable_thinking=false` 路径。

**⚠️ 踩过的坑**：第一版补丁**多插了一个 `{%- else %}`**，而原文本来就有 `else` → Jinja 结构变成 `if/elif/else/else/endif` → **`SIGABRT` (signal 6)，模型加载失败**。正确做法是**只插 `elif`、复用原文的 `else`**。想复现/检查时，验证模板合法性的办法：数 `if` 与 `endif` 是否平衡（设备上 dump 出来的那份是 `if=30, elif=8, else=9, endif=30`）。

**定位提示**：`chatTemplateForKvReuse` 定义在 `llama_jni` 命名空间内（供 `InferenceContext` 用），而 `NativeChatContext` 在该命名空间**之外**，所以另有一个全局转发 `chatTemplateForKvReuseGlobal()`。这个作用域问题当时排查了很久（编译器提示 `did you mean 'llama_jni::chatTemplateForKvReuse'` 才定位到）。

---

## 五、验证方法（怎么证明修好了）

### 关键日志（都已埋好）
```
# 1) tools schema 指纹 —— 跨轮必须恒定
adb logcat -d | Select-String 'tools schema|Initial tools:|OpenAI tool definitions'

# 2) KV 缓存决策 —— 希望看到大量 HIT，不要有 FULL EVAL
adb logcat -d | Select-String 'AgentKvCache'

# 3) prefill 吞吐与耗时 —— 增量轮应该 0.2~1.0 秒
adb logcat -d | Select-String 'PERF\] Prefill'
```

### 判定标准

| 指标 | 修复前 | 修复后（实测）|
|---|---|---|
| `Round tools schema` 的 `hash=` | 每轮不同 | **恒定 `44e76836`** |
| `KV-STATS` 命中率 | — | **90%**（`inc=8 part=1 full=1`）|
| 单轮 `Prefill` | 2942~4729 tokens / **8~16 秒** | 26~220 tokens / **0.25~1.0 秒**|
| `FULL EVAL` | 频繁 | 仅冷启动 1 次 |

### 日志语义速查（源码在 `src/main/cpp/agent_kv_cache.cpp`）
```
HIT: matched N tokens, delta eval M          ← 全前缀命中，只算 M 个（最理想）
PARTIAL: matched N/M tokens, delta eval K    ← 前缀部分匹配，想截断后增量
PARTIAL seq_rm failed (rollback=D)           ← 混合架构SSM无法截断 → 退化成全量
FULL EVAL reason: first_call_or_invalidated  ← 首次 / 缓存被清
FULL EVAL reason: no_prefix_match            ← 前缀完全对不上
FULL EVAL reason: seq_pos_mismatch           ← KV位置与记账不一致
```

---

## 六、未解决 / 需要继续关注

### 1. `PARTIAL` 路径在这个架构上**永远不可能成功**
`n_rs_seq` 保持 0（`native-lib.cpp` 有注释说明）。开启需 `(1 + n_rs_seq)` 倍 recurrent-state 张量：本模型 `ssm.state_size=128 / group_count=16`，18 个 SSM 层约 **9.2MB/档**，`n_rs_seq=128` ≈ **1.2GB**、`256` ≈ 2.4GB → **必然 OOM**（实测 256 触发内核 `lowmemorykiller` 连杀多进程）。

**所以不要试图"让 PARTIAL 成功"**，那条路走不通。已实测否定。

### 2. 仍会偶发 `PARTIAL`/`FULL EVAL`（本次 `part=1`）
剩下的分叉来源是**内容层**的：工具结果本身每轮不同（搜索每次结果不同、`time_date` 时间戳、GPS 定位经纬度）。**这是工具非确定性的本质，无法通过"稳定渲染"解决。**

我曾在这上面判断失误两次，记录如下避免重走：
- 曾以为是"解析器丢失浮点精度"（观察到 `longitude=106.262992` vs `106.263`）。**查证后否定**：`arguments` 全程按字符串透传（`ModelExecutionBridge:1401`、`:578`），native 侧无任何 `setprecision`/`%.3f` 数字格式化，Java 侧 `JSONObject.put` 也是字符串。
- 曾以为是 `System.nanoTime()` 生成的 `tool_call.id` 每轮不同。**查证后否定**：该 id 并未渲染进 prompt 模板。

### 3. 下一步可选方向（若要继续优化）
- **方案 A（治本）**：让 prompt 成为**单调追加的 token 流**——不再每轮从结构化消息重渲染全量，而是维护"上次实际 decode 的 token 序列"只追加新增部分。KV 前缀按构造保证命中。工作量较大。
- **方案 B（快速见效）**：既然全量不可避免，就**缩短历史窗口 / 用摘要替代原文**，把全量代价从 10~16 秒降到 2~4 秒。改动小、风险低。
- **方案 C**：双 KV / 分段缓存（`seq_cp`），把"不变前缀"和"变化尾部"分开管理。工作量大。

### 4. 待清理
- 排查期在设备上落过一个模板 dump：`/data/user/0/com.oilquiz.app/files/_chat_template_dump.jinja`（已删过一次，如再出现可删）。
- **`DIVERGE` / `PROMPT-DUMP` 临时诊断代码已清理完毕**，不要再加回来（`PROMPT-DUMP` 每轮写二进制到 files 目录，`DIVERGE` 每轮解码 60 token 打日志，都只适合一次性排查）。

---

## 七、Git 状态

```
f26554da  修 Agent 多轮「预处理越来越慢」：工具 schema 顺序不稳定 + 每轮改写历史   ← 已推送
b3b4e9b0  llama.cpp 全量卸载到 GPU（消除残留 CPU 层）；状态栏文案统一为短句+秒数   ← 已推送
c7a9adbd  NPU 加载去伪进度（改短句+秒数心跳）；日志按钮接统一日志中心；修重复加载
bb6daa78  GGUF 元数据改走 llama.cpp 官方 API；删除日志查看页及废弃广播链路
2aecc45e  NPU 契约 + 思考分离
```

**远端**：`origin` = gitee（正常）、`github` = https（偶发 `Connection was reset`，重试即可）。

**⚠️ 第九节的 P0/P1/P2 修复已提交推送（见第九节末）。**

---

## 八、给豆包的三条提醒

1. **先看 `hash=` 日志再动手。** 这个问题的所有判断都应该基于"跨轮 schema 指纹是否恒定"和"`KV-STATS` 命中率"，不要凭代码直觉猜。我凭直觉猜错过两次（浮点精度、tool_call id），都在第六节记录了。
2. **不要试图开启 `n_rs_seq`。** 已实测定量否定：128 档就 1.2GB，必然 OOM。
3. **改 prompt 组装相关代码时，牢记唯一原则：「已进入历史的消息，内容永不改写」。** 只允许"追加"和"从头部丢弃"。任何"原地压缩/截断/规范化"都是在破坏 KV 前缀。

---

## 九、后续修复（2026-10-07 二交：上下文满截断 P0/P1/P2，已提交推送）

**背景**：第一次修复后缓存命中健康（KV-STATS hit=90%），但排查发现另一类用户可见 bug —— **长回答被"上下文满"硬截断且完全静默**：截断回复被当成完整回答交付，用户感知"回答突然没了/变短"。

### 根因（预算矛盾）

| 项 | 修复前 | 修复后 |
|---|---|---|
| prompt 预算 | `n_ctx × 0.8` = 9830（固定比例） | `n_ctx − 4000 − 512` = **7776** |
| 生成空间 | 12288 − 9830 = **2454** | 12288 − 7776 − 512 ≈ **4000** |
| maxTokens | 4000（`FINAL_RESPONSE_MAX_TOKENS`，恒发） | `min(4000, n_ctx − 实际prompt − 512 − 512)` |
| 长回答 | 生成 2455 token 即 `Context full, stopping generation`（native-lib.cpp:3115）硬截断 | 在 max_tokens 处自然停止，**永不 ctx_full** |

### P0a · max_tokens 钳制（治本）
`AgentLoopEngine.buildRequestJson()`：原 `req.put("max_tokens", maxTokens)` 恒发 4000，注释声称"按上下文钳制"但实现没有。现按 `n_ctx − 实际 prompt token（countTokensSafe 真实分词 + 512 模板 overhead）− 512 native guard` 钳制，下限 256。

### P0b · 截断可观测（兜底信号）
- native：`InferenceContext` 新增 `lastStopReason_`，`generateStreamIncremental` / `generateStream` 末尾赋值；chatJson 三个 complete 事件（正常 / degraded / fallback）均带 `stop_reason` 字段。
- Java：`GenerateResult.stopReason`；onJson complete 分支读取；主循环检测 `ctx_full` → `AILogger.w` + `LoopCallback.onTruncated` → `AgentSoftwareLayer.onTruncated`（两层都是 default 方法，UI 未实现不影响编译）。
- **验收**：`adb logcat -d | Select-String 'truncated by context full'` 出现即表示发生过截断。

### P1 · 预算公式
`computePromptBudget()`：`n_ctx − FINAL_RESPONSE_MAX_TOKENS − NATIVE_GENERATION_RESERVE(512)`。旧 `PROMPT_BUDGET_RATIO=0.8` 已标记废弃（保留常量防外部引用，勿再使用）。NPU 模式（nCtx=8192）→ 预算 3680，生成空间 4512 ≥ 4000，同样成立。

### P2 · 历史要点稳定化
`trimHistoryToFit()`：要点由每轮基于 evicted 重生成改为**一次性生成后缓存**（新增 `compactionSummary` 字段，`run()` 开头重置），要点生成逻辑提取为 `buildCompactionSummary()`。同一批被裁历史逐字节稳定，不再每轮重生成/递归压缩。

### 改动文件
```
src/main/cpp/native-lib.cpp
src/main/java/com/oilquiz/app/ai/agent/software/AgentSoftwareLayer.java
src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java
```

### 验证
`.\gradlew.bat :assembleDebug` → **BUILD SUCCESSFUL**（唯一编译错误：chatJson fallback lambda 未捕获 `this` 访问 `lastStopReason_`，已加 `this` 到捕获列表）。APK 975MB 正常产出。

### 上设备验收清单
1. 日志 `Prompt budget:` 应显示 **7776**（原 9830）。
2. 长回答轮（>2450 token 输出）不再出现 `Context full, stopping generation`。
3. `KV-STATS` 命中率应保持 90% 不变（增量路径未动）。
4. 若出现截断（兜底场景），Java 日志出现 `generation truncated by context full` + UI 可经 `onTruncated` 提示。

### 遗留说明
- max_tokens 钳制的 token 计数基于 Java 简化文本（serializeHistory）+ 512 模板 overhead 预留；若未来模板 overhead 更大（新增大量特殊 token），预留需同步增大。
- `onTruncated` 目前 UI 层（AIChatActivity 等）未接默认空实现；如需用户可见提示，实现 `AgentCallback.onTruncated` 即可（default 方法，不破坏现有实现）。
## 十、对话页上下文大小/使用量暴露（2026-10-07 三交）

用户要求：对话页面显示上下文**大小**和**使用量**（本地 Agent 模式）。

### 问题
- 对话页上下文仪表（pill 📊）与明细弹窗早已存在，但本地 Agent 模式数据源接错：
  `LlamaSignalAdapter.contextUsage()` 读 `LlamaHelper.getContextUsedTokens()`（`NativeChatContext` 的 chat handle 记账），Agent 走 `InferenceContext`（AgentKvCache），该 handle 恒 0 → pill 显示 `📊 --` 或 0%，明细显示字符估算值。
- pill 只显示百分比，看不到"窗口多大、用了多少"。

### 修复
1. **数据源（治本）**：`LlamaSignalAdapter.contextUsage()` 优先解析 `LlamaHelper.getKvCacheStats()`（= native `nativeGetKvCacheStats`，即 AgentKvCache 记账）的 `cached_npast`（KV 真实 decode token 数：prompt + 生成输出）与 `ctx_size`（n_ctx 窗口）。Agent 记账未建立（ctx_size=0，普通对话路径）时回退原 chat handle 路径。
2. **pill 显示（大小+用量）**：`ChatStatsBar.setContextPercent(int percent, long usedTokens, long windowTokens)` 加 window 参数，文本改 `📊 {pct}% · {used}/{window}`（如 `📊 41% · 5.0k/12.3k`），`window<=0` 时回退旧行为。
3. **明细弹窗**：`showContextMeterDialog()` 本地分支改读 `GenSignalSource.contextUsage()`（引擎无关契约，KV 真实占用），标注 `（KV 实际占用）` 而非 `（估算）`。

### 改动文件
```
src/main/java/com/oilquiz/app/ai/engine/contract/LlamaSignalAdapter.java
src/main/java/com/oilquiz/app/ai/chat/ui/ChatStatsBar.java
src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java
```

### 验证
`.\gradlew.bat :assembleDebug` → **BUILD SUCCESSFUL**（41s，10 任务执行）。APK 975265731 B（18:37:14）。

### 上设备验收清单
1. 本地 Agent 对话页 pill 显示 `📊 {pct}% · {used}/{window}`，used 与 KV-STATS 日志 `cached_npast` 一致（非 0 / 非估算）。
2. 点击 pill：明细显示 `used / window tokens（KV 实际占用）`，窗口 = 12288（BALANCED）。
3. 普通对话（非 Agent）路径仍显示 chat handle 记账，不回退错误。
4. 未加载模型时显示 `📊 --`（window 未知），不显示故障态。
## 十一、本地离线 Agent 默认提示词对齐在线（2026-10-07 四交）

用户要求：本地离线 Agent 的默认提示词与在线 Agent 一致。

### 差异（修复前）
| | 本地（AgentLoopEngine.buildFcSystemPrompt） | 在线（OnlinePromptBuilder.buildPersonaSection） |
|---|---|---|
| 角色行 | 你是答题宝App的AI聊天助手（Agent模式），与用户自然对话，需要实时/外部信息时主动调用工具获取。 | 你是答题宝App中的AI聊天助手，是App内"AI对话"功能模块的助手（在线Agent模式）。 |
| 工作/方式/边界/风格 | 无（仅角色 1 行 + 规则段） | 完整 5 行 persona |

### 修复
本地【角色】段与在线 persona 逐字对齐（同一产品身份），两处按本地能力适配：
1. "（在线Agent模式）"→"（Agent模式）"——本地不能自称在线；
2. "结构化信息用UI组件展示"→"清晰的分段与列表"——本地 ui_component 大 schema 不注入（DEFAULT_CORE_TOOLS 注释），照搬会诱导模型调用不存在的工具。

【规则】段（工具调用规则 1-6）随后一并删除（2026-10-07 四交补）：关键约束已被 persona 覆盖（规则1 实时必须调工具 ≈ 方式行「实时/动态信息必须用工具获取」；规则5 中文简洁先结论 ≈ 风格行），本地 Qwen3.5 原生 FC + tools schema description 引导，prompt 更小（首轮 FULL 更小、KV 更省），与在线最小提示词完全同构。若实测小模型编造实时数据，恢复「实时/时效类必须调用工具，不要用训练数据猜测」一行即可。

### 改动文件
```
src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java
```

### 验证
`.\gradlew.bat :assembleDebug` → **BUILD SUCCESSFUL**（24s，9 任务执行）。

### 上设备验收
- 本地 Agent 自我介绍/行为符合"答题宝App中的AI聊天助手，是App内AI对话功能模块的助手（Agent模式）"身份，与在线一致。
- KV-STATS 命中率不受影响（system 段变化仅影响首轮 FULL，跨轮前缀稳定性依赖 sessionMessages 逐字节回放，不受本段影响）。
