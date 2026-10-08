# 答题宝 (SmartQuiz) — AI 编码助手规则

> **版本: 4.4 | 更新日期: 2026-08-29**
> 本版经**代码级审查**修正，作为 AI 编码工具/Agent 开发工具的约束文件（含非破坏性原则、编码安全、真实 AITool/包/技术栈约定）。

---

## 一、概述

本文档定义 AI 编码助手（如 GitHub Copilot、Cursor、Claude、GPT 系列等）在参与「答题宝」项目开发时必须遵循的规则。AI 助手在生成/修改/审查代码时，必须将本文档作为最高优先级上下文规则。

---

## 二、项目文档清单

AI 助手在生成代码前必须了解以下文档（`docs/development/` 共 01~16）：

| 编号 | 文档路径 | 用途 |
|------|---------|------|
| 01 | `docs/development/01-project-overview.md` | 项目架构总览（技术栈/顶层包） |
| 02 | `docs/development/02-ai-agent-architecture.md` | AI Agent 单循环 + 双路由 |
| 03 | `docs/development/03-ai-chat-ui.md` | AI 对话界面 |
| 04 | `docs/development/04-ai-service-inference.md` | AI 服务/推理 |
| 05 | `docs/development/05-tool-system.md` | 工具系统（AITool 接口） |
| 06 | `docs/development/06-data-layer.md` | 数据层 |
| 07 | `docs/development/07-hardware-performance.md` | 硬件与性能 |
| 08 | `docs/development/08-module-inventory.md` | 模块清单（真实类索引） |
| 09 | `docs/development/09-development-guide.md` | 开发规范/构建/测试 |
| 10 | `docs/development/10-database-design.md` | 数据库设计 |
| 11 | `docs/development/11-edge-model-deployment.md` | 端侧大模型部署 |
| 12 | `docs/development/12-llama-cpp.md` | llama.cpp 功能 |
| 13 | `docs/development/13-inference-engine.md` | 推理库与推理引擎 |
| 14 | `docs/development/14-cmake-build.md` | CMake 构建 |
| 15 | `docs/development/15-question-import.md` | 题库导入功能 |
| 16 | `docs/development/16-ai-coding-conventions.md` | AI 工具编码约定（本文档的细化，**需与本文档一致**） |
| — | `docs/ai_rules.md` | 本文档 |

**规则**：不确定某个配置时，先查阅对应文档，而非凭空假设。

---

## 三、技术栈（重要）

**项目核心代码为纯 Java 17。** 说明：
- 实际界面全部用 `findViewById`（**不用 ViewBinding**，尽管 build.gradle 开了该开关）。
- `src/main/kotlin/com/oilquiz/app/compose/` 存在 **Compose 遗留示例**（ComposeTheme.kt/ComposeExampleActivity.kt/HybridExampleActivity.kt），但**未在 manifest 注册**，属遗留/示例。
- **新代码禁止引入 Kotlin/Compose/Coroutines**（除非开发者明确要求）。

| 类别 | 技术 |
|------|------|
| 语言（核心） | **Java 17** |
| UI | AndroidX + Material3 + RecyclerView + `findViewById` |
| 注入 | **Dagger Hilt**（`@AndroidEntryPoint`/`@Inject`/`@HiltViewModel`/`@Module`） |
| 异步 | **ExecutorService + LiveData + CompletableFuture** |
| 本地 LLM | llama.cpp (JNI) + GGUF |
| GPU | OpenCL（Adreno）+ batch warmup + flash attention |
| 数据库 | Room（`AppDatabase`，版本 24）+ SQLite |
| 网络 | Retrofit/OkHttp |
| Python | Chaquopy（3.10） |

> ⚠️ **新代码禁止引入 Kotlin/Compose/Coroutines/ViewBinding**。`compose` 包为遗留示例，不在 manifest 注册，不要参考或扩展。

---

## 四、架构约束（强制执行）

### 4.1 MVVM 分层

| 规则 | 说明 |
|------|------|
| **Activity 不得直接访问 Database** | 必须走 ViewModel / Manager → Repository → DAO |
| **Activity 是薄壳** | 只做 `initView()`/`initData()`/`initListener()` + 装配模块 |
| **ViewModel 用 `@HiltViewModel`** | 依赖 Hilt 注入 |
| **repository 是数据访问入口** | `com.oilquiz.app.repository.*` |
| **View 层不含业务逻辑** | 业务逻辑放 Manager/ViewModel |

