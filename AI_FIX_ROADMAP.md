# AI 模块专项修复路线

> 创建日期: 2026-05-16 | 基于深度代码审查 | 共发现 26 个问题

---

## 一、审查总览

对 `src/main/java/com/oilquiz/app/ai/` 下 **25+ 个核心文件**进行深度审查，覆盖 8 个子系统：

| 子系统 | 审查文件数 | 发现问题数 |
|--------|:---:|:---:|
| `agent/` 核心引擎 | 5 | 6 |
| `service/` 服务层 | 4 | 5 |
| `chat/` 聊天子系统 | 3 | 3 |
| `tool/` 工具系统 | 3 | 3 |
| `gpu/` GPU 加速 | 3 | 3 |
| `model/` 模型管理 | 3 | 2 |
| `jni/` JNI 桥接 | 2 | 2 |
| `skill/` + `feature/` | 3 | 2 |

**按严重级别分类**:

| 级别 | 数量 | 说明 |
|:---:|:---:|------|
| 🔴 **CRASH** | 8 | 可导致应用崩溃或 ANR |
| 🟠 **CORRECTNESS** | 6 | 行为不正确，如死代码、假数据 |
| 🟡 **MEMORY** | 5 | 内存泄漏、线程泄漏 |
| 🔵 **WARNING** | 7 | 潜在风险，弱一致性 |

---

## 二、修复路线图

```
第1天            第2-3天          第4-5天          第6-7天          第8-10天
  ↓                ↓                ↓                ↓                ↓
┌────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ 崩溃修复 │ → │ 并发安全  │ → │ 内存泄漏  │ → │ 逻辑修复  │ → │ 代码清理  │
│ (3 bugs)│    │ (4 bugs)  │    │ (5 bugs)  │    │ (7 bugs)  │    │ (7 bugs)  │
└────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘
```

---

## 三、第1天 — 崩溃修复 (CRASH)

### Bug #1 🔴 JNI 句柄非 volatile 导致 Native Crash

| 属性 | 值 |
|------|-----|
| 文件 | [AgentInferenceJNI.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/jni/AgentInferenceJNI.java) L4 |
| 文件 | [LlamaHelper.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/jni/LlamaHelper.java) L529 |
| 严重程度 | **CRASH** — 多线程访问时读到 null 句柄，导致 SIGSEGV |
| 根因 | `contextHandle` / `sessionHandle` 声明为 `private long`，未加 `volatile` |

**修复方案**:

```java
// LlamaHelper.java
// 改造前:
private long sessionHandle = 0;

// 改造后:
private volatile long sessionHandle = 0;
```

```java
// AgentInferenceJNI.java
// 改造前:
private long contextHandle;

// 改造后:
private volatile long contextHandle;
```

**验证**: 并发场景下反复调用 `initialize()` + `destroy()` 不应崩溃。

---

### Bug #2 🔴 chatDestroy 存在 TOCTOU 竞态

| 属性 | 值 |
|------|-----|
| 文件 | [LlamaHelper.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/jni/LlamaHelper.java) L581-L587 |
| 严重程度 | **CRASH** — 检查与使用之间存在时间窗口 |
| 根因 | `if (sessionHandle != 0)` 和 `nativeChatDestroy(sessionHandle)` 之间另一个线程可能已置零 |

**修复方案**:

```java
// 使用局部变量 + synchronized 保护
public synchronized void chatDestroy() {
    long handle = sessionHandle;
    sessionHandle = 0;
    if (handle != 0) {
        nativeChatDestroy(handle);
    }
}
```

同样修复 `chatInit`、`processPrompt` 等方法中的相同模式。

---

### Bug #3 🔴 UnifiedAgentEngine 线程池递归耗尽

| 属性 | 值 |
|------|-----|
| 文件 | [UnifiedAgentEngine.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/agent/UnifiedAgentEngine.java) L78 |
| 严重程度 | **CRASH** — 2线程 FixedThreadPool 下递归任务导致永久阻塞 |
| 根因 | `Executors.newFixedThreadPool(2)` + 任务内递归提交 → 两个线程都被占用 |

**修复方案**:

```java
// 方案1: 增大线程池（推荐）
private final ExecutorService executor = Executors.newFixedThreadPool(4);

// 方案2: 递归任务使用独立线程池
private final ExecutorService recursiveExecutor = Executors.newCachedThreadPool();

// 方案3: 递归改为迭代 + 队列
private final LinkedBlockingQueue<Task> taskQueue = new LinkedBlockingQueue<>();
```

---

## 四、第2-3天 — 并发安全修复