### 4.2 依赖注入

| 规则 | 说明 |
|------|------|
| 依赖通过 Hilt `@Inject` 注入 | 禁止手动 `new` 服务（工厂/Singleton 例外） |
| Module 标注 `@InstallIn` | 指定 Component |
| 接口用 `@Binds` | 绑定实现类 |

---

## 五、包目录使用规则（真实结构）

项目顶层包 `com.oilquiz.app`，必须把代码放入正确子包：

### 5.1 核心包

| 包路径 | 允许的内容 | 禁止的内容 |
|------|------|------|
| `ui.activity` | Activity | ViewModel/Repository/直接数据操作 |
| `ui.adapter` | RecyclerView Adapter | 点击事件中的业务逻辑 |
| `ui.fragment` | Fragment | 同 Activity 限制 |
| `model` | 数据模型（Question/ChatMessage 等） | 数据库操作 |
| `database` | Room Entity + DAO（`AppDatabase`） | 业务逻辑 |
| `repository` | 数据访问抽象层 | UI 逻辑 |
| `manager` | 业务管理器 | 直接网络请求 |
| `viewmodel` | ViewModel | Context/View |
| `di` | Hilt Module | 业务逻辑 |
| `resource` | 资源管理 | UI 渲染 |
| `util` | 静态工具方法 | 有状态工具 |
| `infra` | 基础设施（AppLogger 等） | 业务 |
| `webview` | WebView 封装 | 业务逻辑 |
| `weather` | 天气模块 | 非天气逻辑 |

**`ai/` 子包（28 个，完整清单见 `08-module-inventory.md`）**：

| 核心子包 | 职责 |
|---------|------|
| `ai.agent` | Agent 引擎（software/online/thinking） |
| `ai.tool` | 实现 `AITool` 的工具类 |
| `ai.chat` | 对话（coordinator/viewmodel/各模块/渲染） |
| `ai.service` | AI 服务（AIService/AgentService） |
| `ai.inference` | 推理路由 |
| `ai.model` | 模型管理 |
| `ai.gpu` | GPU 加速 |
| `ai.jni` | JNI（LlamaHelper） |
| `ai.speech` | 语音（ASR/TTS） |
| `ai.config` / `ai.refactor` | 配置 / 推理核心（AIConfig/AIInferenceCore） |
| `ai.importing` | 题库导入 |
| `ai.usage` | 用量/计费 |

其余子包（均存在）：`bridge` / `callback` / `db` / `engine` / `export` / `feature` / `intent` / `monitor` / `optimization` / `performance` / `python` / `repair` / `skill` / `stats` / `util` / `python`。

> 不确定放在哪个子包时，先查 `08-module-inventory.md` 的真实类清单，再决定。

### 5.2 包选择流程

1. 是 UI？ → `ui/` 子目录
2. 管理 UI 状态？ → `viewmodel/`
3. 数据访问？ → `repository/`
4. 数据持久化？ → `database/`
5. 依赖注入？ → `di/`
6. 纯工具函数？ → `util/`
7. 业务逻辑？ → `manager/`
8. AI 相关？ → `ai/` 子目录
9. 基础设施？ → `infra/`

---

## 六、编码规则（强制执行）

### 6.1 语言与注释

| 场景 | 语言 |
|------|------|
| 用户界面字符串 | **中文**（`strings.xml`） |
| 代码注释 | **英文** |
| 变量/方法/类名 | 英文 |
| Git 提交信息 | 中文（推荐） |
| 日志 | 英文或中英混合 |

### 6.2 基类继承

| 规则 | 说明 |
|------|------|
| Activity 继承 `BaseActivity` | 无一例外 |
| AI 工具必须实现 `AITool` 接口 | 项目里工具类直接 `implements AITool`（无 extends BaseAITool 的） |
| ViewModel 用 `@HiltViewModel` | Hilt 要求 |

### 6.3 异步与线程

| 规则 | 说明 |
|------|------|
| **禁止 AsyncTask** | 已弃用 |
| 用 `ExecutorService` + `CompletableFuture` | 项目标准（非 Coroutines） |
| 禁止主线程 IO | 放 Executor 线程 |
| UI 更新用 `runOnUiThread` / `Handler.post` / `LiveData` | 不要跨线程改 View |

### 6.4 状态管理

| 场景 | API |
|------|------|
| Activity 观察数据 | `LiveData` + `observe(lifecycleOwner)` |
| 一次性事件 | 回调接口 / LiveData |

### 6.5 资源

| 规则 | 说明 |
|------|------|
| 字符串不硬编码 | 定义在 `strings.xml` |
| 颜色不硬编码 | 定义在 `colors.xml` |
| 尺寸用 `dimens.xml` | |
| 图片复用已有资源 | 不复制 |

### 6.6 日志约定

| 规则 | 说明 |
|------|------|
| AI 层 | 用 `com.oilquiz.app.util.AILogger`（`i/e/w/d(tag, msg)`） |
| 基础设施 | 用 `com.oilquiz.app.infra.AppLogger`（`v/d/i/w/e(tag, msg)`） |
| TAG 常量 | `private static final String TAG = "类名"` |
| 关键路径打 info | 异常打 e（带异常对象） |
| 不打印敏感 | 不打印 API Key/模型路径/用户隐私 |
| 不 println | 用 Logger，不用 `System.out.println` |

### 6.7 Python（Chaquopy）规则

- Python 代码放 `com.oilquiz.app.ai.python`（`PythonToolManager`/`Python*Tool` 等），不散落。
- Python 工具类也实现 `AITool`（如 `PythonCalculateTool`/`PythonChartTool`）。
- 用 `PythonToolManager` 统一管理；不直接调 Python 解释器。
- 依赖用 Chaquopy `pip`（已在 build.gradle 配置），不擅自加包。

---

## 七、构建配置（真实值）

**固定值，不可改**：

| 配置 | 值 |
|------|-----|
| minSdk | 31 (Android 12) |
| targetSdk / compileSdk | 34 (Android 14) |
| Java | 17 |
| Gradle | 8.13 |
| AGP | 8.4.0 |
| JDK | 17 |
| ndk | abiFilters `arm64-v8a`, `x86_64` |

**构建脚本**：`build.gradle`（根项目，非 `build.gradle.kts`，非 app 子目录）。

---

## 八、AI 模块专项规则

### 8.1 新增 AI 工具流程

1. 在 `ai/tool/` 创建类，**实现 `AITool` 接口**（项目工具类直接 `implements AITool`）。
2. 实现 `getName()`/`getDescription()`/`execute(Map<String,Object>)`。
3. 在 `AIToolManager` 注册（`registerDynamicTool` 或加入工具列表）。
4. GPU 代码放 `ai/gpu/`；模型代码放 `ai/model/`。

真实接口：
```java
// ai/tool/AITool.java
public interface AITool {
    String getName();
    String getDescription();
    AIToolResult execute(Map<String, Object> parameters);
    Map<String, String> getParameterDescriptions();   // 参数描述（schema）
}

// ai/tool/BaseAITool.java - 抽象类（实现骨架；真实工具直接 implements AITool）
public abstract class BaseAITool implements AITool {
    public BaseAITool(String toolName, String description) { ... }
    public boolean canHandle(String input) { ... }   // 是否匹配
}

// ai/tool/AIToolManager.java
public void registerDynamicTool(AITool tool);
```

### 8.2 AI 模块目录边界

| 目录 | 可以有 | 不能有 |
|------|------|------|
| `ai.agent` | 引擎/编排 | UI/工具 |
| `ai.tool` | 实现 AITool 的工具 | Agent 调度 |
| `ai.gpu` | OpenCL/调优 | 推理逻辑 |
| `ai.model` | 模型管理 | GPU |

### 8.3 工具调用链

```
模型输出 <tool_call> → AgentLoopEngine 解析
    → AIToolManager.executeTool(name, params)
    → AITool.execute(Map) → AIToolResult
```

---

## 九、AI Agent 架构规则

1. **单循环引擎**：禁止"多轮 LLM 编排"（意图→分解→思考→整合多次 LLM 调用会破坏 KV cache 导致崩溃）。
2. **本地/在线双路由**：`AgentChatHandler.startAgentLoop()` 用 `useLocalAgent` 判断。
3. **动态工具注入**：工具不常驻 prompt，按关键词+检索注入。