### Bug #4 🔴 AIService.initializeSafe 主线程阻塞造成 ANR

| 属性 | 值 |
|------|-----|
| 文件 | [AIService.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/service/AIService.java) L381-L397 |
| 严重程度 | **CRASH (ANR)** — 主线程阻塞可达 45 分钟 |
| 根因 | `initializeSafe()` 在主线程同步等待模型下载完成 |

**修复方案**:

```java
// 改造后: 使用 LiveData 观察异步结果
public LiveData<InitResult> initializeAsync() {
    MutableLiveData<InitResult> result = new MutableLiveData<>();
    executor.execute(() -> {
        try {
            doInitialize();
            result.postValue(InitResult.success());
        } catch (Exception e) {
            result.postValue(InitResult.error(e));
        }
    });
    return result;
}
```

---

### Bug #5 🔴 工具调用返回 null 导致 NPE

| 属性 | 值 |
|------|-----|
| 文件 | [UnifiedAgentEngine.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/agent/UnifiedAgentEngine.java) L540 附近 |
| 严重程度 | **CRASH (NPE)** |
| 根因 | `toolResult.getOutput()` 在 `toolResult` 为 null 时崩溃 |

**修复方案**:

```java
// 改造后:
AIToolResult toolResult = toolManager.execute(toolName, params);
if (toolResult == null) {
    Log.e(TAG, "Tool returned null: " + toolName);
    return new AIToolResult(false, "Tool execution failed");
}
String output = toolResult.getOutput();
```

---

### Bug #6 🟠 Future.get() 无超时 (共7处)

| 属性 | 值 |
|------|-----|
| 文件 | ServiceRouter, LearningAssistant, QuestionAnalyzer, Translator, QuestionGenerator 中约7处 |
| 严重程度 | **CORRECTNESS** — 线程可能永久阻塞 |
| 根因 | `future.get()` 无限等待 |

**修复方案** — 逐文件修复：

```java
// 改造后:
try {
    String result = future.get(30, TimeUnit.SECONDS);
} catch (TimeoutException e) {
    Log.e(TAG, "Operation timed out after 30s");
    future.cancel(true);
    return fallbackResult();
}
```

**修复清单**:

| # | 文件 | 预计位置 | 超时建议 |
|---|------|---------|:---:|
| 1 | ServiceRouter | AI 服务路由调用 | 30s |
| 2 | LearningAssistant | 学习建议生成 | 15s |
| 3 | QuestionAnalyzer | 题目分析 | 20s |
| 4 | QuestionGenerator | 题目生成 | 30s |
| 5 | Translator | 翻译请求 | 15s |
| 6-7 | 其他2处 | — | 20s |

---

## 五、第4-5天 — 内存泄漏修复

### Bug #7 🟡 线程池重复创建不关闭

| 属性 | 值 |
|------|-----|
| 文件 | [GpuAdaptiveTuner.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/gpu/GpuAdaptiveTuner.java) L585 |
| 文件 | [AIProcessingService.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/service/AIProcessingService.java) L524 |
| 严重程度 | **MEMORY LEAK** |
| 根因 | 多次调用 `startTuning()` 创建新线程池但不关闭旧池；AIPS 每请求创建裸线程 |

**修复方案**:

```java
// GpuAdaptiveTuner
// 改造后: 幂等的 startTuning
public synchronized void startTuning() {
    if (tuningExecutor != null && !tuningExecutor.isShutdown()) {
        return; // 已在运行
    }
    tuningExecutor = Executors.newSingleThreadExecutor();
    // ...
}
```

```java
// AIProcessingService 改造后
// 使用共享线程池代替每次 new Thread()
private final ExecutorService workExecutor = Executors.newFixedThreadPool(3);

private void processRequest(Request req) {
    workExecutor.execute(() -> {
        // ... 处理逻辑
    });
}
```

---

### Bug #8 🟡 AIAgentService START_STICKY 空 intent 崩溃

| 属性 | 值 |
|------|-----|
| 文件 | [AIAgentService.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/service/AIAgentService.java) |
| 严重程度 | **MEMORY/CRASH** |
| 根因 | `onStartCommand` 未处理 `intent == null` 情况（系统重启 Service 时） |

**修复方案**:

```java
@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) {
        Log.w(TAG, "Restarted with null intent, re-initializing from saved state");
        restoreState();
        return START_STICKY;
    }
    // ... 原有逻辑
}
```

---

### Bug #9 🟡 Session/回调泄漏