---

## 十、常见违规示例

### 10.1 Activity 直接访问数据库（错误）

```java
// ❌ 错误：Activity 注入并使用 DAO
public class QuizActivity extends BaseActivity {
    @Inject QuestionDao questionDao;
    private void load() { questionDao.getQuestions(); }
}
```

正确：通过 Manager/ViewHolder → Repository → DAO。

### 10.2 多轮 LLM 编排（错误）

```java
// ❌ 错误：多次 generate() 破坏 KV cache
String intent = llm.generate("识别意图...");
String plan = llm.generate("分解任务...");
```

正确：单循环 `AgentLoopEngine`，一次 generate 内循环工具调用。

### 10.3 在错误放包

```
// ❌ AI 工具放 ui/
ui/compose/QuestionTool.kt  // 错误

// ✅ 放 ai/tool/
ai/tool/QuestionTool.java   // 正确
```

---

## 十一、AI 编程工具工作流（可执行标准）

AI 编程工具处理任务时，必须遵循以下闭环流程。**每一步都必须实际执行，不得跳过验证。**

### 11.1 标准工作流（任何改动）

```
1. 理解任务 → 先读相关设计文档 + 相关源码（不凭空假设）
2. 定位改动 → 确认目标文件在正确包目录
3. 改代码 → 遵循本文档架构/编码规则
4. 编译验证 → 必须运行 gradlew 编译（见 11.2）
5. 跑测试 → 涉及逻辑改动必须跑相关单元测试
6. 核对 → 确认无回归、无重复代码
7. 提交 → 按 Git 规范（见 11.4）
```

### 11.2 编译/验证命令（必须执行）

```powershell
# 编译 Java（改后必须跑，确认无编译错误）
.\gradlew.bat compileDebugJavaWithJavac --offline

# 完整打包（涉及资源/Manifest 改动时）
.\gradlew.bat assembleDebug --offline

# 运行单元测试（涉及逻辑改动）
.\gradlew.bat testDebugUnitTest --offline
```

> ⚠️ Windows/gradle 下即使 `BUILD SUCCESSFUL` 偶尔返回 exit code 1（警告处理怪癖），**以输出中的 "BUILD SUCCESSFUL" 文本为准**，不要误判为失败。
> ⚠️ 编译失败时，先用 `git checkout -- <file>` 回滚到干净状态，再重新分析，而不是层层叠加修改。

### 11.3 任务类型分派

| 任务类型 | 额外要求 |
|---------|---------|
| **新功能** | 先读对应设计文档（development/）；新增 AI 工具必须实现 `AITool` 接口且注册 |
| **Bug 修复** | 先定位根因（读日志/复现），再改；改后必须跑相关测试防回归 |
| **重构** | 保持外部行为不变；分小步提交；每步编译验证 |
| **性能优化** | 改后测量（Token 统计/推理延时），不牺牲正确性 |
| **文档更新** | 与真实代码保持一致（可参考 development/ 已验证的架构描述） |

### 11.4 Git 提交规范

- 分支：`feature/agent-local`（当前）。
- 提交信息用**中文**，写明改动目的。
- 一次提交只做一件事（如功能/修复/文档）。
- 提交前 `git status` 确认只暂存预期文件。

---

## 十二、安全红线（最高优先级，不可违反）

以下安全规则**绝对不可违反**，违反即拒绝代码合并。

### 12.1 敏感信息

| 规则 | 说明 |
|------|------|
| **禁止硬编码 API Key/密钥** | 必须走 `APIKeyManager`（`saveAPIKey`/`getAPIKey`） |
| **禁止硬编码签名证书/密码** | `build.gradle` 的 `signingConfig` 由开发者配置 |
| **禁止打印敏感日志** | 不打印 API Key、模型路径、用户隐私到 logcat |
| **禁止把 URL 中的密钥写死** | 在线 API 的 key 从 `APIKeyManager` 读取 |
| **禁止提交敏感文件** | `.keystore`、签名密码文件不进 git |

```java
// ❌ 错误：硬编码 API Key
String key = "sk-xxxxxx";   // 禁止

// ✅ 正确：走 APIKeyManager
String key = APIKeyManager.getInstance(this).getAPIKey("dashscope");
```

### 12.2 权限（Runtime）

- 敏感权限（录音/相机/存储）必须运行时申请，不静默请求。
- 记录权限用途（`PERMISSION_AND_VOICE` 相关）。

### 12.3 WebView 安全（已有）

- 用 `SecurityWebViewClient`/`RedirectWebViewClient`。
- JS Bridge 校验输入；禁 `setAllowFileAccess(true)`。

---

## 十三、禁止修改锁区（绝对不可改）

**AI 工具一律不得修改/删除以下内容**，违反即回滚：

| 文件/内容 | 原因 |
|-----------|------|
| `build.gradle`（signingConfig/minSdk/targetSdk/依赖版本） | 破坏构建/签名 |
| `AndroidManifest.xml`（application 属性/Activity 注册） | 破坏启动（除非明确要求加新 Activity） |
| `AppDatabase.MIGRATIONS` + `AppDatabase.DATABASE_VERSION` | 破坏已有数据 |
| llama.cpp 源码（`src/main/cpp/llama.cpp`） | 上游依赖，不在本项目职责 |
| `native-lib.cpp` 的 GPU/模型参数（除非明确） | 影响推理稳定性 |
| 已有测试 | 应修复，不删除 |
| `gradle.properties` / `settings.gradle` | 构建配置 |

> 需要改这些时，**先向开发者说明并等待确认**，不得擅自修改。

---

## 十四、AI 特定坑（本项目易错点）

### 14.1 Agent 单循环，禁止多轮 LLM 编排

```
❌ 错误：意图识别 → 任务分解 → 思考链 → 结果整合（多次 generate()）
✅ 正确：一次 generate() 内循环，解析 <tool_call> 执行工具
```

理由：多次 `LlamaHelper.generate()` + `chatDestroy()` 破坏 chat context 的 KV cache，导致 SIGSEGV 崩溃。

### 14.2 本地/在线双路由

`AgentChatHandler.startAgentLoop()` 用 `useLocalAgent = aiConfig.isLocalAgentEnabled() && !engine.isOnlineModelActive()` 判断。**不要绕过它**直接调用底层。

### 14.3 动态工具注入

工具不常驻 prompt，按关键词 + `tool_registry` + `control_lookup` 注入。**不要把全部工具写死进 prompt**。

### 14.4 KV 缓存

Agent 多轮迭代用 `AgentKvCache` 增量缓存。**不要清空 context 重建**。

### 14.5 GPU/上下文

- 加速层数上限 `MAX_GPU_LAYERS = 64`；真机 2B 模型为 43/43 层（目标 HTP0/NPU）。
- 上下文由 `AIConfig.OptimizationMode` 决定（BALANCED=12288 默认）。
- **不要硬编码上下文大小**。

---

## 十五、非破坏性修改原则（GOLD RULE，唯一最高优先级）

> ⚠️ **本章节是全局唯一最高优先级**。任何改动不得破坏项目现有功能/构建/数据。违反即回滚。

### 15.0 优先级裁决顺序（冲突时按此排序）

当各章节的红线发生冲突时，按以下顺序裁决（**序号小者优先**）：

```
1. 非破坏性原则（本章节）—— 最优先，不得破坏任何现有功能/数据/构建
2. 安全红线（十二）—— 不得泄露敏感信息/破坏安全
3. 锁区（十三）—— 不得改 build.gradle/迁移/签名
4. 架构约束（四）—— 遵循 MVVM/薄壳
5. 其他（编码/包/工具/工作流）
```

> 示例：若"新增功能（十一 工作流）"与"不动已有代码（十五）"冲突，则**放弃新增，先保已有功能**。AI 不确定时，一律选择**最保守**（不破坏）的方案。若强行需要破坏，先向开发者说明等待确认。

### 15.1 基本原则

| 原则 | 说明 |
|------|------|
| **不动已成功能** | 只做增量/修复，不改动正在工作的代码路径 |
| **不删除已有代码** | 除非确认是死代码；否则一律保留 + 新增 |
| **不破坏编译** | 每步改后 `gradlew compileDebugJavaWithJavac --offline` 必须通过 |
| **不破坏数据** | 不改数据库 schema/迁移（`AppDatabase.MIGRATIONS`） |
| **不破坏 UI** | 不删布局控件 id（`activity_*.xml` 一删即崩） |
| **不加不必要依赖** | 不引入第三方库导致冲突 |