| 属性 | 值 |
|------|-----|
| 文件 | `ai/refactor/SessionManager.java`, `ai/callback/CallbackHandler.java` |
| 严重程度 | **MEMORY LEAK** |
| 根因 | 多次 createSession 不清理旧 session；回调未在 destroy 时取消注册 |

**修复方案**:

```java
// SessionManager
public void createSession(String sessionId) {
    destroySession(currentSessionId); // 先清理旧 session
    // ...创建新 session
}

// CallbackHandler
public void destroy() {
    for (CallbackEntry entry : callbacks) {
        entry.source.removeCallback(entry.runnable);
    }
    callbacks.clear();
}
```

---

## 六、第6-7天 — 逻辑修复

### Bug #10 🔵 正则死代码

| 属性 | 值 |
|------|-----|
| 文件 | [AgentService.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/service/AgentService.java) |
| 严重程度 | **CORRECTNESS** |
| 根因 | 正则 p3 和 p5 完全重复，p5 永远匹配不到 |

**修复方案**: 检查 p3 和 p5 的正则模式，确认哪个是预期行为，删除重复项或修正。

---

### Bug #11 🟠 假置信度

| 属性 | 值 |
|------|-----|
| 文件 | `SmartIntentRecognizer` |
| 严重程度 | **CORRECTNESS** |
| 根因 | 置信度计算依赖不存在的字段或未初始化的变量 |

**修复方案**: 验证意图匹配的正确性，添加单元测试覆盖每种意图类型（QUIZ/QUESTION/LEARNING/TRANSLATION/CREATIVE/GENERAL/SYSTEM）。

---

### Bug #12 🔵 单例 DCL 问题

| 属性 | 值 |
|------|-----|
| 文件 | 多处 `getInstance()` 方法 |
| 严重程度 | **WARNING** — Java 内存模型下可能读到半初始化对象 |
| 根因 | 部分单例使用 Double-Checked Locking 但未加 `volatile` |

**修复方案**: 所有 DCL 单例统一改为静态内部类或枚举单例：

```java
// 推荐: 静态内部类
public class ModelManager {
    private ModelManager() {}
    
    private static class Holder {
        static final ModelManager INSTANCE = new ModelManager();
    }
    
    public static ModelManager getInstance() {
        return Holder.INSTANCE;
    }
}
```

---

### Bug #13 🟠 API Key 硬编码泄露

| 属性 | 值 |
|------|-----|
| 文件 | [AIWeatherManager.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/tool/AIWeatherManager.java) L43, L144, L224 |
| 严重程度 | **CORRECTNESS / SECURITY** — 3个明文API Key |
| 密钥 | `be2af1f8490344feb8a7125ab46608dd` (和风), `37574c70b8d19d7db691c97af40b5947` (OpenWeather) |

**修复方案**: 见下方"代码清理"部分。

---

## 七、第8-10天 — 代码清理

### Bug #14 🔵 DeepThinkingEngine 9处空 catch 块

| 属性 | 值 |
|------|-----|
| 文件 | [DeepThinkingEngine.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/chat/DeepThinkingEngine.java) L54, L85, L126, L166, L196, L222, L281, L326, L361 |
| 严重程度 | **WARNING** |
| 操作 | 所有 9 处添加 `Log.e(TAG, "...", e)` 并在关键路径返回合理的 fallback 值 |

---

### Bug #15 🔵 AILogger / AILogger2 / AppLogger 三合一

| 操作 | 文件 |
|------|------|
| 保留 | `AILogger.java` — 作为唯一 AI 日志入口 |
| 合并 | `AILogger2.java` — 功能迁移到 AILogger |
| 合并 | `AppLogger.java` — 作为 AILogger 的静态包装 |

---

### Bug #16 🔵 弃用代码删除

| 操作 | 文件 |
|------|------|
| **删除** | [GpuCompatibilityAdapter.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/config/GpuCompatibilityAdapter.java) (7处@Deprecated) |
| **删除** | [GpuAutoAdapter.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/config/GpuAutoAdapter.java) (8处@Deprecated) |
| **删除** | [DeviceCapabilityDetector.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/config/DeviceCapabilityDetector.java) (8处@Deprecated) |

---

### Bug #17 🔵 模型配置去重

三处重复的模型元数据需统一：
- [MultiModelManager.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/model/MultiModelManager.java) L97-L112
- [ModelSelectionActivity.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/model/ModelSelectionActivity.java) L60-L93
- [ModelComparisonActivity.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/model/ModelComparisonActivity.java) L57-L90

**修复方案**: 新建 `models_presets.json` 配置文件，三处代码统一读取：