### 15.2 禁止的破坏性操作

| 操作 | 后果 |
|------|------|
| 删除已有方法/字段 | 可能被其他类引用 → 编译失败 |
| 修改方法签名（已有调用方） | 破坏调用链 |
| 改数据库迁移/版本 | 破坏已装用户数据 |
| 删布局控件/改 id | UI 崩溃 |
| 删掉 git 跟踪文件 | 破坏仓库 |
| 强制 push/改历史 | 破坏协作 |

### 15.3 安全修改模式

```
1. 改前: git status 确认干净,读相关代码
2. 改时: 只改必要的,不顺手改无关的
3. 改后: 立即编译验证
4. 失败: git checkout -- <file> 回滚,再重新分析
5. 成功: 才提交（一提交一主题）
```

### 15.4 文件编码与安全读写（防止文件损坏）

> ⚠️ 本项目的源码（`.java`/`.xml`/`.md`）**含中文字符**，必须用 **UTF-8（无 BOM）** 保存。
> 若用错误编码（如 GBK）读取或写入，中文字符会被损坏成一串乱码（如 `鍦ㄧ嚎`）。

#### 编码事实（真实）

| 文件类型 | 编码 | 说明 |
|---------|------|------|
| `.java` | UTF-8 无 BOM | 含中文注释 |
| `.xml`（布局/Manifest） | UTF-8 无 BOM | 含中文 UI 字符串 |
| `.md`（文档） | UTF-8 无 BOM | 含中文 |
| 配置文件 | UTF-8 | 含中文 |

#### 读写规则

| 规则 | 说明 |
|------|------|
| **读写一律 UTF-8** | 严禁用 GBK/Latin-1 等写文件 |
| **不添加 BOM** | `UTF-8 without BOM`（避免 Android/Java 解析问题） |
| **用编辑工具而非 shell 粗放写** | `> ` 重定向的编码取决于控制台，可能不是 UTF-8；用 write/edit 工具直接以 UTF-8 写 |
| **改后校验中文** | 读回文件，确认中文注释/字符串未变乱码 |
| **不混用编码转换** | 不要在程序/脚本里做 `bytes(GBK)→String→bytes(UTF-8)` 中转（会损坏） |

#### 常见错误（禁止）

```powershell
# 说明：PowerShell 的编码行为依版本而异，这里只列真正会损坏中文的做法：

# ❌ 错误1：控制台 `> ` 重定向——输出编码跟在控制台的 `[Console]::OutputEncoding`，若为 GBK 则损坏中文
Get-Content a.java > b.java

# ❌ 错误2：读取时编码不匹配——用 GBK 读 UTF-8 文件 → 乱码（如 "灏戝コ"）
# ❌ 错误3：在 GBK 控制台 echo/拼接后按默认编码写
```

```java
// ✅ 正确：用支持 UTF-8 的编辑器/工具直接读写，保存时选 UTF-8 (无 BOM)
// 或用 Invoke-WebRequest / 明确指定 UTF8 无 BOM 的编码写入
```

> 注：`Out-File -Encoding utf8` 在 Windows PowerShell 5.1 下是**带 BOM** 的 UTF-8（不损坏中文，但会加 BOM）；PowerShell 7+ 才是无 BOM。项目要求**无 BOM**，故避免用依赖默认编码的重定向。

#### 损坏检测（改后必须）

- 改完读回文件，检查中文（注释/字符串）是否仍可读。
- 若出现 `鈥`/`鐨`/`锛` 等怪字符 → 说明编码损坏，立即 `git checkout -- <file>` 恢复，重新用正确编码改。

### 15.5 修改类型判断

| 修改类型 | 是否允许 |
|---------|---------|
| 新增类/方法（加法） | ✅ 允许（放正确包） |
| 修改已有方法内部逻辑 | ⚠️ 谨慎（先确认调用方不影响） |
| 删除方法/字段/文件 | ❌ 禁止（除非确认死代码+去重） |
| 改数据库/迁移/构建配置 | ❌ 禁止（需开发者确认） |
| 重构为主 | ⚠️ 分小步 + 每步编译 |

---

## 十六、修改策略（防"屎山"）

AI 工具改代码时**必须**遵循：

| 规则 | 说明 |
|------|------|
| **小步修改** | 一次改动聚焦一件事，一次性大改易出错 |
| **改后立即编译** | 每步 `gradlew compileDebugJavaWithJavac --offline` |
| **失败即回滚** | 编译失败 `git checkout -- <file>` 回滚，再分析，不要在坏状态上叠修改 |
| **不破坏已有功能** | 保留全部功能，不因重构删功能 |
| **保留布局/控件 id** | 不删除布局控件（UI 一删即崩） |
| **新增类放正确包** | 不塞进 Activity（避免新屎山） |

---

## 十七、验收标准（完成定义）

AI 工具的改动**必须满足以下全部**才算完成：

| # | 标准 |
|---|------|
| 1 | `gradlew compileDebugJavaWithJavac --offline` 通过 |
| 2 | 涉及逻辑改动：`testDebugUnitTest --offline` 通过 |
| 3 | 无编译警告导致的运行时崩溃风险 |
| 4 | 注释清晰（英文注释 + 关键逻辑说明） |
| 5 | 命名符合约定（TAG/常量大写下划线/小驼峰） |
| 6 | 未引入新第三方依赖（如需，先说明） |
| 7 | 未触碰锁区（build.gradle/迁移/签名） |
| 8 | 未硬编码敏感信息 |
| 9 | 未引入 Kotlin/Compose/多轮 LLM 编排 |
| 10 | `git status` 只包含预期文件 |

**任一不满足 → 不算完成，继续修改直至满足。**

---

## 十八、AI 助手行为规范

### 18.1 生成前检查清单

| # | 检查项 |
|---|------|
| 1 | 文件放在正确包目录 |
| 2 | Activity 继承 `BaseActivity` |
| 3 | ViewModel 用 `@HiltViewModel` |
| 4 | 依赖通过 `@Inject`，不手动 `new` |
| 5 | 不直接访问 DAO/Database |
| 6 | 异步用 ExecutorService（非 Coroutines/AsyncTask） |
| 7 | UI 字符串用中文资源 |
| 8 | 注释用英文 |
| 9 | 不引入新第三方依赖 |
| 10 | 新增 AI 工具实现 `AITool` 接口 |
| 11 | **不修改构建配置（minSdk/Gradle/AGP）** |
| 12 | **不引入 Kotlin/Compose**（除非明确要求） |

### 18.2 禁止操作

| 操作 | 原因 |
|------|------|
| 删除/修改构建配置 | 破坏构建 |
| 修改 minSdk/targetSdk | 影响兼容性 |
| 降级依赖 | 引入 Bug |
| 引入 Kotlin/Compose | 项目是纯 Java |
| 修改数据库迁移 | 破坏已有数据 |
| 删除现有测试 | 应修复而非删除 |

---

## 十九、版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 2.1 | 2026-07-18 | 旧版 |
| 3.0 | 2026-08-29 | 按真实源码重写：纯 Java/Executor+LiveData/真实包结构/AITool 接口/构建配置 |
| 3.1 | 2026-08-29 | 新增 AI 编程工具工作流（编译/测试/提交闭环 + 任务分派 + 构建命令） |
| 4.0 | 2026-08-29 | 补齐 AI 工具 Agent 约束：安全红线、禁止修改锁区、AI 特定坑、修改策略、验收标准 |
| 4.1 | 2026-08-29 | 新增"非破坏性修改原则（GOLD RULE）"：禁止删除/改签名/改迁移/改构建配置，安全修改模式 |
| 4.2 | 2026-08-29 | 新增"文件编码与安全读写"：文件必须 UTF-8 无 BOM，禁 GBK/编码转换，防中文乱码损坏 |
| 4.3 | 2026-08-29 | 审阅修正：工具实现方式改为真实约定（实现 `AITool` 接口，而非 extends BaseAITool） |
| 4.4 | 2026-08-29 | 依据代码级审查修正：ViewBinding→findViewById、Kotlin/Compose 表述、文档清单补全 01~16、包表补全、唯一优先级裁决、getParameterDescriptions、补 Python/日志规则 |

---

*本文档优先于 AI 助手默认行为。规则与实际代码冲突时，以实际代码为准并在注释标注。*