```json
{
  "presets": [{
    "id": "qwen2.5-3b-q4_k_m",
    "name": "Qwen2.5 3B Q4",
    "sizeMB": 1780,
    "contextLength": 32768,
    "recommendedRamMB": 3500
  }]
}
```

---

## 八、完整任务清单

### 🔴 紧急 (P0) — 第1天完成

| ID | Bug | 文件 | 预计时间 |
|:--:|------|------|:---:|
| 1 | JNI 句柄 volatile | `AgentInferenceJNI.java`, `LlamaHelper.java` | 30min |
| 2 | TOCTOU 竞态修复 | `LlamaHelper.java` L581-L587 | 30min |
| 3 | 线程池递归耗尽 | `UnifiedAgentEngine.java` L78 | 20min |

### 🟠 高优 (P1) — 第2-3天

| ID | Bug | 文件 | 预计时间 |
|:--:|------|------|:---:|
| 4 | ANR — 主线程阻塞 | `AIService.java` L381-L397 | 1h |
| 5 | NPE — 工具结果 null | `UnifiedAgentEngine.java` L540 | 15min |
| 6a-g | Future.get() 无超时 | ServiceRouter 等 7处 | 1h |

### 🟡 中优 (P2) — 第4-5天

| ID | Bug | 文件 | 预计时间 |
|:--:|------|------|:---:|
| 7a | 线程池重复创建 | `GpuAdaptiveTuner.java` | 30min |
| 7b | 裸线程创建 | `AIProcessingService.java` | 30min |
| 8 | START_STICKY null intent | `AIAgentService.java` | 15min |
| 9a | Session 泄漏 | `SessionManager.java` | 30min |
| 9b | 回调泄漏 | `CallbackHandler.java` | 20min |

### 🔵 低优 (P3) — 第6-10天

| ID | Bug | 文件 | 预计时间 |
|:--:|------|------|:---:|
| 10 | 正则死代码 | `AgentService.java` | 10min |
| 11 | 假置信度 | `SmartIntentRecognizer` | 1h |
| 12 | DCL 单例修复 | 多处 `getInstance()` | 1h |
| 13 | API Key 迁移 | `AIWeatherManager.java` | 1h |
| 14 | 空 catch 块修复 | `DeepThinkingEngine.java` 9处 | 30min |
| 15 | Logger 三合一 | `AILogger/AILogger2/AppLogger` | 1h |
| 16 | 弃用代码删除 | `ai/config/` 3个文件 | 10min |
| 17 | 模型配置去重 | 3个文件 → 配置化 | 1.5h |

---

## 九、每项修复的验证标准

### 修复前必须做

```
1. 理解 bug 的触发条件
2. 设计修复方案
3. 检查修复是否引入新问题
```

### 修复后必须验证

```
4. 相关单元测试通过
5. 手动复现原 bug，确认不再触发
6. 回归测试：不影响原有功能
```

### 每个 Bug 的验证清单

| Bug ID | 验证方法 |
|:--:|------|
| #1 | 100次并发 init/destroy 循环 → 无 SIGSEGV |
| #2 | 多线程同时 destroy + process → 无崩溃 |
| #3 | 提交递归任务到满线程池 → 不死锁 |
| #4 | 模型下载中操作 UI → 不 ANR |
| #5 | 模拟工具返回 null → 不 NPE，有错误日志 |
| #6 | 模拟网络超时 → 30s 后超时退出，不永久阻塞 |
| #7 | 多次 startTuning → 内存不增长 |
| #8 | 系统杀死 Service 后自动重启 → 不崩溃 |
| #9 | 反复创建 session → 内存不增长 |
| #10-#17 | 代码审查 + 功能回归 |

---

## 十、建议执行顺序

```
┌─────────────────────────────────────────────────────────────┐
│ Sprint 1 (2周)                                               │
│                                                              │
│  Day 1-2   Bug #1, #2, #3   崩溃修复 (先修会炸的)            │
│  Day 3-4   Bug #4, #5, #6   ANR + NPE + 超时                │
│  Day 5-6   Bug #7, #8, #9   内存泄漏                         │
│  Day 7-8   Bug #10~#13      逻辑正确性                       │
│  Day 9-10  Bug #14~#17      代码清理                         │
│                                                              │
│  Day 11-14 为修复的代码补充测试                               │
└─────────────────────────────────────────────────────────────┘
```

---

## 相关文档

- [ROADMAP.md](ROADMAP.md) — 整体迭代路线图
- [系统架构](docs/system/system_architecture.md)
- [Agent 架构](docs/AGENT_ARCHITECTURE.md)
- [开发标准](docs/development/development_standards.md)